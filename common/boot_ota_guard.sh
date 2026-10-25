#!/system/bin/sh
# Boot-loop and OTA hold for Ziyu's font mount generation.
# Only owned disable files are ever removed. Font selections, payloads and XML stay intact.
set +e

_zbg_module() {
    if [ -n "$1" ]; then printf '%s\n' "$1"
    elif [ -n "$MODDIR" ]; then printf '%s\n' "$MODDIR"
    elif [ -n "$MODULE_DIR" ]; then printf '%s\n' "$MODULE_DIR"
    else printf '%s\n' /data/adb/modules/ziyu
    fi
}

_zbg_candidates() {
    local _zbg_base _zbg_current
    _zbg_current=$(_zbg_module "$1")
    printf '%s\n' "$_zbg_current"
    case "$_zbg_current" in
        */modules_update/ziyu)
            _zbg_base=$(printf '%s\n' "$_zbg_current" | sed 's,/modules_update/ziyu$,,')
            [ -d "$_zbg_base/modules/ziyu" ] && printf '%s\n' "$_zbg_base/modules/ziyu"
            ;;
        */modules/ziyu)
            _zbg_base=$(printf '%s\n' "$_zbg_current" | sed 's,/modules/ziyu$,,')
            [ -d "$_zbg_base/modules_update/ziyu" ] && printf '%s\n' "$_zbg_base/modules_update/ziyu"
            ;;
    esac
}

_zbg_read_candidates() {
    local _zbg_base _zbg_current
    _zbg_current=$(_zbg_module "$1")
    case "$_zbg_current" in
        */modules_update/ziyu)
            _zbg_base=$(printf '%s\n' "$_zbg_current" | sed 's,/modules_update/ziyu$,,')
            [ -d "$_zbg_base/modules/ziyu" ] && printf '%s\n' "$_zbg_base/modules/ziyu"
            printf '%s\n' "$_zbg_current"
            ;;
        *) _zbg_candidates "$_zbg_current" ;;
    esac
}

_zbg_value() {
    sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'
}

_zbg_boot_id() {
    if [ -n "$ZIYU_BOOT_OTA_TEST_BOOT_ID" ]; then printf '%s\n' "$ZIYU_BOOT_OTA_TEST_BOOT_ID"
    else cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n'
    fi
}

_zbg_boot_completed() {
    if [ -n "$ZIYU_BOOT_OTA_TEST_BOOT_COMPLETED" ]; then
        [ "$ZIYU_BOOT_OTA_TEST_BOOT_COMPLETED" = 1 ]
    else
        [ "$(getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = 1 ]
    fi
}

_zbg_fingerprint() {
    if [ -n "$ZIYU_BOOT_OTA_TEST_FINGERPRINT" ]; then printf '%s\n' "$ZIYU_BOOT_OTA_TEST_FINGERPRINT" | tr -d '\r\n'
    else getprop ro.build.fingerprint 2>/dev/null | tr -d '\r\n'
    fi
}

_zbg_rom_identity() {
    local _zbg_file _zbg_values
    if [ -n "$ZIYU_BOOT_OTA_TEST_FINGERPRINT" ]; then
        printf 'system-file-v2:%s\n' "$(printf '%s\n' "$ZIYU_BOOT_OTA_TEST_FINGERPRINT" | sha256sum | awk '{print $1}')"
        return
    fi
    # Global getprop fields can be changed by Root modules after early boot.
    # Bind to the system image's build file through PID 1, without falling back
    # to mutable display properties when the file is not available.
    for _zbg_file in /proc/1/root/system/build.prop /proc/1/root/system/system/build.prop; do
        [ -r "$_zbg_file" ] || continue
        _zbg_values=$(sed -n '/^ro\.system\.build\.fingerprint=/p; /^ro\.system\.build\.version\.incremental=/p; /^ro\.build\.version\.sdk=/p' "$_zbg_file")
        [ -n "$_zbg_values" ] || continue
        printf 'system-file-v2:%s\n' "$(printf '%s\n' "$_zbg_values" | sha256sum | awk '{print $1}')"
        return 0
    done
    return 1
}

_zbg_state_path() { printf '%s/config/boot-ota-guard.conf\n' "$1"; }
_zbg_ota_path() { printf '%s/config/boot-ota-pending.conf\n' "$1"; }
_zbg_safe_path() { printf '%s/config/boot-safety-mode.conf\n' "$1"; }
_zbg_owner_path() { printf '%s/config/boot-safety-disable-owner.conf\n' "$1"; }

# Resolve the font that will actually be presented to the root manager on this
# boot. A valid next-boot record wins over active_font.conf, including a queued
# return to the stock default. This runs before next-state activation.
_zbg_effective_font() {
    local _zbg_active_candidate _zbg_candidate _zbg_font _zbg_mod _zbg_physical _zbg_selection_source _zbg_universal
    _zbg_mod="$1"
    # A root-manager update hook can run on modules_update before its directory
    # is renamed. A prepared choice in that staged generation wins; otherwise
    # use the currently active module's user selection, never the package's
    # default active_font.conf template.
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_universal="$_zbg_candidate/config/universal-font-next.conf"
        _zbg_physical="$_zbg_candidate/config/font-payload-next.conf"
        if [ -f "$_zbg_universal" ] && [ ! -L "$_zbg_universal" ] && \
           [ "$(_zbg_value "$_zbg_universal" state)" = prepared ] && \
           [ -n "$(_zbg_value "$_zbg_universal" font)" ]; then
            _zbg_font=$(_zbg_value "$_zbg_universal" font)
            if [ "$_zbg_font" = default ] || {
                [ -d "$_zbg_candidate/.luoshu-payload-next" ] && [ ! -L "$_zbg_candidate/.luoshu-payload-next" ] &&
                [ -n "$(_zbg_value "$_zbg_universal" deploymentId)" ] &&
                [ -n "$(_zbg_value "$_zbg_universal" payloadDigest)" ] &&
                [ -s "$_zbg_candidate/.luoshu-payload-next/.luoshu-runtime/deployment/deployment.json" ];
            }; then
                printf '%s\n' "$_zbg_font"
                return 0
            fi
        fi
        if [ -f "$_zbg_physical" ] && [ ! -L "$_zbg_physical" ] && \
           [ "$(_zbg_value "$_zbg_physical" state)" = prepared ] && \
           [ -n "$(_zbg_value "$_zbg_physical" font)" ]; then
            _zbg_font=$(_zbg_value "$_zbg_physical" font)
            if [ "$_zbg_font" = default ] || {
                [ -d "$_zbg_candidate/.luoshu-payload-next" ] && [ ! -L "$_zbg_candidate/.luoshu-payload-next" ] &&
                [ "$(_zbg_value "$_zbg_candidate/.luoshu-payload-next/.luoshu-next-transaction.conf" font)" = "$_zbg_font" ] &&
                [ -n "$(_zbg_value "$_zbg_candidate/.luoshu-payload-next/.luoshu-next-transaction.conf" requestId)" ];
            }; then
                printf '%s\n' "$_zbg_font"
                return 0
            fi
        fi
    done
    _zbg_selection_source="$_zbg_mod"
    case "$_zbg_mod" in
        */modules_update/ziyu)
            _zbg_active_candidate=$(printf '%s\n' "$_zbg_mod" | sed 's,/modules_update/ziyu$,/modules/ziyu,')
            if [ -d "$_zbg_active_candidate" ] && [ ! -L "$_zbg_active_candidate" ]; then
                _zbg_selection_source="$_zbg_active_candidate"
            fi
            ;;
    esac
    _zbg_font=$(head -n1 "$_zbg_selection_source/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_zbg_font" ] || _zbg_font=$(_zbg_value "$_zbg_selection_source/config/universal-font-runtime.conf" font)
    [ -n "$_zbg_font" ] || _zbg_font=$(_zbg_value "$_zbg_selection_source/config/font_runtime_legacy_v14_4.conf" font)
    [ -n "$_zbg_font" ] || _zbg_font=default
    printf '%s\n' "$_zbg_font"
}

_zbg_effective_custom_font() {
    [ "$(_zbg_effective_font "$1")" != default ]
}

_zbg_write_current_boot_block() {
    local _zbg_boot _zbg_font _zbg_mod _zbg_payload_sha _zbg_reason _zbg_tmp _zbg_verify
    _zbg_mod="$1"; _zbg_reason="$2"; _zbg_boot="$3"
    _zbg_font=$(_zbg_effective_font "$_zbg_mod")
    mkdir -p "$_zbg_mod/config" 2>/dev/null || return 1
    _zbg_tmp="$_zbg_mod/config/boot-safety-current-boot.conf.tmp.$$"
    {
        printf 'schema=ziyu-boot-safety-current-v1\nboot_id=%s\nstate=blocked\n' "$_zbg_boot"
        printf 'reason=%s\nactiveFont=%s\ntime=%s\n' "$_zbg_reason" "$_zbg_font" "$(date +%s 2>/dev/null || echo 0)"
    } > "$_zbg_tmp" 2>/dev/null || return 1
    chmod 0600 "$_zbg_tmp" 2>/dev/null || true
    mv -f "$_zbg_tmp" "$_zbg_mod/config/boot-safety-current-boot.conf" 2>/dev/null || return 1
    # Keep the existing App/load-verification contract boot-scoped too, so a
    # previous boot's verified record can never survive an early guard exit.
    _zbg_payload_sha=$(sha256sum "$_zbg_mod/config/font-payload-manifest.conf" 2>/dev/null | awk '{print $1}')
    _zbg_verify="$_zbg_mod/config/device-font-load-verification.conf"
    _zbg_tmp="${_zbg_verify}.tmp.$$"
    {
        printf 'state=unverified\nmode=boot-safety\nactiveFont=%s\n' "$_zbg_font"
        printf 'reason=%s\nbootId=%s\npayloadManifestSha256=%s\ntime=%s\n' \
            "$_zbg_reason" "$_zbg_boot" "$_zbg_payload_sha" "$(date +%s 2>/dev/null || echo 0)"
    } > "$_zbg_tmp" 2>/dev/null || return 1
    chmod 0600 "$_zbg_tmp" 2>/dev/null || true
    mv -f "$_zbg_tmp" "$_zbg_verify" 2>/dev/null || return 1
    return 0
}

_zbg_load_state() {
    local _zbg_candidate _zbg_file _zbg_try
    _zbg_file=''
    for _zbg_candidate in $(_zbg_read_candidates "$1"); do
        _zbg_try=$(_zbg_state_path "$_zbg_candidate")
        if [ -f "$_zbg_try" ] && [ ! -L "$_zbg_try" ] && \
           [ "$(_zbg_value "$_zbg_try" schema)" = ziyu-boot-ota-guard-v1 ]; then
            _zbg_file=$_zbg_try
            break
        fi
    done
    if [ -n "$_zbg_file" ]; then
        ZBG_COUNT=$(_zbg_value "$_zbg_file" unfinished_boots)
        ZBG_PENDING_BOOT=$(_zbg_value "$_zbg_file" pending_boot_id)
        ZBG_LAST_COMPLETED=$(_zbg_value "$_zbg_file" last_completed_boot_id)
        ZBG_FINGERPRINT=$(_zbg_value "$_zbg_file" fingerprint)
        ZBG_ROM_IDENTITY=$(_zbg_value "$_zbg_file" rom_identity)
        ZBG_REASON=$(_zbg_value "$_zbg_file" reason)
    else
        ZBG_COUNT=0
        ZBG_PENDING_BOOT=''
        ZBG_LAST_COMPLETED=''
        ZBG_FINGERPRINT=''
        ZBG_ROM_IDENTITY=''
        ZBG_REASON=none
    fi
    case "$ZBG_COUNT" in ''|*[!0-9]*) ZBG_COUNT=0 ;; esac
    [ "$ZBG_COUNT" -le 99 ] 2>/dev/null || ZBG_COUNT=99
}

_zbg_write_state_one() {
    local _zbg_mod _zbg_tmp
    _zbg_mod="$1"
    mkdir -p "$_zbg_mod/config" 2>/dev/null || return 1
    _zbg_tmp="$(_zbg_state_path "$_zbg_mod").tmp.$$"
    {
        printf 'schema=ziyu-boot-ota-guard-v1\n'
        printf 'unfinished_boots=%s\npending_boot_id=%s\n' "$ZBG_COUNT" "$ZBG_PENDING_BOOT"
        printf 'last_completed_boot_id=%s\nfingerprint=%s\nreason=%s\n' \
            "$ZBG_LAST_COMPLETED" "$ZBG_FINGERPRINT" "$ZBG_REASON"
        printf 'rom_identity=%s\n' "${ZBG_ROM_IDENTITY:-}"
    } > "$_zbg_tmp" 2>/dev/null || return 1
    chmod 0600 "$_zbg_tmp" 2>/dev/null || true
    mv -f "$_zbg_tmp" "$(_zbg_state_path "$_zbg_mod")" 2>/dev/null || return 1
}

_zbg_write_state_all() {
    local _zbg_candidate _zbg_rc _zbg_source
    _zbg_source="$1"; _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_source"); do
        [ "$_zbg_candidate" = "$_zbg_source" ] || [ -d "$_zbg_candidate" ] || continue
        _zbg_write_state_one "$_zbg_candidate" || _zbg_rc=1
    done
    return "$_zbg_rc"
}

_zbg_safe_mode() {
    local _zbg_candidate _zbg_marker _zbg_reason _zbg_sm_value
    _zbg_sm_value=0; _zbg_reason=none
    for _zbg_candidate in $(_zbg_candidates "$1"); do
        _zbg_marker=$(_zbg_safe_path "$_zbg_candidate")
        [ -f "$_zbg_marker" ] && [ ! -L "$_zbg_marker" ] || continue
        [ "$(_zbg_value "$_zbg_marker" schema)" = ziyu-boot-safety-v1 ] || continue
        _zbg_sm_value=1
        _zbg_reason=$(_zbg_value "$_zbg_marker" reason)
        [ -n "$_zbg_reason" ] || _zbg_reason=two-unfinished-boots
        break
    done
    printf '%s|%s\n' "$_zbg_sm_value" "$_zbg_reason"
}

_zbg_ota_pending() {
    local _zbg_candidate _zbg_marker _zbg_pending _zbg_scan
    _zbg_pending=0; _zbg_scan=0
    for _zbg_candidate in $(_zbg_candidates "$1"); do
        _zbg_marker=$(_zbg_ota_path "$_zbg_candidate")
        [ -f "$_zbg_marker" ] && [ ! -L "$_zbg_marker" ] || continue
        [ "$(_zbg_value "$_zbg_marker" schema)" = ziyu-ota-pending-v1 ] || continue
        _zbg_pending=1
        [ "$(_zbg_value "$_zbg_marker" scan_complete)" = 1 ] && _zbg_scan=1
        break
    done
    printf '%s|%s\n' "$_zbg_pending" "$_zbg_scan"
}

_zbg_write_ota_marker() {
    local _zbg_boot _zbg_candidate _zbg_mod _zbg_new _zbg_old _zbg_rc _zbg_scan _zbg_tmp
    _zbg_mod="$1"; _zbg_old="$2"; _zbg_new="$3"; _zbg_scan="$4"; _zbg_boot="$5"; _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        [ "$_zbg_candidate" = "$_zbg_mod" ] || [ -d "$_zbg_candidate" ] || continue
        mkdir -p "$_zbg_candidate/config" 2>/dev/null || { _zbg_rc=1; continue; }
        _zbg_tmp="$(_zbg_ota_path "$_zbg_candidate").tmp.$$"
        {
            printf 'schema=ziyu-ota-pending-v1\nprevious_fingerprint=%s\n' "$_zbg_old"
            printf 'fingerprint=%s\ndetected_boot_id=%s\nscan_complete=%s\n' "$_zbg_new" "$_zbg_boot" "$_zbg_scan"
            printf 'rom_identity=%s\n' "$(_zbg_rom_identity)"
            printf 'reason=system-fingerprint-changed\n'
        } > "$_zbg_tmp" 2>/dev/null || { _zbg_rc=1; continue; }
        chmod 0600 "$_zbg_tmp" 2>/dev/null || true
        mv -f "$_zbg_tmp" "$(_zbg_ota_path "$_zbg_candidate")" 2>/dev/null || _zbg_rc=1
    done
    return "$_zbg_rc"
}

_zbg_write_ota_apply_ready() {
    local _zbg_candidate _zbg_detected_boot _zbg_file _zbg_font _zbg_identity _zbg_mod _zbg_new _zbg_old _zbg_rc _zbg_scan _zbg_state _zbg_tmp
    _zbg_mod="$1"; _zbg_old="$2"; _zbg_new="$3"; _zbg_scan="$4"
    _zbg_detected_boot="$5"; _zbg_state="$6"; _zbg_font="$7"; _zbg_identity="$8"
    case "$_zbg_state" in apply-ready|default-ready) ;; *) return 1 ;; esac
    case "$_zbg_scan" in 0|1) ;; *) return 1 ;; esac
    [ -n "$_zbg_old" ] && [ -n "$_zbg_new" ] && [ -n "$_zbg_detected_boot" ] && \
        [ -n "$_zbg_font" ] && [ -n "$_zbg_identity" ] || return 1
    _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        [ -d "$_zbg_candidate" ] && [ ! -L "$_zbg_candidate" ] || continue
        _zbg_file=$(_zbg_ota_path "$_zbg_candidate")
        [ "$(_zbg_value "$_zbg_file" schema)" = ziyu-ota-pending-v1 ] || { _zbg_rc=1; continue; }
        [ "$(_zbg_value "$_zbg_file" fingerprint)" = "$_zbg_new" ] || { _zbg_rc=1; continue; }
        _zbg_tmp="$_zbg_file.tmp.$$"
        {
            printf 'schema=ziyu-ota-pending-v1\nprevious_fingerprint=%s\nfingerprint=%s\n' \
                "$_zbg_old" "$_zbg_new"
            printf 'detected_boot_id=%s\nscan_complete=%s\nreason=system-fingerprint-changed\n' \
                "$_zbg_detected_boot" "$_zbg_scan"
            printf 'state=%s\napply_font=%s\ngeneration_identity=%s\napply_boot_id=%s\n' \
                "$_zbg_state" "$_zbg_font" "$_zbg_identity" "$(_zbg_boot_id)"
            printf 'rom_identity=%s\n' "$(_zbg_rom_identity)"
        } > "$_zbg_tmp" 2>/dev/null || { _zbg_rc=1; continue; }
        chmod 0600 "$_zbg_tmp" 2>/dev/null || true
        mv -f "$_zbg_tmp" "$_zbg_file" 2>/dev/null || _zbg_rc=1
    done
    return "$_zbg_rc"
}

_zbg_set_scan_complete() {
    local _zbg_boot _zbg_candidate _zbg_current _zbg_failed_boot _zbg_failed_reason _zbg_file _zbg_mod _zbg_rc _zbg_state _zbg_tmp
    _zbg_mod="$1"
    _zbg_current=$(_zbg_fingerprint)
    [ -n "$_zbg_current" ] && [ "$(_zbg_stock_inventory_fingerprint "$_zbg_mod")" = "$_zbg_current" ] || return 1
    [ ! -e "$_zbg_mod/config/stock_inventory_scan_pending" ] || return 1
    [ -s "$_zbg_mod/config/device_font_inventory.json" ] || return 1
    _zbg_boot=$(_zbg_boot_id)
    [ -n "$_zbg_boot" ] || return 1
    _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_file=$(_zbg_ota_path "$_zbg_candidate")
        [ -f "$_zbg_file" ] && [ ! -L "$_zbg_file" ] || continue
        [ "$(_zbg_value "$_zbg_file" schema)" = ziyu-ota-pending-v1 ] || { _zbg_rc=1; continue; }
        [ "$(_zbg_value "$_zbg_file" fingerprint)" = "$_zbg_current" ] || { _zbg_rc=1; continue; }
        _zbg_state=$(_zbg_value "$_zbg_file" state)
        case "$_zbg_state" in
            default-ready|apply-ready) ;;
            *) _zbg_state=scan-complete ;;
        esac
        _zbg_tmp="$_zbg_file.tmp.$$"
        {
            printf 'schema=ziyu-ota-pending-v1\nprevious_fingerprint=%s\nfingerprint=%s\n' \
                "$(_zbg_value "$_zbg_file" previous_fingerprint)" "$_zbg_current"
            printf 'detected_boot_id=%s\nscan_complete=1\nreason=system-fingerprint-changed\n' \
                "$(_zbg_value "$_zbg_file" detected_boot_id)"
            printf 'state=%s\nscan_completed_boot_id=%s\n' "$_zbg_state" "$_zbg_boot"
            printf 'rom_identity=%s\n' "$(_zbg_rom_identity)"
            case "$_zbg_state" in
                default-ready|apply-ready)
                    printf 'apply_font=%s\ngeneration_identity=%s\napply_boot_id=%s\n' \
                        "$(_zbg_value "$_zbg_file" apply_font)" \
                        "$(_zbg_value "$_zbg_file" generation_identity)" \
                        "$(_zbg_value "$_zbg_file" apply_boot_id)"
                    ;;
            esac
            _zbg_failed_boot=$(_zbg_value "$_zbg_file" apply_failed_boot_id)
            _zbg_failed_reason=$(_zbg_value "$_zbg_file" apply_failed_reason)
            [ -z "$_zbg_failed_boot" ] || printf 'apply_failed_boot_id=%s\n' "$_zbg_failed_boot"
            [ -z "$_zbg_failed_reason" ] || printf 'apply_failed_reason=%s\n' "$_zbg_failed_reason"
        } > "$_zbg_tmp" 2>/dev/null || { _zbg_rc=1; continue; }
        chmod 0600 "$_zbg_tmp" 2>/dev/null || true
        mv -f "$_zbg_tmp" "$_zbg_file" 2>/dev/null || _zbg_rc=1
    done
    [ "$_zbg_rc" -eq 0 ] || return 1
    _zbg_log "$_zbg_mod" "trusted stock inventory scan committed fingerprint=$_zbg_current boot=$_zbg_boot"
    return 0
}

_zbg_stock_inventory_fingerprint() {
    local _zbg_candidate _zbg_inventory _zbg_inventory_fp
    for _zbg_candidate in $(_zbg_read_candidates "$1"); do
        _zbg_inventory="$_zbg_candidate/config/device_font_inventory.json"
        [ -f "$_zbg_inventory" ] && [ ! -L "$_zbg_inventory" ] || continue
        _zbg_inventory_fp=$(sed -n 's/.*"buildFingerprint"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$_zbg_inventory" 2>/dev/null | head -n1)
        [ -n "$_zbg_inventory_fp" ] || continue
        printf '%s\n' "$_zbg_inventory_fp"
        return 0
    done
    return 1
}

_zbg_rom_key() {
    local _zbg_digest
    _zbg_digest=$({
        _zbg_fingerprint
        if [ -n "$ZIYU_BOOT_OTA_TEST_FINGERPRINT" ]; then
            printf '%s\n' test-incremental test-device test-sdk
        else
            getprop ro.build.version.incremental 2>/dev/null
            getprop ro.product.device 2>/dev/null
            getprop ro.build.version.sdk 2>/dev/null
        fi
    } | sha256sum 2>/dev/null | awk '{print $1}')
    if [ -n "$_zbg_digest" ]; then printf '%s\n' "$_zbg_digest"
    elif command -v busybox >/dev/null 2>&1; then
        {
            _zbg_fingerprint
            getprop ro.build.version.incremental 2>/dev/null
            getprop ro.product.device 2>/dev/null
            getprop ro.build.version.sdk 2>/dev/null
        } | busybox sha256sum 2>/dev/null | awk '{print $1}'
    else
        {
            _zbg_fingerprint
            getprop ro.build.version.incremental 2>/dev/null
            getprop ro.product.device 2>/dev/null
            getprop ro.build.version.sdk 2>/dev/null
        } | cksum 2>/dev/null | awk '{print $1 ":" $2}'
    fi
}

_zbg_log() {
    local _zbg_log_mod
    _zbg_log_mod="$1"; shift
    mkdir -p "$_zbg_log_mod/logs" 2>/dev/null || return 0
    printf '[%s] [BOOT-OTA-GUARD] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" \
        >> "$_zbg_log_mod/logs/boot-ota-guard.log" 2>/dev/null || true
}

_zbg_proc_starttime() {
    local _zbg_pid _zbg_proc_root _zbg_stat _zbg_tail
    _zbg_pid="$1"
    _zbg_proc_root=${ZIYU_BOOT_OTA_TEST_PROC_ROOT:-/proc}
    IFS= read -r _zbg_stat 2>/dev/null < "$_zbg_proc_root/$_zbg_pid/stat" || return 1
    _zbg_tail=${_zbg_stat##*) }
    [ "$_zbg_tail" != "$_zbg_stat" ] || return 1
    set -- $_zbg_tail
    [ "$#" -ge 20 ] || return 1
    shift 19
    printf '%s\n' "$1"
}

_zbg_worker_identity() {
    local _zbg_cmdline _zbg_expected_start _zbg_pid _zbg_proc_root _zbg_script _zbg_task
    _zbg_pid="$1"; _zbg_task="$2"; _zbg_expected_start="$3"; _zbg_script="$4"
    case "$_zbg_pid" in ''|*[!0-9]*|0|1) return 1 ;; esac
    [ -n "$_zbg_expected_start" ] && [ "$(_zbg_proc_starttime "$_zbg_pid")" = "$_zbg_expected_start" ] || return 1
    _zbg_proc_root=${ZIYU_BOOT_OTA_TEST_PROC_ROOT:-/proc}
    [ -r "$_zbg_proc_root/$_zbg_pid/cmdline" ] || return 1
    _zbg_cmdline=$(tr '\000' '\n' < "$_zbg_proc_root/$_zbg_pid/cmdline" 2>/dev/null)
    printf '%s\n' "$_zbg_cmdline" | grep -Fxq "$_zbg_script" &&
        printf '%s\n' "$_zbg_cmdline" | grep -Fxq "$_zbg_task"
}

_zbg_create_disable_one() {
    local _zbg_boot _zbg_existing_kind _zbg_existing_token _zbg_kind _zbg_owner _zbg_target _zbg_token
    _zbg_target="$1"; _zbg_kind="$2"; _zbg_boot="$3"
    [ -d "$_zbg_target" ] && [ ! -L "$_zbg_target" ] || return 0
    [ ! -L "$_zbg_target/disable" ] || return 1
    _zbg_owner=$(_zbg_owner_path "$_zbg_target")
    if [ -e "$_zbg_target/disable" ]; then
        # A pre-existing disable is always treated as external unless the exact
        # owner token proves it is ours. Never overwrite a manager/user marker.
        [ -f "$_zbg_owner" ] && [ ! -L "$_zbg_owner" ] || return 0
        [ "$(_zbg_value "$_zbg_owner" schema)" = ziyu-boot-disable-owner-v1 ] || return 0
        _zbg_existing_kind=$(_zbg_value "$_zbg_owner" kind)
        _zbg_existing_token=$(_zbg_value "$_zbg_owner" token)
        case "$_zbg_existing_token" in "ziyu-boot-guard-v1:$_zbg_existing_kind:"*) ;; *) return 0 ;; esac
        [ "$(cat "$_zbg_target/disable" 2>/dev/null | tr -d '\r\n')" = "$_zbg_existing_token" ] && return 0
        return 0
    fi
    if [ -e "$_zbg_owner" ]; then
        # Root-manager update scripts may delete disable while copying our owner
        # record. Restore only that exact token, and only while its matching
        # safety/OTA marker is still active.
        [ -f "$_zbg_owner" ] && [ ! -L "$_zbg_owner" ] || return 1
        [ "$(_zbg_value "$_zbg_owner" schema)" = ziyu-boot-disable-owner-v1 ] || return 1
        _zbg_existing_kind=$(_zbg_value "$_zbg_owner" kind)
        _zbg_token=$(_zbg_value "$_zbg_owner" token)
        case "$_zbg_token" in "ziyu-boot-guard-v1:$_zbg_existing_kind:"*) ;; *) return 1 ;; esac
        case "$_zbg_existing_kind" in
            safe_mode)
                [ "$(_zbg_value "$(_zbg_safe_path "$_zbg_target")" schema)" = ziyu-boot-safety-v1 ] || return 1
                ;;
            ota_hold)
                [ "$(_zbg_value "$(_zbg_ota_path "$_zbg_target")" schema)" = ziyu-ota-pending-v1 ] || return 1
                ;;
            *) return 1 ;;
        esac
        if ! (umask 077; set -C; printf '%s\n' "$_zbg_token" > "$_zbg_target/disable") 2>/dev/null; then
            [ "$(cat "$_zbg_target/disable" 2>/dev/null | tr -d '\r\n')" = "$_zbg_token" ] || return 1
        fi
        chmod 0600 "$_zbg_target/disable" 2>/dev/null || true
        return 0
    fi
    _zbg_token="ziyu-boot-guard-v1:$_zbg_kind:$_zbg_boot:$$"
    mkdir -p "$_zbg_target/config" 2>/dev/null || return 1
    [ ! -e "$_zbg_owner" ] && [ ! -L "$_zbg_owner" ] || return 1
    (umask 077; set -C; printf 'schema=ziyu-boot-disable-owner-v1\nkind=%s\ntoken=%s\n' \
        "$_zbg_kind" "$_zbg_token" > "$_zbg_owner") 2>/dev/null || return 1
    if ! (umask 077; set -C; printf '%s\n' "$_zbg_token" > "$_zbg_target/disable") 2>/dev/null; then
        if [ "$(_zbg_value "$_zbg_owner" token)" = "$_zbg_token" ]; then
            rm -f "$_zbg_owner" 2>/dev/null || true
        fi
        return 0
    fi
    chmod 0600 "$_zbg_owner" "$_zbg_target/disable" 2>/dev/null || true
    return 0
}

_zbg_create_disable() {
    local _zbg_boot _zbg_candidate _zbg_kind _zbg_mod _zbg_rc
    _zbg_mod="$1"; _zbg_kind="$2"; _zbg_boot="$3"; _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_create_disable_one "$_zbg_candidate" "$_zbg_kind" "$_zbg_boot" || _zbg_rc=1
    done
    return "$_zbg_rc"
}

_zbg_release_disable_one() {
    local _zbg_kind _zbg_owner _zbg_target _zbg_token
    _zbg_target="$1"; _zbg_kind="$2"
    [ -d "$_zbg_target" ] || return 0
    _zbg_owner=$(_zbg_owner_path "$_zbg_target")
    [ -f "$_zbg_owner" ] && [ ! -L "$_zbg_owner" ] || return 0
    [ "$(_zbg_value "$_zbg_owner" schema)" = ziyu-boot-disable-owner-v1 ] || return 0
    [ "$(_zbg_value "$_zbg_owner" kind)" = "$_zbg_kind" ] || return 0
    _zbg_token=$(_zbg_value "$_zbg_owner" token)
    [ -n "$_zbg_token" ] || return 1
    if [ -e "$_zbg_target/disable" ]; then
        [ ! -L "$_zbg_target/disable" ] || return 1
        [ "$(cat "$_zbg_target/disable" 2>/dev/null | tr -d '\r\n')" = "$_zbg_token" ] || return 1
        rm -f "$_zbg_target/disable" 2>/dev/null || return 1
        [ ! -e "$_zbg_target/disable" ] || return 1
    fi
    rm -f "$_zbg_owner" 2>/dev/null || return 1
    [ ! -e "$_zbg_owner" ]
}

_zbg_release_disable() {
    local _zbg_candidate _zbg_kind _zbg_mod _zbg_rc
    _zbg_mod="$1"; _zbg_kind="$2"; _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_release_disable_one "$_zbg_candidate" "$_zbg_kind" || _zbg_rc=1
    done
    return "$_zbg_rc"
}

_zbg_write_safe_marker() {
    local _zbg_boot _zbg_candidate _zbg_mod _zbg_rc _zbg_tmp
    _zbg_mod="$1"; _zbg_boot="$2"; _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        [ "$_zbg_candidate" = "$_zbg_mod" ] || [ -d "$_zbg_candidate" ] || continue
        mkdir -p "$_zbg_candidate/config" 2>/dev/null || { _zbg_rc=1; continue; }
        _zbg_tmp="$(_zbg_safe_path "$_zbg_candidate").tmp.$$"
        {
            printf 'schema=ziyu-boot-safety-v1\nsafe_mode=1\nreason=two-unfinished-boots\n'
            printf 'unfinished_boots=%s\ntrigger_boot_id=%s\ntime=%s\n' \
                "$ZBG_COUNT" "$_zbg_boot" "$(date +%s 2>/dev/null || echo 0)"
        } > "$_zbg_tmp" 2>/dev/null || { _zbg_rc=1; continue; }
        chmod 0600 "$_zbg_tmp" 2>/dev/null || true
        mv -f "$_zbg_tmp" "$(_zbg_safe_path "$_zbg_candidate")" 2>/dev/null || _zbg_rc=1
    done
    _zbg_create_disable "$_zbg_mod" safe_mode "$_zbg_boot" || _zbg_rc=1
    _zbg_log "$_zbg_mod" "safe mode enabled after two confirmed unfinished boots; boot=$_zbg_boot"
    return "$_zbg_rc"
}

_zbg_invalidate_cache() {
    local _zbg_mod
    _zbg_mod="$1"
    # Rebuild generated ROM caches only. Never delete source fonts, payloads, selections or XML.
    rm -rf "$_zbg_mod/config/safe-switch-cache" \
        "$_zbg_mod/config/device-font-cache" \
        "$_zbg_mod/config/universal-font-plans" \
        "$_zbg_mod/config/device_font_topology.json" \
        "$_zbg_mod/config/device_font_roles.json" 2>/dev/null || return 1
    rm -f "$_zbg_mod/config/coloros-font-targets.cache" \
        "$_zbg_mod/config/device-font-cache-pending.conf" \
        "$_zbg_mod/config/font-payload-rebuild-pending.conf" \
        "$_zbg_mod/config/device-font-template.state" \
        "$_zbg_mod/config/device-font-template-pending.conf" 2>/dev/null || return 1
    : > "$_zbg_mod/config/stock_inventory_scan_pending" 2>/dev/null || return 1
    chmod 0600 "$_zbg_mod/config/stock_inventory_scan_pending" 2>/dev/null || true
    return 0
}

_zbg_retire_inferred_hold() {
    local _zbg_candidate _zbg_file _zbg_mod _zbg_found _zbg_previous
    _zbg_mod="$1"; _zbg_found=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_file=$(_zbg_ota_path "$_zbg_candidate")
        [ -e "$_zbg_file" ] || continue
        [ ! -L "$_zbg_file" ] && [ "$(_zbg_value "$_zbg_file" schema)" = ziyu-ota-pending-v1 ] || return 1
        case "$(_zbg_value "$_zbg_file" state)" in apply-ready|default-ready) return 0 ;; esac
        case "$(_zbg_value "$_zbg_file" rom_identity)" in system-file-v2:*) return 0 ;; esac
        case "${ZBG_ROM_IDENTITY:-}" in system-file-v2:*) return 0 ;; esac
        _zbg_previous=$(_zbg_value "$_zbg_file" previous_fingerprint)
        case "$_zbg_previous" in
            __unknown_prior_fingerprint__|__stale-template__) _zbg_found=1 ;;
            *)
                # Older guards also inferred an update from a fingerprint
                # string alone, before recording an independent build identity.
                # Migrate only an unprepared legacy hold. Keep any generation
                # already prepared/verified under an existing recovery flow.
                if [ -z "$(_zbg_value "$_zbg_file" state)" ] &&
                   [ "$(_zbg_value "$_zbg_file" reason)" = system-fingerprint-changed ]; then
                    _zbg_found=1
                else
                    return 0
                fi
                ;;
        esac
    done
    [ "$_zbg_found" = 1 ] || return 0
    # These old markers were inferred from missing metadata, never an observed
    # ROM transition. Remove only the disable token that belongs to this guard.
    _zbg_release_disable "$_zbg_mod" ota_hold || return 1
    _zbg_remove_ota_markers "$_zbg_mod" || return 1
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_file="$_zbg_candidate/config/boot-safety-current-boot.conf"
        if [ ! -L "$_zbg_file" ] && [ "$(_zbg_value "$_zbg_file" schema)" = ziyu-boot-safety-current-v1 ] &&
           [ "$(_zbg_value "$_zbg_file" reason)" = ota-fingerprint-change ]; then
            rm -f "$_zbg_file" || return 1
        fi
    done
    _zbg_log "$_zbg_mod" 'retired legacy property-only hold; no comparable system-file baseline existed'
}

_zbg_detect_ota() {
    local _zbg_baseline _zbg_boot _zbg_current _zbg_existing _zbg_mod _zbg_rom _zbg_baseline_v2
    _zbg_mod="$1"
    _zbg_current=$(_zbg_fingerprint)
    [ -n "$_zbg_current" ] && [ "$_zbg_current" != unknown ] || return 0
    _zbg_rom=$(_zbg_rom_identity)
    # Do not infer an update from unavailable build properties.
    [ -n "$_zbg_rom" ] || return 0
    _zbg_load_state "$_zbg_mod"
    _zbg_baseline="$ZBG_FINGERPRINT"
    _zbg_retire_inferred_hold "$_zbg_mod" || return 1
    case "$ZBG_ROM_IDENTITY" in system-file-v2:*) _zbg_baseline_v2=1 ;; *) _zbg_baseline_v2=0 ;; esac
    if [ "$_zbg_baseline_v2" = 0 ]; then
        # Upgrade/bootstrap: an old cache fingerprint/template is not a previous
        # observed build identity. Establish the baseline without disabling a
        # previously working custom payload or creating a fictitious OTA.
        ZBG_FINGERPRINT=$_zbg_current; ZBG_ROM_IDENTITY=$_zbg_rom
        ZBG_REASON=baseline-initialized
        _zbg_write_state_all "$_zbg_mod" || return 1
        _zbg_log "$_zbg_mod" 'initialized build baseline; no ROM transition inferred from legacy metadata'
        return 0
    fi
    if [ "$ZBG_ROM_IDENTITY" = "$_zbg_rom" ]; then
        # Root modules/ROM services may change only ro.build.fingerprint. Keep
        # ordinary source/output/route checks; do not invent an OTA hold.
        if [ "$ZBG_FINGERPRINT" != "$_zbg_current" ]; then
            ZBG_FINGERPRINT=$_zbg_current
            _zbg_write_state_all "$_zbg_mod" || return 1
        fi
        return 0
    fi
    _zbg_existing=$(_zbg_ota_path "$_zbg_mod")
    if [ -f "$_zbg_existing" ] &&
       [ "$(_zbg_value "$_zbg_existing" schema)" = ziyu-ota-pending-v1 ] &&
       [ "$(_zbg_value "$_zbg_existing" fingerprint)" = "$_zbg_current" ] &&
       [ "$(_zbg_value "$_zbg_existing" rom_identity)" = "$_zbg_rom" ]; then
        return 0
    fi
    _zbg_boot=$(_zbg_boot_id)
    [ -n "$_zbg_baseline" ] || _zbg_baseline=$_zbg_current
    _zbg_write_ota_marker "$_zbg_mod" "$_zbg_baseline" "$_zbg_current" 0 "$_zbg_boot" || return 1
    _zbg_invalidate_cache "$_zbg_mod" || return 1
    if _zbg_effective_custom_font "$_zbg_mod"; then
        _zbg_create_disable "$_zbg_mod" ota_hold "$_zbg_boot" || return 1
        _zbg_log "$_zbg_mod" "build identity changed old=$ZBG_ROM_IDENTITY new=$_zbg_rom; prior payload retained"
    else
        _zbg_log "$_zbg_mod" 'build identity changed with a default restore queued; allowing safe activation'
    fi
    return 0
}

_zbg_stock_scan_trusted() {
    local _zbg_current _zbg_mod
    _zbg_mod="$1"
    [ "$(sed -n 's/^scan_complete=//p' "$(_zbg_ota_path "$_zbg_mod")" 2>/dev/null | head -n1)" = 1 ] || return 1
    [ ! -e "$_zbg_mod/config/stock_inventory_scan_pending" ] || return 1
    [ "$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" rom_identity)" = "$(_zbg_rom_identity)" ] || return 1
    _zbg_current=$(_zbg_fingerprint)
    [ -n "$_zbg_current" ] && [ "$(_zbg_stock_inventory_fingerprint "$_zbg_mod")" = "$_zbg_current" ]
}

_zbg_remove_ota_markers() {
    local _zbg_candidate _zbg_marker _zbg_mod _zbg_rc
    _zbg_mod="$1"; _zbg_rc=0
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_marker=$(_zbg_ota_path "$_zbg_candidate")
        if [ -f "$_zbg_marker" ] && [ ! -L "$_zbg_marker" ] && \
           [ "$(_zbg_value "$_zbg_marker" schema)" = ziyu-ota-pending-v1 ]; then
            rm -f "$_zbg_marker" 2>/dev/null || _zbg_rc=1
            [ ! -e "$_zbg_marker" ] || _zbg_rc=1
        fi
    done
    return "$_zbg_rc"
}

_zbg_finalize_default_after_scan() {
    local _zbg_active _zbg_current _zbg_marker _zbg_mod
    _zbg_mod="$1"
    _zbg_marker=$(_zbg_ota_path "$_zbg_mod")
    [ "$(_zbg_value "$_zbg_marker" schema)" = ziyu-ota-pending-v1 ] && \
    [ "$(_zbg_value "$_zbg_marker" state)" = default-ready ] && \
    _zbg_stock_scan_trusted "$_zbg_mod" || return 1
    [ "$(_zbg_effective_font "$_zbg_mod")" = default ] || return 1
    _zbg_active=$(head -n1 "$_zbg_mod/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -z "$_zbg_active" ] || [ "$_zbg_active" = default ] || return 1
    _zbg_current=$(_zbg_fingerprint)
    _zbg_load_state "$_zbg_mod"
    ZBG_FINGERPRINT=$_zbg_current; ZBG_ROM_IDENTITY=$(_zbg_rom_identity); ZBG_REASON=none
    _zbg_write_state_all "$_zbg_mod" || return 1
    _zbg_release_disable "$_zbg_mod" ota_hold || return 1
    _zbg_remove_ota_markers "$_zbg_mod" || return 1
    _zbg_log "$_zbg_mod" 'trusted stock scan completed after default restore; OTA hold retired'
    return 0
}

_zbg_apply_verified() {
    local _zbg_block_file _zbg_boot _zbg_current _zbg_device_verify _zbg_expected_digest _zbg_expected_id _zbg_font _zbg_generation _zbg_marker _zbg_mod _zbg_ota _zbg_state _zbg_universal_verify _zbg_verified
    _zbg_mod="$1"; _zbg_ota=$(_zbg_ota_pending "$1")
    [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ] || return 0
    _zbg_marker=$(_zbg_ota_path "$_zbg_mod")
    _zbg_state=$(_zbg_value "$_zbg_marker" state)
    if [ "$_zbg_state" = default-ready ]; then
        _zbg_stock_scan_trusted "$_zbg_mod" && _zbg_finalize_default_after_scan "$_zbg_mod"
        return $?
    fi
    [ "$_zbg_state" = apply-ready ] || return 1
    _zbg_font=$(_zbg_value "$_zbg_marker" apply_font)
    _zbg_apply_ready_matches "$_zbg_mod" "$_zbg_font" || return 1
    _zbg_generation=$(_zbg_apply_generation_identity "$_zbg_mod" "$_zbg_font" 0) || return 1
    _zbg_boot=$(_zbg_boot_id)
    _zbg_device_verify="$_zbg_mod/config/device-font-load-verification.conf"
    _zbg_universal_verify="$_zbg_mod/config/universal-font-runtime-verification.conf"
    _zbg_verified=0
    if [ "$(_zbg_value "$_zbg_device_verify" state)" = verified ] && \
       [ "$(_zbg_value "$_zbg_device_verify" activeFont)" = "$_zbg_font" ] && \
       [ "$(_zbg_value "$_zbg_device_verify" bootId)" = "$_zbg_boot" ] && \
       [ "$(_zbg_value "$_zbg_device_verify" generationIdentity)" = "$_zbg_generation" ]; then
        _zbg_verified=1
    fi
    if [ "$(_zbg_value "$_zbg_universal_verify" grade)" = PASS ] && \
       [ "$(_zbg_value "$_zbg_universal_verify" activeFont)" = "$_zbg_font" ] && \
       [ "$(_zbg_value "$_zbg_universal_verify" bootId)" = "$_zbg_boot" ]; then
        case "$_zbg_generation" in
            universal\|*)
                _zbg_expected_id=$(printf '%s\n' "$_zbg_generation" | cut -d '|' -f2)
                _zbg_expected_digest=$(printf '%s\n' "$_zbg_generation" | cut -d '|' -f3)
                [ "$(_zbg_value "$_zbg_universal_verify" deploymentId)" = "$_zbg_expected_id" ] && \
                [ "$(_zbg_value "$_zbg_universal_verify" payloadDigest)" = "$_zbg_expected_digest" ] && _zbg_verified=1
                ;;
        esac
    fi
    [ "$_zbg_verified" = 1 ] || return 1
    _zbg_current=$(_zbg_fingerprint)
    _zbg_load_state "$_zbg_mod"
    ZBG_FINGERPRINT=$_zbg_current; ZBG_ROM_IDENTITY=$(_zbg_rom_identity); ZBG_REASON=none
    _zbg_write_state_all "$_zbg_mod" || return 1
    _zbg_release_disable "$_zbg_mod" ota_hold || return 1
    _zbg_remove_ota_markers "$_zbg_mod" || return 1
    _zbg_block_file="$_zbg_mod/config/boot-safety-current-boot.conf"
    if [ "$(_zbg_value "$_zbg_block_file" boot_id)" = "$_zbg_boot" ]; then
        rm -f "$_zbg_block_file" 2>/dev/null || return 1
        [ ! -e "$_zbg_block_file" ] || return 1
    fi
    _zbg_log "$_zbg_mod" "OTA generation passed current-boot runtime verification font=$_zbg_font"
}

_zbg_rescan_lock() {
    printf '%s/.ziyu-luoshu-stock-rescan.lock\n' "${ZIYU_BOOT_OTA_TEST_LOCK_ROOT:-/data/adb}"
}

_zbg_apply_generation_identity() {
    local _zbg_active _zbg_digest _zbg_font _zbg_id _zbg_json _zbg_live_json _zbg_live_marker _zbg_manifest_sha _zbg_mod _zbg_next _zbg_payload_marker _zbg_physical _zbg_prefer_next _zbg_py _zbg_pyroot _zbg_request _zbg_universal _zbg_universal_runtime _zbg_validate
    _zbg_mod="$1"; _zbg_font="$2"; _zbg_validate="${3:-0}"
    _zbg_prefer_next="${4:-1}"
    _zbg_universal="$_zbg_mod/config/universal-font-next.conf"
    _zbg_physical="$_zbg_mod/config/font-payload-next.conf"
    _zbg_next="$_zbg_mod/.luoshu-payload-next"
    if [ "$_zbg_prefer_next" = 1 ] && [ -f "$_zbg_universal" ] && [ ! -L "$_zbg_universal" ] && \
       [ "$(_zbg_value "$_zbg_universal" state)" = prepared ] && \
       [ "$(_zbg_value "$_zbg_universal" font)" = "$_zbg_font" ]; then
        _zbg_id=$(_zbg_value "$_zbg_universal" deploymentId)
        _zbg_digest=$(_zbg_value "$_zbg_universal" payloadDigest)
        _zbg_json="$_zbg_next/.luoshu-runtime/deployment/deployment.json"
        _zbg_pyroot="$_zbg_mod/common/python"
        _zbg_py="$_zbg_pyroot/bin/luoshu-python"
        [ -n "$_zbg_id" ] && [ -n "$_zbg_digest" ] && [ -s "$_zbg_json" ] && [ -x "$_zbg_py" ] || return 1
        if [ "$_zbg_validate" = 1 ]; then
            PYTHONHOME="$_zbg_pyroot" PYTHONPATH="$_zbg_mod/common:$_zbg_pyroot/lib/python3.14:$_zbg_pyroot/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_zbg_pyroot/lib:$_zbg_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
                "$_zbg_py" "$_zbg_mod/common/universal_font_deployment.py" --payload-root "$_zbg_next" \
                    --validate-payload-only "$_zbg_json" >/dev/null 2>&1 || return 1
        fi
        printf 'universal|%s|%s|%s\n' "$_zbg_id" "$_zbg_digest" "$(sha256sum "$_zbg_json" 2>/dev/null | awk '{print $1}')"
        return 0
    fi
    if [ "$_zbg_prefer_next" = 1 ] && [ -f "$_zbg_physical" ] && [ ! -L "$_zbg_physical" ] && \
       [ "$(_zbg_value "$_zbg_physical" state)" = prepared ] && \
       [ "$(_zbg_value "$_zbg_physical" font)" = "$_zbg_font" ] && \
       [ -d "$_zbg_next" ] && [ ! -L "$_zbg_next" ]; then
        _zbg_payload_marker="$_zbg_next/.luoshu-next-transaction.conf"
        [ "$(_zbg_value "$_zbg_payload_marker" schema)" = luoshu-next-payload-v1 ] && \
        [ "$(_zbg_value "$_zbg_payload_marker" font)" = "$_zbg_font" ] || return 1
        _zbg_request=$(_zbg_value "$_zbg_payload_marker" requestId)
        [ -n "$_zbg_request" ] && [ -n "$(_zbg_value "$_zbg_physical" requestId)" ] && \
            [ "$(_zbg_value "$_zbg_physical" requestId)" = "$_zbg_request" ] || return 1
        [ "$(find "$_zbg_next" -type f 2>/dev/null | head -n1)" ] || return 1
        printf 'physical-next|%s|%s\n' "$_zbg_request" \
            "$(sha256sum "$_zbg_payload_marker" 2>/dev/null | awk '{print $1}')"
        return 0
    fi
    _zbg_universal_runtime="$_zbg_mod/config/universal-font-runtime.conf"
    _zbg_live_json="$_zbg_mod/.luoshu-payload/.luoshu-runtime/deployment/deployment.json"
    if [ -f "$_zbg_universal_runtime" ] && [ ! -L "$_zbg_universal_runtime" ] && \
       [ "$(_zbg_value "$_zbg_universal_runtime" font)" = "$_zbg_font" ] && [ -s "$_zbg_live_json" ]; then
        _zbg_id=$(_zbg_value "$_zbg_universal_runtime" deploymentId)
        _zbg_digest=$(_zbg_value "$_zbg_universal_runtime" payloadDigest)
        [ -n "$_zbg_id" ] && [ -n "$_zbg_digest" ] || return 1
        printf 'universal|%s|%s|%s\n' "$_zbg_id" "$_zbg_digest" "$(sha256sum "$_zbg_live_json" 2>/dev/null | awk '{print $1}')"
        return 0
    fi
    _zbg_live_marker="$_zbg_mod/.luoshu-payload/.luoshu-next-transaction.conf"
    if [ "$(_zbg_value "$_zbg_live_marker" schema)" = luoshu-next-payload-v1 ] && \
       [ "$(_zbg_value "$_zbg_live_marker" font)" = "$_zbg_font" ]; then
        _zbg_request=$(_zbg_value "$_zbg_live_marker" requestId)
        [ -n "$_zbg_request" ] || return 1
        printf 'physical-next|%s|%s\n' "$_zbg_request" \
            "$(sha256sum "$_zbg_live_marker" 2>/dev/null | awk '{print $1}')"
        return 0
    fi
    if [ "$_zbg_font" = default ] && [ "$(head -n1 "$_zbg_mod/config/active_font.conf" 2>/dev/null | tr -d '\r\n')" = default ]; then
        printf 'default-active|default|%s\n' "$(sha256sum "$_zbg_mod/config/active_font.conf" 2>/dev/null | awk '{print $1}')"
        return 0
    fi
    # The v4 engine publishes the already validated tree in place. Its atomic
    # payload transaction commits active_font and the checksum manifest together.
    _zbg_active=$(head -n1 "$_zbg_mod/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ "$_zbg_active" = "$_zbg_font" ] && [ -s "$_zbg_mod/config/font-payload-manifest.conf" ] && \
        [ ! -L "$_zbg_mod/config/font-payload-manifest.conf" ] || return 1
    _zbg_manifest_sha=$(sha256sum "$_zbg_mod/config/font-payload-manifest.conf" 2>/dev/null | awk '{print $1}')
    case "$_zbg_manifest_sha" in *[!0-9a-fA-F]*|'') return 1 ;; esac
    [ "${#_zbg_manifest_sha}" = 64 ] || return 1
    printf 'physical-active|%s|%s\n' "$_zbg_font" "$_zbg_manifest_sha"
}

_zbg_apply_ready_matches() {
    local _zbg_current_identity _zbg_font _zbg_marker _zbg_mod _zbg_saved_identity
    _zbg_mod="$1"; _zbg_font="$2"
    _zbg_marker=$(_zbg_ota_path "$_zbg_mod")
    [ "$(_zbg_value "$_zbg_marker" schema)" = ziyu-ota-pending-v1 ] && \
    [ "$(_zbg_value "$_zbg_marker" state)" = apply-ready ] && \
    [ "$(_zbg_value "$_zbg_marker" apply_font)" = "$_zbg_font" ] || return 1
    _zbg_saved_identity=$(_zbg_value "$_zbg_marker" generation_identity)
    _zbg_current_identity=$(_zbg_apply_generation_identity "$_zbg_mod" "$_zbg_font" 0) || return 1
    [ -n "$_zbg_saved_identity" ] && [ "$_zbg_saved_identity" = "$_zbg_current_identity" ]
}

_zbg_mark_apply_failed() {
    local _zbg_boot _zbg_candidate _zbg_current_font _zbg_file _zbg_marker _zbg_mod _zbg_ota _zbg_reason _zbg_tmp
    _zbg_mod="$1"; _zbg_reason="$2"
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ] || return 0
    _zbg_marker=$(_zbg_ota_path "$_zbg_mod")
    [ "$(_zbg_value "$_zbg_marker" state)" = apply-ready ] || return 0
    _zbg_current_font=$(_zbg_effective_font "$_zbg_mod")
    _zbg_boot=$(_zbg_boot_id)
    _zbg_write_ota_marker "$_zbg_mod" "$(_zbg_value "$_zbg_marker" previous_fingerprint)" \
        "$(_zbg_value "$_zbg_marker" fingerprint)" 1 "$(_zbg_value "$_zbg_marker" detected_boot_id)" || return 1
    for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
        _zbg_file=$(_zbg_ota_path "$_zbg_candidate")
        [ "$(_zbg_value "$_zbg_file" schema)" = ziyu-ota-pending-v1 ] || continue
        _zbg_tmp="$_zbg_file.tmp.$$"
        {
            printf 'schema=ziyu-ota-pending-v1\nprevious_fingerprint=%s\nfingerprint=%s\n' \
                "$(_zbg_value "$_zbg_file" previous_fingerprint)" "$(_zbg_value "$_zbg_file" fingerprint)"
            printf 'detected_boot_id=%s\nscan_complete=1\nreason=system-fingerprint-changed\n' "$(_zbg_value "$_zbg_file" detected_boot_id)"
            printf 'state=scan-complete\napply_failed_boot_id=%s\napply_failed_reason=%s\n' "$_zbg_boot" "$_zbg_reason"
            printf 'rom_identity=%s\n' "$(_zbg_rom_identity)"
        } > "$_zbg_tmp" 2>/dev/null || return 1
        chmod 0600 "$_zbg_tmp" 2>/dev/null || true
        mv -f "$_zbg_tmp" "$_zbg_file" 2>/dev/null || return 1
    done
    _zbg_create_disable "$_zbg_mod" ota_hold "$_zbg_boot" || return 1
    _zbg_write_current_boot_block "$_zbg_mod" ota-fingerprint-change "$_zbg_boot" || return 1
    _zbg_log "$_zbg_mod" "approved OTA generation failed verification; hold restored font=$_zbg_current_font reason=$_zbg_reason"
}

_zbg_schedule_watcher() {
    local _zbg_base _zbg_boot _zbg_finished _zbg_lock _zbg_lock_root _zbg_mod _zbg_now _zbg_ota _zbg_pid _zbg_pid_start _zbg_ready_wait _zbg_same _zbg_saved _zbg_saved_task _zbg_script _zbg_start_rc _zbg_started _zbg_state_tmp _zbg_task _zbg_watch_mod
    _zbg_mod="$1"
    _zbg_watch_mod="$_zbg_mod"
    case "$_zbg_mod" in
        */modules_update/ziyu)
            _zbg_base=$(printf '%s\n' "$_zbg_mod" | sed 's,/modules_update/ziyu$,,')
            _zbg_watch_mod="$_zbg_base/modules/ziyu"
            ;;
    esac
    [ "$ZIYU_BOOT_OTA_DISABLE_WORKER" != 1 ] || return 0
    [ -f "$_zbg_mod/common/font_manager.sh" ] || return 0
    [ -f "$_zbg_mod/common/background_task.sh" ] && [ -f "$_zbg_mod/common/task_scope.sh" ] || return 1
    mkdir -p "$_zbg_mod/logs" "${ZIYU_BOOT_OTA_TEST_LOCK_ROOT:-/data/adb}" 2>/dev/null || return 1
    _zbg_lock_root=${ZIYU_BOOT_OTA_TEST_LOCK_ROOT:-/data/adb}
    _zbg_lock="$_zbg_lock_root/.ziyu-luoshu-boot-ota-scan.lock"
    _zbg_boot=$(_zbg_boot_id)
    _zbg_task="ziyu-boot-ota-$_zbg_boot"
    if [ -d "$_zbg_lock" ]; then
        _zbg_saved=$(_zbg_value "$_zbg_lock/state" boot_id)
        _zbg_saved_task=$(_zbg_value "$_zbg_lock/state" task)
        _zbg_pid=$(_zbg_value "$_zbg_lock/state" pid)
        _zbg_pid_start=$(_zbg_value "$_zbg_lock/state" proc_starttime)
        _zbg_script=$(_zbg_value "$_zbg_lock/state" supervisor_script)
        _zbg_started=$(_zbg_value "$_zbg_lock/state" started)
        _zbg_finished=$(_zbg_value "$_zbg_lock/state" finished)
        case "$_zbg_started" in ''|*[!0-9]*) _zbg_started=0 ;; esac
        _zbg_now=$(date +%s 2>/dev/null || echo 0)
        case "$_zbg_now" in ''|*[!0-9]*) _zbg_now=$_zbg_started ;; esac
        if [ "$_zbg_saved" = "$_zbg_boot" ] && [ "$_zbg_saved_task" = "$_zbg_task" ]; then
            if [ "$_zbg_finished" = 1 ]; then
                # The boot observer finishing does not prove that its separate
                # stock-scan worker succeeded. Resume that worker through its
                # existing lock instead of leaving an OTA hold stuck forever.
                if _zbg_boot_completed; then
                    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
                    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ] &&
                       ! _zbg_stock_scan_trusted "$_zbg_mod"; then
                        _zbg_queue_rescan "$_zbg_mod" >/dev/null
                        return $?
                    fi
                fi
                return 0
            fi
            _zbg_same=0
            if _zbg_worker_identity "$_zbg_pid" "$_zbg_task" "$_zbg_pid_start" "$_zbg_script"; then
                _zbg_same=1
            elif [ $((_zbg_now - _zbg_started)) -lt 15 ]; then
                # The task launcher writes PID/start-time sidecars just after
                # creating the lock; don't race a second launch through it.
                return 0
            fi
            [ "$_zbg_same" != 1 ] || return 0
        fi
        rm -f "$_zbg_lock/state" "$_zbg_lock/worker.ready" "$_zbg_lock/worker.pid" "$_zbg_lock/worker.pid.task" \
            "$_zbg_lock/worker.pid.boot" "$_zbg_lock/worker.pid.cleanup.json" 2>/dev/null || return 1
        rmdir "$_zbg_lock" 2>/dev/null || return 1
    fi
    mkdir "$_zbg_lock" 2>/dev/null || return 0
    _zbg_started=$(date +%s 2>/dev/null || echo 0)
    _zbg_script="$_zbg_mod/common/task_scope.py"
    {
        printf 'boot_id=%s\ntask=%s\npid=pending\nproc_starttime=pending\n' "$_zbg_boot" "$_zbg_task"
        printf 'started=%s\nmodule=%s\nsupervisor_script=%s\nfinished=0\n' "$_zbg_started" "$_zbg_watch_mod" "$_zbg_script"
    } > "$_zbg_lock/state" 2>/dev/null || return 1
    . "$_zbg_mod/common/background_task.sh" || return 1
    MODDIR="$_zbg_mod" MODULE_DIR="$_zbg_mod" LUOSHU_TASK_TIMEOUT_SECONDS=900 \
        luoshu_start_detached "$_zbg_lock/worker.pid" "$_zbg_task" \
        "$_zbg_mod/logs/boot-ota-guard.log" \
        sh "$_zbg_mod/common/boot_ota_guard.sh" _watcher "$_zbg_watch_mod" "$_zbg_boot"
    _zbg_start_rc=$?
    [ "$_zbg_start_rc" -eq 0 ] || return 1
    _zbg_pid=$(luoshu_pid_value "$_zbg_lock/worker.pid")
    _zbg_pid_start=$(_zbg_proc_starttime "$_zbg_pid")
    _zbg_state_tmp="$_zbg_lock/state.tmp.$$"
    {
        printf 'boot_id=%s\ntask=%s\npid=%s\nproc_starttime=%s\n' "$_zbg_boot" "$_zbg_task" "$_zbg_pid" "$_zbg_pid_start"
        printf 'started=%s\nmodule=%s\nsupervisor_script=%s\nfinished=0\n' "$_zbg_started" "$_zbg_watch_mod" "$_zbg_script"
    } > "$_zbg_state_tmp" 2>/dev/null && chmod 0600 "$_zbg_state_tmp" 2>/dev/null && \
        mv -f "$_zbg_state_tmp" "$_zbg_lock/state" 2>/dev/null || return 1
    # A staged APatch module is renamed immediately after the common hook.
    # Wait only for the bounded worker to have loaded this script and runtime;
    # it subsequently addresses the module by its post-rename active path.
    case "$_zbg_mod" in
        */modules_update/ziyu)
            _zbg_ready_wait=0
            while [ "$(cat "$_zbg_lock/worker.ready" 2>/dev/null)" != "$_zbg_boot" ] &&
                  [ "$_zbg_ready_wait" -lt 2 ]; do
                sleep 1
                _zbg_ready_wait=$((_zbg_ready_wait + 1))
            done
            [ "$(cat "$_zbg_lock/worker.ready" 2>/dev/null)" = "$_zbg_boot" ] || return 1
            ;;
    esac
    return 0
}

_zbg_run_rescan_direct() {
    local _zbg_after _zbg_mod _zbg_ota _zbg_out _zbg_rc
    _zbg_mod="$1"
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" != 1 ]; then
        printf 'status=ok\nscan_state=complete\nreason=not-pending\nota_pending=0\nscan_complete=0\n'
        return 0
    fi
    _zbg_boot_completed || { printf 'status=error\nscan_state=error\nreason=boot-not-completed\nota_pending=1\nscan_complete=0\n'; return 1; }
    [ -f "$_zbg_mod/common/font_manager.sh" ] || {
        printf 'status=error\nscan_state=error\nreason=font-manager-unavailable\nota_pending=1\nscan_complete=0\n'
        return 1
    }
    _zbg_out=$(MODDIR="$_zbg_mod" MODULE_DIR="$_zbg_mod" LUOSHU_FRESH_STOCK_SCAN=1 \
        sh "$_zbg_mod/common/font_manager.sh" action stock_scan 2>&1)
    _zbg_rc=$?
    printf '[%s] [BOOT-OTA-GUARD] trusted stock rescan rc=%s deadline=%ss: %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$_zbg_rc" "${LUOSHU_TASK_TIMEOUT_SECONDS:-900}" \
        "$(printf '%s' "$_zbg_out" | tr '\n\r' '  ' | cut -c1-900)" \
        >> "$_zbg_mod/logs/boot-ota-guard.log" 2>/dev/null || true
    _zbg_after=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$_zbg_rc" -eq 0 ] && _zbg_stock_scan_trusted "$_zbg_mod"; then
        if [ "$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" state)" = default-ready ]; then
            _zbg_finalize_default_after_scan "$_zbg_mod" || {
                printf 'status=error\nscan_state=error\nreason=default-ota-finalize-failed\nota_pending=1\nscan_complete=1\n'
                return 1
            }
        fi
        printf 'status=ok\nscan_state=complete\nreason=stock-rescan-complete\nota_pending=%s\nscan_complete=1\n' \
            "$(printf '%s\n' "$(_zbg_ota_pending "$_zbg_mod")" | cut -d '|' -f1)"
        return 0
    fi
    printf 'status=error\nscan_state=error\nreason=stock-rescan-failed-rc-%s\nota_pending=1\nscan_complete=0\n' "$_zbg_rc"
    return 1
}

_zbg_rescan_worker() {
    local _zbg_boot _zbg_lock _zbg_mod _zbg_rc _zbg_state_tmp
    _zbg_mod="$1"; _zbg_boot="$2"
    _zbg_run_rescan_direct "$_zbg_mod" >/dev/null
    _zbg_rc=$?
    _zbg_lock=$(_zbg_rescan_lock)
    if [ -d "$_zbg_lock" ] && [ "$(_zbg_value "$_zbg_lock/state" boot_id)" = "$_zbg_boot" ]; then
        _zbg_state_tmp="$_zbg_lock/state.tmp.$$"
        awk '$1 !~ /^finished=/ && $1 !~ /^result=/' "$_zbg_lock/state" > "$_zbg_state_tmp" 2>/dev/null || return 1
        printf 'finished=1\nresult=%s\n' "$_zbg_rc" >> "$_zbg_state_tmp" || return 1
        mv -f "$_zbg_state_tmp" "$_zbg_lock/state" 2>/dev/null || return 1
    fi
    return "$_zbg_rc"
}

_zbg_rescan_state() {
    local _zbg_expected _zbg_lock _zbg_mod _zbg_now _zbg_ota _zbg_pid _zbg_saved_module _zbg_script _zbg_start _zbg_started _zbg_task
    _zbg_mod="$1"
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" != 1 ]; then
        printf 'complete\n'; return 0
    fi
    if _zbg_stock_scan_trusted "$_zbg_mod"; then
        printf 'complete\n'; return 0
    fi
    _zbg_lock=$(_zbg_rescan_lock)
    [ -d "$_zbg_lock" ] || { printf 'queued\n'; return 0; }
    _zbg_saved_module=$(_zbg_value "$_zbg_lock/state" module)
    case "$_zbg_mod" in
        */modules_update/ziyu) _zbg_expected=$(printf '%s\n' "$_zbg_mod" | sed 's,/modules_update/ziyu$,/modules/ziyu,') ;;
        *) _zbg_expected=$_zbg_mod ;;
    esac
    [ "$_zbg_saved_module" = "$_zbg_mod" ] || [ "$_zbg_saved_module" = "$_zbg_expected" ] || { printf 'error\n'; return 0; }
    [ "$(_zbg_value "$_zbg_lock/state" boot_id)" = "$(_zbg_boot_id)" ] || { printf 'error\n'; return 0; }
    [ "$(_zbg_value "$_zbg_lock/state" finished)" != 1 ] || {
        # Trusted completion was checked above. A stale successful exit code
        # must not hide a missing receipt, pending marker or changed ROM.
        printf 'error\n'
        return 0
    }
    _zbg_task=$(_zbg_value "$_zbg_lock/state" task)
    _zbg_pid=$(_zbg_value "$_zbg_lock/state" pid)
    _zbg_start=$(_zbg_value "$_zbg_lock/state" proc_starttime)
    _zbg_script=$(_zbg_value "$_zbg_lock/state" supervisor_script)
    if _zbg_worker_identity "$_zbg_pid" "$_zbg_task" "$_zbg_start" "$_zbg_script"; then
        printf 'running\n'; return 0
    fi
    _zbg_started=$(_zbg_value "$_zbg_lock/state" started)
    case "$_zbg_started" in ''|*[!0-9]*) _zbg_started=0 ;; esac
    _zbg_now=$(date +%s 2>/dev/null || echo 0)
    [ $((_zbg_now - _zbg_started)) -lt 15 ] && printf 'queued\n' || printf 'error\n'
}

_zbg_queue_rescan() {
    local _zbg_boot _zbg_lock _zbg_lock_root _zbg_mod _zbg_ota _zbg_pid _zbg_pid_start _zbg_scan_timeout _zbg_script _zbg_start_rc _zbg_started _zbg_state _zbg_state_tmp _zbg_task _zbg_watch_mod
    _zbg_mod="$1"
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" != 1 ] || _zbg_stock_scan_trusted "$_zbg_mod"; then
        printf 'status=ok\nscan_state=complete\nota_pending=%s\nscan_complete=%s\n' \
            "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" \
            "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f2)"
        return 0
    fi
    _zbg_boot_completed || { printf 'status=error\nscan_state=error\nreason=boot-not-completed\nota_pending=1\nscan_complete=0\n'; return 1; }
    [ "$ZIYU_BOOT_OTA_DISABLE_WORKER" != 1 ] || {
        printf 'status=error\nscan_state=error\nreason=worker-disabled\nota_pending=1\nscan_complete=0\n'; return 1;
    }
    [ -f "$_zbg_mod/common/background_task.sh" ] && [ -f "$_zbg_mod/common/task_scope.sh" ] && \
    [ -x "$_zbg_mod/common/python/bin/luoshu-python" ] && [ -f "$_zbg_mod/common/task_scope.py" ] && \
    [ -f "$_zbg_mod/common/font_manager.sh" ] || {
        printf 'status=error\nscan_state=error\nreason=task-supervisor-unavailable\nota_pending=1\nscan_complete=0\n'; return 1;
    }
    _zbg_lock=$(_zbg_rescan_lock)
    _zbg_lock_root=${_zbg_lock%/*}
    mkdir -p "$_zbg_mod/logs" "$_zbg_lock_root" 2>/dev/null || return 1
    if [ -d "$_zbg_lock" ]; then
        _zbg_state=$(_zbg_rescan_state "$_zbg_mod")
        case "$_zbg_state" in
            complete)
                printf 'status=ok\nscan_state=complete\nota_pending=1\nscan_complete=1\n'; return 0 ;;
            running|queued)
                printf 'status=ok\nscan_state=%s\nota_pending=1\nscan_complete=0\n' "$_zbg_state"; return 0 ;;
            error)
                ;;
        esac
        rm -f "$_zbg_lock/state" "$_zbg_lock/worker.pid" "$_zbg_lock/worker.pid.task" \
            "$_zbg_lock/worker.pid.boot" "$_zbg_lock/worker.pid.cleanup.json" 2>/dev/null || return 1
        rmdir "$_zbg_lock" 2>/dev/null || return 1
    fi
    mkdir "$_zbg_lock" 2>/dev/null || {
        printf 'status=ok\nscan_state=queued\nota_pending=1\nscan_complete=0\n'; return 0;
    }
    _zbg_boot=$(_zbg_boot_id)
    _zbg_task="ziyu-stock-rescan-$_zbg_boot"
    _zbg_started=$(date +%s 2>/dev/null || echo 0)
    _zbg_watch_mod="$_zbg_mod"
    case "$_zbg_mod" in
        */modules_update/ziyu) _zbg_watch_mod=$(printf '%s\n' "$_zbg_mod" | sed 's,/modules_update/ziyu$,/modules/ziyu,') ;;
    esac
    _zbg_script="$_zbg_mod/common/task_scope.py"
    {
        printf 'boot_id=%s\ntask=%s\npid=pending\nproc_starttime=pending\n' "$_zbg_boot" "$_zbg_task"
        printf 'started=%s\nmodule=%s\nsupervisor_script=%s\nfinished=0\n' "$_zbg_started" "$_zbg_watch_mod" "$_zbg_script"
    } > "$_zbg_lock/state" 2>/dev/null || return 1
    . "$_zbg_mod/common/background_task.sh" || return 1
    _zbg_scan_timeout=${LUOSHU_BOOT_OTA_SCAN_TIMEOUT_SECONDS:-300}
    case "$_zbg_scan_timeout" in ''|*[!0-9]*) _zbg_scan_timeout=300 ;; esac
    [ "$_zbg_scan_timeout" -ge 30 ] 2>/dev/null || _zbg_scan_timeout=30
    [ "$_zbg_scan_timeout" -le 600 ] 2>/dev/null || _zbg_scan_timeout=600
    MODDIR="$_zbg_mod" MODULE_DIR="$_zbg_mod" LUOSHU_TASK_TIMEOUT_SECONDS="$_zbg_scan_timeout" \
        luoshu_start_detached "$_zbg_lock/worker.pid" "$_zbg_task" \
            "$_zbg_mod/logs/boot-ota-guard.log" \
            sh "$_zbg_mod/common/boot_ota_guard.sh" _rescan-run "$_zbg_watch_mod" "$_zbg_boot"
    _zbg_start_rc=$?
    [ "$_zbg_start_rc" -eq 0 ] || return 1
    _zbg_pid=$(luoshu_pid_value "$_zbg_lock/worker.pid")
    _zbg_pid_start=$(_zbg_proc_starttime "$_zbg_pid")
    if [ "$(_zbg_value "$_zbg_lock/state" finished)" != 1 ]; then
        _zbg_state_tmp="$_zbg_lock/state.tmp.$$"
        {
            printf 'boot_id=%s\ntask=%s\npid=%s\nproc_starttime=%s\n' "$_zbg_boot" "$_zbg_task" "$_zbg_pid" "$_zbg_pid_start"
            printf 'started=%s\nmodule=%s\nsupervisor_script=%s\nfinished=0\n' "$_zbg_started" "$_zbg_watch_mod" "$_zbg_script"
        } > "$_zbg_state_tmp" 2>/dev/null && chmod 0600 "$_zbg_state_tmp" 2>/dev/null && \
            mv -f "$_zbg_state_tmp" "$_zbg_lock/state" 2>/dev/null || return 1
    fi
    printf 'status=ok\nscan_state=queued\nota_pending=1\nscan_complete=0\n'
}

_zbg_watcher() {
    local _zbg_boot _zbg_lock _zbg_mod _zbg_ota _zbg_rc _zbg_state_tmp _zbg_wait
    _zbg_mod="$1"; _zbg_boot="$2"; _zbg_wait=0
    _zbg_lock="${ZIYU_BOOT_OTA_TEST_LOCK_ROOT:-/data/adb}/.ziyu-luoshu-boot-ota-scan.lock"
    [ "$(_zbg_value "$_zbg_lock/state" boot_id)" = "$_zbg_boot" ] || return 1
    (umask 077; printf '%s\n' "$_zbg_boot" > "$_zbg_lock/worker.ready") || return 1
    while ! _zbg_boot_completed && [ "$_zbg_wait" -lt 600 ]; do
        sleep 1
        _zbg_wait=$((_zbg_wait + 1))
    done
    if _zbg_boot_completed; then
        sh "$_zbg_mod/common/boot_ota_guard.sh" complete "$_zbg_mod" >> "$_zbg_mod/logs/boot-ota-guard.log" 2>&1 || return 1
        _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
        if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ] && \
           ! _zbg_stock_scan_trusted "$_zbg_mod"; then
            _zbg_queue_rescan "$_zbg_mod" >/dev/null || _zbg_rc=$?
        fi
    fi
    _zbg_lock="${ZIYU_BOOT_OTA_TEST_LOCK_ROOT:-/data/adb}/.ziyu-luoshu-boot-ota-scan.lock"
    if [ "$(_zbg_value "$_zbg_lock/state" boot_id)" = "$_zbg_boot" ]; then
        _zbg_state_tmp="$_zbg_lock/state.tmp.$$"
        awk '$1 !~ /^finished=/ && $1 !~ /^result=/' "$_zbg_lock/state" > "$_zbg_state_tmp" 2>/dev/null || return 1
        printf 'finished=1\nresult=%s\n' "${_zbg_rc:-0}" >> "$_zbg_state_tmp" || return 1
        mv -f "$_zbg_state_tmp" "$_zbg_lock/state" 2>/dev/null || return 1
    fi
    return 0
}

_zbg_cleanup_task_lock() {
    local _zbg_expected_module _zbg_kind _zbg_lock _zbg_mod _zbg_pid _zbg_saved_module _zbg_script _zbg_start _zbg_stop_wait _zbg_task
    _zbg_lock="$1"; _zbg_mod="$2"; _zbg_kind="$3"
    [ -d "$_zbg_lock" ] || return 0
    _zbg_expected_module="$_zbg_mod"
    case "$_zbg_mod" in
        */modules_update/ziyu)
            _zbg_expected_module=$(printf '%s\n' "$_zbg_mod" | sed 's,/modules_update/ziyu$,/modules/ziyu,')
            ;;
        */modules/ziyu)
            _zbg_expected_module=$(printf '%s\n' "$_zbg_mod" | sed 's,/modules/ziyu$,/modules_update/ziyu,')
            ;;
    esac
    _zbg_saved_module=$(_zbg_value "$_zbg_lock/state" module)
    [ "$_zbg_saved_module" = "$_zbg_mod" ] || [ "$_zbg_saved_module" = "$_zbg_expected_module" ] || {
        printf 'status=error\nreason=%s-lock-module-mismatch\n' "$_zbg_kind"
        return 1
    }
    _zbg_task=$(_zbg_value "$_zbg_lock/state" task)
    case "$_zbg_kind:$_zbg_task" in
        watcher:ziyu-boot-ota-*|rescan:ziyu-stock-rescan-*) ;;
        *) printf 'status=error\nreason=%s-lock-task-mismatch\n' "$_zbg_kind"; return 1 ;;
    esac
    _zbg_pid=$(_zbg_value "$_zbg_lock/state" pid)
    _zbg_start=$(_zbg_value "$_zbg_lock/state" proc_starttime)
    _zbg_script=$(_zbg_value "$_zbg_lock/state" supervisor_script)
    case "$_zbg_script" in */common/task_scope.py) ;; *)
        printf 'status=error\nreason=%s-lock-supervisor-mismatch\n' "$_zbg_kind"
        return 1
        ;;
    esac
    case "$_zbg_script" in
        "$_zbg_mod/common/task_scope.py"|"$_zbg_expected_module/common/task_scope.py") ;;
        *) printf 'status=error\nreason=%s-lock-supervisor-module-mismatch\n' "$_zbg_kind"; return 1 ;;
    esac
    if [ "$(_zbg_value "$_zbg_lock/state" finished)" != 1 ]; then
        if [ -n "$_zbg_pid" ] && [ "$(_zbg_proc_starttime "$_zbg_pid")" = "$_zbg_start" ]; then
            _zbg_worker_identity "$_zbg_pid" "$_zbg_task" "$_zbg_start" "$_zbg_script" || {
                printf 'status=error\nreason=%s-lock-live-identity-ambiguous\n' "$_zbg_kind"
                return 1
            }
            . "$_zbg_mod/common/background_task.sh" || return 1
            luoshu_terminate_task_tree "$_zbg_pid" || {
                printf 'status=error\nreason=%s-worker-stop-failed\n' "$_zbg_kind"
                return 1
            }
            _zbg_stop_wait=0
            while _zbg_worker_identity "$_zbg_pid" "$_zbg_task" "$_zbg_start" "$_zbg_script" && \
                  [ "$_zbg_stop_wait" -lt 5 ]; do
                sleep 1
                _zbg_stop_wait=$((_zbg_stop_wait + 1))
            done
            _zbg_worker_identity "$_zbg_pid" "$_zbg_task" "$_zbg_start" "$_zbg_script" && {
                printf 'status=error\nreason=%s-worker-stop-unconfirmed\n' "$_zbg_kind"
                return 1
            }
        elif [ -n "$_zbg_pid" ] && [ -e "${ZIYU_BOOT_OTA_TEST_PROC_ROOT:-/proc}/$_zbg_pid/stat" ] && \
             [ "$(_zbg_proc_starttime "$_zbg_pid")" = "$_zbg_start" ]; then
            printf 'status=error\nreason=%s-worker-identity-ambiguous\n' "$_zbg_kind"
            return 1
        fi
    fi
    rm -f "$_zbg_lock/state" "$_zbg_lock/state.tmp."* "$_zbg_lock/worker.ready" \
        "$_zbg_lock/worker.pid" "$_zbg_lock/worker.pid.task" \
        "$_zbg_lock/worker.pid.boot" "$_zbg_lock/worker.pid.cleanup.json" 2>/dev/null || return 1
    rmdir "$_zbg_lock" 2>/dev/null || return 1
    return 0
}

_zbg_status() {
    local _zbg_apply_font _zbg_block _zbg_current_boot _zbg_mod _zbg_next_ready _zbg_ota _zbg_ota_value _zbg_reason _zbg_safe _zbg_safe_reason _zbg_safe_value _zbg_scan_state _zbg_scan_value
    _zbg_mod="$1"
    [ -d "$_zbg_mod" ] && [ ! -L "$_zbg_mod" ] || {
        printf 'status=error\nsafe_mode=0\nunfinished_boots=0\ncount=0\nreason=module-directory-unavailable\nota_pending=0\n'
        return 1
    }
    _zbg_load_state "$_zbg_mod"
    _zbg_safe=$(_zbg_safe_mode "$_zbg_mod")
    _zbg_safe_value=$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f1)
    _zbg_safe_reason=$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f2)
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    _zbg_ota_value=$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)
    _zbg_scan_value=$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f2)
    _zbg_reason=none
    if [ "$_zbg_safe_value" = 1 ]; then _zbg_reason=boot-safety-safe-mode
    elif [ "$_zbg_ota_value" = 1 ] && [ "$_zbg_scan_value" != 1 ]; then _zbg_reason=ota-fingerprint-change
    elif [ "$_zbg_ota_value" = 1 ]; then _zbg_reason=ota-fingerprint-change
    elif [ "$ZBG_COUNT" -gt 0 ]; then _zbg_reason=unfinished-boot-pending
    fi
    _zbg_scan_state=$(_zbg_rescan_state "$_zbg_mod")
    _zbg_next_ready=0
    _zbg_apply_font=$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" apply_font)
    if [ "$_zbg_ota_value" = 1 ] && [ -n "$_zbg_apply_font" ] && \
       _zbg_apply_ready_matches "$_zbg_mod" "$_zbg_apply_font"; then
        _zbg_next_ready=1
    fi
    printf 'status=ok\nsafe_mode=%s\nunfinished_boots=%s\ncount=%s\n' "$_zbg_safe_value" "$ZBG_COUNT" "$ZBG_COUNT"
    _zbg_current_boot=$(_zbg_boot_id)
    _zbg_block=0
    [ -f "$_zbg_mod/config/boot-safety-current-boot.conf" ] && \
    [ ! -L "$_zbg_mod/config/boot-safety-current-boot.conf" ] && \
    [ "$(_zbg_value "$_zbg_mod/config/boot-safety-current-boot.conf" boot_id)" = "$_zbg_current_boot" ] && \
    [ "$(_zbg_value "$_zbg_mod/config/boot-safety-current-boot.conf" state)" = blocked ] && _zbg_block=1
    printf 'reason=%s\nota_pending=%s\ncurrent_boot_blocked=%s\nscan_state=%s\nnext_generation_ready=%s\n' \
        "$_zbg_reason" "$_zbg_ota_value" "$_zbg_block" "$_zbg_scan_state" "$_zbg_next_ready"
}

_zbg_recover() {
    local _zbg_candidate _zbg_marker _zbg_mod _zbg_ota _zbg_ota_value _zbg_rc _zbg_safe _zbg_safe_value
    _zbg_mod="$1"; _zbg_rc=0
    _zbg_safe=$(_zbg_safe_mode "$_zbg_mod")
    _zbg_safe_value=$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f1)
    if [ "$_zbg_safe_value" = 1 ]; then
        _zbg_release_disable "$_zbg_mod" safe_mode || _zbg_rc=1
        if [ "$_zbg_rc" -eq 0 ]; then
            for _zbg_candidate in $(_zbg_candidates "$_zbg_mod"); do
                _zbg_marker=$(_zbg_safe_path "$_zbg_candidate")
                if [ -f "$_zbg_marker" ] && [ ! -L "$_zbg_marker" ] && \
                   [ "$(_zbg_value "$_zbg_marker" schema)" = ziyu-boot-safety-v1 ]; then
                    rm -f "$_zbg_marker" 2>/dev/null || _zbg_rc=1
                    [ ! -e "$_zbg_marker" ] || _zbg_rc=1
                fi
            done
        fi
    fi
    _zbg_load_state "$_zbg_mod"
    ZBG_COUNT=0; ZBG_PENDING_BOOT=''; ZBG_REASON=none
    _zbg_write_state_all "$_zbg_mod" || _zbg_rc=1
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    _zbg_ota_value=$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)
    if [ "$_zbg_ota_value" = 1 ]; then
        _zbg_create_disable "$_zbg_mod" ota_hold "$(_zbg_boot_id)" || _zbg_rc=1
    fi
    if [ "$_zbg_rc" -ne 0 ]; then
        printf 'status=error\nsafe_mode=1\nunfinished_boots=0\ncount=0\nreason=recovery-cleanup-failed\nota_pending=%s\n' "$_zbg_ota_value"
        return 1
    fi
    _zbg_log "$_zbg_mod" 'safe-mode recovery completed; OTA hold and external disables remain'
    _zbg_status "$_zbg_mod"
}

_zbg_observe() {
    local _zbg_boot _zbg_mod _zbg_ota _zbg_safe _zbg_safe_value
    _zbg_mod="$1"
    [ -d "$_zbg_mod" ] && [ ! -L "$_zbg_mod" ] || return 1
    _zbg_load_state "$_zbg_mod"
    _zbg_detect_ota "$_zbg_mod" || return 1
    _zbg_boot=$(_zbg_boot_id)
    [ -n "$_zbg_boot" ] || return 1
    _zbg_safe=$(_zbg_safe_mode "$_zbg_mod")
    _zbg_safe_value=$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f1)
    if _zbg_boot_completed; then
        ZBG_COUNT=0; ZBG_PENDING_BOOT=''; ZBG_LAST_COMPLETED=$_zbg_boot; ZBG_REASON=none
        _zbg_write_state_all "$_zbg_mod" || return 1
        return 0
    fi
    if ! _zbg_effective_custom_font "$_zbg_mod"; then
        # A queued default restore takes precedence over the previously active
        # custom selection and must be allowed to finish without counting as a
        # failed custom-font boot.
        ZBG_COUNT=0; ZBG_PENDING_BOOT=''; ZBG_REASON=none
        _zbg_write_state_all "$_zbg_mod" || return 1
        return 0
    fi
    [ "$_zbg_safe_value" != 1 ] || return 10
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ]; then
        if ! _zbg_stock_scan_trusted "$_zbg_mod" || \
           ! _zbg_apply_ready_matches "$_zbg_mod" "$(_zbg_effective_font "$_zbg_mod")"; then
            ZBG_COUNT=0; ZBG_PENDING_BOOT=$_zbg_boot; ZBG_REASON=ota-hold
            _zbg_write_state_all "$_zbg_mod" || return 1
            return 12
        fi
    fi
    if [ "$ZBG_PENDING_BOOT" != "$_zbg_boot" ]; then
        if [ -n "$ZBG_PENDING_BOOT" ] && [ "$ZBG_PENDING_BOOT" != "$ZBG_LAST_COMPLETED" ]; then
            ZBG_COUNT=$((ZBG_COUNT + 1))
        fi
        ZBG_PENDING_BOOT=$_zbg_boot
    fi
    ZBG_REASON=unfinished-boot-pending
    if [ "$ZBG_COUNT" -ge 2 ]; then
        ZBG_COUNT=2
        ZBG_REASON=two-unfinished-boots
        _zbg_write_safe_marker "$_zbg_mod" "$_zbg_boot" || return 1
        _zbg_write_state_all "$_zbg_mod" || return 1
        return 10
    fi
    _zbg_write_state_all "$_zbg_mod" || return 1
    return 0
}

_zbg_complete() {
    local _zbg_boot _zbg_mod
    _zbg_mod="$1"
    _zbg_load_state "$_zbg_mod"
    _zbg_detect_ota "$_zbg_mod" || return 1
    _zbg_boot=$(_zbg_boot_id)
    [ -n "$_zbg_boot" ] && _zbg_boot_completed || return 2
    ZBG_COUNT=0; ZBG_PENDING_BOOT=''; ZBG_LAST_COMPLETED=$_zbg_boot; ZBG_REASON=none
    _zbg_write_state_all "$_zbg_mod"
}

_zbg_hook() {
    local _zbg_block_reason _zbg_hook_rc _zbg_mod _zbg_ota _zbg_safe
    _zbg_mod="$1"
    _zbg_observe "$_zbg_mod"
    _zbg_hook_rc=$?
    case "$_zbg_hook_rc" in
        10|12)
            if [ "$_zbg_hook_rc" = 10 ]; then _zbg_block_reason=boot-safety-safe-mode
            else _zbg_block_reason=ota-fingerprint-change
            fi
            if [ "$_zbg_hook_rc" = 10 ]; then
                _zbg_create_disable "$_zbg_mod" safe_mode "$(_zbg_boot_id)" || return 1
            else
                _zbg_create_disable "$_zbg_mod" ota_hold "$(_zbg_boot_id)" || return 1
            fi
            _zbg_write_current_boot_block "$_zbg_mod" "$_zbg_block_reason" "$(_zbg_boot_id)" || return 1
            _zbg_schedule_watcher "$_zbg_mod" || true
            if _zbg_boot_completed && [ "$_zbg_hook_rc" = 12 ]; then _zbg_queue_rescan "$_zbg_mod" >/dev/null || true; fi
            return "$_zbg_hook_rc"
            ;;
        0) ;;
        *) return 1 ;;
    esac
    _zbg_safe=$(_zbg_safe_mode "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f1)" = 1 ] && _zbg_effective_custom_font "$_zbg_mod"; then
        _zbg_create_disable "$_zbg_mod" safe_mode "$(_zbg_boot_id)" || return 1
        _zbg_write_current_boot_block "$_zbg_mod" boot-safety-safe-mode "$(_zbg_boot_id)" || return 1
        _zbg_schedule_watcher "$_zbg_mod" || true
        return 10
    fi
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ]; then
        _zbg_schedule_watcher "$_zbg_mod" || true
        if _zbg_effective_custom_font "$_zbg_mod" && \
           { ! _zbg_stock_scan_trusted "$_zbg_mod" || ! _zbg_apply_ready_matches "$_zbg_mod" "$(_zbg_effective_font "$_zbg_mod")"; }; then
            _zbg_create_disable "$_zbg_mod" ota_hold "$(_zbg_boot_id)" || return 1
            _zbg_write_current_boot_block "$_zbg_mod" ota-fingerprint-change "$(_zbg_boot_id)" || return 1
            if _zbg_boot_completed; then _zbg_queue_rescan "$_zbg_mod" >/dev/null || true; fi
            return 12
        fi
    fi
    return 0
}

_zbg_allow_apply() {
    local _zbg_font _zbg_mod _zbg_ota _zbg_safe
    _zbg_mod="$1"; _zbg_font="$2"
    [ "$_zbg_font" != default ] || return 0
    _zbg_safe=$(_zbg_safe_mode "$_zbg_mod")
    [ "$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f1)" != 1 ] || {
        printf '%s\n' 'BootSafety safe mode is active; only restoring the system default font is allowed.' >&2
        return 20
    }
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ]; then
        _zbg_stock_scan_trusted "$_zbg_mod" || {
            printf '%s\n' 'OTA hold is waiting for a verified stock-font rescan; reapplying user fonts is paused.' >&2
            return 21
        }
    fi
    return 0
}

_zbg_record_apply() {
    local _zbg_boot _zbg_current _zbg_font _zbg_identity _zbg_marker _zbg_mod _zbg_old _zbg_ota _zbg_safe
    _zbg_mod="$1"; _zbg_font="$2"
    _zbg_ota=$(_zbg_ota_pending "$_zbg_mod")
    _zbg_safe=$(_zbg_safe_mode "$_zbg_mod")
    if [ "$_zbg_font" != default ] && [ "$(printf '%s\n' "$_zbg_safe" | cut -d '|' -f1)" = 1 ]; then
        return 20
    fi
    if [ "$_zbg_font" = default ]; then
        _zbg_identity=$(_zbg_apply_generation_identity "$_zbg_mod" default 1) || return 1
        _zbg_release_disable "$_zbg_mod" safe_mode || return 1
        if [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ]; then
            _zbg_old=$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" previous_fingerprint)
            _zbg_current=$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" fingerprint)
            _zbg_boot=$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" detected_boot_id)
            [ -n "$_zbg_boot" ] || _zbg_boot=$(_zbg_boot_id)
            _zbg_write_ota_apply_ready "$_zbg_mod" "$_zbg_old" "$_zbg_current" \
                "$(_zbg_value "$(_zbg_ota_path "$_zbg_mod")" scan_complete)" "$_zbg_boot" \
                default-ready default "$_zbg_identity" || return 1
            _zbg_release_disable "$_zbg_mod" ota_hold || return 1
        fi
        _zbg_log "$_zbg_mod" "verified default generation accepted; safe-mode marker remains"
        return 0
    fi
    [ "$(printf '%s\n' "$_zbg_ota" | cut -d '|' -f1)" = 1 ] || return 0
    _zbg_stock_scan_trusted "$_zbg_mod" || return 1
    _zbg_identity=$(_zbg_apply_generation_identity "$_zbg_mod" "$_zbg_font" 1) || return 1
    _zbg_marker=$(_zbg_ota_path "$_zbg_mod")
    _zbg_old=$(_zbg_value "$_zbg_marker" previous_fingerprint)
    _zbg_current=$(_zbg_fingerprint)
    _zbg_boot=$(_zbg_boot_id)
    _zbg_write_ota_apply_ready "$_zbg_mod" "$_zbg_old" "$_zbg_current" 1 \
        "$(_zbg_value "$_zbg_marker" detected_boot_id)" apply-ready "$_zbg_font" "$_zbg_identity" || return 1
    # The newly generated payload is bound to the current ROM and validated.
    # Let early hooks run this exact generation; retain OTA hold until the boot
    # verifier confirms that the intended font is actually visible.
    _zbg_release_disable "$_zbg_mod" ota_hold || return 1
    _zbg_log "$_zbg_mod" "validated generation approved for next boot fingerprint=$_zbg_current font=$_zbg_font"
    return 0
}

_zbg_cli() {
    local _zbg_action _zbg_lock_root _zbg_mod
    _zbg_action="$1"
    _zbg_mod=$(_zbg_module "$2")
    case "$_zbg_action" in
        initialize) _zbg_detect_ota "$_zbg_mod" ;;
        status) _zbg_status "$_zbg_mod" ;;
        recover) _zbg_recover "$_zbg_mod" ;;
        hook) _zbg_hook "$_zbg_mod" ;;
        observe) _zbg_observe "$_zbg_mod" ;;
        complete) _zbg_complete "$_zbg_mod" ;;
        allow-apply) _zbg_allow_apply "$_zbg_mod" "$3" ;;
        record-apply) _zbg_record_apply "$_zbg_mod" "$3" ;;
        apply-verified) _zbg_apply_verified "$_zbg_mod" ;;
        apply-failed) _zbg_mark_apply_failed "$_zbg_mod" "${3:-runtime-verification-failed}" ;;
        generation-identity) _zbg_apply_generation_identity "$_zbg_mod" "$3" 0 ;;
        loaded-generation-identity) _zbg_apply_generation_identity "$_zbg_mod" "$3" 0 0 ;;
        rescan) _zbg_queue_rescan "$_zbg_mod" ;;
        _rescan-run) _zbg_rescan_worker "$2" "$3" ;;
        _watcher) _zbg_watcher "$2" "$3" ;;
        scan-complete)
            _zbg_set_scan_complete "$_zbg_mod"
            ;;
        schedule-watcher) _zbg_schedule_watcher "$_zbg_mod" ;;
        uninstall-cleanup)
            _zbg_lock_root=${ZIYU_BOOT_OTA_TEST_LOCK_ROOT:-/data/adb}
            _zbg_cleanup_task_lock "$_zbg_lock_root/.ziyu-luoshu-boot-ota-scan.lock" "$_zbg_mod" watcher || return 1
            _zbg_cleanup_task_lock "$_zbg_lock_root/.ziyu-luoshu-stock-rescan.lock" "$_zbg_mod" rescan || return 1
            _zbg_release_disable "$_zbg_mod" safe_mode || return 1
            _zbg_release_disable "$_zbg_mod" ota_hold || return 1
            printf 'status=ok\nreason=owned-boot-guard-state-cleaned\n'
            ;;
        *) printf 'status=error\nreason=unsupported-action\n'; return 2 ;;
    esac
}

if [ "$(basename "$0" 2>/dev/null)" = boot_ota_guard.sh ]; then
    _zbg_cli "$@"
    exit $?
fi
