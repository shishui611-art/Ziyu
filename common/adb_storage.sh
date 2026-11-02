#!/system/bin/sh
# One owned data directory; manager hook/module locations remain manager-owned.
ziyu_adb_storage() (
    _zas_module="$1"; _zas_action="${2:-boot}"
    _zas_pyroot="$_zas_module/common/python"
    [ -f "$_zas_module/common/adb_storage.py" ] && [ -x "$_zas_pyroot/bin/ziyu-python" ] || return 1
    mkdir -p "$_zas_module/logs" || return 1
    PYTHONHOME="$_zas_pyroot" \
    PYTHONPATH="$_zas_module/common:$_zas_pyroot/lib/python3.14:$_zas_pyroot/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$_zas_pyroot/lib:$_zas_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$_zas_pyroot/bin/ziyu-python" "$_zas_module/common/adb_storage.py" "$_zas_module" "$_zas_action" \
        >> "$_zas_module/logs/adb-storage.log" 2>&1
)
