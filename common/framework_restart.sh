#!/system/bin/sh
# 立即软重启（只重启 Android 框架，不动内核）
#
# 移植自 custom-font-switcher v1.5.3 的 live.sh do_softreboot 思路：
#   ① setprop ctl.restart zygote —— 让 init 重启 zygote（绝大多数机器够用）
#   ② killall system_server     —— 少数机型上 ① 不生效时的兜底
# 与 `ksud soft-reboot` 的区别：
#   · ksud soft-reboot 是 KernelSU 管理的完整 userspace reboot（重新走模块加载、
#     挂载、开机流程），耗时长，属于"系统级软重启"；
#   · 本脚本只重启 framework（zygote + system_server），让进程重新读取字体，
#     屏幕黑一下、约 30~60 秒回到锁屏，不重新执行模块挂载阶段。
#
# 风险提示（如实告知）：② 在极少数机型上可能卡住不回来，此时长按电源键强制重启即可；
# 字体挂载不写系统分区，强制重启不会损坏系统。
#
# 用法：framework_restart.sh [request|status]
set +e
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)}"
LOG_FILE="$MODDIR/logs/framework-restart.log"

_fr_json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\r\n' '  '; }
_fr_ok() {
    printf '{"status":"ok","data":{"requested":true,"method":"%s","message":"%s"}}\n' \
        "$(_fr_json_escape "$1")" "$(_fr_json_escape "$2")"
    exit 0
}
_fr_error() {
    printf '{"status":"error","message":"%s"}\n' "$(_fr_json_escape "$1")"
    exit 1
}
_fr_log() {
    mkdir -p "$MODDIR/logs" 2>/dev/null
    printf '[%s] [FRAMEWORK-RESTART] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$1" >> "$LOG_FILE" 2>/dev/null
}

_fr_detect() {
    [ "$(id -u 2>/dev/null)" = 0 ] || _fr_error '立即软重启需要 Root 权限，请为字域授权。'
    [ -f "$MODDIR/module.prop" ] || _fr_error '字域模块目录不存在，请安装匹配模块。'
}

case "${1:-request}" in
    status)
        _fr_detect
        _zygote_pid=$(pidof zygote64 2>/dev/null || pidof zygote 2>/dev/null)
        _system_pid=$(pidof system_server 2>/dev/null)
        printf '{"status":"ok","data":{"zygote":"%s","system_server":"%s","boot_completed":"%s"}}\n' \
            "$(_fr_json_escape "${_zygote_pid:-}")" "$(_fr_json_escape "${_system_pid:-}")" \
            "$(_fr_json_escape "$(getprop sys.boot_completed 2>/dev/null)")"
        ;;
    request)
        _fr_detect
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || _fr_error '字域模块已禁用或待卸载，请先处理模块状态。'
        [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || _fr_error '系统仍在启动中，请等启动完成后再试。'
        _fr_log "requested"
        # 先记日志再执行：重启过程中日志可能写不进去。
        if setprop ctl.restart zygote 2>/dev/null; then
            _fr_log "accepted method=ctl.restart-zygote"
            _fr_ok "ctl.restart-zygote" "已通知框架重启。屏幕会黑一下，约 30~60 秒后回到锁屏；超过 2 分钟未恢复请长按电源键强制重启。"
        elif killall system_server 2>/dev/null; then
            _fr_log "accepted method=killall-system_server"
            _fr_ok "killall-system_server" "已用兜底方式重启框架。屏幕会黑一下，约 30~60 秒后回到锁屏；超过 2 分钟未恢复请长按电源键强制重启。"
        else
            _fr_log "failed both-methods"
            _fr_error '两条路都不通（setprop / killall 都失败）。请手动重启手机，字体会按计划生效。'
        fi
        ;;
    *)
        _fr_error '用法：framework_restart.sh [request|status]'
        ;;
esac
