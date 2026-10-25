#!/system/bin/sh
# Small pointer reader; sourcing this file never probes Android services.
ziyu_display_config_directory() {
    _zdcs_module="$1"; _zdcs_path=; _zdcs_schema=
    _zdcs_pointer="$_zdcs_module/config/display-config-storage.conf"
    if [ -f "$_zdcs_pointer" ] && [ ! -L "$_zdcs_pointer" ]; then
        _zdcs_bytes=$(wc -c < "$_zdcs_pointer" 2>/dev/null)
        case "$_zdcs_bytes" in ''|*[!0-9]*) return 1 ;; esac
        [ "${#_zdcs_bytes}" -le 5 ] && [ "$_zdcs_bytes" -le 4096 ] || return 1
        _zdcs_lines=0
        while IFS='=' read -r _zdcs_key _zdcs_value; do
            _zdcs_lines=$((_zdcs_lines + 1)); [ "$_zdcs_lines" -le 8 ] || return 1
            case "$_zdcs_key" in schema) _zdcs_schema="$_zdcs_value" ;; path) _zdcs_path="$_zdcs_value" ;; esac
        done < "$_zdcs_pointer"
    fi
    [ "$_zdcs_schema" = ziyu-display-config-storage-v1 ] || return 0
    case "$_zdcs_path" in
        *'/../'*|*'/./'*|*/..|*/.|*'|'*|*'
'*) return 1 ;;
        /storage/emulated/0/?*|/data/adb/ziyu-data/?*) ;;
        /storage/*/*) case "$_zdcs_path" in /storage/emulated/*|/storage/self/*) return 1 ;; esac ;;
        *) return 1 ;;
    esac
    printf '%s\n' "$_zdcs_path"
}

ziyu_display_config_command() (
    _zdcs_module="$1"; shift
    _zdcs_py="$_zdcs_module/common/python"
    PYTHONHOME="$_zdcs_py" PYTHONPATH="$_zdcs_module/common:$_zdcs_py/lib/python3.14:$_zdcs_py/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$_zdcs_py/lib:$_zdcs_py/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$_zdcs_py/bin/luoshu-python" "$_zdcs_module/common/display_config_storage.py" "$_zdcs_module" "$@"
)
