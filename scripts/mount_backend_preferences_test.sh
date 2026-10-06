#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
eq() { [ "$1" = "$2" ] || fail "$3: expected $2, got $1"; }
value() { sed -n "s/^$2=//p" "$1" | head -n1; }
MOD="$TMP/module"
mkdir -p "$MOD/config" "$MOD/logs"
printf 'Demo\n' > "$MOD/config/active_font.conf"
pref() { MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID=pref-boot sh "$ROOT/common/mount_backend_preferences.sh" "$@"; }
hook() {
  MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID=pref-boot LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_MANAGER=KernelSU LUOSHU_BACKEND_TEST_META_ENGINE=hybrid-mount \
  LUOSHU_BACKEND_TEST_META_USABLE=1 bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook "$2"' _ "$ROOT" "$1"
}
# Regression: an explicit self preference must override usable Meta at boot.
printf 'preferred_backend=self\n' > "$MOD/config/mount-backend-preference.conf"
hook post-fs-data
eq "$(value "$MOD/config/mount-backend.conf" selected_backend)" self 'explicit self preference'
grep -q '"bootNomountObserved":true' <(pref get) || fail 'status omitted current-boot NoMount observation'
grep -q '"bootNomountKernelUsable":false' <(pref get) || fail 'status did not report current-boot NoMount capability'
grep -q '"selectedSelfBackend":"legacy"' <(pref get) || fail 'status omitted selected self backend'
[ ! -f "$MOD/config/test-self-mounted" ] || fail 'KSU self mounted before post-mount'
hook post-mount
eq "$(value "$MOD/config/mount-backend.conf" active_backend)" self 'active self'
# Preference changes stage next boot; a later service hook retains this boot.
cp "$MOD/config/mount-backend.conf" "$TMP/active-before"
pref set meta > "$TMP/response"
grep -q '"preferredBackend":"meta"' "$TMP/response" || fail 'missing selected preference JSON'
grep -q '"rebootRequired":true' "$TMP/response" || fail 'missing reboot requirement'
cmp "$TMP/active-before" "$MOD/config/mount-backend.conf" || fail 'preference set changed active state'
hook service
eq "$(value "$MOD/config/mount-backend.conf" selected_backend)" self 'same boot freeze'
eq "$(value "$MOD/config/mount-backend.conf" preferred_backend)" self 'applied boot preference'
pref cancel > "$TMP/cancel"
eq "$(value "$MOD/config/mount-backend-preference.conf" preferred_backend)" self 'cancel restores boot preference'
grep -q '"rebootRequired":false' "$TMP/cancel" || fail 'cancel left reboot pending'
if pref set unsafe > "$TMP/invalid"; then fail 'invalid preference accepted'; fi
eq "$(value "$MOD/config/mount-backend-preference.conf" preferred_backend)" self 'invalid input preserved config'
# Explicit Meta still fails safely if capability is absent, and reports why.
MOD="$TMP/unusable"; mkdir -p "$MOD/config"; printf 'Demo\n' > "$MOD/config/active_font.conf"
pref set meta >/dev/null
MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID=pref-boot LUOSHU_BACKEND_TEST_MODE=1 \
LUOSHU_BACKEND_TEST_MANAGER=Magisk LUOSHU_BACKEND_TEST_META_ENGINE=mountify \
LUOSHU_BACKEND_TEST_META_USABLE=0 LUOSHU_BACKEND_TEST_META_REASON=mountify-has-no-module-scoped-unload \
bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT"
eq "$(value "$MOD/config/mount-backend.conf" active_backend)" self 'unusable Meta chooses self'
eq "$(value "$MOD/config/mount-backend.conf" preferred_backend)" meta 'keeps requested preference'
eq "$(value "$MOD/config/mount-backend.conf" preference_failure)" mountify-has-no-module-scoped-unload 'honest preference failure'
# Installation key choice and update migration use the real helper functions.
. "$ROOT/common/install_ui.sh"
. "$ROOT/common/mount_backend_preferences.sh"
ui_print() { printf '%s\n' "$*" >> "$TMP/install-ui"; }
mkdir -p "$TMP/bin"
cat > "$TMP/bin/getevent" <<'SH'
#!/usr/bin/env bash
printf '/dev/input/event1: EV_KEY %s DOWN\n' "$TEST_EVENT_KEY"
SH
chmod +x "$TMP/bin/getevent"
eq "$(PATH="$TMP/bin:$PATH" TEST_EVENT_KEY=KEY_VOLUMEUP luoshu_install_read_volume_key)" up 'real reader volume UP event'
eq "$(PATH="$TMP/bin:$PATH" TEST_EVENT_KEY=KEY_VOLUMEDOWN luoshu_install_read_volume_key)" down 'real reader volume DOWN event'
eq "$(BOOTMODE=false luoshu_install_read_volume_key)" unavailable 'recovery skips hardware wait'
luoshu_install_read_volume_key() { printf '%s\n' "$TEST_KEY"; }
META_ENGINE=mountify META_INSTALLED=1 META_ENABLED=1 META_USABLE=0 META_USABLE_REASON=mountify-has-no-module-scoped-unload
TEST_KEY=up
luoshu_install_choose_mount_backend self
eq "$LUOSHU_INSTALL_BACKEND_PREFERENCE" meta 'volume UP selects Meta'
TEST_KEY=down
luoshu_install_choose_mount_backend meta
eq "$LUOSHU_INSTALL_BACKEND_PREFERENCE" self 'volume DOWN selects self'
TEST_KEY=timeout
luoshu_install_choose_mount_backend meta
eq "$LUOSHU_INSTALL_BACKEND_PREFERENCE" meta 'key timeout preserves update preference'
luoshu_mount_preference_restore "$TMP/module" "$TMP/updated"
eq "$(value "$TMP/updated/config/mount-backend-preference.conf" preferred_backend)" self 'update preserves preference'
# New boot consumes the staged choice and completes Meta alone.
MOD="$TMP/new-boot"; mkdir -p "$MOD/config"; printf 'Demo\n' > "$MOD/config/active_font.conf"
pref set meta >/dev/null
hook post-fs-data
hook post-mount
eq "$(value "$MOD/config/mount-backend.conf" active_backend)" meta 'new boot applies Meta preference'
[ ! -f "$MOD/config/test-self-mounted" ] || fail 'Meta preference ran self in parallel'
# Reboot destroys previous kernel mounts; its last report is historical and
# cannot count as a live Meta layer when changing to self on the next boot.
pref set self >/dev/null
MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID=second-boot LUOSHU_BACKEND_TEST_MODE=1 \
LUOSHU_BACKEND_TEST_MANAGER=Magisk LUOSHU_BACKEND_TEST_META_USABLE=0 \
bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT" || fail 'new boot rejected historical Meta state as live conflict'
eq "$(value "$MOD/config/mount-backend.conf" active_backend)" self 'next boot switches Meta to self'
pref set meta >/dev/null
# Test-only mount markers model kernel mounts, which vanish at a full reboot.
rm -f "$MOD/config/test-self-mounted" "$MOD/config/test-self-rollback"
MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID=third-boot LUOSHU_BACKEND_TEST_MODE=1 \
LUOSHU_BACKEND_TEST_MANAGER=KernelSU LUOSHU_BACKEND_TEST_META_ENGINE=hybrid-mount LUOSHU_BACKEND_TEST_META_USABLE=1 \
bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT" || fail 'new boot rejected historical self state as live conflict'
MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID=third-boot LUOSHU_BACKEND_TEST_MODE=1 \
LUOSHU_BACKEND_TEST_MANAGER=KernelSU LUOSHU_BACKEND_TEST_META_ENGINE=hybrid-mount LUOSHU_BACKEND_TEST_META_USABLE=1 \
LUOSHU_BACKEND_TEST_META_VERIFY_RESULT=fail \
bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-mount' _ "$ROOT"
eq "$(value "$MOD/config/mount-backend.conf" active_backend)" self 'failed Meta safely falls back'
eq "$(value "$MOD/config/mount-backend.conf" preference_failure)" font-route-verification-failed 'preserved failure after successful fallback'
# OverlayFS mounts the payload; degraded Meta keeps usable=1 and only the
# cleanup capability (module-scoped unload) stays absent.
META="$TMP/adb/modules/meta-overlay"
mkdir -p "$META/mnt" "$TMP/adb/modules"
printf 'id=meta-overlayfs\nname=Meta OverlayFS\nmetamodule=1\n' > "$META/module.prop"
: > "$META/metamount.sh"
ln -s "$META" "$TMP/adb/metamodule"
status=$(MODDIR="$MOD" LUOSHU_META_DETECT_ROOT="$TMP/adb" LUOSHU_META_TEST_ACTIVE_DIR="$META" LUOSHU_META_TEST_ASSUME_ACTIVE=1 bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s" "$META_READY" "$META_USABLE" "$META_USABLE_REASON"' _ "$ROOT")
eq "$status" '1|1|overlayfs-has-no-module-scoped-unload' 'overlayfs meta is usable with degraded cleanup'
HYBRID="$TMP/adb/modules/hybrid_mount"
mkdir -p "$HYBRID"
printf 'id=hybrid_mount\nname=Hybrid Mount\nmetamodule=1\n' > "$HYBRID/module.prop"
printf '[rules.LuoShu]\ndefault_mode="vfs"\n' > "$HYBRID/config.toml"
printf '#!/usr/bin/env bash\nexit 0\n' > "$HYBRID/hybrid-mount"
chmod +x "$HYBRID/hybrid-mount"
hybrid_status() {
 MODDIR="$MOD" LUOSHU_META_DETECT_ROOT="$TMP/adb" LUOSHU_META_TEST_ACTIVE_DIR="$HYBRID" LUOSHU_META_TEST_ASSUME_ACTIVE=1 \
 bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s" "$META_USABLE" "$META_USABLE_REASON"' _ "$ROOT"
}
eq "$(hybrid_status)" '1|hybrid-runtime-api-unavailable' 'old Hybrid binary degrades to Meta without scoped unload'
cat > "$HYBRID/hybrid-mount" <<'SH'
#!/usr/bin/env bash
if [ "$*" = 'runtime status' ]; then
 printf '{"supported":false,"reason":"boot ledger not ready","modules":[]}\n'
else
 exit 2
fi
SH
eq "$(hybrid_status)" '1|hybrid-pure-vfs-scoped-unload' 'runtime API capability before provider boot readiness'
printf 'Mount backend preference staging, boot freeze, fallback, key choice and migration checks passed\n'
