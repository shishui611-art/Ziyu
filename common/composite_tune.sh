#!/system/bin/sh
# Fine-tuning adapted from LuoShu refactor-v3.0.0 composite_cache_proof.sh.
# Source commit: 3e7e19b9f2b0899a326d0ce337602c006b90f233 (GPL-3.0-only).

composite_tune_clamp() {
    _ctc_value="$1"; _ctc_digits="${1#-}"
    case "$_ctc_digits" in ''|*[!0-9]*) printf '0\n'; return 0 ;; esac
    _ctc_digits=${_ctc_digits#"${_ctc_digits%%[!0]*}"}
    [ -n "$_ctc_digits" ] || _ctc_digits=0
    [ "${#_ctc_digits}" -le 3 ] || _ctc_digits=999
    [ "$_ctc_digits" -le "$2" ] || _ctc_digits="$2"
    case "$_ctc_value" in -*) [ "$_ctc_digits" = 0 ] || _ctc_digits="-$_ctc_digits" ;; esac
    printf '%s\n' "$_ctc_digits"
}

composite_tune_load() {
    COMPOSITE_TUNE_SIZE=$(composite_tune_clamp "${ZIYU_MIX_LATIN_SIZE:-0}" 15)
    COMPOSITE_TUNE_OFFSET=$(composite_tune_clamp "${ZIYU_MIX_LATIN_OFFSET:-0}" 10)
}

composite_tune_key_suffix() {
    [ "${COMPOSITE_TUNE_SIZE:-0}" = 0 ] && [ "${COMPOSITE_TUNE_OFFSET:-0}" = 0 ] && return 0
    printf -- '-latin-tune-size=%s-offset=%s' "$COMPOSITE_TUNE_SIZE" "$COMPOSITE_TUNE_OFFSET"
}
