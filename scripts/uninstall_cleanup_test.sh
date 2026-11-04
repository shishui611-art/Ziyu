#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

MODULES="$TMP/modules"
MODULES_UPDATE="$TMP/modules_update"
META_MNT="$TMP/metamodule/mnt"
CONTENT_BASE="$TMP/content"
MAGIC_CONFIG="$TMP/magic_mount/config.toml"
SELF_STATE="$TMP/ziyu/self-mount"
PRIVATE_STATE="$TMP/ziyu/private-payload"
MODDIR="$MODULES/ziyu"
TEST_BIN="$TMP/bin"

mkdir -p \
  "$MODDIR/logs" "$MODDIR/config" "$MODDIR/common" "$MODDIR/.ziyu-runtime/compat/v227" \
  "$MODULES/OtherModule" "$MODULES_UPDATE/ziyu/system/fonts" \
  "$META_MNT/ziyu/product/fonts" "$CONTENT_BASE/ziyu/system/fonts" \
  "$SELF_STATE/lower/system-fonts" "$PRIVATE_STATE" "${MAGIC_CONFIG%/*}" "$TEST_BIN"

# The retire path needs a boot id even when this cleanup test runs on Windows.
# Override only the kernel boot-id read; all other cat calls use the real tool.
REAL_CAT=$(command -v cat)
cat > "$TEST_BIN/cat" <<EOF
#!/bin/sh
if [ "\${1:-}" = /proc/sys/kernel/random/boot_id ]; then
  printf '%s\\n' 'uninstall-test-boot'
else
  exec "$REAL_CAT" "\$@"
fi
EOF
chmod +x "$TEST_BIN/cat"

cp "$ROOT/uninstall.sh" "$MODDIR/uninstall.sh"
cp "$ROOT/common/private_payload.sh" "$MODDIR/common/private_payload.sh"
cp "$ROOT/common/private_payload.lib" "$MODDIR/common/private_payload.lib"
cp "$ROOT/common/font_live_switch.sh" "$MODDIR/common/font_live_switch.sh"
cp "$ROOT/common/font_live_state.sh" "$ROOT/common/legacy_identity.sh" "$MODDIR/common/"
cp "$ROOT/common/font_switch_lock.sh" "$MODDIR/common/font_switch_lock.sh"
cp "$ROOT/.ziyu-runtime/compat/v227/uninstall.sh" "$MODDIR/.ziyu-runtime/compat/v227/uninstall.sh"
printf '%s\n' 'version=v-test' > "$MODDIR/module.prop"
printf '%s\n' 'old log' > "$MODDIR/logs/fontswitch.log"
printf '%s\n' 'keep sibling' > "$MODULES/OtherModule/module.prop"
printf '%s\n' 'partitions = ["system", "product"]' > "$MAGIC_CONFIG"
printf '%s\n' 'backup' > "$MAGIC_CONFIG.ziyu.bak"
printf '%s\n' '999999' > "$MAGIC_CONFIG.ziyu.lock"
printf '%s\n' 'temp' > "$MAGIC_CONFIG.ziyu.123"
printf '%s\n' "$SELF_STATE/lower/system-fonts" '/system/fonts' > "$SELF_STATE/mounts.list"
: > "$PRIVATE_STATE/module-view.mounts"

ZIYU_MODULES_DIR="$MODULES" \
ZIYU_MODULES_UPDATE_DIR="$MODULES_UPDATE" \
ZIYU_METAMODULE_MNT="$META_MNT" \
ZIYU_MAGIC_MOUNT_CONFIG="$MAGIC_CONFIG" \
ZIYU_SELF_MOUNT_STATE="$SELF_STATE" \
ZIYU_PRIVATE_STATE_ROOT="$PRIVATE_STATE" \
MODULE_CONTENT_DIR="$CONTENT_BASE" \
PATH="$TEST_BIN:$PATH" \
sh "$MODDIR/uninstall.sh"

[ ! -e "$MODDIR" ]
[ ! -e "$MODULES_UPDATE/ziyu" ]
[ ! -e "$META_MNT/ziyu" ]
[ ! -e "$CONTENT_BASE/ziyu" ]
[ ! -e "$SELF_STATE" ]
[ -f "$MODULES/OtherModule/module.prop" ]
[ -f "$MAGIC_CONFIG" ]
[ ! -e "$MAGIC_CONFIG.ziyu.bak" ]
[ ! -e "$MAGIC_CONFIG.ziyu.lock" ]
[ ! -e "$MAGIC_CONFIG.ziyu.123" ]
grep -q 'partitions = \["system", "product"\]' "$MAGIC_CONFIG"
[ ! -d "$MODULES/ziyu/logs" ]
echo 'ziyu uninstall cleanup checks passed.'
