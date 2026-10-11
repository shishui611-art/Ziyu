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
  mkdir -p "$path/config" "$path/logs" "$path/.luoshu-payload/system/fonts"
  printf 'Demo\n' > "$path/config/active_font.conf"
  printf 'test-font-payload\n' > "$path/.luoshu-payload/system/fonts/Roboto-Regular.ttf"
}

run_hook() {
  local path="$1" manager="$2" usable="$3" verify="$4" stage="$5" engine="${6:-hybrid-mount}" delta="${7:-0}" prepare_rc="${8:-0}" self_verify="${9:-pass}" rollback_rc="${10:-0}" hybrid_unload_rc="${11:-0}"
  local nomount_usable="${12:-0}" nomount_enabled="${13:-0}" nomount_rc="${14:-0}" boot_id="${15:-backend-test-boot}"
  local provider_state=absent
  [ "$usable" != 1 ] || provider_state=available
  MODDIR="$path" MODULE_DIR="$path" \
  LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID="$boot_id" \
  LUOSHU_BACKEND_TEST_MANAGER="$manager" \
  LUOSHU_BACKEND_TEST_META_ENGINE="$engine" \
  LUOSHU_BACKEND_TEST_PROVIDER_STATE="$provider_state" \
  LUOSHU_BACKEND_TEST_PROVIDER_ID="$engine" \
  LUOSHU_BACKEND_TEST_META_USABLE="$usable" \
  LUOSHU_BACKEND_TEST_NOMOUNT_USABLE="$nomount_usable" \
  LUOSHU_BACKEND_TEST_NOMOUNT_ENABLED="$nomount_enabled" \
  LUOSHU_BACKEND_TEST_NOMOUNT_RC="$nomount_rc" \
  LUOSHU_BACKEND_TEST_MOUNT_DELTA="$delta" \
  LUOSHU_BACKEND_TEST_PREPARE_RC="$prepare_rc" \
  LUOSHU_BACKEND_TEST_META_VERIFY_RESULT="$verify" \
  LUOSHU_BACKEND_TEST_EXTERNAL_VERIFY_RESULT="$verify" \
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
assert_eq "$META_STATUS" 'meta-overlayfs|1|1|1' 'OverlayFS remains a valid provider with degraded cleanup'
META_SKIP_MODULE="$TMP/ziyu-skip"
mkdir -p "$META_SKIP_MODULE/config"
: > "$META_SKIP_MODULE/skip_mount"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$META_MODULE" MODDIR="$META_SKIP_MODULE" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s\n" "$META_ENGINE" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'meta-overlayfs|0' 'Meta does not select a module excluded by skip_mount'
: > "$META_SKIP_MODULE/config/self-mount-owned"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" LUOSHU_META_TEST_ASSUME_ACTIVE=1 LUOSHU_META_TEST_ACTIVE_DIR="$META_MODULE" MODDIR="$META_SKIP_MODULE" \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s\n" "$META_ENGINE" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'meta-overlayfs|1' 'Ziyu-owned skip marker remains releasable for backend transitions'
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
check_meta_fixture mountify "$MOUNTIFY" 1

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
check_meta_fixture hybrid-mount "$HYBRID" 1
printf '[rules.LuoShu]\ndefault_mode="vfs"\n' > "$HYBRID/config.toml"
check_meta_fixture hybrid-mount "$HYBRID" 1
printf '[rules.LuoShu]\ndefault_mode="vfs"\n\n[rules.LuoShu.paths]\n"system/fonts/Latin.ttf"="magic"\n' > "$HYBRID/config.toml"
check_meta_fixture hybrid-mount "$HYBRID" 1

MAGIC="$META_ADB/modules/magic_mount_rs"
mkdir -p "$MAGIC"
printf 'id=magic_mount_rs\nname=Magic Mount RS\nmetamodule=1\n' > "$MAGIC/module.prop"
printf '#!/system/bin/sh\nexit 0\n' > "$MAGIC/meta-mm-rs"
chmod +x "$MAGIC/meta-mm-rs"
check_meta_fixture magic-mount "$MAGIC" 1

# A Magisk installation may contain an enabled but inactive KSU metamodule
# alongside Mountify. The active Mountify config must win over that stale install.
rm -rf "$META_ADB/metamodule"
META_STATUS=$(LUOSHU_META_DETECT_ROOT="$META_ADB" MODDIR="$TMP/ziyu" ROOT_MANAGER=Magisk \
  bash -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s\n" "$META_ENGINE" "$META_USABLE"' _ "$ROOT")
assert_eq "$META_STATUS" 'mountify|1' 'Magisk Mountify is detected as a selected provider'

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
assert_eq "$(state "$M2" selected_backend)" external 'case 2 selection'
[ ! -f "$M2/config/test-self-mounted" ] || fail 'case 2 ran self beside Meta'
run_hook "$M2" KernelSU 1 pass post-mount hybrid-mount
assert_eq "$(state "$M2" active_backend)" external 'case 2 active backend'

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
assert_eq "$(state "$M5" active_backend)" external 'case 5 active backend'
[ ! -f "$M5/config/test-self-mounted" ] || fail 'case 5 ran self beside Meta'

# Case 6: APatch with unusable Meta falls back to self at post-mount.
M6="$TMP/apatch-self"; new_module "$M6"
run_hook "$M6" APatch 0 pass post-fs-data
run_hook "$M6" APatch 0 pass post-mount
assert_eq "$(state "$M6" active_backend)" self 'case 6 active backend'

# If publishing the private payload for an external provider fails, KernelSU
# must select the self fallback and activate it only at the post-mount stage.
M14="$TMP/ksu-meta-prepare-fallback"; new_module "$M14"
rm -rf "$M14/.luoshu-payload"
run_hook "$M14" KernelSU 1 pass post-fs-data hybrid-mount
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
assert_eq "$(state "$M10" active_backend)" external 'SukiSU Meta backend'
[ ! -f "$M10/config/test-self-mounted" ] || fail 'SukiSU ran self beside Meta'

# If an external provider fails route verification, the runtime must still run
# the selected self fallback instead of leaving the font route unresolved.
M11="$TMP/external-route-fallback"; new_module "$M11"
run_hook "$M11" KernelSU 1 pass post-fs-data
run_hook "$M11" KernelSU 1 fail post-mount hybrid-mount 1
assert_eq "$(state "$M11" active_backend)" self 'case 11 fell back after external route verification failed'
assert_eq "$(state "$M11" fallback_used)" 1 'case 11 records external fallback'
[ -f "$M11/config/test-self-mounted" ] || fail 'case 11 did not run self fallback'

# Hybrid Mount route verification failure also proceeds to self fallback.
M17="$TMP/hybrid-route-fallback"; new_module "$M17"
run_hook "$M17" KernelSU 1 pass post-fs-data hybrid-mount
run_hook "$M17" KernelSU 1 fail post-mount hybrid-mount
assert_eq "$(state "$M17" active_backend)" self 'case 17 falls back after Hybrid Mount verification fails'
assert_eq "$(state "$M17" fallback_used)" 1 'case 17 records Hybrid Mount fallback'
[ -f "$M17/config/test-self-mounted" ] || fail 'case 17 did not run self fallback'

# An unrecognized Root manager uses the compatibility hook and Ziyu self route
# when no external provider is available.
M12="$TMP/unknown-root"; new_module "$M12"
run_hook "$M12" unknown 0 pass post-fs-data
assert_eq "$(state "$M12" selected_backend)" self 'case 12 selected compatibility self backend'
assert_eq "$(state "$M12" active_backend)" self 'case 12 activated compatibility self backend'
[ -f "$M12/config/test-self-mounted" ] || fail 'case 12 did not run the self fallback'

# With the ROM default selected, no custom font route is expected; report that
# explicitly rather than claiming XML/CJK/Latin/Digit were verified.
M13="$TMP/default-font"; new_module "$M13"
printf 'default\n' > "$M13/config/active_font.conf"
run_hook "$M13" Magisk 0 pass post-fs-data
assert_eq "$(state "$M13" verification)" not-applicable 'default font route status'
grep -q '字体路由验证不适用' "$M13/logs/mount-backend.log" || fail 'default font route was reported as fully verified'

# A failed self-route verification must roll its own mount transaction back and
# never report a verified backend.
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
assert_eq "$(state "$M16" active_backend)" self 'case 16 preserves the possibly active backend after rollback failure'
assert_eq "$(state "$M16" verification)" failed 'case 16 keeps the failed verification state'
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
assert_eq "$(state "$M20" active_backend)" self 'case 20 preserves possible residual mounts after rollback readback failure'
assert_eq "$(state "$M20" last_error)" 'font-route-verification-failed;rollback-failed' 'case 20 rollback readback detail'
[ -f "$M20/config/test-self-rollback" ] || fail 'case 20 did not execute the rollback command'

# A stale active marker from a previous physical boot cannot describe live
# mounts in this boot; the selector ignores it and records a fresh backend.
M21="$TMP/stale-backend-state"; new_module "$M21"
printf 'boot_id=previous-boot\nactive_backend=external\nselected_backend=external\n' \
  > "$M21/config/mount-backend.conf"
run_hook "$M21" Magisk 0 pass post-fs-data
assert_eq "$(state "$M21" boot_id)" backend-test-boot 'case 21 replaces prior-boot state with the current boot ID'
assert_eq "$(state "$M21" active_backend)" self 'case 21 selects a fresh self backend after reboot'
[ -f "$M21/config/test-self-mounted" ] || fail 'case 21 skipped self-mount after stale backend state'

# Complete the manager/backend matrix: Magisk verifies from service because it
# has no post-mount hook; APatch follows the KSU-family post-mount handoff.
M23="$TMP/magisk-meta"; new_module "$M23"
run_hook "$M23" Magisk 1 pass post-fs-data hybrid-mount
assert_eq "$(state "$M23" active_backend)" none 'case 23 leaves Magisk Meta pending until its mount stage'
[ ! -f "$M23/config/test-self-mounted" ] || fail 'case 23 ran self beside Magisk Meta'
run_hook "$M23" Magisk 1 pass service hybrid-mount
assert_eq "$(state "$M23" active_backend)" external 'case 23 commits Magisk Meta after service verification'

M24="$TMP/magisk-meta-fallback"; new_module "$M24"
run_hook "$M24" Magisk 1 pass post-fs-data hybrid-mount
run_hook "$M24" Magisk 1 fail service hybrid-mount
assert_eq "$(state "$M24" active_backend)" self 'case 24 falls back to Magisk self-mount after scoped Meta unload'
assert_eq "$(state "$M24" fallback_used)" 1 'case 24 records Magisk fallback'

M25="$TMP/apatch-meta"; new_module "$M25"
run_hook "$M25" APatch 1 pass post-fs-data hybrid-mount
run_hook "$M25" APatch 1 pass post-mount hybrid-mount
assert_eq "$(state "$M25" active_backend)" external 'case 25 commits APatch Meta after post-mount verification'
[ ! -f "$M25/config/test-self-mounted" ] || fail 'case 25 ran self beside APatch Meta'

M26="$TMP/sukisu-self"; new_module "$M26"
run_hook "$M26" 'SukiSU Ultra' 0 pass post-fs-data
[ ! -f "$M26/config/test-self-mounted" ] || fail 'case 26 mounted before SukiSU post-mount'
run_hook "$M26" 'SukiSU Ultra' 0 pass post-mount
assert_eq "$(state "$M26" active_backend)" self 'case 26 uses SukiSU self-mount without Meta'

# Stale NoMount capability flags cannot select an unshipped backend; the
# supported self route remains the legacy OverlayFS/bind implementation.
M27="$TMP/self-backend-handoff"; new_module "$M27"
run_hook "$M27" KernelSU 0 pass post-fs-data none 0 0 pass 0 0 1 1 0 self-backend-boot
assert_eq "$(state "$M27" selected_backend)" self 'case 27 selects self when no provider exists'
assert_eq "$(state "$M27" selected_self_backend)" overlayfs 'case 27 selects the supported OverlayFS-first self implementation'
assert_eq "$(state "$M27" active_backend)" none 'case 27 waits for the KernelSU mount hook'
[ ! -f "$M27/config/test-self-mounted" ] || fail 'case 27 mounted before post-mount'
run_hook "$M27" KernelSU 0 pass post-mount none 0 0 pass 0 0 1 1 2 self-backend-boot
assert_eq "$(state "$M27" active_backend)" self 'case 27 activates self at post-mount'
assert_eq "$(state "$M27" active_self_backend)" legacy 'case 27 records the compatibility runtime wrapper'
[ -f "$M27/config/test-self-mounted" ] || fail 'case 27 did not execute the supported self route'
[ ! -f "$M27/config/test-nomount-applied" ] || fail 'case 27 invoked the retired NoMount backend'

# A foreign skip marker blocks every self backend, and its bytes are preserved.
M30="$TMP/foreign-skip"; new_module "$M30"
printf 'user disabled this module\n' > "$M30/skip_mount"
run_hook "$M30" KernelSU 0 pass post-fs-data none 0 0 pass 0 0 1 1 0 foreign-skip-boot
[ -f "$M30/skip_mount" ] || fail 'case 30 deleted a user skip_mount marker'
[ ! -f "$M30/.ziyu_skip_mount_owned" ] || fail 'case 30 claimed a user skip_mount marker'
assert_eq "$(cat "$M30/skip_mount")" 'user disabled this module' 'case 30 preserved foreign marker content'
assert_eq "$(state "$M30" active_backend)" none 'case 30 exits without injecting a backend'

# Provider changes never hot-switch a frozen boot choice; a new boot rescans
# providers and may select the external route.
M31="$TMP/provider-choice-per-boot"; new_module "$M31"
run_hook "$M31" KernelSU 0 pass post-fs-data none 0 0 pass 0 0 0 0 0 provider-boot-1
assert_eq "$(state "$M31" selected_backend)" self 'case 31 stages self while the provider is absent'
run_hook "$M31" KernelSU 1 pass post-mount hybrid-mount 0 0 pass 0 0 0 0 0 provider-boot-1
assert_eq "$(state "$M31" selected_backend)" self 'case 31 keeps its staged choice after a provider appears'
assert_eq "$(state "$M31" active_backend)" self 'case 31 activates the frozen self choice'
run_hook "$M31" KernelSU 1 pass post-fs-data hybrid-mount 0 0 pass 0 0 0 0 0 provider-boot-2
assert_eq "$(state "$M31" selected_backend)" external 'case 31 rescans and selects the provider after reboot'
run_hook "$M31" KernelSU 1 pass post-mount hybrid-mount 0 0 pass 0 0 0 0 0 provider-boot-2
assert_eq "$(state "$M31" active_backend)" external 'case 31 commits the provider on the new boot'

# Restoring the system font must withdraw the previously published font tree
# before the provider scans modules on this boot.
M33="$TMP/restore-default-clears-provider"
new_module "$M33"
printf 'default\n' > "$M33/config/active_font.conf"
mkdir -p "$M33/system/fonts" "$M33/common" "$M33/.ziyu-state"
printf 'stale-custom-font\n' > "$M33/system/fonts/Roboto-Regular.ttf"
printf 'keep-me\n' > "$M33/common/unrelated-runtime-file"
rm -f "$M33/.luoshu-payload/system/fonts/Roboto-Regular.ttf"
printf 'boot_id=previous-boot\nroot_manager=KernelSU\nprovider_state=available\nprovider_id=hybrid-mount\nprovider_layout=nested-system\nprovider_content_root=%s\nselected_backend=external\nactive_backend=external\n' \
  "$M33" > "$M33/config/mount-backend.conf"
run_hook "$M33" KernelSU 1 pass post-fs-data hybrid-mount 0 0 pass 0 0 0 0 0 restore-default-boot
[ ! -e "$M33/system/fonts/Roboto-Regular.ttf" ] || fail 'case 33 left an old provider-published font after restoring system default'
[ -f "$M33/common/unrelated-runtime-file" ] || fail 'case 33 changed unrelated module runtime files'
[ -f "$M33/.ziyu-state/provider-published.conf" ] || fail 'case 33 did not record the cleaned legacy publication'
assert_eq "$(state "$M33" selected_backend)" none 'case 33 does not activate a backend for system default'
assert_eq "$(state "$M33" verification)" not-applicable 'case 33 keeps default-font routing not-applicable'

# The oldest provider-first state may omit both the content-root and provider
# state fields; infer only the module's own native scan tree.
M36="$TMP/restore-default-old-state"
new_module "$M36"
printf 'default\n' > "$M36/config/active_font.conf"
rm -f "$M36/.luoshu-payload/system/fonts/Roboto-Regular.ttf"
mkdir -p "$M36/system/fonts" "$M36/.ziyu-state"
printf 'stale-custom-font\n' > "$M36/system/fonts/Roboto-Regular.ttf"
printf 'boot_id=previous-boot\nprovider_id=hybrid-mount\nprovider_layout=nested-system\nselected_backend=external\nactive_backend=external\n' \
  > "$M36/config/mount-backend.conf"
run_hook "$M36" KernelSU 1 pass post-fs-data hybrid-mount 0 0 pass 0 0 0 0 0 restore-default-old-state-boot
[ ! -e "$M36/system/fonts/Roboto-Regular.ttf" ] || fail 'case 36 did not clean a legacy native provider tree without a receipt or root field'

# Current builds use the publisher receipt as the ownership proof even when the
# previous backend record has already been rotated or is absent.
M35="$TMP/restore-default-receipt"
new_module "$M35"
printf 'default\n' > "$M35/config/active_font.conf"
rm -f "$M35/.luoshu-payload/system/fonts/Roboto-Regular.ttf"
mkdir -p "$M35/system/fonts" "$M35/.ziyu-state"
printf 'stale-custom-font\n' > "$M35/system/fonts/Roboto-Regular.ttf"
printf 'boot_id=previous-boot\nlayout=nested-system\n' > "$M35/.ziyu-state/provider-published.conf"
run_hook "$M35" KernelSU 1 pass post-fs-data hybrid-mount 0 0 pass 0 0 0 0 0 restore-default-receipt-boot
[ ! -e "$M35/system/fonts/Roboto-Regular.ttf" ] || fail 'case 35 left an old receipt-owned provider font after restoring system default'

# Explicit self-mount must bypass an available provider, while still selecting
# the supported OverlayFS-first implementation.
M34="$TMP/explicit-self-mount"
new_module "$M34"
printf 'preferred_backend=self_mount\n' > "$M34/config/mount-backend-preference.conf"
run_hook "$M34" KernelSU 1 pass post-fs-data hybrid-mount 0 0 pass 0 0 0 0 0 self-mount-boot
assert_eq "$(state "$M34" selected_backend)" self 'case 34 explicit self-mount bypasses an available provider'
assert_eq "$(state "$M34" selected_self_backend)" overlayfs 'case 34 uses OverlayFS first for explicit self-mount'
assert_eq "$(state "$M34" fallback_used)" 0 'case 34 is a user selection, not an automatic fallback'
run_hook "$M34" KernelSU 1 pass post-mount hybrid-mount 0 0 pass 0 0 0 0 0 self-mount-boot
assert_eq "$(state "$M34" active_backend)" self 'case 34 activates explicit self-mount at post-mount'
[ -f "$M34/config/test-self-mounted" ] || fail 'case 34 did not execute explicit self-mount'

# The global foreign skip gate cleans an already active Ziyu backend before it
# returns, while leaving the user's marker untouched.
M32="$TMP/foreign-skip-cleans-active"; new_module "$M32"
printf 'boot_id=foreign-skip-active-boot\nactive_backend=self\nactive_self_backend=legacy\n' \
  > "$M32/config/mount-backend.conf"
printf 'user disabled this module\n' > "$M32/skip_mount"
MODDIR="$M32" MODULE_DIR="$M32" \
LUOSHU_BACKEND_TEST_MODE=1 \
LUOSHU_BACKEND_TEST_BOOT_ID=foreign-skip-active-boot \
LUOSHU_BACKEND_TEST_MANAGER=KernelSU \
LUOSHU_BACKEND_TEST_META_ENGINE=none \
LUOSHU_BACKEND_TEST_META_USABLE=0 \
bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook post-fs-data' _ "$ROOT"
[ -f "$M32/config/test-self-rollback" ] || fail 'case 32 did not clean Ziyu legacy backend state'
[ -f "$M32/skip_mount" ] || fail 'case 32 removed the user skip marker'
assert_eq "$(state "$M32" active_backend)" none 'case 32 records that no backend remains active'
assert_eq "$(state "$M32" verification)" not-applicable 'case 32 does not run route verification when foreign skip blocks mounting'
assert_eq "$(state "$M32" last_error)" foreign-skip-mount 'case 32 reports the foreign skip gate'

printf 'mount backend orchestration regression checks, Root manager detection and four Meta engine detectors passed\n'
