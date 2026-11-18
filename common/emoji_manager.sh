#!/system/bin/sh
# First-party Emoji bridge. Caller mutations share the text font lock.
set +e
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
export MODDIR MODULE_DIR="$MODDIR" MODPATH="$MODDIR"
[ "$(id -u 2>/dev/null)" = 0 ] || exit 1
case "${1:-}" in
    apply)
        [ "$#" -eq 2 ] || exit 2
        exec sh "$MODDIR/common/legacy_v14_4/font_switch_safe.sh" action emoji "$2"
        ;;
    status|import|preview) ;;
    *) exit 2 ;;
esac
. "$MODDIR/common/font_switch_lock.sh" || exit 1
ziyu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || {
    printf '{"status":"error","message":"字体正在处理中，请稍后重试"}\n'
    exit 1
}
trap 'ziyu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1' EXIT
. "$MODDIR/common/font_next_transaction.sh" || exit 1
ziyu_next_transaction_recover "$MODDIR" || {
    printf '{"status":"error","message":"上一字体事务尚未恢复，请导出日志"}\n'
    exit 1
}
PYROOT="$MODDIR/common/python"
export PYTHONHOME="$PYROOT"
export PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
"$PYROOT/bin/ziyu-python" "$MODDIR/common/emoji_manager.py" "$MODDIR" "$@"
exit $?
