#!/system/bin/sh
# LuoShu early-boot wrapper around the verified v2.2.7 initializer.
# Delegated core preserves font-payload-rebuild-pending.conf without background mutation.
# Delegated recovery contract: font_mix_controller.sh recover
# Delegated dynamic view contract: device_font_dynamic_mount_apply
set +e
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/../.." 2>/dev/null && pwd)}"
MODULE_DIR="$MODDIR"
_lpf_base="$MODDIR/.luoshu-runtime/compat/v227/post-fs-data.sh"
_lpf_temp="$MODDIR/.post-fs-data-v227.$$.sh"
[ -f "$MODDIR/common/private_payload.sh" ] && . "$MODDIR/common/private_payload.sh"
luoshu_private_mount_module_view "$MODDIR" >/dev/null 2>&1 || true

sed '$d' "$_lpf_base" > "$_lpf_temp" 2>/dev/null || exit 0
_lpf_selector_previous="${LUOSHU_BACKEND_SELECTOR_ACTIVE:-}"
LUOSHU_BACKEND_SELECTOR_ACTIVE=1
export LUOSHU_BACKEND_SELECTOR_ACTIVE
. "$_lpf_temp"
_lpf_rc=$?
rm -f "$_lpf_temp" 2>/dev/null || true
if [ -n "$_lpf_selector_previous" ]; then
    LUOSHU_BACKEND_SELECTOR_ACTIVE="$_lpf_selector_previous"
    export LUOSHU_BACKEND_SELECTOR_ACTIVE
else
    unset LUOSHU_BACKEND_SELECTOR_ACTIVE
fi
[ "$_lpf_rc" -eq 0 ] || exit "$_lpf_rc"

_lpf_backend_runtime="$MODDIR/common/mount_backend_runtime.sh"
[ -f "$_lpf_backend_runtime" ] || exit 1
MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$_lpf_backend_runtime" hook post-fs-data
exit $?
