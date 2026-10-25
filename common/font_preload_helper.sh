#!/system/bin/sh
# The native-only companion is separate because Magisk ignores modules with a
# root zygisk/ directory when its built-in Zygisk is off. Never change policies,
# install Zygisk, enable a disabled helper, or overwrite an unrelated module.
ziyu_preload_helper_owned() {
    [ -d "$1" ] && [ ! -L "$1" ] &&
        [ -f "$1/ziyu-owner.conf" ] && [ ! -L "$1/ziyu-owner.conf" ] &&
        [ -f "$1/module.prop" ] && [ ! -L "$1/module.prop" ] &&
        grep -qx 'schema=ziyu-font-preload-owner-v1' "$1/ziyu-owner.conf" &&
        grep -qx 'owner=ziyu' "$1/ziyu-owner.conf" &&
        grep -qx 'id=ziyu-font-preload' "$1/module.prop"
}

ziyu_preload_helper_install() (
    _zph_main=${1%/}
    case "$_zph_main" in /data/adb/modules/ziyu|/data/adb/modules_update/ziyu) ;; *) return 1 ;; esac
    [ -d "$_zph_main" ] && [ ! -L "$_zph_main" ] || return 1
    _zph_parent=${_zph_main%/*}
    [ "$(readlink -f "$_zph_parent" 2>/dev/null)" = "$_zph_parent" ] || return 1
    _zph_source="$_zph_main/common/font_preload_module"
    _zph_target="$_zph_parent/ziyu-font-preload"
    _zph_old=/data/adb/modules/ziyu-font-preload
    ziyu_preload_helper_owned "$_zph_source" || return 1
    if [ -e "$_zph_target" ] || [ -L "$_zph_target" ]; then
        ziyu_preload_helper_owned "$_zph_target" || return 1
    fi
    if [ "$_zph_target" != "$_zph_old" ] && { [ -e "$_zph_old" ] || [ -L "$_zph_old" ]; }; then
        ziyu_preload_helper_owned "$_zph_old" || return 1
    fi
    mkdir -p "$_zph_target" || return 1
    for _zph_marker in disable remove; do
        [ ! -L "$_zph_target/$_zph_marker" ] || return 1
    done
    # Preserve explicit disabled/removed choices through an ordinary main upgrade.
    if ziyu_preload_helper_owned "$_zph_old"; then
        for _zph_marker in disable remove; do
            [ ! -e "$_zph_old/$_zph_marker" ] || : > "$_zph_target/$_zph_marker" || return 1
        done
    fi
    # Preserve a disabled choice made on the previous, owned helper identity.
    for _zph_legacy in /data/adb/modules/ZiyuFontPreload /data/adb/modules_update/ZiyuFontPreload; do
        [ -d "$_zph_legacy" ] && [ ! -L "$_zph_legacy" ] || continue
        grep -qx 'owner=LuoShu' "$_zph_legacy/ziyu-owner.conf" && grep -qx 'id=ZiyuFontPreload' "$_zph_legacy/module.prop" || continue
        for _zph_marker in disable remove; do
            [ ! -e "$_zph_legacy/$_zph_marker" ] || : > "$_zph_target/$_zph_marker" || return 1
        done
    done
    # Fixed paths only. No recursive replacement or cleanup of module directories.
    for _zph_file in ziyu-owner.conf module.prop skip_mount build.json zygisk/arm64-v8a.so zygisk/armeabi-v7a.so; do
        [ -f "$_zph_source/$_zph_file" ] && [ ! -L "$_zph_source/$_zph_file" ] || return 1
        _zph_dir=${_zph_file%/*}
        [ "$_zph_dir" = "$_zph_file" ] || {
            [ ! -L "$_zph_target/$_zph_dir" ] && mkdir -p "$_zph_target/$_zph_dir"
        } || return 1
        [ ! -L "$_zph_target/$_zph_file" ] || return 1
        if [ -f "$_zph_target/$_zph_file" ] && cmp -s "$_zph_source/$_zph_file" "$_zph_target/$_zph_file"; then
            chmod 0644 "$_zph_target/$_zph_file" || return 1
            continue
        fi
        _zph_temp="$_zph_target/$_zph_file.new.$$"
        [ ! -e "$_zph_temp" ] && [ ! -L "$_zph_temp" ] || return 1
        cp -f "$_zph_source/$_zph_file" "$_zph_temp" &&
            chmod 0644 "$_zph_temp" && cmp -s "$_zph_source/$_zph_file" "$_zph_temp" &&
            mv -f "$_zph_temp" "$_zph_target/$_zph_file" || {
                rm -f "$_zph_temp"
                : > "$_zph_target/disable"
                return 1
            }
    done
    chmod 0755 "$_zph_target" "$_zph_target/zygisk" || return 1
    # Native helper has no scripts, partition payload or extra App. The manager
    # performs library loading on reboot; this installer never injects a process.
    chcon u:object_r:system_file:s0 "$_zph_target" "$_zph_target/zygisk" \
        "$_zph_target/module.prop" "$_zph_target/ziyu-owner.conf" \
        "$_zph_target/zygisk/arm64-v8a.so" "$_zph_target/zygisk/armeabi-v7a.so" 2>/dev/null || true
    ziyu_preload_helper_owned "$_zph_target"
)

ziyu_preload_helper_remove() (
    for _zph_target in /data/adb/modules/ziyu-font-preload /data/adb/modules_update/ziyu-font-preload; do
        [ -e "$_zph_target" ] || [ -L "$_zph_target" ] || continue
        ziyu_preload_helper_owned "$_zph_target" || continue
        [ ! -L "$_zph_target/disable" ] && [ ! -L "$_zph_target/remove" ] || return 1
        : > "$_zph_target/disable" && : > "$_zph_target/remove" || return 1
    done
)
