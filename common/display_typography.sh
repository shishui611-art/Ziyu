#!/system/bin/sh
# Explicit system text/display settings for the foreground Android user.
# Persist last confirmed values; only initialization, explicit sync and apply
# query Android settings. Ordinary status reads are local configuration reads.
set +e
MODDIR="${MODDIR:-${0%/*}/..}"
CACHE="$MODDIR/config/display-typography.conf"
CUSTOM_CACHE=
. "$MODDIR/common/display_config_storage.sh"
dt_custom_directory=$(ziyu_display_config_directory "$MODDIR")
[ -z "$dt_custom_directory" ] || CUSTOM_CACHE="$dt_custom_directory/display-typography.conf"
dt_context="${2:-}"
dt_context_sdk="${3:-}"

fail() { printf 'status=error\nmessage=%s\n' "$1"; exit 1; }
uint() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ "${#1}" -le 5 ]; }
sint() { _dt_digits=${1#-}; uint "$_dt_digits"; }
valid_state() {
    for _dt_number in "$dt_user" "$dt_sdk" "$dt_percent" "$dt_density" "$dt_base" "$dt_min" "$dt_max"; do
        uint "$_dt_number" || return 1
    done
    sint "$dt_weight" || return 1
    [ "$dt_sdk" -ge 28 ] && [ "$dt_percent" -ge 1 ] && [ "$dt_percent" -le 1000 ] && \
        [ "$dt_weight" -ge -1000 ] && [ "$dt_weight" -le 1000 ] && \
        [ "$dt_base" -gt 0 ] && [ "$dt_min" -gt 0 ] && \
        [ "$dt_density" -ge "$dt_min" ] && [ "$dt_density" -le "$dt_max" ]
}
counter() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ "${#1}" -le 10 ] && [ "$1" -le 2147483646 ]; }
cache_generation() {
    _dt_gen=0
    if [ -f "$1" ] && [ ! -L "$1" ]; then
        _dt_gen_bytes=$(wc -c < "$1" 2>/dev/null)
        uint "$_dt_gen_bytes" && [ "$_dt_gen_bytes" -le 16384 ] || { printf '0\n'; return; }
        _dt_gen_lines=0
        while IFS='=' read -r _dt_key _dt_value; do
            _dt_gen_lines=$((_dt_gen_lines + 1)); [ "$_dt_gen_lines" -le 20 ] || break
            case "$_dt_key" in generation) counter "$_dt_value" && _dt_gen="$_dt_value"; break ;; esac
        done < "$1"
    fi
    printf '%s\n' "$_dt_gen"
}
read_cache_path() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    _dt_bytes=$(wc -c < "$1" 2>/dev/null)
    uint "$_dt_bytes" && [ "$_dt_bytes" -le 16384 ] || return 1
    dt_schema=; dt_fingerprint=; dt_user=; dt_sdk=; dt_percent=; dt_weight=
    dt_density=; dt_base=; dt_min=; dt_max=; dt_generation=0; _dt_lines=0
    while IFS='=' read -r _dt_key _dt_value || [ -n "$_dt_key" ]; do
        _dt_lines=$((_dt_lines + 1)); [ "$_dt_lines" -le 20 ] || return 1
        case "$_dt_key" in
            schema) dt_schema="$_dt_value" ;; fingerprint) dt_fingerprint="$_dt_value" ;;
            user) dt_user="$_dt_value" ;; sdk) dt_sdk="$_dt_value" ;;
            scale) dt_percent="$_dt_value" ;; weight) dt_weight="$_dt_value" ;;
            density) dt_density="$_dt_value" ;; baseDensity) dt_base="$_dt_value" ;;
            densityMin) dt_min="$_dt_value" ;; densityMax) dt_max="$_dt_value" ;;
            generation) dt_generation="$_dt_value" ;;
        esac
    done < "$1"
    [ "$dt_schema" = ziyu-display-typography-v1 ] && counter "$dt_generation" && valid_state || return 1
    [ -z "$dt_context" ] || [ "$dt_fingerprint" = "$dt_context" ] || return 1
    [ -z "$dt_context_sdk" ] || [ "$dt_sdk" = "$dt_context_sdk" ] || return 1
}
read_cache() {
    [ ! -e "$MODDIR/config/display-typography-dirty" ] || return 1
    _dt_private_generation=-1
    if read_cache_path "$CACHE"; then _dt_private_generation="$dt_generation"; fi
    if [ -n "$CUSTOM_CACHE" ] && read_cache_path "$CUSTOM_CACHE" && \
       [ "$dt_generation" -ge "$_dt_private_generation" ]; then return 0; fi
    [ "$_dt_private_generation" -ge 0 ] && read_cache_path "$CACHE"
}
write_cache_path() {
    _dt_tmp="$1.tmp.$$"
    [ ! -L "$1" ] || return 1
    (umask 077; set -C; {
        printf 'schema=ziyu-display-typography-v1\nfingerprint=%s\ngeneration=%s\n' "$dt_fingerprint" "$dt_generation"
        emit_state
    } > "$_dt_tmp") || { rm -f "$_dt_tmp"; return 1; }
    chmod 0600 "$_dt_tmp" 2>/dev/null || true
    mv -f "$_dt_tmp" "$1" || { rm -f "$_dt_tmp"; return 1; }
}
save_cache() {
    valid_state || return 1
    [ ! -L "$MODDIR/config" ] && [ ! -L "$CACHE" ] || return 1
    if [ -n "$dt_context" ]; then dt_fingerprint="$dt_context"
    else dt_fingerprint=$(getprop ro.build.fingerprint 2>/dev/null); fi
    dt_fingerprint=$(printf '%s' "$dt_fingerprint" | tr -d '\r\n')
    mkdir -p "$MODDIR/config" || return 1
    dt_generation=$(cache_generation "$CACHE")
    _dt_custom_generation=$(cache_generation "$CUSTOM_CACHE")
    [ "$dt_generation" -ge "$_dt_custom_generation" ] || dt_generation="$_dt_custom_generation"
    [ "$dt_generation" -lt 2147483646 ] || return 1
    dt_generation=$((dt_generation + 1))
    dt_internal_saved=false; dt_custom_saved=false
    write_cache_path "$CACHE" && dt_internal_saved=true
    if [ -n "$CUSTOM_CACHE" ] && [ -d "$dt_custom_directory" ] && [ ! -L "$dt_custom_directory" ]; then
        _dt_owner=
        IFS= read -r _dt_owner < "$dt_custom_directory/.ziyu-display-config" 2>/dev/null || true
        if [ "$_dt_owner" = ziyu-display-config-v1 ]; then write_cache_path "$CUSTOM_CACHE" && dt_custom_saved=true; fi
    fi
    if [ "$dt_internal_saved" = true ] || [ "$dt_custom_saved" = true ]; then
        rm -f "$MODDIR/config/display-typography-dirty"
        return 0
    fi
    return 1
}
read_state() {
    dt_user=$(am get-current-user 2>/dev/null)
    uint "$dt_user" || return 1
    dt_sdk=$(getprop ro.build.version.sdk 2>/dev/null)
    uint "$dt_sdk" || return 1
    dt_scale_raw=$(settings --user "$dt_user" get system font_scale 2>/dev/null) || return 1
    dt_scale="$dt_scale_raw"
    if [ "$dt_scale" = null ]; then
        dt_scale=$(settings --user "$dt_user" get system default_device_font_scale 2>/dev/null)
        [ -n "$dt_scale" ] && [ "$dt_scale" != null ] || dt_scale=1
    fi
    dt_percent=$(printf '%s\n' "$dt_scale" | awk '/^[0-9]+([.][0-9]+)?$/ && $0 > 0 && $0 <= 10 {printf "%.0f", $0 * 100}')
    uint "$dt_percent" || return 1
    dt_weight_raw=$(settings --user "$dt_user" get secure font_weight_adjustment 2>/dev/null) || return 1
    dt_weight="$dt_weight_raw"
    [ "$dt_weight" != null ] || dt_weight=0
    sint "$dt_weight" || return 1
    [ "$dt_weight" -ge -1000 ] && [ "$dt_weight" -le 1000 ] || return 1
    dt_density_text=$(wm density 2>/dev/null) || return 1
    dt_base=$(printf '%s\n' "$dt_density_text" | sed -n 's/^Physical density: *\([0-9][0-9]*\).*$/\1/p')
    dt_override=$(printf '%s\n' "$dt_density_text" | sed -n 's/^Override density: *\([0-9][0-9]*\).*$/\1/p')
    uint "$dt_base" && [ "$dt_base" -gt 0 ] || return 1
    [ -n "$dt_override" ] || dt_override=0
    uint "$dt_override" || return 1
    dt_density="$dt_override"
    [ "$dt_density" -gt 0 ] || dt_density="$dt_base"
    dt_min=$((dt_base * 7 / 10)); dt_max=$((dt_base * 14 / 10))
    [ "$dt_min" -ge 120 ] || dt_min=120
    [ "$dt_max" -le 1200 ] || dt_max=1200
    [ "$dt_min" -le "$dt_density" ] || dt_min="$dt_density"
    [ "$dt_max" -ge "$dt_density" ] || dt_max="$dt_density"
    [ "$dt_min" -le "$dt_base" ] || dt_min="$dt_base"
    [ "$dt_max" -ge "$dt_base" ] || dt_max="$dt_base"
    valid_state
}
emit_state() {
    printf 'status=ok\nuser=%s\nsdk=%s\nscale=%s\nweight=%s\ndensity=%s\nbaseDensity=%s\ndensityMin=%s\ndensityMax=%s\n' \
        "$dt_user" "$dt_sdk" "$dt_percent" "$dt_weight" "$dt_density" "$dt_base" "$dt_min" "$dt_max"
}
restore_setting() {
    if [ "$3" = null ]; then settings --user "$old_user" delete "$1" "$2" >/dev/null 2>&1
    else settings --user "$old_user" put "$1" "$2" "$3" >/dev/null 2>&1; fi
}
rollback() {
    dt_rollback_ok=true
    if [ "$density_written" = true ]; then
        if [ "$old_override" = 0 ]; then wm density reset >/dev/null 2>&1
        else wm density "$old_override" >/dev/null 2>&1; fi
        [ "$?" -eq 0 ] || dt_rollback_ok=false
    fi
    if [ "$scale_written" = true ]; then restore_setting system font_scale "$old_scale_raw" || dt_rollback_ok=false; fi
    read_state && [ "$dt_user" = "$old_user" ] && [ "$dt_percent" = "$old_percent" ] && \
        [ "$dt_override" = "$old_override" ] || dt_rollback_ok=false
}
cleanup() {
    if [ "$committed" != true ]; then
        rollback
        if [ "$dt_rollback_ok" = true ]; then save_cache >/dev/null 2>&1 || rm -f "$CACHE"
        else rm -f "$CACHE"; fi
    fi
    luoshu_font_lock_release "$dt_lock" "$$" >/dev/null 2>&1 || true
}

case "${1:-status}" in
    storage)
        shift
        . "$MODDIR/common/font_switch_lock.sh" || fail '显示设置锁不可用'
        dt_lock="$MODDIR/.display-typography.lock"
        luoshu_font_lock_acquire "$dt_lock" "$$" || fail '另一项显示设置正在处理，请稍后重试'
        trap 'luoshu_font_lock_release "$dt_lock" "$$" >/dev/null 2>&1' EXIT
        trap 'exit 1' HUP INT TERM
        ziyu_display_config_command "$MODDIR" "$@"
        exit $?
        ;;
    status|refresh|initialize)
        [ "$#" -eq 1 ] || [ "$#" -eq 3 ] || fail '显示参数无效'
        if [ "$1" = status ] && read_cache; then emit_state; printf 'cache=local\n'; exit 0; fi
        . "$MODDIR/common/font_switch_lock.sh" || fail '显示设置锁不可用'
        dt_lock="$MODDIR/.display-typography.lock"
        luoshu_font_lock_acquire "$dt_lock" "$$" || fail '另一项显示设置正在处理，请稍后重试'
        trap 'luoshu_font_lock_release "$dt_lock" "$$" >/dev/null 2>&1; rm -f "$CACHE.tmp.$$"' EXIT
        trap 'exit 1' HUP INT TERM
        read_state || fail '无法读取系统字体缩放或实际 DPI'
        if save_cache; then dt_saved=true; else dt_saved=false; fi
        emit_state; printf 'cache=system\ncacheSaved=%s\n' "$dt_saved"
        if [ "$dt_saved" = true ]; then printf 'message=已从系统读取并保存本地配置\n'
        else printf 'message=系统参数已读取，本地配置未保存，可点击同步重试\n'; fi
        [ "$1" != initialize ] || [ "$dt_saved" = true ] || exit 1
        exit 0
        ;;
    apply)
        [ "$#" -eq 8 ] || [ "$#" -eq 10 ] || fail '显示参数无效'
        dt_context="${9:-}"; dt_context_sdk="${10:-}"
        ;;
    *) fail '未知显示设置命令' ;;
esac
. "$MODDIR/common/font_switch_lock.sh" || fail '显示设置锁不可用'
# Keep the existing wire format for cached/older clients, but no longer own
# system weight. Size/DPI writes and rollback must preserve concurrent weight changes.
# apply USER OLD_SCALE OLD_WEIGHT OLD_DPI SCALE keep DPI
for dt_arg in "$2" "$3" "$5" "$6" "$8"; do uint "$dt_arg" || fail '显示数值无效'; done
sint "$4" || fail '原字重数值无效'
[ "$7" = keep ] || fail '主页粗细调整已移除，请在手机系统设置中调整字体粗细'
[ "$6" -ge 50 ] && [ "$6" -le 200 ] || fail '字体大小范围为 50% 至 200%'
dt_lock="$MODDIR/.display-typography.lock"
luoshu_font_lock_acquire "$dt_lock" "$$" || fail '另一项显示设置正在处理，请稍后重试'
read_state || { luoshu_font_lock_release "$dt_lock" "$$"; fail '无法读取修改前的显示设置'; }
# Changes made outside this App become the next local read, without weakening
# the compare-before-write guard for a stale draft or a different Android user.
save_cache >/dev/null 2>&1 || rm -f "$CACHE"
[ "$dt_user" = "$2" ] && [ "$dt_percent" = "$3" ] && [ "$dt_density" = "$5" ] || {
    luoshu_font_lock_release "$dt_lock" "$$"; fail '系统设置已在其他位置变化，请重新读取后应用';
}
[ "$8" -ge "$dt_min" ] && [ "$8" -le "$dt_max" ] || {
    luoshu_font_lock_release "$dt_lock" "$$"; fail 'DPI 超出本机调节范围，请重新读取';
}
old_user="$dt_user"; old_percent="$dt_percent"
old_density="$dt_density"; old_override="$dt_override"
old_scale_raw="$dt_scale_raw"
scale_written=false; density_written=false; committed=false
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
touch "$MODDIR/config/display-typography-dirty" 2>/dev/null || true
dt_ok=true
if [ "$6" != "$old_percent" ]; then
    scale_written=true
    dt_scale_value=$(awk -v n="$6" 'BEGIN {printf "%.2f", n/100}')
    settings --user "$old_user" put system font_scale "$dt_scale_value" >/dev/null 2>&1 || dt_ok=false
fi
if [ "$dt_ok" = true ] && [ "$8" != "$old_density" ]; then
    density_written=true
    if [ "$8" = "$dt_base" ]; then wm density reset >/dev/null 2>&1
    else wm density "$8" >/dev/null 2>&1; fi
    [ "$?" -eq 0 ] || dt_ok=false
fi
if [ "$dt_ok" = true ]; then
    read_state && [ "$dt_user" = "$old_user" ] && [ "$dt_percent" = "$6" ] && [ "$dt_density" = "$8" ] || dt_ok=false
fi
if [ "$dt_ok" = true ]; then
    committed=true
    if save_cache; then
        emit_state; printf 'cache=system\ncacheSaved=true\nmessage=已应用字号与 DPI；字体粗细请在手机系统设置中调整\n'
    else
        emit_state; printf 'cache=system\ncacheSaved=false\nmessage=系统设置已应用，本地配置未保存，可点击同步重试\n'
    fi
    exit 0
fi
rollback
committed=true
if [ "$dt_rollback_ok" = true ]; then save_cache >/dev/null 2>&1 || rm -f "$CACHE"
else rm -f "$CACHE"; fi
if [ "$dt_rollback_ok" = true ]; then fail '显示设置未通过核对，已恢复修改前的值'
else fail '显示设置失败，恢复尚未确认；请在系统显示设置中检查'; fi
