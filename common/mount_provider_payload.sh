#!/system/bin/sh
# Publish an ordinary module tree before the provider scans it. No bind views.
set +e

_zpp_owned_path() {
    case "$2" in "$1"/*) ;; *) return 1 ;; esac
    case "${2#"$1"/}" in ''|../*|*/../*|*/..|*'|'*) return 1 ;; esac
}

_zpp_log() {
    mkdir -p "$_zpp_module/logs" 2>/dev/null || true
    printf '[%s] [PROVIDER-PUBLISH] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" \
        >> "$_zpp_module/logs/mount-backend.log" 2>/dev/null || true
}

# The kernel boot id is stable for the whole boot, so a receipt written earlier
# in this boot is reused instead of republished. A module update can still have
# replaced the module directory before the receipt was written; check the
# receipt and the published projection cheaply before deciding to re-publish.
# The full digest pass still runs on publish and on route failure, so this makes
# no content claim.
ziyu_provider_payload_ready() {
    _zpr_module="$1"
    _zpr_root="$2"
    _zpr_layout="${3:-nomount-partition-roots}"
    case "$_zpr_layout" in nested-system|nomount-partition-roots) ;; *) return 1 ;; esac
    [ -n "$_zpr_module" ] && [ -n "$_zpr_root" ] || return 1
    _zpr_manifest="$_zpr_module/config/font-payload-manifest.conf"
    [ -s "$_zpr_manifest" ] || return 1
    _zpr_receipt="$_zpr_root/.ziyu-state/provider-published.conf"
    [ -s "$_zpr_receipt" ] || return 1
    [ "$(ziyu_mount_conf_value "$_zpr_receipt" layout)" = "$_zpr_layout" ] || return 1
    _zpr_boot="${_lbr_boot:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"
    [ -n "$_zpr_boot" ] || return 1
    [ "$(ziyu_mount_conf_value "$_zpr_receipt" boot_id)" = "$_zpr_boot" ] || return 1
    _zpr_manifest_sha=$(sha256sum "$_zpr_manifest" 2>/dev/null | awk '{print $1}')
    [ -n "$_zpr_manifest_sha" ] || return 1
    [ "$(ziyu_mount_conf_value "$_zpr_receipt" manifest_sha256)" = "$_zpr_manifest_sha" ] || return 1
    while IFS='|' read -r _zpr_relative _zpr_expected _zpr_extra; do
        [ -n "$_zpr_relative" ] || continue
        _zpr_published="$_zpr_relative"
        if [ "$_zpr_layout" = nested-system ]; then
            case "$_zpr_relative" in
                system/*) ;;
                *) _zpr_published="system/$_zpr_relative" ;;
            esac
        fi
        [ -s "$_zpr_root/$_zpr_published" ] || return 1
    done < "$_zpr_manifest"
    return 0
}

# Validate both the canonical tree and its provider-specific projection. Font
# aliases share digest reads in the Python verifier; the portable path remains available.
ziyu_provider_payload_verify() (
    _zpp_module="$1"
    _zpp_verify_root="$2"
    _zpp_verify_layout="${3:-nomount-partition-roots}"
    if type ziyu_current_payload_font >/dev/null 2>&1; then
        _zpp_font=$(ziyu_current_payload_font "$_zpp_module")
    else
        _zpp_font=$(head -n1 "$_zpp_module/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    fi
    [ "$_zpp_font" != default ] || return 0
    if [ ! -s "$_zpp_module/config/font-payload-manifest.conf" ]; then
        # Universal/classic payloads have their own integrity contracts.
        [ "$(sed -n 's/^core=//p' "$_zpp_module/config/font_runtime_legacy_v14_4.conf" 2>/dev/null | head -n1)" != physical-safe-v1 ] && return 0
        _zpp_log 'verification refused: physical payload manifest missing'
        return 1
    fi
    . "$_zpp_module/common/module_update_state.sh" || return 1
    if ! ziyu_update_verify_manifest "$_zpp_module" "$_zpp_verify_root" 0 "$_zpp_verify_layout"; then
        _zpp_log "verification failed root=$_zpp_verify_root layout=$_zpp_verify_layout reason=${ZIYU_UPDATE_FAILURE_REASON:-unknown}"
        return 1
    fi
)

# Recover an interrupted multi-partition publication before exposing a new tree.
ziyu_provider_payload_recover() (
    _zpp_root="$1"
    _zpp_journal="$_zpp_root/.ziyu-state/provider-publish.journal"
    [ -f "$_zpp_journal" ] || return 0
    _zpp_committed="$_zpp_root/.ziyu-state/provider-publish.committed"
    _zpp_pending_receipt="$_zpp_root/.ziyu-state/provider-published.next"
    _zpp_bad=0
    while IFS='|' read -r _zpp_target _zpp_backup _zpp_stage _zpp_had_target; do
        _zpp_owned_path "$_zpp_root" "$_zpp_target" && \
            _zpp_owned_path "$_zpp_root" "$_zpp_backup" && \
            _zpp_owned_path "$_zpp_root" "$_zpp_stage" || { _zpp_bad=1; continue; }
        case "$_zpp_had_target" in 0|1) ;; *) _zpp_bad=1; continue ;; esac
        if [ -f "$_zpp_committed" ]; then
            rm -rf "$_zpp_backup" || _zpp_bad=1
        elif [ -e "$_zpp_backup" ] || [ -L "$_zpp_backup" ]; then
            rm -rf "$_zpp_target" && mv "$_zpp_backup" "$_zpp_target" || _zpp_bad=1
        elif [ "$_zpp_had_target" = 0 ]; then
            # No backup means the destination did not exist before this attempt.
            rm -rf "$_zpp_target" || _zpp_bad=1
        fi
        rm -rf "$_zpp_stage" || _zpp_bad=1
    done < "$_zpp_journal"
    [ "$_zpp_bad" -eq 0 ] || return 1
    if [ -f "$_zpp_committed" ] && [ -f "$_zpp_pending_receipt" ]; then
        mv -f "$_zpp_pending_receipt" "$_zpp_root/.ziyu-state/provider-published.conf" || return 1
    else
        rm -f "$_zpp_pending_receipt" || return 1
    fi
    rm -f "$_zpp_journal" "$_zpp_committed"
)

ziyu_provider_payload_publish() (
    _zpp_module="$1"
    _zpp_root="$2"
    _zpp_layout="$3"
    _zpp_payload="$_zpp_module/.ziyu-payload"
    _zpp_payload_kind=private
    case "$_zpp_layout" in nested-system|nomount-partition-roots) ;; *) return 1 ;; esac
    if [ ! -d "$_zpp_payload" ]; then
        # Older or interrupted installs can still have an intact public tree.
        # Reuse it only after checking every artifact against the durable manifest.
        _zpp_payload="$_zpp_module"
        _zpp_update_state="$_zpp_module/common/module_update_state.sh"
        if [ ! -f "$_zpp_update_state" ]; then
            _zpp_log 'publish refused: private payload missing and verifier unavailable'
            return 1
        fi
        . "$_zpp_update_state"
        if ! type ziyu_update_verify_manifest >/dev/null 2>&1 || \
           ! ziyu_update_verify_manifest "$_zpp_module" "$_zpp_payload"; then
            _zpp_reason="${ZIYU_UPDATE_FAILURE_REASON:-payload-manifest-verification-failed}"
            _zpp_log "publish refused: legacy module tree failed manifest verification reason=$_zpp_reason"
            return 1
        fi
        _zpp_payload_kind=legacy-module-tree
        _zpp_log 'using legacy module tree after payload manifest verification'
    fi
    case "$_zpp_root" in ''|/|/system|/data|/data/adb|/data/adb/modules|*/../*|*/..|*'|'*) return 1 ;; esac
    mkdir -p "$_zpp_root/.ziyu-state" || return 1
    ziyu_provider_payload_recover "$_zpp_root" || return 1
    _zpp_state="$_zpp_root/.ziyu-state"
    _zpp_stage="$_zpp_state/provider-stage"
    _zpp_journal="$_zpp_state/provider-publish.journal"
    _zpp_receipt="$_zpp_state/provider-published.conf"
    rm -f "$_zpp_state/provider-publish.committed" || return 1
    rm -rf "$_zpp_stage" || return 1
    mkdir -p "$_zpp_stage/system" || return 1

    # Include empty destinations to retire fonts removed from the new generation.
    _zpp_parts=$(ziyu_private_partitions)
    for _zpp_part in $_zpp_parts; do
        _ziyu_private_partition_safe "$_zpp_part" || return 1
        _zpp_destination="$_zpp_stage/$_zpp_part"
        _zpp_existing="$_zpp_root/$_zpp_part"
        if [ "$_zpp_layout" = nested-system ] && [ "$_zpp_part" != system ]; then
            _zpp_destination="$_zpp_stage/system/$_zpp_part"
            _zpp_existing="$_zpp_root/system/$_zpp_part"
        fi
        # 只为本代真实有内容的分区建目录；空目录会被 NoMount 当成待注入分区，
        # 遇到设备没有的分区（如 mi_ext）会 abort 全部 VFS injection。
        _zpp_payload_has=0
        if [ -d "$_zpp_payload/$_zpp_part" ] && [ -n "$(ls -A "$_zpp_payload/$_zpp_part" 2>/dev/null)" ]; then
            _zpp_payload_has=1
        fi
        _zpp_existing_has=0
        if [ -d "$_zpp_existing" ] && [ -n "$(ls -A "$_zpp_existing" 2>/dev/null)" ]; then
            _zpp_existing_has=1
        fi
        if [ "$_zpp_payload_has" = 0 ] && [ "$_zpp_existing_has" = 0 ]; then
            continue
        fi
        mkdir -p "$_zpp_destination" || return 1
        [ "$_zpp_payload_has" = 1 ] || continue
        # Immutable font aliases remain regular files. Hard links avoid expanding
        # one font into many copies before a copying provider scans the tree.
        if ! cp -al "$_zpp_payload/$_zpp_part/." "$_zpp_destination/" 2>/dev/null; then
            rm -rf "$_zpp_destination" || return 1
            mkdir -p "$_zpp_destination" || return 1
            cp -af "$_zpp_payload/$_zpp_part/." "$_zpp_destination/" || return 1
        fi
    done
    # 槽位覆盖：把已选字体扩展到目标 ROM 的完整槽位清单（清单数据见
    # common/slot_targets，说明见 THIRD_PARTY_NOTICES.md）。只补 staging 里
    # 尚不存在的槽位，硬链接到已发布字体，几乎不增加启动耗时。
    if [ -f "$_zpp_module/common/slot_coverage.sh" ] && [ -d "$_zpp_stage/system/fonts" ]; then
        . "$_zpp_module/common/slot_coverage.sh"
        ziyu_slot_coverage_expand "$_zpp_module" "$_zpp_stage/system/fonts" "$_zpp_payload/system/fonts" || return 1
    fi
    chmod -R u=rwX,go=rX "$_zpp_stage" || return 1
    chmod 0755 "$_zpp_stage/system/bin/ziyu" "$_zpp_stage/system/bin/ziyud" \
        "$_zpp_stage/system/bin/ziyu-history" "$_zpp_stage/system/bin/ziyu-health" \
        "$_zpp_stage/system/bin/ziyu-backup" 2>/dev/null || true
    # 轻量启动：发布时不再重跑逐文件清单校验（安装/手动检查仍可用），
    # 避免首启为 172 MB 负载做一遍全量哈希。
    # ziyu_provider_payload_verify "$_zpp_module" "$_zpp_stage" "$_zpp_layout" || return 1
    : > "$_zpp_journal" || return 1
    for _zpp_part in $_zpp_parts; do
        _zpp_target="$_zpp_root/$_zpp_part"
        _zpp_backup="$_zpp_state/provider-backup-$_zpp_part"
        _zpp_candidate="$_zpp_stage/$_zpp_part"
        # Nested providers need only system; other roots are emptied to avoid old
        # top-level generations being scanned a second time.
        mkdir -p "$_zpp_candidate" || return 1
        [ ! -e "$_zpp_backup" ] && [ ! -L "$_zpp_backup" ] || return 1
        _zpp_had_target=0
        if [ -e "$_zpp_target" ] || [ -L "$_zpp_target" ]; then _zpp_had_target=1; fi
        printf '%s|%s|%s|%s\n' "$_zpp_target" "$_zpp_backup" "$_zpp_candidate" "$_zpp_had_target" >> "$_zpp_journal" || return 1
        if [ -e "$_zpp_target" ] || [ -L "$_zpp_target" ]; then
            mv "$_zpp_target" "$_zpp_backup" || { ziyu_provider_payload_recover "$_zpp_root"; return 1; }
        fi
        mv "$_zpp_candidate" "$_zpp_target" || { ziyu_provider_payload_recover "$_zpp_root"; return 1; }
    done
    # The receipt is committed only after every destination is installed.
    _zpp_manifest_sha=$(sha256sum "$_zpp_module/config/font-payload-manifest.conf" 2>/dev/null | awk '{print $1}')
    _zpp_request=$(sed -n 's/^requestId=//p' "$_zpp_module/config/font-payload-activated.conf" 2>/dev/null | head -n1)
    printf 'schema=ziyu-provider-publication-v2\nboot_id=%s\nlayout=%s\nsource=%s\nmanifest_sha256=%s\nrequest_id=%s\n' \
        "${_lbr_boot:-unknown}" "$_zpp_layout" "$_zpp_payload_kind" "$_zpp_manifest_sha" "$_zpp_request" \
        > "$_zpp_state/provider-published.next" || {
        ziyu_provider_payload_recover "$_zpp_root"; return 1;
    }
    : > "$_zpp_state/provider-publish.committed" || { ziyu_provider_payload_recover "$_zpp_root"; return 1; }
    ziyu_provider_payload_recover "$_zpp_root" || return 1
    _zpp_log "published source=$_zpp_payload_kind layout=$_zpp_layout manifest=$_zpp_manifest_sha request=$_zpp_request"
    rm -rf "$_zpp_stage"
)
