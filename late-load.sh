#!/system/bin/sh
# KernelSU late-load replaces post-fs-data after Android has already booted.
# Activate the staged generation before the metamodule scans module files.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
case "${KSU_LATE_LOAD:-}:${KSU_RUNTIME_MODE:-}" in
    1:*|*:late-load) ;;
    *) exit 0 ;;
esac

mkdir -p "$MODDIR/config" "$MODDIR/logs" 2>/dev/null || exit 1
_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
[ -n "$_boot" ] || exit 1
_marker="$MODDIR/config/temporary-root-session.conf"
printf 'boot_id=%s\nmode=late-load\n' "$_boot" > "${_marker}.tmp.$$" 2>/dev/null &&
    chmod 0600 "${_marker}.tmp.$$" 2>/dev/null &&
    mv -f "${_marker}.tmp.$$" "$_marker" 2>/dev/null || exit 1
printf '[%s] [TEMP-ROOT] KernelSU late-load started\n' "$(date '+%Y-%m-%d %H:%M:%S')" >> "$MODDIR/logs/mount-backend.log"

[ ! -f "$MODDIR/common/skip_mount_ownership.sh" ] || . "$MODDIR/common/skip_mount_ownership.sh"
if [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] &&
   ! { type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$MODDIR"; }; then
    if [ -s "$MODDIR/config/universal-font-next.conf" ]; then
        . "$MODDIR/common/universal_next_boot.sh" || exit 1
        universal_font_next_boot_activate
        _activate_rc=$?
    else
        . "$MODDIR/common/next_boot_payload.sh" || exit 1
        luoshu_next_boot_activate
        _activate_rc=$?
    fi
    case "$_activate_rc" in
        0|2) ;;
        *) printf '[TEMP-ROOT] staged payload activation failed: rc=%s\n' "$_activate_rc" >> "$MODDIR/logs/mount-backend.log"; exit 1 ;;
    esac
fi

MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data \
    >> "$MODDIR/logs/mount-backend.log" 2>&1
exit $?
