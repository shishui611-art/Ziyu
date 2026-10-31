#!/system/bin/sh
# Foreground ROM check. Heavy work uses the existing owned rescan supervisor.
set +e
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
MODULE_DIR="$MODDIR"
. "$MODDIR/common/boot_ota_guard.sh" || exit 1

_arm_reply() {
    printf 'status=ok\nrom_state=%s\nmessage=%s\n' "$1" "$2"
}

_arm_valid_identity() {
    local _arm_digest
    case "$1" in system-file-v2:*) ;; *) return 1 ;; esac
    _arm_digest=${1#system-file-v2:}
    case "$_arm_digest" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ "${#_arm_digest}" = 64 ]
}

_arm_observe() {
    local _arm_file _arm_tmp
    _arm_file="$MODDIR/config/app-rom-observed.conf"; _arm_tmp="$_arm_file.tmp.$$"
    [ ! -L "$_arm_file" ] || return 1
    mkdir -p "$MODDIR/config" || return 1
    (umask 077; printf 'schema=ziyu-app-rom-v1\nrom_identity=%s\n' "$1" > "$_arm_tmp") && \
        mv -f "$_arm_tmp" "$_arm_file"
}

_arm_has_receipt() {
    [ "$(_zbg_value "$MODDIR/config/stock-rom-scan.conf" schema)" = ziyu-stock-rom-v1 ] && \
    [ "$(_zbg_value "$MODDIR/config/stock-rom-scan.conf" rom_identity)" = "$1" ] && \
    [ ! -e "$MODDIR/config/stock_inventory_scan_pending" ] && \
    [ -s "$MODDIR/config/device_font_inventory.json" ] && [ -s "$MODDIR/config/device_font_topology.json" ]
}

_arm_status() {
    local _arm_rom _arm_scan
    _arm_rom=$(_zbg_rom_identity)
    _arm_valid_identity "$_arm_rom" || { _arm_reply unavailable '暂时无法读取可信 ROM 身份，本次未启动扫描'; return; }
    if _arm_has_receipt "$_arm_rom"; then
        _arm_reply complete '原厂字体与拓扑已核验；如需应用到新系统，请重新应用字体'
        return
    fi
    _arm_scan=$(_zbg_rescan_state "$MODDIR")
    case "$_arm_scan" in
        running|queued) _arm_reply "$_arm_scan" '正在后台核验原厂字体与拓扑，可继续使用 App' ;;
        *)
            if [ "$(_zbg_value "$MODDIR/config/stock-rom-auto-attempt.conf" rom_identity)" = "$_arm_rom" ]; then
                _arm_reply failed '原厂字体自动核验未完成，已停止自动重试；请到设置 → 开机保护查看原因并手动重试'
            else _arm_reply unchanged 'ROM 检查完成，本次没有启动完整扫描'; fi ;;
    esac
}

# Called while the guard's short request lock is held. It neither waits for
# FontTools nor unmounts an existing font view.
_zbg_app_rom_check_locked() (
    local _arm_rom _arm_old _arm_guard _arm_pending _arm_fp _arm_rc _arm_reply_text
    _arm_rom=$(_zbg_rom_identity)
    _arm_valid_identity "$_arm_rom" || { _arm_reply unavailable '暂时无法读取可信 ROM 身份，本次未启动扫描'; return; }
    _arm_old=$(_zbg_value "$MODDIR/config/app-rom-observed.conf" rom_identity)
    if [ "$(_zbg_value "$MODDIR/config/app-rom-observed.conf" schema)" != ziyu-app-rom-v1 ] || ! _arm_valid_identity "$_arm_old"; then _arm_old=''; fi
    _arm_pending=$(_zbg_ota_pending "$MODDIR")
    # Do not compete with an installation or with an incomplete Android boot.
    if [ -f "${MODDIR%/modules/*}/modules_update/${MODDIR##*/}/module.prop" ] || ! _zbg_boot_completed; then
        _arm_reply deferred '系统或模块更新尚未完成，本次未启动扫描'; return
    fi
    if [ "$_arm_old" = "$_arm_rom" ] && [ "${_arm_pending%%\|*}" != 1 ]; then
        _arm_reply unchanged 'ROM 未变，继续使用已有字体清单和拓扑'; return
    fi
    if [ -z "$_arm_old" ] && [ "${_arm_pending%%\|*}" != 1 ]; then
        _zbg_load_state "$MODDIR"
        _arm_guard=$ZBG_ROM_IDENTITY
        _arm_valid_identity "$_arm_guard" || _arm_guard=$_arm_rom
        if [ "$_arm_guard" = "$_arm_rom" ]; then
            _arm_observe "$_arm_rom" || return 1
            _arm_reply initialized '已建立 ROM 观察记录，本次未推断系统更新或启动扫描'; return
        fi
    fi
    if [ "$(_zbg_rom_identity)" != "$_arm_rom" ]; then
        _arm_reply deferred 'ROM 读取结果暂不稳定，本次未启动扫描'; return
    fi
    . "$MODDIR/common/font_switch_lock.sh" || return 1
    if ! luoshu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$"; then
        _arm_reply deferred '字体切换正在进行，本次未修改 ROM 基准或启动扫描'; return
    fi
    trap 'luoshu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1' EXIT
    if [ "${_arm_pending%%\|*}" = 1 ] && _zbg_stock_scan_trusted "$MODDIR" && ! _arm_has_receipt "$_arm_rom"; then
        if [ -s "$MODDIR/config/device_font_topology.json" ]; then
            _zbg_set_scan_complete "$MODDIR" "$_arm_rom" || return 1
        else
            : > "$MODDIR/config/stock_inventory_scan_pending" || return 1
        fi
    fi
    # Existing verified output can close an old default-ready hold without a
    # second full scan. Only a matching trusted receipt is reusable.
    if _arm_has_receipt "$_arm_rom"; then
        if [ "${_arm_pending%%\|*}" = 1 ]; then
            if ! _zbg_stock_scan_trusted "$MODDIR"; then _zbg_set_scan_complete "$MODDIR" "$_arm_rom" || return 1; fi
            if [ "$(_zbg_value "$(_zbg_ota_path "$MODDIR")" state)" = default-ready ]; then
                _zbg_finalize_default_after_scan "$MODDIR" || return 1
            fi
        fi
        if [ "$_arm_old" != "$_arm_rom" ]; then _arm_observe "$_arm_rom" || return 1; fi
        _arm_reply complete '已复用当前 ROM 的原厂字体核验结果，无需重复扫描'; return
    fi
    if [ "${_arm_pending%%\|*}" != 1 ]; then
        # A comparable recorded system-file identity changed. Do not infer an
        # OTA from a missing baseline or a changed global getprop string alone.
        _arm_fp=$(_zbg_fingerprint)
        [ -n "$_arm_fp" ] || { _arm_reply unavailable '系统版本信息不可用，本次未启动扫描'; return; }
        _zbg_write_ota_marker "$MODDIR" "$_arm_fp" "$_arm_fp" 0 "$(_zbg_boot_id)" || return 1
        _zbg_invalidate_cache "$MODDIR" || return 1
        if _zbg_effective_custom_font "$MODDIR"; then
            _zbg_create_disable "$MODDIR" ota_hold "$(_zbg_boot_id)" || return 1
        fi
        _zbg_log "$MODDIR" "App observed ROM change old=$_arm_old new=$_arm_rom; scheduling trusted rescan"
    fi
    if [ "$_arm_old" != "$_arm_rom" ]; then _arm_observe "$_arm_rom" || return 1; fi
    _arm_reply_text=$(_zbg_queue_rescan_locked "$MODDIR" auto); _arm_rc=$?
    _zbg_log "$MODDIR" "App ROM rescan request rc=$_arm_rc $(printf '%s' "$_arm_reply_text" | tr '\n\r' ' ' | cut -c1-800)"
    if [ "$_arm_rc" = 0 ]; then _arm_status
    else _arm_reply failed '原厂字体自动核验未启动或已失败；已保留状态，请到开机保护手动重试'; fi
)

case "${1:-check}" in
    check) _zbg_queue_rescan "$MODDIR" app-check ;;
    status) _arm_status ;;
    *) printf 'status=error\nreason=invalid-action\n'; exit 2 ;;
esac
