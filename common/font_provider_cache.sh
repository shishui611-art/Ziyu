#!/system/bin/sh
# Ziyu legacy downloadable-font cache cleanup.
#
# Android 15/16 FontManagerService serializes the active font map. Replacing files in
# /data/fonts/files after boot does not rebuild that map and can also invalidate signed
# provider content. v2.2 therefore never overwrites provider font files. This helper is
# retained only to restore backups left by old experimental builds.
set +e

ZIYU_PROVIDER_DIR="${ZIYU_PROVIDER_DIR:-/data/fonts/files}"
ZIYU_PROVIDER_BACKUP_SUFFIX=".ziyu-bak"

ziyu_provider_log() {
    _lpl_msg="$1"
    _lpl_mod="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}"
    mkdir -p "$_lpl_mod/logs" 2>/dev/null || true
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_lpl_msg" \
        >> "$_lpl_mod/logs/provider_cache.log" 2>/dev/null || true
}

# Restore only files that Ziyu itself backed up in the retired direct-write path.
ziyu_provider_cache_restore() {
    [ -d "$ZIYU_PROVIDER_DIR" ] || return 0
    _lpcr_count=0
    for _lpcr_bak in "$ZIYU_PROVIDER_DIR"/*"$ZIYU_PROVIDER_BACKUP_SUFFIX"; do
        [ -f "$_lpcr_bak" ] || continue
        _lpcr_orig="${_lpcr_bak%$ZIYU_PROVIDER_BACKUP_SUFFIX}"
        if mv -f "$_lpcr_bak" "$_lpcr_orig" 2>/dev/null; then
            chmod 0644 "$_lpcr_orig" 2>/dev/null || true
            _lpcr_count=$((_lpcr_count + 1))
        fi
    done
    [ "$_lpcr_count" -eq 0 ] || ziyu_provider_log "已恢复旧实验遗留的 $_lpcr_count 个 provider 字体备份"
    return 0
}

# Compatibility API used by older font_manager.sh. It intentionally performs no cache
# replacement; v2.2 handles named dynamic families before FontManagerService starts.
ziyu_provider_cache_sync() {
    ziyu_provider_cache_restore >/dev/null 2>&1 || true
    ziyu_provider_log '已停用 /data/fonts/files 覆盖；改用启动前动态 family 配置视图'
    return 0
}
