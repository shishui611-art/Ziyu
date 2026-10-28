#!/system/bin/sh
# Request KernelSU's managed userspace reboot. ksud owns cleanup-hook ordering,
# module reload and Android restart. A zero exit code means request accepted only.
set +e
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)}"
MODULE_DIR="$MODDIR"
LOG_FILE="$MODDIR/logs/soft-reboot.log"

_sr_json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\r\n' '  '; }
_sr_error() {
    printf '{"status":"error","message":"%s"}\n' "$(_sr_json_escape "$1")"
    exit 1
}
_sr_detect() {
    [ "$(id -u 2>/dev/null)" = 0 ] || _sr_error '软重启需要 Root 权限，请为字域授权。'
    [ -f "$MODDIR/module.prop" ] || _sr_error '字域模块目录不存在，请安装匹配模块。'
    . "$MODDIR/common/root_manager_detection.sh" || _sr_error 'Root 管理器检测脚本不可用。'
    if [ -f "$MODDIR/config/identity-migration-full-reboot.conf" ] &&
       [ "$(sed -n 's/^bootId=//p' "$MODDIR/config/identity-migration-full-reboot.conf")" = "$(cat /proc/sys/kernel/random/boot_id)" ]; then
        _sr_error 'ziyu 标识升级首次需要完整重启，完成后可恢复使用 KernelSU 软重启。'
    fi
    _sr_manager=$(luoshu_detect_root_manager)
    case "$_sr_manager" in
        KernelSU|SukiSU\ Ultra) ;;
        *) _sr_error '当前未确认是 KernelSU 系列管理器，无法请求 KernelSU 软重启。' ;;
    esac
    _sr_ksud=''
    # Only installed daemon paths; never use another module's bundled binary.
    for _sr_candidate in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ksu/ksud "$(command -v ksud 2>/dev/null)"; do
        [ -n "$_sr_candidate" ] && [ -x "$_sr_candidate" ] || continue
        _sr_help=$("$_sr_candidate" soft-reboot --help 2>&1)
        [ "$?" -eq 0 ] || continue
        printf '%s\n' "$_sr_help" | grep -q 'soft-reboot' || continue
        _sr_ksud="$_sr_candidate"
        break
    done
    [ -n "$_sr_ksud" ] || _sr_error '本机 ksud 未提供 soft-reboot 命令，请使用支持软重启的 KernelSU 管理器。'
}

case "${1:-request}" in
    status)
        _sr_detect
        printf '{"status":"ok","data":{"supported":true,"daemon":"%s"}}\n' "$(_sr_json_escape "$_sr_ksud")"
        ;;
    request)
        _sr_detect
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || _sr_error '字域模块已禁用或待卸载，请先处理模块状态。'
        [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || _sr_error '系统仍在启动或软重启中，请等待启动完成。'
        mkdir -p "$MODDIR/logs" || _sr_error '无法创建软重启日志目录。'
        . "$MODDIR/common/mount_provider_reload.sh" || _sr_error '软重启挂载兼容检查不可用。'
        if ! ziyu_provider_reload_check "$MODDIR" request; then
            printf '[%s] [SOFT-REBOOT] blocked reason=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" \
                "${PROVIDER_RELOAD_REASON:-provider-reload-check-failed}" >> "$LOG_FILE"
            _sr_error "${PROVIDER_RELOAD_MESSAGE:-无法确认元模块软重启兼容性，已保留待应用字体。}"
        fi
        # A ROM update blocks mounting until the OEM font baseline is rescanned
        # for the new build, so a plain soft reboot would land in the same
        # blocked state every time. Run that recovery in a detached worker and
        # reboot when it finishes; the App call must return immediately.
        _sr_ota_recover=0
        if [ -f "$MODDIR/common/boot_ota_recover.sh" ]; then
            . "$MODDIR/common/boot_ota_recover.sh"
            case "$(ziyu_boot_ota_state "$MODDIR")" in
                pending) _sr_ota_recover=1 ;;
                complete)
                    case "$(ziyu_boot_ota_status "$MODDIR")" in
                        *next_generation_ready=1*) ;;
                        *) _sr_ota_recover=1 ;;
                    esac
                    ;;
            esac
        fi
        if [ "$_sr_ota_recover" = 1 ]; then
            printf '[%s] [SOFT-REBOOT] deferred: recovering OTA baseline and payload before reboot\n' \
                "$(date '+%Y-%m-%d %H:%M:%S')" >> "$LOG_FILE" 2>/dev/null
            if [ -f "$MODDIR/common/boot_ota_recover_task.sh" ] && \
               [ -f "$MODDIR/common/background_task.sh" ]; then
                . "$MODDIR/common/background_task.sh"
                LUOSHU_TASK_TIMEOUT_SECONDS=900 luoshu_start_detached \
                    "$MODDIR/config/boot-ota-recover.pid" "ziyu-ota-recover-$(date +%s 2>/dev/null || echo 0)" \
                    "$LOG_FILE" sh "$MODDIR/common/boot_ota_recover_task.sh" "$MODDIR"
                _sr_task_rc=$?
                case "$_sr_task_rc" in
                    0|3) ;;
                    *)
                        printf '[%s] [SOFT-REBOOT] deferred worker unavailable rc=%s; running inline\n' \
                            "$(date '+%Y-%m-%d %H:%M:%S')" "$_sr_task_rc" >> "$LOG_FILE" 2>/dev/null
                        ziyu_boot_ota_refresh "$MODDIR" 240 &&
                        ziyu_boot_ota_ensure_generation "$MODDIR" 420 || {
                            _sr_error '系统更新后需要重新核验原厂字体，恢复未完成。请到设置 → 开机保护查看原因后重试。'
                        }
                        "$_sr_ksud" soft-reboot >> "$LOG_FILE" 2>&1
                        _sr_rc=$?
                        [ "$_sr_rc" -eq 0 ] || _sr_error "KernelSU 软重启请求失败（返回码 $_sr_rc），请查看 logs/soft-reboot.log。"
                        printf '{"status":"ok","data":{"requested":true,"message":"已重新核验原厂字体并请求软重启。"}}\n'
                        exit 0
                        ;;
                esac
            fi
            printf '{"status":"ok","data":{"requested":true,"deferred":true,"message":"系统更新后正在重新核验原厂字体并重新准备字体，完成后会自动软重启。"}}\n'
            exit 0
        fi
        printf '[%s] [SOFT-REBOOT] requesting daemon=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$_sr_ksud" >> "$LOG_FILE" || _sr_error '无法写入软重启日志。'
        # Do not run emulated-soft-reboot.sh here: provider hooks must run first.
        "$_sr_ksud" soft-reboot >> "$LOG_FILE" 2>&1
        _sr_rc=$?
        printf '[%s] [SOFT-REBOOT] request returned rc=%s; mount verification follows reload\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$_sr_rc" >> "$LOG_FILE"
        [ "$_sr_rc" -eq 0 ] || _sr_error "KernelSU 软重启请求失败（返回码 $_sr_rc），请查看 logs/soft-reboot.log。"
        printf '{"status":"ok","data":{"requested":true,"message":"已请求 KernelSU 软重启，完成后返回字域查看挂载验证。"}}\n'
        ;;
    *) _sr_error '用法：soft_reboot.sh [request|status]' ;;
esac
