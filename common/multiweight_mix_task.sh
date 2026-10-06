#!/system/bin/sh
# Compatibility entry: use only the weights selected for the three slots.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
exec sh "$MODDIR/common/weighted_mix_task.sh" "$@"
