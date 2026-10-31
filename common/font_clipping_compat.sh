#!/system/bin/sh
# Snapshot a per-family preference; changes affect the next explicit apply.
set -eu
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
if [ "${1:-}" = set ]; then
    . "$MODDIR/common/font_switch_lock.sh"
    luoshu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || {
        printf '{"status":"error","message":"字体正在处理，请结束后再修改防裁切设置"}\n'
        exit 1
    }
    trap 'luoshu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1 || true' EXIT
fi
_clip_pyroot="$MODDIR/common/python"
if [ -n "${LUOSHU_PYTHON:-}" ]; then
    "$LUOSHU_PYTHON" "$MODDIR/common/font_clipping_compat.py" "$@" --module "$MODDIR"
else
    PYTHONHOME="$_clip_pyroot" \
    PYTHONPATH="$MODDIR/common:$_clip_pyroot/lib/python3.14:$_clip_pyroot/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$_clip_pyroot/lib:$_clip_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$_clip_pyroot/bin/luoshu-python" "$MODDIR/common/font_clipping_compat.py" "$@" --module "$MODDIR"
fi
