#!/system/bin/sh
# Confirm the managed reload restarted zygote and gave it a low parent PID.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
_srv_file="$MODDIR/config/temporary-root-soft-reboot.conf"
_srv_log="$MODDIR/logs/soft-reboot.log"
_srv_boot="${ZIYU_TEST_BOOT_ID:-}"
_srv_saved=''; _srv_state=''; _srv_wrap_state=''
_srv_wrap_rc=''; _srv_service=''; _srv_old_pid=''
[ -n "$_srv_boot" ] || IFS= read -r _srv_boot < /proc/sys/kernel/random/boot_id || exit 1
[ -s "$_srv_file" ] || exit 0
while IFS='=' read -r _srv_key _srv_value; do
    case "$_srv_key" in
        boot_id) _srv_saved=$_srv_value ;;
        state) _srv_state=$_srv_value ;;
        pid_wrap_state) _srv_wrap_state=$_srv_value ;;
        pid_wrap_rc) _srv_wrap_rc=$_srv_value ;;
        zygote_service) _srv_service=$_srv_value ;;
        old_zygote_pid) _srv_old_pid=$_srv_value ;;
    esac
done < "$_srv_file"
[ "$_srv_saved" = "$_srv_boot" ] || exit 0
case "$_srv_state" in reload-prepared|ppid-reset-failed) ;; *) exit 0 ;; esac
_srv_receipt=$(cat "$_srv_file")

_srv_fail() {
    _srv_reason=$1
    [ "$(cat "$_srv_file")" = "$_srv_receipt" ] || exit 2
    {
        printf 'boot_id=%s\nstate=ppid-reset-failed\nreason=%s\n' "$_srv_boot" "$_srv_reason"
        printf 'pid_wrap_rc=%s\npid_wrap_state=%s\nzygote_service=%s\nold_zygote_pid=%s\n' \
            "$_srv_wrap_rc" "$_srv_wrap_state" "$_srv_service" "$_srv_old_pid"
    } > "$_srv_file.tmp.$$" && mv -f "$_srv_file.tmp.$$" "$_srv_file"
    printf '[SOFT-REBOOT] PPID reset verification failed: %s\n' "$_srv_reason" >> "$_srv_log" 2>/dev/null
    exit 1
}

case "$_srv_wrap_state:$_srv_wrap_rc" in wrapped:0|already-low:0) ;; *) _srv_fail pid-wrap-not-confirmed ;; esac
_srv_pid=''; _srv_attempt=0
while [ "$_srv_attempt" -lt 180 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] && \
       { [ "$(getprop init.svc.zygote 2>/dev/null)" = running ] ||
         [ "$(getprop init.svc.zygote64 2>/dev/null)" = running ]; }; then
        for _srv_candidate in $(pidof zygote64 zygote 2>/dev/null); do
            case "$_srv_candidate" in ''|*[!0-9]*) continue ;; esac
            case "$(readlink "/proc/$_srv_candidate/exe" 2>/dev/null)" in
                */app_process|*/app_process32|*/app_process64)
                    _srv_pid=$_srv_candidate
                    break ;;
            esac
        done
        [ -z "$_srv_pid" ] || break
    fi
    [ "${1:-}" != reconcile ] || exit 2
    sleep 1
    _srv_attempt=$((_srv_attempt + 1))
done
[ -n "$_srv_pid" ] || _srv_fail zygote-not-ready
[ "$_srv_pid" -lt 5000 ] 2>/dev/null || _srv_fail zygote-pid-above-4999

[ "$(cat "$_srv_file")" = "$_srv_receipt" ] || exit 2

{
    printf 'boot_id=%s\nstate=ppid-reset-verified\n' "$_srv_boot"
    printf 'pid_wrap_rc=%s\npid_wrap_state=%s\nzygote_service=%s\nold_zygote_pid=%s\nnew_zygote_pid=%s\n' \
        "$_srv_wrap_rc" "$_srv_wrap_state" "$_srv_service" "$_srv_old_pid" "$_srv_pid"
} > "$_srv_file.tmp.$$" && mv -f "$_srv_file.tmp.$$" "$_srv_file" || exit 1
printf '[SOFT-REBOOT] PPID reset verified: service=%s old_pid=%s new_pid=%s\n' \
    "$_srv_service" "$_srv_old_pid" "$_srv_pid" >> "$_srv_log" 2>/dev/null
exit 0
