#!/system/bin/sh
# Read-only readiness for KernelSU's post-stop callback, not process-name guessing.
ziyu_soft_reboot_window_ready() {
    ZIYU_SOFT_WINDOW_REASON=none
    _zsw_init_ns=$(readlink /proc/1/ns/mnt 2>/dev/null)
    if [ -z "$_zsw_init_ns" ] || [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$_zsw_init_ns" ]; then
        ZIYU_SOFT_WINDOW_REASON=not-init-mount-namespace; return 1
    fi
    if [ "$(getprop sys.boot_completed 2>/dev/null)" != 0 ]; then
        ZIYU_SOFT_WINDOW_REASON=android-not-in-reload; return 1
    fi
    # ColorOS Tango's /system_ext/bin/tango_translator calls itself "zygote"
    # and may remain after Android services stop. pidof zygote
    # therefore cannot prove that the real Android zygote is still running.
    # Keep retired font files for existing mmaps; do not kill vendor services.
    _zsw_known=0
    for _zsw_service in zygote zygote64 zygote32 zygote_secondary zygote_next; do
        _zsw_state=$(getprop "init.svc.$_zsw_service" 2>/dev/null)
        case "$_zsw_state" in
            '') continue ;;
            stopped) _zsw_known=1 ;;
            *) ZIYU_SOFT_WINDOW_REASON="service-not-stopped:$_zsw_service:$_zsw_state"; return 1 ;;
        esac
    done
    if [ "$_zsw_known" != 1 ]; then
        ZIYU_SOFT_WINDOW_REASON=zygote-service-state-unavailable; return 1
    fi
    if [ -n "$(pidof system_server zygote64 2>/dev/null)" ]; then
        ZIYU_SOFT_WINDOW_REASON=android-services-running; return 1
    fi
    return 0
}

ziyu_soft_reboot_window_wait() {
    _zsw_attempt=0
    _zsw_limit="${1:-8}"
    case "$_zsw_limit" in ''|*[!0-9]*) return 1 ;; esac
    [ "$_zsw_limit" -le 40 ] || return 1
    while ! ziyu_soft_reboot_window_ready; do
        case "$ZIYU_SOFT_WINDOW_REASON" in
            service-not-stopped:*|android-services-running) ;;
            *) return 1 ;;
        esac
        [ "$_zsw_attempt" -lt "$_zsw_limit" ] || return 1
        sleep 0.25
        _zsw_attempt=$((_zsw_attempt + 1))
    done
}
