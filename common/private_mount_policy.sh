#!/system/bin/sh
# All system injection is routed through this one guarded entry.
set +e
luoshu_private_self_mount_ensure() {
    _lpmp_module="${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}"
    [ ! -f "$_lpmp_module/common/skip_mount_ownership.sh" ] || . "$_lpmp_module/common/skip_mount_ownership.sh"
    if type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$_lpmp_module"; then
        return 90
    fi
    _lpmp_state="$_lpmp_module/config/mount-backend.conf"
    _lpmp_selected=$(sed -n 's/^selected_backend=//p' "$_lpmp_state" 2>/dev/null | head -n1)
    _lpmp_provider=$(sed -n 's/^provider_state=//p' "$_lpmp_state" 2>/dev/null | head -n1)
    _lpmp_fallback=$(sed -n 's/^fallback_used=//p' "$_lpmp_state" 2>/dev/null | head -n1)
    _lpmp_preference=$(sed -n 's/^preferred_backend=//p' "$_lpmp_state" 2>/dev/null | head -n1)
    _lpmp_boot="${LUOSHU_BACKEND_TEST_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"
    [ -n "$_lpmp_boot" ] && [ "$_lpmp_selected" = self ] && \
        [ "$(sed -n 's/^boot_id=//p' "$_lpmp_state" 2>/dev/null | head -n1)" = "$_lpmp_boot" ] || return 90
    case "$_lpmp_provider:$_lpmp_fallback" in
        absent:*|*:1) ;;
        *)
            case "$_lpmp_preference:$_lpmp_selected" in
                magic:self|overlayfs:self|self_mount:self) ;;
                *) return 90 ;;
            esac
            ;;
    esac
    [ ! -e "$_lpmp_module/disable" ] && [ ! -e "$_lpmp_module/remove" ] || return 90
    if type ziyu_skip_marker_claim >/dev/null 2>&1; then
        ziyu_skip_marker_claim "$_lpmp_module" skip_mount || return 1
        ziyu_skip_marker_claim "$_lpmp_module" skip_mountify
        _lpmp_rc=$?
        [ "$_lpmp_rc" -eq 0 ] || [ "$_lpmp_rc" -eq 2 ] || return 1
    fi
    luoshu_self_mount_ensure "$@" || return $?
    rm -f "$_lpmp_module/mount_error"
}
