#!/system/bin/sh
# Extended Android/OEM font-configuration partition map. Source after font_config_runtime.sh.
set +e

_ziyu_font_config_emit_partition() {
    _lfcp_key="$1"
    _lfcp_real_etc="$2"
    _lfcp_overlay_root="$3"
    shift 3
    for _lfcp_name in "$@"; do
        printf '%s/%s|%s/%s|%s/etc/%s|%s/fonts\n' \
            "$_lfcp_key" "$_lfcp_name" \
            "$_lfcp_real_etc" "$_lfcp_name" \
            "$_lfcp_overlay_root" "$_lfcp_name" \
            "$_lfcp_overlay_root"
    done
}

_ziyu_font_config_xml_names() {
    printf '%s\n' 'fonts.xml font_fallback.xml fonts_customization.xml font_customization.xml mi_fonts_customization.xml oplus_fonts_customization.xml oplus_font_customization.xml oppo_fonts_customization.xml coloros_fonts_customization.xml vivo_fonts.xml vivo_fonts_customization.xml vivo_font_customization.xml origin_fonts.xml originos_fonts.xml iqoo_fonts.xml flyme_fonts.xml flyme_fonts_customization.xml flyme_font_customization.xml meizu_fonts.xml meizu_fonts_customization.xml mz_fonts.xml fonts_vendor.xml fonts_overlay.xml'
}

_ziyu_font_config_resolve_etc() {
    _lfcr_root="$1"
    _lfcr_legacy="$2"
    _lfcr_default="$3"
    if [ -n "$_lfcr_root" ]; then
        printf '%s/etc\n' "${_lfcr_root%/}"
    elif [ -n "$_lfcr_legacy" ]; then
        printf '%s\n' "${_lfcr_legacy%/}"
    else
        printf '%s\n' "$_lfcr_default"
    fi
}

_ziyu_font_config_partition_rows() {
    _lfcp_payload="$(_ziyu_font_config_payload_root)"
    printf 'system|%s|%s/system\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_SYSTEM_ROOT:-}" "${ZIYU_SYSTEM_ETC_ROOT:-}" /system/etc)" "$_lfcp_payload"
    printf 'system_ext|%s|%s/system_ext\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_SYSTEM_EXT_ROOT:-}" "${ZIYU_SYSTEM_EXT_ETC_ROOT:-}" /system_ext/etc)" "$_lfcp_payload"
    printf 'product|%s|%s/product\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_PRODUCT_ROOT:-}" "${ZIYU_PRODUCT_ETC_ROOT:-}" /product/etc)" "$_lfcp_payload"
    printf 'vendor|%s|%s/vendor\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_VENDOR_ROOT:-}" "${ZIYU_VENDOR_ETC_ROOT:-}" /vendor/etc)" "$_lfcp_payload"
    printf 'odm|%s|%s/odm\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_ODM_ROOT:-}" "${ZIYU_ODM_ETC_ROOT:-}" /odm/etc)" "$_lfcp_payload"
    printf 'oem|%s|%s/oem\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_OEM_ROOT:-}" "${ZIYU_OEM_ETC_ROOT:-}" /oem/etc)" "$_lfcp_payload"
    printf 'my_product|%s|%s/my_product\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MY_PRODUCT_ROOT:-}" "${ZIYU_MY_PRODUCT_ETC_ROOT:-}" /my_product/etc)" "$_lfcp_payload"
    printf 'my_engineering|%s|%s/my_engineering\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MY_ENGINEERING_ROOT:-}" "${ZIYU_MY_ENGINEERING_ETC_ROOT:-}" /my_engineering/etc)" "$_lfcp_payload"
    printf 'my_company|%s|%s/my_company\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MY_COMPANY_ROOT:-}" "${ZIYU_MY_COMPANY_ETC_ROOT:-}" /my_company/etc)" "$_lfcp_payload"
    printf 'my_preload|%s|%s/my_preload\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MY_PRELOAD_ROOT:-}" "${ZIYU_MY_PRELOAD_ETC_ROOT:-}" /my_preload/etc)" "$_lfcp_payload"
    printf 'my_region|%s|%s/my_region\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MY_REGION_ROOT:-}" "${ZIYU_MY_REGION_ETC_ROOT:-}" /my_region/etc)" "$_lfcp_payload"
    printf 'my_stock|%s|%s/my_stock\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MY_STOCK_ROOT:-}" "${ZIYU_MY_STOCK_ETC_ROOT:-}" /my_stock/etc)" "$_lfcp_payload"
    printf 'oplus_product|%s|%s/oplus_product\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_OPLUS_PRODUCT_ROOT:-}" "${ZIYU_OPLUS_PRODUCT_ETC_ROOT:-}" /oplus_product/etc)" "$_lfcp_payload"
    printf 'oplus_engineering|%s|%s/oplus_engineering\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_OPLUS_ENGINEERING_ROOT:-}" "${ZIYU_OPLUS_ENGINEERING_ETC_ROOT:-}" /oplus_engineering/etc)" "$_lfcp_payload"
    printf 'oplus_version|%s|%s/oplus_version\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_OPLUS_VERSION_ROOT:-}" "${ZIYU_OPLUS_VERSION_ETC_ROOT:-}" /oplus_version/etc)" "$_lfcp_payload"
    printf 'oplus_region|%s|%s/oplus_region\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_OPLUS_REGION_ROOT:-}" "${ZIYU_OPLUS_REGION_ETC_ROOT:-}" /oplus_region/etc)" "$_lfcp_payload"
    printf 'mi_ext|%s|%s/mi_ext\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_MI_EXT_ROOT:-}" "${ZIYU_MI_EXT_ETC_ROOT:-}" /mi_ext/etc)" "$_lfcp_payload"
    printf 'cust|%s|%s/cust\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_CUST_ROOT:-}" "${ZIYU_CUST_ETC_ROOT:-}" /cust/etc)" "$_lfcp_payload"
    printf 'hw_product|%s|%s/hw_product\n' "$(_ziyu_font_config_resolve_etc "${ZIYU_HW_PRODUCT_ROOT:-}" "${ZIYU_HW_PRODUCT_ETC_ROOT:-}" /hw_product/etc)" "$_lfcp_payload"

    # Scanner-discovered OEM partitions are intentionally data-driven. Only safe
    # root-level names from device_font_partitions.conf are accepted, and only
    # when a real etc directory exists on this ROM.
    _lfcp_module="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}"
    _lfcp_manifest="$_lfcp_module/config/device_font_partitions.conf"
    if [ -f "$_lfcp_manifest" ]; then
        while IFS= read -r _lfcp_part; do
            case "$_lfcp_part" in
                ''|*[!A-Za-z0-9_]*|[0-9]*|_*|data|proc|sys|dev|mnt|storage|sdcard|apex|metadata|cache|tmp|config|acct|linkerconfig|debug_ramdisk|vendor_dlkm|odm_dlkm|system_dlkm) continue ;;
            esac
            case " system system_ext product vendor odm oem my_product my_engineering my_company my_preload my_region my_stock oplus_product oplus_engineering oplus_version oplus_region mi_ext cust hw_product " in
                *" $_lfcp_part "*) continue ;;
            esac
            _lfcp_real_etc=''
            [ -d "/$_lfcp_part/etc" ] && _lfcp_real_etc="/$_lfcp_part/etc"
            [ -n "$_lfcp_real_etc" ] || [ ! -d "/system/$_lfcp_part/etc" ] || _lfcp_real_etc="/system/$_lfcp_part/etc"
            [ -n "$_lfcp_real_etc" ] || continue
            printf '%s|%s|%s/%s\n' "$_lfcp_part" "$_lfcp_real_etc" "$_lfcp_payload" "$_lfcp_part"
        done < "$_lfcp_manifest"
    fi
}

# key | real XML | module overlay XML | font directory referenced by that document
_ziyu_font_config_specs() {
    _lfcp_names="$(_ziyu_font_config_xml_names)"
    while IFS='|' read -r _lfcp_key _lfcp_real_etc _lfcp_overlay; do
        [ -n "$_lfcp_key" ] && [ -n "$_lfcp_real_etc" ] && [ -n "$_lfcp_overlay" ] || continue
        # Keep the curated list as a stable baseline, then discover vendor-specific font XML that
        # actually exists on this ROM. Unknown OEM names must not silently escape the no-hook path.
        _lfcp_all="$_lfcp_names"
        for _lfcp_found in             "$_lfcp_real_etc"/*font*.xml             "$_lfcp_real_etc"/*Font*.xml             "$_lfcp_real_etc"/*FONT*.xml; do
            [ -f "$_lfcp_found" ] || continue
            _lfcp_base="${_lfcp_found##*/}"
            case " $_lfcp_all " in
                *" $_lfcp_base "*) continue ;;
            esac
            _lfcp_all="$_lfcp_all $_lfcp_base"
        done
        # shellcheck disable=SC2086
        _ziyu_font_config_emit_partition "$_lfcp_key" "$_lfcp_real_etc" "$_lfcp_overlay" $_lfcp_all
    done <<EOF_ZIYU_PARTITIONS
$(_ziyu_font_config_partition_rows)
EOF_ZIYU_PARTITIONS
}

_ziyu_coloros_helper="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}/common/coloros_global.sh"
[ -f "$_ziyu_coloros_helper" ] && . "$_ziyu_coloros_helper"
_ziyu_origin_flyme_helper="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}/common/origin_flyme_global.sh"
[ -f "$_ziyu_origin_flyme_helper" ] && . "$_ziyu_origin_flyme_helper"

_ziyufp_module="${MODULE_DIR:-${MODDIR:-/data/adb/modules/ziyu}}"
if ! type ziyu_payload_validate_current >/dev/null 2>&1; then
    [ -f "$_ziyufp_module/common/font_config_runtime.sh" ] && . "$_ziyufp_module/common/font_config_runtime.sh"
fi
if ! type font_config_prepare_payload_weights >/dev/null 2>&1; then
    [ -f "$_ziyufp_module/common/font_config_weights.sh" ] && . "$_ziyufp_module/common/font_config_weights.sh"
fi
[ -f "$_ziyufp_module/common/font_finalize_hotfix.sh" ] && . "$_ziyufp_module/common/font_finalize_hotfix.sh"

[ -f "$_ziyufp_module/common/device_font_payload_bridge.sh" ] && . "$_ziyufp_module/common/device_font_payload_bridge.sh"
[ -f "$_ziyufp_module/common/device_font_dynamic_guard.sh" ] && . "$_ziyufp_module/common/device_font_dynamic_guard.sh"
[ -f "$_ziyufp_module/common/device_font_transaction_guard.sh" ] && . "$_ziyufp_module/common/device_font_transaction_guard.sh"
[ -f "$_ziyufp_module/common/device_font_load_verify.sh" ] && . "$_ziyufp_module/common/device_font_load_verify.sh"
[ -f "$_ziyufp_module/common/device_font_runtime_report.sh" ] && . "$_ziyufp_module/common/device_font_runtime_report.sh"
[ -f "$_ziyufp_module/common/device_font_cache.sh" ] && . "$_ziyufp_module/common/device_font_cache.sh"
[ -f "$_ziyufp_module/common/mount_fast_sync.sh" ] && . "$_ziyufp_module/common/mount_fast_sync.sh"
[ -f "$_ziyufp_module/common/mount_self_fallback.sh" ] && . "$_ziyufp_module/common/mount_self_fallback.sh"
[ -f "$_ziyufp_module/common/mount_compat_policy.sh" ] && . "$_ziyufp_module/common/mount_compat_policy.sh"
[ -f "$_ziyufp_module/common/device_font_payload_policy.sh" ] && . "$_ziyufp_module/common/device_font_payload_policy.sh"
# Must load last: device_font_payload_policy.sh also overrides the validator.
[ -f "$_ziyufp_module/common/font_validate_fast_v4.sh" ] && . "$_ziyufp_module/common/font_validate_fast_v4.sh"
unset _ziyufp_module
