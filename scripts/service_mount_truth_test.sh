#!/bin/sh
# Exercise the actual legacy service and shared verifier against PID 1 fixtures.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
MOD="$TMP/module"
PID1="$TMP/pid1"
SU_VIEW="$TMP/su"
mkdir -p "$MOD/common" "$MOD/config" "$MOD/logs" "$TMP/bin" \
    "$MOD/.luoshu-payload/system/fonts" "$MOD/.luoshu-payload/system/etc"
cp "$ROOT/service.sh" "$MOD/service.sh"
cp "$ROOT/common/device_font_load_verify.sh" "$ROOT/common/font_route_verify.py" \
    "$ROOT/common/font_switch_lock.sh" "$MOD/common/"
printf 'mix\n' > "$MOD/config/active_font.conf"
printf 'font=mix\n' > "$MOD/config/font_runtime_legacy_v14_4.conf"
printf 'state=mounted\n' > "$MOD/config/self-mount.conf"
printf 'state=confirmed\nfont=mix\n' > "$MOD/config/font-payload-boot.conf"
printf '<familyset><family name="NotoSansCJKsc"><font>LuoShuCJK.ttf</font></family></familyset>\n' \
    > "$MOD/.luoshu-payload/system/etc/fonts.xml"
printf 'cjk-fixture\n' > "$MOD/.luoshu-payload/system/fonts/LuoShuCJK.ttf"
for rel in system/etc/fonts.xml system/fonts/LuoShuCJK.ttf; do
    printf '%s|%s\n' "$rel" "$(sha256sum "$MOD/.luoshu-payload/$rel" | awk '{print $1}')"
done > "$MOD/config/font-payload-manifest.conf"
printf 'system/fonts/LuoShuCJK.ttf|system/etc/fonts.xml|400|NotoSansCJKsc\n' > "$MOD/config/font-target-aliases.conf"
mkdir -p "$PID1" "$SU_VIEW"
cp -R "$MOD/.luoshu-payload/system" "$PID1/"
cp -R "$MOD/.luoshu-payload/system" "$SU_VIEW/"
chmod -R a+r "$PID1" "$SU_VIEW"
printf '33 22 0:29 / /system rw,relatime - ext4 /dev/block/system rw\n' > "$TMP/mountinfo"
cat > "$TMP/bin/getprop" <<'GETPROP'
#!/bin/sh
[ "$1" = sys.boot_completed ] && printf '1\n'
GETPROP
chmod +x "$TMP/bin/getprop"
export PATH="$TMP/bin:$PATH" MODDIR="$MOD" MODULE_DIR="$MOD"
export LUOSHU_BACKEND_TEST_BOOT_ID=test-boot LUOSHU_PYTHON=python3
export LUOSHU_FONT_VERIFY_VISIBLE_ROOT="$PID1" LUOSHU_FONT_VERIFY_MOUNTINFO="$TMP/mountinfo"
export LUOSHU_VISIBLE_ROOT="$SU_VIEW"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
backend() {
    printf 'schema=ziyu-mount-backend-v1\nboot_id=%s\nactive_backend=%s\nverification=%s\nlast_error=%s\n' \
        "$1" "$2" "$3" "$4" > "$MOD/config/mount-backend.conf"
}
verify_rc() {
    set +e
    sh "$MOD/common/device_font_load_verify.sh" "$1"
    RC=$?
    set -e
    [ "$RC" = "$2" ] || fail "$1 returned $RC; expected $2"
}
assert_reason() { grep -q "^reason=$1$" "$MOD/config/device-font-load-verification.conf" || fail "missing reason $1"; }
backend test-boot none failed font-route-verification-failed
verify_rc verify 1
assert_reason backend-verification-failed:font-route-verification-failed
verify_rc status 1
printf 'PASS failed backend overrides stale mounted marker and matching su font\n'
backend old-boot self passed none
verify_rc verify 2
assert_reason backend-state-from-different-boot
printf 'PASS previous boot backend success cannot confirm current boot\n'
backend test-boot self pending none
verify_rc verify 2
assert_reason backend-verification-pending
printf 'PASS pending backend blocks legacy mounted confirmation\n'
backend test-boot self passed none
verify_rc status 2
assert_reason backend-awaiting-pid1-route-verification
verify_rc verify 0
assert_reason backend-pid1-route-verified:self
verify_rc status 0
grep -q '^bootId=test-boot$' "$MOD/config/device-font-load-verification.conf" || fail 'current boot missing'
printf 'PASS actual PID 1 route and backend confirmation agree\n'
printf 'backend_conflict=1\n' >> "$MOD/config/mount-backend.conf"
verify_rc verify 1
assert_reason backend-conflict
printf 'PASS conflicting backends cannot be shown as verified\n'
backend test-boot self passed none
printf 'factory-cjk\n' > "$PID1/system/fonts/LuoShuCJK.ttf"
verify_rc verify 1
assert_reason backend-pid1-route-verification-failed
verify_rc status 1
assert_reason backend-pid1-route-verification-failed
printf 'PASS matching su view does not hide PID 1 hash mismatch\n'
cp "$MOD/.luoshu-payload/system/fonts/LuoShuCJK.ttf" "$PID1/system/fonts/LuoShuCJK.ttf"
rm "$PID1/system/fonts/LuoShuCJK.ttf"
verify_rc verify 1
printf 'PASS missing PID 1 CJK rejects a previously passed backend\n'
backend test-boot none failed font-route-verification-failed
sh "$MOD/service.sh"
attempt=0
while [ "$attempt" -lt 100 ]; do
    grep -q 'physical compatibility service complete' "$MOD/logs/service-legacy-v14.4.log" 2>/dev/null && break
    sleep 0.1
    attempt=$((attempt + 1))
done
grep -q 'font load FAILED' "$MOD/logs/service-legacy-v14.4.log" || fail 'legacy service hid backend failure'
! grep -q 'font load confirmed' "$MOD/logs/service-legacy-v14.4.log" || fail 'legacy service falsely confirmed'
grep -q '^state=failed$' "$MOD/config/device-font-load-verification.conf" || fail 'service overwrote failure'
printf 'PASS actual legacy service preserves backend failure\n'
rm "$MOD/common/device_font_load_verify.sh"
: > "$MOD/logs/service-legacy-v14.4.log"
sh "$MOD/service.sh"
attempt=0
while [ "$attempt" -lt 100 ]; do
    grep -q 'physical compatibility service complete' "$MOD/logs/service-legacy-v14.4.log" 2>/dev/null && break
    sleep 0.1
    attempt=$((attempt + 1))
done
assert_reason backend-load-verifier-missing
! grep -q 'font load confirmed' "$MOD/logs/service-legacy-v14.4.log" || fail 'missing helper falsely confirmed'
printf 'PASS incomplete update cannot downgrade to mounted-marker success\n'
cp "$ROOT/common/device_font_load_verify.sh" "$MOD/common/"
backend test-boot self passed none
cp "$MOD/.luoshu-payload/system/fonts/LuoShuCJK.ttf" "$PID1/system/fonts/LuoShuCJK.ttf"
: > "$MOD/logs/service-legacy-v14.4.log"
printf 'pending-reboot\n' > "$MOD/config/text_reboot_required.conf"
sh "$MOD/service.sh"
attempt=0
while [ "$attempt" -lt 100 ]; do
    grep -q 'physical compatibility service complete' "$MOD/logs/service-legacy-v14.4.log" 2>/dev/null && break
    sleep 0.1
    attempt=$((attempt + 1))
done
grep -q 'font load confirmed' "$MOD/logs/service-legacy-v14.4.log" || fail 'PID 1 success not confirmed by actual service'
assert_reason backend-pid1-route-verified:self
[ ! -f "$MOD/config/text_reboot_required.conf" ] || fail 'verified boot retained reboot marker'
printf 'PASS actual legacy service confirms only after successful PID 1 readback\n'
printf 'boot_id=test-boot\nactive_backend=self\nverification=passed\n' > "$MOD/config/mount-backend.conf"
verify_rc verify 1
assert_reason backend-state-invalid-schema
printf 'PASS malformed backend record cannot confirm an old mounted marker\n'
rm "$MOD/config/mount-backend.conf"
verify_rc verify 0
assert_reason visible-font-files-match
printf 'different-su-font\n' > "$SU_VIEW/system/fonts/LuoShuCJK.ttf"
verify_rc verify 0
assert_reason mount-active-visible-layout-differs
printf 'PASS old package without new backend state keeps compatibility boundary\n'
backend test-boot none failed font-route-verification-failed
printf 'default\n' > "$MOD/config/active_font.conf"
verify_rc verify 2
assert_reason default-font
printf 'PASS ROM default needs no custom mount verification\n'
