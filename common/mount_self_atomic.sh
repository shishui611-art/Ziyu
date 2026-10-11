#!/system/bin/sh
# LuoShu atomic self-mount transaction and strict boot visibility verification.
# Independent physical font components may retain verified replacements while
# unavailable components remain stock. XML-changing payloads remain atomic.
set +e

# Legacy injected metamodule fixtures keep their strict per-partition verifier.
# Production never sets LUOSHU_META_TEST_ENGINE and always uses this atomic path.
case "${LUOSHU_META_TEST_ENGINE:-}" in
    ''|self-mount) ;;
    *) return 0 2>/dev/null || exit 0 ;;
esac

_luoshu_atomic_manifest() {
    _lsam_module=$(_luoshu_self_module)
    printf '%s/config/self-mount-required.conf\n' "$_lsam_module"
}

_luoshu_atomic_file_optional() {
    case "$1" in
        luoshu/mount-probe.conf|.luoshu-data-fonts-config.xml) return 0 ;;
        *) return 1 ;;
    esac
}

_luoshu_font_mount_warning() {
    _lfmw_module=$(_luoshu_self_module)
    printf 'warning=%s\n' "$*" >> "$_lfmw_module/config/font-mount-warnings.conf"
    printf '[%s] [FONT-MOUNT] WARN %s；其他已验证字体继续应用\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" \
        >> "$_lfmw_module/logs/fontswitch.log"
    _luoshu_self_log "[WARN] $*"
}

_luoshu_atomic_missing_target_allowed() {
    _lsamta_rel="$1"
    _lsamta_mode="${2:-overlay}"
    _luoshu_atomic_file_optional "$_lsamta_rel" && return 0
    # A per-file bind can only replace an inode that already exists in the ROM
    # view. Payloads intentionally contain additive aliases for several ROM
    # families, so an alias absent on this device is not a failed replacement.
    # The caller still requires at least one real target per component, keeping
    # the transaction fail-closed when nothing on the device can be mounted.
    [ "$_lsamta_mode" = bind ]
}

_luoshu_atomic_real_target() {
    _lsart_path="$1"
    _lsart_real=''
    if command -v readlink >/dev/null 2>&1; then
        _lsart_real=$(readlink -f "$_lsart_path" 2>/dev/null)
    elif command -v busybox >/dev/null 2>&1; then
        _lsart_real=$(busybox readlink -f "$_lsart_path" 2>/dev/null)
    fi
    [ -n "$_lsart_real" ] || _lsart_real="$_lsart_path"
    printf '%s\n' "$_lsart_real"
}

_luoshu_atomic_target_seen() {
    _lsats_file="$1"
    _lsats_target="$2"
    [ -s "$_lsats_file" ] || return 1
    while IFS= read -r _lsats_seen; do
        [ "$_lsats_seen" = "$_lsats_target" ] && return 0
    done < "$_lsats_file"
    return 1
}

# Process real files before symlink aliases. Several OEM ROMs expose many font
# names as symlinks to one canonical variable font; binding an alias first would
# otherwise choose an arbitrary role for that shared mount target.
_luoshu_atomic_bind_file_order() {
    _lsabfo_source="$1"
    _lsabfo_target="$2"
    _lsabfo_output="$3"
    _lsabfo_all="${_lsabfo_output}.all"
    find "$_lsabfo_source" -type f 2>/dev/null > "$_lsabfo_all" || return 1
    : > "$_lsabfo_output" 2>/dev/null || return 1
    while IFS= read -r _lsabfo_src; do
        [ -n "$_lsabfo_src" ] || continue
        _lsabfo_rel=${_lsabfo_src#$_lsabfo_source/}
        [ -L "$_lsabfo_target/$_lsabfo_rel" ] || printf '%s\n' "$_lsabfo_src" >> "$_lsabfo_output"
    done < "$_lsabfo_all"
    while IFS= read -r _lsabfo_src; do
        [ -n "$_lsabfo_src" ] || continue
        _lsabfo_rel=${_lsabfo_src#$_lsabfo_source/}
        [ ! -L "$_lsabfo_target/$_lsabfo_rel" ] || printf '%s\n' "$_lsabfo_src" >> "$_lsabfo_output"
    done < "$_lsabfo_all"
    rm -f "$_lsabfo_all" 2>/dev/null || true
    return 0
}

_luoshu_atomic_hash_stream() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum 2>/dev/null | awk '{print $1}'
    elif command -v busybox >/dev/null 2>&1; then
        busybox sha256sum 2>/dev/null | awk '{print $1}'
    else
        cksum 2>/dev/null | awk '{print $1 ":" $2}'
    fi
}

_luoshu_atomic_file_size() {
    stat -c '%s' "$1" 2>/dev/null || wc -c < "$1" 2>/dev/null | tr -d '[:space:]'
}

_luoshu_atomic_quick_fingerprint() {
    _lsaqf_file="$1"
    [ -f "$_lsaqf_file" ] || return 1
    _lsaqf_size=$(_luoshu_atomic_file_size "$_lsaqf_file")
    case "$_lsaqf_size" in ''|*[!0-9]*) return 1 ;; esac
    {
        printf 'bytes=%s\n' "$_lsaqf_size"
        head -c 65536 "$_lsaqf_file" 2>/dev/null || true
        if [ "$_lsaqf_size" -gt 65536 ] 2>/dev/null; then
            tail -c 65536 "$_lsaqf_file" 2>/dev/null || true
        fi
    } | _luoshu_atomic_hash_stream
}

_luoshu_atomic_files_equal() {
    _lsafe_left="$1"
    _lsafe_right="$2"
    [ -f "$_lsafe_left" ] && [ -f "$_lsafe_right" ] || return 1
    _lsafe_left_size=$(_luoshu_atomic_file_size "$_lsafe_left")
    _lsafe_right_size=$(_luoshu_atomic_file_size "$_lsafe_right")
    [ -n "$_lsafe_left_size" ] && [ "$_lsafe_left_size" = "$_lsafe_right_size" ] || return 1
    _lsafe_left_fingerprint=$(_luoshu_atomic_quick_fingerprint "$_lsafe_left")
    _lsafe_right_fingerprint=$(_luoshu_atomic_quick_fingerprint "$_lsafe_right")
    [ -n "$_lsafe_left_fingerprint" ] && [ "$_lsafe_left_fingerprint" = "$_lsafe_right_fingerprint" ]
}

_luoshu_atomic_files_identical() {
    _lsafi_left="$1"
    _lsafi_right="$2"
    [ -f "$_lsafi_left" ] && [ -f "$_lsafi_right" ] || return 2
    _lsafi_left_size=$(_luoshu_atomic_file_size "$_lsafi_left")
    _lsafi_right_size=$(_luoshu_atomic_file_size "$_lsafi_right")
    [ -n "$_lsafi_left_size" ] && [ "$_lsafi_left_size" = "$_lsafi_right_size" ] || return 1
    if command -v cmp >/dev/null 2>&1; then
        cmp -s "$_lsafi_left" "$_lsafi_right" 2>/dev/null
        return $?
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        _lsafi_left_hash=$(sha256sum "$_lsafi_left" 2>/dev/null | awk '{print $1}')
        _lsafi_right_hash=$(sha256sum "$_lsafi_right" 2>/dev/null | awk '{print $1}')
    elif command -v busybox >/dev/null 2>&1; then
        _lsafi_left_hash=$(busybox sha256sum "$_lsafi_left" 2>/dev/null | awk '{print $1}')
        _lsafi_right_hash=$(busybox sha256sum "$_lsafi_right" 2>/dev/null | awk '{print $1}')
    else
        return 2
    fi
    [ -n "$_lsafi_left_hash" ] && [ -n "$_lsafi_right_hash" ] || return 2
    [ "$_lsafi_left_hash" = "$_lsafi_right_hash" ]
}

# A file bind follows the ROM symlink to its real inode. If two logical font
# paths resolve to that inode but carry different payload bytes, no sequence of
# per-file binds can satisfy both routes. Detect this before creating font binds.
_luoshu_atomic_bind_conflict_detail() {
    _lsabcd_source="$1"
    _lsabcd_target="$2"
    _lsabcd_state=$(_luoshu_self_state_root)
    _lsabcd_files="$_lsabcd_state/conflict-files.$$"
    _lsabcd_seen="$_lsabcd_state/conflict-seen.$$"
    _LUOSHU_ATOMIC_BIND_CONFLICT_DETAIL=''
    mkdir -p "$_lsabcd_state" 2>/dev/null || return 2
    find "$_lsabcd_source" -type f 2>/dev/null > "$_lsabcd_files" || return 2
    : > "$_lsabcd_seen" 2>/dev/null || { rm -f "$_lsabcd_files"; return 2; }
    while IFS= read -r _lsabcd_src; do
        [ -n "$_lsabcd_src" ] || continue
        _lsabcd_rel=${_lsabcd_src#$_lsabcd_source/}
        _lsabcd_dst="$_lsabcd_target/$_lsabcd_rel"
        [ -f "$_lsabcd_dst" ] || continue
        _lsabcd_real=$(_luoshu_atomic_real_target "$_lsabcd_dst")
        if [ -L "$_lsabcd_dst" ] && [ "$_lsabcd_real" = "$_lsabcd_dst" ]; then
            _LUOSHU_ATOMIC_BIND_CONFLICT_DETAIL="target=$_lsabcd_dst; real-target-resolution-unavailable"
            rm -f "$_lsabcd_files" "$_lsabcd_seen" 2>/dev/null || true
            return 2
        fi
        while IFS='|' read -r _lsabcd_prev_real _lsabcd_prev_src; do
            [ -n "$_lsabcd_prev_real" ] && [ "$_lsabcd_prev_real" = "$_lsabcd_real" ] || continue
            _luoshu_atomic_files_identical "$_lsabcd_prev_src" "$_lsabcd_src"
            _lsabcd_identical_rc=$?
            if [ "$_lsabcd_identical_rc" -eq 1 ]; then
                _LUOSHU_ATOMIC_BIND_CONFLICT_DETAIL="target=$_lsabcd_real; first=$_lsabcd_prev_src; second=$_lsabcd_src"
                rm -f "$_lsabcd_files" "$_lsabcd_seen" 2>/dev/null || true
                return 1
            fi
            if [ "$_lsabcd_identical_rc" -ne 0 ]; then
                _LUOSHU_ATOMIC_BIND_CONFLICT_DETAIL="target=$_lsabcd_real; byte-comparison-unavailable"
                rm -f "$_lsabcd_files" "$_lsabcd_seen" 2>/dev/null || true
                return 2
            fi
        done < "$_lsabcd_seen"
        printf '%s|%s\n' "$_lsabcd_real" "$_lsabcd_src" >> "$_lsabcd_seen" || {
            rm -f "$_lsabcd_files" "$_lsabcd_seen" 2>/dev/null || true
            return 2
        }
    done < "$_lsabcd_files"
    rm -f "$_lsabcd_files" "$_lsabcd_seen" 2>/dev/null || true
    return 0
}

_luoshu_atomic_pid1_target() {
    _lsapt_target="$1"
    _lsapt_root="${LUOSHU_SELF_PID1_ROOT:-/proc/1/root}"
    if [ -d "$_lsapt_root" ]; then
        case "$_lsapt_root" in
            /) printf '%s\n' "$_lsapt_target" ;;
            *) printf '%s%s\n' "${_lsapt_root%/}" "$_lsapt_target" ;;
        esac
    else
        printf '%s\n' "$_lsapt_target"
    fi
}

_luoshu_atomic_boot_id() {
    _lsabi_value=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    [ -n "$_lsabi_value" ] || _lsabi_value=unknown
    printf '%s\n' "$_lsabi_value"
}

# Return success only when the mount journal belongs to this boot. A journal
# from an older boot must be cleared without unmounting generic system targets,
# because another module may own those targets now.
_luoshu_atomic_prepare_boot_state() {
    _lsapbs_list="$1"
    _lsapbs_state=$(_luoshu_self_state_root)
    _lsapbs_file="$_lsapbs_state/boot-id"
    _lsapbs_current=$(_luoshu_atomic_boot_id)
    _lsapbs_saved=$(cat "$_lsapbs_file" 2>/dev/null | tr -d '\r\n')
    if [ -n "$_lsapbs_saved" ] && [ "$_lsapbs_saved" = "$_lsapbs_current" ]; then
        return 0
    fi
    if _luoshu_self_state_mounts_remain; then
        _luoshu_self_log '旧启动账本仍有工作目录挂载或无法检查挂载表，拒绝清空工作目录'
        return 2
    fi
    : > "$_lsapbs_list" 2>/dev/null || return 2
    rm -rf "$_lsapbs_state/lower" "$_lsapbs_state/work" 2>/dev/null || true
    printf '%s\n' "$_lsapbs_current" > "${_lsapbs_file}.tmp.$$" 2>/dev/null && \
        mv -f "${_lsapbs_file}.tmp.$$" "$_lsapbs_file" 2>/dev/null || return 2
    return 1
}

# Derive this in every shell that verifies a manifest, including later boot
# hooks that did not run the original mounting transaction.
_luoshu_atomic_partial_fonts_enabled() {
    _lsapfe_module=$(_luoshu_self_module)
    [ "$(sed -n 's/^enabled=//p' "$_lsapfe_module/config/font_runtime_legacy_v14_4.conf" 2>/dev/null)" = true ] &&
        [ "$(sed -n 's/^core=//p' "$_lsapfe_module/config/font_runtime_legacy_v14_4.conf" 2>/dev/null)" = physical-safe-v1 ] &&
        [ "$(sed -n 's/^schema=//p' "$_lsapfe_module/config/font-payload-schema.conf" 2>/dev/null)" = legacy-physical-safe-v1 ]
}

_luoshu_atomic_tree_visible() {
    _lsatv_source="$1"
    _lsatv_target="$2"
    _lsatv_mode="${3:-overlay}"
    _lsatv_state=$(_luoshu_self_state_root)
    _lsatv_files="$_lsatv_state/verify-files.$$"
    _lsatv_seen="$_lsatv_state/verify-targets.$$"
    _lsatv_failed=0
    _lsatv_total=0
    _lsatv_partial=0
    case "$_lsatv_source" in
        */fonts) [ "$_lsatv_mode" != bind ] || ! _luoshu_atomic_partial_fonts_enabled || _lsatv_partial=1 ;;
    esac
    mkdir -p "$_lsatv_state" 2>/dev/null || return 1
    : > "$_lsatv_seen" 2>/dev/null || return 1
    if [ "$_lsatv_mode" = bind ]; then
        _luoshu_atomic_bind_file_order "$_lsatv_source" "$_lsatv_target" "$_lsatv_files" || return 1
    else
        find "$_lsatv_source" -type f 2>/dev/null > "$_lsatv_files" || return 1
    fi
    while IFS= read -r _lsatv_src; do
        [ -n "$_lsatv_src" ] || continue
        _lsatv_rel=${_lsatv_src#$_lsatv_source/}
        _lsatv_dst="$_lsatv_target/$_lsatv_rel"
        if [ ! -f "$_lsatv_dst" ]; then
            [ "$_lsatv_partial" != 1 ] || continue
            if _luoshu_atomic_missing_target_allowed "$_lsatv_rel" "$_lsatv_mode"; then
                continue
            fi
            _luoshu_self_log "负载可见性失败：mode=$_lsatv_mode reason=missing-or-inaccessible source=$_lsatv_src target=$_lsatv_dst"
            _luoshu_mount_diag_log "verify missing-or-inaccessible source=$_lsatv_src target=$_lsatv_dst"
            ls -lZ "$_lsatv_src" "$_lsatv_dst" >> "$(_luoshu_mount_diag_file)" 2>&1
            _lsatv_failed=1
            break
        fi
        if [ "$_lsatv_mode" = bind ]; then
            _lsatv_dst=$(_luoshu_atomic_real_target "$_lsatv_dst")
            if _luoshu_atomic_target_seen "$_lsatv_seen" "$_lsatv_dst"; then
                continue
            fi
            printf '%s\n' "$_lsatv_dst" >> "$_lsatv_seen" 2>/dev/null || {
                _lsatv_failed=1
                break
            }
        fi
        _lsatv_total=$((_lsatv_total + 1))
        _luoshu_atomic_files_equal "$_lsatv_src" "$_lsatv_dst" || {
            _luoshu_self_log "负载可见性失败：mode=$_lsatv_mode reason=content-or-read-mismatch source=$_lsatv_src target=$_lsatv_dst"
            _luoshu_mount_diag_log "verify mismatch source=$_lsatv_src target=$_lsatv_dst source_size=$(_luoshu_atomic_file_size "$_lsatv_src") target_size=$(_luoshu_atomic_file_size "$_lsatv_dst") source_fp=$(_luoshu_atomic_quick_fingerprint "$_lsatv_src") target_fp=$(_luoshu_atomic_quick_fingerprint "$_lsatv_dst")"
            ls -lZ "$_lsatv_src" "$_lsatv_dst" >> "$(_luoshu_mount_diag_file)" 2>&1
            if [ "$_lsatv_partial" = 1 ]; then
                _lsatv_total=$((_lsatv_total - 1))
                continue
            fi
            _lsatv_failed=1
            break
        }
    done < "$_lsatv_files"
    rm -f "$_lsatv_files" "$_lsatv_seen" 2>/dev/null || true
    [ "$_lsatv_total" -gt 0 ] 2>/dev/null && [ "$_lsatv_failed" -eq 0 ]
}

_luoshu_atomic_bind_tree() {
    _lsabt_source="$1"
    _lsabt_target="$2"
    _lsabt_state=$(_luoshu_self_state_root)
    _lsabt_files="$_lsabt_state/bind-files.$$"
    _lsabt_seen="$_lsabt_state/bind-targets.$$"
    _lsabt_expected=0
    _lsabt_mounted=0
    _lsabt_failed=0
    mkdir -p "$_lsabt_state" 2>/dev/null || return 1
    : > "$_lsabt_seen" 2>/dev/null || return 1
    _luoshu_atomic_bind_file_order "$_lsabt_source" "$_lsabt_target" "$_lsabt_files" || return 1
    _luoshu_atomic_bind_conflict_detail "$_lsabt_source" "$_lsabt_target"
    _lsabt_preflight_rc=$?
    if [ "$_lsabt_preflight_rc" -ne 0 ]; then
        if [ "$_lsabt_preflight_rc" -eq 1 ]; then
            _lsabt_message="逐文件 bind 已在挂载前停止：多个字体路径指向同一 ROM 文件但内容不同；${_LUOSHU_ATOMIC_BIND_CONFLICT_DETAIL:-details-unavailable}"
            _luoshu_self_log "$_lsabt_message"
            _lsabt_module=$(_luoshu_self_module)
            mkdir -p "$_lsabt_module/logs" 2>/dev/null || true
            printf '[%s] [SELF-MOUNT] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" \
                "$_lsabt_message" >> "$_lsabt_module/logs/mount-backend.log" 2>/dev/null || true
            rm -f "$_lsabt_files" "$_lsabt_seen" 2>/dev/null || true
            return 3
        fi
        _lsabt_message="逐文件 bind 预检无法确认字体别名冲突：${_LUOSHU_ATOMIC_BIND_CONFLICT_DETAIL:-comparison-unavailable}"
        _luoshu_self_log "$_lsabt_message"
        _lsabt_module=$(_luoshu_self_module)
        mkdir -p "$_lsabt_module/logs" 2>/dev/null || true
        printf '[%s] [SELF-MOUNT] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" \
            "$_lsabt_message" >> "$_lsabt_module/logs/mount-backend.log" 2>/dev/null || true
        rm -f "$_lsabt_files" "$_lsabt_seen" 2>/dev/null || true
        return 4
    fi
    while IFS= read -r _lsabt_src; do
        [ -n "$_lsabt_src" ] || continue
        _lsabt_rel=${_lsabt_src#$_lsabt_source/}
        _lsabt_dst="$_lsabt_target/$_lsabt_rel"
        if [ ! -f "$_lsabt_dst" ] && \
           _luoshu_atomic_missing_target_allowed "$_lsabt_rel" bind; then
            continue
        fi
        [ -f "$_lsabt_dst" ] || {
            _lsabt_failed=1
            break
        }
        _lsabt_dst=$(_luoshu_atomic_real_target "$_lsabt_dst")
        if _luoshu_atomic_target_seen "$_lsabt_seen" "$_lsabt_dst"; then
            continue
        fi
        _lsabt_expected=$((_lsabt_expected + 1))
        printf '%s\n' "$_lsabt_dst" >> "$_lsabt_seen" 2>/dev/null || {
            _lsabt_failed=1
            break
        }
        if _luoshu_mount_observe file-bind _luoshu_mount_cmd -o bind "$_lsabt_src" "$_lsabt_dst"; then
            printf '%s\n' "$_lsabt_dst" >> "$_lsme_mount_list" 2>/dev/null || {
                _luoshu_mount_observe file-bind-journal-cleanup _luoshu_umount_cmd "$_lsabt_dst"
                _lsabt_failed=1
                break
            }
            _lsabt_mounted=$((_lsabt_mounted + 1))
        else
            if [ "${LUOSHU_PARTIAL_FONT_MOUNT:-0}" = 1 ]; then
                _luoshu_font_mount_warning "$_lsabt_dst 无法挂载，保留原厂字体"
                continue
            fi
            _lsabt_failed=1
            break
        fi
    done < "$_lsabt_files"
    rm -f "$_lsabt_files" "$_lsabt_seen" 2>/dev/null || true
    [ "$_lsabt_failed" -eq 0 ] || return 1
    [ "${LUOSHU_PARTIAL_FONT_MOUNT:-0}" = 1 ] || [ "$_lsabt_mounted" -eq "$_lsabt_expected" ] || return 1
    # Distinguish an actual bind failure from an additive-only component whose
    # files have no pre-existing ROM inode. The caller may skip the latter for
    # a component without any proven font cannot count as applied.
    [ "$_lsabt_mounted" -gt 0 ] 2>/dev/null || return 2
    return 0
}

_luoshu_atomic_mountinfo_target_present() {
    _lsamtp_target="$1"
    _lsamtp_mountinfo="${LUOSHU_PID1_MOUNTINFO:-/proc/1/mountinfo}"
    [ -r "$_lsamtp_mountinfo" ] || return 1
    awk -v target="$_lsamtp_target" '$5 == target { found=1 } END { exit !found }' \
        "$_lsamtp_mountinfo" 2>/dev/null
}

_luoshu_atomic_mountinfo_owned_target() {
    _lsamot_target="$1"
    _lsamot_module=$(_luoshu_self_module)
    _lsamot_state=$(_luoshu_self_state_root)
    _lsamot_payload_relative="${_lsamot_module#/data}"
    _lsamot_mountinfo="${LUOSHU_PID1_MOUNTINFO:-/proc/1/mountinfo}"
    [ -r "$_lsamot_mountinfo" ] || return 1
    case "$_lsamot_target" in
        "$_lsamot_state"/lower/*|"$_lsamot_state"/work/overlay-*)
            awk -v target="$_lsamot_target" '$5 == target { found=1 } END { exit !found }' \
                "$_lsamot_mountinfo" 2>/dev/null
            ;;
        *)
            awk -v target="$_lsamot_target" \
                -v payload="$_lsamot_module/.luoshu-payload/" \
                -v payload_relative="$_lsamot_payload_relative/.luoshu-payload/" \
                -v live_payload="$_lsamot_module/.luoshu-state/cache/live/" \
                -v live_relative="$_lsamot_payload_relative/.luoshu-state/cache/live/" \
                -v mirror="$_lsamot_state/work/" \
                -v mirror_relative="${_lsamot_state#/data}/work/" \
                '$5 == target { if (index($0, payload) || index($0, payload_relative) || index($0, live_payload) || index($0, live_relative) || index($0, mirror) || index($0, mirror_relative)) found=1; else foreign=1 } END { exit !(found && !foreign) }' \
                "$_lsamot_mountinfo" 2>/dev/null
            ;;
    esac
}

_luoshu_atomic_rollback() {
    _lsar_list="$1"
    _lsar_keep="${3:-0}"
    case "$_lsar_keep" in ''|*[!0-9]*) return 1 ;; esac
    _lsar_mountinfo="${LUOSHU_PID1_MOUNTINFO:-/proc/1/mountinfo}"
    [ -r "$_lsar_mountinfo" ] || return 1
    _lsar_state=$(_luoshu_self_state_root)
    if [ ! -e "$_lsar_list" ]; then
        _luoshu_self_state_mounts_remain && return 1
        return 0
    fi
    _lsar_remaining="${_lsar_list}.remaining.$$"
    _lsar_reverse="${_lsar_list}.reverse.$$"
    _lsar_prefix="${_lsar_list}.prefix.$$"
    awk -v keep="$_lsar_keep" 'NR <= keep { print } END { if (NR < keep) exit 1 }' "$_lsar_list" > "$_lsar_prefix" || return 1
    : > "$_lsar_remaining" || return 1
    if [ -s "$_lsar_list" ]; then
        awk -v keep="$_lsar_keep" 'NR > keep { item[NR]=$0 } END { for (i=NR; i>keep; i--) print item[i] }' "$_lsar_list" > "$_lsar_reverse" || return 1
        while IFS= read -r _lsar_target; do
            [ -n "$_lsar_target" ] || continue
            # An interrupted rollback can leave already detached targets in the
            # journal. Never unmount a replacement owned by another module.
            if ! _luoshu_atomic_mountinfo_owned_target "$_lsar_target"; then
                [ -r "$_lsar_mountinfo" ] || return 1
                if _luoshu_atomic_mountinfo_target_present "$_lsar_target"; then
                    _luoshu_self_log "回滚保留未确认归属的目标，不卸载其他模块：$_lsar_target"
                    printf '%s\n' "$_lsar_target" >> "$_lsar_remaining" || return 1
                fi
                continue
            fi
            if ! _luoshu_mount_observe rollback-unmount _luoshu_umount_cmd "$_lsar_target"; then
                if ! _luoshu_atomic_mountinfo_target_present "$_lsar_target"; then
                    continue
                fi
                _lsar_lazy_ok=0
                if [ "${LUOSHU_SELF_ALLOW_LAZY_UMOUNT:-0}" = 1 ] && \
                   _luoshu_atomic_mountinfo_owned_target "$_lsar_target"; then
                    if command -v umount >/dev/null 2>&1; then
                        _luoshu_mount_observe rollback-lazy-unmount umount -l "$_lsar_target" && _lsar_lazy_ok=1
                    elif command -v toybox >/dev/null 2>&1; then
                        _luoshu_mount_observe rollback-lazy-unmount toybox umount -l "$_lsar_target" && _lsar_lazy_ok=1
                    elif command -v busybox >/dev/null 2>&1; then
                        _luoshu_mount_observe rollback-lazy-unmount busybox umount -l "$_lsar_target" && _lsar_lazy_ok=1
                    fi
                    if [ "$_lsar_lazy_ok" = 1 ] && \
                       _luoshu_atomic_mountinfo_owned_target "$_lsar_target"; then
                        _lsar_lazy_ok=0
                    fi
                fi
                [ "$_lsar_lazy_ok" = 1 ] || printf '%s\n' "$_lsar_target" >> "$_lsar_remaining" || return 1
            fi
        done < "$_lsar_reverse"
    fi
    rm -f "$_lsar_reverse"
    _lsar_incomplete=0
    [ ! -s "$_lsar_remaining" ] || _lsar_incomplete=1
    # Replace the authoritative journal atomically. A crash before this rename
    # retains all old entries; ownership checks make repeated cleanup safe.
    cat "$_lsar_prefix" > "$_lsar_reverse" || return 1
    awk '{ item[NR]=$0 } END { for (i=NR; i>=1; i--) print item[i] }' "$_lsar_remaining" >> "$_lsar_reverse" || return 1
    chmod 0600 "$_lsar_reverse" || return 1
    mv -f "$_lsar_reverse" "$_lsar_list" || return 1
    rm -f "$_lsar_prefix" "$_lsar_remaining"
    [ "$_lsar_incomplete" = 0 ] || return 1
    # A failed independent component must not remove other components' lower
    # views or work files. All targets after this component's boundary detached.
    [ "${2:-all}" != component ] || return 0
    # Detached fonts can still be mmap'ed. Live transactions keep all work files.
    [ "${2:-all}" != detach ] && [ -z "${LUOSHU_LIVE_MOUNT_SOURCE:-}" ] || return 0
    if _luoshu_self_state_mounts_remain payload; then
        _luoshu_self_log '回滚后仍有私有负载/工作目录挂载或挂载表不可读，保留 lower/work 并标记回滚未确认'
        _luoshu_mount_diag_kernel
        return 1
    fi
    rm -rf "$_lsar_state/lower" "$_lsar_state/work" 2>/dev/null || true
}

_luoshu_atomic_verify_manifest() {
    _lsavm_manifest="${1:-$(_luoshu_atomic_manifest)}"
    [ -s "$_lsavm_manifest" ] || return 1
    while IFS='|' read -r _lsavm_source _lsavm_target _lsavm_mode; do
        [ -n "$_lsavm_source" ] && [ -n "$_lsavm_target" ] || return 1
        _lsavm_visible=$(_luoshu_atomic_pid1_target "$_lsavm_target")
        _luoshu_atomic_tree_visible "$_lsavm_source" "$_lsavm_visible" "${_lsavm_mode:-overlay}" || return 1
    done < "$_lsavm_manifest"
    return 0
}

_luoshu_atomic_verify_manifest_retry() {
    _lsavmr_manifest="$1"
    _lsavmr_limit="${LUOSHU_SELF_VERIFY_RETRIES:-3}"
    _lsavmr_delay="${LUOSHU_SELF_VERIFY_DELAY:-1}"
    case "$_lsavmr_limit" in ''|*[!0-9]*) _lsavmr_limit=3 ;; esac
    case "$_lsavmr_delay" in ''|*[!0-9]*) _lsavmr_delay=1 ;; esac
    [ "$_lsavmr_limit" -ge 1 ] 2>/dev/null || _lsavmr_limit=1
    _lsavmr_attempt=1
    while [ "$_lsavmr_attempt" -le "$_lsavmr_limit" ]; do
        _luoshu_atomic_verify_manifest "$_lsavmr_manifest" && return 0
        [ "$_lsavmr_attempt" -ge "$_lsavmr_limit" ] || \
            [ "$_lsavmr_delay" -eq 0 ] 2>/dev/null || sleep "$_lsavmr_delay"
        _lsavmr_attempt=$((_lsavmr_attempt + 1))
    done
    return 1
}

# A mount is verified only when the last transaction fully committed and every
# required payload file is visible from init's root namespace.
luoshu_mount_verify_active() {
    _lsmva_active="${1:-$(head -n1 "$LUOSHU_MOUNT_MODDIR/config/active_font.conf" 2>/dev/null)}"
    [ -n "$_lsmva_active" ] || _lsmva_active=default
    if [ "$_lsmva_active" = default ]; then
        luoshu_mount_record verified '系统默认字体无需挂载验证' '' 0 0
        return 0
    fi

    _lsmva_state=$(_luoshu_self_state_value state)
    _lsmva_manifest=$(_luoshu_atomic_manifest)
    if [ "$_lsmva_state" != mounted ]; then
        luoshu_mount_record unverified "字域自挂载未完整提交：${_lsmva_state:-missing}" '' 0 1 system '' self-mount
        return 1
    fi
    if ! _luoshu_atomic_verify_manifest "$_lsmva_manifest"; then
        _lsmva_mounted=$(_luoshu_self_state_value mounted)
        _luoshu_self_state_write failed verification "$_lsmva_mounted" pid1-visibility-mismatch
        _luoshu_self_log '自挂载验证失败：PID 1 根命名空间未读取完整字体负载'
        luoshu_mount_record unverified 'PID 1 根命名空间未读取完整字域字体负载' '' 0 1 system '' visibility
        return 1
    fi
    luoshu_mount_record verified '已提交的字体目录已通过系统主命名空间检查，应用范围以字体路由报告为准' '' 0 0 system system
    return 0
}
