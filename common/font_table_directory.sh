#!/system/bin/sh
# Source-only bounded SFNT/TTC directory reader shared by both switch engines.
# It never searches outline bytes for table-name strings.
FONT_TABLE_CACHE_FILE=""
FONT_TABLE_CACHE_TAGS=""
FONT_TABLE_CACHE_READY=false
FONT_TABLE_CACHE_ERROR=""

font_table_directory_cache_load() {
    _ftc_file="$1"
    FONT_TABLE_CACHE_FILE="$_ftc_file"
    FONT_TABLE_CACHE_TAGS=""
    FONT_TABLE_CACHE_READY=false
    FONT_TABLE_CACHE_ERROR=""
    _ftc_module="${ZIYU_REAL_MODDIR:-${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}}"
    _ftc_root="$_ftc_module/common/python"
    _ftc_checker="$_ftc_module/common/font_structure.py"
    _ftc_host="${ZIYU_HOST_PYTHON:-${ZIYU_IMPORT_PYTHON:-}}"
    _ftc_python="${_ftc_host:-$_ftc_root/bin/ziyu-python}"
    [ -z "$_ftc_host" ] || _ftc_python=$(command -v "$_ftc_host" 2>/dev/null)
    if [ ! -f "$_ftc_checker" ] || [ ! -x "$_ftc_python" ]; then
        FONT_TABLE_CACHE_ERROR="字体表目录校验器不可用"
        return 1
    fi
    if [ -n "$_ftc_host" ]; then
        _ftc_result=$("$_ftc_python" "$_ftc_checker" --tables "$_ftc_file" 2>&1)
    else
        _ftc_result=$(PYTHONHOME="$_ftc_root" PYTHONPATH="$_ftc_root/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_ftc_root/lib:$_ftc_root/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            "$_ftc_python" "$_ftc_checker" --tables "$_ftc_file" 2>&1)
    fi
    _ftc_rc=$?
    if [ "$_ftc_rc" -ne 0 ]; then
        FONT_TABLE_CACHE_ERROR="字体表目录无效：${_ftc_result:-无法读取}"
        return 1
    fi
    FONT_TABLE_CACHE_TAGS="$_ftc_result"
    FONT_TABLE_CACHE_READY=true
}

font_has_table() {
    _fht_file="$1"
    _fht_tag="$2"
    if [ "$FONT_TABLE_CACHE_READY" != true ] || [ "$FONT_TABLE_CACHE_FILE" != "$_fht_file" ]; then
        font_table_directory_cache_load "$_fht_file" || return 1
    fi
    case "$_fht_tag" in 'SVG ') _fht_tag=SVG_ ;; esac
    case "|$FONT_TABLE_CACHE_TAGS" in
        *"|${_fht_tag}|"*) return 0 ;;
        *) return 1 ;;
    esac
}
