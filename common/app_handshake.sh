#!/system/bin/sh
# Read-only connection handshake. No font engine, mount discovery or Python.
set +e
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
_ah_cr=$(printf '\r')
_ah_uid=$(id -u 2>/dev/null)
_ah_id=''; _ah_version=''; _ah_code=0; _ah_active=default
_ah_root=Root; _ah_installed=false; _ah_pending=false; _ah_disabled=false; _ah_remove=false
_ah_prop() {
    [ -r "$1" ] || return 0
    while IFS= read -r _ah_line || [ -n "$_ah_line" ]; do
        _ah_line=${_ah_line%"$_ah_cr"}
        case "$_ah_line" in "$2"=*) printf '%s' "${_ah_line#*=}"; return ;; esac
    done < "$1"
}
_ah_json() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\r' '  '; }
[ "$_ah_uid" = 0 ] || { printf '{"status":"error","message":"Root 尚未授权"}\n'; exit 1; }
if [ -f "$MODDIR/module.prop" ]; then
    while IFS= read -r _ah_line || [ -n "$_ah_line" ]; do
        _ah_line=${_ah_line%"$_ah_cr"}
        case "$_ah_line" in id=*) _ah_id=${_ah_line#*=} ;; version=*) _ah_version=${_ah_line#*=} ;; versionCode=*) _ah_code=${_ah_line#*=} ;; esac
    done < "$MODDIR/module.prop"
    case "$_ah_id" in ziyu|LuoShu) _ah_installed=true ;; esac
fi
case "$_ah_code" in ''|*[!0-9]*) _ah_code=0 ;; esac
[ ! -e "$MODDIR/disable" ] || _ah_disabled=true
[ ! -e "$MODDIR/remove" ] || _ah_remove=true
case "$MODDIR" in
    */modules/ziyu|*/modules/LuoShu)
        _ah_update="${MODDIR%/modules/*}/modules_update/${MODDIR##*/}"
        [ ! -f "$_ah_update/module.prop" ] || _ah_pending=true ;;
esac
_ah_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ -n "$_ah_boot" ] && [ "$(_ah_prop "$MODDIR/config/mount-backend.conf" boot_id)" = "$_ah_boot" ]; then
    case "$(_ah_prop "$MODDIR/config/mount-backend.conf" schema)" in
        ziyu-mount-backend-v1|ziyu-mount-backend-v2|ziyu-mount-backend-v3)
            case "$(_ah_prop "$MODDIR/config/mount-backend.conf" root_manager)" in
                Magisk|KernelSU|APatch|'SukiSU Ultra') _ah_root=$(_ah_prop "$MODDIR/config/mount-backend.conf" root_manager) ;;
            esac ;;
    esac
fi
if [ -r "$MODDIR/config/active_font.conf" ]; then IFS= read -r _ah_active < "$MODDIR/config/active_font.conf"; fi
_ah_active=${_ah_active%"$_ah_cr"}
[ -n "$_ah_active" ] || _ah_active=default
if [ "${1:-}" = probe ]; then
    printf 'uid=0\nroot_manager=%s\nmodule_installed=%s\nmodule_pending_update=%s\nmodule_disabled=%s\nmodule_remove_pending=%s\nmodule_id_matches=%s\nmodule_version=%s\nbridge_responding=%s\n' \
        "$_ah_root" "$_ah_installed" "$_ah_pending" "$_ah_disabled" "$_ah_remove" "$_ah_installed" "$_ah_version" "$_ah_installed"
else
    printf '{"status":"ok","schema":"ziyu-app-handshake-v1","data":{"root":true,"installed":%s,"version":"%s","versionCode":%s,"active":"%s","rootManager":"%s","moduleDir":"%s","disabled":%s,"removePending":%s,"pendingUpdate":%s,"romCheck":true}}\n' \
        "$_ah_installed" "$(_ah_json "$_ah_version")" "$_ah_code" "$(_ah_json "$_ah_active")" "$(_ah_json "$_ah_root")" "$(_ah_json "$MODDIR")" "$_ah_disabled" "$_ah_remove" "$_ah_pending"
fi
