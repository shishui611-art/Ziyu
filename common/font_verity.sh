#!/system/bin/sh
_fv_mod="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
_fv_root="$_fv_mod/common/python"
PYTHONHOME="$_fv_root" PYTHONPATH="$_fv_root/lib/python3.14:$_fv_root/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$_fv_root/lib:$_fv_root/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    exec "$_fv_root/bin/luoshu-python" "$_fv_mod/common/font_verity.py" "$@"
