#!/system/bin/sh
# Compatibility entry; implementation is an internal shell library.
_ziyu_library_file=''
for _ziyu_library_candidate in \
    "${MODPATH:-}/common/private_payload.lib" \
    "${MODULE_DIR:-}/common/private_payload.lib" \
    "${MODDIR:-}/common/private_payload.lib" \
    "${0%/*}/private_payload.lib" \
    "${0%/*}/common/private_payload.lib" \
    "${0%/*}/../common/private_payload.lib" \
    "${ROOT:-}/common/private_payload.lib" \
    "${PWD:-.}/common/private_payload.lib" \
    "/data/adb/modules/ziyu/common/private_payload.lib"; do
    [ -f "$_ziyu_library_candidate" ] && [ -r "$_ziyu_library_candidate" ] || continue
    _ziyu_library_file=$_ziyu_library_candidate
    break
done
if [ -z "$_ziyu_library_file" ]; then
    printf '%s\n' '字域运行库缺失：private_payload.lib，请刷入完整模块并重启。' >&2
    return 1 2>/dev/null || exit 1
fi
. "$_ziyu_library_file"
