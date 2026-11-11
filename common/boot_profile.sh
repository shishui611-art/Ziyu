#!/system/bin/sh
# 开机阶段进程计数与耗时诊断。只使用 shell 内建读取 /proc 与参数展开，
# 不为统计额外派生进程；结果写入 logs/boot-profile.log，每次启动追加一行/阶段。
#
# 背景：zygote 的 PID 等于本次内核启动在它之前创建的任务数。post-fs-data
# 阶段每派生一个进程，zygote PID 就 +1，因此该日志用于量化本模块自己的
# 开机派生开销，而不是把系统或其它模块的开销算进来。

ziyu_boot_profile_enabled() {
    [ "${ZIYU_BOOT_PROFILE:-1}" = 1 ] || return 1
    [ -n "${1:-}" ] && [ -d "$1/logs" ] || return 1
    return 0
}

ziyu_boot_profile_start() {
    _zbp_root="${1:-${MODDIR:-}}"
    ziyu_boot_profile_enabled "$_zbp_root" || return 0
    _zbp_state="$_zbp_root/logs/.boot-profile.state"
    _zbp_lpid=''
    _zbp_up=''
    IFS=' ' read -r _zbp_l1 _zbp_l2 _zbp_l3 _zbp_l4 _zbp_lpid < /proc/loadavg 2>/dev/null || _zbp_lpid=''
    IFS=' ' read -r _zbp_up _zbp_idle < /proc/uptime 2>/dev/null || _zbp_up=0
    _zbp_up=${_zbp_up%%.*}
    _zbp_epoch=$(date +%s 2>/dev/null || printf '0')
    case "$_zbp_lpid" in ''|*[!0-9]*) return 0 ;; esac
    case "$_zbp_epoch" in ''|*[!0-9]*) _zbp_epoch=0 ;; esac
    printf '%s %s %s\n' "$_zbp_lpid" "$_zbp_up" "$_zbp_epoch" > "$_zbp_state" 2>/dev/null || return 0
    ziyu_boot_profile_mark "$_zbp_root" start
}

ziyu_boot_profile_mark() {
    _zbp_root="${1:-${MODDIR:-}}"
    _zbp_stage="${2:-unknown}"
    ziyu_boot_profile_enabled "$_zbp_root" || return 0
    _zbp_state="$_zbp_root/logs/.boot-profile.state"
    _zbp_log="$_zbp_root/logs/boot-profile.log"
    [ -r "$_zbp_state" ] || return 0
    IFS=' ' read -r _zbp_start _zbp_start_up _zbp_start_epoch < "$_zbp_state" 2>/dev/null || return 0
    case "$_zbp_start" in ''|*[!0-9]*) return 0 ;; esac
    case "$_zbp_start_epoch" in ''|*[!0-9]*) _zbp_start_epoch=0 ;; esac
    _zbp_lpid=''
    _zbp_up=''
    IFS=' ' read -r _zbp_l1 _zbp_l2 _zbp_l3 _zbp_l4 _zbp_lpid < /proc/loadavg 2>/dev/null || _zbp_lpid=''
    IFS=' ' read -r _zbp_up _zbp_idle < /proc/uptime 2>/dev/null || _zbp_up=0
    _zbp_up=${_zbp_up%%.*}
    case "$_zbp_lpid" in ''|*[!0-9]*) return 0 ;; esac
    _zbp_boot=''
    IFS= read -r _zbp_boot < /proc/sys/kernel/random/boot_id 2>/dev/null || _zbp_boot=''
    _zbp_epoch=$(date +%s 2>/dev/null || printf '0')
    case "$_zbp_epoch" in ''|*[!0-9]*) _zbp_epoch=0 ;; esac
    _zbp_dur=0
    [ "$_zbp_start_epoch" -gt 0 ] && [ "$_zbp_epoch" -ge "$_zbp_start_epoch" ] && \
        _zbp_dur=$((_zbp_epoch - _zbp_start_epoch))
    printf 'boot=%s stage=%s pid=%s delta=%s uptime=%s dur=%ss\n' \
        "$_zbp_boot" "$_zbp_stage" "$_zbp_lpid" "$((_zbp_lpid - _zbp_start))" "$_zbp_up" "$_zbp_dur" \
        >> "$_zbp_log" 2>/dev/null || true
}

ziyu_boot_profile_end() {
    _zbp_root="${1:-${MODDIR:-}}"
    ziyu_boot_profile_enabled "$_zbp_root" || return 0
    _zbp_state="$_zbp_root/logs/.boot-profile.state"
    [ -r "$_zbp_state" ] || return 0
    ziyu_boot_profile_mark "$_zbp_root" end
    rm -f "$_zbp_state" 2>/dev/null || true
}
