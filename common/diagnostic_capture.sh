#!/system/bin/sh
set -eu
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
. "$MODDIR/common/user_storage.sh"
ziyu_user_storage_environment "$MODDIR"
PYROOT="$MODDIR/common/python"
export PYTHONHOME="$PYROOT"
export PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$PYROOT/bin/ziyu-python" "$MODDIR/common/diagnostic_capture.py" "$MODDIR" "$@"
