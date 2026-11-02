#!/system/bin/sh
# Retire only the historical, separately owned experimental component.
ziyu_preload_helper_owned() {
    [ -d "$1" ] && [ ! -L "$1" ] &&
        [ -f "$1/ziyu-owner.conf" ] && [ ! -L "$1/ziyu-owner.conf" ] &&
        [ -f "$1/module.prop" ] && [ ! -L "$1/module.prop" ] &&
        grep -qx 'schema=ziyu-font-preload-owner-v1' "$1/ziyu-owner.conf" &&
        grep -qx 'owner=ziyu' "$1/ziyu-owner.conf" &&
        grep -qx 'id=ziyu-font-preload' "$1/module.prop"
}

ziyu_preload_helper_remove() (
    for _zph_target in /data/adb/modules/ziyu-font-preload /data/adb/modules_update/ziyu-font-preload; do
        [ -e "$_zph_target" ] || [ -L "$_zph_target" ] || continue
        ziyu_preload_helper_owned "$_zph_target" || continue
        [ ! -L "$_zph_target/disable" ] && [ ! -L "$_zph_target/remove" ] || return 1
        : > "$_zph_target/disable" && : > "$_zph_target/remove" || return 1
    done
)
