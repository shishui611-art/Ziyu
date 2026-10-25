#!/system/bin/sh
# Select a provider automatically or use a user-selected self-mount strategy.

# This chooses an implementation, never invents a Root-manager identity.
# When a manager invokes the standard early hook, an unknown identity can
# attempt bind without depending on its OverlayFS or metamodule integration.
ziyu_mount_auto_self_backend() {
    case "${1:-unknown}" in
        Magisk|KernelSU|SukiSU\ Ultra|APatch) printf 'self_mount\n' ;;
        *) printf 'magic\n' ;;
    esac
}

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
