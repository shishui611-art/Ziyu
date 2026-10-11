#!/system/bin/sh
# Select a provider automatically or use a user-selected self-mount strategy.
ziyu_mount_select() {
    [ "${1:-false}" != true ] || { printf 'none\n'; return 0; }
    # A default-font selection has no active mount backend. The runtime separately
    # retracts a prior provider publication when an ownership receipt is present.
    [ "${3:-false}" = true ] || { printf 'none\n'; return 0; }
    case "${4:-auto}" in
        magic|overlayfs|self_mount) printf 'self\n'; return 0 ;;
    esac
    case "${2:-unknown}" in
        available) printf 'external\n' ;;
        *) printf 'unresolved\n' ;;
    esac
}
