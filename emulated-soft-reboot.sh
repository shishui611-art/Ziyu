#!/system/bin/sh
# KernelSU runs this before its userspace soft reboot. The metamodule's hook
# runs first; release only our own self mounts and require a fresh backend choice.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
# This is a KernelSU stage hook, including manually requested soft reboots on
# persistent-root installations. Every such cycle must reset our boot choice.
mkdir -p "$MODDIR/config" "$MODDIR/logs" || exit 1
# KernelSU invokes this hook BEFORE stop. Never rename a payload or remove a
# NoMount rule while Android still holds its files. Resume after stop instead.
if [ "${ZIYU_KSU_POST_STOP_RELOAD:-0}" != 1 ]; then
    _boot=''
    IFS= read -r _boot < /proc/sys/kernel/random/boot_id || exit 1
    [ -n "$_boot" ] || exit 1
    _zygote_service=''; _zygote_pid=''
    for _service in zygote64 zygote; do
        [ "$(getprop "init.svc.$_service" 2>/dev/null)" = running ] || continue
        _pids=$(pidof "$_service" 2>/dev/null)
        set -- $_pids
        case "${1:-}" in ''|*[!0-9]*) continue ;; esac
        _zygote_service=$_service
        _zygote_pid=$1
        break
    done
    {
        printf 'boot_id=%s\nstate=cleanup-pending\nzygote_service=%s\nold_zygote_pid=%s\n' \
            "$_boot" "$_zygote_service" "$_zygote_pid"
        printf 'zygote=%s\nsystem_server=%s\n' "$(pidof zygote64 zygote 2>/dev/null)" "$(pidof system_server 2>/dev/null)"
    } > "$MODDIR/config/temporary-root-soft-reboot.conf.tmp.$$" &&
        mv -f "$MODDIR/config/temporary-root-soft-reboot.conf.tmp.$$" \
            "$MODDIR/config/temporary-root-soft-reboot.conf" || exit 1
    exit 0
fi
. "$MODDIR/common/soft_reboot_window.sh" || exit 1
if ! ziyu_soft_reboot_window_wait; then
    _boot=''
    IFS= read -r _boot < /proc/sys/kernel/random/boot_id || exit 1
    printf 'boot_id=%s\nstate=failed\nreason=%s\n' "$_boot" "$ZIYU_SOFT_WINDOW_REASON" \
        > "$MODDIR/config/temporary-root-soft-reboot.conf.tmp.$$" &&
        mv -f "$MODDIR/config/temporary-root-soft-reboot.conf.tmp.$$" \
            "$MODDIR/config/temporary-root-soft-reboot.conf"
    printf '[SOFT-REBOOT] post-stop readiness failed: %s\n' "$ZIYU_SOFT_WINDOW_REASON" >&2
    exit 1
fi
. "$MODDIR/common/font_live_state.sh" || exit 1
_self_root="${ZIYU_SELF_MOUNT_STATE_ROOT:-$(ziyu_live_work_root)}"
[ -n "$_self_root" ] || _self_root=/data/adb/ziyu/self-mount

_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
[ -n "$_boot" ] || exit 1
_state="$MODDIR/config/mount-backend.conf"
_stage="$MODDIR/config/temporary-root-soft-reboot.conf"
_state_boot=$(sed -n 's/^boot_id=//p' "$_state" 2>/dev/null | head -n1)
_backend=$(sed -n 's/^active_backend=//p' "$_state" 2>/dev/null | head -n1)
_provider=$(sed -n 's/^provider_id=//p' "$_state" 2>/dev/null | head -n1)

_soft_fail() {
    printf 'boot_id=%s\nstate=failed\nreason=%s\n' "$_boot" "$1" > "$_stage"
    printf '[TEMP-ROOT] soft reboot refused: %s\n' "$1" >> "$MODDIR/logs/mount-backend.log"
    exit 1
}

# The provider's hook has already run. A copied/global overlay does not contain
# MODDIR in mountinfo, so the module-source check below cannot detect stale fonts.
. "$MODDIR/common/mount_provider_reload.sh" || _soft_fail 'provider-reload-check-unavailable'

_soft_module_mount_remains() {
    [ -r /proc/1/mountinfo ] || return 0
    _soft_module_relative="${MODDIR#/data}"
    grep -F "$MODDIR/" /proc/1/mountinfo >/dev/null 2>&1 && return 0
    if [ "$_soft_module_relative" != "$MODDIR" ]; then
        grep -F "$_soft_module_relative/" /proc/1/mountinfo >/dev/null 2>&1 && return 0
    fi
    # Directory mirrors use state storage rather than the private payload path.
    grep -F "$_self_root/" /proc/1/mountinfo >/dev/null 2>&1 && return 0
    _soft_state_relative="${_self_root#/data}"
    if [ "$_soft_state_relative" != "$_self_root" ]; then
        grep -F "$_soft_state_relative/" /proc/1/mountinfo >/dev/null 2>&1 && return 0
    fi
    return 1
}

if [ "$_provider" = nomount ] || [ -e "$MODDIR/.ziyu-state/nomount-rules.current" ]; then
    . "$MODDIR/common/mount_nomount_backend.sh" || _soft_fail 'nomount-cleanup-check-unavailable'
    # KernelSU runs this stage before `stop`. Defer NoMount writes until the
    # post-fs-data callback, after manager/WebUI and Android apps are stopped.
    if [ "${ZIYU_KSU_POST_STOP_RELOAD:-0}" != 1 ]; then
        printf 'boot_id=%s\nstate=nomount-cleanup-pending\n' "$_boot" > "${_stage}.tmp.$$" &&
            mv -f "${_stage}.tmp.$$" "$_stage" || exit 1
        printf '[TEMP-ROOT] NoMount cleanup deferred until Android services stop\n' >> "$MODDIR/logs/mount-backend.log"
        exit 0
    fi
    ziyu_nomount_reload_window_ready || _soft_fail 'nomount-cleanup-window-not-quiescent'
    # Rescue and provider pairs share the font-transaction lock in this stage.
    ziyu_nomount_provider_reload_cleanup "$MODDIR" || \
        _soft_fail 'nomount-provider-owned-rule-cleanup-unconfirmed'
    _nomount_rules=$(ziyu_nomount_module_rules_state "$MODDIR") || \
        _soft_fail 'nomount-live-rule-state-unknown'
    case "$_nomount_rules" in
        absent)
            printf '[TEMP-ROOT] NoMount has no live rule referencing Ziyu payload\n' \
                >> "$MODDIR/logs/mount-backend.log"
            ;;
        present) _soft_fail 'nomount-still-routes-ziyu-payload' ;;
        *) _soft_fail 'nomount-live-rule-state-invalid' ;;
    esac
fi

MODDIR="$MODDIR" sh "$MODDIR/common/font_live_switch.sh" retire || _soft_fail 'live-font-retirement-unconfirmed'

if { [ "$_state_boot" = "$_boot" ] && [ "$_backend" = self ]; } || \
   { [ "$(cat "$_self_root/boot-id" 2>/dev/null | tr -d '\r\n')" = "$_boot" ] && [ -s "$_self_root/mounts.list" ]; }; then
    . "$MODDIR/common/mount_backend_runtime.sh" || exit 1
    _lbr_restore_boot_choice
    ZIYU_SELF_ALLOW_LAZY_UMOUNT=1
    export ZIYU_SELF_ALLOW_LAZY_UMOUNT
    _lbr_rollback_self_attempt || _soft_fail 'self-mount-cleanup-failed'
fi

# Retire our mounts before validating provider ownership: a Ziyu-owned child
# mount must not be mistaken for an unrelated module's mount above Mountify.
ziyu_provider_reload_check "$MODDIR" reload || \
    _soft_fail "${PROVIDER_RELOAD_REASON:-provider-reload-cleanup-unconfirmed}"

# A mounted module source cannot be renamed while Android is still using it.
if _soft_module_mount_remains; then
    _soft_fail 'old-payload-still-mounted'
fi

_saved_zygote_service=$(sed -n 's/^zygote_service=//p' "$_stage" | head -n1)
_saved_zygote_pid=$(sed -n 's/^old_zygote_pid=//p' "$_stage" | head -n1)
printf 'boot_id=%s\nstate=ready\nzygote_service=%s\nold_zygote_pid=%s\n' \
    "$_boot" "$_saved_zygote_service" "$_saved_zygote_pid" > "${_stage}.tmp.$$" &&
    mv -f "${_stage}.tmp.$$" "$_stage" || exit 1
rm -f "$MODDIR/config/provider-rescue.conf" "$_state" 2>/dev/null || exit 1
printf '[TEMP-ROOT] KernelSU soft reboot: fresh mount selection requested\n' >> "$MODDIR/logs/mount-backend.log"
exit 0
