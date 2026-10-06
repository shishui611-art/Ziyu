#!/system/bin/sh
# LuoShu next-boot payload activation.
# Foreground font switching only prepares .luoshu-payload-next. This helper runs
# before LuoShu self-mount, so the payload used by the previous boot is never
# renamed, deleted or rewritten while Android is still rendering from it.
set +e

luoshu_next_boot_module() {
    printf '%s\n' "${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}"
}

luoshu_next_boot_value() {
    _lnbv_file="$1"
    _lnbv_key="$2"
    sed -n "s/^${_lnbv_key}=//p" "$_lnbv_file" 2>/dev/null | head -n1 | tr -d '\r\n'
}

luoshu_next_boot_log() {
    _lnbl_module=$(luoshu_next_boot_module)
    mkdir -p "$_lnbl_module/logs" 2>/dev/null || true
    printf '[%s] [NEXT-BOOT] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" \
        >> "$_lnbl_module/logs/fontswitch.log" 2>/dev/null || true
}

luoshu_next_boot_write_mode() {
    _lnbwm_module="$1"
    _lnbwm_font="$2"
    _lnbwm_target_mode="${3:-legacy}"
    _lnbwm_mode="$_lnbwm_module/config/font_runtime_legacy_v14_4.conf"
    _lnbwm_schema="$_lnbwm_module/config/font-payload-schema.conf"
    if [ "$_lnbwm_font" = default ] || [ "$_lnbwm_target_mode" = default ] || [ "$_lnbwm_target_mode" = classic ]; then
        rm -f "$_lnbwm_mode" "$_lnbwm_schema" 2>/dev/null || true
        return 0
    fi
    # The physical path intentionally preserves ROM XML. Its generated fonts
    # still need a frozen hash ledger for the backend's PID 1 route verifier.
    [ -f "$_lnbwm_module/common/physical_payload_manifest.sh" ] || return 1
    . "$_lnbwm_module/common/physical_payload_manifest.sh" || return 1
    luoshu_physical_manifest_build "$_lnbwm_module" "$_lnbwm_module/.luoshu-payload" || return 1
    _lnbwm_tmp="${_lnbwm_mode}.tmp.$$"
    {
        printf 'enabled=true\n'
        printf 'core=physical-safe-v1\n'
        printf 'font=%s\n' "$_lnbwm_font"
        printf 'pipeline=atomic-next-boot\n'
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$_lnbwm_tmp" 2>/dev/null && mv -f "$_lnbwm_tmp" "$_lnbwm_mode" 2>/dev/null || return 1
    chmod 0600 "$_lnbwm_mode" 2>/dev/null || true
    printf 'schema=legacy-physical-safe-v1\n' > "$_lnbwm_schema.tmp.$$" 2>/dev/null &&
        chmod 0644 "$_lnbwm_schema.tmp.$$" 2>/dev/null &&
        mv -f "$_lnbwm_schema.tmp.$$" "$_lnbwm_schema" 2>/dev/null || return 1
    return 0
}

luoshu_next_boot_restore_selection() {
    _lnbrs_module="$1"
    _lnbrs_previous="$2"
    _lnbrs_previous_legacy="$3"
    [ -n "$_lnbrs_previous" ] || _lnbrs_previous=default
    printf '%s\n' "$_lnbrs_previous" > "$_lnbrs_module/config/active_font.conf" 2>/dev/null || true
    chmod 0644 "$_lnbrs_module/config/active_font.conf" 2>/dev/null || true
    if [ "$_lnbrs_previous_legacy" = true ] && [ "$_lnbrs_previous" != default ]; then
        luoshu_next_boot_write_mode "$_lnbrs_module" "$_lnbrs_previous" >/dev/null 2>&1 || true
    else
        rm -f "$_lnbrs_module/config/font_runtime_legacy_v14_4.conf" 2>/dev/null || true
    fi
}

luoshu_next_boot_activate() {
    _lnba_module=$(luoshu_next_boot_module)
    LUOSHU_ACTION_CONTROL_LIBRARY=true . "$_lnba_module/common/action_control.sh" || return 1
    LUOSHU_ACTION_CONTROL_LIBRARY=false
    _lnba_live="$_lnba_module/.luoshu-payload"
    _lnba_next="$_lnba_module/.luoshu-payload-next"
    _lnba_state="$_lnba_module/config/font-payload-next.conf"
    _lnba_activated="$_lnba_module/config/font-payload-activated.conf"
    [ -d "$_lnba_next" ] && [ -s "$_lnba_state" ] || return 2
    if luoshu_undo_cancel_pending_boot "$_lnba_module" "$_lnba_state"; then
        luoshu_next_boot_log 'cancelled task stage discarded before activation'
        return 2
    fi

    _lnba_font=$(luoshu_next_boot_value "$_lnba_state" font)
    _lnba_previous=$(luoshu_next_boot_value "$_lnba_state" previousFont)
    _lnba_previous_legacy=$(luoshu_next_boot_value "$_lnba_state" previousLegacy)
    _lnba_target_mode=$(luoshu_next_boot_value "$_lnba_state" targetMode)
    _lnba_undo=$(luoshu_next_boot_value "$_lnba_state" undo)
    _lnba_request=$(luoshu_next_boot_value "$_lnba_state" requestId)
    _lnba_cjk=$(luoshu_next_boot_value "$_lnba_state" cjk)
    _lnba_latin=$(luoshu_next_boot_value "$_lnba_state" latin)
    _lnba_digit=$(luoshu_next_boot_value "$_lnba_state" digit)
    _lnba_digest=$(luoshu_next_boot_value "$_lnba_state" compositeHash)
    [ -n "$_lnba_font" ] || return 1
    [ -n "$_lnba_previous" ] || _lnba_previous=default
    [ "$_lnba_previous_legacy" = true ] || _lnba_previous_legacy=false
    if [ -z "$_lnba_target_mode" ]; then
        if [ "$_lnba_font" = default ]; then _lnba_target_mode=default
        else _lnba_target_mode=legacy
        fi
    fi

    _lnba_retired_root="$_lnba_module/.luoshu-retired"
    _lnba_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    [ -n "$_lnba_boot" ] || _lnba_boot="$(date +%s 2>/dev/null || echo 0)-$$"
    _lnba_retired="$_lnba_retired_root/payload-${_lnba_boot}-$$"
    while [ -e "$_lnba_retired" ]; do _lnba_retired="${_lnba_retired}-next"; done
    mkdir -p "$_lnba_retired_root" "$_lnba_module/config" 2>/dev/null || return 1
    luoshu_undo_capture_config "$_lnba_module" || return 1

    if [ -d "$_lnba_live" ]; then
        mv "$_lnba_live" "$_lnba_retired" 2>/dev/null || {
            luoshu_next_boot_log "cannot retire previous payload; keeping previous selection=$_lnba_previous"
            luoshu_next_boot_restore_selection "$_lnba_module" "$_lnba_previous" "$_lnba_previous_legacy"
            return 1
        }
    fi

    if ! mv "$_lnba_next" "$_lnba_live" 2>/dev/null; then
        [ ! -d "$_lnba_retired" ] || mv "$_lnba_retired" "$_lnba_live" 2>/dev/null || true
        luoshu_next_boot_restore_selection "$_lnba_module" "$_lnba_previous" "$_lnba_previous_legacy"
        luoshu_next_boot_log "next payload activation failed; previous payload restored"
        return 1
    fi

    chmod 0755 "$_lnba_live" 2>/dev/null || true
    if ! luoshu_undo_restore_config "$_lnba_module" "$_lnba_state"; then
        mv "$_lnba_live" "$_lnba_next" 2>/dev/null || true
        [ ! -d "$_lnba_retired" ] || mv "$_lnba_retired" "$_lnba_live" 2>/dev/null || true
        luoshu_undo_restore_dir "$_lnba_module" "$_lnba_module/config/.font-undo-config-stage.$$" || true
        luoshu_next_boot_restore_selection "$_lnba_module" "$_lnba_previous" "$_lnba_previous_legacy"
        return 1
    fi
    if ! luoshu_next_boot_write_mode "$_lnba_module" "$_lnba_font" "$_lnba_target_mode"; then
        rm -rf "$_lnba_live" 2>/dev/null || true
        [ ! -d "$_lnba_retired" ] || mv "$_lnba_retired" "$_lnba_live" 2>/dev/null || true
        luoshu_undo_restore_dir "$_lnba_module" "$_lnba_module/config/.font-undo-config-stage.$$" || true
        luoshu_next_boot_restore_selection "$_lnba_module" "$_lnba_previous" "$_lnba_previous_legacy"
        luoshu_next_boot_log "runtime mode commit failed; previous payload restored"
        return 1
    fi

    printf '%s\n' "$_lnba_font" > "$_lnba_module/config/active_font.conf" 2>/dev/null || true
    chmod 0644 "$_lnba_module/config/active_font.conf" 2>/dev/null || true

    # A legacy/default/classic payload taking over from Phase 9 must fully leave
    # Universal runtime mode before mount routing continues in this same boot.
    rm -f "$_lnba_module/config/universal-font-runtime.conf" \
          "$_lnba_module/config/universal-font-runtime-verification.conf" \
          "$_lnba_module/config/universal-font-runtime-verification.json" \
          "$_lnba_module/config/universal-font-mount.conf" \
          "$_lnba_module/config/universal-font-next.conf" \
          "$_lnba_module/config/universal-font-activated.conf" \
          "$_lnba_module/config/universal-font-rollback.conf" \
          "$_lnba_module/config/text_reboot_required.conf" 2>/dev/null || true
    {

        printf 'font=%s\n' "$_lnba_font"
        printf 'requestId=%s\n' "$_lnba_request"
        printf 'cjk=%s\nlatin=%s\ndigit=%s\n' "$_lnba_cjk" "$_lnba_latin" "$_lnba_digit"
        printf 'compositeHash=%s\n' "$_lnba_digest"
        printf 'previousFont=%s\n' "$_lnba_previous"
        printf 'previousLegacy=%s\n' "$_lnba_previous_legacy"
        if [ "$_lnba_previous_legacy" = true ]; then printf 'previousMode=legacy\n'
        elif [ -s "$_lnba_module/config/.font-undo-config-stage.$$/universal-font-runtime.conf" ]; then printf 'previousMode=universal\n'
        elif [ "$_lnba_previous" = default ]; then printf 'previousMode=default\n'
        else printf 'previousMode=classic\n'; fi
        printf 'targetMode=%s\n' "$_lnba_target_mode"
        printf 'retired=%s\n' "$_lnba_retired"
        printf 'bootId=%s\n' "$_lnba_boot"
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "${_lnba_activated}.tmp.$$" 2>/dev/null && \
        mv -f "${_lnba_activated}.tmp.$$" "$_lnba_activated" 2>/dev/null || true
    chmod 0644 "$_lnba_activated" 2>/dev/null || true
    luoshu_undo_commit_record "$_lnba_module" "$_lnba_activated" "$_lnba_undo" || {
        luoshu_next_boot_log 'cannot persist explicit undo metadata; retired payload retained'
    }
    rm -f "$_lnba_state" 2>/dev/null || true
    luoshu_next_boot_log "activated payload for $_lnba_font; previous=$_lnba_previous targetMode=$_lnba_target_mode"
    return 0
}
