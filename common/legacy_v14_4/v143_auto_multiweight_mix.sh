#!/system/bin/sh
# Compatibility entry for earlier packages: combinations use only selected slot weights.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
exec sh "$MODDIR/common/v142_weighted_mix.sh" "$@"
