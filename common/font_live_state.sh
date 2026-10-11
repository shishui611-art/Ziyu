#!/system/bin/sh
# Read-only current-boot runtime pointer. Builders continue using canonical payload.
_ZIYU_LIVE_CR="$(printf '\r')"
ziyu_live_value() {
    [ -r "$1" ] || return 0
    while IFS= read -r _zlv_line || [ -n "$_zlv_line" ]; do
        case "$_zlv_line" in "$2"=*) ;; *) continue ;; esac
        _zlv_value=${_zlv_line#*=}
        while :; do
            case "$_zlv_value" in
                *"$_ZIYU_LIVE_CR"*) _zlv_value=${_zlv_value%%"$_ZIYU_LIVE_CR"*}${_zlv_value#*"$_ZIYU_LIVE_CR"} ;;
                *) break ;;
            esac
        done
        printf '%s' "$_zlv_value"
        return 0
    done < "$1"
}
ziyu_live_current() {
    _zlc_module="${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}"
    _zlc_file="$_zlc_module/config/font-live.conf"
    _zlc_schema=''; _zlc_state=''; _zlc_saved_boot=''; _zlc_source=''; _zlc_root=''; _zlc_font=''
    _zlc_seen=''
    [ -r "$_zlc_file" ] || return 1
    while IFS= read -r _zlc_line || [ -n "$_zlc_line" ]; do
        case "$_zlc_line" in *=*) ;; *) continue ;; esac
        _zlc_key=${_zlc_line%%=*}
        case "$_zlc_key" in schema|state|boot_id|source|work_root|font) ;; *) continue ;; esac
        case "|$_zlc_seen" in *"|$_zlc_key|"*) continue ;; esac
        _zlc_seen="$_zlc_seen$_zlc_key|"
        _zlc_value=${_zlc_line#*=}
        while :; do
            case "$_zlc_value" in
                *"$_ZIYU_LIVE_CR"*) _zlc_value=${_zlc_value%%"$_ZIYU_LIVE_CR"*}${_zlc_value#*"$_ZIYU_LIVE_CR"} ;;
                *) break ;;
            esac
        done
        case "$_zlc_key" in
            schema) _zlc_schema=$_zlc_value ;; state) _zlc_state=$_zlc_value ;;
            boot_id) _zlc_saved_boot=$_zlc_value ;; source) _zlc_source=$_zlc_value ;;
            work_root) _zlc_root=$_zlc_value ;; font) _zlc_font=$_zlc_value ;;
        esac
    done < "$_zlc_file"
    [ "$_zlc_schema" = ziyu-live-font-v1 ] && [ "$_zlc_state" = mounted ] || return 1
    _zlc_boot=''
    IFS= read -r _zlc_boot < /proc/sys/kernel/random/boot_id || [ -n "$_zlc_boot" ] || return 1
    [ -n "$_zlc_boot" ] && [ "$_zlc_saved_boot" = "$_zlc_boot" ] || return 1
    case "$_zlc_source" in "$_zlc_module/.luoshu-payload"|"$_zlc_module/.luoshu-state/cache/live/$_zlc_boot/"generation-*) ;; *) return 1 ;; esac
    case "$_zlc_source/$_zlc_root" in *'/../'*|*'/./'*) return 1 ;; esac
    case "$_zlc_root" in /data/adb/luoshu/live-mount/"$_zlc_boot".*) ;; *) return 1 ;; esac
    [ -d "$_zlc_source" ] && [ ! -L "$_zlc_source" ] && [ -d "$_zlc_root" ] && [ ! -L "$_zlc_root" ] || return 1
    _zlc_work_boot=''
    [ -r "$_zlc_root/boot-id" ] || return 1
    while IFS= read -r _zlc_work_line || [ -n "$_zlc_work_line" ]; do
        while :; do
            case "$_zlc_work_line" in
                *"$_ZIYU_LIVE_CR"*) _zlc_work_line=${_zlc_work_line%%"$_ZIYU_LIVE_CR"*}${_zlc_work_line#*"$_ZIYU_LIVE_CR"} ;;
                *) break ;;
            esac
        done
        _zlc_work_boot="$_zlc_work_boot$_zlc_work_line"
    done < "$_zlc_root/boot-id"
    [ "$_zlc_work_boot" = "$_zlc_boot" ] || return 1
    [ -s "$_zlc_root/mounts.list" ] || return 1
    return 0
}
ziyu_live_source() { ziyu_live_current && printf '%s' "$_zlc_source"; }
ziyu_live_work_root() { ziyu_live_current && printf '%s' "$_zlc_root"; }
ziyu_live_font() { ziyu_live_current && printf '%s' "$_zlc_font"; }
