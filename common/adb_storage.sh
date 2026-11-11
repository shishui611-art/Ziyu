#!/system/bin/sh
# One owned data directory; manager hook/module locations remain manager-owned.
ziyu_adb_storage() (
    _zas_module="$1"; _zas_action="${2:-boot}"
    _zas_pyroot="$_zas_module/common/python"
    [ -f "$_zas_module/common/adb_storage.py" ] || return 1
    # 安装器解压 zip 时常不保留可执行位（真机日志：inflating 后没有 +x），
    # 这里先补上再判断，否则数据目录初始化会直接失败、整个安装被 abort。
    if [ -f "$_zas_pyroot/bin/ziyu-python" ] && [ ! -x "$_zas_pyroot/bin/ziyu-python" ]; then
        chmod 0755 "$_zas_pyroot/bin/ziyu-python" 2>/dev/null || true
    fi
    [ -x "$_zas_pyroot/bin/ziyu-python" ] || return 1
    mkdir -p "$_zas_module/logs" || return 1
    PYTHONHOME="$_zas_pyroot" \
    PYTHONPATH="$_zas_module/common:$_zas_pyroot/lib/python3.14:$_zas_pyroot/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$_zas_pyroot/lib:$_zas_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$_zas_pyroot/bin/ziyu-python" "$_zas_module/common/adb_storage.py" "$_zas_module" "$_zas_action" \
        >> "$_zas_module/logs/adb-storage.log" 2>&1
)
