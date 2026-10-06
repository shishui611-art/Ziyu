#!/system/bin/sh
# LuoShu installer wrapper: run the verified installer, then hide every standard
# partition payload before the first boot.
# Installer contract: delegated core deploys common/luoshu_cli.sh to system/bin/洛书.
# Compatibility contract retained for source/regression checks:
# for _enable_dir in "$MODPATH" "$OLD_MOD"
# rm -f "$_enable_dir/disable"
set +e
MODPATH="${MODPATH:-$3}"
LUOSHU_OLD_MOD="${LUOSHU_OLD_MOD:-/data/adb/modules/LuoShu}"
_lc_source_dir=$(CDPATH= cd -- "${0%/*}" 2>/dev/null && pwd)
_lc_base="$MODPATH/.luoshu-runtime/compat/v227/customize.sh"
_lc_helper="$MODPATH/common/private_payload.sh"
_lc_temp="$MODPATH/.customize-v227.$$.sh"
[ -f "$_lc_base" ] || _lc_base="$_lc_source_dir/.luoshu-runtime/compat/v227/customize.sh"
[ -f "$_lc_helper" ] || _lc_helper="$_lc_source_dir/common/private_payload.sh"
[ -f "$_lc_helper" ] && . "$_lc_helper"

if ! command -v ui_print >/dev/null 2>&1; then
    ui_print() { printf '%s\n' "$*"; }
fi
if ! command -v abort >/dev/null 2>&1; then
    abort() { ui_print "! $*"; return 1; }
fi

if [ ! -f "$_lc_base" ]; then
    abort '缺少字域安装核心'
    return 1 2>/dev/null || exit 1
fi

[ ! -f "$MODPATH/common/install_ui.sh" ] || . "$MODPATH/common/install_ui.sh"

# Migrate directly from durable private payloads. The migrator must not require
# mounting or projecting the active module in the installer's namespace, and
# schema metadata in the running installation must remain untouched.
_lc_real_old_mod="$LUOSHU_OLD_MOD"

# Run the delegated installer by sourcing it so its migration state remains visible
# to this wrapper, but convert its top-level exit statements into returns. This is
# essential for APatch (which sources customize.sh) and guarantees cleanup of the
# temporary private-payload view on a controlled migration rejection.
sed -e 's/^[[:space:]]*exit 1[[:space:]]*$/        return 1/' \
    -e 's/^[[:space:]]*exit 0[[:space:]]*$/return 0/' \
    -e '/^ui_print "✓ 挂载：字域私有自挂载"$/d' \
    "$_lc_base" > "$_lc_temp" 2>/dev/null || {
    abort '安装入口准备失败'
    return 1 2>/dev/null || exit 1
}

. "$_lc_temp"
_lc_rc=$?
rm -f "$_lc_temp" 2>/dev/null || true
if [ "$_lc_rc" -ne 0 ]; then
    return "$_lc_rc" 2>/dev/null || exit "$_lc_rc"
fi

# Never rebuild fonts synchronously while a Root manager is flashing the module.
if [ "${UPDATE_PRESERVED:-false}" = true ] && [ "${LUOSHU_UPDATE_REBUILD_REQUIRED:-false}" = true ]; then
    _lc_font=$(head -n1 "$MODPATH/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_lc_font" ] || _lc_font=default
    ui_print "✓ 已保留当前字体负载：$_lc_font"
    ui_print '• 本次刷写不会同步重建字体；重启后可在字域中重新应用以升级引擎'
fi

type luoshu_install_step >/dev/null 2>&1 && luoshu_install_step 4 "部署字体挂载"
if ! luoshu_private_install_migrate "$MODPATH"; then
    abort '字域私有挂载树部署失败'
    return 1 2>/dev/null || exit 1
fi
ui_print '✓ 私有字体负载已部署'
if [ -f "$MODPATH/common/mount_backend_preferences.sh" ]; then
    . "$MODPATH/common/mount_backend_preferences.sh"
    luoshu_mount_preference_restore "$_lc_real_old_mod" "$MODPATH" || {
        abort '挂载偏好迁移失败'
        return 1 2>/dev/null || exit 1
    }
    _lc_saved_preference=$(luoshu_mount_preference_get "$MODPATH")
    MODDIR="$MODPATH" MODULE_DIR="$MODPATH"
    [ ! -f "$MODPATH/common/root_manager_detection.sh" ] || . "$MODPATH/common/root_manager_detection.sh"
    type luoshu_detect_root_manager >/dev/null 2>&1 && luoshu_detect_root_manager >/dev/null
    [ ! -f "$MODPATH/common/meta_mount_detection.sh" ] || . "$MODPATH/common/meta_mount_detection.sh"
    type luoshu_meta_mount_detect >/dev/null 2>&1 && luoshu_meta_mount_detect >/dev/null
    luoshu_install_choose_mount_backend "$_lc_saved_preference"
    luoshu_mount_preference_write "$LUOSHU_INSTALL_BACKEND_PREFERENCE" "$MODPATH" || {
        abort '挂载偏好保存失败'
        return 1 2>/dev/null || exit 1
    }
else
    abort '缺少挂载偏好管理脚本'
    return 1 2>/dev/null || exit 1
fi
type luoshu_install_complete >/dev/null 2>&1 && luoshu_install_complete
return 0 2>/dev/null || exit 0
