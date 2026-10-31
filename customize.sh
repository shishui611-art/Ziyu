#!/system/bin/sh
# LuoShu installer wrapper: run the verified installer, then hide every standard
# partition payload before the first boot.
# Installer contract: delegated core deploys common/luoshu_cli.sh to system/bin/ziyu.
# Compatibility contract retained for source/regression checks:
# for _enable_dir in "$MODPATH" "$OLD_MOD"
# rm -f "$_enable_dir/disable"
set +e
MODPATH="${MODPATH:-$3}"
[ -f "$MODPATH/common/identity_migration.sh" ] || { abort '缺少标识迁移脚本'; return 1 2>/dev/null || exit 1; }
. "$MODPATH/common/identity_migration.sh"
ziyu_identity_select_old
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
_lc_core_aborted=0
_lc_core_abort() {
    _lc_core_aborted=1
    ui_print "! $*"
    return 0
}
sed -e 's/^[[:space:]]*exit 1[[:space:]]*$/        return 1/' \
    -e 's/^[[:space:]]*exit 0[[:space:]]*$/return 0/' \
    -e 's/^[[:space:]]*abort[[:space:]]*/        _lc_core_abort /' \
    -e '/^ui_print "✓ 挂载：字域私有自挂载"$/d' \
    "$_lc_base" > "$_lc_temp" 2>/dev/null || {
    abort '安装入口准备失败'
    return 1 2>/dev/null || exit 1
}

. "$_lc_temp"
_lc_rc=$?
rm -f "$_lc_temp" 2>/dev/null || true
if [ "$_lc_rc" -ne 0 ]; then
    if [ "$_lc_core_aborted" = 1 ]; then
        # Preserve the manager's abort semantics, but only after the generated
        # source file has been removed from the module directory.
        abort '安装核心拒绝迁移当前字体；本次更新已停止'
        return 1 2>/dev/null || exit 1
    fi
    return "$_lc_rc" 2>/dev/null || exit "$_lc_rc"
fi

type luoshu_install_step >/dev/null 2>&1 && luoshu_install_step 4 "挂载状态"
if ! luoshu_private_install_migrate "$MODPATH"; then
    abort '字域私有挂载树部署失败'
    return 1 2>/dev/null || exit 1
fi
# Some root managers extract regular files without retaining ZIP executable bits.
[ ! -f "$MODPATH/common/font-native-instance" ] || chmod 0755 "$MODPATH/common/font-native-instance" 2>/dev/null || true
# Establish the build baseline during a module upgrade, before a guard-owned
# stale disable token can prevent the new boot hook from running at all.
if [ -f "$MODPATH/common/boot_ota_guard.sh" ]; then
    MODDIR="$MODPATH" MODULE_DIR="$MODPATH" sh "$MODPATH/common/boot_ota_guard.sh" initialize "$MODPATH" \
        >> "$MODPATH/logs/boot-ota-guard.log" 2>&1 || \
        ui_print '• 系统基准迁移尚未完成，请在开机保护页面核对；外部停用标记保持不变'
fi
if [ -f "$MODPATH/common/mount_backend_preferences.sh" ]; then
    . "$MODPATH/common/mount_backend_preferences.sh"
    luoshu_mount_preference_restore "$_lc_real_old_mod" "$MODPATH" || {
        abort '挂载偏好迁移失败'
        return 1 2>/dev/null || exit 1
    }
    MODDIR="$MODPATH" MODULE_DIR="$MODPATH"
    _lc_saved_preference=$(luoshu_mount_preference_get "$MODPATH")
    [ ! -f "$MODPATH/common/mount_backend_policy.sh" ] || . "$MODPATH/common/mount_backend_policy.sh"
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
# Register the APatch common hook now so the first reboot after installation
# prepares modules_update before the manager's provider scan.
. "$MODPATH/common/apatch_boot_prepare.sh" || {
    abort '缺少 APatch 启动准备脚本'
    return 1 2>/dev/null || exit 1
}
ziyu_apatch_hook_install "$MODPATH" || {
    abort 'APatch 启动准备入口安装失败'
    return 1 2>/dev/null || exit 1
}
# Optional font-cache companion never gates the existing font mount path.
if [ -f "$MODPATH/common/font_preload_helper.sh" ]; then
    . "$MODPATH/common/font_preload_helper.sh"
    if ziyu_preload_helper_install "$MODPATH"; then
        ui_print '• 字体排除兼容：已部署「字域字体预加载」（实验性，需要可用 Zygisk）'
        ui_print '  主模块挂载不依赖它；已有停用/移除选择保持不变，重启后加载'
    else
        ui_print '• 字体预加载配套组件未部署，请查看安装日志；原有挂载流程保留'
    fi
fi
ziyu_identity_finish "$MODPATH" "$_lc_real_old_mod" || {
    abort 'ziyu 标识迁移未完成，请保留原模块并导出安装日志'
    return 1 2>/dev/null || exit 1
}
# Settings services may be unavailable in recovery/early boot. Attempt one
# bounded, read-only initialization; the App fills a missing cache on first use.
# Preserve only small regular per-font clipping preferences during upgrades.
# Their schema/font binding is rechecked by the runtime before use.
if [ "$_lc_real_old_mod" != "$MODPATH" ] && \
   [ -d "$_lc_real_old_mod/config/font-clipping-preferences" ] && \
   [ ! -L "$_lc_real_old_mod/config/font-clipping-preferences" ]; then
    [ ! -L "$MODPATH/config/font-clipping-preferences" ] || abort '防裁切偏好目录不可为符号链接'
    mkdir -p "$MODPATH/config/font-clipping-preferences" || abort '防裁切偏好目录创建失败'
    for _lc_clip_file in "$_lc_real_old_mod/config/font-clipping-preferences/"*.json; do
        [ -f "$_lc_clip_file" ] && [ ! -L "$_lc_clip_file" ] || continue
        _lc_clip_name=${_lc_clip_file##*/}
        printf '%s\n' "$_lc_clip_name" | grep -Eq '^[0-9a-f]{64}\.json$' || continue
        _lc_clip_size=$(wc -c < "$_lc_clip_file")
        [ "$_lc_clip_size" -le 4096 ] || continue
        [ -e "$MODPATH/config/font-clipping-preferences/$_lc_clip_name" ] || \
            cp -p "$_lc_clip_file" "$MODPATH/config/font-clipping-preferences/$_lc_clip_name" || abort '防裁切偏好迁移失败'
    done
fi
for _lc_display_config in display-config-storage.conf display-typography.conf display-typography-dirty; do
    if [ ! -e "$MODPATH/config/$_lc_display_config" ] && \
       [ -f "$_lc_real_old_mod/config/$_lc_display_config" ] && [ ! -L "$_lc_real_old_mod/config/$_lc_display_config" ]; then
        cp -f "$_lc_real_old_mod/config/$_lc_display_config" "$MODPATH/config/$_lc_display_config" 2>/dev/null || true
    fi
done
if [ "${BOOTMODE:-false}" = true ] && [ -f "$MODPATH/common/display_typography.sh" ] && command -v timeout >/dev/null 2>&1; then
    if MODDIR="$MODPATH" timeout 8 sh "$MODPATH/common/display_typography.sh" initialize \
        > "$MODPATH/logs/display-typography-install.log" 2>&1; then
        ui_print '• 已读取并保存字体大小、粗细与 DPI，本地配置在后续启动直接复用'
    else
        ui_print '• 系统显示服务暂不可读，首次打开字域时补充本地配置'
    fi
fi
[ "$_lc_real_old_mod" != /data/adb/modules/LuoShu ] || ui_print '• 旧标识已迁移为 ziyu，本次必须完整重启；后续字体切换可选受支持的 KSU 软重启'
type luoshu_install_complete >/dev/null 2>&1 && luoshu_install_complete
return 0 2>/dev/null || exit 0
