#!/system/bin/sh
# Read the committed pointer only; saving a new path never changes active I/O.
ziyu_user_storage_environment() {
    [ -z "${LUOSHU_PUBLIC_DIR:-}" ] || return 0
    _zus_module="$1"
    _zus_path=''
    if [ -f "$_zus_module/config/user-storage.conf" ]; then
        while IFS= read -r _zus_line || [ -n "$_zus_line" ]; do
            case "$_zus_line" in path=*) _zus_path=${_zus_line#path=} ;; esac
        done < "$_zus_module/config/user-storage.conf"
    fi
    if [ -z "$_zus_path" ]; then
        if [ -d /sdcard/LuoShu/fonts ]; then _zus_path=/sdcard/LuoShu
        else _zus_path=/sdcard/ziyu; fi
    fi
    case "$_zus_path" in /*) ;; *) return 1 ;; esac
    LUOSHU_PUBLIC_DIR="$_zus_path"
    export LUOSHU_PUBLIC_DIR
}

ziyu_user_storage_command() (
    _zus_module="$1"; shift
    _zus_py="$_zus_module/common/python"
    PYTHONHOME="$_zus_py" PYTHONPATH="$_zus_module/common:$_zus_py/lib/python3.14:$_zus_py/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$_zus_py/lib:$_zus_py/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$_zus_py/bin/luoshu-python" "$_zus_module/common/user_storage.py" "$_zus_module" "$@"
)
