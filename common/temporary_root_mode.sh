#!/system/bin/sh
# A KSU installation alone does not prove temporary root. Late-load is explicit;
# users of KSU's emulated soft reboot can opt in separately.
ziyu_temporary_root_mode() {
    _ztr_module="$1"
    _ztr_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    _ztr_session="$_ztr_module/config/temporary-root-session.conf"
    if [ -n "$_ztr_boot" ] &&
       [ "$(sed -n 's/^boot_id=//p' "$_ztr_session" 2>/dev/null | head -n1)" = "$_ztr_boot" ] &&
       [ "$(sed -n 's/^mode=//p' "$_ztr_session" 2>/dev/null | head -n1)" = late-load ]; then
        return 0
    fi
    [ "$(sed -n 's/^enabled=//p' "$_ztr_module/config/temporary-root-mode.conf" 2>/dev/null | head -n1)" = true ]
}
