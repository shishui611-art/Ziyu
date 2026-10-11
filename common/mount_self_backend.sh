#!/system/bin/sh
# LuoShu self-mount backend.
# Uses the same read-only lower-layer model as KernelSU's reference meta-overlayfs:
# module content first, captured stock tree last, and source name KSU for unified cleanup.
set +e
_lsmb_diag="${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}/common/mount_diagnostics.sh"
[ ! -f "$_lsmb_diag" ] || . "$_lsmb_diag"
unset _lsmb_diag
_lsmb_layer="${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}/common/mount_overlay_layer.sh"
[ ! -f "$_lsmb_layer" ] || . "$_lsmb_layer"
unset _lsmb_layer

luoshu_self_mount_stage_for_manager() {
    case "${1:-unknown}" in
        APatch|KernelSU|KernelSU*|SukiSU|SukiSU*)
            printf 'post-mount\n'
            ;;
        *)
            # Magisk does not provide a module post-mount hook. Unknown legacy
            # managers retain the existing early path for compatibility.
            printf 'post-fs-data\n'
            ;;
    esac
}

_luoshu_overlay_mount_dir() {
    _lsomb_source=$(_luoshu_atomic_real_target "$1")
    _lsomb_target="$2"
    _lsomb_key="$3"
    _lsomb_state=$(_luoshu_self_state_root)
    _lsomb_lower="$_lsomb_state/lower/$_lsomb_key"

    _LUOSHU_OVERLAY_FAILURE_STAGE=prepare
    _luoshu_mount_diag_environment "$_lsomb_source" "$_lsomb_target" "$_lsomb_state"
    [ -d "$_lsomb_source" ] && [ -d "$_lsomb_target" ] || {
        _LUOSHU_OVERLAY_FAILURE_STAGE=path-missing
        _luoshu_mount_diag_log "overlay path missing source=$_lsomb_source target=$_lsomb_target"
        return 1
    }
    case "$_lsomb_source:$_lsomb_lower" in
        *,*|*\\*) _LUOSHU_OVERLAY_FAILURE_STAGE=option-path-invalid; return 1 ;;
    esac
    case "$_lsomb_source" in *:*) _LUOSHU_OVERLAY_FAILURE_STAGE=option-path-invalid; return 1 ;; esac
    case "$_lsomb_lower" in *:*) _LUOSHU_OVERLAY_FAILURE_STAGE=option-path-invalid; return 1 ;; esac
    # Record and isolate the lower immediately, including when every overlay
    # attempt fails. The transaction owns cleanup, never rm a live bound tree.
    _luoshu_capture_lower_dir "$_lsomb_target" "$_lsomb_key" || {
        _LUOSHU_OVERLAY_FAILURE_STAGE=lower-prepare-failed
        return 1
    }
    _LUOSHU_OVERLAY_FAILURE_STAGE=mount-rejected
    _lsomb_base="ro,lowerdir=$_lsomb_source:$_lsomb_lower"
    _lsomb_compat=''
    for _lsomb_param in index metacopy nfs_export; do
        [ ! -e "/sys/module/overlay/parameters/$_lsomb_param" ] || \
            _lsomb_compat="$_lsomb_compat,$_lsomb_param=off"
    done
    if [ -e /sys/module/overlay/parameters/redirect_dir ] || \
       [ -e /sys/module/overlay/parameters/redirect_always_follow ]; then
        _lsomb_compat="$_lsomb_compat,redirect_dir=nofollow"
    fi
    # Android's older override_creds=off interface is identified by its module
    # parameter. Do not send this option to upstream kernels with a different API.
    _lsomb_profiles='default'
    [ -z "$_lsomb_compat" ] || _lsomb_profiles="compat $_lsomb_profiles"
    if [ -e /sys/module/overlay/parameters/override_creds ]; then
        _lsomb_profiles="android-default $_lsomb_profiles"
        [ -z "$_lsomb_compat" ] || _lsomb_profiles="android-compat $_lsomb_profiles"
    fi
    _lsomb_casefold_before=$(_luoshu_overlay_casefold_message "$_lsomb_source")
    if _luoshu_overlay_try_profiles; then
        return 0
    fi
    _lsomb_casefold_after=$(_luoshu_overlay_casefold_message "$_lsomb_source")
    if [ -n "$_lsomb_casefold_after" ] && [ "$_lsomb_casefold_after" != "$_lsomb_casefold_before" ]; then
        _luoshu_self_log '内核拒绝 F2FS 字体下层，正在准备磁盘兼容层并重试 OverlayFS'
        _LUOSHU_OVERLAY_FAILURE_STAGE=ext4-layer-prepare-failed
        _luoshu_overlay_prepare_ext4_layer "$_lsomb_source" "$_lsomb_target" "$_lsomb_key" || return 1
        _lsomb_base="ro,lowerdir=$_LUOSHU_OVERLAY_COMPAT_SOURCE:$_lsomb_lower"
        _LUOSHU_OVERLAY_FAILURE_STAGE=mount-rejected
        _luoshu_overlay_try_profiles && return 0
    fi
    _luoshu_mount_diag_kernel
    _luoshu_self_log "OverlayFS 参数及兼容层尝试失败：target=$_lsomb_target；详见 logs/mount-diagnostics.log"
    return 1
}

_luoshu_overlay_try_profiles() {
    for _lsomb_profile in $_lsomb_profiles; do
        _lsomb_options="$_lsomb_base"
        case "$_lsomb_profile" in *compat) _lsomb_options="$_lsomb_options$_lsomb_compat" ;; esac
        case "$_lsomb_profile" in android-*) _lsomb_options="$_lsomb_options,override_creds=off" ;; esac
        if _luoshu_mount_observe "overlay-$_lsomb_profile" _luoshu_mount_cmd \
            -t overlay KSU -o "$_lsomb_options" "$_lsomb_target"; then
            _LUOSHU_OVERLAY_FAILURE_STAGE=none
            _luoshu_self_log "OverlayFS 已挂载：target=$_lsomb_target profile=$_lsomb_profile；等待完整负载及 PID 1 验证"
            return 0
        fi
    done
    return 1
}

# OverlayFS is not available on every KernelSU/HyperOS combination. The atomic
# runtime then falls back to per-file bind mounts, but the stock directory must
# still remain reachable for later inventory scans and stock-metric alignment.
# Capture it before installing any file binds and detach its propagation so the
# later child mounts cannot leak back into this stock view.
_luoshu_capture_lower_dir() {
    _lscld_target="$1"
    _lscld_key="$2"
    _lscld_state=$(_luoshu_self_state_root)
    _lscld_lower="$_lscld_state/lower/$_lscld_key"

    [ -d "$_lscld_target" ] && [ -n "${_lsme_mount_list:-}" ] || return 1
    [ ! -L "$_lscld_lower" ] || return 1
    if _luoshu_self_target_mounted "$_lscld_lower"; then
        if grep -Fxq "$_lscld_lower" "$_lsme_mount_list" 2>/dev/null; then
            _luoshu_mount_diag_log "reuse journaled lower=$_lscld_lower"
            return 0
        fi
        _luoshu_mount_observe lower-stale-unmount _luoshu_umount_cmd "$_lscld_lower" || return 1
    else
        _lscld_presence_rc=$?
        [ "$_lscld_presence_rc" -eq 1 ] || return 1
    fi
    rm -rf "$_lscld_lower" 2>/dev/null || return 1
    mkdir -p "$_lscld_lower" 2>/dev/null || return 1
    _luoshu_mount_observe lower-bind _luoshu_mount_cmd -o bind "$_lscld_target" "$_lscld_lower" || return 1
    printf '%s\n' "$_lscld_lower" >> "$_lsme_mount_list" 2>/dev/null || {
        _luoshu_self_log "无法登记 lower 挂载：$_lscld_lower"
        _luoshu_mount_observe lower-journal-cleanup _luoshu_umount_cmd "$_lscld_lower"
        return 1
    }
    # Android toybox parses "private", while util-linux also has --make-private.
    # A propagation change ignores the source/type at the kernel boundary.
    _luoshu_mount_observe lower-private _luoshu_mount_cmd -o private -t none none "$_lscld_lower" || \
        _luoshu_mount_observe lower-private-util-linux _luoshu_mount_cmd --make-private "$_lscld_lower" || return 1
    awk -v target="$_lscld_lower" '$5==target {found=1; for(i=7;i<=NF && $i!="-";i++) if ($i ~ /^(shared|master|propagate_from):/) bad=1} END {exit !(found && !bad)}' \
        "${LUOSHU_SELF_MOUNTINFO:-/proc/self/mountinfo}" || {
            _luoshu_self_log "lower 挂载传播隔离未通过验证：$_lscld_lower"
            return 1
        }
    return 0
}

# Copy the stock font directory before replacing its logical entries. Unlike a
# file bind onto a ROM symlink, a directory bind keeps aliases as distinct files.
# No provider CLI or writable system partition is involved. Work stays on /data,
# not tmpfs, so large fonts do not consume their full size in RAM.
_luoshu_mirror_context() {
    _lsmc_reference="$1"
    _lsmc_dest="$2"
    if [ -L "$_lsmc_reference" ]; then
        _lsmc_reference=$(_luoshu_atomic_real_target "$_lsmc_reference")
    fi
    if chcon --reference="$_lsmc_reference" "$_lsmc_dest" >/dev/null 2>&1; then
        return 0
    fi
    _lsmc_label=$(ls -Zd "$_lsmc_reference" 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i ~ /^[^:]+:[^:]+:[^:]+:/) {print $i; exit}}')
    if [ -n "$_lsmc_label" ] && chcon "$_lsmc_label" "$_lsmc_dest" >/dev/null 2>&1; then
        return 0
    fi
    # Under Enforcing, refusing the transaction is safer than delivering fonts
    # that init can hash but Android applications cannot open.
    [ "$(getenforce 2>/dev/null)" = Disabled ]
}

_luoshu_mirror_mount_dir() {
    _lsmmd_source="$1"
    _lsmmd_target="$2"
    _lsmmd_key="$3"
    [ "${_lsmmd_target##*/}" = fonts ] || return 1
    _lsmmd_state=$(_luoshu_self_state_root)
    _lsmmd_lower="$_lsmmd_state/lower/$_lsmmd_key"
    _lsmmd_mirror="$_lsmmd_state/work/$_lsmmd_key"
    _lsmmd_files="$_lsmmd_state/mirror-files.$$"
    [ -d "$_lsmmd_lower" ] || return 1
    mkdir -p "$_lsmmd_mirror" || return 1
    _luoshu_self_log "正在复制原厂字体目录并准备独立别名：$_lsmmd_target（需要 /data 可用空间）"
    cp -a "$_lsmmd_lower/." "$_lsmmd_mirror/" || {
        _luoshu_self_log "目录镜像复制原厂字体失败：$_lsmmd_target；请检查 /data 剩余空间"
        return 1
    }
    find "$_lsmmd_source" -type f > "$_lsmmd_files" || return 1
    while IFS= read -r _lsmmd_src; do
        _lsmmd_rel=${_lsmmd_src#$_lsmmd_source/}
        _lsmmd_dst="$_lsmmd_mirror/$_lsmmd_rel"
        _lsmmd_parent=${_lsmmd_dst%/*}
        while [ "$_lsmmd_parent" != "$_lsmmd_mirror" ]; do
            [ ! -L "$_lsmmd_parent" ] || return 1
            _lsmmd_parent=${_lsmmd_parent%/*}
        done
        mkdir -p "${_lsmmd_dst%/*}" || return 1
        # Unlink aliases first: cp must never follow a copied absolute ROM link.
        [ ! -d "$_lsmmd_dst" ] || return 1
        rm -f "$_lsmmd_dst" || return 1
        cp "$_lsmmd_src" "$_lsmmd_dst" || {
            _luoshu_self_log "目录镜像复制字体负载失败：$_lsmmd_rel；请检查 /data 剩余空间"
            return 1
        }
        chmod 0644 "$_lsmmd_dst" || return 1
    done < "$_lsmmd_files"
    # Use the actual ROM contexts, including vendor-specific font file labels.
    _lsmmd_sample=$(find "$_lsmmd_lower" -type f -print -quit)
    [ -n "$_lsmmd_sample" ] || return 1
    find "$_lsmmd_mirror" -type f -o -type d > "$_lsmmd_files" || return 1
    while IFS= read -r _lsmmd_entry; do
        _lsmmd_rel=${_lsmmd_entry#$_lsmmd_mirror/}
        if [ -d "$_lsmmd_entry" ]; then
            chmod 0755 "$_lsmmd_entry" || return 1
            _lsmmd_ref="$_lsmmd_lower/$_lsmmd_rel"
            [ -d "$_lsmmd_ref" ] || _lsmmd_ref="$_lsmmd_lower"
        else
            _lsmmd_ref="$_lsmmd_lower/$_lsmmd_rel"
            [ -f "$_lsmmd_ref" ] || _lsmmd_ref="$_lsmmd_sample"
        fi
        _luoshu_mirror_context "$_lsmmd_ref" "$_lsmmd_entry" || {
            _luoshu_self_log "目录镜像无法保留 SELinux 字体上下文：$_lsmmd_rel"
            return 1
        }
    done < "$_lsmmd_files"
    rm -f "$_lsmmd_files"
    _luoshu_atomic_tree_visible "$_lsmmd_source" "$_lsmmd_mirror" mirror || return 1
    _luoshu_mount_observe mirror-bind _luoshu_mount_cmd -o bind "$_lsmmd_mirror" "$_lsmmd_target" || return 1
    printf '%s\n' "$_lsmmd_target" >> "$_lsme_mount_list" || {
        _luoshu_mount_observe mirror-journal-cleanup _luoshu_umount_cmd "$_lsmmd_target"
        return 1
    }
    # Toybox resolves remounts through /proc/mounts, which describes a bind
    # using its backing device. BusyBox can remount the explicit bind paths.
    if [ -z "${LUOSHU_SELF_MOUNT_COMMAND:-}" ] && _luoshu_overlay_select_loop_mounter; then
        _luoshu_mount_observe mirror-readonly "$_LUOSHU_OVERLAY_LOOP_MOUNTER" mount \
            -o remount,bind,ro "$_lsmmd_mirror" "$_lsmmd_target" || return 1
    else
        _luoshu_mount_observe mirror-readonly _luoshu_mount_cmd -o remount,bind,ro "$_lsmmd_target" || return 1
    fi
    awk -v target="$_lsmmd_target" '$5==target {ro=($6 ~ /(^|,)ro(,|$)/)} END {exit !ro}' \
        "${LUOSHU_SELF_MOUNTINFO:-/proc/self/mountinfo}" || return 1
    _luoshu_self_log "目录镜像 bind 已完成：$_lsmmd_target；字体别名各自保留负载内容"
}
