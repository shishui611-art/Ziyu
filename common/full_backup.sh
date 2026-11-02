#!/system/bin/sh
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)}"
if [ "${ZIYU_STORAGE_LOCKED:-0}" != 1 ]; then
    exec sh "$MODDIR/common/user_storage_dispatch.sh" "$MODDIR" --backup "$@"
fi
. "$MODDIR/common/user_storage.sh" || exit 1
ziyu_user_storage_environment "$MODDIR" || exit 1
_zfb_py="$MODDIR/common/python"
PYTHONHOME="$_zfb_py" PYTHONPATH="$MODDIR/common:$_zfb_py/lib/python3.14:$_zfb_py/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$_zfb_py/lib:$_zfb_py/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    exec "$_zfb_py/bin/ziyu-python" "$MODDIR/common/full_backup.py" "$MODDIR" "$ZIYU_PUBLIC_DIR" "$@"
