#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
MODDIR="$TMP/module"; MODULE_DIR="$MODDIR"; export MODDIR MODULE_DIR
mkdir -p "$MODDIR/common" "$MODDIR/config" "$MODDIR/.luoshu-payload" "$MODDIR/.luoshu-payload-next"
cp "$ROOT/common/action_control.sh" "$MODDIR/common/"
cp "$ROOT/common/physical_payload_manifest.sh" "$MODDIR/common/"
mkdir -p "$MODDIR/.luoshu-payload/system/fonts" "$MODDIR/.luoshu-payload-next/system/fonts"
printf 'old CJK\n' > "$MODDIR/.luoshu-payload/system/fonts/SysSans-Hans-Regular.ttf"
printf 'new CJK\n' > "$MODDIR/.luoshu-payload-next/system/fonts/SysSans-Hans-Regular.ttf"
printf 'old\n' > "$MODDIR/.luoshu-payload/font"
printf 'new\n' > "$MODDIR/.luoshu-payload-next/font"
printf 'old\n' > "$MODDIR/config/active_font.conf"
printf 'enabled=true\nfont=old\n' > "$MODDIR/config/font_runtime_legacy_v14_4.conf"
printf 'mode=old-overlay\n' > "$MODDIR/config/font-config-overlay.conf"
printf 'font=new\npreviousFont=old\npreviousLegacy=true\ntargetMode=legacy\n' > "$MODDIR/config/font-payload-next.conf"
. "$ROOT/common/next_boot_payload.sh"
set -eu
luoshu_next_boot_activate
test -s "$MODDIR/config/font-undo.conf"
grep -q '^previousFont=old$' "$MODDIR/config/font-undo.conf"
grep -q '^mode=old-overlay$' "$MODDIR/config/.font-undo-config/font-config-overlay.conf"
retired=$(sed -n 's/^retired=//p' "$MODDIR/config/font-undo.conf")
test -f "$retired/font"
# An explicit undo takes effect only at boot and restores its saved config.
printf 'mode=new-overlay\n' > "$MODDIR/config/font-config-overlay.conf"
cp -Rp "$retired" "$MODDIR/.luoshu-payload-next"
printf 'font=old\npreviousFont=new\npreviousLegacy=true\ntargetMode=legacy\nundo=true\n' > "$MODDIR/config/font-payload-next.conf"
luoshu_next_boot_activate
test "$(cat "$MODDIR/.luoshu-payload/font")" = old
grep -q '^mode=old-overlay$' "$MODDIR/config/font-config-overlay.conf"
grep -q '^state=applied$' "$MODDIR/config/font-undo-result.conf"
# Applying default must still allow undo after boot.
mkdir "$MODDIR/.luoshu-payload-next"
printf 'font=default\npreviousFont=old\npreviousLegacy=true\ntargetMode=default\n' > "$MODDIR/config/font-payload-next.conf"
luoshu_next_boot_activate
retired=$(sed -n 's/^retired=//p' "$MODDIR/config/font-undo.conf")
test -f "$retired/font"
test "$(cat "$retired/font")" = old
# A forced reboot racing cancellation must never activate that cancelled stage.
mkdir "$MODDIR/.luoshu-payload-next"
printf 'wrong\n' > "$MODDIR/.luoshu-payload-next/font"
printf 'font=wrong\npreviousFont=default\ntaskId=cancelled-task\n' > "$MODDIR/config/font-payload-next.conf"
printf 'task=cancelled-task\n' > "$MODDIR/config/switch_task.cancel"
luoshu_next_boot_activate && { echo 'cancelled stage was activated' >&2; exit 1; }
test ! -e "$MODDIR/.luoshu-payload-next"
test "$(cat "$MODDIR/config/active_font.conf")" = default
echo 'font_undo_boot_test: PASS'
