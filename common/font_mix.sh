#!/system/bin/sh
# Public compatibility entry for preparation-only combinations.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
export MODDIR
CONTROLLER="$MODDIR/common/font_mix_controller.sh"
[ -f "$CONTROLLER" ] || { printf '{"status":"error","message":"缺少字体组合核心"}\n'; exit 1; }
exec sh "$CONTROLLER" "$@"
