#!/system/bin/sh
# Root-manager and Meta backend selection is centralized in one mount runtime.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
mkdir -p "$MODDIR/logs" || exit 1

# Resolve boot-loop/OTA holds before APatch's NoMount scan, payload activation,
# stock snapshotting or any later provider discovery.
if [ -f "$MODDIR/common/boot_ota_guard.sh" ]; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/boot_ota_guard.sh" hook "$MODDIR" \
        >> "$MODDIR/logs/boot-ota-guard.log" 2>&1
    _boot_guard_rc=$?
    case "$_boot_guard_rc" in
        0) ;;
        10|12)
            if [ -f "$MODDIR/common/mount_backend_runtime.sh" ]; then
                MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data \
                    >> "$MODDIR/logs/mount-backend.log" 2>&1 || true
            fi
            printf '[BOOT-OTA-GUARD] mount/provider work stopped for this boot rc=%s\n' "$_boot_guard_rc" \
                >> "$MODDIR/logs/boot-ota-guard.log"
            exit 0
            ;;
        *)
            printf '[BOOT-OTA-GUARD] helper failed rc=%s; failing closed before provider scan\n' "$_boot_guard_rc" \
                >> "$MODDIR/logs/boot-ota-guard.log"
            exit 1
            ;;
    esac
fi

# APatch scans providers before module post-fs-data. Its owned common hook
# runs this preparation first, including a staged modules_update installation.
if [ -f "$MODDIR/common/apatch_boot_prepare.sh" ]; then
    . "$MODDIR/common/apatch_boot_prepare.sh"
    if [ "${ZIYU_APATCH_PRE_MOUNT:-0}" = 1 ]; then
        _zap_identity && _zap_module_safe "$MODDIR" || exit 1
    elif ziyu_apatch_boot_ready "$MODDIR" "$(cat /proc/sys/kernel/random/boot_id | tr -d '\r\n')"; then
        printf '[APATCH-PRE-MOUNT] reusing preparation; provider has already scanned this generation\n' >> "$MODDIR/logs/mount-backend.log"
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data
        exit $?
    fi
fi

_soft_stage="$MODDIR/config/temporary-root-soft-reboot.conf"
_soft_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
# An update may replace the module only after the old v1.4.38 cleanup hook has
# already failed. Resume a deferred cleanup (or that exact legacy failure) with
# the new full cleanup hook after `stop`, before activating any generation.
_soft_saved_state=$(sed -n 's/^state=//p' "$_soft_stage" 2>/dev/null | head -n1)
_soft_saved_reason=$(sed -n 's/^reason=//p' "$_soft_stage" 2>/dev/null | head -n1)
if [ -n "$_soft_boot" ] &&
   [ "$(sed -n 's/^boot_id=//p' "$_soft_stage" 2>/dev/null | head -n1)" = "$_soft_boot" ] &&
   { [ "$_soft_saved_state" = nomount-cleanup-pending ] ||
     { [ "$_soft_saved_state" = failed ] &&
       { [ "$_soft_saved_reason" = nomount-still-routes-luoshu-payload ] ||
         [ "$_soft_saved_reason" = nomount-cleanup-window-not-quiescent ]; }; }; } &&
   [ -f "$MODDIR/common/mount_nomount_backend.sh" ] && [ -f "$MODDIR/emulated-soft-reboot.sh" ]; then
    printf '[TEMP-ROOT] retrying legacy NoMount cleanup with updated hook before activation\n' >> "$MODDIR/logs/mount-backend.log"
    ZIYU_KSU_POST_STOP_RELOAD=1 sh "$MODDIR/emulated-soft-reboot.sh" >> "$MODDIR/logs/mount-backend.log" 2>&1
    _soft_retry_rc=$?
    [ "$_soft_retry_rc" -eq 0 ] &&
        [ "$(sed -n 's/^state=//p' "$_soft_stage" 2>/dev/null | head -n1)" = ready ] || exit 1
fi
if [ -n "$_soft_boot" ] &&
   [ "$(sed -n 's/^boot_id=//p' "$_soft_stage" 2>/dev/null | head -n1)" = "$_soft_boot" ] &&
   [ "$(sed -n 's/^state=//p' "$_soft_stage" 2>/dev/null | head -n1)" = failed ]; then
    printf '[TEMP-ROOT] refusing payload activation after failed soft reboot cleanup\n' >> "$MODDIR/logs/mount-backend.log"
    exit 1
fi

# A module update can install this hook after an older soft-reboot cleanup hook
# ran. Recheck provider evidence before activating the pending generation.
if [ -n "$_soft_boot" ] && \
   [ "$(sed -n 's/^boot_id=//p' "$_soft_stage" 2>/dev/null | head -n1)" = "$_soft_boot" ] && \
   [ -f "$MODDIR/common/mount_provider_reload.sh" ]; then
    . "$MODDIR/common/mount_provider_reload.sh"
    if ! ziyu_provider_reload_check "$MODDIR" reload; then
        printf 'boot_id=%s\nstate=failed\nreason=%s\n' "$_soft_boot" \
            "${PROVIDER_RELOAD_REASON:-provider-reload-cleanup-unconfirmed}" > "$_soft_stage"
        printf '[TEMP-ROOT] pending payload retained: %s\n' "${PROVIDER_RELOAD_REASON:-unknown}" \
            >> "$MODDIR/logs/mount-backend.log"
        exit 1
    fi
fi

# Manual exclusions are checked before even activating the pending generation.
[ ! -f "$MODDIR/common/skip_mount_ownership.sh" ] || . "$MODDIR/common/skip_mount_ownership.sh"
if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || \
   { type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$MODDIR"; }; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data
    exit $?
fi

# Capture only proven stock views before any provider sees our font payload.
# This never mounts/unmounts and may defer without blocking the existing boot.
_stock_pyroot="$MODDIR/common/python"
if [ -x "$_stock_pyroot/bin/luoshu-python" ] && [ -f "$MODDIR/common/stock_font_snapshot.py" ]; then
    PYTHONHOME="$_stock_pyroot" \
    PYTHONPATH="$MODDIR/common:$_stock_pyroot/lib/python3.14:$_stock_pyroot/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$_stock_pyroot/lib:$_stock_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$_stock_pyroot/bin/luoshu-python" "$MODDIR/common/stock_font_snapshot.py" "$MODDIR" \
        >> "$MODDIR/logs/stock-font-snapshot.log" 2>&1
fi

# Activate a previously prepared payload before exposing any module files.
# Recover publication under the same lock before deciding which next-state wins.
# An interrupted physical publish may still have a Universal receipt in config.
if [ -f "$MODDIR/common/font_next_transaction.sh" ] &&
   [ -f "$MODDIR/common/font_switch_lock.sh" ]; then
    . "$MODDIR/common/font_next_transaction.sh"
    . "$MODDIR/common/font_switch_lock.sh"
    luoshu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || exit 1
    luoshu_next_transaction_recover "$MODDIR"
    _pending_recovery_rc=$?
    luoshu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1 || true
    if [ "$_pending_recovery_rc" -ne 0 ]; then
        mkdir -p "$MODDIR/logs"
        printf '[NEXT-TRANSACTION] recovery unconfirmed rc=%s; activation deferred\n' \
            "$_pending_recovery_rc" >> "$MODDIR/logs/fontswitch.log"
        exit 1
    fi
fi
UNIVERSAL_NEXT_STATE="$MODDIR/config/universal-font-next.conf"
MODDIR="$MODDIR" sh "$MODDIR/common/font_live_switch.sh" retire >> "$MODDIR/logs/font-live.log" 2>&1 || {
    echo '字域：旧热挂载清理未确认，保留字体负载并停止本次启动切换。' >&2
    exit 1
}
if [ -s "$UNIVERSAL_NEXT_STATE" ]; then
    NEXT_BOOT_HELPER="$MODDIR/common/universal_next_boot.sh"
    [ -f "$NEXT_BOOT_HELPER" ] && . "$NEXT_BOOT_HELPER"
    type universal_font_next_boot_activate >/dev/null 2>&1 || exit 1
    universal_font_next_boot_activate >> "$MODDIR/logs/fontswitch.log" 2>&1
    _activation_rc=$?
else
    NEXT_BOOT_HELPER="$MODDIR/common/next_boot_payload.sh"
    [ -f "$NEXT_BOOT_HELPER" ] && . "$NEXT_BOOT_HELPER"
    type luoshu_next_boot_activate >/dev/null 2>&1 || exit 1
    luoshu_next_boot_activate >> "$MODDIR/logs/fontswitch.log" 2>&1
    _activation_rc=$?
fi
case "$_activation_rc" in 0|2) ;; *)
    printf '[NEXT-BOOT] activation failed rc=%s; refusing to publish a different generation\n' "$_activation_rc" >> "$MODDIR/logs/mount-backend.log"
    exit 1 ;;
esac

RUNTIME="$MODDIR/common/mount_backend_runtime.sh"
V4_POST_FS="$MODDIR/.luoshu-runtime/core/post-fs-data.sh"
LEGACY_MODE="$MODDIR/config/font_runtime_legacy_v14_4.conf"
UNIVERSAL_MODE="$MODDIR/config/universal-font-runtime.conf"

# Keep the verified v2.2.7 initializer for the normal v4 payload, then let its
# core wrapper hand off to the same selector used by compatibility runtimes.
if [ ! -s "$UNIVERSAL_MODE" ] && [ ! -f "$LEGACY_MODE" ] && [ -f "$V4_POST_FS" ]; then
    exec sh "$V4_POST_FS"
fi

mkdir -p "$MODDIR/logs" "$MODDIR/config" 2>/dev/null || true
if [ -f "$RUNTIME" ]; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$RUNTIME" hook post-fs-data \
        >> "$MODDIR/logs/mount-backend.log" 2>&1
    _lpf_rc=$?
else
    _lpf_rc=1
    printf 'runtime-loader-missing\n' > "$MODDIR/config/mount-backend-failed" 2>/dev/null || true
fi
exit "$_lpf_rc"
