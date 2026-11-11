#!/bin/sh
# A function defined in several files is resolved purely by source order, and the last definition
# silently wins. That has already cost a whole feature: a third definition of
# font_config_enable_for_payload shadowed the two that actually generated the no-hook XML overlay,
# so the module fell back to file-slot mapping on every device without any error being reported.
#
# Overriding is a legitimate technique here (later layers deliberately replace earlier ones), so this
# guard does not forbid it. It pins the current set: adding a new duplicate, or a new file that
# redefines an existing function, fails until the intent is recorded in the allowlist below.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT/scripts/assert.sh"
CASE='重复函数定义'

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

# The health command now lives in common/ outside the replaceable payload.
# It is executed (never sourced) by system/bin/ziyu-health, so these private
# command helpers do not override the independently executed app_status reader.
# app_bridge executes that reader; it retains private helpers for its other commands.
# Pin the exact files rather than allowing an arbitrary extra definition.
# an additional definition must still fail instead of inheriting this exception.
for fn in mount_engine read_value root_manager read_first_line; do
    case "$fn" in
        read_value) expected='common/font_switch_task.sh common/ziyu_health.sh' ;;
        root_manager) expected='common/app_bridge.sh common/app_status.sh common/ziyu_health.sh' ;;
        read_first_line) expected='common/app_bridge.sh common/app_status.sh' ;;
        *) expected='common/app_status.sh common/ziyu_health.sh' ;;
    esac
    actual=$(grep -l "^${fn}()" "$ROOT"/common/*.sh "$ROOT"/*.sh 2>/dev/null | sed "s|$ROOT/||" | sort | tr '\n' ' ' | sed 's/ $//')
    [ "$actual" = "$expected" ] || fail "independent health helper changed: $fn -> $actual"
done

# Every function that is intentionally overridden by a later-loaded layer, with the reason.
cat > "$TMP/allow" <<'ALLOW'
_copy_as_inventory
_device_font_inventory_target
_font_anchor
_ziyu_config_weight_source
_ziyu_font_config_specs
_verify_font_copy
apply_font_by_rom
clear_managed_text_fonts
copy_as_coloros
copy_as_hyperos
current_boot_id
fail_json
font_config_disable
font_config_enable_for_payload
font_config_mark_boot_success
font_config_prepare_payload_weights
font_validate_global
get_all_coloros_files
get_all_coloros_names
get_all_hyperos_files
import_copy_unique
import_detect_family
import_is_italic_name
import_probe_metadata
import_weight_label
json_escape
ziyu_copy_tree_bounded
ziyu_current_boot_id
ziyu_detect_mount_engine
ziyu_dynamic_targets_apply
ziyu_font_lock_acquire
ziyu_font_lock_active
ziyu_font_lock_boot_id
ziyu_font_lock_created
ziyu_font_lock_force_clear
ziyu_font_lock_forget_owned
ziyu_font_lock_owned_record
ziyu_font_lock_pid
ziyu_font_lock_recent_token
ziyu_font_lock_reap_stale
ziyu_font_lock_release
ziyu_font_lock_starttime
ziyu_font_lock_token
ziyu_font_validate_global_cached
ziyu_hybrid_backend
ziyu_migrate_active_install
ziyu_mount_backend
ziyu_mount_preflight
ziyu_mount_verify_active
ziyu_payload_build_manifest
ziyu_payload_quarantine
ziyu_payload_transaction_abort
ziyu_payload_transaction_begin
ziyu_payload_transaction_commit
ziyu_payload_transaction_rollback
ziyu_payload_validate_current
ziyu_payload_validate_manifest_fast
ziyu_payload_validate_manifest_full
ziyu_process_starttime
ziyu_used_partitions
python_run
read_prop
read_first_line
mount_engine
read_value
root_manager
ALLOW

# v4.0.0 deliberately sources common/module_update_hotfix_v4.sh after
# common/module_update_state.sh. Its ziyu_migrate_active_install override preserves the selected
# font while isolating/rebuilding payloads whose schema is incompatible during an in-place update.
# Keep that override registered here so a different accidental third definition still fails below.
#
# common/font_switch_lock.sh deliberately mirrors only the current identity-lock functions used by
# common/util_functions.sh. The isolated v14.4 switch backend sources this safety-only file after its
# old utility layer, preserving PID/starttime/boot-id/token concurrency protection without importing
# the v4 device-template / slot / XML font generation pipeline.

: > "$TMP/defs"
for f in "$ROOT"/common/*.sh "$ROOT"/*.sh; do
    [ -f "$f" ] || continue
    grep -o '^[a-zA-Z_][a-zA-Z0-9_]*()' "$f" 2>/dev/null | tr -d '()' | while IFS= read -r fn; do
        printf '%s\n' "$fn" >> "$TMP/defs"
    done
done

sort "$TMP/defs" | uniq -d | sort > "$TMP/dupes"
sort "$TMP/allow" > "$TMP/allow-sorted"

comm -23 "$TMP/dupes" "$TMP/allow-sorted" > "$TMP/unexpected"
if [ -s "$TMP/unexpected" ]; then
    printf '以下函数在多个文件中重复定义，但不在允许清单里：\n' >&2
    while IFS= read -r fn; do
        printf '  %s  ->  %s\n' "$fn" \
            "$(grep -l "^${fn}()" "$ROOT"/common/*.sh "$ROOT"/*.sh 2>/dev/null | sed "s|$ROOT/||" | tr '\n' ' ')" >&2
    done < "$TMP/unexpected"
    printf '如果这是有意的分层覆盖，请把函数名加入本测试的允许清单并说明原因。\n' >&2
    fail "$(wc -l < "$TMP/unexpected" | tr -d '[:space:]') 个未登记的重复定义"
fi

# The allowlist must not rot: an entry that is no longer duplicated should be removed.
comm -13 "$TMP/dupes" "$TMP/allow-sorted" > "$TMP/stale"
if [ -s "$TMP/stale" ]; then
    printf '允许清单里这些函数已经不再重复定义，请删除条目：\n' >&2
    sed 's/^/  /' "$TMP/stale" >&2
    fail "$(wc -l < "$TMP/stale" | tr -d '[:space:]') 个过期的允许条目"
fi

printf 'Duplicate shell function guard passed (%s 个已登记的分层覆盖).\n' \
    "$(wc -l < "$TMP/dupes" | tr -d '[:space:]')"
