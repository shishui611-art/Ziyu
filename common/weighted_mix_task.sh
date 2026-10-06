#!/system/bin/sh
# Compatibility entry: every selected-axis combination is a preparation request.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
export MODDIR
ROUTER="$MODDIR/common/legacy_v14_4/mix_router.sh"
case "${1:-config}" in
    start|config|status|recover|reconcile)
        [ -f "$ROUTER" ] || { printf '{"status":"error","message":"缺少字体组合核心"}\n'; exit 1; }
        exec sh "$ROUTER" "$@"
        ;;
    *) printf '{"status":"error","message":"未知多轴组合命令"}\n'; exit 2 ;;
esac
