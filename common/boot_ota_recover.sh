#!/system/bin/sh
# Recover the trusted OEM font baseline after a ROM update.
#
# An OTA changes the build fingerprint. The boot guard then blocks every mount
# until a trusted stock scan for the new build exists, otherwise a scan taken
# while the old payload is mounted could record the user font as "stock". This
# helper performs that scan on demand and reports whether the prepared payload
# is already approved for the current build, so the switch and soft-reboot paths
# can self-heal instead of failing until the user opens the boot-safety page.
set +e

_bor_q() { printf '%s' "$1" | tr '\n\r' '  ' | cut -c1-600; }

ziyu_boot_ota_status() {
    _bor_mod="$1"
    [ -f "$_bor_mod/common/boot_ota_guard.sh" ] || return 1
    MODDIR="$_bor_mod" MODULE_DIR="$_bor_mod" \
        sh "$_bor_mod/common/boot_ota_guard.sh" status "$_bor_mod" 2>/dev/null
}

# Prints one of: none (no hold), complete (scan already trusted), pending.
ziyu_boot_ota_state() {
    _bor_status=$(ziyu_boot_ota_status "$1") || { printf 'none\n'; return 0; }
    case "$_bor_status" in
        *reason=ota-fingerprint-change*) ;;
        *) printf 'none\n'; return 0 ;;
    esac
    case "$_bor_status" in
        *scan_state=complete*|*scan_complete=1*) printf 'complete\n' ;;
        *) printf 'pending\n' ;;
    esac
}

# Runs the trusted stock scan and waits for it. Returns 0 when the scan for the
# current build is complete, 1 on failure or timeout. Logs to the guard log.
ziyu_boot_ota_refresh() {
    _bor_mod="$1"
    _bor_timeout="${2:-240}"
    case "$_bor_timeout" in ''|*[!0-9]*) _bor_timeout=240 ;; esac
    [ "$_bor_timeout" -ge 30 ] 2>/dev/null || _bor_timeout=30
    [ "$_bor_timeout" -le 600 ] 2>/dev/null || _bor_timeout=600
    case "$(ziyu_boot_ota_state "$_bor_mod")" in
        complete) return 0 ;;
        none) return 0 ;;
    esac
    _bor_log="$_bor_mod/logs/boot-ota-guard.log"
    mkdir -p "$_bor_mod/logs" 2>/dev/null || true
    printf '[%s] [BOOT-OTA-RECOVER] stock scan requested by recovery path\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" >> "$_bor_log" 2>/dev/null
    MODDIR="$_bor_mod" MODULE_DIR="$_bor_mod" \
        sh "$_bor_mod/common/boot_ota_guard.sh" rescan "$_bor_mod" >> "$_bor_log" 2>&1
    _bor_waited=0
    while [ "$_bor_waited" -lt "$_bor_timeout" ]; do
        _bor_status=$(ziyu_boot_ota_status "$_bor_mod")
        case "$_bor_status" in
            *scan_state=error*)
                printf '[%s] [BOOT-OTA-RECOVER] stock scan failed: %s\n' \
                    "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" \
                    "$(_bor_q "$_bor_status")" >> "$_bor_log" 2>/dev/null
                return 1
                ;;
        esac
        case "$(ziyu_boot_ota_state "$_bor_mod")" in
            complete)
                printf '[%s] [BOOT-OTA-RECOVER] stock scan complete after %ss\n' \
                    "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_bor_waited" \
                    >> "$_bor_log" 2>/dev/null
                return 0
                ;;
        esac
        sleep 2
        _bor_waited=$((_bor_waited + 2))
    done
    printf '[%s] [BOOT-OTA-RECOVER] stock scan timed out after %ss\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_bor_timeout" \
        >> "$_bor_log" 2>/dev/null
    return 1
}

# After an OTA the payload prepared before the update is not approved for the
# new build. Re-runs the normal switch task for the active selection so a new
# generation is prepared and recorded as apply-ready for this fingerprint.
# Returns 0 when the guard reports the next generation is ready, 1 otherwise.
ziyu_boot_ota_ensure_generation() {
    _bor_mod="$1"
    _bor_timeout="${2:-420}"
    case "$_bor_timeout" in ''|*[!0-9]*) _bor_timeout=420 ;; esac
    _bor_status=$(ziyu_boot_ota_status "$_bor_mod") || return 0
    case "$_bor_status" in
        *reason=ota-fingerprint-change*) ;;
        *) return 0 ;;
    esac
    case "$_bor_status" in
        *next_generation_ready=1*) return 0 ;;
    esac
    _bor_font=$(head -n1 "$_bor_mod/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    case "$_bor_font" in
        ''|default) return 0 ;;
    esac
    printf '[%s] [BOOT-OTA-RECOVER] preparing "%s" for the updated build\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_bor_font" \
        >> "$_bor_mod/logs/boot-ota-guard.log" 2>/dev/null
    MODDIR="$_bor_mod" MODULE_DIR="$_bor_mod" \
        sh "$_bor_mod/common/font_switch_task.sh" start "$_bor_font" \
        >> "$_bor_mod/logs/boot-ota-guard.log" 2>&1
    _bor_waited=0
    while [ "$_bor_waited" -lt "$_bor_timeout" ]; do
        _bor_state=$(sed -n 's/^state=//p' "$_bor_mod/config/switch_task.conf" 2>/dev/null | head -n1)
        case "$_bor_state" in
            success) _bor_status=$(ziyu_boot_ota_status "$_bor_mod") ;;
            failed|error)
                printf '[%s] [BOOT-OTA-RECOVER] regeneration failed state=%s\n' \
                    "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_bor_state" \
                    >> "$_bor_mod/logs/boot-ota-guard.log" 2>/dev/null
                return 1 ;;
        esac
        case "$_bor_status" in
            *next_generation_ready=1*)
                printf '[%s] [BOOT-OTA-RECOVER] payload approved for the updated build\n' \
                    "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" \
                    >> "$_bor_mod/logs/boot-ota-guard.log" 2>/dev/null
                return 0
                ;;
        esac
        sleep 3
        _bor_waited=$((_bor_waited + 3))
    done
    printf '[%s] [BOOT-OTA-RECOVER] regeneration timed out after %ss\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_bor_timeout" \
        >> "$_bor_mod/logs/boot-ota-guard.log" 2>/dev/null
    return 1
}
