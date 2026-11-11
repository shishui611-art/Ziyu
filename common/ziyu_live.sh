#!/system/bin/sh
# 字域临时 Root 立即生效流程（参考 custom-font-switcher 的 live.sh 思路，
# 脚本为独立实现）：不重启，把当前负载按槽位覆盖计划 bind mount 到 init 命名空间。
#
# 用法：
#   sh ziyu_live.sh mount     立即挂载当前负载
#   sh ziyu_live.sh unmount   撤销本流程挂载
#   sh ziyu_live.sh status    查看状态
#   sh ziyu_live.sh apply     mount 的别名
set +e

MODDIR="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
MODULE_DIR="$MODDIR"
ZLIVE_LIST="$MODDIR/config/live-mount.list"
ZLIVE_LOG="$MODDIR/logs/live.log"
ZLIVE_SCRIPT="${0##*/}"

[ -f "$MODDIR/common/slot_coverage.sh" ] && . "$MODDIR/common/slot_coverage.sh"

_zl_log() {
    mkdir -p "$MODDIR/logs" 2>/dev/null || true
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" >> "$ZLIVE_LOG" 2>/dev/null || true
}

_zl_payload_fonts() {
    for _zl_dir in "$MODDIR/.ziyu-payload/system/fonts" "$MODDIR/system/fonts"; do
        [ -d "$_zl_dir" ] || continue
        printf '%s' "$_zl_dir"
        return 0
    done
    return 1
}

# 只有在 su 会话与 init 不是同一 mount 命名空间时才需要 nsenter。
_zl_ns_prefix() {
    _zl_same=1
    _zl_pid1=$(readlink /proc/1/ns/mnt 2>/dev/null)
    _zl_self=$(readlink /proc/self/ns/mnt 2>/dev/null)
    [ -n "$_zl_pid1" ] && [ "$_zl_pid1" = "$_zl_self" ] || _zl_same=0
    if [ "$_zl_same" = 1 ]; then
        printf ''
    elif command -v nsenter >/dev/null 2>&1; then
        printf 'nsenter -t 1 -m --'
    else
        printf ''
    fi
}

_zl_boot_id() {
    _zl_bid=''
    IFS= read -r _zl_bid < /proc/sys/kernel/random/boot_id 2>/dev/null || _zl_bid=''
    printf '%s' "$_zl_bid"
}

_zl_count() {
    if [ -f "$ZLIVE_LIST" ]; then
        grep -c . "$ZLIVE_LIST" 2>/dev/null || printf '0'
    else
        printf '0'
    fi
}

ziyu_live_mount() {
    _zlm_fonts=$(_zl_payload_fonts) || {
        printf '没有可用的字体负载（.ziyu-payload/system/fonts 不存在）\n'
        return 1
    }
    _zlm_plan=$(ziyu_slot_coverage_plan "$MODDIR" "$_zlm_fonts" 2>/dev/null) || _zlm_plan=''
    case "$_zlm_plan" in
        '') printf '槽位覆盖计划为空：请先应用字体，或检查 common/slot_targets 清单\n'; return 1 ;;
    esac
    _zlm_ns=$(_zl_ns_prefix)
    [ -n "$_zlm_ns" ] || _zlm_ns=''
    mkdir -p "$MODDIR/config" 2>/dev/null || true
    : > "$ZLIVE_LIST.tmp.$$"
    _zlm_add=0
    _zlm_skip=0
    _zlm_fail=0
    _zlm_boot=$(_zl_boot_id)
    while IFS='|' read -r _zlm_src _zlm_slot; do
        [ -n "$_zlm_src" ] && [ -n "$_zlm_slot" ] || continue
        [ -f "$_zlm_src" ] || { _zlm_fail=$((_zlm_fail + 1)); continue; }
        _zlm_dst="/system/fonts/$_zlm_slot"
        [ -e "$_zlm_dst" ] || { _zlm_skip=$((_zlm_skip + 1)); continue; }
        if [ -f "$ZLIVE_LIST" ] && grep -Fxq "$_zlm_slot|$_zlm_src" "$ZLIVE_LIST" 2>/dev/null; then
            printf '%s|%s\n' "$_zlm_slot" "$_zlm_src" >> "$ZLIVE_LIST.tmp.$$"
            _zlm_skip=$((_zlm_skip + 1))
            continue
        fi
        if $_zlm_ns mount --bind "$_zlm_src" "$_zlm_dst" 2>/dev/null; then
            printf '%s|%s\n' "$_zlm_slot" "$_zlm_src" >> "$ZLIVE_LIST.tmp.$$"
            _zlm_add=$((_zlm_add + 1))
        else
            _zlm_fail=$((_zlm_fail + 1))
        fi
    done <<EOF
$_zlm_plan
EOF
    if [ -s "$ZLIVE_LIST.tmp.$$" ]; then
        mv -f "$ZLIVE_LIST.tmp.$$" "$ZLIVE_LIST" 2>/dev/null || rm -f "$ZLIVE_LIST.tmp.$$"
    else
        rm -f "$ZLIVE_LIST.tmp.$$"
    fi
    printf 'boot=%s\nfont=%s\nmounted=%s\n' "$_zlm_boot" "$(head -n1 "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')" "$(_zl_count)" \
        > "$MODDIR/config/live-mount.conf" 2>/dev/null || true
    _zl_log "live mount added=$_zlm_add skipped=$_zlm_skip failed=$_zlm_fail ns=${_zlm_ns:-same}"
    printf '立即生效：新挂载 %s 个槽位，已有/跳过 %s 个，失败 %s 个\n' "$_zlm_add" "$_zlm_skip" "$_zlm_fail"
    printf '提示：已打开的应用可能仍使用旧字体缓存，重新打开该应用或重启应用进程后可见。\n'
    [ "$_zlm_add" -gt 0 ] || [ "$_zlm_skip" -gt 0 ]
}

ziyu_live_unmount() {
    [ -f "$ZLIVE_LIST" ] || { printf '没有本流程挂载的记录\n'; return 0; }
    _zlu_ns=$(_zl_ns_prefix)
    _zlu_rev=$(awk '{a[NR]=$0} END{for(i=NR;i>=1;i--) print a[i]}' "$ZLIVE_LIST" 2>/dev/null)
    _zlu_ok=0
    _zlu_fail=0
    while IFS='|' read -r _zlu_slot _zlu_src; do
        [ -n "$_zlu_slot" ] || continue
        if $_zlu_ns umount "/system/fonts/$_zlu_slot" 2>/dev/null; then
            _zlu_ok=$((_zlu_ok + 1))
        else
            _zlu_fail=$((_zlu_fail + 1))
        fi
    done <<EOF
$_zlu_rev
EOF
    : > "$ZLIVE_LIST"
    rm -f "$MODDIR/config/live-mount.conf" 2>/dev/null || true
    _zl_log "live unmount ok=$_zlu_ok failed=$_zlu_fail"
    printf '已撤销 %s 个槽位挂载，失败 %s 个\n' "$_zlu_ok" "$_zlu_fail"
    [ "$_zlu_fail" -eq 0 ]
}

ziyu_live_status() {
    _zls_count=$(_zl_count)
    _zls_boot=$(_zl_boot_id)
    _zls_saved_boot=''
    [ -f "$MODDIR/config/live-mount.conf" ] && \
        _zls_saved_boot=$(sed -n 's/^boot=//p' "$MODDIR/config/live-mount.conf" 2>/dev/null | head -n1)
    _zls_font=$(head -n1 "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_zls_font" ] || _zls_font=default
    printf '当前字体：%s\n' "$_zls_font"
    printf '本次启动立即挂载：%s 个槽位\n' "$_zls_count"
    if [ -n "$_zls_saved_boot" ] && [ "$_zls_saved_boot" != "$_zls_boot" ]; then
        printf '提示：挂载记录来自上一次启动，已失效；重新执行 mount 即可。\n'
    fi
    printf '命名空间：%s\n' "$( [ -n "$(_zl_ns_prefix)" ] && echo '需要 nsenter（su 与 init 不同）' || echo '与 init 相同' )"
    return 0
}

case "${1:-status}" in
    mount|apply) ziyu_live_mount ;;
    unmount|restore) ziyu_live_unmount ;;
    status) ziyu_live_status ;;
    free)
        ziyu_live_mount || exit $?
        printf '已立即挂载。若界面未刷新，可手动重启对应应用；本版不提供软重启。\n'
        ;;
    *)
        printf '用法: sh %s <mount|unmount|status|free>\n' "$ZLIVE_SCRIPT"
        exit 2
        ;;
esac
