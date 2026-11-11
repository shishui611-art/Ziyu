#!/system/bin/sh
# Ziyu installer wrapper: run the verified installer, then hide every standard
# partition payload before the first boot.
# Installer contract: delegated core deploys common/ziyu_cli.sh to system/bin/ziyu.
# Compatibility contract retained for source/regression checks:
# for _enable_dir in "$MODPATH" "$OLD_MOD"
# rm -f "$_enable_dir/disable"
set +e
MODPATH="${MODPATH:-$3}"
[ -f "$MODPATH/common/identity_migration.sh" ] || { abort '缺少标识迁移脚本'; return 1 2>/dev/null || exit 1; }
. "$MODPATH/common/identity_migration.sh"
ziyu_identity_select_old
_lc_source_dir=$(CDPATH= cd -- "${0%/*}" 2>/dev/null && pwd)
_lc_base="$MODPATH/.ziyu-runtime/compat/v227/customize.sh"
_lc_helper="$MODPATH/common/private_payload.sh"
_lc_temp="$MODPATH/.customize-v227.$$.sh"
[ -f "$_lc_base" ] || _lc_base="$_lc_source_dir/.ziyu-runtime/compat/v227/customize.sh"
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
_lc_real_old_mod="$ZIYU_OLD_MOD"
_lc_scan_rom=$(MODDIR="$MODPATH" sh "$MODPATH/common/boot_ota_guard.sh" rom-identity "$MODPATH" 2>/dev/null)

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

type ziyu_install_step >/dev/null 2>&1 && ziyu_install_step 4 "挂载状态"
if ! ziyu_private_install_migrate "$MODPATH"; then
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
    # An actual fresh install scan can be reused by the App. Never bless a
    # fallback inventory, incomplete topology, or a scan across ROM changes.
    if [ "${_inventory_rc:-1}" = 0 ] && [ "${_topology_rc:-1}" = 0 ] && \
       [ -n "$_lc_scan_rom" ] && [ ! -e "$MODPATH/config/stock_inventory_scan_pending" ]; then
        MODDIR="$MODPATH" sh "$MODPATH/common/boot_ota_guard.sh" scan-complete "$MODPATH" "$_lc_scan_rom" \
            >> "$MODPATH/logs/boot-ota-guard.log" 2>&1 || \
            ui_print '• 安装扫描未形成可信 ROM 回执，保留后续核验流程'
    fi
fi
for _lc_rom_record in app-rom-observed.conf stock-rom-auto-attempt.conf; do
    if [ ! -e "$MODPATH/config/$_lc_rom_record" ] && \
       [ -f "$_lc_real_old_mod/config/$_lc_rom_record" ] && [ ! -L "$_lc_real_old_mod/config/$_lc_rom_record" ] && \
       [ "$(wc -c < "$_lc_real_old_mod/config/$_lc_rom_record")" -le 2048 ]; then
        cp -p "$_lc_real_old_mod/config/$_lc_rom_record" "$MODPATH/config/$_lc_rom_record" || abort 'ROM 观察记录迁移失败'
    fi
done
if [ -f "$MODPATH/common/mount_backend_preferences.sh" ]; then
    . "$MODPATH/common/mount_backend_preferences.sh"
    ziyu_mount_preference_restore "$_lc_real_old_mod" "$MODPATH" || {
        abort '挂载偏好迁移失败'
        return 1 2>/dev/null || exit 1
    }
    MODDIR="$MODPATH" MODULE_DIR="$MODPATH"
    _lc_saved_preference=$(ziyu_mount_preference_get "$MODPATH")
    [ ! -f "$MODPATH/common/mount_backend_policy.sh" ] || . "$MODPATH/common/mount_backend_policy.sh"
    [ ! -f "$MODPATH/common/root_manager_detection.sh" ] || . "$MODPATH/common/root_manager_detection.sh"
    type ziyu_detect_root_manager >/dev/null 2>&1 && ziyu_detect_root_manager >/dev/null
    [ ! -f "$MODPATH/common/meta_mount_detection.sh" ] || . "$MODPATH/common/meta_mount_detection.sh"
    type ziyu_meta_mount_detect >/dev/null 2>&1 && ziyu_meta_mount_detect >/dev/null
    ziyu_install_choose_mount_backend "$_lc_saved_preference"
    # 默认走元模块 / 根管理器挂载：检测到 NoMount 时，未指定或 auto 的偏好
    # 直接落为 manager，避免首次安装后被自动策略切到自挂载。
    case "${META_ENGINE:-none}" in
        nomount)
            case "$ZIYU_INSTALL_BACKEND_PREFERENCE" in
                ''|auto|meta|self) ZIYU_INSTALL_BACKEND_PREFERENCE=manager ;;
            esac
            ;;
    esac
    ziyu_mount_preference_write "$ZIYU_INSTALL_BACKEND_PREFERENCE" "$MODPATH" || {
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
# Retire the separately owned experimental component; main module stays script-only.
. "$MODPATH/common/font_preload_helper.sh" || abort '缺少旧组件清理脚本'
ziyu_preload_helper_remove || abort '旧字体预加载组件清理标记失败'
. "$MODPATH/common/adb_storage.sh" || abort '缺少数据目录迁移脚本'
# 安装器解压 zip 时常不保留可执行位，先补 python 运行时权限再初始化数据目录。
[ ! -f "$MODPATH/common/python/bin/ziyu-python" ] || \
    chmod 0755 "$MODPATH/common/python/bin/ziyu-python" 2>/dev/null || true
if ! ziyu_adb_storage "$MODPATH" install; then
    # 数据目录迁移不是装模块的前提：开机阶段还会再执行一次（真机日志里开机均为 complete）。
    # 这里只提示不中断，避免管理器把整个安装判成
    # "Error: Failed to install module script"。
    ui_print '• 数据目录初始化将在首次开机时完成（安装环境未能执行迁移组件）'
fi
[ ! -f "$MODPATH/config/identity-migration-full-reboot.conf" ] || ui_print '• 数据目录升级：请完整重启一次，旧挂载目录在重启后自动清理'
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
for _lc_display_config in display-config-storage.conf display-typography.conf display-typography-dirty font-slot-overrides.json font-slot-overrides.previous.json; do
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
[ "$_lc_real_old_mod" != /data/adb/modules/LuoShu ] || ui_print '• 旧标识已迁移为 ziyu，本次必须完整重启'
# NoMount 的脚本接管会扫描模块根下所有 *.sh；安装时就把运行库移入 data/
# （NoMount 会裁剪该目录）并留相对符号链接，开机路径不变但不再被逐个扫描。
if [ -d "$MODPATH/common" ] && [ ! -L "$MODPATH/common" ]; then
    if mkdir -p "$MODPATH/data" 2>/dev/null &&
       mv "$MODPATH/common" "$MODPATH/data/common" 2>/dev/null &&
       ln -s data/common "$MODPATH/common" 2>/dev/null; then
        :
    else
        [ -d "$MODPATH/data/common" ] && [ ! -e "$MODPATH/common" ] &&
            mv "$MODPATH/data/common" "$MODPATH/common" 2>/dev/null
    fi
fi
type ziyu_install_complete >/dev/null 2>&1 && ziyu_install_complete
return 0 2>/dev/null || exit 0
