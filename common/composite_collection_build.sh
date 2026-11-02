#!/system/bin/sh
# Build or validate TTC/OTC mix collections using only verified OEM snapshots.
set +e

REALMOD="${ZIYU_REAL_MODDIR:-${MODDIR:-${MODULE_DIR:-}}}"
if [ -z "$REALMOD" ] || [ ! -f "$REALMOD/module.prop" ]; then
    _self_dir=$(CDPATH= cd -- "${0%/*}" 2>/dev/null && pwd)
    REALMOD=$(CDPATH= cd -- "$_self_dir/.." 2>/dev/null && pwd)
fi
[ -f "$REALMOD/module.prop" ] || {
    echo '字域模块目录不可用，无法构建字体集合' >&2
    exit 2
}

_python="${ZIYU_PYTHON:-}"
if [ -n "$_python" ]; then
    MODDIR="$REALMOD" MODULE_DIR="$REALMOD" exec "$_python" \
        "$REALMOD/common/composite_collection_build.py" "$@"
    exit $?
fi

_pyroot="$REALMOD/common/python"
_python="$_pyroot/bin/ziyu-python"
[ -x "$_python" ] || {
    echo '字域字体运行时不可用，无法构建字体集合' >&2
    exit 20
}
MODDIR="$REALMOD" MODULE_DIR="$REALMOD" \
PYTHONHOME="$_pyroot" \
PYTHONPATH="$REALMOD/common:$_pyroot/lib/python3.14:$_pyroot/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$_pyroot/lib:$_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    exec "$_python" "$REALMOD/common/composite_collection_build.py" "$@"
