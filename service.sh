#!/system/bin/sh
# LuoShu service router.
# Normal installations keep the current v4 service unchanged. Once the isolated
# physical compatibility runtime is selected, background v4 payload rebuilds stay off.
# App font inventory remains prewarmed through config/native_font_index.json.
set +e
MODDIR="${0%/*}"
UNIVERSAL_MODE="$MODDIR/config/universal-font-runtime.conf"
UNIVERSAL_RUNTIME="$MODDIR/common/universal_mount_runtime.sh"
UNIVERSAL_VERIFY="$MODDIR/common/universal_font_runtime_verify.sh"
MOUNT_BACKEND_RUNTIME="$MODDIR/common/mount_backend_runtime.sh"
LEGACY_MODE="$MODDIR/config/font_runtime_legacy_v14_4.conf"
V4_SERVICE="$MODDIR/.luoshu-runtime/core/service.sh"

# A Meta backend is verified after the manager's mount stage. The runtime is a
# no-op for a self backend already committed at its Root-specific hook.
mkdir -p "$MODDIR/logs" 2>/dev/null || true
if [ -f "$MOUNT_BACKEND_RUNTIME" ]; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MOUNT_BACKEND_RUNTIME" hook service \
        >> "$MODDIR/logs/mount-backend.log" 2>&1 || true
fi

# Retire the old global weight override after boot. Restore only a value still
# owned by this module; preserve subsequent user/system changes.
if [ -f "$MODDIR/common/font_weight_runtime.sh" ]; then
    (
        mkdir -p "$MODDIR/logs" 2>/dev/null || true
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
            sh "$MODDIR/common/font_weight_runtime.sh" service \
            >> "$MODDIR/logs/font-weight-service.log" 2>&1 || true
    ) </dev/null >/dev/null 2>&1 &
fi

if [ -s "$UNIVERSAL_MODE" ]; then
    # Phase 7/8 runtime may only consume the frozen deployment artifacts.
    # The legacy provider watcher re-discovers targets and chooses weights, so it
    # must not run once the universal deployment pipeline is active.
    [ -f "$UNIVERSAL_RUNTIME" ] && MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
        sh "$UNIVERSAL_RUNTIME" service >/dev/null 2>&1 || true
    # Phase 8 runs once per boot after boot-complete. It consumes only the frozen
    # Phase 4/6/7 artifacts and never starts a resident target-discovery loop.
    [ -f "$UNIVERSAL_VERIFY" ] && MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
        sh "$UNIVERSAL_VERIFY" schedule >/dev/null 2>&1 || true
    exit 0
fi

# Legacy/current production paths keep the Google provider compatibility service.
if [ -f "$MODDIR/common/google_font_provider_service.sh" ]; then
    (
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
            sh "$MODDIR/common/google_font_provider_service.sh" boot
    ) </dev/null >/dev/null 2>&1 &
fi

if [ ! -f "$LEGACY_MODE" ]; then
    [ -f "$V4_SERVICE" ] && exec sh "$V4_SERVICE"
    exit 0
fi

(
    LOG="$MODDIR/logs/service-legacy-v14.4.log"
    VERIFY="$MODDIR/config/device-font-load-verification.conf"
    MOUNT_STATE_FILE="$MODDIR/config/self-mount.conf"
    mkdir -p "$MODDIR/logs" "$MODDIR/config" 2>/dev/null || true
    printf '[%s] physical compatibility boot service start\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" >> "$LOG" 2>/dev/null

    _wait=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$_wait" -lt 150 ]; do
        sleep 2
        _wait=$((_wait + 1))
    done
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] || exit 0

    _active=$(sed -n '1p' "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_active" ] || _active=$(sed -n 's/^font=//p' "$LEGACY_MODE" 2>/dev/null | head -n1 | tr -d '\r\n')
    [ -n "$_active" ] || _active=default
    _mount_state=$(sed -n 's/^state=//p' "$MOUNT_STATE_FILE" 2>/dev/null | head -n1 | tr -d '\r\n')
    _mount_failed=$(sed -n 's/^failed=//p' "$MOUNT_STATE_FILE" 2>/dev/null | head -n1 | tr -d '\r\n')
    _backend_state="$MODDIR/config/mount-backend.conf"
    _backend_active=$(sed -n 's/^active_backend=//p' "$_backend_state" 2>/dev/null | head -n1 | tr -d '\r\n')
    _backend_verify=$(sed -n 's/^verification=//p' "$_backend_state" 2>/dev/null | head -n1 | tr -d '\r\n')
    _boot_id="${LUOSHU_BACKEND_TEST_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"
    _now=$(date +%s 2>/dev/null || echo 0)

    _verify_state=pending
    _verify_mode=compatibility
    _verify_reason=awaiting-mount-confirmation
    if [ "$_active" = default ]; then
        _verify_state=not-applicable
        _verify_mode=system
        _verify_reason=default-font
    elif [ -f "$MODDIR/common/device_font_load_verify.sh" ]; then
        # The shared verifier gives the current-boot backend verdict priority and
        # reads the XML/CJK route through PID 1, never a stale self-mount marker.
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/device_font_load_verify.sh" verify >> "$LOG" 2>&1
        _verify_rc=$?
        _verify_state=$(sed -n 's/^state=//p' "$VERIFY" 2>/dev/null | head -n1 | tr -d '\r\n')
        _verify_mode=$(sed -n 's/^mode=//p' "$VERIFY" 2>/dev/null | head -n1 | tr -d '\r\n')
        _verify_reason=$(sed -n 's/^reason=//p' "$VERIFY" 2>/dev/null | head -n1 | tr -d '\r\n')
        if [ "$_verify_rc" -ne 0 ] && [ "$_verify_state" = verified ]; then
            _verify_state=failed
            _verify_reason=load-verifier-command-failed
        fi
        [ -n "$_verify_state" ] || _verify_state=pending
        [ -n "$_verify_reason" ] || _verify_reason=load-verifier-evidence-missing
    elif [ -f "$_backend_state" ]; then
        # Do not downgrade to the old marker if the new verification helper is
        # missing from an incomplete update. Retain the payload for diagnostics.
        _verify_state=failed
        _verify_reason=backend-load-verifier-missing
    else
        case "$_mount_state" in
            mounted|degraded|confirmed|verified)
                _verify_state=verified
                _verify_mode=mount-confirmed
                _verify_reason=physical-font-backend-active
                ;;
            failed)
                _verify_state=failed
                _verify_mode=compatibility
                _verify_reason="self-mount-failed${_mount_failed:+:$_mount_failed}"
                ;;
            *)
                _verify_state=pending
                _verify_mode=compatibility
                _verify_reason=mount-state-not-confirmed
                ;;
        esac
    fi

    {
        printf 'state=%s\n' "$_verify_state"
        printf 'mode=%s\n' "$_verify_mode"
        printf 'activeFont=%s\n' "$_active"
        printf 'reason=%s\n' "$_verify_reason"
        printf 'bootId=%s\n' "$_boot_id"
        printf 'time=%s\n' "$_now"
    } > "${VERIFY}.tmp.$$" 2>/dev/null && mv -f "${VERIFY}.tmp.$$" "$VERIFY" 2>/dev/null || true
    chmod 0644 "$VERIFY" 2>/dev/null || true

    case "$_verify_state" in
        verified|not-applicable)
            rm -f "$MODDIR/config/text_reboot_required.conf" 2>/dev/null || true
            # Keep the previous actual payload for explicit undo across reboots.
            rm -rf "$MODDIR"/.luoshu-payload-stage.* 2>/dev/null || true
            if [ -f "$MODDIR/common/action_control.sh" ]; then
                LUOSHU_ACTION_CONTROL_LIBRARY=true . "$MODDIR/common/action_control.sh"
                LUOSHU_ACTION_CONTROL_LIBRARY=false
                luoshu_undo_prune_retired "$MODDIR" >> "$LOG" 2>&1 || true
            fi
            printf '[%s] font load confirmed: active=%s mount=%s\n' \
                "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_active" "$_mount_state" >> "$LOG" 2>/dev/null
            ;;
        failed)
            printf '[%s] font load FAILED: active=%s mount=%s backend=%s verification=%s reason=%s detail=%s; retired payload retained\n' \
                "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_active" "$_mount_state" "$_backend_active" "$_backend_verify" "$_verify_reason" "$_mount_failed" >> "$LOG" 2>/dev/null
            # Produce a shareable diagnostic bundle without adb: written into
            # the module and copied to /sdcard/Ziyu/reports when possible.
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
                sh "$MODDIR/common/diagnostic_bundle.sh" dump-once-per-boot "boot-verify-failed" >> "$LOG" 2>&1 || true
            ;;
        *)
            printf '[%s] font load pending: active=%s mount=%s; retired payload retained\n' \
                "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_active" "${_mount_state:-unknown}" >> "$LOG" 2>/dev/null
            ;;
    esac

    if [ -f "$MODDIR/config/app_install_pending" ] && [ -f "$MODDIR/common/app_installer.sh" ]; then
        MODDIR="$MODDIR" sh "$MODDIR/common/app_installer.sh" service-retry >> "$LOG" 2>&1 || true
    fi
    if [ -f "$MODDIR/common/module_status.sh" ]; then
        MODDIR="$MODDIR" sh "$MODDIR/common/module_status.sh" "$_active" >> "$LOG" 2>&1 || true
    fi
    if [ -f "$MODDIR/common/font_manager.sh" ]; then
        if [ -f "$MODDIR/config/stock_inventory_scan_pending" ]; then
            _stock_scan=$(LUOSHU_FRESH_STOCK_SCAN=1 MODDIR="$MODDIR" sh "$MODDIR/common/font_manager.sh" action stock_scan 2>&1)
            printf '[%s] deferred stock inventory: %s\n' \
                "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_stock_scan" >> "$LOG" 2>/dev/null || true
        fi
        MODDIR="$MODDIR" sh "$MODDIR/common/font_manager.sh" action list --native-index >/dev/null 2>&1 || true
    fi

    printf '[%s] physical compatibility service complete: %s (%s)\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_active" "$_verify_state" >> "$LOG" 2>/dev/null
) &

exit 0
