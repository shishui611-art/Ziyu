#!/bin/sh
# The no-adb diagnostic bundle must capture live detection, backend state and
# log tails into one shareable file, once per boot for automatic triggers.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
MOD="$TMP/module"
mkdir -p "$MOD/config" "$MOD/logs" "$MOD/system/bin" "$TMP/public"
printf 'id=LuoShu\nversion=vTest\n' > "$MOD/module.prop"
printf 'state=failed\nreason=font-route-verification-failed\n' > "$MOD/config/device-font-load-verification.conf"
printf 'active_backend=self\nverification=failed\n' > "$MOD/config/mount-backend.conf"
printf 'mix\n' > "$MOD/config/active_font.conf"
printf '[1] mount attempt ok\n[2] font load FAILED\n' > "$MOD/logs/mount-backend.log"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# 1. dump writes the bundle with live sections and config contents.
out=$(MODDIR="$MOD" LUOSHU_DIAG_BOOT_ID=boot-abc LUOSHU_DIAG_PUBLIC_DIR="$TMP/public/Ziyu/reports" \
    sh "$ROOT/common/diagnostic_bundle.sh" dump test-reason)
[ -s "$out" ] || fail 'bundle missing'
command grep -q 'report=ziyu-diagnostic-bundle-v1' "$out" || fail 'bundle header missing'
command grep -q 'reason=test-reason' "$out" || fail 'reason missing'
command grep -q 'bootId=boot-abc' "$out" || fail 'boot id missing'
command grep -q 'verification=failed' "$out" || fail 'backend state missing'
command grep -q 'font load FAILED' "$out" || fail 'log tail missing'
command grep -q '\[live root detection\]' "$out" || fail 'live root section missing'
command grep -q '\[health\]' "$out" || fail 'health section missing'
command grep -q '(health unavailable)' "$out" || fail 'missing health helper must be non-fatal'

# 2. Public copy for sharing without adb.
pub="$TMP/public/Ziyu/reports/$(basename "$out")"
[ -s "$pub" ] || fail 'public copy missing'

# 3. Automatic trigger runs once per boot and reason.
MODDIR="$MOD" LUOSHU_DIAG_BOOT_ID=boot-abc LUOSHU_DIAG_PUBLIC_DIR="$TMP/public/Ziyu/reports" \
    sh "$ROOT/common/diagnostic_bundle.sh" dump-once-per-boot boot-verify-failed >/dev/null
before=$(ls -1 "$MOD/logs/diagnostics" | command grep -c '^diag-')
MODDIR="$MOD" LUOSHU_DIAG_BOOT_ID=boot-abc LUOSHU_DIAG_PUBLIC_DIR="$TMP/public/Ziyu/reports" \
    sh "$ROOT/common/diagnostic_bundle.sh" dump-once-per-boot boot-verify-failed >/dev/null
after=$(ls -1 "$MOD/logs/diagnostics" | command grep -c '^diag-')
[ "$before" = "$after" ] || fail 'auto dump ran more than once per boot'
MODDIR="$MOD" LUOSHU_DIAG_BOOT_ID=boot-def LUOSHU_DIAG_PUBLIC_DIR="$TMP/public/Ziyu/reports" \
    sh "$ROOT/common/diagnostic_bundle.sh" dump-once-per-boot boot-verify-failed >/dev/null
final=$(ls -1 "$MOD/logs/diagnostics" | command grep -c '^diag-')
[ "$final" -gt "$after" ] || fail 'new boot must produce a new bundle'

# 4. Missing optional files never abort the dump.
rm -rf "$MOD/logs" "$MOD/config/mount-backend.conf"
out2=$(MODDIR="$MOD" LUOSHU_DIAG_BOOT_ID=boot-xyz \
    sh "$ROOT/common/diagnostic_bundle.sh" dump sparse)
[ -s "$out2" ] || fail 'sparse bundle missing'
command grep -q 'report=ziyu-diagnostic-bundle-v1' "$out2" || fail 'sparse bundle header missing'

printf 'diagnostic_bundle_test: PASS\n'
