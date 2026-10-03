#!/system/bin/sh
# The optional global font-weight control. Changes only Android's secure setting;
# user font files and the active font payload are never modified here.
set +e

FW_MODDIR="${MODDIR:-${MODULE_DIR:-}}"
if [ -z "$FW_MODDIR" ]; then
    FW_MODDIR="$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)"
fi
FW_CONFIG_DIR="$FW_MODDIR/config"
FW_CURRENT="$FW_CONFIG_DIR/font_weight.conf"
FW_ORIGINAL="$FW_CONFIG_DIR/font_weight_original.conf"
FW_MIGRATION="$FW_CONFIG_DIR/font-weight-retired-v2.conf"
FW_KEY=font_weight_adjustment

fw_json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\r' '  '
}

fw_error() {
    printf '{"status":"error","message":"%s"}\n' "$(fw_json_escape "$1")"
}

fw_report() {
    [ -d "$FW_CONFIG_DIR" ] || mkdir -p "$FW_CONFIG_DIR" 2>/dev/null || return 1
    printf 'state=%s\n' "$1" > "$FW_MIGRATION.tmp.$$" 2>/dev/null && \
        mv -f "$FW_MIGRATION.tmp.$$" "$FW_MIGRATION" 2>/dev/null
}

fw_read_current() {
    command -v settings >/dev/null 2>&1 || return 5
    FW_RAW=$(settings --user current get secure "$FW_KEY" 2>/dev/null)
    FW_READ_RC=$?
    if [ "$FW_READ_RC" -ne 0 ]; then
        FW_RAW=$(settings get secure "$FW_KEY" 2>/dev/null)
        FW_READ_RC=$?
    fi
    [ "$FW_READ_RC" -eq 0 ] || return 4
    case "$FW_RAW" in
        ''|null|NULL|undefined|2147483647|-2147483648)
            FW_PRESENT=false
            FW_ADJUSTMENT=0
            return 0
            ;;
    esac
    printf '%s\n' "$FW_RAW" | grep -Eq '^-?[0-9]{1,4}$' || return 4
    [ "$FW_RAW" -ge -1000 ] 2>/dev/null && [ "$FW_RAW" -le 1000 ] 2>/dev/null || return 4
    FW_PRESENT=true
    FW_ADJUSTMENT="$FW_RAW"
}

fw_put_current() {
    _fw_put_value="$1"
    settings --user current put secure "$FW_KEY" "$_fw_put_value" >/dev/null 2>&1 && return 0
    settings put secure "$FW_KEY" "$_fw_put_value" >/dev/null 2>&1
}

fw_delete_current() {
    settings --user current delete secure "$FW_KEY" >/dev/null 2>&1 && return 0
    settings delete secure "$FW_KEY" >/dev/null 2>&1
}

fw_write_original() {
    _fw_orig_present="$1"
    _fw_orig_adjustment="$2"
    case "$_fw_orig_present" in true|false) ;; *) return 1 ;; esac
    case "$_fw_orig_adjustment" in ''|*[!0-9-]*) return 1 ;; esac
    mkdir -p "$FW_CONFIG_DIR" 2>/dev/null || return 1
    {
        printf 'present=%s\n' "$_fw_orig_present"
        printf 'adjustment=%s\n' "$_fw_orig_adjustment"
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$FW_ORIGINAL.tmp.$$" 2>/dev/null || return 1
    mv -f "$FW_ORIGINAL.tmp.$$" "$FW_ORIGINAL" 2>/dev/null || {
        rm -f "$FW_ORIGINAL.tmp.$$" 2>/dev/null
        return 1
    }
    chmod 0644 "$FW_ORIGINAL" 2>/dev/null || true
}

fw_write_active() {
    _fw_active_weight="$1"
    _fw_active_adjustment="$2"
    mkdir -p "$FW_CONFIG_DIR" 2>/dev/null || return 1
    {
        printf 'weight=%s\n' "$_fw_active_weight"
        printf 'adjustment=%s\n' "$_fw_active_adjustment"
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$FW_CURRENT.tmp.$$" 2>/dev/null || return 1
    mv -f "$FW_CURRENT.tmp.$$" "$FW_CURRENT" 2>/dev/null || {
        rm -f "$FW_CURRENT.tmp.$$" 2>/dev/null
        return 1
    }
    chmod 0644 "$FW_CURRENT" 2>/dev/null || true
}

fw_load_active() {
    [ -s "$FW_CURRENT" ] || return 1
    FW_LAST_ADJUSTMENT=$(sed -n 's/^adjustment=//p' "$FW_CURRENT" 2>/dev/null | head -n1 | tr -d '\r\n')
    FW_LAST_WEIGHT=$(sed -n 's/^weight=//p' "$FW_CURRENT" 2>/dev/null | head -n1 | tr -d '\r\n')
    printf '%s\n' "$FW_LAST_ADJUSTMENT" | grep -Eq '^-?[0-9]{1,4}$' || return 1
    [ "$FW_LAST_ADJUSTMENT" -ge -1000 ] 2>/dev/null && [ "$FW_LAST_ADJUSTMENT" -le 1000 ] 2>/dev/null || return 1
    case "$FW_LAST_WEIGHT" in ''|*[!0-9]*) FW_LAST_WEIGHT=$((400 + FW_LAST_ADJUSTMENT)) ;; esac
    [ "$FW_LAST_WEIGHT" -ge 300 ] 2>/dev/null || FW_LAST_WEIGHT=300
    [ "$FW_LAST_WEIGHT" -le 700 ] 2>/dev/null || FW_LAST_WEIGHT=700
}

fw_load_original() {
    [ -s "$FW_ORIGINAL" ] || return 1
    FW_ORIG_PRESENT=$(sed -n 's/^present=//p' "$FW_ORIGINAL" 2>/dev/null | head -n1 | tr -d '\r\n')
    FW_ORIG_ADJUSTMENT=$(sed -n 's/^adjustment=//p' "$FW_ORIGINAL" 2>/dev/null | head -n1 | tr -d '\r\n')
    # v1.1.1 stored only a normalized numeric value, so keep its historical meaning.
    [ -n "$FW_ORIG_PRESENT" ] || FW_ORIG_PRESENT=true
    case "$FW_ORIG_PRESENT" in true|false) ;; *) return 1 ;; esac
    printf '%s\n' "$FW_ORIG_ADJUSTMENT" | grep -Eq '^-?[0-9]{1,4}$' || return 1
    [ "$FW_ORIG_ADJUSTMENT" -ge -1000 ] 2>/dev/null && [ "$FW_ORIG_ADJUSTMENT" -le 1000 ] 2>/dev/null || return 1
}

fw_matches_active() {
    [ "$FW_PRESENT" = true ] && [ "$FW_ADJUSTMENT" = "$FW_LAST_ADJUSTMENT" ]
}

fw_matches_original() {
    if [ "$FW_ORIG_PRESENT" = false ] && [ "$FW_PRESENT" = true ] && [ "$FW_ADJUSTMENT" = 0 ]; then
        return 0
    fi
    [ "$FW_PRESENT" = "$FW_ORIG_PRESENT" ] && [ "$FW_ADJUSTMENT" = "$FW_ORIG_ADJUSTMENT" ]
}

fw_clear_owned_state() {
    rm -f "$FW_CURRENT" "$FW_ORIGINAL" "$FW_CONFIG_DIR/font_weight_reboot_required.conf" 2>/dev/null || true
}

fw_apply_notifications() {
    command -v cmd >/dev/null 2>&1 && cmd font system --update >/dev/null 2>&1 || true
    command -v am >/dev/null 2>&1 && am broadcast -a android.intent.action.CONFIGURATION_CHANGED >/dev/null 2>&1 || true
}

fw_restore_original() {
    if [ "$FW_ORIG_PRESENT" = true ]; then
        fw_put_current "$FW_ORIG_ADJUSTMENT" || return 1
    else
        fw_delete_current || return 1
    fi
    fw_read_current || return 1
    fw_matches_original
}

fw_snap() {
    _fw_snap_value="$1"
    [ "$_fw_snap_value" -ge 300 ] 2>/dev/null || _fw_snap_value=300
    [ "$_fw_snap_value" -le 700 ] 2>/dev/null || _fw_snap_value=700
    _fw_snap_value=$((300 + (((_fw_snap_value - 300 + 5) / 10) * 10)))
    [ "$_fw_snap_value" -le 700 ] 2>/dev/null || _fw_snap_value=700
    printf '%s\n' "$_fw_snap_value"
}

fw_status() {
    if ! fw_read_current; then
        printf '%s\n' '{"status":"ok","data":{"supported":false,"weight":400,"adjustment":0,"systemAdjustment":null,"originalAdjustment":0,"originalPresent":false,"ownedByModule":false,"min":300,"max":700,"step":10,"message":"当前系统不支持读取安全字体粗细设置"}}'
        return 0
    fi
    _fw_system_present="$FW_PRESENT"
    _fw_system_adjustment="$FW_ADJUSTMENT"
    _fw_weight=$(fw_snap $((400 + _fw_system_adjustment)))
    _fw_adjustment="$_fw_system_adjustment"
    _fw_original=0
    _fw_original_present=false
    _fw_owned=false
    _fw_message='拖动后会立即应用；部分未刷新的应用需要重新打开'
    if fw_load_active && fw_load_original; then
        if fw_matches_active || fw_matches_original; then
            _fw_weight="$FW_LAST_WEIGHT"
            _fw_adjustment="$FW_LAST_ADJUSTMENT"
            _fw_original="$FW_ORIG_ADJUSTMENT"
            _fw_original_present="$FW_ORIG_PRESENT"
            _fw_owned=true
            if fw_matches_original && ! fw_matches_active; then
                _fw_message="本次开机系统值回到了原始粗细；开机服务将恢复 $_fw_weight"
            fi
        else
            _fw_message='系统粗细已被其他设置更改；字域会保留该值'
        fi
    fi
    if [ "$_fw_system_present" = true ]; then _fw_json_system="$_fw_system_adjustment"; else _fw_json_system=null; fi
    printf '{"status":"ok","data":{"supported":true,"weight":%s,"adjustment":%s,"systemAdjustment":%s,"originalAdjustment":%s,"originalPresent":%s,"ownedByModule":%s,"min":300,"max":700,"step":10,"message":"%s"}}\n' \
        "$_fw_weight" "$_fw_adjustment" "$_fw_json_system" "$_fw_original" "$_fw_original_present" "$_fw_owned" "$(fw_json_escape "$_fw_message")"
}

fw_set() {
    _fw_input="$1"
    case "$_fw_input" in ''|*[!0-9]*) fw_error '粗细仅支持 300 到 700，步进为 10'; return 2 ;; esac
    [ "$_fw_input" -ge 300 ] 2>/dev/null && [ "$_fw_input" -le 700 ] 2>/dev/null && [ $((_fw_input % 10)) -eq 0 ] || {
        fw_error '粗细仅支持 300 到 700，步进为 10'
        return 2
    }
    fw_read_current || { fw_error '当前系统不支持字体粗细设置'; return 5; }
    _fw_original_created=false

    if [ -s "$FW_CURRENT" ]; then
        if fw_load_active && fw_load_original && { fw_matches_active || fw_matches_original; }; then
            : # Keep the original value captured at the first user change.
        else
            # The current setting no longer belongs to this module. An explicit
            # slider change opts back in and captures the newer system value.
            fw_clear_owned_state
        fi
    elif [ -s "$FW_ORIGINAL" ]; then
        rm -f "$FW_ORIGINAL" 2>/dev/null || true
    fi

    if [ ! -s "$FW_ORIGINAL" ]; then
        fw_write_original "$FW_PRESENT" "$FW_ADJUSTMENT" || { fw_error '无法保存原始系统粗细'; return 1; }
        _fw_original_created=true
    fi
    _fw_target_adjustment=$((_fw_input - 400))
    fw_put_current "$_fw_target_adjustment" || {
        [ "$_fw_original_created" != true ] || rm -f "$FW_ORIGINAL" 2>/dev/null || true
        fw_error '无法写入系统字体粗细'
        return 4
    }
    fw_read_current && [ "$FW_PRESENT" = true ] && [ "$FW_ADJUSTMENT" = "$_fw_target_adjustment" ] || {
        if fw_load_original; then fw_restore_original >/dev/null 2>&1 || true; fi
        fw_error '系统未确认新的字体粗细设置'
        return 4
    }
    fw_write_active "$_fw_input" "$_fw_target_adjustment" || {
        fw_load_original && fw_restore_original >/dev/null 2>&1 || true
        fw_clear_owned_state
        fw_error '无法保存当前系统粗细状态，已尝试恢复原值'
        return 1
    }
    fw_report feature-enabled >/dev/null 2>&1 || true
    fw_apply_notifications
    printf '{"status":"ok","data":{"weight":%s,"adjustment":%s,"message":"系统粗细已更新；未刷新的应用请重新打开"}}\n' \
        "$_fw_input" "$_fw_target_adjustment"
}

fw_reset() {
    if ! fw_load_active || ! fw_load_original; then
        fw_clear_owned_state
        printf '%s\n' '{"status":"ok","data":{"reset":false,"message":"没有字域保存的原始系统粗细"}}'
        return 0
    fi
    fw_read_current || { fw_error '无法读取当前系统粗细'; return 4; }
    if ! fw_matches_active && ! fw_matches_original; then
        fw_clear_owned_state
        fw_report external-change >/dev/null 2>&1 || true
        printf '%s\n' '{"status":"ok","data":{"reset":false,"message":"检测到其他设置已修改系统粗细，已保留该值"}}'
        return 0
    fi
    _fw_changed=true
    fw_matches_original && _fw_changed=false
    if [ "$_fw_changed" = true ]; then
        fw_restore_original || { fw_error '无法恢复原始系统字体粗细'; return 4; }
        fw_apply_notifications
    fi
    fw_clear_owned_state
    fw_report reset >/dev/null 2>&1 || true
    printf '{"status":"ok","data":{"reset":true,"weight":%s,"message":"已恢复系统原始字体粗细"}}\n' \
        "$(fw_snap $((400 + FW_ADJUSTMENT)))"
}

fw_boot() {
    fw_load_active && fw_load_original || return 0
    fw_read_current || return 1
    if fw_matches_active; then
        return 0
    fi
    if ! fw_matches_original; then
        fw_clear_owned_state
        fw_report external-change >/dev/null 2>&1 || true
        printf '%s\n' 'global font weight: external setting preserved'
        return 0
    fi
    fw_put_current "$FW_LAST_ADJUSTMENT" || return 1
    fw_read_current && [ "$FW_PRESENT" = true ] && [ "$FW_ADJUSTMENT" = "$FW_LAST_ADJUSTMENT" ] || return 1
    fw_apply_notifications
    printf 'global font weight restored: %s\n' "$FW_LAST_WEIGHT"
}

fw_service() {
    _fw_wait=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ] && [ "$_fw_wait" -lt 600 ]; do
        sleep 3
        _fw_wait=$((_fw_wait + 3))
    done
    [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || return 0

    # Finish a one-time v2.0 retirement migration before applying this version's
    # setting. The helper only restores a value still owned by the older release.
    if [ -f "$FW_MODDIR/common/font_weight_retire.sh" ] && \
       grep -qx 'state=pending' "$FW_MIGRATION" 2>/dev/null; then
        sh "$FW_MODDIR/common/font_weight_retire.sh" "$FW_MODDIR" "$FW_MODDIR" boot \
            >> "$FW_MODDIR/logs/font-weight-retire.log" 2>&1 || true
    fi
    [ -s "$FW_CURRENT" ] && [ -s "$FW_ORIGINAL" ] || return 0
    if [ -f "$FW_MODDIR/common/task_scope.sh" ]; then
        MODDIR="$FW_MODDIR" MODULE_DIR="$FW_MODDIR" \
            sh "$FW_MODDIR/common/task_scope.sh" --timeout 15 -- sh "$0" boot
    else
        fw_boot
    fi
}

case "${1:-status}" in
    status) fw_status ;;
    set) fw_set "${2:-}" ;;
    reset) fw_reset ;;
    boot) fw_boot ;;
    service) fw_service ;;
    *) fw_error '未知的系统粗细操作'; exit 2 ;;
esac
