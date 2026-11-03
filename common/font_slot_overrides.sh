#!/system/bin/sh
set -eu
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
PYROOT="$MODDIR/common/python"
MODULE_DIR="$MODDIR"
if [ "${1:-}" = save ]; then
    . "$MODDIR/common/font_switch_lock.sh"
    ziyu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || {
        printf '{"status":"error","message":"字体任务正在运行，请完成后再保存规则"}\n'
        exit 1
    }
    trap 'ziyu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1' EXIT HUP INT TERM
fi
if [ -n "${ZIYU_PYTHON:-}" ]; then
    "$ZIYU_PYTHON" "$MODDIR/common/font_slot_overrides.py" "$MODDIR" "$@"
    exit $?
fi
export PYTHONHOME="$PYROOT"
export PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
"$PYROOT/bin/ziyu-python" "$MODDIR/common/font_slot_overrides.py" "$MODDIR" "$@"
exit $?
