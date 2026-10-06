#!/system/bin/sh
# Legacy preference commands remain compatible; the boot policy is automatic.
_lmp_module() { printf '%s\n' "${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}"; }
_lmp_value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'; }
_lmp_valid() { case "$1" in auto|meta|self) return 0 ;; *) return 1 ;; esac; }
_lmp_json_string() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/[[:cntrl:]]/ /g'; }
_lmp_boot_id() {
    printf '%s\n' "${LUOSHU_BACKEND_TEST_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"
}
luoshu_mount_preference_get() {
    # Accept older App commands, but all stored preferences migrate to automatic.
    printf 'auto\n'
}
luoshu_mount_preference_write() {
    _lmp_write_preference="$1"
    _lmp_write_module="${2:-$(_lmp_module)}"
    _lmp_valid "$_lmp_write_preference" || return 2
    _lmp_write_preference=auto
    mkdir -p "$_lmp_write_module/config" 2>/dev/null || return 1
    _lmp_write_file="$_lmp_write_module/config/mount-backend-preference.conf"
    _lmp_write_tmp="$_lmp_write_file.tmp.$$"
    (umask 077; printf 'schema=ziyu-mount-preference-v1\npreferred_backend=%s\n' "$_lmp_write_preference" > "$_lmp_write_tmp") || return 1
    mv -f "$_lmp_write_tmp" "$_lmp_write_file" 2>/dev/null || { rm -f "$_lmp_write_tmp"; return 1; }
}
luoshu_mount_preference_restore() {
    # Explicitly migrate even when the font payload itself cannot be migrated.
    [ -f "$1/config/mount-backend-preference.conf" ] || return 0
    _lmp_restore=$(luoshu_mount_preference_get "$1")
    luoshu_mount_preference_write "$_lmp_restore" "$2"
}
luoshu_mount_preference_status() {
    _lmp_status_module=$(_lmp_module)
    _lmp_status_file="$_lmp_status_module/config/mount-backend.conf"
    . "$_lmp_status_module/common/mount_backend_details.sh"
    luoshu_mount_backend_details
    _lmp_preferred=$(luoshu_mount_preference_get)
    _lmp_boot_preference=$(_lmp_value "$_lmp_status_file" preferred_backend)
    _lmp_state_boot=$(_lmp_value "$_lmp_status_file" boot_id)
    _lmp_current_boot=$(_lmp_boot_id)
    _lmp_active=none
    _lmp_selected=none
    _lmp_nomount_observed=false
    _lmp_nomount_usable=false
    _lmp_selected_self=none
    _lmp_pending=true
    if [ -n "$_lmp_current_boot" ] && [ "$_lmp_state_boot" = "$_lmp_current_boot" ]; then
        _lmp_valid "$_lmp_boot_preference" || _lmp_boot_preference=auto
        _lmp_active=$(_lmp_value "$_lmp_status_file" active_backend)
        _lmp_selected=$(_lmp_value "$_lmp_status_file" selected_backend)
        # A matching boot record means detection ran, even when NoMount is unusable.
        _lmp_nomount_observed=true
        [ "$(_lmp_value "$_lmp_status_file" nomount_kernel_usable)" = 1 ] && _lmp_nomount_usable=true
        _lmp_selected_self=$(_lmp_value "$_lmp_status_file" selected_self_backend)
        case "$_lmp_selected_self" in nomount|legacy) ;; *) _lmp_selected_self=none ;; esac
        [ "$_lmp_preferred" != "$_lmp_boot_preference" ] || _lmp_pending=false
    else
        _lmp_boot_preference=auto
    fi
    case "$_lmp_active" in self|meta|external) ;; *) _lmp_active=none ;; esac
    case "$_lmp_selected" in self|meta|external|unresolved|none) ;; *) _lmp_selected=none ;; esac
    _lmp_failure=$(_lmp_value "$_lmp_status_file" preference_failure)
    _lmp_error=$(_lmp_value "$_lmp_status_file" last_error)
    _lmp_provider_state=$(_lmp_value "$_lmp_status_file" provider_state)
    _lmp_provider_id=$(_lmp_value "$_lmp_status_file" provider_id)
    _lmp_provider_layout=$(_lmp_value "$_lmp_status_file" provider_layout)
    _lmp_verification=$(_lmp_value "$_lmp_status_file" verification)
    if [ -z "$_lmp_current_boot" ] || [ "$_lmp_state_boot" != "$_lmp_current_boot" ]; then
        _lmp_provider_state=unknown
        _lmp_provider_id=unknown
        _lmp_provider_layout=unknown
        _lmp_verification=pending
        _lmp_error=none
        _lmp_failure=none
    fi
    [ -n "$_lmp_provider_state" ] || _lmp_provider_state=unknown
    [ -n "$_lmp_provider_id" ] || _lmp_provider_id=unknown
    [ -n "$_lmp_provider_layout" ] || _lmp_provider_layout=unknown
    [ -n "$_lmp_verification" ] || _lmp_verification=pending
    printf '{"ok":true,"policy":"automatic-provider-first","preferredBackend":"%s","bootPreference":"%s","providerState":"%s","providerId":"%s","providerLayout":"%s","plannedBackend":"%s","activeBackend":"%s","verification":"%s","selectedBackend":"%s","selectedSelfBackend":"%s","bootNomountObserved":%s,"bootNomountKernelUsable":%s,"pending":%s,"rebootRequired":%s,"preferenceFailure":"%s","lastError":"%s","providerName":"%s","providerVersion":"%s","mountMethod":"%s","mountMethodEvidence":"%s","rootManager":"%s","rootVersion":"%s"}\n' \
        "$_lmp_preferred" "$_lmp_boot_preference" "$(_lmp_json_string "$_lmp_provider_state")" "$(_lmp_json_string "$_lmp_provider_id")" "$(_lmp_json_string "$_lmp_provider_layout")" "$(_lmp_json_string "$_lmp_selected")" "$_lmp_active" "$(_lmp_json_string "$_lmp_verification")" "$_lmp_selected" "$_lmp_selected_self" "$_lmp_nomount_observed" "$_lmp_nomount_usable" "$_lmp_pending" "$_lmp_pending" \
        "$(_lmp_json_string "${_lmp_failure:-none}")" "$(_lmp_json_string "${_lmp_error:-none}")" \
        "$(_lmp_json_string "$MOUNT_PROVIDER_NAME")" "$(_lmp_json_string "$MOUNT_PROVIDER_VERSION")" "$(_lmp_json_string "$MOUNT_METHOD")" "$(_lmp_json_string "$MOUNT_METHOD_EVIDENCE")" "$(_lmp_json_string "$MOUNT_ROOT_MANAGER")" "$(_lmp_json_string "$MOUNT_ROOT_VERSION")"
}
luoshu_mount_preference_cancel() {
    _lmp_cancel_file="$(_lmp_module)/config/mount-backend.conf"
    _lmp_cancel_boot=$(_lmp_value "$_lmp_cancel_file" boot_id)
    _lmp_cancel_current=$(_lmp_boot_id)
    # Without a current-boot decision, cancel cannot infer a live policy.
    [ -n "$_lmp_cancel_current" ] && [ "$_lmp_cancel_boot" = "$_lmp_cancel_current" ] || return 1
    _lmp_cancel_preference=$(_lmp_value "$_lmp_cancel_file" preferred_backend)
    _lmp_valid "$_lmp_cancel_preference" || _lmp_cancel_preference=auto
    luoshu_mount_preference_write "$_lmp_cancel_preference"
}
if [ "${0##*/}" = mount_backend_preferences.sh ]; then
    case "${1:-get}" in
        get) luoshu_mount_preference_status ;;
        set)
            if ! _lmp_valid "${2:-}"; then
                printf '{"ok":false,"error":"invalid-backend-preference"}\n'; exit 2
            fi
            luoshu_mount_preference_write "$2" || { printf '{"ok":false,"error":"preference-write-failed"}\n'; exit 1; }
            luoshu_mount_preference_status
            ;;
        cancel)
            luoshu_mount_preference_cancel || { printf '{"ok":false,"error":"no-current-boot-preference"}\n'; exit 1; }
            luoshu_mount_preference_status
            ;;
        *) printf '{"ok":false,"error":"unknown-command"}\n'; exit 2 ;;
    esac
fi
