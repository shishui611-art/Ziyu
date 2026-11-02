#!/system/bin/sh
# Root managers own the window. Use short, static text; no ANSI cursor controls,
# fake percentages, subprocess spinners or background animation jobs.
ziyu_install_header() {
    ui_print ''
    ui_print '  字域'
    ui_print "  ${1#v} 字体管理器"
    ui_print '  ────────────────────────'
    ui_print '  ColorOS For You'
    ui_print '  保留 Emoji、图标和代码等宽字体'
}
ziyu_install_step() {
    ui_print ''
    ui_print "  [$1/4] $2"
}
ziyu_install_complete() {
    ui_print ''
    ui_print '  ✓ 字域安装完成'
    _lui_finish_module="${MODPATH:-${MODDIR:-}}"
    _lui_finish_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    _lui_finish_required=$(sed -n 's/^bootId=//p' "$_lui_finish_module/config/identity-migration-full-reboot.conf" 2>/dev/null | head -n1)
    if [ -n "$_lui_finish_boot" ] && [ "$_lui_finish_required" = "$_lui_finish_boot" ]; then
        ui_print '  本次目录升级需完整重启一次；临时 Root 需重新获取 Root 后加载模块'
    elif [ "${ZIYU_INSTALL_TEMPORARY_ROOT:-false}" = true ]; then
        case "${ROOT_MANAGER:-unknown}" in
            KernelSU|SukiSU\ Ultra) ui_print '  已启用临时 Root 流程，请在字域 App 确认可用的软重启方式' ;;
            *) ui_print '  已保存临时 Root 选择，请按当前 Root 管理器支持的方式加载模块' ;;
        esac
    else
        ui_print '  请完整重启'
    fi
    ui_print '  实际挂载结果见字域 App「挂载详情」'
    ui_print ''
}

# getevent reads real hardware key presses. A bounded wait selects ordinary Root
# when a manager/recovery does not expose volume-key input.
ziyu_install_read_volume_key() {
    [ "${BOOTMODE:-true}" != false ] || { printf 'unavailable\n'; return 0; }
    command -v getevent >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 || {
        printf 'unavailable\n'; return 0;
    }
    _liku_end=$(( $(date +%s) + 30 ))
    while :; do
        _liku_remaining=$(( _liku_end - $(date +%s) ))
        [ "$_liku_remaining" -gt 0 ] || { printf 'timeout\n'; return 0; }
        _liku_event=$(timeout "$_liku_remaining" getevent -qlc 1 2>/dev/null)
        _liku_rc=$?
        case "$_liku_rc" in
            0) [ -n "$_liku_event" ] || { printf 'unavailable\n'; return 0; } ;;
            124|137|143) printf 'timeout\n'; return 0 ;;
            *) printf 'unavailable\n'; return 0 ;;
        esac
        case "$_liku_event" in
            *KEY_VOLUMEUP*DOWN*|*KEY_VOLUMEUP*00000001*|*'0001 0073 00000001'*) printf 'up\n'; return 0 ;;
            *KEY_VOLUMEDOWN*DOWN*|*KEY_VOLUMEDOWN*00000001*|*'0001 0072 00000001'*) printf 'down\n'; return 0 ;;
        esac
    done
}

ziyu_install_choose_root_mode() {
    _lui_root_module="$1"
    [ -d "$_lui_root_module" ] && [ ! -L "$_lui_root_module/config" ] || return 1
    mkdir -p "$_lui_root_module/config" || return 1
    _lui_root_file="$_lui_root_module/config/temporary-root-mode.conf"
    [ ! -L "$_lui_root_file" ] || return 1
    # Each flash starts from ordinary Root. A migrated preference must not silently
    # make temporary Root the default; the user can explicitly select it below.
    ZIYU_INSTALL_TEMPORARY_ROOT=false
    _lui_root_default='普通 Root'
    ui_print ''
    ui_print '  请选择 Root 类型'
    ui_print '  音量上键（+）：普通 Root'
    ui_print '  音量下键（-）：临时 Root'
    ui_print '  完整重启会丢失 Root 时，选择临时 Root'
    ui_print "  等待按键（30 秒）；未选择则使用：$_lui_root_default"
    _lui_root_key=$(ziyu_install_read_volume_key)
    case "$_lui_root_key" in
        up) ZIYU_INSTALL_TEMPORARY_ROOT=false ;;
        down) ZIYU_INSTALL_TEMPORARY_ROOT=true ;;
        timeout) ui_print "• 按键等待结束，使用默认：$_lui_root_default" ;;
        *) ui_print "• 当前安装环境无法读取音量键，使用默认：$_lui_root_default" ;;
    esac
    # Replace atomically: migration can have hard-linked the old preference.
    # Use the existing App/runtime setting, without changing real late-load state.
    _lui_root_tmp="${_lui_root_file}.tmp.$$"
    (umask 077; set -C; printf 'enabled=%s\n' "$ZIYU_INSTALL_TEMPORARY_ROOT" > "$_lui_root_tmp") || return 1
    if ! chmod 0600 "$_lui_root_tmp" || ! mv -f "$_lui_root_tmp" "$_lui_root_file"; then
        rm -f "$_lui_root_tmp" 2>/dev/null || true
        return 1
    fi
    if [ "$ZIYU_INSTALL_TEMPORARY_ROOT" = true ]; then
        ui_print '✓ 已选择：临时 Root（已同步到字域 App）'
        case "${ROOT_MANAGER:-unknown}" in
            KernelSU|SukiSU\ Ultra) ui_print '• 软重启仍需管理器实际支持，由 App 检查并确认后执行' ;;
            *) ui_print '• 当前未确认 KernelSU 系列管理器，App 内软重启不可用，请按管理器支持的方式加载模块' ;;
        esac
    else
        ui_print '✓ 已选择：普通 Root（已同步到字域 App）'
    fi
    return 0
}

ziyu_install_choose_mount_backend() {
    # Installation reports the saved choice; only the App changes that choice.
    case "${1:-auto}" in
        magic|overlayfs|self_mount|auto) ZIYU_INSTALL_BACKEND_PREFERENCE="${1:-auto}" ;;
        *) ZIYU_INSTALL_BACKEND_PREFERENCE=auto ;;
    esac
    case "$ZIYU_INSTALL_BACKEND_PREFERENCE" in
        magic) _lui_mode='Magic Mount（字域逐文件 bind 挂载）' ;;
        overlayfs) _lui_mode='OverlayFS（仅使用 OverlayFS）' ;;
        self_mount) _lui_mode='字域自挂载（OverlayFS / bind / 目录镜像）' ;;
        *) _lui_mode='元模块自动挂载' ;;
    esac
    ui_print "• 已保存挂载选择：$_lui_mode"
    _lui_auto_self_mode=self_mount
    if [ "$ZIYU_INSTALL_BACKEND_PREFERENCE" = auto ]; then
        if type ziyu_mount_auto_self_backend >/dev/null 2>&1; then
            _lui_auto_self_mode=$(ziyu_mount_auto_self_backend "${ROOT_MANAGER:-unknown}")
        fi
        if [ "$_lui_auto_self_mode" = magic ]; then
            ui_print '• 自动策略：Root 管理器未识别，重启时尝试 Magic Mount（bind）'
        else
            case "${ROOT_MANAGER:-unknown}" in
                Magisk|APatch)
                    ui_print '• 自动策略：优先可用元模块或管理器内置挂载，不可用则尝试字域自挂载'
                    ;;
                *)
                    ui_print '• 自动策略：未检测到可用元模块时，重启尝试字域自挂载'
                    ;;
            esac
        fi
    fi
    _lui_module="${MODPATH:-${MODDIR:-}}"
    _lui_active_font='default'
    [ -z "$_lui_module" ] || _lui_active_font=$(head -n1 "$_lui_module/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_lui_active_font" ] || _lui_active_font=default

    if [ "$_lui_active_font" = default ]; then
        ZIYU_INSTALL_MOUNT_STATE=not-applicable
        ui_print '• 挂载：未执行（当前使用系统默认字体）'
        return 0
    fi

    if [ -n "$_lui_module" ] && [ -e "$_lui_module/skip_mount" ] && \
         [ ! -e "$_lui_module/.ziyu_skip_mount_owned" ] && \
         [ ! -e "$_lui_module/config/self-mount-owned" ]; then
        ZIYU_INSTALL_MOUNT_STATE=skipped
        ui_print '• 挂载：已跳过（存在外部排除标记）'
        return 0
    fi

    _lui_engine="${META_ENGINE:-none}"
    case "$_lui_engine" in
        hybrid-mount) _lui_engine_name='Hybrid Mount 元模块' ;;
        mountify) _lui_engine_name='Mountify 元模块' ;;
        meta-overlayfs) _lui_engine_name='Meta OverlayFS 元模块' ;;
        nomount) _lui_engine_name='NoMount 元模块' ;;
        magic-mount) _lui_engine_name='Magic Mount 元模块' ;;
        none|'') _lui_engine_name='未检测到活动的第三方元模块' ;;
        *) _lui_engine_name="未识别元模块（$_lui_engine）" ;;
    esac
    ZIYU_INSTALL_MOUNT_STATE=pending
    if [ "$ZIYU_INSTALL_BACKEND_PREFERENCE" != auto ]; then
        ui_print "• 挂载：待重启验证（使用已保存的 $_lui_mode）"
        return 0
    fi
    if [ "$_lui_auto_self_mode" = magic ]; then
        ui_print '• 挂载：待重启验证（Root 未识别，使用字域 Magic Mount / bind 回退）'
        return 0
    fi
    case "${META_ENABLED:-0}:${META_USABLE:-0}" in
        1:1)
            if [ -n "$_lui_engine" ] && [ "$_lui_engine" != none ]; then
                _lui_meta_dir="${META_MODULE_DIR:-${META_ACTIVE_DIR:-}}"
                _lui_meta_version=''
                [ -z "$_lui_meta_dir" ] || _lui_meta_version=$(sed -n 's/^version=//p' "$_lui_meta_dir/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n')
                _lui_candidate="$_lui_engine_name"
                [ -z "$_lui_meta_version" ] || _lui_candidate="$_lui_candidate $_lui_meta_version"
                ui_print "• 挂载：待重启验证（优先 $_lui_candidate；失败回退字域自挂载）"
            else
                ui_print '• 挂载：待重启验证（启动时自动选择后端，失败回退字域自挂载）'
            fi
            ;;
        *)
            ui_print '• 挂载：待重启验证（启动时自动选择后端，失败回退字域自挂载）'
            ;;
    esac
}
