#!/system/bin/sh
# Run one command in an owned, bounded scope. No global pkill/killall.
_scope_mod="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
_scope_home="$_scope_mod/common/python"
_scope_python="$_scope_home/bin/luoshu-python"
if [ ! -x "$_scope_python" ] || [ ! -f "$_scope_mod/common/task_scope.py" ]; then
    echo '字域：任务进程管理组件不完整，未启动字体任务' >&2
    exit 126
fi
PYTHONHOME="$_scope_home" \
PYTHONPATH="$_scope_home/lib/python3.14:$_scope_home/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$_scope_home/lib:$_scope_home/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    exec "$_scope_python" "$_scope_mod/common/task_scope.py" "$@"
