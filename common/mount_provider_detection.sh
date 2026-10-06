#!/system/bin/sh
# Classify only providers whose standard module scan path is known. This file
# consumes root_manager_detection.sh and meta_mount_detection.sh results; it
# never executes nm, mount, or manager control commands.
set +e

ziyu_mount_provider_detect() {
    _zpd_module="${1:-${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}}"
    PROVIDER_STATE=unknown
    PROVIDER_ID=unknown
    PROVIDER_LAYOUT=unknown
    PROVIDER_CONTENT_ROOT="$_zpd_module"
    PROVIDER_REASON=not-evaluated

    if [ "${LUOSHU_PROVIDER_TEST_MODE:-0}" = 1 ]; then
        PROVIDER_STATE="${LUOSHU_PROVIDER_TEST_STATE:-unknown}"
        PROVIDER_ID="${LUOSHU_PROVIDER_TEST_ID:-test-provider}"
        PROVIDER_LAYOUT="${LUOSHU_PROVIDER_TEST_LAYOUT:-nested-system}"
        PROVIDER_REASON="${LUOSHU_PROVIDER_TEST_REASON:-test}"
        case "$PROVIDER_STATE" in available|absent|unknown|excluded) return 0 ;; *) PROVIDER_STATE=unknown; PROVIDER_REASON=invalid-test-state; return 0 ;; esac
    fi

    case "${ROOT_MANAGER:-unknown}:${ROOT_DETECTION_SOURCE:-unknown}" in
        unknown:*|*ambiguous*|*'|ambiguous'*)
            PROVIDER_REASON="root-identity-${ROOT_DETECTION_SOURCE:-unknown}"
            return 0
            ;;
    esac

    # A declared selector with unreadable/unknown metadata is not an absence.
    _zpd_selector="${LUOSHU_META_DETECT_ROOT:-/data/adb}/metamodule"
    if [ -z "${META_ACTIVE_DIR:-}" ] && \
       { [ -e "$_zpd_selector" ] || [ -L "$_zpd_selector" ] || [ -n "${LUOSHU_META_TEST_ACTIVE_DIR:-}" ]; }; then
        PROVIDER_REASON=active-meta-identity-unavailable
        return 0
    fi

    # A confirmed active metamodule takes priority even when the root manager
    # also supplies native mounting. META_READY means its boot hook can scan a
    # standard tree; META_USABLE remains the legacy self-failover capability.
    if [ "${META_ENGINE:-none}" != none ] && [ "${META_ENABLED:-0}" = 1 ]; then
        case "${META_USABLE_REASON:-}" in
            *module-excluded*) PROVIDER_STATE=excluded; PROVIDER_REASON=module-excluded; return 0 ;;
        esac
        if [ -z "${META_ACTIVE_DIR:-}" ]; then
            if [ "${ROOT_MANAGER:-}" != Magisk ] || [ "${META_ENGINE:-}" != mountify ]; then
                PROVIDER_REASON=meta-active-identity-unavailable
                return 0
            fi
        fi
        if [ "${META_READY:-0}" != 1 ]; then
            PROVIDER_REASON="meta-not-ready-${META_USABLE_REASON:-unknown}"
            return 0
        fi
        PROVIDER_STATE=available
        PROVIDER_ID="${META_ENGINE:-meta}"
        case "$META_ENGINE" in
            nomount) PROVIDER_LAYOUT=nomount-partition-roots ;;
            hybrid-mount|meta-overlayfs|mountify|magic-mount) PROVIDER_LAYOUT=nested-system ;;
            *) PROVIDER_STATE=unknown; PROVIDER_LAYOUT=unknown; PROVIDER_REASON=unsupported-meta-engine; return 0 ;;
        esac
        if [ "$META_ENGINE" = meta-overlayfs ]; then
            _zpd_content_base="${MODULE_CONTENT_DIR:-${META_ACTIVE_DIR}/mnt}"
            _zpd_module_id=$(sed -n 's/^id=//p' "$_zpd_module/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n')
            [ -n "$_zpd_module_id" ] || _zpd_module_id="${_zpd_module##*/}"
            case "$_zpd_module_id" in ''|*[!A-Za-z0-9_.-]*|.|..) PROVIDER_STATE=unknown; PROVIDER_REASON=invalid-module-id; return 0 ;; esac
            case "$_zpd_content_base" in
                */"$_zpd_module_id") PROVIDER_CONTENT_ROOT="$_zpd_content_base" ;;
                *) PROVIDER_CONTENT_ROOT="$_zpd_content_base/$_zpd_module_id" ;;
            esac
            # The provider owns mounting its content image; do not create a
            # substitute directory when that image is unavailable in this hook.
            if [ ! -d "$_zpd_content_base" ] || [ ! -w "$_zpd_content_base" ]; then
                PROVIDER_STATE=unknown
                PROVIDER_REASON=provider-content-root-unavailable
                return 0
            fi
            _zpd_image_root="$_zpd_content_base"
            case "$_zpd_image_root" in */"$_zpd_module_id") _zpd_image_root="${_zpd_image_root%/*}" ;; esac
            if ! type luoshu_mountpoint_ready >/dev/null 2>&1 || ! luoshu_mountpoint_ready "$_zpd_image_root"; then
                PROVIDER_STATE=unknown
                PROVIDER_REASON=provider-content-image-not-mounted
                return 0
            fi
        fi
        PROVIDER_REASON=active-meta-standard-tree
        return 0
    fi

    case "${ROOT_MANAGER:-unknown}" in
        Magisk)
            PROVIDER_STATE=available
            PROVIDER_ID=magisk-magic-mount
            PROVIDER_LAYOUT=nested-system
            PROVIDER_REASON=magisk-native-module-mount
            ;;
        APatch)
            PROVIDER_STATE=available
            PROVIDER_ID=apatch-native-module-mount
            PROVIDER_LAYOUT=nested-system
            PROVIDER_REASON=apatch-native-module-mount
            ;;
        KernelSU|SukiSU\ Ultra)
            if [ "${META_INSTALLED:-0}" = 1 ] && [ "${META_ENABLED:-0}" = 1 ]; then
                PROVIDER_REASON=enabled-meta-without-active-proof
            else
                PROVIDER_STATE=absent
                PROVIDER_ID=none
                PROVIDER_LAYOUT=none
                PROVIDER_REASON=no-active-meta-provider
            fi
            ;;
        *) PROVIDER_REASON=unsupported-or-unknown-root-manager ;;
    esac
    return 0
}
