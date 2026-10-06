#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t luoshu-active-state)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MOD="$TMP/module"
CFG="$MOD/config"
mkdir -p "$CFG" "$MOD/common" "$TMP/public/fonts"
cp "$ROOT/common/font_active_state.sh" "$MOD/common/font_active_state.sh"
cp "$ROOT/common/weighted_mix_task.sh" "$MOD/common/weighted_mix_task.sh"
MODDIR="$MOD"
MODULE_DIR="$MOD"
export MODDIR MODULE_DIR

printf 'Demo\n' > "$CFG/active_font.conf"
printf 'state=confirmed\nfont=Demo\n' > "$CFG/font-payload-boot.conf"
printf 'system/fonts/Demo.ttf|hash|1234\n' > "$CFG/font-payload-manifest.conf"
printf 'state=verified\nmode=mount-confirmed\nactiveFont=Demo\n' > "$CFG/device-font-load-verification.conf"
printf 'state=mounted\n' > "$CFG/self-mount.conf"

. "$ROOT/common/font_active_state.sh"
luoshu_active_payload_verified Demo
if luoshu_active_payload_verified Other; then
    echo 'different font reused verified payload' >&2
    exit 1
fi

DIRECT_RESULT=$(MODDIR="$MOD" MODULE_DIR="$MOD" LUOSHU_PUBLIC_DIR="$TMP/public" \
    sh "$ROOT/common/font_manager.sh" action switch Demo)
printf '%s\n' "$DIRECT_RESULT" | grep -q '"status":"ok"'
printf '%s\n' "$DIRECT_RESULT" | grep -q '"font":"Demo"'
printf '%s\n' "$DIRECT_RESULT" | grep -q '"reused":true'
test ! -e "$CFG/text_reboot_required.conf"

printf 'state=awaiting-explicit-apply\n' > "$CFG/font-payload-rebuild-pending.conf"
if luoshu_active_payload_verified Demo; then
    echo 'schema rebuild marker was ignored' >&2
    exit 1
fi
rm -f "$CFG/font-payload-rebuild-pending.conf"

printf 'font=Demo\n' > "$CFG/text_reboot_required.conf"
if luoshu_active_payload_verified Demo; then
    echo 'same-boot reboot marker was ignored' >&2
    exit 1
fi
rm -f "$CFG/text_reboot_required.conf"

# New selector metadata has priority over old cached green records. None of
# these queries may hash fonts or rebuild a payload merely to determine reuse.
LUOSHU_BACKEND_TEST_BOOT_ID=current-boot
export LUOSHU_BACKEND_TEST_BOOT_ID
backend_state() {
    printf 'schema=ziyu-mount-backend-v1\nboot_id=%s\nactive_backend=%s\nverification=%s\nbackend_conflict=%s\n' \
        "$1" "$2" "$3" "${4:-0}" > "$CFG/mount-backend.conf"
}
reject_reuse() {
    if luoshu_active_payload_verified Demo; then
        printf 'FAIL stale green reuse: %s\n' "$1" >&2
        exit 1
    fi
}
backend_state current-boot none failed
reject_reuse failed-backend
backend_state current-boot self pending
reject_reuse pending-backend
backend_state previous-boot self passed
reject_reuse previous-boot-backend
backend_state current-boot self passed
reject_reuse previous-boot-load-cache
printf 'state=verified\nmode=mount-verified\nactiveFont=Demo\nbootId=current-boot\nreason=backend-pid1-route-verified:self\n' \
    > "$CFG/device-font-load-verification.conf"
luoshu_active_payload_verified Demo
printf 'state=failed\n' > "$CFG/self-mount.conf"
backend_state current-boot meta passed
reject_reuse different-backend-readback
printf 'state=verified\nmode=mount-verified\nactiveFont=Demo\nbootId=current-boot\nreason=backend-pid1-route-verified:meta\n' \
    > "$CFG/device-font-load-verification.conf"
luoshu_active_payload_verified Demo
backend_state current-boot meta passed 1
reject_reuse backend-conflict
rm "$CFG/mount-backend.conf"
reject_reuse legacy-self-failure
printf 'state=mounted\n' > "$CFG/self-mount.conf"
luoshu_active_payload_verified Demo
printf 'PASS new backend boot and cached readback must agree before metadata reuse\n'

printf 'mix\n' > "$CFG/active_font.conf"
printf 'state=confirmed\nfont=mix\n' > "$CFG/font-payload-boot.conf"
printf 'state=verified\nmode=mount-confirmed\nactiveFont=mix\n' > "$CFG/device-font-load-verification.conf"
cat > "$CFG/font_mix.conf" <<'EOF_MIX'
cjk=CJK
latin=Latin
digit=Digit
cjkWeight=400
latinWeight=500
digitWeight=600
cjkAxes=wght=400
latinAxes=wght=500
digitAxes=wght=600
cjkMode=fixed
latinMode=fixed
digitMode=fixed
EOF_MIX
luoshu_mix_request_matches_active CJK Latin Digit wght=400 wght=500 wght=600 fixed fixed fixed
if luoshu_mix_request_matches_active CJK Latin Other wght=400 wght=500 wght=600 fixed fixed fixed; then
    echo 'different composite request reused active payload' >&2
    exit 1
fi

# Preparing a combination always travels through the preparation router, even
# when the current active font happens to use the same role selections.
mkdir -p "$MOD/common/legacy_v14_4"
cat >"$MOD/common/legacy_v14_4/mix_router.sh" <<'EOF_PREPARE'
#!/bin/sh
printf '{"status":"ok","data":{"result":"prepared","request":"%s"}}\n' "$*"
EOF_PREPARE
AUTO_RESULT=$(MODDIR="$MOD" MODULE_DIR="$MOD" LUOSHU_PUBLIC_DIR="$TMP/public" \
    sh "$ROOT/common/multiweight_mix_task.sh" start CJK Latin Digit \
        wght=400 wght=500 wght=600)
printf '%s\n' "$AUTO_RESULT" | grep -q '"result":"prepared"'
printf '%s\n' "$AUTO_RESULT" | grep -q 'start CJK Latin Digit wght=400 wght=500 wght=600'
test ! -e "$CFG/text_reboot_required.conf"

sh -n "$ROOT/common/font_active_state.sh"
echo 'Verified active font and identical composite requests reuse metadata only.'
