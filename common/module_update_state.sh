#!/system/bin/sh
# 模块更新状态迁移：继承当前字体负载；架构变化只登记一次显式重应用。

LUOSHU_PAYLOAD_SCHEMA_CURRENT="${LUOSHU_PAYLOAD_SCHEMA_CURRENT:-baseline-v9-rolegraph-v2}"
LUOSHU_UPDATE_ACTIVE=default
LUOSHU_UPDATE_OLD_SCHEMA=''
LUOSHU_UPDATE_REBUILD_REQUIRED=false
LUOSHU_UPDATE_FAILURE_REASON=''

# v3.1-v3.3 changed variable-font preparation, XML overlays, HyperOS metrics and
# switch caches in several independent layers.  v3.3.4 and later use the v3.0
# runtime and must not inherit a payload produced before that recovery boundary.
# The reset is one-shot across the boundary: v3.3.4 -> later keeps its payload,
# while a direct v3.1-v3.3 -> later upgrade still receives the safe reset.
luoshu_runtime_recovery_required() {
    _lrr_old="$1"
    _lrr_new="$2"
    [ -f "$_lrr_old/module.prop" ] && [ -f "$_lrr_new/module.prop" ] || return 1
    _lrr_old_code=$(sed -n 's/^versionCode=//p' "$_lrr_old/module.prop" 2>/dev/null | head -n1)
    _lrr_new_code=$(sed -n 's/^versionCode=//p' "$_lrr_new/module.prop" 2>/dev/null | head -n1)
    case "$_lrr_old_code:$_lrr_new_code" in
        *[!0-9:]*|:*|*:) return 1 ;;
    esac
    [ "$_lrr_old_code" -lt 30304 ] && [ "$_lrr_new_code" -ge 30304 ]
}

luoshu_update_payload_schema() {
    sed -n 's/^schema=//p' "$1/config/font-payload-schema.conf" 2>/dev/null | head -n1 | tr -d '\r\n'
}

luoshu_update_config_value() {
    sed -n "s/^${2}=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'
}

# The installer may carry the current schema forward for the compatibility
# runtime. Schema equality alone therefore cannot certify generated physical
# fonts: a new routing/subsetting/metric policy must take effect on the next
# explicit apply. Compare only the small builders, never the active font trees.
luoshu_update_font_builder_compatible() {
    for _lufb_relative in \
        common/hyperos_physical_policy.py \
        common/hyperos_metrics_batch.py \
        common/legacy_v14_4/hyperos_full_coverage.sh \
        common/coloros_metrics_batch.py; do
        [ -e "$1/$_lufb_relative" ] || [ -e "$2/$_lufb_relative" ] || continue
        [ -f "$1/$_lufb_relative" ] && [ -f "$2/$_lufb_relative" ] || return 1
        cmp -s "$1/$_lufb_relative" "$2/$_lufb_relative" || return 1
    done
    return 0
}


luoshu_copy_update_tree() {
    _source="$1"
    _destination="$2"
    [ -d "$_source" ] || return 0
    mkdir -p "$_destination" 2>/dev/null || return 1
    cp -al "$_source/." "$_destination/" 2>/dev/null || \
        cp -af "$_source/." "$_destination/" 2>/dev/null || \
        cp -rfp "$_source/." "$_destination/" 2>/dev/null
}

luoshu_update_config_is_volatile() {
    case "$1" in
        version_notes.conf|switch_task.conf|mix_task.conf|axes_task.conf|emoji_task.conf|\
        text_reboot_required.conf|font_weight_reboot_required.conf|emoji_reboot_required.conf|\
        webui_font_list.json|webui_font_list.key|native_font_index.json|native_font_index.key|\
        composite_progress.json|mix_last_error.txt|app_install_pending|app_install_state.conf|\
        app_install_manual|font-payload-rebuild-pending.conf|font-payload-reapply-notified.conf|font-boot-failures|\
        font-payload-quarantine.conf|mount_compat.conf|self-mount.conf|\
        self-mount-required.conf|device-font-load-verification.conf|\
        device-font-cache-pending.conf|device-font-cache-failures.conf|\
        device-font-engine.conf|device-font-installed.conf|device-font-dynamic-mount.conf|\
        device-font-load-verification.json|device-font-manager-dump.txt|\
        device-font-mount-evidence.txt|*.pid|*.pid.task|*.tmp|*.tmp.*)
            return 0
            ;;
    esac
    return 1
}

luoshu_update_payload_partitions() {
    _lup_config_module="${1:-${MODULE_DIR:-${MODDIR:-/data/adb/modules/LuoShu}}}"
    if type luoshu_private_partitions >/dev/null 2>&1; then
        ( MODULE_DIR="$_lup_config_module"; MODDIR="$MODULE_DIR"; luoshu_private_partitions )
        return
    fi
    _lup_partitions='system system_ext product vendor odm oem my_product my_engineering my_company my_preload my_region my_stock oplus_product oplus_engineering oplus_version oplus_region mi_ext cust hw_product'
    printf '%s\n' "$_lup_partitions"
    [ -f "$_lup_config_module/config/device_font_partitions.conf" ] || return 0
    while IFS= read -r _lup_extra; do
        case "$_lup_extra" in ''|*[!A-Za-z0-9_]*|[0-9]*|_*|data|proc|sys|dev|mnt|storage|sdcard|apex|metadata|cache|tmp|config|acct|linkerconfig|debug_ramdisk|vendor_dlkm|odm_dlkm|system_dlkm) continue ;; esac
        case " $_lup_partitions " in *" $_lup_extra "*) continue ;; esac
        printf '%s\n' "$_lup_extra"
        _lup_partitions="$_lup_partitions $_lup_extra"
    done < "$_lup_config_module/config/device_font_partitions.conf"
}

luoshu_update_payload_root() {
    # Private storage is the durable artifact, while the public partition view
    # may be absent or only partially projected in a flashing namespace.
    if [ -d "$1/.luoshu-payload" ]; then
        printf '%s/.luoshu-payload\n' "$1"
    else
        printf '%s\n' "$1"
    fi
}

luoshu_update_verify_manifest() {
    _luvm_module="$1"
    _luvm_root="$2"
    _luvm_manifest="$_luvm_module/config/font-payload-manifest.conf"
    [ -e "$_luvm_manifest" ] || return 0
    [ -s "$_luvm_manifest" ] || { LUOSHU_UPDATE_FAILURE_REASON=empty-payload-manifest; return 1; }
    _luvm_normalize="${3:-0}"
    _luvm_records=0
    _luvm_tmp="$_luvm_manifest.tmp.$$"
    [ "$_luvm_normalize" != 1 ] || : > "$_luvm_tmp" || return 1
    while IFS='|' read -r _luvm_rel _luvm_expected _luvm_extra || [ -n "$_luvm_rel" ]; do
        [ -n "$_luvm_rel" ] || continue
        case "$_luvm_rel" in /*|../*|*/../*|*/..|*\\*|*//* )
            LUOSHU_UPDATE_FAILURE_REASON=unsafe-payload-manifest-path; return 1 ;;
        esac
        _luvm_partition=${_luvm_rel%%/*}
        case " $(luoshu_update_payload_partitions "$_luvm_module" | tr '\n' ' ') " in *" $_luvm_partition "*) ;; *)
            LUOSHU_UPDATE_FAILURE_REASON=unsupported-payload-manifest-partition; return 1 ;;
        esac
        [ -s "$_luvm_root/$_luvm_rel" ] || {
            LUOSHU_UPDATE_FAILURE_REASON="payload-artifact-missing:$_luvm_rel"; return 1;
        }
        case "$_luvm_expected" in ''|*[!0-9a-fA-F]*)
            LUOSHU_UPDATE_FAILURE_REASON=invalid-payload-manifest-hash; return 1 ;;
        esac
        _luvm_actual=$(sha256sum "$_luvm_root/$_luvm_rel" 2>/dev/null | awk '{print $1}')
        if [ "${#_luvm_expected}" -eq 64 ]; then
            [ "$_luvm_actual" = "$(printf '%s' "$_luvm_expected" | tr 'A-F' 'a-f')" ] || {
                LUOSHU_UPDATE_FAILURE_REASON="payload-artifact-hash-mismatch:$_luvm_rel"; return 1;
            }
        else
            # Older supported payloads used POSIX cksum CRC|size. Validate
            # that contract, then normalize only the staged copy for the new
            # SHA256/PID1 verifier; never rewrite the running module's ledger.
            case "$_luvm_expected:$_luvm_extra" in *[!0-9:]*|:*|*:) LUOSHU_UPDATE_FAILURE_REASON=invalid-payload-manifest-hash; return 1 ;; esac
            _luvm_crc=$(cksum "$_luvm_root/$_luvm_rel" 2>/dev/null | awk '{print $1 "|" $2}')
            [ "$_luvm_crc" = "$_luvm_expected|$_luvm_extra" ] || {
                LUOSHU_UPDATE_FAILURE_REASON="payload-artifact-checksum-mismatch:$_luvm_rel"; return 1;
            }
        fi
        [ "${#_luvm_actual}" -eq 64 ] || { LUOSHU_UPDATE_FAILURE_REASON=payload-sha256-unavailable; return 1; }
        if [ "$_luvm_normalize" = 1 ]; then
            printf '%s|%s\n' "$_luvm_rel" "$_luvm_actual" >> "$_luvm_tmp" || return 1
        fi
        _luvm_records=$((_luvm_records + 1))
    done < "$_luvm_manifest"
    [ "$_luvm_records" -gt 0 ] || { LUOSHU_UPDATE_FAILURE_REASON=empty-payload-manifest; return 1; }
    [ "$_luvm_normalize" != 1 ] || mv -f "$_luvm_tmp" "$_luvm_manifest" || return 1
    return 0
}

luoshu_update_has_font_payload() {
    _module=$(luoshu_update_payload_root "$1")
    for _partition in $(luoshu_update_payload_partitions "$1"); do
        case "$_partition" in
            system) _directory="$_module/system/fonts" ;;
            *) _directory="$_module/$_partition" ;;
        esac
        [ -d "$_directory" ] || continue
        # Physical aliases are symlinks to immutable *.font anchors. Looking
        # only for regular TTFs rejects a valid font-store-only installation.
        find "$_directory" -type f -size +0c \( -iname '*.ttf' -o -iname '*.otf' -o -iname '*.ttc' -o -iname '*.font' \) \
            -print -quit 2>/dev/null | grep -q . && return 0
    done
    return 1
}

luoshu_clear_update_volatile() {
    _module="$1"
    if type luoshu_font_lock_force_clear >/dev/null 2>&1; then
        luoshu_font_lock_force_clear "$_module/.font_switch.lock" >/dev/null 2>&1 || true
    else
        rm -f "$_module/.font_switch.lock/pid" 2>/dev/null || true
        rmdir "$_module/.font_switch.lock" 2>/dev/null || true
        rm -f "$_module"/.font_switch.lock.owner.* 2>/dev/null || true
    fi
    rm -f \
        "$_module/config/switch_task.conf" \
        "$_module/config/mix_task.conf" \
        "$_module/config/axes_task.conf" \
        "$_module/config/emoji_task.conf" \
        "$_module/config/text_reboot_required.conf" \
        "$_module/config/font_weight_reboot_required.conf" \
        "$_module/config/emoji_reboot_required.conf" \
        "$_module/config/webui_font_list.json" \
        "$_module/config/webui_font_list.key" \
        "$_module/config/native_font_index.json" \
        "$_module/config/native_font_index.key" \
        "$_module/config/composite_progress.json" \
        "$_module/config/mix_last_error.txt" \
        "$_module/config/app_install_pending" \
        "$_module/config/app_install_state.conf" \
        "$_module/config/app_install_manual" \
        "$_module/config/font-payload-rebuild-pending.conf" \
        "$_module/config/font-payload-reapply-notified.conf" \
        "$_module/config/device-font-cache-pending.conf" \
        "$_module/config/device-font-cache-failures.conf" \
        "$_module/config/device-font-engine.conf" \
        "$_module/config/device-font-installed.conf" \
        "$_module/config/device-font-dynamic-mount.conf" \
        "$_module/config/mount_compat.conf" \
        "$_module/config/self-mount.conf" \
        "$_module/config/self-mount-required.conf" \
        "$_module/config/device-font-load-verification.conf" \
        "$_module/config/device-font-load-verification.json" \
        "$_module/config/device-font-manager-dump.txt" \
        "$_module/config/device-font-mount-evidence.txt" \
        "$_module/.font_switch.lock" \
        "$_module/.font-payload-commit.ok" 2>/dev/null || true
    rm -f "$_module/config"/*.pid "$_module/config"/*.pid.task \
        "$_module/config"/*.tmp "$_module/config"/*.tmp.* 2>/dev/null || true
    rm -rf "$_module"/.font-payload-stage.* "$_module"/.font-payload-backup.* 2>/dev/null || true
    if [ -e "$_module/.device-font-cache.lock" ]; then
        if type luoshu_font_lock_reap_stale >/dev/null 2>&1; then
            luoshu_font_lock_reap_stale "$_module/.device-font-cache.lock" >/dev/null 2>&1 || true
        fi
    fi
}

luoshu_migrate_update_config() {
    _old="$1"
    _new="$2"
    [ -d "$_old/config" ] || return 0
    mkdir -p "$_new/config" 2>/dev/null || return 1
    for _source in "$_old/config"/*; do
        [ -f "$_source" ] || continue
        _name=${_source##*/}
        luoshu_update_config_is_volatile "$_name" && continue
        cp -af "$_source" "$_new/config/$_name" 2>/dev/null || \
            cp -fp "$_source" "$_new/config/$_name" 2>/dev/null || return 1
    done
    return 0
}

luoshu_migrate_update_cache() {
    _old="$1"
    _new="$2"
    _schema_compatible="${3:-false}"
    _builder_compatible="${4:-true}"
    # Generated fonts can be large and are no longer reusable after a builder
    # change. Do not copy them into the update only to invalidate them on apply;
    # cross-filesystem installs would allocate a second full set. The active
    # payload is migrated separately and continues to work until explicit apply.
    for _relative in \
        cache/full-composite-v12 \
        cache/auto-multiweight-mix/composites-v9 \
        cache/auto-multiweight-mix/prepared-v8 \
        cache/full-composite-v7 \
        cache/auto-multiweight-mix/composites-v3; do
        rm -rf "$_new/$_relative" 2>/dev/null || true
        [ "$_schema_compatible" = true ] && [ "$_builder_compatible" = true ] || continue
        [ -d "$_old/$_relative" ] || continue
        mkdir -p "${_new}/${_relative%/*}" 2>/dev/null || continue
        luoshu_copy_update_tree "$_old/$_relative" "$_new/$_relative" || true
    done
    mkdir -p "$_new/cache" 2>/dev/null || true
    for _probe in "$_old/cache"/runtime_probe.*.ok; do
        [ -f "$_probe" ] || continue
        cp -al "$_probe" "$_new/cache/${_probe##*/}" 2>/dev/null || \
            cp -af "$_probe" "$_new/cache/${_probe##*/}" 2>/dev/null || true
    done

    # Config contains persistent immutable artifacts as directories. The old migrator copied only
    # regular files from config/*, silently dropping the entire device alignment cache on update.
    # Metric/source caches are content-addressed and safe across releases. A device payload cache is
    # retained only when its payload schema and physical-font builder agree.
    for _relative in cache/auto-multiweight-mix/source-meta-v1 config/metrics_cache config/font-config-source; do
        [ -d "$_old/$_relative" ] || continue
        rm -rf "$_new/$_relative" 2>/dev/null || true
        mkdir -p "${_new}/${_relative%/*}" 2>/dev/null || continue
        luoshu_copy_update_tree "$_old/$_relative" "$_new/$_relative" || true
    done
    if [ "$_schema_compatible" = true ] && [ "$_builder_compatible" = true ]; then
        if [ -d "$_old/config/device-font-cache" ]; then
            rm -rf "$_new/config/device-font-cache" 2>/dev/null || true
            mkdir -p "$_new/config" 2>/dev/null || true
            luoshu_copy_update_tree "$_old/config/device-font-cache" "$_new/config/device-font-cache" || true
        fi
    else
        # Only discard derived artifacts in the replacement installation. Keep
        # the active installation and its mounted font payload untouched.
        rm -rf "$_new/config/device-font-cache" 2>/dev/null || true
    fi
}

luoshu_migrate_active_install() {
    _old="$1"
    _new="$2"
    [ -f "$_old/module.prop" ] || return 2
    [ "$_old" != "$_new" ] || return 2
    LUOSHU_UPDATE_FAILURE_REASON=migration-copy-failed
    _lup_payload_root=$(luoshu_update_payload_root "$_old")

    _active=$(head -n1 "$_old/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_active" ] || _active=default
    _old_schema=$(luoshu_update_payload_schema "$_old")
    LUOSHU_UPDATE_ACTIVE="$_active"
    LUOSHU_UPDATE_OLD_SCHEMA="$_old_schema"
    LUOSHU_UPDATE_REBUILD_REQUIRED=false
    [ "$_active" = default ] || [ "$_old_schema" = "$LUOSHU_PAYLOAD_SCHEMA_CURRENT" ] || LUOSHU_UPDATE_REBUILD_REQUIRED=true
    _lup_builder_compatible=false
    luoshu_update_font_builder_compatible "$_old" "$_new" && _lup_builder_compatible=true
    _lup_rebuild_reason=schema-upgrade
    # Reinstalling before applying must not lose a previous builder migration.
    # Explicit successful switch/mix commits already remove this marker.
    if [ "$(luoshu_update_config_value "$_old/config/font-payload-rebuild-pending.conf" reason)" = font-builder-changed ]; then
        _lup_builder_compatible=false
    fi
    if [ "$_active" != default ] && [ "$_lup_builder_compatible" != true ]; then
        LUOSHU_UPDATE_REBUILD_REQUIRED=true
        _lup_rebuild_reason=font-builder-changed
    fi
    # Copying an already generated mix does not need the original recipe or
    # source fonts. Those are only needed for the user's next explicit apply.
    if [ "$_active" != default ] && ! luoshu_update_has_font_payload "$_old"; then
        LUOSHU_UPDATE_FAILURE_REASON=active-font-payload-missing
        return 1
    fi
    if [ "$_active" != default ]; then
        luoshu_update_verify_manifest "$_old" "$_lup_payload_root" || return 1
        if [ -s "$_old/config/universal-font-runtime.conf" ]; then
            for _lup_artifact in deployment.json font-plan.json artifact-manifest.json; do
                [ -s "$_lup_payload_root/.luoshu-runtime/deployment/$_lup_artifact" ] || {
                    LUOSHU_UPDATE_FAILURE_REASON="universal-artifact-missing:$_lup_artifact"; return 1;
                }
            done
        fi
    fi

    mkdir -p "$_new/config" "$_new/system/fonts" 2>/dev/null || return 1
    luoshu_migrate_update_config "$_old" "$_new" || return 1

    # Keep the new release's system/bin runtime, but migrate both system font
    # trees and every supported OEM partition from the active installation.
    for _relative in system/fonts system/etc; do
        [ -d "$_lup_payload_root/$_relative" ] || continue
        rm -rf "$_new/$_relative" 2>/dev/null || return 1
        mkdir -p "${_new}/${_relative%/*}" 2>/dev/null || return 1
        luoshu_copy_update_tree "$_lup_payload_root/$_relative" "$_new/$_relative" || return 1
    done
    for _partition in $(luoshu_update_payload_partitions "$_old"); do
        [ "$_partition" != system ] || continue
        [ -d "$_lup_payload_root/$_partition" ] || continue
        rm -rf "$_new/$_partition" 2>/dev/null || return 1
        mkdir -p "$_new" 2>/dev/null || return 1
        luoshu_copy_update_tree "$_lup_payload_root/$_partition" "$_new/$_partition" || return 1
    done
    if [ "$_active" != default ] && [ -s "$_old/config/universal-font-runtime.conf" ]; then
        # Keep deployment artifacts beside the fonts they describe, without
        # overwriting the new release's executable runtime/core.
        luoshu_copy_update_tree "$_lup_payload_root/.luoshu-runtime/deployment" \
            "$_new/.luoshu-payload/.luoshu-runtime/deployment" || return 1
        luoshu_copy_update_tree "$_lup_payload_root/.luoshu-dynamic" \
            "$_new/.luoshu-payload/.luoshu-dynamic" || return 1
    fi
    if [ "$_active" != default ]; then
        if [ ! -e "$_new/config/font-payload-manifest.conf" ] &&
           [ "$(luoshu_update_config_value "$_new/config/font_runtime_legacy_v14_4.conf" core)" = physical-safe-v1 ] &&
           [ "$(luoshu_update_config_value "$_new/config/font-payload-schema.conf" schema)" = legacy-physical-safe-v1 ]; then
            [ -f "$_new/common/physical_payload_manifest.sh" ] &&
                . "$_new/common/physical_payload_manifest.sh" &&
                luoshu_physical_manifest_ensure "$_new" "$_new" || {
                    LUOSHU_UPDATE_FAILURE_REASON=physical-integrity-contract-missing; return 1;
                }
        fi
        luoshu_update_verify_manifest "$_new" "$_new" 1 || return 1
    fi

    _schema_compatible=false
    [ "$_old_schema" = "$LUOSHU_PAYLOAD_SCHEMA_CURRENT" ] && _schema_compatible=true
    luoshu_migrate_update_cache "$_old" "$_new" "$_schema_compatible" "$_lup_builder_compatible"
    luoshu_clear_update_volatile "$_new"
    # active_font.conf is the selection authority. Write the captured value after cleanup so a
    # packaged default or a partial config copy can never relabel an inherited composite as default.
    printf '%s\n' "$_active" >"$_new/config/active_font.conf" || return 1
    if [ "$LUOSHU_UPDATE_REBUILD_REQUIRED" = true ]; then
        {
            printf 'state=awaiting-explicit-apply\n'
            printf 'mode=preserve-current\n'
            printf 'reason=%s\n' "$_lup_rebuild_reason"
            printf 'font=%s\n' "$_active"
            printf 'oldSchema=%s\n' "${_old_schema:-missing}"
            printf 'newSchema=%s\n' "$LUOSHU_PAYLOAD_SCHEMA_CURRENT"
            printf 'time=%s\n' "$(date +%s)"
        } > "$_new/config/font-payload-rebuild-pending.conf" 2>/dev/null || return 1
        rm -f "$_new/config/font-payload-reapply-notified.conf" 2>/dev/null || true
    fi
    find "$_new/config" -type d -exec chmod 0755 {} \; 2>/dev/null || true
    find "$_new/config" -type f -exec chmod 0644 {} \; 2>/dev/null || true
    find "$_new/system/fonts" -type f -exec chmod 0644 {} \; 2>/dev/null || true
    LUOSHU_UPDATE_FAILURE_REASON=''
    return 0
}
