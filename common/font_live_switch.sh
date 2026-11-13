#!/system/bin/sh
# Reuse the selected self-mount core in PID 1. No process restart is automatic.
set +e
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
MODDIR=$(CDPATH= cd -- "$MODDIR" && pwd) || exit 1
MODULE_DIR="$MODDIR"
export MODDIR MODULE_DIR
. "$MODDIR/common/font_switch_lock.sh" || exit 1
. "$MODDIR/common/font_live_state.sh" || exit 1
LIVE_JOURNAL="$MODDIR/config/font-live-transaction.conf"
LIVE_PY="$MODDIR/common/font_live_payload.py"
LIVE_LOG="$MODDIR/logs/font-live.log"
mkdir -p "$MODDIR/logs" "$MODDIR/.ziyu-state/tmp" || exit 1

live_python() {
    if [ -n "${ZIYU_PYTHON:-}" ]; then "$ZIYU_PYTHON" "$@"; return $?; fi
    PYTHONHOME="$MODDIR/common/python" \
    PYTHONPATH="$MODDIR/common:$MODDIR/common/python/lib/python3.14:$MODDIR/common/python/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$MODDIR/common/python/lib:$MODDIR/common/python/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$MODDIR/common/python/bin/ziyu-python" "$@"
}
live_log() {
    printf '[%s] [LIVE] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LIVE_LOG"
    printf '[LIVE] %s\n' "$*"
}
live_defer() {
    live_log "WARN 热切换未执行：$1；已保留重启应用队列"
    printf 'boot_id=%s\nstate=deferred\nreason=%s\n' "$BOOT" "$1" > "$MODDIR/config/font-live-attempt.conf"
    return 3
}
live_mount() (
    # Each attempt, including rollback, gets fresh work files. Old mmaps remain valid.
    ZIYU_SELF_MOUNT_STATE_ROOT="$3"
    ZIYU_MOUNT_ACTIVE_FONT="$2"
    ZIYU_LIVE_MOUNT_SOURCE="$1"
    ZIYU_SELF_MOUNT_MODE="$4"
    ZIYU_SELF_ALLOW_LAZY_UMOUNT=1
    export ZIYU_SELF_MOUNT_STATE_ROOT ZIYU_MOUNT_ACTIVE_FONT ZIYU_LIVE_MOUNT_SOURCE ZIYU_SELF_MOUNT_MODE ZIYU_SELF_ALLOW_LAZY_UMOUNT
    . "$MODDIR/common/mount_compat.sh" || exit 1
    rm -f "$MODDIR/config/self-mount-required.conf" || exit 1
    ziyu_private_self_mount_ensure || exit 1
    . "$MODDIR/common/physical_payload_manifest.sh" || exit 1
    ziyu_physical_manifest_build "$MODDIR" "$ZIYU_LIVE_MOUNT_SOURCE" || exit 1
    live_python "$MODDIR/common/font_route_verify.py" --module-root "$MODDIR" \
        --payload-root "$ZIYU_LIVE_MOUNT_SOURCE" --active-font "$ZIYU_MOUNT_ACTIVE_FONT" \
        --mode legacy --output "$MODDIR/config/mount-backend-verification.json" || exit 1
)
live_detach() (
    [ -n "$1" ] || exit 1
    case "$1" in /data/adb/ziyu/self-mount|/data/adb/ziyu/live-mount/"$BOOT".*) ;; *) exit 1 ;; esac
    case "$1" in *'/../'*|*'/./'*) exit 1 ;; esac
    ZIYU_SELF_MOUNT_STATE_ROOT="$1"
    ZIYU_SELF_ALLOW_LAZY_UMOUNT=1
    export ZIYU_SELF_MOUNT_STATE_ROOT ZIYU_SELF_ALLOW_LAZY_UMOUNT
    . "$MODDIR/common/mount_compat.sh" || exit 1
    _ziyu_atomic_rollback "$1/mounts.list" detach
)
live_recover() {
    [ -f "$LIVE_JOURNAL" ] || return 0
    [ "$(ziyu_live_value "$LIVE_JOURNAL" schema)" = ziyu-live-transaction-v1 ] || return 1
    if [ "$(ziyu_live_value "$LIVE_JOURNAL" boot_id)" != "$BOOT" ]; then
        # A kernel reboot removes the old namespace. Its files remain preserved.
        live_log '已跨完整重启，撤销旧热切换指针，交由启动挂载重新验证'
        rm -f "$MODDIR/config/font-live.conf" || return 1
        live_python "$LIVE_PY" "$MODDIR" finish
        return $?
    fi
    REC_SOURCE=$(ziyu_live_value "$LIVE_JOURNAL" old_source)
    REC_FONT=$(ziyu_live_value "$LIVE_JOURNAL" old_font)
    REC_MODE=$(ziyu_live_value "$LIVE_JOURNAL" mode)
    REC_NEW_WORK=$(ziyu_live_value "$LIVE_JOURNAL" work_root)
    REC_OLD_WORK=$(ziyu_live_value "$LIVE_JOURNAL" old_work)
    case "$REC_SOURCE" in "$MODDIR/.ziyu-payload"|"$MODDIR/.ziyu-state/cache/live/$BOOT/"generation-*) ;; *) return 1 ;; esac
    case "$REC_SOURCE" in *'/../'*|*'/./'*) return 1 ;; esac
    [ -d "$REC_SOURCE" ] && [ -n "$REC_FONT" ] || return 1
    if [ "$REC_FONT" = default ]; then
        live_detach "$REC_NEW_WORK" && live_python "$LIVE_PY" "$MODDIR" restore &&
            live_python "$LIVE_PY" "$MODDIR" finish
        return $?
    fi
    live_log "正在恢复上一套字体：$REC_FONT"
    live_detach "$REC_NEW_WORK" || return 1
    # Old detach can have been interrupted before mounting the new payload.
    live_detach "$REC_OLD_WORK" || return 1
    live_python "$LIVE_PY" "$MODDIR" restore || return 1
    REC_WORK="/data/adb/ziyu/live-mount/$BOOT.$$.rollback"
    [ ! -e "$REC_WORK" ] || REC_WORK="$REC_WORK.$(date +%s)"
    # Persist the rollback work root before its first mount for SIGKILL recovery.
    live_python "$LIVE_PY" "$MODDIR" work "$REC_WORK" || return 1
    live_mount "$REC_SOURCE" "$REC_FONT" "$REC_WORK" "$REC_MODE" >> "$LIVE_LOG" 2>&1 || return 1
    live_python "$LIVE_PY" "$MODDIR" record "$REC_SOURCE" "$REC_FONT" "$REC_WORK" "$BOOT" recovered || return 1
    live_python "$LIVE_PY" "$MODDIR" proof "$REC_FONT" "$BOOT" || return 1
    live_python "$LIVE_PY" "$MODDIR" finish || return 1
    live_log "上一套字体挂载恢复并通过校验：$REC_FONT；新选择仍保留在重启队列"
}
BOOT=$(ziyu_current_boot_id) || exit 1
ACTION="${1:-apply}"
case "$ACTION" in apply|recover|retire) ;; *) exit 2 ;; esac
# Ordinary reboot-only users do not need a live runtime or namespace entry.
[ "$ACTION" != recover ] || [ -e "$LIVE_JOURNAL" ] || exit 0
if [ "$ACTION" = retire ] && [ ! -e "$LIVE_JOURNAL" ] && [ ! -e "$MODDIR/config/font-live.conf" ]; then exit 0; fi
if [ "${ZIYU_LIVE_INNER:-0}" != 1 ]; then
    # Verify the actual canonical lock owner, not an untrusted bypass boolean.
    OWNER="${ZIYU_LIVE_OWNER_PID:-}"
    if [ -n "$OWNER" ]; then
        [ "$(ziyu_font_lock_pid "$MODDIR/.font_switch.lock")" = "$OWNER" ] && \
        [ "$(ziyu_font_lock_boot_id "$MODDIR/.font_switch.lock")" = "$BOOT" ] && \
        [ "$(ziyu_font_lock_starttime "$MODDIR/.font_switch.lock")" = "$(ziyu_process_starttime "$OWNER")" ] || exit 1
    else
        ziyu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || exit 1
        trap 'ziyu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1' EXIT
        OWNER="$$"
    fi
    ZIYU_LIVE_OWNER_PID="$OWNER" ZIYU_LIVE_INNER=1 \
        live_python "$LIVE_PY" --lock-exec "$MODDIR/.ziyu-state/tmp/font-live.lock" sh "$0" "$ACTION"
    exit $?
fi
# Both inherited identities must survive namespace entry.
[ -n "${ZIYU_LIVE_OWNER_PID:-}" ] && \
    [ "$(ziyu_font_lock_pid "$MODDIR/.font_switch.lock")" = "$ZIYU_LIVE_OWNER_PID" ] && \
    [ "$(ziyu_font_lock_starttime "$MODDIR/.font_switch.lock")" = "$(ziyu_process_starttime "$ZIYU_LIVE_OWNER_PID")" ] && \
    [ "$(readlink "/proc/self/fd/${ZIYU_LIVE_LOCK_FD:-invalid}")" = "$MODDIR/.ziyu-state/tmp/font-live.lock" ] || exit 1
# The wrapper holds both locks while nsenter and all child mount commands execute.
NEEDS_NAMESPACE=1
if [ "$ACTION" = retire ] && [ "$(ziyu_live_value "$MODDIR/config/font-live.conf" boot_id)" != "$BOOT" ] && \
   { [ ! -e "$LIVE_JOURNAL" ] || [ "$(ziyu_live_value "$LIVE_JOURNAL" boot_id)" != "$BOOT" ]; }; then NEEDS_NAMESPACE=0; fi
if [ "$NEEDS_NAMESPACE" = 1 ] && [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
    if command -v nsenter >/dev/null 2>&1; then exec nsenter -t 1 -m -- sh "$0" "$ACTION"; fi
    if command -v toybox >/dev/null 2>&1 && toybox nsenter --help >/dev/null 2>&1; then exec toybox nsenter -t 1 -m -- sh "$0" "$ACTION"; fi
    live_defer pid1-namespace-entry-unavailable
    exit 3
fi
case "$ACTION" in
    apply) live_defer live-switch-removed-reboot-required; exit 3 ;;
    recover) live_recover; exit $? ;;
    retire)
        live_python "$LIVE_PY" "$MODDIR" preserve-undo "$BOOT" >> "$LIVE_LOG" 2>&1 || {
            live_log 'ERROR 无法保留热切换前字体的重启回退快照，暂停启动切换'
            exit 1
        }
        if [ -f "$LIVE_JOURNAL" ]; then
            [ "$(ziyu_live_value "$LIVE_JOURNAL" schema)" = ziyu-live-transaction-v1 ] || exit 1
            if [ "$(ziyu_live_value "$LIVE_JOURNAL" boot_id)" = "$BOOT" ]; then
                live_detach "$(ziyu_live_value "$LIVE_JOURNAL" work_root)" || exit 1
                live_detach "$(ziyu_live_value "$LIVE_JOURNAL" old_work)" || exit 1
            fi
            live_python "$LIVE_PY" "$MODDIR" finish || exit 1
            rm -f "$MODDIR/config/font-live.conf" || exit 1
        fi
        if [ -e "$MODDIR/config/font-live.conf" ] && [ "$(ziyu_live_value "$MODDIR/config/font-live.conf" boot_id)" = "$BOOT" ]; then
            [ "$(ziyu_live_value "$MODDIR/config/font-live.conf" schema)" = ziyu-live-font-v1 ] || exit 1
            live_detach "$(ziyu_live_value "$MODDIR/config/font-live.conf" work_root)" || exit 1
        fi
        rm -f "$MODDIR/config/font-live.conf" || exit 1
        live_python "$LIVE_PY" "$MODDIR" prune "$BOOT" >> "$LIVE_LOG" 2>&1 || live_log 'WARN 旧启动字体代清理未完成，保留文件'
        ;;
esac
