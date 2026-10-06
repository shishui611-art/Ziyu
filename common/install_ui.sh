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
    ui_print '  ────────────────────────'
}
luoshu_install_complete() {
    ui_print ''
    ui_print '  ────────────────────────'
    ui_print '  ✓ 模块安装完成'
    ui_print '  请完整重启手机，再进入字域'
    ui_print '  字体任务按需运行，结束即退出'
    ui_print '  ────────────────────────'
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
    LUOSHU_INSTALL_BACKEND_PREFERENCE="${1:-auto}"
    case "$LUOSHU_INSTALL_BACKEND_PREFERENCE" in auto|meta|self) ;; *) LUOSHU_INSTALL_BACKEND_PREFERENCE=auto ;; esac
    ui_print "• Meta 引擎：${META_ENGINE:-none}"
    ui_print "• 已安装=${META_INSTALLED:-0} 已启用=${META_ENABLED:-0} 可接入=${META_READY:-0} 可安全使用=${META_USABLE:-0}"
    if [ "${META_USABLE:-0}" = 1 ] && [ "${META_INSTALLED:-0}" = 1 ]; then
        case "${META_CLEANUP_CAPABILITY:-none}" in
            hybrid-vfs)
                ui_print '• 回滚能力：Hybrid 支持按模块卸载，验证失败可在线回退' ;;
            *)
                ui_print '• 回滚能力：无按模块卸载接口；验证失败需重启恢复（Meta 挂载重启即清空）'
                [ "${META_ENGINE:-}" = hybrid-mount ] && \
                    [ "${META_USABLE_REASON:-}" = hybrid-route-not-pure-vfs ] && \
                    ui_print "• 字域默认路由：${META_HYBRID_DEFAULT_MODE:-overlay}；下次启动自动补 VFS 规则升级回滚能力"
                ;;
        esac
    fi
    if [ "${META_USABLE:-0}" != 1 ]; then
        ui_print "• Meta 状态原因：${META_USABLE_REASON:-not-installed}"
        case "${META_USABLE_REASON:-}" in
            hybrid-config-missing) ui_print '• 未找到 Hybrid 配置，不能确认字域使用纯 VFS' ;;
            hybrid-config-unreadable|hybrid-config-unverified) ui_print '• Hybrid 配置无法读取或无法确认路由，需保留安全回退' ;;
            hybrid-module-excluded) ui_print '• 字域被 Hybrid 跳过、停用、移除或排除规则禁止接入' ;;
            hybrid-runtime-binary-unavailable) ui_print '• Hybrid 运行程序不可用' ;;
            hybrid-not-active|hybrid-not-metamodule) ui_print '• Hybrid 已安装，但尚未作为当前 Meta 引擎启用' ;;
            nomount-not-active) ui_print '• NoMount 已安装，但尚未作为当前 Meta 引擎启用' ;;
            nomount-cli-unavailable) ui_print '• NoMount 的 nm 命令行不可用' ;;
            nomount-not-integrated)
                ui_print '• 检测到 NoMount 元模块（内核 VFS 注入，零挂载）'
                ui_print '• 字域尚未接入其规则注入；继续使用字域自挂载，不影响使用' ;;
        esac
    fi
    if [ "${META_INSTALLED:-0}" = 1 ]; then
        ui_print '• 选择下次启动挂载：音量上 = Meta；音量下 = 字域自挂载'
        ui_print "• 30 秒未选择将保留：$LUOSHU_INSTALL_BACKEND_PREFERENCE"
        _likc_key=$(luoshu_install_read_volume_key)
        case "$_likc_key" in
            up) LUOSHU_INSTALL_BACKEND_PREFERENCE=meta ;;
            down) LUOSHU_INSTALL_BACKEND_PREFERENCE=self ;;
            *) ui_print '• 未读取到选择，已保留挂载偏好' ;;
        esac
    fi
    ui_print "✓ 已选择挂载偏好：$LUOSHU_INSTALL_BACKEND_PREFERENCE"
    if [ "$LUOSHU_INSTALL_BACKEND_PREFERENCE" = meta ]; then
        if [ "${META_CLEANUP_CAPABILITY:-none}" != hybrid-vfs ] && \
           [ "${META_HYBRID_ROUTE_REASON:-}" = hybrid-route-not-pure-vfs ] && \
           type luoshu_meta_hybrid_ensure_vfs_rule >/dev/null 2>&1; then
            # Users cannot be expected to edit Hybrid Mount TOML by hand: fix
            # the upstream Overlay default ourselves so the route becomes pure
            # VFS and rollback capability upgrades when the runtime supports it.
            ui_print '• Hybrid 默认是 Overlay 模式；正在为字域添加专属 VFS 规则...'
            if luoshu_meta_hybrid_ensure_vfs_rule; then
                ui_print '✓ 已为字域写入 VFS 规则（原配置备份为 config.toml.luoshu-backup）'
            else
                case "${LUOSHU_META_ENSURE_RESULT:-}" in
                    explicit-rule-wins)
                        ui_print '• Hybrid 中已有字域专属规则，尊重现有配置，不改写' ;;
                    already)
                        ui_print '• 字域 VFS 规则已存在' ;;
                    verify-failed)
                        ui_print '• VFS 规则写入后验证未通过，已恢复原配置' ;;
                    *)
                        ui_print '• 无法安全改写 Hybrid 配置，保留原样' ;;
                esac
            fi
        fi
        if [ "${META_USABLE:-0}" = 1 ]; then
            case "${META_CLEANUP_CAPABILITY:-none}" in
                hybrid-vfs)
                    ui_print '✓ Meta 元模块可安全接入，下次启动生效' ;;
                *)
                    ui_print '✓ Meta 元模块将挂载字体，下次启动生效'
                    ui_print '• 无按模块卸载接口：验证失败需重启恢复，不影响系统其他部分'
                    ;;
            esac
        else
            ui_print '• Meta 偏好已保留；当前条件未满足，下次启动将使用字域自挂载并记录原因'
        fi
    fi
    ui_print '• 偏好在完整重启时生效，主页可再次切换'
}
