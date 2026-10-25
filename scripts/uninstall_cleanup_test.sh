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
SELF_STATE="$TMP/luoshu/self-mount"
PRIVATE_STATE="$TMP/luoshu/private-payload"
MODDIR="$MODULES/ziyu"

mkdir -p \
  "$MODDIR/logs" "$MODDIR/config" "$MODDIR/common" "$MODDIR/.luoshu-runtime/compat/v227" \
  "$MODULES/OtherModule" "$MODULES_UPDATE/ziyu/system/fonts" \
  "$META_MNT/ziyu/product/fonts" "$CONTENT_BASE/ziyu/system/fonts" \
  "$SELF_STATE/lower/system-fonts" "$PRIVATE_STATE" "${MAGIC_CONFIG%/*}"

cp "$ROOT/uninstall.sh" "$MODDIR/uninstall.sh"
cp "$ROOT/common/private_payload.sh" "$MODDIR/common/private_payload.sh"
cp "$ROOT/common/font_live_switch.sh" "$MODDIR/common/font_live_switch.sh"
cp "$ROOT/common/font_live_state.sh" "$MODDIR/common/font_live_state.sh"
cp "$ROOT/common/font_switch_lock.sh" "$MODDIR/common/font_switch_lock.sh"
cp "$ROOT/.luoshu-runtime/compat/v227/uninstall.sh" "$MODDIR/.luoshu-runtime/compat/v227/uninstall.sh"
printf '%s\n' 'version=v-test' > "$MODDIR/module.prop"
printf '%s\n' 'old log' > "$MODDIR/logs/fontswitch.log"
printf '%s\n' 'keep sibling' > "$MODULES/OtherModule/module.prop"
printf '%s\n' 'partitions = ["system", "product"]' > "$MAGIC_CONFIG"
printf '%s\n' 'backup' > "$MAGIC_CONFIG.luoshu.bak"
printf '%s\n' '999999' > "$MAGIC_CONFIG.luoshu.lock"
printf '%s\n' 'temp' > "$MAGIC_CONFIG.luoshu.123"
printf '%s\n' "$SELF_STATE/lower/system-fonts" '/system/fonts' > "$SELF_STATE/mounts.list"
: > "$PRIVATE_STATE/module-view.mounts"

LUOSHU_MODULES_DIR="$MODULES" \
LUOSHU_MODULES_UPDATE_DIR="$MODULES_UPDATE" \
LUOSHU_METAMODULE_MNT="$META_MNT" \
LUOSHU_MAGIC_MOUNT_CONFIG="$MAGIC_CONFIG" \
LUOSHU_SELF_MOUNT_STATE="$SELF_STATE" \
LUOSHU_PRIVATE_STATE_ROOT="$PRIVATE_STATE" \
MODULE_CONTENT_DIR="$CONTENT_BASE" \
sh "$MODDIR/uninstall.sh"

[ ! -e "$MODDIR" ]
[ ! -e "$MODULES_UPDATE/ziyu" ]
[ ! -e "$META_MNT/ziyu" ]
[ ! -e "$CONTENT_BASE/ziyu" ]
[ ! -e "$SELF_STATE" ]
[ -f "$MODULES/OtherModule/module.prop" ]
[ -f "$MAGIC_CONFIG" ]
[ ! -e "$MAGIC_CONFIG.luoshu.bak" ]
[ ! -e "$MAGIC_CONFIG.luoshu.lock" ]
[ ! -e "$MAGIC_CONFIG.luoshu.123" ]
grep -q 'partitions = \["system", "product"\]' "$MAGIC_CONFIG"
[ ! -d "$MODULES/ziyu/logs" ]
echo 'ziyu uninstall cleanup checks passed.'
