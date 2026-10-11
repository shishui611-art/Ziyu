#!/system/bin/sh
# KernelSU runs this before its userspace soft reboot. The metamodule's hook
# runs first; release only our own self mounts and require a fresh backend choice.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
# This is a KernelSU stage hook, including manually requested soft reboots on
# persistent-root installations. Every such cycle must reset our boot choice.
mkdir -p "$MODDIR/config" "$MODDIR/logs" || exit 1
. "$MODDIR/common/font_live_state.sh" || exit 1
_self_root="${LUOSHU_SELF_MOUNT_STATE_ROOT:-$(ziyu_live_work_root)}"
[ -n "$_self_root" ] || _self_root=/data/adb/luoshu/self-mount

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
    # Provider hooks retract their scan rules first. Rescue rules are owned by
    # Ziyu's ledger and must be removed here even when active_backend=external.
    luoshu_nomount_cleanup "$MODDIR" || _soft_fail 'nomount-owned-rule-cleanup-unconfirmed'
    _nomount_rules=$(luoshu_nomount_module_rules_state "$MODDIR") || \
        _soft_fail 'nomount-live-rule-state-unknown'
    case "$_nomount_rules" in
        absent)
            printf '[TEMP-ROOT] NoMount has no live rule referencing LuoShu payload\n' \
                >> "$MODDIR/logs/mount-backend.log"
            ;;
        present) _soft_fail 'nomount-still-routes-luoshu-payload' ;;
        *) _soft_fail 'nomount-live-rule-state-invalid' ;;
    esac
fi

MODDIR="$MODDIR" sh "$MODDIR/common/font_live_switch.sh" retire || _soft_fail 'live-font-retirement-unconfirmed'

if { [ "$_state_boot" = "$_boot" ] && [ "$_backend" = self ]; } || \
   { [ "$(cat "$_self_root/boot-id" 2>/dev/null | tr -d '\r\n')" = "$_boot" ] && [ -s "$_self_root/mounts.list" ]; }; then
    . "$MODDIR/common/mount_backend_runtime.sh" || exit 1
    _lbr_restore_boot_choice
    LUOSHU_SELF_ALLOW_LAZY_UMOUNT=1
    export LUOSHU_SELF_ALLOW_LAZY_UMOUNT
    _lbr_rollback_self_attempt || _soft_fail 'self-mount-cleanup-failed'
fi

# A mounted module source cannot be renamed while Android is still using it.
if _soft_module_mount_remains; then
    _soft_fail 'old-payload-still-mounted'
fi

printf 'boot_id=%s\nstate=ready\n' "$_boot" > "${_stage}.tmp.$$" &&
    mv -f "${_stage}.tmp.$$" "$_stage" || exit 1
rm -f "$MODDIR/config/provider-rescue.conf" "$_state" 2>/dev/null || exit 1
printf '[TEMP-ROOT] KernelSU soft reboot: fresh mount selection requested\n' >> "$MODDIR/logs/mount-backend.log"
exit 0
