#!/system/bin/sh
# Enforce the selected backend before entering the existing mount transaction.
set +e

luoshu_private_self_mount_ensure() {
    type luoshu_self_mount_ensure >/dev/null 2>&1 || return 1
    _lpmp_module="${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}"
    _lpmp_state="$_lpmp_module/config/mount-backend.conf"
    _lpmp_selected=$(sed -n 's/^selected_backend=//p' "$_lpmp_state" 2>/dev/null | head -n1)
    _lpmp_active=$(sed -n 's/^active_backend=//p' "$_lpmp_state" 2>/dev/null | head -n1)
    if [ "$_lpmp_selected" = meta ] && [ "$_lpmp_active" != self ]; then
        [ -f "$_lpmp_module/logs/mount-backend.log" ] && \
            printf '[%s] SELF-MOUNT denied: meta backend is selected and not released\n' \
                "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" \
                >> "$_lpmp_module/logs/mount-backend.log" 2>/dev/null || true
        return 90
    fi
    luoshu_self_mount_ensure "$@"
    _lpmp_rc=$?
    if [ "$_lpmp_rc" -eq 0 ]; then
        # A self mount must not be picked up again by a second module engine on
        # the next boot. The mount runtime owns these markers and removes them
        # only when selecting Meta.
        : > "$_lpmp_module/skip_mount" 2>/dev/null || true
        : > "$_lpmp_module/skip_mountify" 2>/dev/null || true
        : > "$_lpmp_module/config/self-mount-owned" 2>/dev/null || true
        rm -f "$_lpmp_module/mount_error" 2>/dev/null || true
    fi
    return "$_lpmp_rc"
}
