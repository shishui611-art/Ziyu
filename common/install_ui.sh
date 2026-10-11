#!/system/bin/sh
# Root managers own the window. Use short, static text; no ANSI cursor controls,
# fake percentages, subprocess spinners or background animation jobs.
luoshu_install_header() {
    ui_print ''
    ui_print '  字域'
    ui_print "  ${1#v} 字体管理器"
    ui_print '  ────────────────────────'
    ui_print '  ColorOS For You'
    ui_print '  保留 Emoji、图标和代码等宽字体'
}
luoshu_install_step() {
    ui_print ''
    ui_print "  [$1/4] $2"
}
luoshu_install_complete() {
    ui_print ''
    ui_print '  ✓ 字域安装完成'
    ui_print '  请完整重启；实际挂载结果见字域 App「挂载详情」'
    ui_print ''
}

# getevent reads real hardware key presses. A bounded wait preserves the prior
# preference when a manager/recovery does not expose volume-key input.
luoshu_install_read_volume_key() {
    [ "${BOOTMODE:-true}" != false ] || { printf 'unavailable\n'; return 0; }
    command -v getevent >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 || {
        printf 'unavailable\n'; return 0;
    }
    _liku_end=$(( $(date +%s) + 30 ))
    while :; do
        _liku_remaining=$(( _liku_end - $(date +%s) ))
        [ "$_liku_remaining" -gt 0 ] || { printf 'timeout\n'; return 0; }
        _liku_event=$(timeout "$_liku_remaining" getevent -qlc 1 2>/dev/null) || { printf 'timeout\n'; return 0; }
        case "$_liku_event" in
            *KEY_VOLUMEUP*DOWN*|*KEY_VOLUMEUP*00000001*|*'0001 0073 00000001'*) printf 'up\n'; return 0 ;;
            *KEY_VOLUMEDOWN*DOWN*|*KEY_VOLUMEDOWN*00000001*|*'0001 0072 00000001'*) printf 'down\n'; return 0 ;;
        esac
    done
}

luoshu_install_choose_mount_backend() {
    # Installation reports the saved choice; only the App changes that choice.
    case "${1:-auto}" in
        magic|overlayfs|self_mount|auto) LUOSHU_INSTALL_BACKEND_PREFERENCE="${1:-auto}" ;;
        *) LUOSHU_INSTALL_BACKEND_PREFERENCE=auto ;;
    esac
    case "$LUOSHU_INSTALL_BACKEND_PREFERENCE" in
        magic) _lui_mode='Magic Mount（字域逐文件 bind 挂载）' ;;
        overlayfs) _lui_mode='OverlayFS（仅使用 OverlayFS）' ;;
        self_mount) _lui_mode='字域自挂载（OverlayFS / bind / 目录镜像）' ;;
        *) _lui_mode='元模块自动挂载' ;;
    esac
    ui_print "• 已保存挂载选择：$_lui_mode"
    _lui_module="${MODPATH:-${MODDIR:-}}"
    _lui_active_font='default'
    [ -z "$_lui_module" ] || _lui_active_font=$(head -n1 "$_lui_module/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_lui_active_font" ] || _lui_active_font=default

    if [ "$_lui_active_font" = default ]; then
        LUOSHU_INSTALL_MOUNT_STATE=not-applicable
        ui_print '• 挂载：未执行（当前使用系统默认字体）'
        return 0
    fi

    if [ -n "$_lui_module" ] && [ -e "$_lui_module/skip_mount" ] && \
         [ ! -e "$_lui_module/.ziyu_skip_mount_owned" ] && \
         [ ! -e "$_lui_module/config/self-mount-owned" ]; then
        LUOSHU_INSTALL_MOUNT_STATE=skipped
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
    LUOSHU_INSTALL_MOUNT_STATE=pending
    if [ "$LUOSHU_INSTALL_BACKEND_PREFERENCE" != auto ]; then
        ui_print "• 挂载：待重启验证（使用已保存的 $_lui_mode）"
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
