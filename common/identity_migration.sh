#!/system/bin/sh
ziyu_identity_select_old() {
    ZIYU_OLD_MOD=/data/adb/modules/ziyu
    if [ ! -d "$ZIYU_OLD_MOD" ] && [ -d /data/adb/modules/LuoShu ] &&
       [ ! -L /data/adb/modules/LuoShu ] &&
       [ "$(sed -n 's/^id=//p' /data/adb/modules/LuoShu/module.prop | tr -d '\r')" = LuoShu ]; then
        ZIYU_OLD_MOD=/data/adb/modules/LuoShu
    fi
    export ZIYU_OLD_MOD
}

ziyu_identity_finish() (
    _zim_new="$1"; _zim_old="$2"
    case "$_zim_new" in /data/adb/modules/ziyu|/data/adb/modules_update/ziyu) ;; *) return 1 ;; esac
    [ ! -L "$_zim_old" ] && [ ! -L "$_zim_new" ] || return 1
    # A same-ID update can still change the private directory layout. Require a
    # full boot exactly across that boundary; ordinary future updates skip it.
    if [ -d "$_zim_old/.luoshu-runtime" ] || [ -d "$_zim_old/.luoshu-payload" ]; then
        [ -d "$_zim_new/config" ] && [ ! -L "$_zim_new/config" ] || return 1
        _zim_marker="$_zim_new/config/identity-migration-full-reboot.conf"
        printf 'bootId=%s\nreason=private-identity-layout-upgrade\n' "$(cat /proc/sys/kernel/random/boot_id)" > "$_zim_marker.identity.$$" &&
            chmod 0600 "$_zim_marker.identity.$$" && mv -f "$_zim_marker.identity.$$" "$_zim_marker" || return 1
    fi
    [ "$_zim_old" = /data/adb/modules/LuoShu ] || return 0
    # Copied config is small text metadata. Rewrite atomically so hard-linked
    # cached generations and the still-running old installation stay untouched.
    for _zim_file in "$_zim_new/config"/*.conf "$_zim_new/config"/*.json; do
        [ -f "$_zim_file" ] && [ ! -L "$_zim_file" ] || continue
        grep -q '/data/adb/modules/LuoShu' "$_zim_file" || continue
        sed 's,/data/adb/modules/LuoShu,/data/adb/modules/ziyu,g' "$_zim_file" > "$_zim_file.identity.$$" &&
            chmod 0600 "$_zim_file.identity.$$" && mv -f "$_zim_file.identity.$$" "$_zim_file" || return 1
    done
    # The retired uninstall script removes shared hooks/runtime resources. Keep
    # it as evidence but do not let the manager execute it after the new setup.
    if [ -f "$_zim_old/uninstall.sh" ]; then
        [ ! -e "$_zim_old/.ziyu-retired-uninstall.sh" ] || return 1
        mv "$_zim_old/uninstall.sh" "$_zim_old/.ziyu-retired-uninstall.sh" || return 1
    fi
    printf 'bootId=%s\nreason=module-identity-upgrade\n' "$(cat /proc/sys/kernel/random/boot_id)" > "$_zim_new/config/identity-migration-full-reboot.conf" || return 1
    : > "$_zim_old/disable" && : > "$_zim_old/remove" || return 1
    # Retire only our script-free legacy companion; preserve unrelated modules.
    for _zim_helper in /data/adb/modules/ZiyuFontPreload /data/adb/modules_update/ZiyuFontPreload; do
        [ -d "$_zim_helper" ] && [ ! -L "$_zim_helper" ] || continue
        grep -qx 'owner=LuoShu' "$_zim_helper/ziyu-owner.conf" &&
            grep -qx 'id=ZiyuFontPreload' "$_zim_helper/module.prop" || continue
        [ ! -L "$_zim_helper/disable" ] && [ ! -L "$_zim_helper/remove" ] || return 1
        : > "$_zim_helper/disable" && : > "$_zim_helper/remove" || return 1
    done
)
