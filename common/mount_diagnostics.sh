#!/system/bin/sh
# Sourced helpers only: observing a command never replaces its exit status.
set +e

_luoshu_mount_diag_file() {
    printf '%s/logs/mount-diagnostics.log\n' "$(_luoshu_self_module)"
}

_luoshu_mount_diag_log() {
    _lmdl_file=$(_luoshu_mount_diag_file)
    mkdir -p "${_lmdl_file%/*}" 2>/dev/null || return 0
    printf '[%s] [MOUNT-DIAG] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$_lmdl_file" 2>/dev/null || true
}

_luoshu_mount_observe() {
    _lmo_label="$1"
    shift
    _lmo_argv=$(printf ' <%s>' "$@")
    _lmo_output=$("$@" 2>&1)
    _lmo_rc=$?
    _luoshu_mount_diag_log "step=$_lmo_label rc=$_lmo_rc argv=$_lmo_argv"
    if [ -n "$_lmo_output" ]; then
        printf '%s\n' "$_lmo_output" >> "$(_luoshu_mount_diag_file)" 2>/dev/null || true
    fi
    if [ "$_lmo_rc" -ne 0 ]; then
        _luoshu_self_log "挂载步骤失败：step=$_lmo_label rc=$_lmo_rc detail=$(printf '%s' "$_lmo_output" | tr '\r\n' '  ' | cut -c1-500)"
        _luoshu_mount_diag_kernel
    fi
    return "$_lmo_rc"
}

_luoshu_mount_diag_kernel() {
    _lmdk_file=$(_luoshu_mount_diag_file)
    _lmdk_tmp="${_lmdk_file}.kernel.$$"
    dmesg > "$_lmdk_tmp" 2>&1
    _lmdk_rc=$?
    {
        printf '\n[kernel snapshot] rc=%s; historical messages, not proof of this attempt\n' "$_lmdk_rc"
        if [ "$_lmdk_rc" -eq 0 ]; then
            grep -Ei 'overlay|avc:.*denied|kernelsu|sukisu|nomount' "$_lmdk_tmp" | tail -n 100
        else
            head -n 8 "$_lmdk_tmp"
        fi
    } >> "$_lmdk_file" 2>/dev/null
    rm -f "$_lmdk_tmp" 2>/dev/null || true
    return 0
}

_luoshu_mount_diag_environment() {
    _lmde_file=$(_luoshu_mount_diag_file)
    mkdir -p "${_lmde_file%/*}" 2>/dev/null || return 0
    _lmde_size=0
    if [ -s "$_lmde_file" ]; then
        _lmde_size=$(wc -c < "$_lmde_file" 2>/dev/null)
    fi
    if [ "${_lmde_size:-0}" -gt 1048576 ] 2>/dev/null; then
        mv -f "$_lmde_file" "${_lmde_file}.previous" 2>/dev/null || true
    fi
    {
        printf '\n[environment] time=%s boot_id=%s uptime=%s requested=%s\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" \
            "$(cat /proc/uptime 2>/dev/null)" "${LUOSHU_SELF_MOUNT_MODE:-unknown}"
        uname -a 2>&1
        printf 'uid/context: '; id 2>&1
        printf 'selinux: '; getenforce 2>&1
        printf 'mount executable: '; command -v mount 2>&1
        printf 'mount override=%s\n' "${LUOSHU_SELF_MOUNT_COMMAND:-none}"
        for _lmde_ns in /proc/self/ns/mnt /proc/1/ns/mnt; do
            printf '%s: ' "$_lmde_ns"; readlink "$_lmde_ns" 2>&1
        done
        printf '[filesystems]\n'; cat /proc/filesystems 2>&1
        printf '[overlay parameters]\n'
        _lmde_parameters=0
        for _lmde_param in /sys/module/overlay/parameters/*; do
            [ -f "$_lmde_param" ] || continue
            _lmde_parameters=1
            printf '%s=' "${_lmde_param##*/}"; cat "$_lmde_param" 2>&1
        done
        [ "$_lmde_parameters" = 1 ] || printf '(absent or inaccessible)\n'
        for _lmde_path in "$@"; do
            printf '\n[path] %s\n' "$_lmde_path"
            ls -ldZ "$_lmde_path" 2>&1
            stat -f -c 'filesystem=%T' "$_lmde_path" 2>&1
            df -k "$_lmde_path" 2>&1 | tail -n 2
            for _lmde_mi in "${LUOSHU_SELF_MOUNTINFO:-/proc/self/mountinfo}" "${LUOSHU_PID1_MOUNTINFO:-/proc/1/mountinfo}"; do
                printf '[backing mount %s]\n' "$_lmde_mi"
                if [ -r "$_lmde_mi" ]; then
                    awk -v path="$_lmde_path" '($5=="/" || path==$5 || index(path,$5"/")==1) && length($5)>=longest {longest=length($5); row=$0} END {print row}' "$_lmde_mi"
                else
                    printf '(inaccessible)\n'
                fi
            done
        done
    } >> "$_lmde_file" 2>&1
    return 0
}

# 0 = mounted, 1 = absent, 2 = cannot inspect. Never treat unknown as absent.
_luoshu_self_target_mounted() {
    _lstm_mountinfo="${LUOSHU_SELF_MOUNTINFO:-/proc/self/mountinfo}"
    [ -r "$_lstm_mountinfo" ] || return 2
    awk -v target="$1" '$5==target {found=1} END {exit !found}' "$_lstm_mountinfo"
}

# Includes overlays using a captured lower and binds from a directory mirror.
# Return success for inaccessible mountinfo so cleanup preserves working trees.
_luoshu_self_state_mounts_remain() {
    _lsmr_state=$(_luoshu_self_state_root)
    _lsmr_payload=''
    [ "${1:-}" != payload ] || _lsmr_payload="$(_luoshu_self_module)/.luoshu-payload/"
    for _lsmr_mi in "${LUOSHU_SELF_MOUNTINFO:-/proc/self/mountinfo}" "${LUOSHU_PID1_MOUNTINFO:-/proc/1/mountinfo}"; do
        [ -r "$_lsmr_mi" ] || return 0
        awk -v root="$_lsmr_state/" -v relative="${_lsmr_state#/data}/" \
            -v payload="$_lsmr_payload" -v payload_relative="${_lsmr_payload#/data}" \
            'index($0,root) || (root!=relative && index($0,relative)) || (payload!="" && (index($0,payload) || index($0,payload_relative))) {found=1} END {exit !found}' "$_lsmr_mi" && return 0
    done
    return 1
}
