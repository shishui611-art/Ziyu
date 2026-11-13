#!/system/bin/sh
# Runs once per manager userspace reload, after font publication, before start.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
_srf_file="$MODDIR/config/temporary-root-soft-reboot.conf"
_srf_boot="${ZIYU_TEST_BOOT_ID:-}"
_srf_saved=''; _srf_state=''; _srf_zygote_service=''; _srf_old_zygote_pid=''
[ -n "$_srf_boot" ] || IFS= read -r _srf_boot < /proc/sys/kernel/random/boot_id || exit 1
[ -s "$_srf_file" ] || exit 0
while IFS='=' read -r _srf_k _srf_v; do
    case "$_srf_k" in
        boot_id) _srf_saved=$_srf_v ;;
        state) _srf_state=$_srf_v ;;
        zygote_service) _srf_zygote_service=$_srf_v ;;
        old_zygote_pid) _srf_old_zygote_pid=$_srf_v ;;
    esac
done < "$_srf_file"
[ "$_srf_saved" = "$_srf_boot" ] && [ "$_srf_state" = ready ] || exit 0
# The shared entry proves KernelSU has stopped Android before PID preparation.
# A private manager child must not bypass that check.
MODDIR="$MODDIR" sh "$MODDIR/common/pid_reload.sh" > "$MODDIR/config/pid-wrap-last.conf.tmp.$$" 2>> "$MODDIR/logs/soft-reboot.log"
_srf_rc=$?
mv -f "$MODDIR/config/pid-wrap-last.conf.tmp.$$" "$MODDIR/config/pid-wrap-last.conf" || exit 1
cat "$MODDIR/config/pid-wrap-last.conf" >> "$MODDIR/logs/soft-reboot.log"
_srf_wrap_state=$(sed -n 's/^state=//p' "$MODDIR/config/pid-wrap-last.conf" 2>/dev/null | head -n1)
_srf_restored=$(sed -n 's/^restored=//p' "$MODDIR/config/pid-wrap-last.conf" 2>/dev/null | head -n1)
case "$_srf_wrap_state:$_srf_rc" in
    wrapped:0) [ "$_srf_restored" = 1 ] || _srf_wrap_state=restore-failed ;;
    already-low:0) ;;
    *) [ -n "$_srf_wrap_state" ] || _srf_wrap_state=unavailable ;;
esac
case "$_srf_wrap_state:$_srf_rc" in
    wrapped:0|already-low:0)
        printf 'boot_id=%s\nstate=reload-prepared\npid_wrap_rc=0\npid_wrap_state=%s\nzygote_service=%s\nold_zygote_pid=%s\n' \
            "$_srf_boot" "$_srf_wrap_state" "$_srf_zygote_service" "$_srf_old_zygote_pid" > "$_srf_file.tmp.$$" || exit 1
        ;;
    *)
        printf 'boot_id=%s\nstate=ppid-reset-failed\npid_wrap_rc=%s\npid_wrap_state=%s\nzygote_service=%s\nold_zygote_pid=%s\n' \
            "$_srf_boot" "$_srf_rc" "$_srf_wrap_state" "$_srf_zygote_service" "$_srf_old_zygote_pid" > "$_srf_file.tmp.$$" || exit 1
        mv -f "$_srf_file.tmp.$$" "$_srf_file" || exit 1
        printf '[SOFT-REBOOT] PPID reset failed: state=%s rc=%s\n' "$_srf_wrap_state" "$_srf_rc" >> "$MODDIR/logs/soft-reboot.log"
        exit 1
        ;;
esac
mv -f "$_srf_file.tmp.$$" "$_srf_file" || exit 1
exit 0
