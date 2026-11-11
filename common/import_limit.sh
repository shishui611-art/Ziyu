#!/system/bin/sh
# 字体导入大小上限：默认 256 MB，可在设置页调整（32–512 MB）。
# 供 font_import.sh / native_import.sh / app_bridge.sh 共用。
set +e

MODDIR="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
IMPORT_LIMIT_CONF="$MODDIR/config/tools/font-import-limit.conf"
IMPORT_LIMIT_LEGACY="$MODDIR/config/font-import-limit.conf"
IMPORT_LIMIT_DEFAULT_MB=256
IMPORT_LIMIT_MIN_MB=32
IMPORT_LIMIT_MAX_MB=512

import_limit_normalize() {
    _iln_mb="$1"
    case "$_iln_mb" in ''|*[!0-9]*) _iln_mb=$IMPORT_LIMIT_DEFAULT_MB ;; esac
    [ "$_iln_mb" -ge "$IMPORT_LIMIT_MIN_MB" ] 2>/dev/null || _iln_mb=$IMPORT_LIMIT_MIN_MB
    [ "$_iln_mb" -le "$IMPORT_LIMIT_MAX_MB" ] 2>/dev/null || _iln_mb=$IMPORT_LIMIT_MAX_MB
    printf '%s' "$_iln_mb"
}

import_limit_get_mb() {
    _ilg_mb=''
    _ilg_file="$IMPORT_LIMIT_CONF"
    [ -r "$_ilg_file" ] || _ilg_file="$IMPORT_LIMIT_LEGACY"
    if [ -r "$_ilg_file" ]; then
        while IFS= read -r _ilg_line || [ -n "$_ilg_line" ]; do
            case "$_ilg_line" in max_mb=*) _ilg_mb=${_ilg_line#max_mb=} ;; esac
        done < "$_ilg_file"
    fi
    [ -n "$_ilg_mb" ] || _ilg_mb=$IMPORT_LIMIT_DEFAULT_MB
    import_limit_normalize "$_ilg_mb"
}

import_limit_get_bytes() {
    _ilb_mb=$(import_limit_get_mb)
    printf '%s' "$((_ilb_mb * 1024 * 1024))"
}

import_limit_set_mb() {
    _ils_mb=$(import_limit_normalize "$1")
    mkdir -p "$MODDIR/config/tools" 2>/dev/null || return 1
    {
        printf 'schema=ziyu-font-import-limit-v1\n'
        printf 'max_mb=%s\n' "$_ils_mb"
        printf 'min_mb=%s\nmax_mb_limit=%s\n' "$IMPORT_LIMIT_MIN_MB" "$IMPORT_LIMIT_MAX_MB"
        printf 'bytes=%s\n' "$((_ils_mb * 1024 * 1024))"
    } > "$IMPORT_LIMIT_CONF.tmp.$$" 2>/dev/null && mv -f "$IMPORT_LIMIT_CONF.tmp.$$" "$IMPORT_LIMIT_CONF" 2>/dev/null || {
        rm -f "$IMPORT_LIMIT_CONF.tmp.$$" 2>/dev/null
        return 1
    }
    printf 'max_mb=%s\nbytes=%s\n' "$_ils_mb" "$((_ils_mb * 1024 * 1024))"
    return 0
}

import_limit_status() {
    _ilst_mb=$(import_limit_get_mb)
    printf 'schema=ziyu-font-import-limit-v1\nmax_mb=%s\nmin_mb=%s\nmax_limit_mb=%s\nbytes=%s\n' \
        "$_ilst_mb" "$IMPORT_LIMIT_MIN_MB" "$IMPORT_LIMIT_MAX_MB" "$((_ilst_mb * 1024 * 1024))"
}

# 直接执行时支持 get / set <mb> / status
if [ "${IMPORT_LIMIT_LIBRARY_ONLY:-0}" != 1 ]; then
    case "${1:-status}" in
        get) import_limit_status ;;
        set) import_limit_set_mb "${2:-}" ;;
        status) import_limit_status ;;
        *) printf '用法: import_limit.sh <get|set <mb>|status>\n'; exit 2 ;;
    esac
fi
