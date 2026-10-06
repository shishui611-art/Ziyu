#!/system/bin/sh
# Publish an ordinary module tree before the provider scans it. No bind views.
set +e

_zpp_owned_path() {
    case "$2" in "$1"/*) ;; *) return 1 ;; esac
    case "${2#"$1"/}" in ''|../*|*/../*|*/..|*'|'*) return 1 ;; esac
}

# Recover an interrupted multi-partition publication before exposing a new tree.
ziyu_provider_payload_recover() (
    _zpp_root="$1"
    _zpp_journal="$_zpp_root/.ziyu-state/provider-publish.journal"
    [ -f "$_zpp_journal" ] || return 0
    _zpp_committed="$_zpp_root/.ziyu-state/provider-publish.committed"
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
    rm -f "$_zpp_journal" "$_zpp_committed"
)

ziyu_provider_payload_publish() (
    _zpp_module="$1"
    _zpp_root="$2"
    _zpp_layout="$3"
    _zpp_payload="$_zpp_module/.luoshu-payload"
    case "$_zpp_layout" in nested-system|nomount-partition-roots) ;; *) return 1 ;; esac
    [ -d "$_zpp_payload" ] || return 1
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
    _zpp_parts=$(luoshu_private_partitions)
    for _zpp_part in $_zpp_parts; do
        _luoshu_private_partition_safe "$_zpp_part" || return 1
        _zpp_destination="$_zpp_stage/$_zpp_part"
        if [ "$_zpp_layout" = nested-system ] && [ "$_zpp_part" != system ]; then
            _zpp_destination="$_zpp_stage/system/$_zpp_part"
        fi
        mkdir -p "$_zpp_destination" || return 1
        [ -d "$_zpp_payload/$_zpp_part" ] || continue
        cp -af "$_zpp_payload/$_zpp_part/." "$_zpp_destination/" || return 1
    done
    chmod -R u=rwX,go=rX "$_zpp_stage" || return 1
    chmod 0755 "$_zpp_stage/system/bin/洛书" "$_zpp_stage/system/bin/luoshud" \
        "$_zpp_stage/system/bin/luoshu-history" "$_zpp_stage/system/bin/luoshu-health" \
        "$_zpp_stage/system/bin/luoshu-backup" 2>/dev/null || true
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
    printf 'boot_id=%s\nlayout=%s\n' "${_lbr_boot:-unknown}" "$_zpp_layout" > "$_zpp_receipt.tmp" || {
        ziyu_provider_payload_recover "$_zpp_root"; return 1;
    }
    mv -f "$_zpp_receipt.tmp" "$_zpp_receipt" || { ziyu_provider_payload_recover "$_zpp_root"; return 1; }
    : > "$_zpp_state/provider-publish.committed" || { ziyu_provider_payload_recover "$_zpp_root"; return 1; }
    ziyu_provider_payload_recover "$_zpp_root" || return 1
    rm -rf "$_zpp_stage"
)
