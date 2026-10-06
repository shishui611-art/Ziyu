#!/system/bin/sh
# Ownership-safe helpers for the root-manager skip markers used by Ziyu.
set +e

_zso_module() { printf '%s\n' "${1:-${MODDIR:-${MODULE_DIR:-/data/adb/modules/Ziyu}}}"; }

ziyu_skip_migrate_legacy_ownership() {
    _zso_module_path=$(_zso_module "${1:-}")
    _zso_legacy="$_zso_module_path/config/self-mount-owned"
    [ -f "$_zso_legacy" ] || return 0
    if [ -e "$_zso_module_path/skip_mount" ] && [ ! -e "$_zso_module_path/.ziyu_skip_mount_owned" ]; then
        : > "$_zso_module_path/.ziyu_skip_mount_owned" 2>/dev/null || return 1
    fi
    if [ -e "$_zso_module_path/skip_mountify" ] && [ ! -e "$_zso_module_path/.ziyu_skip_mountify_owned" ]; then
        : > "$_zso_module_path/.ziyu_skip_mountify_owned" 2>/dev/null || return 1
    fi
    rm -f "$_zso_legacy" 2>/dev/null || return 1
}

ziyu_skip_marker_owned() {
    _zso_module_path=$(_zso_module "${1:-}")
    case "${2:-skip_mount}" in
        skip_mount) [ -f "$_zso_module_path/.ziyu_skip_mount_owned" ] ;;
        skip_mountify) [ -f "$_zso_module_path/.ziyu_skip_mountify_owned" ] ;;
        *) return 1 ;;
    esac
}

ziyu_foreign_skip_mount_present() {
    _zso_module_path=$(_zso_module "${1:-}")
    [ -e "$_zso_module_path/skip_mount" ] || return 1
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 0
    ! ziyu_skip_marker_owned "$_zso_module_path" skip_mount
}

ziyu_skip_marker_claim() {
    _zso_module_path=$(_zso_module "${1:-}")
    _zso_marker="${2:-skip_mount}"
    case "$_zso_marker" in
        skip_mount) _zso_owner="$_zso_module_path/.ziyu_skip_mount_owned" ;;
        skip_mountify) _zso_owner="$_zso_module_path/.ziyu_skip_mountify_owned" ;;
        *) return 2 ;;
    esac
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 1
    if [ -e "$_zso_module_path/$_zso_marker" ]; then
        [ -f "$_zso_owner" ] && return 0
        return 2
    fi
    : > "$_zso_module_path/$_zso_marker" 2>/dev/null || return 1
    : > "$_zso_owner" 2>/dev/null || {
        rm -f "$_zso_module_path/$_zso_marker" 2>/dev/null || true
        return 1
    }
    return 0
}

ziyu_skip_marker_release() {
    _zso_module_path=$(_zso_module "${1:-}")
    _zso_marker="${2:-skip_mount}"
    case "$_zso_marker" in
        skip_mount) _zso_owner="$_zso_module_path/.ziyu_skip_mount_owned" ;;
        skip_mountify) _zso_owner="$_zso_module_path/.ziyu_skip_mountify_owned" ;;
        *) return 2 ;;
    esac
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 1
    [ -f "$_zso_owner" ] || return 0
    rm -f "$_zso_module_path/$_zso_marker" "$_zso_owner" 2>/dev/null
}

ziyu_skip_release_owned() {
    _zso_module_path=$(_zso_module "${1:-}")
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 1
    ziyu_skip_marker_release "$_zso_module_path" skip_mount || return 1
    ziyu_skip_marker_release "$_zso_module_path" skip_mountify || return 1
    return 0
}
