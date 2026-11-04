#!/system/bin/sh
# FinaSU/Folk Mount contract pinned in docs/FINASU_FOLK_MOUNT_v1.4.95.md.
# Read manager-owned files only. Never invoke set-mode, inject or global cleanup.
set +e

_ziyu_fina_layout_supported() {
    _zfm_payload="$_zfm_module/.ziyu-payload"
    [ -d "$_zfm_payload" ] || _zfm_payload="$_zfm_module"
    for _zfm_part in $(ziyu_private_partitions); do
        case "$_zfm_provider:$_zfm_part" in
            *:system|*:vendor|*:system_ext|*:product|*:odm|*:oem) continue ;;
            nomount:apex|nomount:optics|nomount:prism|nomount:mi_ext|nomount:my_*) continue ;;
        esac
        [ -d "$_zfm_payload/$_zfm_part" ] || continue
        # No font hashes or tree copies: only locate one unsupported file.
        _zfm_entry=$(find "$_zfm_payload/$_zfm_part" -type f -print -quit 2>/dev/null)
        if [ "$?" -ne 0 ]; then
            PROVIDER_STATE=unknown
            PROVIDER_REASON="finasu-partition-content-unreadable:$_zfm_part"
            return 1
        fi
        if [ -n "$_zfm_entry" ]; then
            PROVIDER_STATE=unknown
            PROVIDER_REASON="finasu-${_zfm_provider}-partition-unsupported:$_zfm_part"
            return 1
        fi
    done
    return 0
}

ziyu_finasu_provider_detect() {
    _zfm_module="$1"
    _zfm_base="$(_ziyu_root_path /data/adb/ksu)"
    _zfm_mode=auto
    if [ -e "$_zfm_base/mount_mode" ]; then
        if [ ! -r "$_zfm_base/mount_mode" ]; then
            if [ "${_lbr_stage:-}" = post-fs-data ]; then
                PROVIDER_STATE=unknown; PROVIDER_REASON=finasu-mode-unreadable; return 0
            fi
            _zfm_mode=unknown
        else
            _zfm_mode=$(sed 's/^[[:space:]]*//;s/[[:space:]]*$//' "$_zfm_base/mount_mode" 2>/dev/null)
            # FinaSU itself treats an unrecognised mode as auto.
            case "$_zfm_mode" in auto|builtin|metamodule|nomount|off) ;; *) _zfm_mode=auto ;; esac
        fi
    fi
    _zfm_provider=''; _zfm_result=prepared; _zfm_warning=''
    if [ "${_lbr_stage:-}" = post-fs-data ]; then
        # Hooks run before the manager scans modules. Ignore even a same-boot
        # receipt here: a managed userspace restart can preserve boot_id.
        case "$_zfm_mode" in
            builtin) _zfm_provider=builtin ;;
            nomount)
                _zfm_meta=$(_ziyu_meta_active_dir)
                # Mirror the manager's documented external-driver ownership
                # check. Publish the external engine's layout from the outset.
                if [ -n "$_zfm_meta" ] && [ ! -e "$_zfm_meta/disable" ] &&
                   { [ "${_zfm_meta##*/}" = nomount ] || [ -e "$_zfm_meta/bin/nm" ] ||
                     [ -e "$_zfm_meta/lkm/nomount.ko" ] || [ -e "$_zfm_meta/nomount.ko" ]; }; then return 1; fi
                _zfm_provider=nomount
                ;;
            off) _zfm_provider=none ;;
            metamodule) return 1 ;;
            auto)
                # Unknown third-party engines can still be active in FinaSU.
                # Use the selector before the generic detector filters its type.
                _zfm_meta=$(_ziyu_meta_active_dir)
                if [ -n "$_zfm_meta" ] && _ziyu_meta_enabled "$_zfm_meta"; then return 1; fi
                _zfm_provider=builtin
                ;;
        esac
    else
        _zfm_record="$_zfm_base/mount_runtime"
        _zfm_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
        _zfm_record_boot=''; _zfm_result=''; _zfm_error=''
        # One open gives a consistent snapshot across the manager's atomic
        # receipt replacement and avoids one subprocess pipeline per field.
        if [ -r "$_zfm_record" ]; then
            while IFS='=' read -r _zfm_key _zfm_value || [ -n "$_zfm_key" ]; do
                case "$_zfm_key" in
                    boot_id) _zfm_record_boot=$_zfm_value ;;
                    provider) _zfm_provider=$_zfm_value ;;
                    result) _zfm_result=$_zfm_value ;;
                    warning_code) _zfm_warning=$_zfm_value ;;
                    error) _zfm_error=$_zfm_value ;;
                esac
            done < "$_zfm_record"
        fi
        if [ -z "$_zfm_boot" ] || [ "$_zfm_record_boot" != "$_zfm_boot" ]; then
            PROVIDER_STATE=unknown; PROVIDER_REASON=finasu-current-boot-record-unavailable; return 0
        fi
        case "$_zfm_warning" in *[!A-Za-z0-9_.-]*) _zfm_warning=unrecognized ;; esac
        case "$_zfm_result" in
            mounted|noop) ;;
            *)
                PROVIDER_STATE=unknown
                case "$_zfm_result" in disabled|skipped) PROVIDER_STATE=excluded ;; esac
                case "$_zfm_result" in failed|partial|disabled|skipped|mounting) ;; *) _zfm_result=unknown ;; esac
                PROVIDER_REASON="finasu-runtime-$_zfm_result"
                if [ -n "$_zfm_error" ]; then
                    _zfm_error=$(printf '%s' "$_zfm_error" | tr '\r\n|' '   ' | cut -c1-512)
                    PROVIDER_REASON="$PROVIDER_REASON:$_zfm_error"
                fi
                return 0
                ;;
        esac
        [ "$_zfm_provider" != metamodule ] || return 1
    fi
    case "$_zfm_provider" in
        builtin) PROVIDER_ID=finasu-folk-mount ;;
        nomount) PROVIDER_ID=finasu-nomount ;;
        none)
            PROVIDER_STATE=excluded; PROVIDER_ID=none; PROVIDER_LAYOUT=none
            PROVIDER_REASON=finasu-mount-disabled; return 0
            ;;
        *) PROVIDER_STATE=unknown; PROVIDER_REASON=finasu-provider-unconfirmed; return 0 ;;
    esac
    _ziyu_fina_layout_supported || return 0
    PROVIDER_STATE=available
    # Both engines consume the standard nested system tree. The existing
    # publisher creates real directories and hard-linked regular font files.
    PROVIDER_LAYOUT=nested-system
    PROVIDER_CONTENT_ROOT="$_zfm_module"
    PROVIDER_REASON="finasu-${_zfm_provider}-${_zfm_result}:configured=${_zfm_mode}:warning=${_zfm_warning:-none}"
    return 0
}
