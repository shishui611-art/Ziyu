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
mkdir -p "$MODDIR/logs" "$MODDIR/.luoshu-state/tmp" || exit 1

live_python() {
    if [ -n "${LUOSHU_PYTHON:-}" ]; then "$LUOSHU_PYTHON" "$@"; return $?; fi
    PYTHONHOME="$MODDIR/common/python" \
    PYTHONPATH="$MODDIR/common:$MODDIR/common/python/lib/python3.14:$MODDIR/common/python/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$MODDIR/common/python/lib:$MODDIR/common/python/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$MODDIR/common/python/bin/luoshu-python" "$@"
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
    LUOSHU_SELF_MOUNT_STATE_ROOT="$3"
    LUOSHU_MOUNT_ACTIVE_FONT="$2"
    LUOSHU_LIVE_MOUNT_SOURCE="$1"
    LUOSHU_SELF_MOUNT_MODE="$4"
    LUOSHU_SELF_ALLOW_LAZY_UMOUNT=1
    export LUOSHU_SELF_MOUNT_STATE_ROOT LUOSHU_MOUNT_ACTIVE_FONT LUOSHU_LIVE_MOUNT_SOURCE LUOSHU_SELF_MOUNT_MODE LUOSHU_SELF_ALLOW_LAZY_UMOUNT
    . "$MODDIR/common/mount_compat.sh" || exit 1
    rm -f "$MODDIR/config/self-mount-required.conf" || exit 1
    luoshu_private_self_mount_ensure || exit 1
    . "$MODDIR/common/physical_payload_manifest.sh" || exit 1
    luoshu_physical_manifest_build "$MODDIR" "$LUOSHU_LIVE_MOUNT_SOURCE" || exit 1
    live_python "$MODDIR/common/font_route_verify.py" --module-root "$MODDIR" \
        --payload-root "$LUOSHU_LIVE_MOUNT_SOURCE" --active-font "$LUOSHU_MOUNT_ACTIVE_FONT" \
        --mode legacy --output "$MODDIR/config/mount-backend-verification.json" || exit 1
)
live_detach() (
    [ -n "$1" ] || exit 1
    case "$1" in /data/adb/luoshu/self-mount|/data/adb/luoshu/live-mount/"$BOOT".*) ;; *) exit 1 ;; esac
    case "$1" in *'/../'*|*'/./'*) exit 1 ;; esac
    LUOSHU_SELF_MOUNT_STATE_ROOT="$1"
    LUOSHU_SELF_ALLOW_LAZY_UMOUNT=1
    export LUOSHU_SELF_MOUNT_STATE_ROOT LUOSHU_SELF_ALLOW_LAZY_UMOUNT
    . "$MODDIR/common/mount_compat.sh" || exit 1
    _luoshu_atomic_rollback "$1/mounts.list" detach
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
    case "$REC_SOURCE" in "$MODDIR/.luoshu-payload"|"$MODDIR/.luoshu-state/cache/live/$BOOT/"generation-*) ;; *) return 1 ;; esac
    case "$REC_SOURCE" in *'/../'*|*'/./'*) return 1 ;; esac
    [ -d "$REC_SOURCE" ] && [ -n "$REC_FONT" ] && [ "$REC_FONT" != default ] || return 1
    live_log "正在恢复上一套字体：$REC_FONT"
    live_detach "$REC_NEW_WORK" || return 1
    # Old detach can have been interrupted before mounting the new payload.
    live_detach "$REC_OLD_WORK" || return 1
    live_python "$LIVE_PY" "$MODDIR" restore || return 1
    REC_WORK="/data/adb/luoshu/live-mount/$BOOT.$$.rollback"
    [ ! -e "$REC_WORK" ] || REC_WORK="$REC_WORK.$(date +%s)"
    # Persist the rollback work root before its first mount for SIGKILL recovery.
    live_python "$LIVE_PY" "$MODDIR" work "$REC_WORK" || return 1
    live_mount "$REC_SOURCE" "$REC_FONT" "$REC_WORK" "$REC_MODE" >> "$LIVE_LOG" 2>&1 || return 1
    live_python "$LIVE_PY" "$MODDIR" record "$REC_SOURCE" "$REC_FONT" "$REC_WORK" "$BOOT" recovered || return 1
    live_python "$LIVE_PY" "$MODDIR" proof "$REC_FONT" "$BOOT" || return 1
    live_python "$LIVE_PY" "$MODDIR" finish || return 1
    live_log "上一套字体挂载恢复并通过校验：$REC_FONT；新选择仍保留在重启队列"
}
live_apply() {
    live_recover || return 1
    BACKEND="$MODDIR/config/mount-backend.conf"
    case "$(ziyu_live_value "$BACKEND" schema)" in
        ziyu-mount-backend-v1|ziyu-mount-backend-v2|ziyu-mount-backend-v3) ;;
        *) live_defer backend-schema-invalid; return 3 ;;
    esac
    [ "$(ziyu_live_value "$BACKEND" boot_id)" = "$BOOT" ] || { live_defer backend-from-different-boot; return 3; }
    [ "$(ziyu_live_value "$BACKEND" selected_backend)" = self ] && \
        [ "$(ziyu_live_value "$BACKEND" active_backend)" = self ] || { live_defer selected-backend-does-not-support-live-update; return 3; }
    case "$(ziyu_live_value "$BACKEND" verification)" in passed|partial) ;; *) live_defer current-mount-not-verified; return 3 ;; esac
    [ "$(ziyu_live_value "$BACKEND" selected_self_backend)" != nomount ] && \
        [ "$(ziyu_live_value "$BACKEND" active_self_backend)" != nomount ] || { live_defer nomount-runtime-update-unavailable; return 3; }
    [ "$(ziyu_live_value "$BACKEND" backend_conflict)" != 1 ] || { live_defer backend-conflict; return 3; }
    [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || { live_defer module-disabled; return 3; }
    [ ! -s "$MODDIR/config/universal-font-runtime.conf" ] || { live_defer universal-runtime-requires-reboot; return 3; }
    MODE=$(ziyu_live_value "$BACKEND" preferred_backend)
    case "$MODE" in magic|overlayfs|self_mount) ;; *) live_defer explicit-self-preference-required; return 3 ;; esac
    PREF=$(ziyu_live_value "$MODDIR/config/mount-backend-preference.conf" preferred_backend)
    [ -n "$PREF" ] || PREF=$(ziyu_live_value "$MODDIR/config/mount-backend-preference.conf" preference)
    [ -z "$PREF" ] || [ "$PREF" = "$MODE" ] || { live_defer mount-preference-changed-reboot-required; return 3; }
    . "$MODDIR/common/skip_mount_ownership.sh" || return 1
    ziyu_foreign_skip_mount_present "$MODDIR" && { live_defer foreign-skip-mount; return 3; }
    NEXT="$MODDIR/config/font-payload-next.conf"
    FONT=$(ziyu_live_value "$NEXT" font)
    REQUEST=$(ziyu_live_value "$NEXT" requestId)
    [ -n "$FONT" ] && [ -n "$REQUEST" ] && [ "$(ziyu_live_value "$NEXT" state)" = prepared ] || return 1
    [ "$FONT" != default ] || { live_defer restoring-default-requires-reboot; return 3; }
    OLD_SOURCE="$MODDIR/.luoshu-payload"
    OLD_WORK=/data/adb/luoshu/self-mount
    OLD_FONT=$(ziyu_live_value "$MODDIR/config/font-payload-activated.conf" font)
    if [ -z "$OLD_FONT" ]; then
        case "$(ziyu_live_value "$MODDIR/config/device-font-load-verification.conf" state)" in
            verified|partial) OLD_FONT=$(ziyu_live_value "$MODDIR/config/device-font-load-verification.conf" activeFont) ;;
        esac
    fi
    if ziyu_live_current; then
        OLD_SOURCE=$_zlc_source; OLD_WORK=$_zlc_root; OLD_FONT=$_zlc_font
    fi
    [ -n "$OLD_FONT" ] || OLD_FONT=$(ziyu_live_value "$MODDIR/config/font-payload-activated.conf" font)
    [ -n "$OLD_FONT" ] && [ "$OLD_FONT" != default ] && [ -s "$OLD_WORK/mounts.list" ] || { live_defer previous-font-route-unavailable; return 3; }
    if grep -q '\.xml|' "$MODDIR/config/font-payload-manifest.conf" 2>/dev/null; then
        live_defer previous-xml-runtime-requires-reboot; return 3
    fi
    if ! ( LUOSHU_SELF_MOUNT_STATE_ROOT="$OLD_WORK"; export LUOSHU_SELF_MOUNT_STATE_ROOT
           . "$MODDIR/common/mount_compat.sh" || exit 1
           _luoshu_atomic_partial_fonts_enabled && LUOSHU_PARTIAL_FONT_MOUNT=1
           export LUOSHU_PARTIAL_FONT_MOUNT
           _luoshu_atomic_verify_manifest "$MODDIR/config/self-mount-required.conf" ); then
        live_defer previous-mount-readback-failed; return 3
    fi
    live_log "正在建立独立字体代：$FONT；挂载方式=$MODE"
    CACHE="$MODDIR/.luoshu-state/cache/live/$BOOT"
    SOURCE=$(live_python "$LIVE_PY" prepare "$MODDIR/.luoshu-payload-next" "$CACHE" "$BOOT" "$CACHE/.prepare.$$" 2>> "$LIVE_LOG") || { live_defer immutable-generation-prepare-failed; return 3; }
    WORK="/data/adb/luoshu/live-mount/$BOOT.$$.apply"
    [ ! -e "$WORK" ] || { live_defer working-directory-already-exists; return 3; }
    # Boot may already need an ext4 loop layer on casefold F2FS. Check the
    # long-path helper before detaching that working font.
    if [ -n "$(find "$OLD_WORK/work" -maxdepth 1 -name 'overlay-*.img' -type f -print -quit 2>/dev/null)" ]; then
        ( . "$MODDIR/common/mount_overlay_layer.sh" &&
          _luoshu_overlay_select_loop_mounter ) || {
            live_defer ext4-loop-long-path-helper-unavailable; return 3
        }
    fi
    live_python "$LIVE_PY" "$MODDIR" begin "$OLD_SOURCE" "$OLD_FONT" "$OLD_WORK" "$SOURCE" "$FONT" "$WORK" "$MODE" "$BOOT" "$REQUEST" || return 1
    printf 'boot_id=%s\nstate=mounting\nfont=%s\nrequest_id=%s\n' "$BOOT" "$FONT" "$REQUEST" > "$MODDIR/config/font-live-attempt.conf"
    trap 'live_log "热切换中断，正在回滚"; live_recover; exit 1' HUP INT TERM
    if live_detach "$OLD_WORK" && live_mount "$SOURCE" "$FONT" "$WORK" "$MODE" >> "$LIVE_LOG" 2>&1 && \
       live_python "$LIVE_PY" "$MODDIR" record "$SOURCE" "$FONT" "$WORK" "$BOOT" "$REQUEST" && \
       live_python "$LIVE_PY" "$MODDIR" proof "$FONT" "$BOOT"; then
        live_python "$LIVE_PY" "$MODDIR" finish || return 1
        trap - HUP INT TERM
        rm -f "$MODDIR/config/text_reboot_required.conf" || return 1
        UI_CACHE=unknown
        if [ -f "$MODDIR/common/font_ui_cache.py" ]; then
            UI_CACHE=$(live_python "$MODDIR/common/font_ui_cache.py" "$MODDIR" 2>> "$LIVE_LOG")
        fi
        case "$UI_CACHE" in stale|current-mappings) ;; *) UI_CACHE=unknown ;; esac
        printf 'boot_id=%s\nstate=mounted\nfont=%s\nrequest_id=%s\nui_cache_state=%s\n' "$BOOT" "$FONT" "$REQUEST" "$UI_CACHE" > "$MODDIR/config/font-live-attempt.conf"
        if [ "$UI_CACHE" = stale ]; then
            live_log 'WARN 字体文件已热挂载；SystemUI 仍映射旧字体，可由用户确认软重启刷新系统界面'
        fi
        live_log "字体已热挂载且 PID 1 路由校验通过：$FONT；部分已打开界面可能仍使用字体缓存"
        return 0
    fi
    trap - HUP INT TERM
    if live_recover; then live_defer hot-mount-failed-previous-font-restored; return 3; fi
    live_log 'ERROR 热切换失败，上一套字体恢复未确认；请导出诊断日志'
    printf 'boot_id=%s\nstate=failed\nreason=hot-mount-and-recovery-unconfirmed\nfont=%s\nrequest_id=%s\n' "$BOOT" "$FONT" "$REQUEST" > "$MODDIR/config/font-live-attempt.conf"
    return 1
}

BOOT=$(luoshu_current_boot_id) || exit 1
ACTION="${1:-apply}"
case "$ACTION" in apply|recover|retire) ;; *) exit 2 ;; esac
# Ordinary reboot-only users do not need a live runtime or namespace entry.
[ "$ACTION" != recover ] || [ -e "$LIVE_JOURNAL" ] || exit 0
if [ "$ACTION" = retire ] && [ ! -e "$LIVE_JOURNAL" ] && [ ! -e "$MODDIR/config/font-live.conf" ]; then exit 0; fi
if [ "${ZIYU_LIVE_INNER:-0}" != 1 ]; then
    # Verify the actual canonical lock owner, not an untrusted bypass boolean.
    OWNER="${ZIYU_LIVE_OWNER_PID:-}"
    if [ -n "$OWNER" ]; then
        [ "$(luoshu_font_lock_pid "$MODDIR/.font_switch.lock")" = "$OWNER" ] && \
        [ "$(luoshu_font_lock_boot_id "$MODDIR/.font_switch.lock")" = "$BOOT" ] && \
        [ "$(luoshu_font_lock_starttime "$MODDIR/.font_switch.lock")" = "$(luoshu_process_starttime "$OWNER")" ] || exit 1
    else
        luoshu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || exit 1
        trap 'luoshu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1' EXIT
        OWNER="$$"
    fi
    ZIYU_LIVE_OWNER_PID="$OWNER" ZIYU_LIVE_INNER=1 \
        live_python "$LIVE_PY" --lock-exec "$MODDIR/.luoshu-state/tmp/font-live.lock" sh "$0" "$ACTION"
    exit $?
fi
# Both inherited identities must survive namespace entry.
[ -n "${ZIYU_LIVE_OWNER_PID:-}" ] && \
    [ "$(luoshu_font_lock_pid "$MODDIR/.font_switch.lock")" = "$ZIYU_LIVE_OWNER_PID" ] && \
    [ "$(luoshu_font_lock_starttime "$MODDIR/.font_switch.lock")" = "$(luoshu_process_starttime "$ZIYU_LIVE_OWNER_PID")" ] && \
    [ "$(readlink "/proc/self/fd/${ZIYU_LIVE_LOCK_FD:-invalid}")" = "$MODDIR/.luoshu-state/tmp/font-live.lock" ] || exit 1
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
    apply) live_apply; exit $? ;;
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
