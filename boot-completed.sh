#!/system/bin/sh
# Reliable final convergence stage for KernelSU/APatch. service.sh remains the
# Magisk-compatible fallback, but post-boot verification must not be one-shot.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
mkdir -p "$MODDIR/logs" 2>/dev/null || true

# 清理过期的“待重启”标记：后端已确认本次启动挂载/验证通过时，字体已经生效，
# 不应继续要求重启（否则 apply 会被“请先重启手机”拦住）。
if [ -f "$MODDIR/config/mount-backend.conf" ] && [ -f "$MODDIR/config/text_reboot_required.conf" ]; then
    _bc_now=''
    IFS= read -r _bc_now < /proc/sys/kernel/random/boot_id 2>/dev/null || _bc_now=''
    _bc_boot=''; _bc_ver=''; _bc_active=''
    while IFS= read -r _bc_line || [ -n "$_bc_line" ]; do
        case "$_bc_line" in
            boot_id=*) _bc_boot=${_bc_line#boot_id=} ;;
            verification=*) _bc_ver=${_bc_line#verification=} ;;
            active_backend=*) _bc_active=${_bc_line#active_backend=} ;;
        esac
    done < "$MODDIR/config/mount-backend.conf"
    case "$_bc_ver" in
        passed|pass)
            case "$_bc_active" in
                manager|self|meta|external)
                    if [ -n "$_bc_now" ] && [ "$_bc_boot" = "$_bc_now" ]; then
                        rm -f "$MODDIR/config/text_reboot_required.conf" 2>/dev/null || true
                    fi
                    ;;
            esac
            ;;
    esac
fi

[ -f "$MODDIR/common/util_functions.sh" ] && . "$MODDIR/common/util_functions.sh"
[ -f "$MODDIR/common/font_config_runtime.sh" ] && . "$MODDIR/common/font_config_runtime.sh"
[ -f "$MODDIR/common/font_config_partitions.sh" ] && . "$MODDIR/common/font_config_partitions.sh"
[ -f "$MODDIR/common/mount_compat.sh" ] && . "$MODDIR/common/mount_compat.sh"
[ -f "$MODDIR/common/mount_self_backend.sh" ] && . "$MODDIR/common/mount_self_backend.sh"
[ -f "$MODDIR/common/font_boot_state.sh" ] && . "$MODDIR/common/font_boot_state.sh"

ziyu_font_rebuild_marker_reconcile >/dev/null 2>&1 || true
_lfbc_active=$(head -n1 "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
[ -n "$_lfbc_active" ] || _lfbc_active=default
if [ "$_lfbc_active" != default ]; then
    # The physical compatibility compositor has its own mount-confirmation
    # contract in service.sh and deliberately carries no v4 device-template
    # manifest. Running the v4 guard here falsely labels every successfully
    # activated composite as an expired payload and leaves the App showing both
    # "confirmed" and "next payload failed" at the same time.
    if [ ! -f "$MODDIR/config/font_runtime_legacy_v14_4.conf" ]; then
        type font_config_boot_guard >/dev/null 2>&1 && \
            font_config_boot_guard "$_lfbc_active" >/dev/null 2>&1 || true
    fi
fi

# Recheck the selected backend without invoking a second mount path.
if [ -f "$MODDIR/common/mount_backend_runtime.sh" ]; then
    mkdir -p "$MODDIR/logs" 2>/dev/null || true
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook service \
        >> "$MODDIR/logs/mount-backend.log" 2>&1 || true
fi

_lfbc_attempt=1
while [ "$_lfbc_attempt" -le 5 ]; do
    ziyu_text_reboot_reconcile >/dev/null 2>&1 && exit 0
    [ "$_lfbc_attempt" -ge 5 ] || sleep 3
    _lfbc_attempt=$((_lfbc_attempt + 1))
done
exit 0
