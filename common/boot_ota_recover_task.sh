#!/system/bin/sh
# Detached worker: recover the OEM baseline after a ROM update, prepare the font
# payload for the new build, then request KernelSU's soft reboot.
#
# Runs outside the App call so the UI request returns immediately. The task
# scope supervisor bounds the whole job; every step is logged.
set +e
MODDIR="${1:-${MODDIR:-}}"
[ -n "$MODDIR" ] || exit 2
MODULE_DIR="$MODDIR"
export MODDIR MODULE_DIR
LOG_FILE="$MODDIR/logs/soft-reboot.log"
GUARD_LOG="$MODDIR/logs/boot-ota-guard.log"
mkdir -p "$MODDIR/logs" 2>/dev/null || exit 1

_log() {
    printf '[%s] [OTA-RECOVER] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$1" \
        >> "$LOG_FILE" 2>/dev/null
}

[ -f "$MODDIR/common/boot_ota_recover.sh" ] || { _log 'helper missing'; exit 1; }
. "$MODDIR/common/boot_ota_recover.sh" || { _log 'helper unusable'; exit 1; }

_log 'rescanning OEM font baseline for the updated build'
if ! ziyu_boot_ota_refresh "$MODDIR" 240; then
    _log 'baseline rescan failed; soft reboot not requested'
    exit 1
fi
_log 'preparing the font payload for the updated build'
if ! ziyu_boot_ota_ensure_generation "$MODDIR" 420; then
    _log 'payload regeneration failed; soft reboot not requested'
    exit 1
fi

_ksud=''
for _candidate in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ksu/ksud "$(command -v ksud 2>/dev/null)"; do
    [ -n "$_candidate" ] && [ -x "$_candidate" ] || continue
    _help=$("$_candidate" soft-reboot --help 2>&1)
    [ "$?" -eq 0 ] || continue
    printf '%s\n' "$_help" | grep -q 'soft-reboot' || continue
    _ksud="$_candidate"
    break
done
[ -n "$_ksud" ] || { _log 'ksud soft-reboot unavailable; reboot manually'; exit 1; }

# Let provider hooks run first; the daemon owns cleanup-hook ordering.
_log "requesting soft reboot via $_ksud"
"$_ksud" soft-reboot >> "$LOG_FILE" 2>&1
_rc=$?
_log "soft reboot request returned rc=$_rc"
[ "$_rc" -eq 0 ] || exit 1
