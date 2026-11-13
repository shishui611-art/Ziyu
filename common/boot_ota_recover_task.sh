#!/system/bin/sh
# Detached recovery explicitly requested by the soft-reboot button.
set +e
MODDIR="${1:-/data/adb/modules/ziyu}"
MODULE_DIR="$MODDIR"
[ "$MODDIR" = /data/adb/modules/ziyu ] && [ "$(id -u)" = 0 ] || exit 1
. "$MODDIR/common/boot_ota_recover.sh" || exit 1
ziyu_boot_ota_refresh "$MODDIR" 240 &&
    ziyu_boot_ota_ensure_generation "$MODDIR" 420 || {
        printf '[SOFT-REBOOT] requested OTA recovery failed; reboot was not requested\n'
        exit 1
    }
[ "$(ziyu_boot_ota_state "$MODDIR")" != pending ] || exit 1
# Re-enter the normal provider/readiness checks; never jump directly to ksud.
exec sh "$MODDIR/common/soft_reboot.sh" request
