#!/system/bin/sh
# One-time migration/uninstall restore for legacy global Settings ownership.
# Never alter a setting changed by another app/user after LuoShu's last write.
set -u
_fwr_old="${1:?old module required}"
_fwr_new="${2:?new module required}"
_fwr_mode="${3:-flash}"
_fwr_cfg="$_fwr_new/config"
mkdir -p "$_fwr_cfg" || exit 1
_fwr_report="$_fwr_cfg/font-weight-retired-v2.conf"
_fwr_record() {
    printf 'state=%s\n' "$1" > "$_fwr_report.tmp.$$" && mv -f "$_fwr_report.tmp.$$" "$_fwr_report"
}
_fwr_clear() {
    rm -f "$_fwr_cfg/font_weight.conf" "$_fwr_cfg/font_weight_original.conf" \
          "$_fwr_cfg/font_weight_reboot_required.conf"
    _fwr_record "$1"
}
_fwr_defer() {
    if [ "$_fwr_mode" = flash ]; then
        for _fwr_name in font_weight.conf font_weight_original.conf; do
            [ "$_fwr_old/config/$_fwr_name" = "$_fwr_cfg/$_fwr_name" ] || \
                cp -f "$_fwr_old/config/$_fwr_name" "$_fwr_cfg/$_fwr_name" 2>/dev/null || true
        done
        _fwr_record pending
    else
        # No repeated boot-time settings jobs after this bounded retry.
        _fwr_record failed
    fi
    exit 1
}
# A successful/skipped migration must never repeat on a later boot.
[ "$_fwr_mode" != boot ] || grep -qx 'state=pending' "$_fwr_report" 2>/dev/null || exit 0
if [ ! -s "$_fwr_old/config/font_weight.conf" ] || [ ! -s "$_fwr_old/config/font_weight_original.conf" ]; then
    _fwr_clear not-owned
    exit 0
fi
_fwr_saved=$(sed -n 's/^adjustment=//p' "$_fwr_old/config/font_weight.conf" | head -n1)
_fwr_original=$(sed -n 's/^adjustment=//p' "$_fwr_old/config/font_weight_original.conf" | head -n1)
_fwr_original_present=$(sed -n 's/^present=//p' "$_fwr_old/config/font_weight_original.conf" | head -n1)
# Older releases normalized an unset value to zero and had no presence flag.
[ -n "$_fwr_original_present" ] || _fwr_original_present=true
case "$_fwr_original_present" in true|false) ;; *) _fwr_clear invalid-backup; exit 0 ;; esac
for _fwr_number in "$_fwr_saved" "$_fwr_original"; do
    printf '%s\n' "$_fwr_number" | grep -Eq '^-?[0-9]{1,4}$' || { _fwr_clear invalid-backup; exit 0; }
    [ "$_fwr_number" -ge -1000 ] && [ "$_fwr_number" -le 1000 ] || { _fwr_clear invalid-backup; exit 0; }
done
# Supervise Binder commands too: a busy Settings provider must not hang the
# installer or leave Java/cmd descendants running after the job has ended.
if [ "${LUOSHU_SCOPE_WORKER_PID:-}" != "$$" ]; then
    _fwr_record pending
    MODDIR="$_fwr_new"; export MODDIR
    exec sh "$_fwr_new/common/task_scope.sh" --timeout 15 -- sh "$0" "$@"
fi
command -v settings >/dev/null 2>&1 || _fwr_defer
_fwr_current=$(settings --user current get secure font_weight_adjustment 2>/dev/null)
_fwr_get_rc=$?
if [ "$_fwr_get_rc" -ne 0 ]; then
    _fwr_current=$(settings get secure font_weight_adjustment 2>/dev/null) || _fwr_defer
fi
if [ "$_fwr_current" != "$_fwr_saved" ]; then
    # Preserve null/unset and the system/user's newer preference alike.
    _fwr_clear externally-changed
    exit 0
fi
if [ "$_fwr_original_present" = true ]; then
    settings --user current put secure font_weight_adjustment "$_fwr_original" >/dev/null 2>&1 || \
        settings put secure font_weight_adjustment "$_fwr_original" >/dev/null 2>&1 || _fwr_defer
    _fwr_check=$(settings --user current get secure font_weight_adjustment 2>/dev/null)
    _fwr_get_rc=$?
    if [ "$_fwr_get_rc" -ne 0 ]; then
        _fwr_check=$(settings get secure font_weight_adjustment 2>/dev/null) || _fwr_defer
    fi
    [ "$_fwr_check" = "$_fwr_original" ] || _fwr_defer
else
    settings --user current delete secure font_weight_adjustment >/dev/null 2>&1 || \
        settings delete secure font_weight_adjustment >/dev/null 2>&1 || _fwr_defer
    _fwr_check=$(settings --user current get secure font_weight_adjustment 2>/dev/null)
    _fwr_get_rc=$?
    if [ "$_fwr_get_rc" -ne 0 ]; then
        _fwr_check=$(settings get secure font_weight_adjustment 2>/dev/null) || _fwr_defer
    fi
    case "$_fwr_check" in ''|null|NULL|undefined|2147483647|-2147483648) ;; *) _fwr_defer ;; esac
fi
_fwr_clear restored
exit 0
