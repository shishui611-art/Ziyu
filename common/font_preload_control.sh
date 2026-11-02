#!/system/bin/sh
# Compatibility endpoint for older Apps; the independent module is retired.
set -eu
MODDIR="${MODDIR:-/data/adb/modules/ziyu}"
case "${1:-status}" in
    status) printf 'state=removed\ncallback=unobserved\n' ;;
    disable)
        . "$MODDIR/common/font_preload_helper.sh"
        ziyu_preload_helper_remove
        printf 'state=removed\nrebootRequired=true\n'
        ;;
    enable) printf '独立的实验性预加载组件已退役，请更新并使用字域主模块。\n' >&2; exit 1 ;;
    *) exit 64 ;;
esac
