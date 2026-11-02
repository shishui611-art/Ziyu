#!/system/bin/sh
# Ziyu metamodule compatibility layer.
#
# The canonical module tree lives under /data/adb/modules/ziyu. Some
# metamodules use a second persistent content tree, while others read the
# canonical tree directly. This file detects that contract, updates payloads
# transactionally, and verifies every partition after reboot.
set +e

_lmb_root="${MODDIR:-${MODULE_DIR:-}}"
if [ -n "$_lmb_root" ]; then
    [ -f "$_lmb_root/common/root_manager_detection.sh" ] && . "$_lmb_root/common/root_manager_detection.sh"
fi

ZIYU_MOUNT_MODDIR="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
ZIYU_MOUNT_LOG="${ZIYU_MOUNT_LOG:-$ZIYU_MOUNT_MODDIR/logs/mount_compat.log}"
ZIYU_MOUNT_LOCK="$ZIYU_MOUNT_MODDIR/.mount_compat.lock"
ZIYU_MOUNT_TIMEOUT="${ZIYU_MOUNT_TIMEOUT:-55}"
ZIYU_MOUNT_PREFLIGHT_ERROR=''
ZIYU_MOUNT_DETECTION_WARNING=''
ZIYU_MOUNT_STARTED_AT=0
ZIYU_MOUNT_DEADLINE=0

case "$ZIYU_MOUNT_TIMEOUT" in
    ''|*[!0-9]*) ZIYU_MOUNT_TIMEOUT=55 ;;
esac
if [ -z "${ZIYU_META_TEST_ENGINE:-}" ] && [ "$ZIYU_MOUNT_TIMEOUT" -lt 10 ] 2>/dev/null; then
    ZIYU_MOUNT_TIMEOUT=10
fi
[ "$ZIYU_MOUNT_TIMEOUT" -ge 1 ] 2>/dev/null || ZIYU_MOUNT_TIMEOUT=1
[ "$ZIYU_MOUNT_TIMEOUT" -le 300 ] 2>/dev/null || ZIYU_MOUNT_TIMEOUT=300

ziyu_mount_log() {
    _lml_message="$1"
    mkdir -p "${ZIYU_MOUNT_LOG%/*}" 2>/dev/null || true
    printf '[%s] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" \
        "$_lml_message" >> "$ZIYU_MOUNT_LOG" 2>/dev/null || true
}

ziyu_module_id() {
    _lmi_id=$(sed -n 's/^id=//p' "$ZIYU_MOUNT_MODDIR/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n')
    [ -n "$_lmi_id" ] || _lmi_id=${ZIYU_MOUNT_MODDIR##*/}
    printf '%s\n' "$_lmi_id"
}

_ziyu_payload_base_partitions() {
    printf '%s\n' 'system system_ext product vendor odm oem my_product my_engineering my_company my_preload my_region my_stock oplus_product oplus_engineering oplus_version oplus_region mi_ext cust hw_product'
}

_ziyu_payload_partition_safe() {
    case "$1" in
        ''|*[!A-Za-z0-9_]*|[0-9]*|_* ) return 1 ;;
        data|proc|sys|dev|mnt|storage|sdcard|apex|metadata|cache|tmp|config|acct|linkerconfig|debug_ramdisk|vendor_dlkm|odm_dlkm|system_dlkm) return 1 ;;
    esac
    return 0
}

ziyu_payload_partitions() {
    if type ziyu_private_partitions >/dev/null 2>&1; then
        ziyu_private_partitions
        return
    fi
    _lpp_base=$(_ziyu_payload_base_partitions)
    printf '%s\n' "$_lpp_base"
    _lpp_manifest="$ZIYU_MOUNT_MODDIR/config/device_font_partitions.conf"
    [ -f "$_lpp_manifest" ] || return 0
    _lpp_seen=" $_lpp_base "
    while IFS= read -r _lpp_part; do
        _ziyu_payload_partition_safe "$_lpp_part" || continue
        case "$_lpp_seen" in *" $_lpp_part "*) continue ;; esac
        printf '%s\n' "$_lpp_part"
        _lpp_seen="$_lpp_seen$_lpp_part "
    done < "$_lpp_manifest"
}

type ziyu_detect_root_manager >/dev/null 2>&1 || {
    ziyu_detect_root_manager() { printf 'unknown\n'; }
}

_ziyu_module_prop_id() {
    sed -n 's/^id=//p' "$1/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n'
}

_ziyu_module_enabled() {
    [ -f "$1/module.prop" ] && [ ! -e "$1/disable" ] && [ ! -e "$1/remove" ]
}

_ziyu_module_metadata_matches() {
    _lmmm_path="$1"
    shift
    _ziyu_module_enabled "$_lmmm_path" || return 1
    _lmmm_metadata=$(sed -n 's/^\(id\|name\|description\)=/\1=/p' "$_lmmm_path/module.prop" 2>/dev/null \
        | tr '[:upper:]' '[:lower:]' | tr '\n' ' ')
    for _lmmm_token in "$@"; do
        case "$_lmmm_metadata" in
            *"$_lmmm_token"*) return 0 ;;
        esac
    done
    return 1
}

ziyu_mountpoint_ready() {
    _lmr_path="$1"
    [ -n "${ZIYU_META_TEST_ROOT:-}" ] && return 0
    [ -e "$_lmr_path" ] || return 1
    if command -v mountpoint >/dev/null 2>&1; then
        mountpoint -q "$_lmr_path" 2>/dev/null && return 0
    fi
    awk -v path="$_lmr_path" '$2 == path { found=1 } END { exit !found }' /proc/mounts 2>/dev/null
}

_ziyu_magic_mount_present() {
    for _lmmp_path in /data/adb/modules/*; do
        [ -d "$_lmmp_path" ] || continue
        _ziyu_module_metadata_matches "$_lmmp_path" \
            magic_mount magic-mount 'magic mount' id=meta-mm && return 0
    done
    [ -f /data/adb/magic_mount/config.toml ] && {
        [ -x /data/adb/metamodule/meta-mm ] ||
        [ -x /data/adb/metamodule/meta-mm-rs ] ||
        [ -x /data/adb/modules/magic_mount_rs/meta-mm ]
    }
}

_ziyu_mountify_present() {
    for _lmp_path in \
        /data/adb/modules/mountify \
        /data/adb/modules/Mountify \
        /data/adb/modules/*mountify*; do
        [ -d "$_lmp_path" ] || continue
        _ziyu_module_metadata_matches "$_lmp_path" mountify && return 0
    done
    return 1
}

_ziyu_hybrid_mount_present() {
    command -v hybrid-mount >/dev/null 2>&1 && return 0
    for _lhmp_path in \
        /data/adb/modules/meta-hybrid_mount \
        /data/adb/modules/hybrid_mount \
        /data/adb/modules/*hybrid*mount*; do
        [ -d "$_lhmp_path" ] || continue
        _ziyu_module_metadata_matches "$_lhmp_path" \
            hybrid_mount hybrid-mount 'hybrid mount' && return 0
    done
    return 1
}

ziyu_hybrid_backend() {
    if [ -n "${ZIYU_META_TEST_BACKEND:-}" ]; then
        printf '%s\n' "$ZIYU_META_TEST_BACKEND"
        return 0
    fi

    for _lhb_file in \
        /data/adb/hybrid-mount/config.toml \
        /data/adb/hybrid-mount/config.conf \
        /data/adb/modules/meta-hybrid_mount/config.toml \
        /data/adb/modules/meta-hybrid_mount/config.conf \
        /data/adb/modules/hybrid_mount/config.toml; do
        [ -f "$_lhb_file" ] || continue
        _lhb_value=$(sed -n 's/^[[:space:]]*\(backend\|mount_backend\|mount_mode\|mode\)[[:space:]]*=[[:space:]]*//p' "$_lhb_file" 2>/dev/null \
            | tail -n1 | cut -d'#' -f1 | tr -d "\"'[:space:]" | tr '[:upper:]' '[:lower:]')
        case "$_lhb_value" in
            *overlay*) printf 'overlayfs\n'; return 0 ;;
            *magic*) printf 'magic-mount\n'; return 0 ;;
            *kasumi*) printf 'kasumi\n'; return 0 ;;
        esac
    done
    printf 'unknown\n'
}

_ziyu_meta_overlayfs_active() {
    _lmoa_id=$(_ziyu_module_prop_id /data/adb/metamodule)
    case "$_lmoa_id" in
        meta-overlay|meta-overlayfs|meta-overlayfsUltra) ;;
        *) return 1 ;;
    esac
    [ ! -e /data/adb/metamodule/disable ] || return 1
    [ ! -e /data/adb/metamodule/remove ] || return 1
    [ -d /data/adb/metamodule/mnt ] || return 1
    ziyu_mountpoint_ready /data/adb/metamodule/mnt && return 0
    [ -f /data/adb/metamodule/modules.img ] || [ -L /data/adb/metamodule ]
}

_ziyu_mount_candidates() {
    if [ -n "${ZIYU_META_TEST_CANDIDATES:-}" ]; then
        printf '%s\n' "$ZIYU_META_TEST_CANDIDATES"
        return 0
    fi

    _lmc_candidates=''
    _ziyu_meta_overlayfs_active && _lmc_candidates="$_lmc_candidates meta-overlayfs"
    _ziyu_hybrid_mount_present && _lmc_candidates="$_lmc_candidates hybrid-mount"
    _ziyu_mountify_present && _lmc_candidates="$_lmc_candidates mountify"
    _ziyu_magic_mount_present && _lmc_candidates="$_lmc_candidates magic-mount"
    printf '%s\n' "$_lmc_candidates"
}

ziyu_mount_detection_warning() {
    _lmdw_candidates=$(_ziyu_mount_candidates)
    _lmdw_count=$(printf '%s\n' "$_lmdw_candidates" | awk '{ print NF }')
    [ "${_lmdw_count:-0}" -le 1 ] && return 0
    printf '检测到多个已启用挂载模块：%s\n' \
        "$(printf '%s\n' "$_lmdw_candidates" | sed 's/^ *//; s/  */、/g')"
}

ziyu_detect_mount_engine() {
    ZIYU_MOUNT_DETECTION_WARNING=''
    if [ -n "${ZIYU_META_TEST_ENGINE:-}" ]; then
        printf '%s\n' "$ZIYU_META_TEST_ENGINE"
        return 0
    fi
    if [ -n "${MODULE_CONTENT_DIR:-}" ] && [ -n "${MODULE_METADATA_DIR:-}" ]; then
        printf 'dual-dir-metamodule\n'
        return 0
    fi

    _ldme_candidates=$(_ziyu_mount_candidates)
    _ldme_count=$(printf '%s\n' "$_ldme_candidates" | awk '{ print NF }')
    if [ "${_ldme_count:-0}" -gt 1 ]; then
        ZIYU_MOUNT_DETECTION_WARNING=$(ziyu_mount_detection_warning)
        ziyu_mount_log "$ZIYU_MOUNT_DETECTION_WARNING"
    fi

    case " $_ldme_candidates " in
        *' meta-overlayfs '*) printf 'meta-overlayfs\n' ;;
        *' hybrid-mount '*) printf 'hybrid-mount\n' ;;
        *' mountify '*) printf 'mountify\n' ;;
        *' magic-mount '*) printf 'magic-mount\n' ;;
        *) printf 'native-module-mount\n' ;;
    esac
}

ziyu_mount_backend() {
    _lmb_engine="${1:-$(ziyu_detect_mount_engine)}"
    case "$_lmb_engine" in
        hybrid-mount) ziyu_hybrid_backend ;;
        meta-overlayfs|dual-dir-metamodule) printf 'overlayfs\n' ;;
        mountify) printf 'mountify\n' ;;
        magic-mount|magic-mount-rs) printf 'magic-mount\n' ;;
        *) printf 'native\n' ;;
    esac
}

ziyu_dual_content_base() {
    if [ -n "${ZIYU_META_TEST_ROOT:-}" ]; then
        printf '%s\n' "$ZIYU_META_TEST_ROOT"
    elif [ -n "${MODULE_CONTENT_DIR:-}" ]; then
        printf '%s\n' "${MODULE_CONTENT_DIR%/}"
    else
        printf '/data/adb/metamodule/mnt\n'
    fi
}

ziyu_meta_content_roots() {
    _lmcr_engine=$(ziyu_detect_mount_engine)
    case "$_lmcr_engine" in
        meta-overlayfs|dual-dir-metamodule) ;;
        *) return 0 ;;
    esac

    _lmcr_base=$(ziyu_dual_content_base)
    _lmcr_id=$(ziyu_module_id)
    case "$_lmcr_base" in
        */"$_lmcr_id") _lmcr_root="$_lmcr_base" ;;
        *) _lmcr_root="$_lmcr_base/$_lmcr_id" ;;
    esac
    [ "$_lmcr_root" = "$ZIYU_MOUNT_MODDIR" ] || printf '%s\n' "$_lmcr_root"
}

ziyu_mount_lock_acquire() {
    if [ -f "$ZIYU_MOUNT_LOCK" ]; then
        _lmla_pid=$(cat "$ZIYU_MOUNT_LOCK" 2>/dev/null)
        if [ -n "$_lmla_pid" ] && kill -0 "$_lmla_pid" 2>/dev/null; then
            return 1
        fi
        rm -f "$ZIYU_MOUNT_LOCK" 2>/dev/null || true
    fi
    printf '%s\n' "$$" > "$ZIYU_MOUNT_LOCK" 2>/dev/null
}

ziyu_mount_lock_release() {
    rm -f "$ZIYU_MOUNT_LOCK" 2>/dev/null || true
}

ziyu_mountify_value() {
    _lmv_key="$1"
    for _lmv_file in \
        /data/adb/mountify/config.sh \
        /data/adb/modules/mountify/config.sh \
        /data/adb/modules/Mountify/config.sh; do
        [ -f "$_lmv_file" ] || continue
        sed -n "s/^[[:space:]]*${_lmv_key}[[:space:]]*=[[:space:]]*[\"']\{0,1\}\([^\"'#[:space:]]*\).*/\1/p" "$_lmv_file" 2>/dev/null | tail -n1
        return 0
    done
    return 1
}

ziyu_mountify_module_selected() {
    _lmms_id=$(ziyu_module_id)
    _lmms_mode=$(ziyu_mountify_value mountify_mounts)
    [ -n "$_lmms_mode" ] || _lmms_mode=2
    [ "$_lmms_mode" != 1 ] && return 0

    for _lmms_file in \
        /data/adb/mountify/modules.txt \
        /data/adb/modules/mountify/modules.txt \
        /data/adb/modules/Mountify/modules.txt; do
        [ -f "$_lmms_file" ] || continue
        grep -Fxq "$_lmms_id" "$_lmms_file" 2>/dev/null && return 0
    done
    return 1
}

ziyu_recover_explicit_disable() {
    [ -e "$ZIYU_MOUNT_MODDIR/disable" ] || return 0
    rm -f "$ZIYU_MOUNT_MODDIR/disable" 2>/dev/null || {
        ZIYU_MOUNT_PREFLIGHT_ERROR='无法解除字域模块的 disable 标记'
        return 1
    }
    rm -f \
        "$ZIYU_MOUNT_MODDIR/config/font-boot-failures" \
        "$ZIYU_MOUNT_MODDIR/config/font-payload-quarantine.conf" 2>/dev/null || true
    ziyu_mount_log '已解除字域 disable 标记'
}

ziyu_recover_magic_mount_markers() {
    _lrmm_engine="$1"
    case "$_lrmm_engine" in
        magic-mount|magic-mount-rs) ;;
        *) return 0 ;;
    esac

    for _lrmm_marker in skip_mount mount_error; do
        [ -e "$ZIYU_MOUNT_MODDIR/$_lrmm_marker" ] || continue
        rm -f "$ZIYU_MOUNT_MODDIR/$_lrmm_marker" 2>/dev/null || {
            ZIYU_MOUNT_PREFLIGHT_ERROR="无法清理 Magic Mount 遗留标记：$_lrmm_marker"
            return 1
        }
    done
}

_ziyu_partition_has_payload() {
    _lphp_partition="$1"
    [ -d "$ZIYU_MOUNT_MODDIR/$_lphp_partition" ] || return 1
    find "$ZIYU_MOUNT_MODDIR/$_lphp_partition" -type f -print -quit 2>/dev/null | grep -q .
}

ziyu_used_partitions() {
    _lup_content_root="${1:-}"
    for _lup_partition in $(ziyu_payload_partitions); do
        if _ziyu_partition_has_payload "$_lup_partition"; then
            printf '%s\n' "$_lup_partition"
        elif [ -n "$_lup_content_root" ] && [ -e "$_lup_content_root/$_lup_partition" ]; then
            printf '%s\n' "$_lup_partition"
        fi
    done
}

_ziyu_meta_declared_partitions() {
    printf '%s\n' "${ZIYU_META_EXTRA_PARTITIONS:-}"
    for _lmdp_file in \
        /data/adb/metamodule/config.toml \
        /data/adb/metamodule/config.conf \
        /data/adb/modules/meta-overlayfs/config.toml; do
        [ -f "$_lmdp_file" ] || continue
        awk -F'"' '/partition|mount/ { for (i=2; i<=NF; i+=2) if ($i ~ /^[A-Za-z0-9_]+$/) print $i }' "$_lmdp_file"
    done
}

ziyu_meta_partition_supported() {
    _lmps_partition="$1"
    case "$_lmps_partition" in
        system|vendor|product|system_ext|odm|oem) return 0 ;;
    esac
    _lmps_declared=$(_ziyu_meta_declared_partitions | tr '\n' ' ')
    case " $_lmps_declared " in
        *" $_lmps_partition "*) return 0 ;;
    esac
    return 1
}

ziyu_engine_partitions() {
    _lep_engine="${1:-$(ziyu_detect_mount_engine)}"
    case "$_lep_engine" in
        meta-overlayfs|dual-dir-metamodule)
            for _lep_partition in $(ziyu_payload_partitions); do
                ziyu_meta_partition_supported "$_lep_partition" && printf '%s\n' "$_lep_partition"
            done
            ;;
        *) ziyu_payload_partitions ;;
    esac
}

ziyu_magic_mount_ensure_partitions() {
    _lmep_engine="$1"
    case "$_lmep_engine" in
        magic-mount|magic-mount-rs) ;;
        *) return 0 ;;
    esac

    _lmep_config="${ZIYU_MAGIC_MOUNT_CONFIG:-/data/adb/magic_mount/config.toml}"
    [ -f "$_lmep_config" ] || return 0
    _lmep_lock="$_lmep_config.ziyu.lock"
    if [ -f "$_lmep_lock" ]; then
        _lmep_pid=$(cat "$_lmep_lock" 2>/dev/null)
        if [ -n "$_lmep_pid" ] && kill -0 "$_lmep_pid" 2>/dev/null; then
            ZIYU_MOUNT_PREFLIGHT_ERROR='Magic Mount 配置正在被修改'
            return 1
        fi
        rm -f "$_lmep_lock" 2>/dev/null || true
    fi
    printf '%s\n' "$$" > "$_lmep_lock" 2>/dev/null || return 1

    _lmep_current=$(awk '
        /^[[:space:]]*partitions[[:space:]]*=/ { capture=1 }
        capture { printf "%s ", $0 }
        capture && /]/ { exit }
    ' "$_lmep_config" 2>/dev/null)
    _lmep_list=''
    for _lmep_item in $(printf '%s\n' "$_lmep_current" | awk -F'"' '{ for (i=2; i<=NF; i+=2) print $i }'); do
        case "$_lmep_item" in ''|*[!A-Za-z0-9_]*) continue ;; esac
        case " $_lmep_list " in
            *" $_lmep_item "*) ;;
            *) _lmep_list="$_lmep_list $_lmep_item" ;;
        esac
    done

    _lmep_added=''
    for _lmep_partition in $(ziyu_used_partitions); do
        [ "$_lmep_partition" = system ] && continue
        case " $_lmep_list " in
            *" $_lmep_partition "*) ;;
            *)
                _lmep_list="$_lmep_list $_lmep_partition"
                _lmep_added="${_lmep_added}${_lmep_added:+,}$_lmep_partition"
                ;;
        esac
    done
    if [ -z "$_lmep_added" ]; then
        rm -f "$_lmep_lock" 2>/dev/null || true
        return 0
    fi

    _lmep_array=''
    for _lmep_item in $_lmep_list; do
        _lmep_array="${_lmep_array}${_lmep_array:+, }\"$_lmep_item\""
    done
    _lmep_temp="$_lmep_config.ziyu.$$"
    [ -f "$_lmep_config.ziyu.bak" ] || cp -pf "$_lmep_config" "$_lmep_config.ziyu.bak" 2>/dev/null || true
    awk -v replacement="partitions = [$_lmep_array]" '
        BEGIN { replaced=0; skipping=0 }
        skipping { if ($0 ~ /]/) skipping=0; next }
        /^[[:space:]]*partitions[[:space:]]*=/ {
            print replacement
            replaced=1
            if ($0 !~ /]/) skipping=1
            next
        }
        { print }
        END { if (!replaced) print replacement }
    ' "$_lmep_config" > "$_lmep_temp" 2>/dev/null || {
        rm -f "$_lmep_temp" "$_lmep_lock" 2>/dev/null || true
        ZIYU_MOUNT_PREFLIGHT_ERROR='无法更新 Magic Mount 配置'
        return 1
    }
    if [ "$(grep -c '^[[:space:]]*partitions[[:space:]]*=' "$_lmep_temp" 2>/dev/null)" -ne 1 ]; then
        rm -f "$_lmep_temp" "$_lmep_lock" 2>/dev/null || true
        ZIYU_MOUNT_PREFLIGHT_ERROR='Magic Mount 配置校验失败'
        return 1
    fi
    chmod --reference="$_lmep_config" "$_lmep_temp" 2>/dev/null || chmod 0644 "$_lmep_temp" 2>/dev/null || true
    mv -f "$_lmep_temp" "$_lmep_config" 2>/dev/null || {
        rm -f "$_lmep_temp" "$_lmep_lock" 2>/dev/null || true
        ZIYU_MOUNT_PREFLIGHT_ERROR='无法提交 Magic Mount 配置'
        return 1
    }
    rm -f "$_lmep_lock" 2>/dev/null || true
    ziyu_mount_log "Magic Mount 增加分区：$_lmep_added"
}

ziyu_mount_preflight() {
    _lmp_active="${1:-unknown}"
    ZIYU_MOUNT_PREFLIGHT_ERROR=''
    _lmp_engine=$(ziyu_detect_mount_engine)
    _lmp_manager=$(ziyu_detect_root_manager)

    ziyu_recover_explicit_disable || return 1
    [ "$_lmp_active" = default ] || ziyu_recover_magic_mount_markers "$_lmp_engine" || return 1
    [ ! -e "$ZIYU_MOUNT_MODDIR/remove" ] || {
        ZIYU_MOUNT_PREFLIGHT_ERROR='模块存在 remove 标记'
        return 1
    }
    if [ "$_lmp_active" != default ] && [ -e "$ZIYU_MOUNT_MODDIR/mount_error" ]; then
        ZIYU_MOUNT_PREFLIGHT_ERROR='模块存在 mount_error 标记'
        return 1
    fi

    case "$_lmp_engine" in
        meta-overlayfs|dual-dir-metamodule|hybrid-mount|magic-mount|magic-mount-rs)
            if [ "$_lmp_active" != default ] && [ -e "$ZIYU_MOUNT_MODDIR/skip_mount" ]; then
                ZIYU_MOUNT_PREFLIGHT_ERROR='检测到 skip_mount'
                return 1
            fi
            ;;
        mountify)
            if [ "$_lmp_active" != default ]; then
                if [ "$_lmp_manager" = Magisk ] && [ -e "$ZIYU_MOUNT_MODDIR/skip_mountify" ]; then
                    ZIYU_MOUNT_PREFLIGHT_ERROR='检测到 skip_mountify'
                    return 1
                fi
                ziyu_mountify_module_selected || {
                    ZIYU_MOUNT_PREFLIGHT_ERROR='Mountify 白名单未包含 ziyu'
                    return 1
                }
            fi
            ;;
    esac

    [ "$_lmp_active" = default ] || ziyu_magic_mount_ensure_partitions "$_lmp_engine" || return 1

    case "$_lmp_engine" in
        meta-overlayfs|dual-dir-metamodule)
            _lmp_base=$(ziyu_dual_content_base)
            ziyu_mountpoint_ready "$_lmp_base" || {
                ZIYU_MOUNT_PREFLIGHT_ERROR="元模块内容镜像未挂载：$_lmp_base"
                return 1
            }
            [ -w "$_lmp_base" ] || {
                ZIYU_MOUNT_PREFLIGHT_ERROR="元模块内容镜像不可写：$_lmp_base"
                return 1
            }
            _lmp_unsupported=''
            for _lmp_partition in $(ziyu_used_partitions); do
                ziyu_meta_partition_supported "$_lmp_partition" || \
                    _lmp_unsupported="${_lmp_unsupported}${_lmp_unsupported:+,}$_lmp_partition"
            done
            [ -z "$_lmp_unsupported" ] || {
                ZIYU_MOUNT_PREFLIGHT_ERROR="meta-overlayfs 未声明支持分区：$_lmp_unsupported"
                return 1
            }
            ;;
    esac
}

_ziyu_now() {
    date +%s 2>/dev/null || echo 0
}

ziyu_mount_budget_begin() {
    ZIYU_MOUNT_STARTED_AT=$(_ziyu_now)
    ZIYU_MOUNT_DEADLINE=$((ZIYU_MOUNT_STARTED_AT + ZIYU_MOUNT_TIMEOUT))
}

ziyu_mount_remaining() {
    _lmr_now=$(_ziyu_now)
    _lmr_left=$((ZIYU_MOUNT_DEADLINE - _lmr_now))
    [ "$_lmr_left" -gt 0 ] || _lmr_left=0
    printf '%s\n' "$_lmr_left"
}

ziyu_kill_tree() {
    _lkt_pid="$1"
    if command -v pgrep >/dev/null 2>&1; then
        for _lkt_child in $(pgrep -P "$_lkt_pid" 2>/dev/null); do
            ziyu_kill_tree "$_lkt_child"
        done
    fi
    kill -TERM "$_lkt_pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$_lkt_pid" 2>/dev/null || true
}

ziyu_run_bounded() {
    _lrb_seconds="$1"
    shift
    [ "$_lrb_seconds" -gt 0 ] || return 124

    if command -v timeout >/dev/null 2>&1; then
        timeout "$_lrb_seconds" "$@"
        return $?
    fi
    if command -v toybox >/dev/null 2>&1 && toybox timeout --help >/dev/null 2>&1; then
        toybox timeout "$_lrb_seconds" "$@"
        return $?
    fi

    "$@" &
    _lrb_pid=$!
    _lrb_elapsed=0
    while kill -0 "$_lrb_pid" 2>/dev/null && [ "$_lrb_elapsed" -lt "$_lrb_seconds" ]; do
        sleep 1
        _lrb_elapsed=$((_lrb_elapsed + 1))
    done
    if kill -0 "$_lrb_pid" 2>/dev/null; then
        ziyu_kill_tree "$_lrb_pid"
        wait "$_lrb_pid" 2>/dev/null || true
        return 124
    fi
    wait "$_lrb_pid"
}

ziyu_copy_tree_bounded() {
    _lctb_source="$1"
    _lctb_destination="$2"
    _lctb_remaining=$(ziyu_mount_remaining)
    [ "$_lctb_remaining" -gt 0 ] || return 124
    ziyu_run_bounded "$_lctb_remaining" cp -af "$_lctb_source" "$_lctb_destination"
}

ziyu_copy_partition_atomic() {
    _lcpa_source="$1"
    _lcpa_destination="$2"
    _lcpa_parent=${_lcpa_destination%/*}
    _lcpa_name=${_lcpa_destination##*/}
    _lcpa_temp="$_lcpa_parent/.${_lcpa_name}.ziyu.$$"
    _lcpa_backup="$_lcpa_parent/.${_lcpa_name}.ziyu-backup.$$"

    mkdir -p "$_lcpa_parent" 2>/dev/null || return 1
    rm -rf "$_lcpa_temp" "$_lcpa_backup" 2>/dev/null || true
    ziyu_copy_tree_bounded "$_lcpa_source" "$_lcpa_temp" || {
        _lcpa_result=$?
        rm -rf "$_lcpa_temp" 2>/dev/null || true
        return "$_lcpa_result"
    }
    chmod -R u=rwX,go=rX "$_lcpa_temp" 2>/dev/null || true

    if [ -e "$_lcpa_destination" ]; then
        mv "$_lcpa_destination" "$_lcpa_backup" 2>/dev/null || {
            rm -rf "$_lcpa_temp" 2>/dev/null || true
            return 1
        }
    fi
    if mv "$_lcpa_temp" "$_lcpa_destination" 2>/dev/null; then
        rm -rf "$_lcpa_backup" 2>/dev/null || true
        return 0
    fi

    rm -rf "$_lcpa_destination" 2>/dev/null || true
    [ ! -e "$_lcpa_backup" ] || mv "$_lcpa_backup" "$_lcpa_destination" 2>/dev/null || true
    rm -rf "$_lcpa_temp" 2>/dev/null || true
    return 1
}

_ziyu_probe_path() {
    if [ "$1" = system ]; then
        printf '/system/etc/ziyu/mount-probe.conf\n'
    else
        printf '/%s/etc/ziyu/mount-probe.conf\n' "$1"
    fi
}

ziyu_write_mount_probes() {
    _lwmp_active="${1:-unknown}"
    _lwmp_engine=$(ziyu_detect_mount_engine)
    _lwmp_id=$(ziyu_module_id)
    _lwmp_nonce="$(_ziyu_now)-$$"
    _lwmp_manifest="$ZIYU_MOUNT_MODDIR/config/mount-probes-expected.conf"
    _lwmp_temp="$_lwmp_manifest.tmp.$$"
    _lwmp_count=0

    mkdir -p "$ZIYU_MOUNT_MODDIR/config" 2>/dev/null || return 1
    : > "$_lwmp_temp" 2>/dev/null || return 1
    for _lwmp_partition in $(ziyu_used_partitions); do
        _lwmp_directory="$ZIYU_MOUNT_MODDIR/$_lwmp_partition/etc/ziyu"
        mkdir -p "$_lwmp_directory" 2>/dev/null || return 1
        {
            printf 'id=%s\n' "$_lwmp_id"
            printf 'font=%s\n' "$_lwmp_active"
            printf 'engine=%s\n' "$_lwmp_engine"
            printf 'partition=%s\n' "$_lwmp_partition"
            printf 'nonce=%s-%s\n' "$_lwmp_nonce" "$_lwmp_partition"
        } > "$_lwmp_directory/mount-probe.conf.tmp.$$" 2>/dev/null || return 1
        mv -f "$_lwmp_directory/mount-probe.conf.tmp.$$" "$_lwmp_directory/mount-probe.conf" 2>/dev/null || return 1
        chmod 0644 "$_lwmp_directory/mount-probe.conf" 2>/dev/null || true
        printf '%s|%s-%s|%s\n' \
            "$_lwmp_partition" "$_lwmp_nonce" "$_lwmp_partition" \
            "$(_ziyu_probe_path "$_lwmp_partition")" >> "$_lwmp_temp"
        _lwmp_count=$((_lwmp_count + 1))
    done

    if [ "$_lwmp_count" -eq 0 ]; then
        _lwmp_directory="$ZIYU_MOUNT_MODDIR/system/etc/ziyu"
        mkdir -p "$_lwmp_directory" 2>/dev/null || return 1
        {
            printf 'id=%s\n' "$_lwmp_id"
            printf 'font=%s\n' "$_lwmp_active"
            printf 'engine=%s\n' "$_lwmp_engine"
            printf 'partition=system\n'
            printf 'nonce=%s-system\n' "$_lwmp_nonce"
        } > "$_lwmp_directory/mount-probe.conf" 2>/dev/null || return 1
        printf 'system|%s-system|/system/etc/ziyu/mount-probe.conf\n' "$_lwmp_nonce" >> "$_lwmp_temp"
    fi

    mv -f "$_lwmp_temp" "$_lwmp_manifest" 2>/dev/null || return 1
    chmod 0644 "$_lwmp_manifest" 2>/dev/null || true
    cp -f \
        "$ZIYU_MOUNT_MODDIR/system/etc/ziyu/mount-probe.conf" \
        "$ZIYU_MOUNT_MODDIR/config/mount-probe-expected.conf" 2>/dev/null || true
}

# Compatibility with callers from earlier releases.
ziyu_write_mount_probe() {
    ziyu_write_mount_probes "$@"
}

ziyu_mount_record() {
    _lmr_state="$1"
    _lmr_detail="$2"
    _lmr_root="$3"
    _lmr_synced="$4"
    _lmr_failed="$5"
    _lmr_partitions="${6:-}"
    _lmr_verified="${7:-}"
    _lmr_failed_partitions="${8:-}"
    _lmr_unsupported="${9:-}"
    _lmr_time=$(_ziyu_now)
    _lmr_duration=0
    [ "$ZIYU_MOUNT_STARTED_AT" -gt 0 ] && _lmr_duration=$((_lmr_time - ZIYU_MOUNT_STARTED_AT))
    _lmr_warning="$ZIYU_MOUNT_DETECTION_WARNING"
    [ -n "$_lmr_warning" ] || _lmr_warning=$(ziyu_mount_detection_warning)

    mkdir -p "$ZIYU_MOUNT_MODDIR/config" 2>/dev/null || true
    {
        printf 'manager=%s\n' "$(ziyu_detect_root_manager)"
        printf 'engine=%s\n' "$(ziyu_detect_mount_engine)"
        printf 'backend=%s\n' "$(ziyu_mount_backend)"
        printf 'state=%s\n' "$_lmr_state"
        printf 'detail=%s\n' "$_lmr_detail"
        printf 'warning=%s\n' "$_lmr_warning"
        printf 'contentRoot=%s\n' "$_lmr_root"
        printf 'synced=%s\n' "$_lmr_synced"
        printf 'failed=%s\n' "$_lmr_failed"
        printf 'partitions=%s\n' "$_lmr_partitions"
        printf 'verifiedPartitions=%s\n' "$_lmr_verified"
        printf 'failedPartitions=%s\n' "$_lmr_failed_partitions"
        printf 'unsupportedPartitions=%s\n' "$_lmr_unsupported"
        printf 'durationSeconds=%s\n' "$_lmr_duration"
        printf 'time=%s\n' "$_lmr_time"
    } > "$ZIYU_MOUNT_MODDIR/config/mount_compat.conf.tmp.$$" 2>/dev/null && \
        mv -f "$ZIYU_MOUNT_MODDIR/config/mount_compat.conf.tmp.$$" \
            "$ZIYU_MOUNT_MODDIR/config/mount_compat.conf" 2>/dev/null || true
}

ziyu_sync_mount_payload() {
    # Font preparation never modifies a provider's active tree. The one early
    # boot publisher installs the next generation after activation and detection.
    ziyu_mount_record prepared '字体负载已准备，完整重启后发布并验证' '' 0 0
    return 0
}

ziyu_restore_mount_payload() {
    _lrmp_active=$(head -n1 "$ZIYU_MOUNT_MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_lrmp_active" ] || _lrmp_active=default
    ziyu_sync_mount_payload "$_lrmp_active"
}

ziyu_mount_verify_active() {
    _lmva_active="${1:-$(head -n1 "$ZIYU_MOUNT_MODDIR/config/active_font.conf" 2>/dev/null)}"
    [ -n "$_lmva_active" ] || _lmva_active=default
    if [ "$_lmva_active" = default ]; then
        ziyu_mount_record verified '系统默认字体无需挂载验证' '' 0 0
        return 0
    fi

    _lmva_manifest="$ZIYU_MOUNT_MODDIR/config/mount-probes-expected.conf"
    if [ ! -s "$_lmva_manifest" ]; then
        ziyu_mount_record unverified '缺少分区挂载探针清单' '' 0 1 '' '' manifest
        return 1
    fi

    _lmva_partitions=''
    _lmva_verified=''
    _lmva_failed=''
    _lmva_failed_count=0
    while IFS='|' read -r _lmva_partition _lmva_expected _lmva_visible_path; do
        [ -n "$_lmva_partition" ] || continue
        _lmva_partitions="${_lmva_partitions}${_lmva_partitions:+,}$_lmva_partition"
        if [ -n "${ZIYU_VISIBLE_PROBE:-}" ] && [ "$_lmva_partition" = system ]; then
            _lmva_visible="$ZIYU_VISIBLE_PROBE"
        elif [ -n "${ZIYU_VISIBLE_PROBE_ROOT:-}" ]; then
            _lmva_visible="${ZIYU_VISIBLE_PROBE_ROOT%/}$_lmva_visible_path"
        else
            _lmva_visible="$_lmva_visible_path"
        fi
        _lmva_seen=$(sed -n 's/^nonce=//p' "$_lmva_visible" 2>/dev/null | head -n1)
        _lmva_seen_partition=$(sed -n 's/^partition=//p' "$_lmva_visible" 2>/dev/null | head -n1)
        if [ "$_lmva_expected" = "$_lmva_seen" ] && [ "$_lmva_partition" = "$_lmva_seen_partition" ]; then
            _lmva_verified="${_lmva_verified}${_lmva_verified:+,}$_lmva_partition"
        else
            _lmva_failed="${_lmva_failed}${_lmva_failed:+,}$_lmva_partition"
            _lmva_failed_count=$((_lmva_failed_count + 1))
        fi
    done < "$_lmva_manifest"

    if [ "$_lmva_failed_count" -eq 0 ]; then
        ziyu_mount_record verified \
            '所有字体负载分区均已从系统路径读取' '' 0 0 \
            "$_lmva_partitions" "$_lmva_verified"
        return 0
    fi
    ziyu_mount_record unverified \
        "部分字体分区未挂载：$_lmva_failed" '' 0 "$_lmva_failed_count" \
        "$_lmva_partitions" "$_lmva_verified" "$_lmva_failed"
    return 1
}

# A completed Android boot is not enough. The selected mount engine must expose
# every generated probe before the payload transaction is trusted.
font_config_mark_boot_success() {
    _lmbs_config="${CONFIG_DIR:-$ZIYU_MOUNT_MODDIR/config}"
    _lmbs_state=$(sed -n 's/^state=//p' "$_lmbs_config/font-payload-boot.conf" 2>/dev/null | head -n1)
    [ "$_lmbs_state" = booting ] || return 0
    _lmbs_font=$(sed -n 's/^font=//p' "$_lmbs_config/font-payload-boot.conf" 2>/dev/null | head -n1)
    ziyu_mount_verify_active "${_lmbs_font:-unknown}" || return 1
    printf 'state=confirmed\nfont=%s\ntime=%s\n' \
        "${_lmbs_font:-unknown}" "$(_ziyu_now)" \
        > "$_lmbs_config/font-payload-boot.conf.tmp.$$" 2>/dev/null || return 1
    mv -f "$_lmbs_config/font-payload-boot.conf.tmp.$$" \
        "$_lmbs_config/font-payload-boot.conf" 2>/dev/null || return 1
    rm -f \
        "$_lmbs_config/font-boot-failures" \
        "$_lmbs_config/font-payload-quarantine.conf" 2>/dev/null || true
    printf 'time=%s\n' "$(_ziyu_now)" > "$_lmbs_config/font-last-boot-success.conf" 2>/dev/null || true
    chmod 0644 \
        "$_lmbs_config/font-payload-boot.conf" \
        "$_lmbs_config/font-last-boot-success.conf" 2>/dev/null || true
}

_ziyu_json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\r' '  '
}

ziyu_mount_status_json() {
    _lmsj_config="$ZIYU_MOUNT_MODDIR/config/mount_compat.conf"
    _lmsj_get() {
        sed -n "s/^$1=//p" "$_lmsj_config" 2>/dev/null | head -n1
    }
    _lmsj_state=$(_lmsj_get state)
    _lmsj_detail=$(_lmsj_get detail)
    _lmsj_warning=$(_lmsj_get warning)
    _lmsj_root=$(_lmsj_get contentRoot)
    _lmsj_backend=$(_lmsj_get backend)
    _lmsj_partitions=$(_lmsj_get partitions)
    _lmsj_verified=$(_lmsj_get verifiedPartitions)
    _lmsj_failed_partitions=$(_lmsj_get failedPartitions)
    _lmsj_unsupported=$(_lmsj_get unsupportedPartitions)
    _lmsj_synced=$(_lmsj_get synced)
    _lmsj_failed=$(_lmsj_get failed)
    _lmsj_duration=$(_lmsj_get durationSeconds)
    _lmsj_time=$(_lmsj_get time)

    printf '{"manager":"%s","engine":"%s","backend":"%s","state":"%s","detail":"%s","warning":"%s","contentRoot":"%s","partitions":"%s","verifiedPartitions":"%s","failedPartitions":"%s","unsupportedPartitions":"%s","synced":%s,"failed":%s,"durationSeconds":%s,"time":%s}' \
        "$(_ziyu_json_escape "$(ziyu_detect_root_manager)")" \
        "$(_ziyu_json_escape "$(ziyu_detect_mount_engine)")" \
        "$(_ziyu_json_escape "${_lmsj_backend:-$(ziyu_mount_backend)}")" \
        "$(_ziyu_json_escape "${_lmsj_state:-unknown}")" \
        "$(_ziyu_json_escape "$_lmsj_detail")" \
        "$(_ziyu_json_escape "$_lmsj_warning")" \
        "$(_ziyu_json_escape "$_lmsj_root")" \
        "$(_ziyu_json_escape "$_lmsj_partitions")" \
        "$(_ziyu_json_escape "$_lmsj_verified")" \
        "$(_ziyu_json_escape "$_lmsj_failed_partitions")" \
        "$(_ziyu_json_escape "$_lmsj_unsupported")" \
        "${_lmsj_synced:-0}" "${_lmsj_failed:-0}" \
        "${_lmsj_duration:-0}" "${_lmsj_time:-0}"
}

_ziyu_hyperos_helper="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}/common/hyperos_global.sh"
[ -f "$_ziyu_hyperos_helper" ] && . "$_ziyu_hyperos_helper"
_ziyu_font_config_partitions="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}/common/font_config_partitions.sh"
[ -f "$_ziyu_font_config_partitions" ] && . "$_ziyu_font_config_partitions"
_ziyu_meta_helper="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}/common/meta_mount_detection.sh"
[ -f "$_ziyu_meta_helper" ] && . "$_ziyu_meta_helper"

if [ "${0##*/}" = mount_compat.sh ]; then
    case "${1:-status}" in
        status)
            ziyu_mount_status_json
            printf '\n'
            ;;
        detect)
            printf 'engine=%s\n' "$(ziyu_detect_mount_engine)"
            printf 'backend=%s\n' "$(ziyu_mount_backend)"
            printf 'warning=%s\n' "$(ziyu_mount_detection_warning)"
            ;;
        verify)
            ziyu_mount_verify_active "${2:-}"
            ;;
        *)
            printf 'usage: %s {status|detect|verify [font]}\n' "$0" >&2
            exit 2
            ;;
    esac
fi
