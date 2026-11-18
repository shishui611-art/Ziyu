#!/system/bin/sh
# Lightweight selection guard. Full file and route proofs run in the verifier.
ziyu_payload_has_emoji() {
    [ -d "$1" ] && [ ! -L "$1" ] && [ -f "$1/.ziyu-emoji.json" ] && \
        [ ! -L "$1/.ziyu-emoji.json" ] && \
        grep -Eq '"id"[[:space:]]*:[[:space:]]*"[0-9a-f]{64}"' "$1/.ziyu-emoji.json"
}

# Text-only compatibility cleanup keeps the independently owned Emoji files.
# All paths remain inside the module's supplied private font directory.
ziyu_clear_text_fonts() {
    [ ! -L "$1" ] || return 1
    [ -d "$1" ] || return 0
    for _zcte_file in "$1"/* "$1"/.[!.]* "$1"/..?*; do
        [ -e "$_zcte_file" ] || [ -L "$_zcte_file" ] || continue
        _zcte_name=${_zcte_file##*/}
        case "$_zcte_name" in
            *[eE][mM][oO][jJ][iI]*.[tT][tT][fF]|*[eE][mM][oO][jJ][iI]*.[oO][tT][fF])
                [ ! -L "$_zcte_file" ] && [ -f "$_zcte_file" ] && continue ;;
        esac
        rm -rf "$_zcte_file" || return 1
    done
}
