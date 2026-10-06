#!/system/bin/sh
# LuoShu shared backend-selection handoff.
set +e
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/../.." 2>/dev/null && pwd)}"
MODULE_DIR="$MODDIR"
_lpm_backend_runtime="$MODDIR/common/mount_backend_runtime.sh"
[ -f "$_lpm_backend_runtime" ] || exit 1
MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$_lpm_backend_runtime" hook post-mount
exit $?
