#!/system/bin/sh
# Export ROM defaults as real, reusable library sources without changing mounts.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/luoshu-python"
[ -x "$PYBIN" ] || { printf '{"status":"error","message":"系统字体导出运行时不可用"}\n'; exit 1; }
PYTHONHOME="$PYROOT" \
PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$PYBIN" "$MODDIR/common/system_font_library.py" --module "$MODDIR" \
    --destination "${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}/fonts"
