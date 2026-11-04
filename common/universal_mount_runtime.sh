#!/system/bin/sh
# Compatibility entry; implementation is an internal shell library.
_ziyu_library_file=''
for _ziyu_library_candidate in \
    "${MODPATH:-}/common/universal_mount_runtime.lib" \
    "${MODULE_DIR:-}/common/universal_mount_runtime.lib" \
    "${MODDIR:-}/common/universal_mount_runtime.lib" \
    "${0%/*}/universal_mount_runtime.lib" \
    "${0%/*}/common/universal_mount_runtime.lib" \
    "${0%/*}/../common/universal_mount_runtime.lib" \
    "${ROOT:-}/common/universal_mount_runtime.lib" \
    "${PWD:-.}/common/universal_mount_runtime.lib" \
    "/data/adb/modules/ziyu/common/universal_mount_runtime.lib"; do
    [ -f "$_ziyu_library_candidate" ] && [ -r "$_ziyu_library_candidate" ] || continue
    _ziyu_library_file=$_ziyu_library_candidate
    break
done
if [ -z "$_ziyu_library_file" ]; then
    printf '%s\n' '字域运行库缺失：universal_mount_runtime.lib，请刷入完整模块并重启。' >&2
    return 1 2>/dev/null || exit 1
fi
. "$_ziyu_library_file"
