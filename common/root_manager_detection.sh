#!/system/bin/sh
# Shared Root-manager identity detection for boot hooks, installer checks and status output.
set +e

_luoshu_root_truthy() {
    case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|on|enabled) return 0 ;;
        *) return 1 ;;
    esac
}

_luoshu_root_declared() {
    case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
        ''|0|false|no|off|disabled) return 1 ;;
        *) return 0 ;;
    esac
}

_luoshu_root_path() {
    _lrd_path="$1"
    if [ -n "${LUOSHU_ROOT_DETECT_ROOT:-}" ]; then
        printf '%s%s\n' "${LUOSHU_ROOT_DETECT_ROOT%/}" "$_lrd_path"
    else
        printf '%s\n' "$_lrd_path"
    fi
}

_luoshu_root_value() {
    _lrd_value=$(printf '%s' "${1:-}" | tr '\r\n|' '   ')
    [ -n "$_lrd_value" ] || _lrd_value=unknown
    printf '%s\n' "$_lrd_value"
}

# App su shells often omit the manager environment present in boot hooks. Only
# reuse an explicit hook identity from this boot, never an old directory guess.
luoshu_current_boot_backend_state() {
    _lrd_state="${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}/config/mount-backend.conf"
    [ -r "$_lrd_state" ] || return 1
    _lrd_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    [ -n "$_lrd_boot" ] || return 1
    [ "$(sed -n 's/^schema=//p' "$_lrd_state" | head -n1)" = ziyu-mount-backend-v1 ] || return 1
    [ "$(sed -n 's/^boot_id=//p' "$_lrd_state" | head -n1 | tr -d '\r\n')" = "$_lrd_boot" ] || return 1
    printf '%s\n' "$_lrd_state"
}

_luoshu_root_boot_identity() {
    _lrd_state=$(luoshu_current_boot_backend_state) || return 1
    case "$(sed -n 's/^root_detection_source=//p' "$_lrd_state" | head -n1)" in env|current-boot-hook) ;; *) return 1 ;; esac
    _lrd_manager=$(sed -n 's/^root_manager=//p' "$_lrd_state" | head -n1)
    case "$_lrd_manager" in Magisk|KernelSU|SukiSU\ Ultra|APatch) ;; *) return 1 ;; esac
    ROOT_MANAGER=$_lrd_manager
    ROOT_VERSION=$(_luoshu_root_value "$(sed -n 's/^root_version=//p' "$_lrd_state" | head -n1)")
    ROOT_VERSION_CODE=$(_luoshu_root_value "$(sed -n 's/^root_version_code=//p' "$_lrd_state" | head -n1)")
    ROOT_DETECTION_SOURCE=current-boot-hook
    return 0
}

luoshu_root_manager_detect() {
    ROOT_MANAGER=unknown
    ROOT_VERSION=unknown
    ROOT_VERSION_CODE=0
    ROOT_DETECTION_SOURCE=unknown

    # Prefer manager-specific environment identity. SukiSU inherits the KSU
    # environment and shares /data/adb/ksu, so its explicit identity must win.
    if _luoshu_root_truthy "${KSU_SUKISU:-}" || \
       _luoshu_root_declared "${SUKISU:-}" || [ -n "${SUKISU_VER:-}${SUKISU_VER_CODE:-}" ]; then
        ROOT_MANAGER='SukiSU Ultra'
        ROOT_VERSION=$(_luoshu_root_value "${SUKISU_VER:-${KSU_VER:-unknown}}")
        ROOT_VERSION_CODE=$(_luoshu_root_value "${SUKISU_VER_CODE:-${KSU_VER_CODE:-${KSU_KERNEL_VER_CODE:-0}}}")
        ROOT_DETECTION_SOURCE=env
    elif _luoshu_root_declared "${APATCH:-}" || [ -n "${APATCH_VER:-}${APATCH_VER_CODE:-}" ]; then
        ROOT_MANAGER=APatch
        ROOT_VERSION=$(_luoshu_root_value "${APATCH_VER:-unknown}")
        ROOT_VERSION_CODE=$(_luoshu_root_value "${APATCH_VER_CODE:-0}")
        ROOT_DETECTION_SOURCE=env
    elif _luoshu_root_truthy "${KSU:-}" || [ -n "${KSU_VER_CODE:-}${KSU_KERNEL_VER_CODE:-}" ]; then
        ROOT_MANAGER=KernelSU
        ROOT_VERSION=$(_luoshu_root_value "${KSU_VER:-unknown}")
        ROOT_VERSION_CODE=$(_luoshu_root_value "${KSU_VER_CODE:-${KSU_KERNEL_VER_CODE:-0}}")
        ROOT_DETECTION_SOURCE=env
    elif [ -n "${MAGISK_VER_CODE:-}${MAGISK_VER:-}" ]; then
        ROOT_MANAGER=Magisk
        ROOT_VERSION=$(_luoshu_root_value "${MAGISK_VER:-unknown}")
        ROOT_VERSION_CODE=$(_luoshu_root_value "${MAGISK_VER_CODE:-0}")
        ROOT_DETECTION_SOURCE=env
    elif _luoshu_root_boot_identity; then
        :
    else
        _lrd_ap=$(_luoshu_root_path /data/adb/ap)
        _lrd_apatch=$(_luoshu_root_path /data/adb/apatch)
        _lrd_ksu=$(_luoshu_root_path /data/adb/ksu)
        _lrd_magisk=$(_luoshu_root_path /data/adb/magisk)
        _lrd_ap_present=0; _lrd_ksu_present=0; _lrd_magisk_present=0
        [ -d "$_lrd_ap" ] || [ -d "$_lrd_apatch" ] || command -v apd >/dev/null 2>&1
        [ "$?" = 0 ] && _lrd_ap_present=1
        [ -d "$_lrd_ksu" ] || command -v ksud >/dev/null 2>&1 || [ -x "$(_luoshu_root_path /data/adb/ksud)" ]
        [ "$?" = 0 ] && _lrd_ksu_present=1
        [ -d "$_lrd_magisk" ] || command -v magisk >/dev/null 2>&1
        [ "$?" = 0 ] && _lrd_magisk_present=1
        if [ "$((_lrd_ap_present + _lrd_ksu_present + _lrd_magisk_present))" -gt 1 ]; then
            ROOT_DETECTION_SOURCE=ambiguous-root-installations
        elif [ "$_lrd_ap_present" = 1 ]; then
            ROOT_MANAGER=APatch
            ROOT_DETECTION_SOURCE=fallback
        elif [ "$_lrd_magisk_present" = 1 ]; then
            ROOT_MANAGER=Magisk
            ROOT_DETECTION_SOURCE=fallback
        elif [ -d "$_lrd_ksu" ]; then
            # KernelSU and SukiSU share /data/adb/ksu and ksud. Without a
            # manager-specific identity, preserve uncertainty.
            ROOT_MANAGER=unknown
            ROOT_DETECTION_SOURCE=ambiguous-ksu-family
        elif command -v ksud >/dev/null 2>&1 || [ -x "$(_luoshu_root_path /data/adb/ksud)" ]; then
            ROOT_MANAGER=unknown
            ROOT_DETECTION_SOURCE=ambiguous-ksu-daemon
        fi
    fi

    case "$ROOT_MANAGER" in
        Magisk|KernelSU|SukiSU\ Ultra|APatch) ;;
        *) ROOT_VERSION=unknown; ROOT_VERSION_CODE=0 ;;
    esac

    printf '%s|%s|%s|%s\n' "$ROOT_MANAGER" "$ROOT_VERSION" "$ROOT_VERSION_CODE" "$ROOT_DETECTION_SOURCE"
}

luoshu_detect_root_manager() {
    _lrd_record=$(luoshu_root_manager_detect)
    IFS='|' read -r ROOT_MANAGER ROOT_VERSION ROOT_VERSION_CODE ROOT_DETECTION_SOURCE <<EOF_ROOT_ID
$_lrd_record
EOF_ROOT_ID
    printf '%s\n' "$ROOT_MANAGER"
}

luoshu_root_manager_info() {
    luoshu_detect_root_manager >/dev/null
    printf 'ROOT_MANAGER=%s\n' "$ROOT_MANAGER"
    printf 'ROOT_VERSION=%s\n' "$ROOT_VERSION"
    printf 'ROOT_VERSION_CODE=%s\n' "$ROOT_VERSION_CODE"
    printf 'ROOT_DETECTION_SOURCE=%s\n' "$ROOT_DETECTION_SOURCE"
}
