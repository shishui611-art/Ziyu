#!/system/bin/sh
# One boot-scoped selector for Root identity, Meta Mount, self-mount and commit.
set +e

MODDIR="${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}"
MODULE_DIR="$MODDIR"
_lbr_state="$MODDIR/config/mount-backend.conf"
_lbr_log="$MODDIR/logs/mount-backend.log"
_lbr_boot="${LUOSHU_BACKEND_TEST_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"
[ -n "$_lbr_boot" ] || _lbr_boot="$(date +%s 2>/dev/null || echo 0)-$$"

luoshu_backend_load_dependencies() {
    _lbr_preference_helper="$MODDIR/common/mount_backend_preferences.sh"
    [ -f "$_lbr_preference_helper" ] || _lbr_preference_helper="${BASH_SOURCE:-$0}"
    case "$_lbr_preference_helper" in */mount_backend_runtime.sh) _lbr_preference_helper="${_lbr_preference_helper%/*}/mount_backend_preferences.sh" ;; esac
    [ -f "$_lbr_preference_helper" ] && . "$_lbr_preference_helper"
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        [ -f "$MODDIR/common/private_payload.sh" ] && . "$MODDIR/common/private_payload.sh"
        return 0
    fi
    [ -f "$MODDIR/common/root_manager_detection.sh" ] && . "$MODDIR/common/root_manager_detection.sh"
    [ -f "$MODDIR/common/private_payload.sh" ] && . "$MODDIR/common/private_payload.sh"
    [ -f "$MODDIR/common/util_functions.sh" ] && . "$MODDIR/common/util_functions.sh"
    [ -f "$MODDIR/common/font_config_runtime.sh" ] && . "$MODDIR/common/font_config_runtime.sh"
    [ -f "$MODDIR/common/font_config_partitions.sh" ] && . "$MODDIR/common/font_config_partitions.sh"
    [ -f "$MODDIR/common/mount_compat.sh" ] && . "$MODDIR/common/mount_compat.sh"
    [ -f "$MODDIR/common/mount_self_backend.sh" ] && . "$MODDIR/common/mount_self_backend.sh"
}

_lbr_log() {
    mkdir -p "$MODDIR/logs" 2>/dev/null || true
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" >> "$_lbr_log" 2>/dev/null || true
}

_lbr_value() {
    sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'
}

_lbr_active_font() {
    _lbr_active=$(head -n1 "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_lbr_active" ] || _lbr_active=default
    printf '%s\n' "$_lbr_active"
}

_lbr_write_state() {
    _lbr_selected="$1"
    _lbr_active="$2"
    _lbr_fallback="$3"
    _lbr_meta_result="$4"
    _lbr_self_result="$5"
    _lbr_stage="$6"
    _lbr_verify="$7"
    _lbr_error="$8"
    mkdir -p "$MODDIR/config" "$MODDIR/logs" 2>/dev/null || return 1
    _lbr_tmp="${_lbr_state}.tmp.$$"
    {
        printf 'schema=ziyu-mount-backend-v1\n'
        printf 'boot_id=%s\n' "$_lbr_boot"
        printf 'root_manager=%s\n' "${ROOT_MANAGER:-unknown}"
        printf 'root_version=%s\n' "${ROOT_VERSION:-unknown}"
        printf 'root_version_code=%s\n' "${ROOT_VERSION_CODE:-0}"
        printf 'root_detection_source=%s\n' "${ROOT_DETECTION_SOURCE:-unknown}"
        printf 'meta_engine=%s\n' "${META_ENGINE:-none}"
        printf 'meta_available=%s\n' "${META_AVAILABLE:-0}"
        printf 'meta_enabled=%s\n' "${META_ENABLED:-0}"
        printf 'meta_usable=%s\n' "${META_USABLE:-0}"
        printf 'meta_ready=%s\n' "${META_READY:-0}"
        printf 'meta_usable_reason=%s\n' "${META_USABLE_REASON:-unknown}"
        printf 'meta_cleanup_capability=%s\n' "${META_CLEANUP_CAPABILITY:-none}"
        printf 'preferred_backend=%s\n' "${_lbr_preference:-auto}"
        printf 'preference_failure=%s\n' "${_lbr_preference_failure:-none}"
        printf 'selected_backend=%s\n' "$_lbr_selected"
        printf 'active_backend=%s\n' "$_lbr_active"
        printf 'backend_conflict=%s\n' "${BACKEND_CONFLICT:-0}"
        printf 'fallback_used=%s\n' "$_lbr_fallback"
        printf 'meta_result=%s\n' "$_lbr_meta_result"
        printf 'self_result=%s\n' "$_lbr_self_result"
        printf 'mount_stage=%s\n' "$_lbr_stage"
        printf 'verification=%s\n' "$_lbr_verify"
        printf 'last_error=%s\n' "${_lbr_error:-none}"
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$_lbr_tmp" 2>/dev/null || return 1
    chmod 0600 "$_lbr_tmp" 2>/dev/null || true
    mv -f "$_lbr_tmp" "$_lbr_state" 2>/dev/null || return 1
}

_lbr_mountinfo() {
    printf '%s\n' "${LUOSHU_FONT_VERIFY_MOUNTINFO:-/proc/1/mountinfo}"
}

_lbr_mountinfo_readable() {
    # procfs reports st_size=0 even for a readable, populated mountinfo file.
    # A disk-file size check would disable the PID 1 safety baseline on Android.
    [ -r "$1" ] || return 1
    awk 'NF >= 6 { seen=1; exit } END { exit !seen }' "$1" 2>/dev/null
}

_lbr_snapshot_mountinfo() {
    _lbr_before="$MODDIR/config/.mount-backend-before.$_lbr_boot"
    _lbr_info=$(_lbr_mountinfo)
    _lbr_mountinfo_readable "$_lbr_info" || return 1
    cp -f "$_lbr_info" "$_lbr_before" 2>/dev/null || return 1
    chmod 0600 "$_lbr_before" 2>/dev/null || true
}

_lbr_meta_mount_delta() {
    [ "${LUOSHU_BACKEND_TEST_MOUNT_DELTA:-}" != '' ] && \
        [ "${LUOSHU_BACKEND_TEST_MOUNT_DELTA:-0}" = 1 ] && return 0
    [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ] && return 1
    _lbr_before="$MODDIR/config/.mount-backend-before.$_lbr_boot"
    _lbr_now=$(_lbr_mountinfo)
    # Unknown mount state is unsafe, just like a detected delta: never layer
    # self-mount over Meta when the PID 1 baseline cannot be compared.
    _lbr_mountinfo_readable "$_lbr_before" && _lbr_mountinfo_readable "$_lbr_now" || return 0
    awk '
        function relevant(path) {
            return path=="/system" || path ~ /^\/system\// || path=="/system_ext" || path ~ /^\/system_ext\// ||
                   path=="/product" || path ~ /^\/product\// || path=="/vendor" || path ~ /^\/vendor\// ||
                   path=="/odm" || path ~ /^\/odm\// || path=="/oem" || path ~ /^\/oem\// ||
                   path=="/my_product" || path ~ /^\/my_product\// || path=="/oplus" || path ~ /^\/oplus\//
        }
        NR==FNR { old[$5]=$0; next }
        { if (relevant($5) && old[$5]!=$0) changed=1; seen[$5]=1 }
        END { for (path in old) if (relevant(path) && !seen[path]) changed=1; exit !changed }
    ' "$_lbr_before" "$_lbr_now" 2>/dev/null
}

_lbr_stage_for_root() {
    case "$1" in
        Magisk) printf 'post-fs-data\n' ;;
        KernelSU|SukiSU\ Ultra|APatch) printf 'post-mount\n' ;;
        *) printf 'none\n' ;;
    esac
}

_lbr_detect() {
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        ROOT_MANAGER="${LUOSHU_BACKEND_TEST_MANAGER:-unknown}"
        ROOT_VERSION="${LUOSHU_BACKEND_TEST_ROOT_VERSION:-test}"
        ROOT_VERSION_CODE="${LUOSHU_BACKEND_TEST_ROOT_VERSION_CODE:-0}"
        ROOT_DETECTION_SOURCE=test
        META_ENGINE="${LUOSHU_BACKEND_TEST_META_ENGINE:-none}"
        META_AVAILABLE="${LUOSHU_BACKEND_TEST_META_AVAILABLE:-${LUOSHU_BACKEND_TEST_META_USABLE:-0}}"
        META_ENABLED="${LUOSHU_BACKEND_TEST_META_ENABLED:-${LUOSHU_BACKEND_TEST_META_USABLE:-0}}"
        META_USABLE="${LUOSHU_BACKEND_TEST_META_USABLE:-0}"
        META_READY="${LUOSHU_BACKEND_TEST_META_READY:-$META_USABLE}"
        META_USABLE_REASON="${LUOSHU_BACKEND_TEST_META_REASON:-test}"
        META_CLEANUP_CAPABILITY="${LUOSHU_BACKEND_TEST_CLEANUP_CAPABILITY:-none}"
        if [ "$META_CLEANUP_CAPABILITY" = none ] && [ "$META_ENGINE" = hybrid-mount ] && [ "$META_USABLE" = 1 ]; then
            META_CLEANUP_CAPABILITY=hybrid-vfs
        fi
        return 0
    fi
    type luoshu_detect_root_manager >/dev/null 2>&1 || return 1
    luoshu_detect_root_manager >/dev/null 2>&1
    type luoshu_meta_mount_detect >/dev/null 2>&1 || return 1
    luoshu_meta_mount_detect >/dev/null 2>&1
    return 0
}

_lbr_is_universal() {
    [ -s "$MODDIR/config/universal-font-runtime.conf" ]
}

_lbr_meta_payload_compatible() {
    _lbr_is_universal || return 0
    _lbr_dynamic="$MODDIR/.luoshu-payload/.luoshu-runtime/deployment/dynamic-mounts.conf"
    [ ! -s "$_lbr_dynamic" ]
}

_lbr_set_self_skip() {
    mkdir -p "$MODDIR/config" 2>/dev/null || return 1
    : > "$MODDIR/skip_mount" 2>/dev/null || return 1
    : > "$MODDIR/skip_mountify" 2>/dev/null || return 1
    : > "$MODDIR/config/self-mount-owned" 2>/dev/null || return 1
    return 0
}

_lbr_clear_owned_skip() {
    [ -f "$MODDIR/config/self-mount-owned" ] || return 0
    rm -f "$MODDIR/skip_mount" "$MODDIR/skip_mountify" || return 1
    rm -f "$MODDIR/config/self-mount-owned"
}

_lbr_meta_snapshot() {
    [ "${META_ENGINE:-}" = meta-overlayfs ] || return 0
    type luoshu_meta_content_roots >/dev/null 2>&1 || return 1
    _lbr_root=$(luoshu_meta_content_roots | head -n1)
    [ -n "$_lbr_root" ] || return 1
    _lbr_backup="$MODDIR/.luoshu-runtime/meta-backup/$_lbr_boot"
    rm -rf "$_lbr_backup" 2>/dev/null || true
    mkdir -p "$_lbr_backup" || return 1
    : > "$_lbr_backup/partitions" || return 1
    for _lbr_part in $(luoshu_used_partitions 2>/dev/null); do
        _lbr_dest="$_lbr_root/$_lbr_part"
        if [ -e "$_lbr_dest" ]; then
            mkdir -p "$_lbr_backup/tree" || return 1
            cp -al "$_lbr_dest" "$_lbr_backup/tree/$_lbr_part" 2>/dev/null || {
                rm -rf "$_lbr_backup"
                return 1
            }
            printf '%s|present\n' "$_lbr_part" >> "$_lbr_backup/partitions"
        else
            printf '%s|absent\n' "$_lbr_part" >> "$_lbr_backup/partitions"
        fi
    done
    printf '%s\n' "$_lbr_root" > "$_lbr_backup/root"
    printf '%s\n' "$_lbr_backup"
}

_lbr_restore_meta_snapshot() {
    _lbr_backup="$MODDIR/.luoshu-runtime/meta-backup/$_lbr_boot"
    [ -s "$_lbr_backup/partitions" ] || return 0
    _lbr_root=$(cat "$_lbr_backup/root" 2>/dev/null)
    [ -n "$_lbr_root" ] || return 1
    while IFS='|' read -r _lbr_part _lbr_was; do
        [ -n "$_lbr_part" ] || continue
        case "$_lbr_part" in *[!A-Za-z0-9_]*|''|_*|[0-9]*) return 1 ;; esac
        rm -rf "$_lbr_root/$_lbr_part" 2>/dev/null || return 1
        if [ "$_lbr_was" = present ]; then
            [ -d "$_lbr_backup/tree/$_lbr_part" ] || return 1
            mv "$_lbr_backup/tree/$_lbr_part" "$_lbr_root/$_lbr_part" 2>/dev/null || return 1
        fi
    done < "$_lbr_backup/partitions"
    rm -rf "$_lbr_backup"
}

_lbr_cleanup_meta_attempt() {
    _lbr_cleanup_stage="${1:-unknown}"
    if [ "$_lbr_cleanup_stage" != post-fs-data ]; then
        if [ "${META_CLEANUP_CAPABILITY:-none}" = hybrid-vfs ] && [ "${META_ENGINE:-}" = hybrid-mount ]; then
            _lbr_hybrid_unload_luoshu || return 2
            _lbr_log '[Fallback] Hybrid Mount confirmed LuoShu pure-VFS rules unloaded by module ID'
            return 0
        fi
        _lbr_log "[Fallback] engine=${META_ENGINE:-unknown} has no verified LuoShu-scoped runtime unload; refusing self-mount overlap"
        return 2
    fi
    if _lbr_meta_mount_delta; then
        _lbr_log '[Fallback] cannot safely unmount changed system targets; external backend state is not attributable to Ziyu'
        return 2
    fi
    _lbr_restore_meta_snapshot || return 1
    if type luoshu_private_unmount_module_view >/dev/null 2>&1; then
        luoshu_private_unmount_module_view "$MODDIR" >/dev/null 2>&1 || return 1
    fi
    _lbr_log '[Fallback] no PID 1 font-target mount delta; Ziyu Meta payload state restored and module view cleaned'
    return 0
}

_lbr_hybrid_cli() {
    for _lbr_hybrid_bin in \
        "${META_MODULE_DIR:-}/hybrid-mount" \
        "${META_MODULE_DIR:-}/hybrid_mount"; do
        [ -x "$_lbr_hybrid_bin" ] || continue
        printf '%s\n' "$_lbr_hybrid_bin"
        return 0
    done
    return 1
}

_lbr_hybrid_module_state() {
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        case "${LUOSHU_BACKEND_TEST_HYBRID_STATE:-active}" in
            active|inactive) printf '%s\n' "$LUOSHU_BACKEND_TEST_HYBRID_STATE"; return 0 ;;
            *) return 2 ;;
        esac
    fi
    _lbr_hybrid_bin=$(_lbr_hybrid_cli) || return 2
    _lbr_hybrid_python="${LUOSHU_PYTHON:-$MODDIR/common/python/bin/luoshu-python}"
    [ -x "$_lbr_hybrid_python" ] || return 2
    _lbr_module_id=$(sed -n 's/^id=//p' "$MODDIR/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n')
    [ "$_lbr_module_id" = 'LuoShu' ] || return 2
    _lbr_hybrid_json=$("$_lbr_hybrid_bin" runtime status 2>>"$_lbr_log") || return 2
    if [ -n "${LUOSHU_PYTHON:-}" ]; then
        printf '%s\n' "$_lbr_hybrid_json" | "$_lbr_hybrid_python" "$MODDIR/common/hybrid_mount_runtime.py" --module-id "$_lbr_module_id" 2>>"$_lbr_log"
    else
        _lbr_pyroot="$MODDIR/common/python"
        printf '%s\n' "$_lbr_hybrid_json" | \
            PYTHONHOME="$_lbr_pyroot" \
            PYTHONPATH="$_lbr_pyroot/lib/python3.14:$_lbr_pyroot/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_lbr_pyroot/lib:$_lbr_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            "$_lbr_hybrid_python" "$MODDIR/common/hybrid_mount_runtime.py" --module-id "$_lbr_module_id" 2>>"$_lbr_log"
    fi
}

_lbr_hybrid_unload_luoshu() {
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        [ "${LUOSHU_BACKEND_TEST_HYBRID_UNLOAD_RC:-0}" -eq 0 ] 2>/dev/null || return 2
        _lbr_log '[Hybrid Mount] test runtime unload verified inactive'
        return 0
    fi
    _lbr_hybrid_bin=$(_lbr_hybrid_cli) || return 2
    _lbr_module_id=$(sed -n 's/^id=//p' "$MODDIR/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n')
    [ "$_lbr_module_id" = 'LuoShu' ] || return 2
    _lbr_status=$(_lbr_hybrid_module_state)
    _lbr_status_rc=$?
    [ "$_lbr_status_rc" -eq 0 ] || {
        _lbr_log "[Hybrid Mount] runtime status unavailable rc=$_lbr_status_rc"
        return 2
    }
    if [ "$_lbr_status" = active ]; then
        "$_lbr_hybrid_bin" runtime unload "$_lbr_module_id" >>"$_lbr_log" 2>&1 || {
            _lbr_log '[Hybrid Mount] runtime unload command failed'
            return 2
        }
    elif [ "$_lbr_status" != inactive ]; then
        _lbr_log "[Hybrid Mount] invalid status=$_lbr_status"
        return 2
    fi
    _lbr_status=$(_lbr_hybrid_module_state)
    _lbr_status_rc=$?
    [ "$_lbr_status_rc" -eq 0 ] && [ "$_lbr_status" = inactive ] || {
        _lbr_log "[Hybrid Mount] unload readback failed rc=$_lbr_status_rc state=$_lbr_status"
        return 2
    }
    return 0
}

_lbr_self_mount_targets_file() {
    _lbr_targets_out="$1"
    _lbr_self_root="${LUOSHU_SELF_MOUNT_STATE_ROOT:-/data/adb/luoshu/self-mount}"
    _lbr_universal_root="${LUOSHU_UNIVERSAL_MOUNT_STATE_ROOT:-/data/adb/luoshu/universal-mount}"
    : > "$_lbr_targets_out" 2>/dev/null || return 2
    for _lbr_list in "$_lbr_self_root/mounts.list" "$_lbr_universal_root/dynamic.mounts"; do
        [ -s "$_lbr_list" ] || continue
        while IFS= read -r _lbr_target; do
            case "$_lbr_target" in
                /system|/system/*|/system_ext|/system_ext/*|/product|/product/*|/vendor|/vendor/*|/odm|/odm/*|/oem|/oem/*|/my_product|/my_product/*|/oplus|/oplus/*|/data/fonts/*)
                    printf '%s\n' "$_lbr_target" >> "$_lbr_targets_out" || return 2
                    ;;
            esac
        done < "$_lbr_list"
    done
    [ -s "$_lbr_targets_out" ]
}

_lbr_target_in_main_mountinfo() {
    _lbr_check_target="$1"
    _lbr_info=$(_lbr_mountinfo)
    _lbr_mountinfo_readable "$_lbr_info" || return 2
    awk -v target="$_lbr_check_target" '$5==target { found=1 } END { exit !found }' "$_lbr_info" 2>/dev/null
}

_lbr_self_mount_targets_active() {
    _lbr_require_target_records="${1:-0}"
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        [ "${LUOSHU_BACKEND_TEST_ROLLBACK_STILL_ACTIVE:-0}" = 1 ] && [ -f "$MODDIR/config/test-self-mounted" ] && return 0
        [ -f "$MODDIR/config/test-self-mounted" ] && [ ! -f "$MODDIR/config/test-self-rollback" ]
        return $?
    fi
    _lbr_targets="$MODDIR/config/.self-mount-targets.$_lbr_boot.$$.tmp"
    _lbr_self_mount_targets_file "$_lbr_targets"
    _lbr_targets_rc=$?
    if [ "$_lbr_targets_rc" -ne 0 ]; then
        rm -f "$_lbr_targets"
        # An active same-boot self transaction with missing records is unknown.
        # On first selection this boot, however, no transaction has run yet.
        [ "$_lbr_targets_rc" -eq 1 ] && [ "$_lbr_require_target_records" != 1 ] || return 2
        _lbr_state_boot=$(_lbr_value "$_lbr_state" boot_id)
        _lbr_state_backend=$(_lbr_value "$_lbr_state" active_backend)
        if [ "$_lbr_state_boot" = "$_lbr_boot" ] && [ "$_lbr_state_backend" = self ]; then
            return 2
        fi
        return 1
    fi
    while IFS= read -r _lbr_target; do
        _lbr_target_in_main_mountinfo "$_lbr_target"
        _lbr_target_rc=$?
        if [ "$_lbr_target_rc" -eq 0 ]; then
            rm -f "$_lbr_targets"
            return 0
        fi
        if [ "$_lbr_target_rc" -eq 2 ]; then
            rm -f "$_lbr_targets"
            return 2
        fi
    done < "$_lbr_targets"
    rm -f "$_lbr_targets"
    return 1
}

_lbr_repair_self_backend_conflict() {
    _lbr_targets="$MODDIR/config/.self-mount-conflict.$_lbr_boot.$$.tmp"
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        [ "${LUOSHU_BACKEND_TEST_SELF_ROLLBACK_RC:-0}" -eq 0 ] 2>/dev/null || return 1
        : > "$MODDIR/config/test-self-rollback" 2>/dev/null || return 1
        return 0
    fi
    _lbr_self_mount_targets_file "$_lbr_targets" || { rm -f "$_lbr_targets"; return 1; }
    _lbr_rollback_self_attempt || { rm -f "$_lbr_targets"; return 1; }
    while IFS= read -r _lbr_target; do
        _lbr_target_in_main_mountinfo "$_lbr_target"
        _lbr_target_rc=$?
        if [ "$_lbr_target_rc" -ne 1 ]; then
            rm -f "$_lbr_targets"
            return 1
        fi
    done < "$_lbr_targets"
    rm -f "$_lbr_targets"
    if type _luoshu_self_state_write >/dev/null 2>&1; then
        _luoshu_self_state_write failed rollback '' backend-conflict-repaired
    fi
    _lbr_write_universal_mount_state self post-mount 0 rolled-back backend-conflict-repaired
    return 0
}

luoshu_assert_single_mount_backend() {
    _lbr_candidate="$1"
    _lbr_current=$(_lbr_value "$_lbr_state" active_backend)
    # The last boot's report cannot establish a layer in this boot. Actual
    # recorded target readback below still detects any real self overlap.
    _lbr_assert_state_boot=$(_lbr_value "$_lbr_state" boot_id)
    if [ -n "$_lbr_assert_state_boot" ] && [ "$_lbr_assert_state_boot" != "$_lbr_boot" ]; then
        _lbr_current=none
    fi
    if [ "$_lbr_candidate" = meta ]; then
        _lbr_self_mount_targets_active
        _lbr_self_active_rc=$?
        if [ "$_lbr_self_active_rc" -eq 0 ]; then
            BACKEND_CONFLICT=1
            _lbr_log '[Backend Conflict] detected active Ziyu self-mount targets before Meta activation; rolling back the owned self transaction'
            _lbr_repair_self_backend_conflict || {
                _lbr_log '[Backend Conflict] self-mount rollback failed or PID 1 still exposes a recorded target'
                _lbr_write_state "${_lbr_current:-none}" "${_lbr_current:-none}" 0 "${META_ENGINE:-unknown}" failed "${_lbr_stage:-unknown}" failed backend-conflict
                return 1
            }
            _lbr_current=none
            _lbr_write_state meta none 0 "${META_ENGINE:-unknown}" not-run "${_lbr_stage:-unknown}" pending backend-conflict-repaired
            _lbr_log '[Backend Conflict] self-mount rollback verified; Meta activation may proceed alone'
        elif [ "$_lbr_self_active_rc" -eq 2 ]; then
            BACKEND_CONFLICT=1
            _lbr_log '[Backend Conflict] PID 1 mountinfo unavailable while checking recorded self-mount targets; refusing Meta activation'
            _lbr_write_state none none 0 "${META_ENGINE:-unknown}" not-run "${_lbr_stage:-unknown}" failed backend-conflict-state-unknown
            return 1
        fi
    elif [ "$_lbr_candidate" = self ] && [ "${META_CLEANUP_CAPABILITY:-none}" = hybrid-vfs ] && [ "${META_ENGINE:-}" = hybrid-mount ]; then
        _lbr_hybrid_unload_luoshu || {
            BACKEND_CONFLICT=1
            _lbr_log '[Backend Conflict] could not confirm Hybrid Mount LuoShu VFS rules inactive before self-mount'
            _lbr_write_state "${_lbr_current:-none}" "${_lbr_current:-none}" 0 hybrid-mount not-run "${_lbr_stage:-unknown}" failed backend-conflict
            return 1
        }
    fi
    if { [ "$_lbr_candidate" = meta ] && [ "$_lbr_current" = self ]; } || \
       { [ "$_lbr_candidate" = self ] && [ "$_lbr_current" = meta ]; }; then
        BACKEND_CONFLICT=1
        _lbr_log "[Backend Conflict] BACKEND_CONFLICT=1 current=$_lbr_current candidate=$_lbr_candidate; refusing commit"
        _lbr_saved_selected=$(_lbr_value "$_lbr_state" selected_backend)
        [ -n "$_lbr_saved_selected" ] || _lbr_saved_selected="$_lbr_current"
        _lbr_write_state "$_lbr_saved_selected" "$_lbr_current" \
            "$(_lbr_value "$_lbr_state" fallback_used)" \
            "$(_lbr_value "$_lbr_state" meta_result)" \
            "$(_lbr_value "$_lbr_state" self_result)" \
            "${_lbr_stage:-unknown}" failed backend-conflict
        return 1
    fi
    return 0
}

_lbr_verify_font_route() {
    _lbr_backend="${1:-unknown}"
    _lbr_active="$(_lbr_active_font)"
    FONT_ROUTE_VERIFY_RESULT=failed
    if [ "$_lbr_active" = default ]; then
        FONT_ROUTE_VERIFY_RESULT=not-applicable
        _lbr_log '[Final Verify] no custom font selected; XML/CJK/Latin/Digit route check is not applicable'
        return 0
    fi
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        case "$_lbr_backend" in
            meta) _lbr_test_verify="${LUOSHU_BACKEND_TEST_META_VERIFY_RESULT:-${LUOSHU_BACKEND_TEST_VERIFY_RESULT:-pass}}" ;;
            *) _lbr_test_verify="${LUOSHU_BACKEND_TEST_SELF_VERIFY_RESULT:-${LUOSHU_BACKEND_TEST_VERIFY_RESULT:-pass}}" ;;
        esac
        [ "$_lbr_test_verify" = pass ] && FONT_ROUTE_VERIFY_RESULT=passed
        [ "$_lbr_test_verify" = pass ]
        return $?
    fi
    _lbr_python="$MODDIR/common/python/bin/luoshu-python"
    if [ -n "${LUOSHU_PYTHON:-}" ]; then
        _lbr_python="$LUOSHU_PYTHON"
        _lbr_py_rc=$("$_lbr_python" "$MODDIR/common/font_route_verify.py" \
            --module-root "$MODDIR" --visible-root "${LUOSHU_FONT_VERIFY_VISIBLE_ROOT:-/proc/1/root}" \
            --mountinfo "$(_lbr_mountinfo)" --mode auto \
            --output "$MODDIR/config/mount-backend-verification.json" 2>>"$_lbr_log")
    else
        _lbr_pyroot="$MODDIR/common/python"
        _lbr_py_rc=$(PYTHONHOME="$_lbr_pyroot" PYTHONPATH="$MODDIR/common:$_lbr_pyroot/lib/python3.14:$_lbr_pyroot/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_lbr_pyroot/lib:$_lbr_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            "$_lbr_python" "$MODDIR/common/font_route_verify.py" \
            --module-root "$MODDIR" --visible-root "${LUOSHU_FONT_VERIFY_VISIBLE_ROOT:-/proc/1/root}" \
            --mountinfo "$(_lbr_mountinfo)" --mode auto \
            --output "$MODDIR/config/mount-backend-verification.json" 2>>"$_lbr_log")
    fi
    _lbr_rc=$?
    _lbr_log "[Final Verify] route-closure rc=$_lbr_rc result=$_lbr_py_rc"
    [ "$_lbr_rc" -eq 0 ] || return 1

    if _lbr_is_universal; then
        LUOSHU_VERIFY_BOOT_COMPLETED=1 LUOSHU_VERIFY_SETTLE_SECONDS=0 \
        LUOSHU_VERIFY_MAIN_NAMESPACE=1 LUOSHU_VERIFY_NO_CUTOVER=1 \
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/universal_font_runtime_verify.sh" verify >>"$_lbr_log" 2>&1 || return 1
        [ "$(_lbr_value "$MODDIR/config/universal-font-runtime-verification.conf" grade)" != FAIL ] || return 1
    fi
    FONT_ROUTE_VERIFY_RESULT=passed
    return 0
}

_lbr_write_universal_mount_state() {
    [ -s "$MODDIR/config/universal-font-runtime.conf" ] || return 0
    _lbr_runtime="$MODDIR/config/universal-font-runtime.conf"
    _lbr_mount_state="$MODDIR/config/universal-font-mount.conf"
    _lbr_dynamic="$MODDIR/.luoshu-payload/.luoshu-runtime/deployment/dynamic-mounts.conf"
    _lbr_dynamic_count=0
    [ ! -s "$_lbr_dynamic" ] || _lbr_dynamic_count=$(awk 'NF {n++} END {print n+0}' "$_lbr_dynamic")
    {
        printf 'state=%s\n' "${4:-mounted}"
        printf 'backend=%s\n' "${1:-meta}"
        printf 'manager=%s\n' "${ROOT_MANAGER:-unknown}"
        printf 'stage=%s\n' "${2:-post-mount}"
        printf 'deploymentId=%s\n' "$(_lbr_value "$_lbr_runtime" deploymentId)"
        printf 'payloadDigest=%s\n' "$(_lbr_value "$_lbr_runtime" payloadDigest)"
        printf 'dynamicMounted=%s\n' "${3:-$_lbr_dynamic_count}"
        printf 'error=%s\n' "${5:-}"
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$_lbr_mount_state.tmp.$$" 2>/dev/null && mv -f "$_lbr_mount_state.tmp.$$" "$_lbr_mount_state" 2>/dev/null
}

_lbr_meta_prepare() {
    [ "${LUOSHU_BACKEND_TEST_MODE:-0}" != 1 ] || return "${LUOSHU_BACKEND_TEST_PREPARE_RC:-0}"
    type luoshu_private_mount_module_view >/dev/null 2>&1 || return 1
    luoshu_private_mount_module_view "$MODDIR" >/dev/null 2>&1 || return 1
    if [ "${META_ENGINE:-}" = meta-overlayfs ]; then
        _lbr_snapshot=$( _lbr_meta_snapshot ) || return 1
        [ -n "$_lbr_snapshot" ] || return 1
        type luoshu_sync_mount_payload >/dev/null 2>&1 || return 1
        luoshu_sync_mount_payload "$(_lbr_active_font)" >/dev/null 2>&1 || return 1
    fi
    return 0
}

_lbr_finish_meta_backup() {
    rm -rf "$MODDIR/.luoshu-runtime/meta-backup/$_lbr_boot" 2>/dev/null || true
}

_lbr_run_self() {
    _lbr_stage="$1"
    _lbr_expected=$(_lbr_stage_for_root "${ROOT_MANAGER:-unknown}")
    [ "$_lbr_stage" = "$_lbr_expected" ] || return 2
    [ "$_lbr_stage" != none ] || return 2
    luoshu_assert_single_mount_backend self || return 1
    _lbr_set_self_skip || return 1
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        _lbr_rc="${LUOSHU_BACKEND_TEST_SELF_RC:-0}"
        if [ "$_lbr_rc" -eq 0 ]; then
            : > "$MODDIR/config/test-self-mounted"
        fi
    elif _lbr_is_universal; then
        # Use the existing frozen-deployment transaction, including dynamic
        # /data/fonts targets; never clone its mount algorithm here.
        [ -f "$MODDIR/common/universal_mount_runtime.sh" ] || return 1
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/universal_mount_runtime.sh" hook "$_lbr_stage" >/dev/null 2>&1
        _lbr_rc=$?
    else
        # Older physical-safe releases committed fonts without a hash ledger.
        # Bootstrap that exact declared contract before touching system mounts;
        # existing ledgers remain immutable and are verified normally.
        if [ "$(_lbr_active_font)" != default ] && [ ! -e "$MODDIR/config/font-payload-manifest.conf" ]; then
            [ -f "$MODDIR/common/physical_payload_manifest.sh" ] &&
                . "$MODDIR/common/physical_payload_manifest.sh" &&
                luoshu_physical_manifest_ensure "$MODDIR" "$MODDIR/.luoshu-payload" || {
                    _lbr_log '[Prepare] physical payload integrity contract missing or invalid; self-mount not started'
                    _lbr_write_state self none "${_lbr_fallback:-0}" "${_lbr_meta_result:-not-selected}" failed "$_lbr_stage" failed physical-integrity-contract-missing
                    return 1
                }
            _lbr_log '[Prepare] bootstrapped missing physical-safe payload SHA256 ledger before self-mount'
        fi
        type luoshu_private_mount_module_view >/dev/null 2>&1 || return 1
        luoshu_private_mount_module_view "$MODDIR" >/dev/null 2>&1 || return 1
        _lbr_active=$(_lbr_active_font)
        if [ "$_lbr_active" != default ] && [ -f "$MODDIR/common/font_manager.sh" ]; then
            if [ -f "$MODDIR/config/stock_inventory_scan_pending" ] || [ ! -s "$MODDIR/config/device_font_inventory.json" ]; then
                LUOSHU_STOCK_VIEW_VERIFIED=1 LUOSHU_FRESH_STOCK_SCAN=1 MODDIR="$MODDIR" \
                    sh "$MODDIR/common/font_manager.sh" action stock_scan >>"$MODDIR/logs/mount-backend.log" 2>&1 || true
            fi
        fi
        type luoshu_private_self_mount_ensure >/dev/null 2>&1 || return 1
        _lbr_previous_force_self="${LUOSHU_META_FORCE_SELF:-}"
        LUOSHU_META_FORCE_SELF=1
        luoshu_private_self_mount_ensure >/dev/null 2>&1
        _lbr_rc=$?
        if [ -n "$_lbr_previous_force_self" ]; then
            LUOSHU_META_FORCE_SELF="$_lbr_previous_force_self"
        else
            unset LUOSHU_META_FORCE_SELF
        fi
    fi
    if [ "$_lbr_rc" -ne 0 ]; then
        _lbr_log "[Self Mount] failed manager=${ROOT_MANAGER:-unknown} stage=$_lbr_stage rc=$_lbr_rc"
        _lbr_write_universal_mount_state self "$_lbr_stage" 0 failed self-mount-failed
        _lbr_write_state self none "${_lbr_fallback:-0}" "${_lbr_meta_result:-not-selected}" failed "$_lbr_stage" failed self-mount-failed
        return 1
    fi
    _lbr_write_universal_mount_state self "$_lbr_stage"
    _lbr_verify_font_route self || {
        _lbr_log '[Final Verify] self font route failed; rolling back the recorded Ziyu transaction'
        if ! _lbr_rollback_self_attempt; then
            _lbr_log '[Rollback] self mount rollback failed; refusing to report a clean state'
            _lbr_write_universal_mount_state self "$_lbr_stage" 0 failed rollback-failed
            _lbr_write_state self none "${_lbr_fallback:-0}" "${_lbr_meta_result:-not-selected}" failed "$_lbr_stage" failed 'font-route-verification-failed;rollback-failed'
            return 1
        fi
        _lbr_self_mount_targets_active required
        _lbr_rollback_verify_rc=$?
        if [ "$_lbr_rollback_verify_rc" -ne 1 ]; then
            _lbr_log "[Rollback] command returned success but PID 1 target readback is not clean rc=$_lbr_rollback_verify_rc"
            _lbr_write_universal_mount_state self "$_lbr_stage" 0 failed rollback-verification-failed
            _lbr_write_state self none "${_lbr_fallback:-0}" "${_lbr_meta_result:-not-selected}" failed "$_lbr_stage" failed 'font-route-verification-failed;rollback-verification-failed'
            return 1
        fi
        _lbr_write_universal_mount_state self "$_lbr_stage" 0 rolled-back font-route-verification-failed
        _lbr_write_state self none "${_lbr_fallback:-0}" "${_lbr_meta_result:-not-selected}" failed "$_lbr_stage" failed font-route-verification-failed
        return 1
    }
    luoshu_assert_single_mount_backend self || return 1
    _lbr_write_state self self "${_lbr_fallback:-0}" "${_lbr_meta_result:-not-selected}" "$FONT_ROUTE_VERIFY_RESULT" "$_lbr_stage" "$FONT_ROUTE_VERIFY_RESULT" none
    _lbr_log "[Final Verify] backend=self result=$FONT_ROUTE_VERIFY_RESULT"
    _lbr_log "[Self Mount] result=ok stage=$_lbr_stage"
    _lbr_log 'ACTIVE_BACKEND=self'
    return 0
}

_lbr_rollback_self_attempt() {
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        [ "${LUOSHU_BACKEND_TEST_ROLLBACK_RC:-0}" -eq 0 ] 2>/dev/null || return 1
        : > "$MODDIR/config/test-self-rollback"
        return 0
    fi
    if _lbr_is_universal && [ -f "$MODDIR/common/universal_mount_runtime.sh" ]; then
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/universal_mount_runtime.sh" rollback >/dev/null 2>&1
        return $?
    fi
    type _luoshu_atomic_rollback >/dev/null 2>&1 || return 1
    type _luoshu_self_state_root >/dev/null 2>&1 || return 1
    _lbr_mounts=$(_luoshu_self_state_root)/mounts.list
    _luoshu_atomic_rollback "$_lbr_mounts" >/dev/null 2>&1
}

_lbr_meta_failover() {
    _lbr_reason="$1"
    _lbr_preference_failure="$_lbr_reason"
    _lbr_cleanup_meta_attempt "${2:-unknown}"
    _lbr_rc=$?
    if [ "$_lbr_rc" -ne 0 ]; then
        BACKEND_CONFLICT=0
        _lbr_write_universal_mount_state meta "${2:-post-mount}" 0 failed meta-cleanup-not-safe
        _lbr_write_state meta none 0 cleanup-uncertain not-run "${2:-post-mount}" failed "$_lbr_reason;meta-cleanup-not-safe"
        _lbr_log "[Fallback] Meta changed or untracked PID 1 target mounts; cleanup could not prove them removed. Self-mount is blocked to prevent overlap. reason=$_lbr_reason"
        if [ -f "$MODDIR/common/diagnostic_bundle.sh" ]; then
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
                sh "$MODDIR/common/diagnostic_bundle.sh" dump-once-per-boot "meta-cleanup-uncertain" >> "$_lbr_log" 2>&1 || true
        fi
        return 1
    fi
    _lbr_write_universal_mount_state meta "${2:-post-mount}" 0 rolled-back "$_lbr_reason"
    _lbr_log "[Fallback] meta backend rejected reason=$_lbr_reason cleanup=ok switching to self-mount"
    _lbr_set_self_skip || {
        _lbr_write_state none none 1 failed failed "${2:-post-mount}" failed skip-marker-write-failed
        return 1
    }
    _lbr_fallback=1
    _lbr_meta_result=failed
    _lbr_fallback_stage=$(_lbr_stage_for_root "${ROOT_MANAGER:-unknown}")
    if [ "$_lbr_fallback_stage" = service ] && [ "${ROOT_MANAGER:-unknown}" = Magisk ]; then
        _lbr_log '[Fallback] Magisk late verification failed; running the existing self-mount transaction from service stage'
        _lbr_fallback_stage=post-fs-data
    fi
    _lbr_write_state self none 1 failed pending "$_lbr_fallback_stage" pending "$_lbr_reason"
    if [ "${2:-}" = post-fs-data ] && [ "$_lbr_fallback_stage" = post-mount ]; then
        _lbr_log '[Fallback] self-mount is scheduled for the Root manager post-mount hook'
        return 0
    fi
    _lbr_run_self "$_lbr_fallback_stage"
}

_lbr_verify_meta() {
    _lbr_stage="$1"
    _lbr_write_universal_mount_state meta "$_lbr_stage" 0
    if _lbr_verify_font_route meta; then
        luoshu_assert_single_mount_backend meta || return 1
        _lbr_finish_meta_backup
        _lbr_write_universal_mount_state meta "$_lbr_stage" 0 mounted
        _lbr_write_state meta meta "${_lbr_fallback:-0}" passed "${_lbr_self_result:-not-run}" "$_lbr_stage" "$FONT_ROUTE_VERIFY_RESULT" none
        _lbr_log "[Final Verify] backend=meta result=$FONT_ROUTE_VERIFY_RESULT"
        _lbr_log 'ACTIVE_BACKEND=meta'
        return 0
    fi
    _lbr_meta_failover font-route-verification-failed "$_lbr_stage"
}

luoshu_mount_backend_hook() {
    _lbr_stage="$1"
    _lbr_detect || return 1
    _lbr_active_font=$(_lbr_active_font)
    _lbr_fallback=0
    _lbr_saved_boot=$(_lbr_value "$_lbr_state" boot_id)
    _lbr_preference=auto
    type luoshu_mount_preference_get >/dev/null 2>&1 && _lbr_preference=$(luoshu_mount_preference_get)
    _lbr_preference_failure=none
    if [ "$_lbr_saved_boot" = "$_lbr_boot" ]; then
        _lbr_boot_preference=$(_lbr_value "$_lbr_state" preferred_backend)
        case "$_lbr_boot_preference" in auto|meta|self) _lbr_preference="$_lbr_boot_preference" ;; esac
        _lbr_preference_failure=$(_lbr_value "$_lbr_state" preference_failure)
        [ -n "$_lbr_preference_failure" ] || _lbr_preference_failure=none
    fi
    BACKEND_CONFLICT=0
    if [ "$_lbr_saved_boot" = "$_lbr_boot" ]; then
        BACKEND_CONFLICT=$(_lbr_value "$_lbr_state" backend_conflict)
        [ "$BACKEND_CONFLICT" = 1 ] || BACKEND_CONFLICT=0
    fi
    _lbr_meta_result=not-selected
    _lbr_self_result=not-run
    _lbr_verify=pending
    _lbr_error=none
    _lbr_expected=$(_lbr_stage_for_root "${ROOT_MANAGER:-unknown}")

    if [ "${META_USABLE:-0}" = 1 ] && ! _lbr_meta_payload_compatible; then
        META_USABLE=0
        _lbr_error=meta-engine-does-not-cover-universal-dynamic-targets
        _lbr_log '[Meta] usable=no reason=universal-dynamic-targets-require-self-backend'
    fi
    if [ "${ROOT_MANAGER:-unknown}" = unknown ] || [ "$_lbr_expected" = none ]; then
        _lbr_write_state none none 0 skipped skipped "$_lbr_stage" failed unknown-root-manager
        _lbr_log '[Root] manager=unknown; safest policy is no mount attempt'
        return 1
    fi

    _lbr_selected=self
    if [ "$_lbr_preference" != self ] && [ "${META_USABLE:-0}" = 1 ] && [ "$_lbr_active_font" != default ]; then
        _lbr_selected=meta
        if [ "${META_CLEANUP_CAPABILITY:-none}" != hybrid-vfs ] && \
           [ "${META_HYBRID_ROUTE_REASON:-}" = hybrid-route-not-pure-vfs ] && \
           type luoshu_meta_hybrid_ensure_vfs_rule >/dev/null 2>&1; then
            # Degraded Meta (Overlay route) is accepted per explicit preference;
            # still upgrade the route to pure VFS so a capable runtime gets
            # module-scoped unload on a later boot.
            if luoshu_meta_hybrid_ensure_vfs_rule; then
                _lbr_log "[Meta Ensure] result=${LUOSHU_META_ENSURE_RESULT:-}; LuoShu VFS rule takes effect on next boot"
            else
                _lbr_log "[Meta Ensure] result=${LUOSHU_META_ENSURE_RESULT:-} detail=${LUOSHU_META_ENSURE_DETAIL:-none}; config left unchanged"
            fi
        fi
    elif [ "$_lbr_preference" = meta ] && [ "${META_USABLE:-0}" != 1 ]; then
        _lbr_preference_failure="${META_USABLE_REASON:-meta-unavailable}"
        [ "$_lbr_error" = none ] || _lbr_preference_failure="$_lbr_error"
        if [ "$_lbr_preference_failure" = hybrid-route-not-pure-vfs ] && \
           type luoshu_meta_hybrid_ensure_vfs_rule >/dev/null 2>&1; then
            # Users pick Meta in the app; they must not need to edit Hybrid
            # Mount TOML. Fix the upstream Overlay default for the NEXT boot;
            # this boot still mounts with self so mount ownership stays clear.
            if luoshu_meta_hybrid_ensure_vfs_rule; then
                _lbr_log "[Meta Ensure] result=${LUOSHU_META_ENSURE_RESULT:-}; LuoShu VFS rule takes effect on next boot"
            else
                _lbr_log "[Meta Ensure] result=${LUOSHU_META_ENSURE_RESULT:-} detail=${LUOSHU_META_ENSURE_DETAIL:-none}; config left unchanged"
            fi
        fi
        _lbr_log "[Preference] requested=meta unavailable=$_lbr_preference_failure; selecting safe self backend"
    fi
    if [ "$_lbr_stage" = post-fs-data ]; then
        _lbr_snapshot_mountinfo || {
            _lbr_log '[Main Namespace] could not snapshot /proc/1/mountinfo; Meta fallback will fail closed'
        }
    fi

    if [ "$_lbr_stage" = post-fs-data ]; then
        _lbr_log "[Root] manager=${ROOT_MANAGER:-unknown} version=${ROOT_VERSION:-unknown} source=${ROOT_DETECTION_SOURCE:-unknown}"
        _lbr_log "[Meta] engine=${META_ENGINE:-none} available=${META_AVAILABLE:-0} enabled=${META_ENABLED:-0} usable=${META_USABLE:-0} reason=${META_USABLE_REASON:-unknown} cleanup=${META_CLEANUP_CAPABILITY:-none}"
        _lbr_log "[Backend] preferred=$_lbr_preference selected=$_lbr_selected preference_failure=$_lbr_preference_failure"
        if [ "$_lbr_selected" = meta ]; then
            if ! luoshu_assert_single_mount_backend meta; then
                _lbr_write_state none none 0 skipped skipped post-fs-data failed backend-conflict
                return 1
            fi
            _lbr_clear_owned_skip || true
            if _lbr_meta_prepare; then
                _lbr_meta_result=prepared
                _lbr_write_state meta none 0 prepared not-run post-fs-data pending none
                _lbr_log "[Meta Sync] engine=${META_ENGINE:-none} result=ok"
                return 0
            fi
            _lbr_meta_result=failed
            _lbr_meta_failover meta-prepare-failed post-fs-data
            return $?
        fi
        _lbr_set_self_skip || {
            _lbr_write_state none none 0 not-usable failed post-fs-data failed skip-marker-write-failed
            return 1
        }
        _lbr_write_state self none 0 not-usable pending post-fs-data pending "${_lbr_error:-none}"
        if [ "$_lbr_expected" = post-fs-data ]; then
            _lbr_run_self post-fs-data
            return $?
        fi
        if type luoshu_private_unmount_module_view >/dev/null 2>&1; then
            luoshu_private_unmount_module_view "$MODDIR" >/dev/null 2>&1 || {
                _lbr_write_state none none 0 not-usable failed post-fs-data failed module-view-cleanup-failed
                return 1
            }
        fi
        _lbr_log "[Self Mount] scheduled stage=$_lbr_expected"
        return 0
    fi

    _lbr_saved_boot=$(_lbr_value "$_lbr_state" boot_id)
    _lbr_saved_selected=$(_lbr_value "$_lbr_state" selected_backend)
    if [ "$_lbr_saved_boot" = "$_lbr_boot" ] && [ -n "$_lbr_saved_selected" ]; then
        _lbr_selected="$_lbr_saved_selected"
        _lbr_fallback=$(_lbr_value "$_lbr_state" fallback_used)
        _lbr_meta_result=$(_lbr_value "$_lbr_state" meta_result)
        _lbr_self_result=$(_lbr_value "$_lbr_state" self_result)
    fi
    _lbr_log "[Backend] stage=$_lbr_stage selected=$_lbr_selected"

    if [ "$_lbr_selected" = meta ]; then
        if [ "$_lbr_stage" = post-mount ] || [ "$_lbr_stage" = service ]; then
            _lbr_verify_meta "$_lbr_stage"
            return $?
        fi
        return 0
    fi
    if [ "$_lbr_expected" = "$_lbr_stage" ]; then
        _lbr_run_self "$_lbr_stage"
        return $?
    fi
    return 0
}

luoshu_backend_load_dependencies

if [ "${0##*/}" = mount_backend_runtime.sh ]; then
    case "${1:-hook}" in
        hook) luoshu_mount_backend_hook "${2:-post-fs-data}" ;;
        detect)
            _lbr_detect
            printf 'ROOT_MANAGER=%s\nROOT_VERSION=%s\nROOT_DETECTION_SOURCE=%s\n' \
                "${ROOT_MANAGER:-unknown}" "${ROOT_VERSION:-unknown}" "${ROOT_DETECTION_SOURCE:-unknown}"
            printf 'META_ENGINE=%s\nMETA_AVAILABLE=%s\nMETA_ENABLED=%s\nMETA_USABLE=%s\n' \
                "${META_ENGINE:-none}" "${META_AVAILABLE:-0}" "${META_ENABLED:-0}" "${META_USABLE:-0}"
            printf 'META_USABLE_REASON=%s\nMETA_CLEANUP_CAPABILITY=%s\n' \
                "${META_USABLE_REASON:-unknown}" "${META_CLEANUP_CAPABILITY:-none}"
            printf 'META_INSTALLED=%s\nMETA_READY=%s\n' "${META_INSTALLED:-${META_AVAILABLE:-0}}" "${META_READY:-0}"
            ;;
        *) printf 'usage: %s {hook <post-fs-data|post-mount|service>|detect}\n' "$0" >&2; exit 2 ;;
    esac
fi
