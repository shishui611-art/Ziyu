#!/system/bin/sh
# Compatibility entry; implementation is an internal shell library.
_ziyu_library_file=''
for _ziyu_library_candidate in \
    "${MODPATH:-}/common/mount_self_fallback.lib" \
    "${MODULE_DIR:-}/common/mount_self_fallback.lib" \
    "${MODDIR:-}/common/mount_self_fallback.lib" \
    "${0%/*}/mount_self_fallback.lib" \
    "${0%/*}/common/mount_self_fallback.lib" \
    "${0%/*}/../common/mount_self_fallback.lib" \
    "${ROOT:-}/common/mount_self_fallback.lib" \
    "${PWD:-.}/common/mount_self_fallback.lib" \
    "/data/adb/modules/ziyu/common/mount_self_fallback.lib"; do
    [ -f "$_ziyu_library_candidate" ] && [ -r "$_ziyu_library_candidate" ] || continue
    _ziyu_library_file=$_ziyu_library_candidate
    break
done
if [ -z "$_ziyu_library_file" ]; then
    printf '%s\n' '字域运行库缺失：mount_self_fallback.lib，请刷入完整模块并重启。' >&2
    return 1 2>/dev/null || exit 1
fi
. "$_ziyu_library_file"
