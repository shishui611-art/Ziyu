#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3 (expected '$2', got '$1')"; }

for hook in post-fs-data.sh post-mount.sh; do
  grep -q 'mount_backend_runtime.sh' "$ROOT/$hook" || fail "$hook bypasses the unified backend runtime"
done

new_module() {
  local path="$1"
  mkdir -p "$path/config" "$path/logs"
  printf 'Demo\n' > "$path/config/active_font.conf"
}

run_hook() {
  local path="$1" manager="$2" usable="$3" verify="$4" stage="$5" engine="${6:-hybrid-mount}" delta="${7:-0}" prepare_rc="${8:-0}" self_verify="${9:-pass}" rollback_rc="${10:-0}" hybrid_unload_rc="${11:-0}"
  MODDIR="$path" MODULE_DIR="$path" \
  LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID=backend-test-boot \
  LUOSHU_BACKEND_TEST_MANAGER="$manager" \
  LUOSHU_BACKEND_TEST_META_ENGINE="$engine" \
  LUOSHU_BACKEND_TEST_META_USABLE="$usable" \
  LUOSHU_BACKEND_TEST_MOUNT_DELTA="$delta" \
  LUOSHU_BACKEND_TEST_PREPARE_RC="$prepare_rc" \
  LUOSHU_BACKEND_TEST_META_VERIFY_RESULT="$verify" \
  LUOSHU_BACKEND_TEST_SELF_VERIFY_RESULT="$self_verify" \
  LUOSHU_BACKEND_TEST_ROLLBACK_RC="$rollback_rc" \
  LUOSHU_BACKEND_TEST_HYBRID_UNLOAD_RC="$hybrid_unload_rc" \
  bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook "$2"' _ "$ROOT" "$stage"
}

state() { sed -n "s/^$2=//p" "$1/config/mount-backend.conf" | head -n1; }

# Root manager identity prioritizes SukiSU's explicit identity over KSU env vars.
detect_root() {
  env -i PATH="$PATH" "$@" bash -c '. "$1/common/root_manager_detection.sh"; luoshu_detect_root_manager >/dev/null; printf "%s|%s|%s|%s\n" "$ROOT_MANAGER" "$ROOT_VERSION" "$ROOT_VERSION_CODE" "$ROOT_DETECTION_SOURCE"' _ "$ROOT"
}
detect_root SUKISU=1 SUKISU_VER=1.2.3 SUKISU_VER_CODE=123 KSU=1 KSU_VER=0.9.5 > "$TMP/root.txt"
assert_eq "$(cat "$TMP/root.txt")" 'SukiSU Ultra|1.2.3|123|env' 'SukiSU root identity'
assert_eq "$(detect_root APATCH=APatch APATCH_VER=1.0.0 APATCH_VER_CODE=100 KSU=1)" 'APatch|1.0.0|100|env' 'APatch env identity'
assert_eq "$(detect_root KSU=1 KSU_VER=3.2.1 KSU_VER_CODE=321)" 'KernelSU|3.2.1|321|env' 'KernelSU env identity'
assert_eq "$(detect_root KSU_SUKISU=1 KSU=1 KSU_VER=4.1.1)" 'SukiSU Ultra|4.1.1|0|env' 'SukiSU env identity'
assert_eq "$(detect_root MAGISK_VER=29.0 MAGISK_VER_CODE=29000)" 'Magisk|29.0|29000|env' 'Magisk env identity'

# A bare shared KSU directory and its ksud executable are ambiguous. The
# SukiSU-specific environment flag is tested above instead of guessing by path.
ROOT_ADB="$TMP/root-fallback"
mkdir -p "$ROOT_ADB/data/adb/ksu"
assert_eq "$(detect_root LUOSHU_ROOT_DETECT_ROOT="$ROOT_ADB")" 'unknown|unknown|0|ambiguous-ksu-family' 'ambiguous shared KSU directory'
ln -s "$(command -v bash)" "$ROOT_ADB/data/adb/ksu/ksud"
assert_eq "$(detect_root LUOSHU_ROOT_DETECT_ROOT="$ROOT_ADB")" 'unknown|unknown|0|ambiguous-ksu-family' 'shared ksud is not a KernelSU identity'
ROOT_ADB_AP="$TMP/root-apatch"
mkdir -p "$ROOT_ADB_AP/data/adb/ap"
assert_eq "$(detect_root LUOSHU_ROOT_DETECT_ROOT="$ROOT_ADB_AP")" 'APatch|unknown|0|fallback' 'APatch directory fallback'
ROOT_ADB_MAGISK="$TMP/root-magisk"
mkdir -p "$ROOT_ADB_MAGISK/data/adb/magisk"
assert_eq "$(detect_root LUOSHU_ROOT_DETECT_ROOT="$ROOT_ADB_MAGISK")" 'Magisk|unknown|0|fallback' 'Magisk directory fallback'

META_ADB="$TMP/meta-adb"
META_MODULE="$META_ADB/modules/meta-overlayfs"
mkdir -p "$META_MODULE/mnt" "$META_ADB/modules"
printf 'id=meta-overlayfs\nname=Meta OverlayFS\nmetamodule=1\n' > "$META_MODULE/module.prop"
printf '#!/system/bin/sh\nexit 0\n' > "$META_MODULE/metamount.sh"
touch "$META_MODULE/modules.img"
chmod +x "$META_MODULE/metamount.sh"
ln -s "$META_MODULE" "$META_ADB/metamodule"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$META_MODULE" MODDIR="$TMP/ziyu" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s|%s\n" "$META_ENGINE" "$META_AVAILABLE" "$META_ENABLED" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'meta-overlayfs|1|1|0' 'OverlayFS detected but not transactionally unloadable'
META_SKIP_MODULE="$TMP/ziyu-skip"
mkdir -p "$META_SKIP_MODULE/config"
: > "$META_SKIP_MODULE/skip_mount"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$META_MODULE" MODDIR="$META_SKIP_MODULE" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s\n" "$META_ENGINE" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'meta-overlayfs|0' 'Meta does not select a module excluded by skip_mount'
: > "$META_SKIP_MODULE/config/self-mount-owned"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$META_MODULE" MODDIR="$META_SKIP_MODULE" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s\n" "$META_ENGINE" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'meta-overlayfs|0' 'Ziyu-owned skip marker does not make OverlayFS unloadable'
touch "$META_MODULE/disable"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$META_MODULE" MODDIR="$TMP/ziyu" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s|%s\n" "$META_ENGINE" "$META_AVAILABLE" "$META_ENABLED" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'meta-overlayfs|1|0|0' 'disabled Meta detection'
rm -f "$META_MODULE/disable"

NO_META_ROOT="$TMP/no-meta-adb"
mkdir -p "$NO_META_ROOT/modules"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$NO_META_ROOT" MODDIR="$TMP/ziyu" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s|%s\n" "$META_ENGINE" "$META_AVAILABLE" "$META_ENABLED" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'none|0|0|0' 'no Meta installed'

check_meta_fixture() {
  local engine="$1" module="$2" usable="$3" status
  status=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$module" \
    MODDIR="$TMP/ziyu" ROOT_MANAGER=KernelSU \
    bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s|%s|%s\n" "$META_ENGINE" "$META_INSTALLED" "$META_ENABLED" "$META_AVAILABLE" "$META_USABLE"' _ "$ROOT")
  assert_eq "$status" "$engine|1|1|1|$usable" "$engine active detection"
}

# Mountify is an enabled backend only when it has a runner and its config
# actually includes this module. Hybrid Mount also needs an active module rule.
MOUNTIFY="$META_ADB/modules/mountify"
mkdir -p "$MOUNTIFY"
printf 'id=mountify\nname=Mountify\n' > "$MOUNTIFY/module.prop"
printf '#!/system/bin/sh\nexit 0\n' > "$MOUNTIFY/post-fs-data.sh"
chmod +x "$MOUNTIFY/post-fs-data.sh"
printf 'mountify_mounts=2\n' > "$MOUNTIFY/config.sh"
check_meta_fixture mountify "$MOUNTIFY" 0

HYBRID="$META_ADB/modules/meta-hybrid_mount"
mkdir -p "$HYBRID"
printf 'id=meta-hybrid_mount\nname=Hybrid Mount\nmetamodule=1\n' > "$HYBRID/module.prop"
printf '#!/system/bin/sh\nexit 0\n' > "$HYBRID/post-fs-data.sh"
chmod +x "$HYBRID/post-fs-data.sh"
cat > "$HYBRID/hybrid-mount" <<'SH'
#!/usr/bin/env bash
[ "$*" = 'runtime status' ] || exit 2
printf '{"supported":false,"reason":"boot ledger not ready","modules":[]}\n'
SH
chmod +x "$HYBRID/hybrid-mount"
printf '[rules.LuoShu]\ndefault_mode="overlay"\n' > "$HYBRID/config.toml"
check_meta_fixture hybrid-mount "$HYBRID" 0
printf '[rules.LuoShu]\ndefault_mode="vfs"\n' > "$HYBRID/config.toml"
check_meta_fixture hybrid-mount "$HYBRID" 1
printf '[rules.LuoShu]\ndefault_mode="vfs"\n\n[rules.LuoShu.paths]\n"system/fonts/Latin.ttf"="magic"\n' > "$HYBRID/config.toml"
check_meta_fixture hybrid-mount "$HYBRID" 0

MAGIC="$META_ADB/modules/magic_mount_rs"
mkdir -p "$MAGIC"
printf 'id=magic_mount_rs\nname=Magic Mount RS\nmetamodule=1\n' > "$MAGIC/module.prop"
printf '#!/system/bin/sh\nexit 0\n' > "$MAGIC/meta-mm-rs"
chmod +x "$MAGIC/meta-mm-rs"
check_meta_fixture magic-mount "$MAGIC" 0

# A Magisk installation may contain an enabled but inactive KSU metamodule
# alongside Mountify. The active Mountify config must win over that stale install.
rm -rf "$META_ADB/metamodule"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" MODDIR="$TMP/ziyu" ROOT_MANAGER=Magisk \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s\n" "$META_ENGINE" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'mountify|0' 'Magisk Mountify is detected but cannot safely unload LuoShu'

# Case 1: Magisk without Meta selects and activates self at post-fs-data.
M1="$TMP/magisk-self"; new_module "$M1"
run_hook "$M1" Magisk 0 pass post-fs-data
assert_eq "$(state "$M1" selected_backend)" self 'case 1 selected backend'
assert_eq "$(state "$M1" active_backend)" self 'case 1 active backend'
[ -f "$M1/config/test-self-mounted" ] || fail 'case 1 did not run self mount'

# Case 2: KernelSU with a usable pure-VFS Meta route prepares at post-fs-data,
# then commits after the metamodule mount stage; self must never run in parallel.
M2="$TMP/ksu-meta-primary"; new_module "$M2"
run_hook "$M2" KernelSU 1 pass post-fs-data hybrid-mount
assert_eq "$(state "$M2" selected_backend)" meta 'case 2 selection'
[ ! -f "$M2/config/test-self-mounted" ] || fail 'case 2 ran self beside Meta'
run_hook "$M2" KernelSU 1 pass post-mount hybrid-mount
assert_eq "$(state "$M2" active_backend)" meta 'case 2 active backend'

# Case 3: KSU Meta verification failure cleans the attempt and falls back to
# self in the KSU post-mount stage.
M3="$TMP/ksu-meta-fallback"; new_module "$M3"
run_hook "$M3" KernelSU 1 pass post-fs-data hybrid-mount
run_hook "$M3" KernelSU 1 fail post-mount hybrid-mount
assert_eq "$(state "$M3" active_backend)" self 'case 3 active backend'
assert_eq "$(state "$M3" fallback_used)" 1 'case 3 fallback flag'
[ -f "$M3/config/test-self-mounted" ] || fail 'case 3 did not run fallback self mount'

# Case 4: KernelSU without Meta defers self until post-mount.
M4="$TMP/ksu-self"; new_module "$M4"
run_hook "$M4" KernelSU 0 pass post-fs-data
[ ! -f "$M4/config/test-self-mounted" ] || fail 'case 4 mounted before KSU post-mount'
run_hook "$M4" KernelSU 0 pass post-mount
assert_eq "$(state "$M4" active_backend)" self 'case 4 active backend'

# Case 5: KSU with Meta commits Meta and never runs self.
M5="$TMP/ksu-meta"; new_module "$M5"
run_hook "$M5" KernelSU 1 pass post-fs-data hybrid-mount
run_hook "$M5" KernelSU 1 pass post-mount hybrid-mount
assert_eq "$(state "$M5" active_backend)" meta 'case 5 active backend'
[ ! -f "$M5/config/test-self-mounted" ] || fail 'case 5 ran self beside Meta'

# Case 6: APatch with unusable Meta falls back to self at post-mount.
M6="$TMP/apatch-self"; new_module "$M6"
run_hook "$M6" APatch 0 pass post-fs-data
run_hook "$M6" APatch 0 pass post-mount
assert_eq "$(state "$M6" active_backend)" self 'case 6 active backend'

# If Meta preparation fails before it mounts, KernelSU must clean the Meta view,
# schedule self-mount for post-mount, and activate only that backend there.
M14="$TMP/ksu-meta-prepare-fallback"; new_module "$M14"
run_hook "$M14" KernelSU 1 pass post-fs-data hybrid-mount 0 1
assert_eq "$(state "$M14" selected_backend)" self 'case 14 fallback selected backend'
assert_eq "$(state "$M14" active_backend)" none 'case 14 waits for the correct hook'
[ ! -f "$M14/config/test-self-mounted" ] || fail 'case 14 ran KSU self-mount during post-fs-data'
run_hook "$M14" KernelSU 1 pass post-mount hybrid-mount
assert_eq "$(state "$M14" active_backend)" self 'case 14 activates self at post-mount'

# SukiSU is distinct in diagnostics but follows KSU's post-mount hook timing.
M10="$TMP/sukisu-meta"; new_module "$M10"
run_hook "$M10" 'SukiSU Ultra' 1 pass post-fs-data hybrid-mount
run_hook "$M10" 'SukiSU Ultra' 1 pass post-mount hybrid-mount
assert_eq "$(state "$M10" root_manager)" 'SukiSU Ultra' 'SukiSU root identity in state'
assert_eq "$(state "$M10" active_backend)" meta 'SukiSU meta backend'
[ ! -f "$M10/config/test-self-mounted" ] || fail 'SukiSU ran self beside Meta'

# If Meta changed a PID 1 target mount but verification failed, a generic
# unmount is unsafe. The runtime must stop with no Ziyu self layer, and report
# cleanup uncertainty instead of pretending fallback succeeded.
M11="$TMP/meta-cleanup-unsafe"; new_module "$M11"
run_hook "$M11" KernelSU 1 pass post-fs-data
if run_hook "$M11" KernelSU 1 fail post-mount meta-overlayfs 1; then
  fail 'case 11 fell through after unsafe Meta cleanup'
fi
assert_eq "$(state "$M11" active_backend)" none 'case 11 active backend remains uncommitted'
assert_eq "$(state "$M11" backend_conflict)" 0 'case 11 is cleanup uncertainty, not a confirmed dual backend'
assert_eq "$(state "$M11" last_error)" 'font-route-verification-failed;meta-cleanup-not-safe' 'case 11 cleanup error'
[ ! -f "$M11/config/test-self-mounted" ] || fail 'case 11 layered self-mount over an unverified Meta mount'

# Hybrid Mount unload failure is unsafe: an unchanged mountinfo cannot prove
# that VFS rules were removed, so self-mount must remain blocked.
M17="$TMP/hybrid-vfs-cleanup-unsafe"; new_module "$M17"
run_hook "$M17" KernelSU 1 pass post-fs-data hybrid-mount
if run_hook "$M17" KernelSU 1 fail post-mount hybrid-mount 0 0 pass 0 2; then
  fail 'case 17 fell through after Hybrid Mount runtime unload failed'
fi
assert_eq "$(state "$M17" active_backend)" none 'case 17 active backend remains uncommitted'
assert_eq "$(state "$M17" last_error)" 'font-route-verification-failed;meta-cleanup-not-safe' 'case 17 cleanup error'
[ ! -f "$M17/config/test-self-mounted" ] || fail 'case 17 layered self-mount over unverified Hybrid state'

# An unrecognized Root provider is fail-safe: it must not start either backend.
M12="$TMP/unknown-root"; new_module "$M12"
if run_hook "$M12" unknown 0 pass post-fs-data; then
  fail 'case 12 attempted mount under an unknown Root provider'
fi
assert_eq "$(state "$M12" selected_backend)" none 'case 12 selected backend'
assert_eq "$(state "$M12" active_backend)" none 'case 12 active backend'

# With the ROM default selected, no custom font route is expected; report that
# explicitly rather than claiming XML/CJK/Latin/Digit were verified.
M13="$TMP/default-font"; new_module "$M13"
printf 'default\n' > "$M13/config/active_font.conf"
run_hook "$M13" Magisk 0 pass post-fs-data
assert_eq "$(state "$M13" verification)" not-applicable 'default font route status'
grep -q 'route check is not applicable' "$M13/logs/mount-backend.log" || fail 'default font route was reported as fully verified'

# A failed self-route verification must roll its own mount transaction back and
# never commit active_backend=self.
M15="$TMP/self-verify-rollback"; new_module "$M15"
if run_hook "$M15" Magisk 0 pass post-fs-data meta-overlayfs 0 0 fail; then
  fail 'case 15 accepted a failed self font-route verification'
fi
assert_eq "$(state "$M15" active_backend)" none 'case 15 active backend after rollback'
assert_eq "$(state "$M15" verification)" failed 'case 15 verification state'
[ -f "$M15/config/test-self-rollback" ] || fail 'case 15 skipped the rollback transaction'

M16="$TMP/self-rollback-failed"; new_module "$M16"
if run_hook "$M16" Magisk 0 pass post-fs-data meta-overlayfs 0 0 fail 1; then
  fail 'case 16 accepted a failed self rollback'
fi
assert_eq "$(state "$M16" active_backend)" none 'case 16 did not commit after rollback failure'
assert_eq "$(state "$M16" last_error)" 'font-route-verification-failed;rollback-failed' 'case 16 rollback failure detail'
[ ! -f "$M16/config/test-self-rollback" ] || fail 'case 16 reported rollback success when rollback failed'

# A successful rollback command is not enough: the PID 1 namespace must no
# longer expose any target recorded by the self-mount transaction.
M20="$TMP/self-rollback-readback-failed"; new_module "$M20"
if MODDIR="$M20" MODULE_DIR="$M20" LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID=backend-test-boot LUOSHU_BACKEND_TEST_MANAGER=Magisk \
  LUOSHU_BACKEND_TEST_META_ENGINE=meta-overlayfs LUOSHU_BACKEND_TEST_META_USABLE=0 \
  LUOSHU_BACKEND_TEST_SELF_VERIFY_RESULT=fail LUOSHU_BACKEND_TEST_ROLLBACK_RC=0 \
  LUOSHU_BACKEND_TEST_ROLLBACK_STILL_ACTIVE=1 \
  bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT"; then
  fail 'case 20 accepted a successful rollback command without an inactive-state readback'
fi
assert_eq "$(state "$M20" active_backend)" none 'case 20 did not commit after rollback readback failure'
assert_eq "$(state "$M20" last_error)" 'font-route-verification-failed;rollback-verification-failed' 'case 20 rollback readback detail'
[ -f "$M20/config/test-self-rollback" ] || fail 'case 20 did not execute the rollback command'

# Missing ownership records are an unknown state, not proof that the main
# namespace has no Ziyu mounts.
M21="$TMP/self-target-state-unknown"; new_module "$M21"
mkdir -p "$M21/common" "$M21/self-state" "$M21/universal-state"
cp "$ROOT/common/mount_backend_runtime.sh" "$M21/common/"
printf 'boot_id=backend-test-boot\nactive_backend=self\n' > "$M21/config/mount-backend.conf"
M21_RESULT=$(MODDIR="$M21" MODULE_DIR="$M21" LUOSHU_BACKEND_TEST_BOOT_ID=backend-test-boot \
  LUOSHU_SELF_MOUNT_STATE_ROOT="$M21/self-state" \
  LUOSHU_UNIVERSAL_MOUNT_STATE_ROOT="$M21/universal-state" \
  LUOSHU_FONT_VERIFY_MOUNTINFO="$M21/mountinfo" \
  bash -c '. "$1/common/mount_backend_runtime.sh"; _lbr_self_mount_targets_active; printf "%s\n" "$?"' _ "$M21")
assert_eq "$M21_RESULT" 2 'case 21 treats missing self-mount ownership records as unknown'

# A stale active marker from a previous physical boot cannot describe live
# mounts in this boot; the selector should be allowed to start cleanly.
printf 'boot_id=previous-boot\nactive_backend=self\n' > "$M21/config/mount-backend.conf"
M22_RESULT=$(MODDIR="$M21" MODULE_DIR="$M21" LUOSHU_BACKEND_TEST_BOOT_ID=backend-test-boot \
  LUOSHU_SELF_MOUNT_STATE_ROOT="$M21/self-state" \
  LUOSHU_UNIVERSAL_MOUNT_STATE_ROOT="$M21/universal-state" \
  LUOSHU_FONT_VERIFY_MOUNTINFO="$M21/mountinfo" \
  bash -c '. "$1/common/mount_backend_runtime.sh"; _lbr_self_mount_targets_active; printf "%s\n" "$?"' _ "$M21")
assert_eq "$M22_RESULT" 1 'case 22 ignores prior-boot backend state after a fresh kernel boot'

# Complete the manager/backend matrix: Magisk verifies from service because it
# has no post-mount hook; APatch follows the KSU-family post-mount handoff.
M23="$TMP/magisk-meta"; new_module "$M23"
run_hook "$M23" Magisk 1 pass post-fs-data hybrid-mount
assert_eq "$(state "$M23" active_backend)" none 'case 23 leaves Magisk Meta pending until its mount stage'
[ ! -f "$M23/config/test-self-mounted" ] || fail 'case 23 ran self beside Magisk Meta'
run_hook "$M23" Magisk 1 pass service hybrid-mount
assert_eq "$(state "$M23" active_backend)" meta 'case 23 commits Magisk Meta after service verification'

M24="$TMP/magisk-meta-fallback"; new_module "$M24"
run_hook "$M24" Magisk 1 pass post-fs-data hybrid-mount
run_hook "$M24" Magisk 1 fail service hybrid-mount
assert_eq "$(state "$M24" active_backend)" self 'case 24 falls back to Magisk self-mount after scoped Meta unload'
assert_eq "$(state "$M24" fallback_used)" 1 'case 24 records Magisk fallback'

M25="$TMP/apatch-meta"; new_module "$M25"
run_hook "$M25" APatch 1 pass post-fs-data hybrid-mount
run_hook "$M25" APatch 1 pass post-mount hybrid-mount
assert_eq "$(state "$M25" active_backend)" meta 'case 25 commits APatch Meta after post-mount verification'
[ ! -f "$M25/config/test-self-mounted" ] || fail 'case 25 ran self beside APatch Meta'

M26="$TMP/sukisu-self"; new_module "$M26"
run_hook "$M26" 'SukiSU Ultra' 0 pass post-fs-data
[ ! -f "$M26/config/test-self-mounted" ] || fail 'case 26 mounted before SukiSU post-mount'
run_hook "$M26" 'SukiSU Ultra' 0 pass post-mount
assert_eq "$(state "$M26" active_backend)" self 'case 26 uses SukiSU self-mount without Meta'

# Case 9: a committed opposite backend blocks a second backend and exposes a
# conflict status before another mount is attempted.
M9="$TMP/conflict"; new_module "$M9"
printf 'active_backend=meta\n' > "$M9/config/mount-backend.conf"
MODDIR="$M9" MODULE_DIR="$M9" LUOSHU_BACKEND_TEST_MODE=1 \
  bash -c '. "$1/common/mount_backend_runtime.sh"; if luoshu_assert_single_mount_backend self; then exit 1; fi; [ "${BACKEND_CONFLICT:-0}" = 1 ]' _ "$ROOT" \
  || fail 'case 9 allowed both backends to commit or did not set BACKEND_CONFLICT'
assert_eq "$(state "$M9" backend_conflict)" 1 'case 9 persisted backend conflict'
assert_eq "$(state "$M9" active_backend)" meta 'case 9 preserved the prior committed backend'

# Detect a live self-mount from its transaction record and repair it before the
# Meta backend is allowed to proceed. A failed rollback must fail closed.
M18="$TMP/live-self-meta-repair"; new_module "$M18"
: > "$M18/config/test-self-mounted"
MODDIR="$M18" MODULE_DIR="$M18" LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID=backend-test-boot LUOSHU_BACKEND_TEST_MANAGER=KernelSU \
  LUOSHU_BACKEND_TEST_META_ENGINE=hybrid-mount LUOSHU_BACKEND_TEST_META_USABLE=1 \
  LUOSHU_BACKEND_TEST_SELF_ROLLBACK_RC=0 \
  bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT" \
  || fail 'case 18 could not repair a recorded active self-mount'
assert_eq "$(state "$M18" backend_conflict)" 1 'case 18 recorded the detected backend conflict'
[ -f "$M18/config/test-self-rollback" ] || fail 'case 18 did not roll back the active self-mount'
run_hook "$M18" KernelSU 1 pass post-mount hybrid-mount
assert_eq "$(state "$M18" active_backend)" meta 'case 18 commits Meta only after self rollback'
assert_eq "$(state "$M18" backend_conflict)" 1 'case 18 retains the repaired conflict in boot state'

M19="$TMP/live-self-meta-repair-fails"; new_module "$M19"
: > "$M19/config/test-self-mounted"
if MODDIR="$M19" MODULE_DIR="$M19" LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID=backend-test-boot LUOSHU_BACKEND_TEST_MANAGER=KernelSU \
  LUOSHU_BACKEND_TEST_META_ENGINE=hybrid-mount LUOSHU_BACKEND_TEST_META_USABLE=1 \
  LUOSHU_BACKEND_TEST_SELF_ROLLBACK_RC=1 \
  bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT"; then
  fail 'case 19 accepted a second backend after self rollback failed'
fi
assert_eq "$(state "$M19" backend_conflict)" 1 'case 19 persisted the unresolved backend conflict'
[ ! -f "$M19/config/test-self-rollback" ] || fail 'case 19 reported a successful self rollback'

printf 'mount backend orchestration cases 1-6, 9-26, Root manager detection and four Meta engine detectors passed\n'
