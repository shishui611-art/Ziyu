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
