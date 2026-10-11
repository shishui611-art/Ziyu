#!/system/bin/sh
# Final atomic self-mount implementation for the private LuoShu payload.
# Loaded after mount_self_atomic.sh and font_runtime_policy.sh.
set +e

type _lfrp_payload_root >/dev/null 2>&1 || return 0 2>/dev/null || exit 0
type _luoshu_atomic_manifest >/dev/null 2>&1 || return 0 2>/dev/null || exit 0

# All directories write immediately to one journal. A component boundary limits
# rollback to the current directory while preserving earlier proven mounts.
_luoshu_font_component_mount() {
    _lsme_mode=overlay
    [ -d "$_lsme_target" ] || { _lsme_failed="$_lsme_partition/$_lsme_subdir-target-missing"; return 1; }
    if [ "$_lsme_requested_mode" != magic ]; then
        if _luoshu_overlay_mount_dir "$_lsme_source" "$_lsme_target" "${_lsme_partition}-${_lsme_subdir}"; then
            printf '%s\n' "$_lsme_target" >> "$_lsme_mount_list" || {
                _luoshu_mount_observe overlay-journal-cleanup _luoshu_umount_cmd "$_lsme_target"
                _lsme_failed=overlay-journal-failed
                return 1
            }
            _luoshu_atomic_tree_visible "$_lsme_source" "$_lsme_target" overlay || { _lsme_failed=overlay-visibility-mismatch; return 1; }
            return 0
        fi
        if [ "$_lsme_requested_mode" = overlayfs ]; then
            _lsme_failed="$_lsme_partition/$_lsme_subdir-overlayfs-${_LUOSHU_OVERLAY_FAILURE_STAGE:-unavailable}"
            return 1
        fi
        case "${_LUOSHU_OVERLAY_FAILURE_STAGE:-mount-rejected}" in
            mount-rejected|ext4-layer-*) ;;
            *) _lsme_failed="$_lsme_partition/$_lsme_subdir-overlayfs-${_LUOSHU_OVERLAY_FAILURE_STAGE:-prepare-failed}"; return 1 ;;
        esac
        # self_mount permits a bind strategy; explicit OverlayFS never switches.
    fi
    _lsme_mode=bind
    _luoshu_capture_lower_dir "$_lsme_target" "${_lsme_partition}-${_lsme_subdir}" || {
        _lsme_failed="$_lsme_partition/$_lsme_subdir-lower-prepare-failed"; return 1;
    }
    _luoshu_atomic_bind_tree "$_lsme_source" "$_lsme_target"
    _lsme_bind_rc=$?
    case "$_lsme_bind_rc" in
        0) ;;
        2) _lsme_failed="$_lsme_partition/$_lsme_subdir-no-existing-bind-target"; return 2 ;;
        3)
            if _luoshu_mirror_mount_dir "$_lsme_source" "$_lsme_target" "${_lsme_partition}-${_lsme_subdir}"; then
                _lsme_mode=mirror
            else
                _lsme_failed="$_lsme_partition/$_lsme_subdir-directory-mirror-failed"; return 1
            fi ;;
        *) _lsme_failed="$_lsme_partition/$_lsme_subdir-bind-incomplete"; return 1 ;;
    esac
    _luoshu_atomic_tree_visible "$_lsme_source" "$_lsme_target" "$_lsme_mode" || {
        _lsme_failed="$_lsme_partition/$_lsme_subdir-visibility-mismatch"; return 1;
    }
}

luoshu_self_mount_ensure() {
    _lsme_module=$(_luoshu_self_module)
    _lsme_requested_mode="${LUOSHU_SELF_MOUNT_MODE:-self_mount}"
    case "$_lsme_requested_mode" in magic|overlayfs|self_mount) ;; *) _lsme_requested_mode=self_mount ;; esac
    [ ! -f "$_lsme_module/common/skip_mount_ownership.sh" ] || . "$_lsme_module/common/skip_mount_ownership.sh"
    if type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$_lsme_module"; then
        return 90
    fi
    _lsme_payload=$(_lfrp_payload_root)
    _lsme_live_existing=0
    _lsme_active=$(head -n1 "$_lsme_module/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    if [ -n "${LUOSHU_LIVE_MOUNT_SOURCE:-}" ]; then
        _lsme_payload="$LUOSHU_LIVE_MOUNT_SOURCE"
        _lsme_active="$LUOSHU_MOUNT_ACTIVE_FONT"
    elif type ziyu_live_current >/dev/null 2>&1 && ziyu_live_current; then
        _lsme_payload=$(ziyu_live_source)
        _lsme_active=$(ziyu_live_font)
        _lsme_live_existing=1
    elif [ -e "$_lsme_module/config/font-live.conf" ] && \
         [ "$(ziyu_live_value "$_lsme_module/config/font-live.conf" boot_id)" = "$(cat /proc/sys/kernel/random/boot_id)" ]; then
        _luoshu_self_log '当前热挂载指针无法确认，拒绝改用旧规范负载重新挂载'
        return 1
    fi
    [ -n "$_lsme_active" ] || _lsme_active=default
    LUOSHU_PARTIAL_FONT_MOUNT=0
    _luoshu_atomic_partial_fonts_enabled && LUOSHU_PARTIAL_FONT_MOUNT=1
    export LUOSHU_PARTIAL_FONT_MOUNT
    _lsme_state_root=$(_luoshu_self_state_root)
    _lsme_mount_list="$_lsme_state_root/mounts.list"
    _lsme_manifest=$(_luoshu_atomic_manifest)
    _lsme_manifest_temp="${_lsme_manifest}.tmp.$$"
    mkdir -p "$_lsme_state_root" "$_lsme_module/config" 2>/dev/null || return 1
    _lsme_same_boot=0
    _luoshu_atomic_prepare_boot_state "$_lsme_mount_list"
    _lsme_prepare_rc=$?
    case "$_lsme_prepare_rc" in
        0) _lsme_same_boot=1 ;;
        1) ;;
        *) _luoshu_self_state_write failed rollback '' boot-state-cleanup-unconfirmed; return 1 ;;
    esac

    if [ "$_lsme_active" = default ]; then
        if [ "$_lsme_same_boot" -eq 1 ]; then
            _luoshu_atomic_rollback "$_lsme_mount_list" || return 1
        fi
        : > "$_lsme_mount_list" 2>/dev/null || true
        rm -f "$_lsme_manifest" "$_lsme_manifest_temp" 2>/dev/null || true
        _luoshu_self_state_write idle none '' ''
        return 0
    fi

    if [ "$_lsme_same_boot" -eq 1 ] && \
       [ "$(_luoshu_self_state_value state)" = mounted ] && \
       _luoshu_atomic_verify_manifest "$_lsme_manifest"; then
        _luoshu_self_log '私有字体负载已完整挂载，跳过重复事务'
        return 0
    fi

    # Runtime repair must also use a new work directory. A boot/service hook
    # cannot reuse files that Android processes may still have mapped.
    if [ "$_lsme_live_existing" = 1 ]; then
        _luoshu_self_log '当前热挂载复核未通过，保留工作文件并交由事务恢复或用户重启'
        return 1
    fi

    if [ "$_lsme_same_boot" -eq 1 ]; then
        _luoshu_atomic_rollback "$_lsme_mount_list" "${LUOSHU_LIVE_MOUNT_SOURCE:+detach}" || return 1
    fi
    : > "$_lsme_mount_list" 2>/dev/null || return 1
    : > "$_lsme_manifest_temp" 2>/dev/null || return 1
    _luoshu_self_state_write preparing "$_lsme_requested_mode" '' '' || return 1
    _lsme_mounted=''
    _lsme_failed=''
    _lsme_component_count=0
    _lsme_bind_count=0
    _lsme_mirror_count=0
    _lsme_any_fonts_ok=0

    printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" > "$_lsme_module/config/font-mount-warnings.conf"
    for _lsme_partition in $(_lfrp_partitions); do
        for _lsme_subdir in fonts etc; do
            _lsme_source="$_lsme_payload/$_lsme_partition/$_lsme_subdir"
            [ -d "$_lsme_source" ] && find "$_lsme_source" -type f -print -quit 2>/dev/null | grep -q . || continue
            _lsme_failed=''
            _lsme_component_start=$(awk 'END { print NR+0 }' "$_lsme_mount_list") || {
                _lsme_failed=component-journal-unavailable; break;
            }
            if _lsme_root=$(_luoshu_partition_root "$_lsme_partition"); then
                _lsme_target="$_lsme_root/$_lsme_subdir"
                _luoshu_font_component_mount
                _lsme_component_rc=$?
                if [ "$_lsme_component_rc" -eq 2 ] && [ "$_lsme_subdir" = etc ]; then
                    if _luoshu_atomic_rollback "$_lsme_mount_list" component "$_lsme_component_start"; then
                        rm -rf "$_lsme_state_root/lower/${_lsme_partition}-${_lsme_subdir}" 2>/dev/null || true
                        _luoshu_self_log "跳过没有现存 ROM 文件目标的附加 system/etc 负载：$_lsme_partition/$_lsme_subdir"
                        _lsme_failed=''
                        continue
                    fi
                    _lsme_failed="$_lsme_partition/$_lsme_subdir-skip-rollback-failed"
                fi
            else
                _lsme_failed="$_lsme_partition/root-unavailable"
            fi
            if [ -n "$_lsme_failed" ]; then
                if ! _luoshu_atomic_rollback "$_lsme_mount_list" component "$_lsme_component_start"; then
                    _lsme_failed="$_lsme_failed;component-rollback-failed"
                    break
                fi
                if [ "$LUOSHU_PARTIAL_FONT_MOUNT" = 1 ] && [ "$_lsme_subdir" = fonts ]; then
                    _luoshu_font_mount_warning "$_lsme_partition/fonts 未应用：$_lsme_failed"
                    _lsme_failed=''
                    continue
                fi
                break
            fi
            printf '%s|%s|%s\n' "$_lsme_source" "$_lsme_target" "$_lsme_mode" >> "$_lsme_manifest_temp" || {
                _lsme_failed="$_lsme_partition/$_lsme_subdir-manifest-failed"; break;
            }
            _lsme_component_count=$((_lsme_component_count + 1))
            case "$_lsme_mode" in bind) _lsme_bind_count=$((_lsme_bind_count + 1)) ;; mirror) _lsme_mirror_count=$((_lsme_mirror_count + 1)) ;; esac
            _lsme_mounted="${_lsme_mounted}${_lsme_mounted:+,}${_lsme_partition}/${_lsme_subdir}:${_lsme_mode}"
            [ "$_lsme_subdir" != fonts ] || _lsme_any_fonts_ok=1
        done
        [ -z "$_lsme_failed" ] || break
    done

    [ "$_lsme_component_count" -gt 0 ] 2>/dev/null || _lsme_failed="${_lsme_failed:-payload-empty}"
    [ "$_lsme_any_fonts_ok" -eq 1 ] 2>/dev/null || _lsme_failed="${_lsme_failed:-font-partition-required}"
    if [ -z "$_lsme_failed" ]; then
        _luoshu_atomic_verify_manifest_retry "$_lsme_manifest_temp" || _lsme_failed=pid1-visibility-mismatch
    fi

    if [ -n "$_lsme_failed" ]; then
        if ! _luoshu_atomic_rollback "$_lsme_mount_list"; then
            _luoshu_self_state_write failed rollback "$_lsme_mounted" "$_lsme_failed;rollback-failed"
            return 1
        fi
        rm -f "$_lsme_manifest" "$_lsme_manifest_temp" 2>/dev/null || true
        _luoshu_self_state_write failed rollback "$_lsme_mounted" "$_lsme_failed"
        _luoshu_self_log "私有字体自挂载事务失败并已完整回滚：failed=$_lsme_failed mounted=$_lsme_mounted"
        return 1
    fi

    mv -f "$_lsme_manifest_temp" "$_lsme_manifest" 2>/dev/null || {
        if ! _luoshu_atomic_rollback "$_lsme_mount_list"; then
            _luoshu_self_state_write failed rollback "$_lsme_mounted" 'manifest-commit-failed;rollback-failed'
            return 1
        fi
        rm -f "$_lsme_manifest_temp" 2>/dev/null || true
        _luoshu_self_state_write failed rollback "$_lsme_mounted" manifest-commit-failed
        return 1
    }
    chmod 0600 "$_lsme_manifest" 2>/dev/null || true
    if [ "$_lsme_mirror_count" -gt 0 ]; then
        _lsme_backend=self-directory-mirror
    elif [ "$_lsme_requested_mode" = magic ]; then
        _lsme_backend=self-magic-bind
    elif [ "$_lsme_bind_count" -eq 0 ]; then
        _lsme_backend=self-overlay
    else
        _lsme_backend=self-overlay-bind
    fi
    _luoshu_self_state_write mounted "$_lsme_backend" "$_lsme_mounted" ''
    _luoshu_self_log "私有字体自挂载原子事务成功：mounted=$_lsme_mounted"
    return 0
}
