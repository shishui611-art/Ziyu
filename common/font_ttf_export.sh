#!/system/bin/sh
# GPL-3.0-only: prepare an immutable user-library snapshot, then export one face.
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)}"
command="$1"; shift
if [ "$command" = prepare ]; then
    family="$1"; root="$2"
    result=$(sh "$MODDIR/common/font_archive_export.sh" "$family" "$root")
    case "$result" in *'"status":"ok"'*) ;; *) printf '%s\n' "$result"; exit 1 ;; esac
    set -- list "$root"
elif [ "$command" = export ]; then
    set -- export "$1" "$2"
else
    echo '{"status":"error","message":"未知的 TTF 导出操作"}'
    exit 1
fi
pyroot="$MODDIR/common/python"
PYTHONHOME="$pyroot" \
PYTHONPATH="$MODDIR/common:$pyroot/lib/python3.14:$pyroot/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$pyroot/lib:$pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    exec "$pyroot/bin/luoshu-python" "$MODDIR/common/task_scope.py" \
        --task font-ttf-export --timeout 300 -- \
        "$pyroot/bin/luoshu-python" "$MODDIR/common/font_ttf_export.py" "$@"
