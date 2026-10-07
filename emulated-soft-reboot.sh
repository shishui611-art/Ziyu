#!/system/bin/sh
# KernelSU runs this before its userspace soft reboot. The metamodule's hook
# runs first; release only our own self mounts and require a fresh backend choice.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
. "$MODDIR/common/temporary_root_mode.sh" || exit 1
ziyu_temporary_root_mode "$MODDIR" || exit 0

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

[ "$_provider" != nomount ] || _soft_fail 'nomount-live-rule-cleanup-unconfirmed'

if [ "$_state_boot" = "$_boot" ] && [ "$_backend" = self ]; then
    . "$MODDIR/common/mount_backend_runtime.sh" || exit 1
    _lbr_restore_boot_choice
    _lbr_rollback_self_attempt || _soft_fail 'self-mount-cleanup-failed'
fi

# A mounted module source cannot be renamed while Android is still using it.
if grep -F "$MODDIR/" /proc/1/mountinfo >/dev/null 2>&1; then
    _soft_fail 'old-payload-still-mounted'
fi

printf 'boot_id=%s\nstate=ready\n' "$_boot" > "${_stage}.tmp.$$" &&
    mv -f "${_stage}.tmp.$$" "$_stage" || exit 1
rm -f "$_state" 2>/dev/null || exit 1
printf '[TEMP-ROOT] KernelSU soft reboot: fresh mount selection requested\n' >> "$MODDIR/logs/mount-backend.log"
exit 0
