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
_fr_error() {
    printf '{"status":"error","message":"%s"}\n' "$(_fr_json_escape "$1")"
    exit 1
}
_fr_log() {
    mkdir -p "$MODDIR/logs" 2>/dev/null
    printf '[%s] [FRAMEWORK-RESTART] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$1" >> "$LOG_FILE" 2>/dev/null
}

# 记下"本次启动已经通过实时挂载兑现了新字体"，供主页状态判定使用。
# 只影响界面提示（不再催用户完整重启）；下次真正重启后 boot_id 变化，标记自然失效，
# 待启动负载仍按正常流程在开机时提交持久化。
_fr_mark_live_restart() {
    _fm_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    _fm_font=$(head -n1 "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_fm_boot" ] || return 0
    mkdir -p "$MODDIR/config" 2>/dev/null
    {
        printf 'boot_id=%s\n' "$_fm_boot"
        printf 'font=%s\n' "$_fm_font"
        printf 'time=%s\n' "$(date +%s 2>/dev/null)"
    } > "$MODDIR/config/live-restart.conf.tmp.$$" 2>/dev/null && \
        mv -f "$MODDIR/config/live-restart.conf.tmp.$$" "$MODDIR/config/live-restart.conf" 2>/dev/null
    rm -f "$MODDIR/config/live-restart.conf.tmp.$$" 2>/dev/null
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

        # ① 先把当前字体挂到 init 命名空间。
        #    顺序不能反（custom-font-switcher 真机教训：只重启框架时还没有任何东西可读，
        #    等于白重启）。挂载失败就不重启，避免把界面打断却没换来任何变化。
        _fr_mount_out=''
        if [ -f "$MODDIR/common/ziyu_live.sh" ]; then
            _fr_mount_out=$(MODDIR="$MODDIR" sh "$MODDIR/common/ziyu_live.sh" mount 2>&1)
            _fr_mount_rc=$?
            _fr_log "live-mount rc=$_fr_mount_rc"
            if [ "$_fr_mount_rc" -ne 0 ]; then
                _fr_log "aborted: live mount failed"
                printf '{"status":"error","message":"%s"}\n' \
                    "$(_fr_json_escape "字体挂载失败，已取消重启：$_fr_mount_out")"
                exit 1
            fi
        else
            _fr_error '缺少立即挂载组件，无法在重启前把字体挂上。'
        fi

        # ② 再只重启 Android 框架，让它重新读取字体。
        if setprop ctl.restart zygote 2>/dev/null; then
            _fr_mark_live_restart
            _fr_log "accepted method=ctl.restart-zygote"
            printf '{"status":"ok","data":{"requested":true,"method":"ctl.restart-zygote","mounted":true,"message":"%s"}}\n' \
                "$(_fr_json_escape "已先挂载字体，再通知框架重启。屏幕会黑一下，约 30~60 秒后回到锁屏；这次不用再完整重启。")"
            exit 0
        elif killall system_server 2>/dev/null; then
            _fr_mark_live_restart
            _fr_log "accepted method=killall-system_server"
            printf '{"status":"ok","data":{"requested":true,"method":"killall-system_server","mounted":true,"message":"%s"}}\n' \
                "$(_fr_json_escape "已先挂载字体，再用兜底方式重启框架。屏幕会黑一下，约 30~60 秒后回到锁屏；这次不用再完整重启。")"
            exit 0
        else
            _fr_log "failed: framework restart unavailable (mount already applied)"
            _fr_error '字体已挂载，但两条重启路径都不通（setprop / killall 都失败）。可重新打开应用查看，或手动重启手机。'
        fi
        ;;
    *)
        _fr_error '用法：framework_restart.sh [request|status]'
        ;;
esac
