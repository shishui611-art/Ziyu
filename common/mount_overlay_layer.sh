#!/system/bin/sh
# Disk-backed lower layer for kernels which reject encoding-capable F2FS.
# Called only after a fresh, source-specific OverlayFS kernel rejection.

_luoshu_overlay_casefold_message() {
    dmesg 2>/dev/null | grep -F "case-insensitive capable filesystem on $1 not supported" | tail -n 1
}

# Toybox resolves the absolute path before its 64-byte loop name check.
# BusyBox opens the full path and truncates only the descriptive loop name.
_luoshu_overlay_select_loop_mounter() {
    _LUOSHU_OVERLAY_LOOP_MOUNTER=''
    for _lolm_candidate in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox \
        /data/adb/ap/bin/busybox "$(command -v busybox 2>/dev/null)"; do
        [ -n "$_lolm_candidate" ] && [ -x "$_lolm_candidate" ] || continue
        case "$_lolm_candidate" in /*) ;; *) continue ;; esac
        "$_lolm_candidate" mount --help >/dev/null 2>&1 || continue
        _LUOSHU_OVERLAY_LOOP_MOUNTER="$_lolm_candidate"
        return 0
    done
    return 1
}

_luoshu_overlay_mount_image() {
    if [ "${#1}" -ge 64 ]; then
        _luoshu_overlay_select_loop_mounter || {
            _luoshu_self_log 'OverlayFS 镜像路径超过 Toybox loop 名称限制，缺少可处理长路径的 BusyBox mount'
            return 1
        }
        _luoshu_mount_diag_log "overlay loop helper=$_LUOSHU_OVERLAY_LOOP_MOUNTER image=$1 path_bytes=${#1}"
        _luoshu_mount_observe overlay-layer-mount "$_LUOSHU_OVERLAY_LOOP_MOUNTER" mount \
            -t ext4 -o loop,rw,nodev,nosuid,noexec "$1" "$2"
    else
        _luoshu_mount_observe overlay-layer-mount _luoshu_mount_cmd -t ext4 \
            -o loop,rw,nodev,nosuid,noexec "$1" "$2"
    fi
}

_luoshu_overlay_select_formatter() {
    _LUOSHU_OVERLAY_FORMATTER=''
    # KernelSU boot hooks use BusyBox standalone ash: a bare mke2fs resolves
    # to its limited applet even when PATH includes Android's e2fsprogs binary.
    # Absolute paths bypass that applet resolution.
    for _loef_candidate in /system/bin/mke2fs /system/bin/mkfs.ext4 \
        /system_ext/bin/mke2fs /vendor/bin/mke2fs /product/bin/mke2fs; do
        [ -x "$_loef_candidate" ] || continue
        _loef_version=$("$_loef_candidate" -V 2>&1)
        _loef_rc=$?
        _luoshu_mount_diag_log "overlay formatter candidate=$_loef_candidate rc=$_loef_rc version=$(printf '%s' "$_loef_version" | tr '\r\n' ' ' | cut -c1-220)"
        case "$_loef_version" in
            *'Using EXT2FS Library'*)
                [ "$_loef_rc" -eq 0 ] || continue
                _LUOSHU_OVERLAY_FORMATTER="$_loef_candidate"
                return 0
                ;;
        esac
    done
    _LUOSHU_OVERLAY_FAILURE_STAGE=ext4-layer-formatter-unavailable
    _luoshu_self_log 'OverlayFS 兼容层缺少完整 e2fsprogs 格式化工具；BusyBox mke2fs 不支持 ext4 负载生成参数'
    return 1
}

_luoshu_overlay_prepare_ext4_layer() {
    _loel_source="$1"
    _loel_target="$2"
    _loel_key="$3"
    case "$_loel_key" in ''|*[!a-zA-Z0-9_-]*) return 1 ;; esac
    _loel_state=$(_luoshu_self_state_root)
    _loel_work="$_loel_state/work"
    _loel_image="$_loel_work/overlay-$_loel_key.img"
    _loel_layer="$_loel_work/overlay-$_loel_key"
    _LUOSHU_OVERLAY_COMPAT_SOURCE=''
    [ -n "${_lsme_mount_list:-}" ] && [ -d "$_loel_source" ] || return 1
    [ ! -L "$_loel_work" ] && [ ! -L "$_loel_image" ] && [ ! -L "$_loel_layer" ] || return 1
    _luoshu_overlay_select_formatter || return 1
    for _loel_tool in truncate; do
        command -v "$_loel_tool" >/dev/null 2>&1 || {
            _luoshu_self_log "OverlayFS 磁盘兼容层缺少工具：$_loel_tool"
            return 1
        }
    done
    # Never overwrite a mounted layer or an image held by a surviving loop mount.
    _luoshu_self_target_mounted "$_loel_layer"
    [ "$?" -eq 1 ] || return 1
    [ ! -e "$_loel_image" ] || return 1
    mkdir -p "$_loel_layer" || return 1
    _loel_kb=$(du -sk "$_loel_source" 2>/dev/null | awk '{print $1}')
    _loel_entries=$(find "$_loel_source" 2>/dev/null | wc -l | tr -d '[:space:]')
    case "$_loel_kb:$_loel_entries" in *[!0-9:]*|:*|*:) return 1 ;; esac
    _loel_size_kb=$((_loel_kb + _loel_kb / 5 + 16384))
    _loel_free_kb=$(df -k "$_loel_work" 2>/dev/null | awk 'END {print $4}')
    case "$_loel_free_kb" in ''|*[!0-9]*) return 1 ;; esac
    [ "$_loel_free_kb" -ge "$((_loel_size_kb + 32768))" ] || {
        _luoshu_self_log "OverlayFS 磁盘兼容层空间不足：required_kb=$_loel_size_kb available_kb=$_loel_free_kb"
        return 1
    }
    _luoshu_self_log "正在创建 OverlayFS ext4 字体下层：source=$_loel_source size_kb=$_loel_size_kb"
    # Format only this newly created regular file, never a device or partition.
    (umask 077; _luoshu_mount_observe overlay-layer-allocate truncate -s "$((_loel_size_kb * 1024))" "$_loel_image") || return 1
    [ -f "$_loel_image" ] && [ ! -L "$_loel_image" ] || return 1
    _luoshu_mount_observe overlay-layer-format "$_LUOSHU_OVERLAY_FORMATTER" -q -t ext4 -b 4096 -I 256 \
        -m 0 -N "$((_loel_entries + 128))" -O ^has_journal,^casefold \
        -d "$_loel_source" "$_loel_image" || return 1
    _luoshu_overlay_mount_image "$_loel_image" "$_loel_layer" || return 1
    printf '%s\n' "$_loel_layer" >> "$_lsme_mount_list" || {
        _luoshu_mount_observe overlay-layer-journal-cleanup _luoshu_umount_cmd "$_loel_layer"
        return 1
    }
    # Android mke2fs can omit SELinux xattrs while populating an image. Preserve
    # ROM directory/file labels explicitly before making the image read-only.
    rm -rf "$_loel_layer/lost+found" || return 1
    chmod 0755 "$_loel_layer" || return 1
    _luoshu_mount_observe overlay-layer-root-label _luoshu_mirror_context \
        "$_loel_target" "$_loel_layer" || return 1
    _loel_paths="$_loel_work/overlay-$_loel_key.paths"
    find "$_loel_source" -mindepth 1 > "$_loel_paths" || return 1
    while IFS= read -r _loel_entry; do
        _loel_rel=${_loel_entry#$_loel_source/}
        # Font payloads consist of regular files/directories. Refuse links or
        # special files rather than following a link outside the staged image.
        if [ -L "$_loel_entry" ] || { [ ! -f "$_loel_entry" ] && [ ! -d "$_loel_entry" ]; }; then
            _luoshu_mount_diag_log "overlay layer unsupported entry=$_loel_entry"
            return 1
        fi
        _loel_ref="$_loel_entry"
        # A provider may expose additive aliases with an unreadable '?' label.
        # Use the ROM reference only when its actual context can be read.
        if ls -Zd "$_loel_target/$_loel_rel" 2>/dev/null | \
            awk '{for(i=1;i<=NF;i++) if($i ~ /^[^:]+:[^:]+:[^:]+:/) found=1} END {exit !found}'; then
            _loel_ref="$_loel_target/$_loel_rel"
        fi
        _luoshu_mount_observe overlay-layer-entry-label _luoshu_mirror_context \
            "$_loel_ref" "$_loel_layer/$_loel_rel" || return 1
    done < "$_loel_paths"
    rm -f "$_loel_paths"
    _luoshu_atomic_tree_visible "$_loel_source" "$_loel_layer" overlay || return 1
    _luoshu_mount_observe overlay-layer-readonly _luoshu_mount_cmd \
        -o remount,ro "$_loel_layer" || return 1
    awk -v target="$_loel_layer" '$5==target && $6 ~ /(^|,)ro(,|$)/ {ok=1} END {exit !ok}' \
        /proc/self/mountinfo || return 1
    _LUOSHU_OVERLAY_COMPAT_SOURCE="$_loel_layer"
    _luoshu_mount_diag_log "overlay ext4 layer ready source=$_loel_source layer=$_loel_layer image=$_loel_image size_kb=$_loel_size_kb"
    return 0
}
