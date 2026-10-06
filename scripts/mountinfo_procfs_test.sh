#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MODDIR="$TMP/module"
mkdir -p "$MODDIR/config"
LUOSHU_BACKEND_TEST_BOOT_ID=procfs-regression
. "$ROOT/common/mount_backend_runtime.sh"
printf '1 0 0:1 / /system ro - ext4 /dev/system ro\n' > "$TMP/mountinfo"
LUOSHU_FONT_VERIFY_MOUNTINFO="$TMP/mountinfo"
# Model procfs stat size=0 while its contents remain readable. The shell's
# builtin [ otherwise gives a regular fixture its nonzero disk-file size.
eval '[() { if test "$#" = 3 && test "$1" = -s && test "$2" = "$LUOSHU_FONT_VERIFY_MOUNTINFO"; then return 1; fi; command [ "$@"; }'
_lbr_snapshot_mountinfo || { echo 'FAIL: zero-stat-size readable mountinfo rejected'; exit 1; }
cmp "$TMP/mountinfo" "$MODDIR/config/.mount-backend-before.procfs-regression"
if _lbr_meta_mount_delta; then echo 'FAIL: unchanged mountinfo reported unsafe'; exit 1; fi
printf '2 0 0:2 / /system/fonts rw - tmpfs tmpfs rw\n' >> "$TMP/mountinfo"
_lbr_meta_mount_delta || { echo 'FAIL: actual font mount delta missed'; exit 1; }
_lbr_target_in_main_mountinfo /system/fonts || { echo 'FAIL: readable PID1 font target missed'; exit 1; }
if _lbr_target_in_main_mountinfo /system/absent; then echo 'FAIL: nonexistent target reported mounted'; exit 1; fi
echo 'PASS: procfs size=0 supports snapshot and actual delta verification'
