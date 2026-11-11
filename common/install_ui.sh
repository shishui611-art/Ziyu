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
        ui_print '  本次目录升级需完整重启一次'
    else
        ui_print '  请完整重启'
    fi
    ui_print '  实际挂载结果见字域 App「挂载详情」'
    ui_print ''
}

ziyu_install_choose_mount_backend() {
    # Installation reports the saved choice; only the App changes that choice.
    case "${1:-auto}" in
        magic|overlayfs|self_mount|manager|auto) ZIYU_INSTALL_BACKEND_PREFERENCE="${1:-auto}" ;;
        *) ZIYU_INSTALL_BACKEND_PREFERENCE=auto ;;
    esac
    case "$ZIYU_INSTALL_BACKEND_PREFERENCE" in
        magic) _lui_mode='Magic Mount（字域逐文件 bind 挂载）' ;;
        overlayfs) _lui_mode='OverlayFS（仅使用 OverlayFS）' ;;
        self_mount) _lui_mode='字域自挂载（OverlayFS / bind / 目录镜像）' ;;
        manager) _lui_mode='管理器挂载（由 Root 管理器开机自动挂载）' ;;
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
                FinaSU)
                    ui_print '• 自动策略：使用 FinaSU 当前选择的挂载引擎'
                    ;;
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
    if [ "${ROOT_MANAGER:-unknown}" = FinaSU ]; then
        ui_print '• 挂载：待重启验证；关闭挂载时不会改用字域自挂载'
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
