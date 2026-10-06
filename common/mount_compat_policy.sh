#!/system/bin/sh
# Final policy overrides for LuoShu v2.2.7 mount compatibility.
# Direct-source engines are migration-friendly and non-invasive; real dual-directory
# metamodules retain strict marker, mountpoint and supported-partition validation.
set +e

# Keep every v2.2.7 probe injection contract working. New self-mount tests use
# LUOSHU_SELF_MOUNT_VISIBLE_ROOT, while the existing runtime/tests use the older
# LUOSHU_VISIBLE_PROBE_ROOT or a direct LUOSHU_VISIBLE_PROBE file.
_luoshu_self_visible_root() {
    printf '%s\n' "${LUOSHU_SELF_MOUNT_VISIBLE_ROOT:-${LUOSHU_VISIBLE_PROBE_ROOT:-}}"
}

_luoshu_visible_path() {
    _lmcvp_path="$1"
    if [ "$_lmcvp_path" = /system/etc/luoshu/mount-probe.conf ] && \
       [ -n "${LUOSHU_VISIBLE_PROBE:-}" ]; then
        printf '%s\n' "$LUOSHU_VISIBLE_PROBE"
        return 0
    fi
    _lmcvp_root=$(_luoshu_self_visible_root)
    if [ -n "$_lmcvp_root" ]; then
        printf '%s%s\n' "${_lmcvp_root%/}" "$_lmcvp_path"
    else
        printf '%s\n' "$_lmcvp_path"
    fi
}

# Hybrid Mount v4 uses default_mode globally and per module. The v2.2.7 base
# parser only knew older backend/mode keys, so keep reporting and diagnostics in
# sync with current Full/Lite/Nano configuration without editing Hybrid's files.
_luoshu_hybrid_mode_from_file() {
    _lhmff_file="$1"
    [ -f "$_lhmff_file" ] || return 1
    awk '
        function clean(v) {
            sub(/[[:space:]]*#.*/, "", v)
            sub(/^[^=]*=[[:space:]]*/, "", v)
            gsub(/["\047[:space:]]/, "", v)
            return tolower(v)
        }
        /^[[:space:]]*\[/ {
            section=$0
            sub(/[[:space:]]*#.*/, "", section)
            gsub(/["\047[:space:]]/, "", section)
            section=tolower(section)
            in_luoshu=(section=="[rules.luoshu]")
            next
        }
        /^[[:space:]]*default_mode[[:space:]]*=/ {
            value=clean($0)
            if (in_luoshu) module_value=value
            else if (global_value=="") global_value=value
        }
        END {
            if (module_value!="") print module_value
            else if (global_value!="") print global_value
        }
    ' "$_lhmff_file" 2>/dev/null | tail -n1
}

luoshu_hybrid_backend() {
    if [ -n "${LUOSHU_META_TEST_BACKEND:-}" ]; then
        printf '%s\n' "$LUOSHU_META_TEST_BACKEND"
        return 0
    fi

    for _lhb_file in \
        /data/adb/hybrid-mount/config.toml \
        /data/adb/metamodule/config.toml \
        /data/adb/modules/hybrid_mount/config.toml \
        /data/adb/modules/meta-hybrid_mount/config.toml \
        /data/adb/modules/hybrid-mount/config.toml; do
        _lhb_value=$(_luoshu_hybrid_mode_from_file "$_lhb_file")
        case "$_lhb_value" in
            overlay|overlayfs) printf 'overlayfs\n'; return 0 ;;
            magic|magic-mount|magic_mount) printf 'magic-mount\n'; return 0 ;;
            kasumi) printf 'kasumi\n'; return 0 ;;
        esac
    done
    printf 'unknown\n'
}

luoshu_mount_preflight() {
    LUOSHU_MOUNT_PREFLIGHT_ERROR=''
    for _lmcp_marker in disable remove; do
        if [ -e "$LUOSHU_MOUNT_MODDIR/$_lmcp_marker" ]; then
            LUOSHU_MOUNT_PREFLIGHT_ERROR="字域存在 $_lmcp_marker 标记"
            return 1
        fi
    done
    [ ! -f "$LUOSHU_MOUNT_MODDIR/common/skip_mount_ownership.sh" ] || . "$LUOSHU_MOUNT_MODDIR/common/skip_mount_ownership.sh"
    if type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$LUOSHU_MOUNT_MODDIR"; then
        LUOSHU_MOUNT_PREFLIGHT_ERROR='字域被手动排除挂载'
        return 1
    fi
    return 0
}
