#!/system/bin/sh
# Integrity contract for the existing XML-preserving physical font backend.
# Build only from the frozen payload before mount/activation, never live ROM files.
luoshu_physical_manifest_build() (
    _lpm_module="$1"
    _lpm_root="$2"
    _lpm_config="$_lpm_module/config"
    _lpm_tmp="$_lpm_config/font-payload-manifest.conf.tmp.$$"
    _lpm_list="$_lpm_config/.physical-manifest-files.$$"
    [ -d "$_lpm_root" ] || return 1
    mkdir -p "$_lpm_config" || return 1
    trap 'rm -f "$_lpm_tmp" "$_lpm_list"' EXIT HUP INT TERM
    : > "$_lpm_tmp" || return 1
    : > "$_lpm_list" || return 1
    _lpm_parts='system system_ext product vendor odm oem my_product my_engineering my_company my_preload my_region my_stock oplus_product oplus_engineering oplus_version oplus_region mi_ext cust hw_product oplus'
    if [ -f "$_lpm_config/device_font_partitions.conf" ]; then
        while IFS= read -r _lpm_extra; do
            case "$_lpm_extra" in ''|*[!A-Za-z0-9_]*|[0-9]*|_*|data|proc|sys|dev|mnt|storage|sdcard|apex|metadata|cache|tmp|config|acct|linkerconfig|debug_ramdisk|vendor_dlkm|odm_dlkm|system_dlkm) continue ;; esac
            case " $_lpm_parts " in *" $_lpm_extra "*) ;; *) _lpm_parts="$_lpm_parts $_lpm_extra" ;; esac
        done < "$_lpm_config/device_font_partitions.conf"
    fi
    for _lpm_part in $_lpm_parts; do
        [ ! -d "$_lpm_root/$_lpm_part/fonts" ] || find "$_lpm_root/$_lpm_part/fonts" \( -type f -o -type l \) >> "$_lpm_list" || return 1
        [ ! -d "$_lpm_root/$_lpm_part/etc" ] || find "$_lpm_root/$_lpm_part/etc" -maxdepth 1 -type f -name '*font*.xml' >> "$_lpm_list" || return 1
    done
    _lpm_fonts=0
    while IFS= read -r _lpm_file; do
        case "$_lpm_file" in *.ttf|*.otf|*.ttc|*.font|*.TTF|*.OTF|*.TTC) _lpm_fonts=$((_lpm_fonts + 1)) ;; *.xml) ;; *) continue ;; esac
        [ -s "$_lpm_file" ] && [ -r "$_lpm_file" ] || return 1
        # Font aliases must resolve inside this transaction's immutable payload.
        _lpm_real=$(readlink -f "$_lpm_file") || return 1
        _lpm_root_real=$(readlink -f "$_lpm_root") || return 1
        case "$_lpm_real" in "$_lpm_root_real"/*) ;; *) return 1 ;; esac
        _lpm_rel=${_lpm_file#"$_lpm_root"/}
        case "$_lpm_rel" in *'|'*|*'\'*|/*|../*|*/../*) return 1 ;; esac
        _lpm_sum=$(sha256sum "$_lpm_file" 2>/dev/null | awk '{print $1}')
        case "$_lpm_sum" in ''|*[!0-9a-fA-F]*) return 1 ;; esac
        [ "${#_lpm_sum}" -eq 64 ] || return 1
        printf '%s|%s\n' "$_lpm_rel" "$_lpm_sum" >> "$_lpm_tmp" || return 1
    done < "$_lpm_list"
    [ "$_lpm_fonts" -gt 0 ] && [ -s "$_lpm_tmp" ] || return 1
    chmod 0644 "$_lpm_tmp" || return 1
    mv -f "$_lpm_tmp" "$_lpm_config/font-payload-manifest.conf" || return 1
)

luoshu_physical_manifest_ensure() {
    [ ! -e "$1/config/font-payload-manifest.conf" ] || {
        [ -s "$1/config/font-payload-manifest.conf" ]; return;
    }
    [ "$(sed -n 's/^core=//p' "$1/config/font_runtime_legacy_v14_4.conf" 2>/dev/null | head -n1)" = physical-safe-v1 ] || return 1
    [ "$(sed -n 's/^schema=//p' "$1/config/font-payload-schema.conf" 2>/dev/null | head -n1)" = legacy-physical-safe-v1 ] || return 1
    luoshu_physical_manifest_build "$1" "$2"
}
