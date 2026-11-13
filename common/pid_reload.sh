#!/system/bin/sh
# Bounded PID preparation for KernelSU's managed soft reboot.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
[ "$(id -u 2>/dev/null)" = 0 ] || exit 1
case "${1:-}" in ''|--in-init) ;; *) exit 2 ;; esac
_pr_init_mnt=$(readlink /proc/1/ns/mnt 2>/dev/null)
_pr_self_mnt=$(readlink /proc/self/ns/mnt 2>/dev/null)
[ -n "$_pr_init_mnt" ] && [ -n "$_pr_self_mnt" ] || exit 2
_pr_mnt_same=0
[ "$_pr_self_mnt" = "$_pr_init_mnt" ] && _pr_mnt_same=1

_pr_init_pid=$(readlink /proc/1/ns/pid 2>/dev/null)
_pr_self_pid=$(readlink /proc/self/ns/pid 2>/dev/null)
_pr_pid_same=0
if [ -n "$_pr_init_pid" ] && [ -n "$_pr_self_pid" ]; then
    [ "$_pr_self_pid" = "$_pr_init_pid" ] && _pr_pid_same=1
elif [ -z "$_pr_init_pid" ] && [ -z "$_pr_self_pid" ]; then
    # Kernels built without CONFIG_PID_NS expose neither handle. In that case
    # there is no PID namespace to enter; never pass an unsupported -p flag.
    _pr_pid_same=1
else
    exit 2
fi

if [ "$_pr_mnt_same" != 1 ] || [ "$_pr_pid_same" != 1 ]; then
    [ "${1:-}" != --in-init ] || exit 2
    _pr_nsenter=$(command -v nsenter 2>/dev/null) || exit 2
    _pr_ns_args=''
    [ "$_pr_mnt_same" = 1 ] || _pr_ns_args="$_pr_ns_args -m"
    [ "$_pr_pid_same" = 1 ] || _pr_ns_args="$_pr_ns_args -p"
    set -- $_pr_ns_args -- sh "$MODDIR/common/pid_reload.sh" --in-init
    exec "$_pr_nsenter" -t 1 "$@"
fi
. "$MODDIR/common/soft_reboot_window.sh" || exit 1
ziyu_soft_reboot_window_ready || { printf 'pid-preparation: %s\n' "$ZIYU_SOFT_WINDOW_REASON" >&2; exit 2; }
case "$(getprop ro.product.cpu.abi)" in
    arm64-v8a) _pr_abi=arm64 ;; armeabi*) _pr_abi=arm ;;
    x86_64) _pr_abi=x86_64 ;; x86) _pr_abi=x86 ;; *) exit 1 ;;
esac
_pr_tool="$MODDIR/common/pid-wrap/pid_wrap_$_pr_abi"
[ -x "$_pr_tool" ] || exit 1
mkdir -p /data/adb/ziyu || exit 1
exec "$_pr_tool" --managed-reload
