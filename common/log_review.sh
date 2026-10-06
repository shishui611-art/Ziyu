#!/system/bin/sh
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
export MODDIR
exec sh "$MODDIR/common/action_control.sh" logs "${1:-status}"
