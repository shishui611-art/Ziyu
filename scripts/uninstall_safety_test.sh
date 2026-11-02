#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t ziyu-uninstall)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MODULE="$TMP/module"
DATA="$TMP/flyme-data"
PROVIDER="$TMP/provider"
BIN="$TMP/bin"
TARGET="$TMP/data-fonts-config.xml"
MOUNTINFO="$TMP/mountinfo"
mkdir -p "$MODULE/common" "$MODULE/.ziyu-runtime/compat/v227" \
    "$MODULE/config/flyme-data-original" "$MODULE/system/etc" \
    "$DATA" "$PROVIDER" "$BIN"
cp "$ROOT/uninstall.sh" "$MODULE/uninstall.sh"
cp "$ROOT/.ziyu-runtime/compat/v227/uninstall.sh" "$MODULE/.ziyu-runtime/compat/v227/uninstall.sh"
cp "$ROOT/common/private_payload.sh" "$MODULE/common/private_payload.sh"
cp "$ROOT/common/font_live_switch.sh" "$MODULE/common/font_live_switch.sh"
cp "$ROOT/common/font_live_state.sh" "$ROOT/common/legacy_identity.sh" "$MODULE/common/"
cp "$ROOT/common/font_switch_lock.sh" "$MODULE/common/font_switch_lock.sh"
cp "$ROOT/common/origin_flyme_global.sh" "$MODULE/common/origin_flyme_global.sh"
cp "$ROOT/common/font_provider_cache.sh" "$MODULE/common/font_provider_cache.sh"
printf 'id=Ziyu\nversion=v2.3-test\n' > "$MODULE/module.prop"

# Simulate Ziyu's private dynamic view mounted on the original config path.
printf '<fontConfig sanitized="true"/>\n' > "$MODULE/system/etc/.ziyu-data-fonts-config.xml"
cp "$MODULE/system/etc/.ziyu-data-fonts-config.xml" "$TARGET"
HASH=$(sha256sum "$TARGET" | awk '{print $1}')
cat > "$MODULE/config/device-font-dynamic-mount.conf" <<EOF_STATE
state=prepared
source=system/etc/.ziyu-data-fonts-config.xml
target=$TARGET
targetSha256=original
sourceSha256=$HASH
EOF_STATE
printf '101 1 0:42 / %s rw,relatime - tmpfs tmpfs rw\n' "$TARGET" > "$MOUNTINFO"
cat > "$BIN/umount" <<'EOF_UMOUNT'
#!/bin/sh
printf '%s\n' "$1" >> "$ZIYU_UMOUNT_LOG"
exit 0
EOF_UMOUNT
chmod 0755 "$BIN/umount"

# Simulate a retired provider overwrite that still has Ziyu's explicit backup.
printf 'old-provider\n' > "$PROVIDER/GoogleSans-Regular.ttf.ziyu-bak"
printf 'modified-provider\n' > "$PROVIDER/GoogleSans-Regular.ttf"

# Simulate Flyme persistent font currently replaced by Ziyu and its captured original.
dd if=/dev/zero of="$MODULE/config/flyme-data-original/flymeFont.ttf" bs=2048 count=1 2>/dev/null
printf 'original=present\n' > "$MODULE/config/flyme-data-original/state.conf"
dd if=/dev/zero of="$DATA/flymeFont.ttf" bs=3072 count=1 2>/dev/null
ORIGINAL_HASH=$(sha256sum "$MODULE/config/flyme-data-original/flymeFont.ttf" | awk '{print $1}')

PATH="$BIN:$PATH" \
ZIYU_UMOUNT_LOG="$TMP/umount.log" \
ZIYU_MOUNTINFO="$MOUNTINFO" \
ZIYU_PROVIDER_DIR="$PROVIDER" \
ZIYU_TEST_ROM=flyme \
ZIYU_FLYME_DATA_FONT_ROOT="$DATA" \
ZIYU_MODULES_DIR="$TMP/modules" \
ZIYU_MODULES_UPDATE_DIR="$TMP/modules-update" \
ZIYU_METAMODULE_MNT="$TMP/metamodule" \
ZIYU_SELF_MOUNT_STATE="$TMP/ziyu/self-mount" \
    sh "$MODULE/uninstall.sh"

grep -qx "$TARGET" "$TMP/umount.log"
test "$(cat "$PROVIDER/GoogleSans-Regular.ttf")" = old-provider
test ! -e "$PROVIDER/GoogleSans-Regular.ttf.ziyu-bak"
test "$(sha256sum "$DATA/flymeFont.ttf" | awk '{print $1}')" = "$ORIGINAL_HASH"
test ! -e "$MODULE/config/flyme-data-original"
test ! -e "$MODULE/config/flyme-data-pending.conf"

CORE="$ROOT/.ziyu-runtime/compat/v227/uninstall.sh"
sh -n "$ROOT/uninstall.sh"
sh -n "$CORE"
grep -q 'private_payload.sh' "$ROOT/uninstall.sh"
grep -q '.ziyu-runtime/compat/v227/uninstall.sh' "$ROOT/uninstall.sh"
grep -q 'device-font-dynamic-mount.conf' "$CORE"
grep -q '_ziyu_flyme_prepare_data_restore' "$CORE"
grep -q 'ziyu_flyme_pending_apply' "$CORE"
grep -q '_ziyu_cleanup_self_mount' "$CORE"

echo 'LuoShu uninstall restores recorded state through the v2.3 private self-mount wrapper.'
