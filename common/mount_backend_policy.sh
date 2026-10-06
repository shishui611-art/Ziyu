#!/system/bin/sh
# Prefer the external provider when available; use Ziyu's own mount as the
# recovery route whenever a provider is absent, unavailable, or excluded.
ziyu_mount_select() {
    [ "${1:-false}" != true ] || { printf 'none\n'; return 0; }
    # The provider tree is only needed when an actual custom font is selected.
    # Do not publish an empty/default payload into the external module scan path.
    [ "${3:-false}" = true ] || { printf 'none\n'; return 0; }
    case "${2:-unknown}" in
        available) printf 'external\n' ;;
        *) printf 'self\n' ;;
    esac
}
