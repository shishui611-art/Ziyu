#!/system/bin/sh
# App status has its own narrow reader path. Do not source font/mount engines.
set +e
_AS_FORMAT=${1:-json}
case "$_AS_FORMAT" in json|kv) ;; *) exit 2 ;; esac
MODDIR=$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd) || exit 1
# An installer/root shell may leave unrelated module environment behind.
unset MODPATH _status_root_manager
MODULE_DIR=$MODDIR
export MODDIR MODULE_DIR
if [ "$(id -u 2>/dev/null)" != 0 ]; then
    printf '{"status":"error","message":"Root 尚未授权"}\n'; exit 1
fi
for _as_file in root_manager_detection.sh mount_backend_state.sh mount_backend_details.sh temporary_root_mode.sh legacy_identity.sh font_boot_state.sh font_live_state.sh; do
    [ -r "$MODDIR/common/$_as_file" ] || { printf '{"status":"error","message":"模块状态组件缺失"}\n'; exit 1; }
    . "$MODDIR/common/$_as_file" || { printf '{"status":"error","message":"模块状态组件加载失败"}\n'; exit 1; }
done
AXES_TASK_FILE=$MODDIR/config/axes_task.conf
SWITCH_TASK_FILE=$MODDIR/config/switch_task.conf
TEXT_REBOOT_REQUIRED=$MODDIR/config/text_reboot_required.conf
MIX_ENGINE=$MODDIR/common/font_mix_controller.sh
FONT_SWITCH_TASK=$MODDIR/common/font_switch_task.sh

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\r' '  '
}

# Config files may be checked out with CRLF endings on Windows and installed
# without newline normalization. Cache the carriage return once, then keep
# status reads inside the shell instead of starting sed, head and tr per key.
_APP_BRIDGE_CR="$(printf '\r')"

read_prop() {
    _rp_file="${1:-}"
    _rp_key="${2:-}"
    [ -r "$_rp_file" ] || return 0
    while IFS= read -r _rp_line || [ -n "$_rp_line" ]; do
        case "$_rp_line" in
            "$_rp_key"=*)
                _rp_value=${_rp_line#*=}
                case "$_rp_value" in
                    *"$_APP_BRIDGE_CR") _rp_value=${_rp_value%"$_APP_BRIDGE_CR"} ;;
                esac
                printf '%s' "$_rp_value"
                return 0
                ;;
        esac
    done < "$_rp_file"
    return 0
}

read_first_line() {
    _rfl_file="${1:-}"
    _rfl_line=''
    [ -r "$_rfl_file" ] || return 0
    IFS= read -r _rfl_line < "$_rfl_file" || [ -n "$_rfl_line" ] || return 0
    case "$_rfl_line" in
        *"$_APP_BRIDGE_CR") _rfl_line=${_rfl_line%"$_APP_BRIDGE_CR"} ;;
    esac
    printf '%s' "$_rfl_line"
}

root_manager() {
    if [ -n "${_status_root_manager:-}" ]; then printf '%s' "$_status_root_manager"; return; fi
    if type ziyu_detect_root_manager >/dev/null 2>&1; then
        _root=$(ziyu_detect_root_manager 2>/dev/null)
        [ -n "$_root" ] && [ "$_root" != unknown ] && { printf '%s' "$_root"; return; }
    fi
    # Keep the App label aligned with boot-hook identity. An unknown `su`
    # provider is shown generically instead of being guessed from /data/adb/ksu.
    printf 'Root'
}

mount_engine() {
    _backend_file=$(type ziyu_current_boot_backend_state >/dev/null 2>&1 && ziyu_current_boot_backend_state)
    if [ -n "$_backend_file" ]; then
        _backend_active=$(read_prop "$_backend_file" active_backend)
        _backend_selected=$(read_prop "$_backend_file" selected_backend)
        _backend_verify=$(read_prop "$_backend_file" verification)
        _backend_error=$(read_prop "$_backend_file" last_error)
        if [ "$_backend_verify" = not-applicable ]; then
            if [ "$(read_first_line "$MODDIR/config/active_font.conf")" = default ] || \
               [ ! -s "$MODDIR/config/active_font.conf" ]; then
                printf '系统默认字体，无需挂载'
            else
                printf '自定义字体挂载已跳过'
            fi
            return
        fi
        ziyu_mount_backend_details
        _backend_label="$MOUNT_PROVIDER_NAME · $MOUNT_METHOD"
        if [ "$_backend_verify" = failed ]; then
            case "$_backend_selected" in self) _backend_label='自挂载' ;; esac
            case "$_backend_error" in
                *rollback-failed*|*rollback-verification-failed*|*cleanup*|*backend-conflict*) printf '%s失败 · 回滚待检查' "$_backend_label" ;;
                font-route-verification-failed) printf '%s失败 · 已回滚' "$_backend_label" ;;
                *) printf '%s失败 · 未生效' "$_backend_label" ;;
            esac
        elif [ "$_backend_verify" = partial ]; then
            printf '%s · 部分应用（有警告）' "$_backend_label"
        elif [ "$_backend_active" = none ] || [ "$_backend_verify" = pending ]; then
            printf '%s · 待验证' "$_backend_label"
        else
            printf '%s' "$_backend_label"
        fi
        return
    fi
    printf '本次启动尚未确认挂载后端'
}

select_task_file() {
    # queued/running is only trustworthy while its matching worker still exists.
    # Reconcile both controllers before selecting the one visible to the App.
    case "$(read_prop "$AXES_TASK_FILE" state)" in
        queued|running) [ ! -f "$MIX_ENGINE" ] || MODDIR="$MODDIR" sh "$MIX_ENGINE" reconcile >/dev/null 2>&1 ;;
    esac
    case "$(read_prop "$SWITCH_TASK_FILE" state)" in
        queued|running) [ ! -f "$FONT_SWITCH_TASK" ] || MODDIR="$MODDIR" sh "$FONT_SWITCH_TASK" reconcile >/dev/null 2>&1 ;;
    esac
    _axes_state="$(read_prop "$AXES_TASK_FILE" state)"
    _switch_state="$(read_prop "$SWITCH_TASK_FILE" state)"
    case "$_axes_state" in queued|running) printf 'mix|%s\n' "$AXES_TASK_FILE"; return ;; esac
    case "$_switch_state" in queued|running) printf 'switch|%s\n' "$SWITCH_TASK_FILE"; return ;; esac

    _axes_finished="$(read_prop "$AXES_TASK_FILE" finished)"
    _switch_finished="$(read_prop "$SWITCH_TASK_FILE" finished)"
    case "$_axes_finished" in ''|*[!0-9]*) _axes_finished=0 ;; esac
    case "$_switch_finished" in ''|*[!0-9]*) _switch_finished=0 ;; esac
    if [ "$_axes_finished" -ge "$_switch_finished" ] 2>/dev/null; then
        case "$_axes_state" in success|failed) printf 'mix|%s\n' "$AXES_TASK_FILE"; return ;; esac
        case "$_switch_state" in success|failed) printf 'switch|%s\n' "$SWITCH_TASK_FILE"; return ;; esac
    else
        case "$_switch_state" in success|failed) printf 'switch|%s\n' "$SWITCH_TASK_FILE"; return ;; esac
        case "$_axes_state" in success|failed) printf 'mix|%s\n' "$AXES_TASK_FILE"; return ;; esac
    fi
    printf 'none|\n'
}

# Percent-encode only framing characters. No eval, JSON engine or external process.
status_value() {
    _asv_rest=$1
    while [ -n "$_asv_rest" ]; do
        _asv_char=${_asv_rest%"${_asv_rest#?}"}
        _asv_rest=${_asv_rest#?}
        case "$_asv_char" in
            '%') printf '%%25' ;;
            "$_APP_BRIDGE_CR") printf '%%0D' ;;
            '
') printf '%%0A' ;;
            *) printf '%s' "$_asv_char" ;;
        esac
    done
}

status_field() {
    printf '%s=' "$1"
    status_value "$2"
    printf '\n'
}

status_emit() {
    [ "$#" -eq 33 ] || { printf '{"status":"error","message":"模块状态字段不完整"}\n'; return 1; }
    if [ "$_AS_FORMAT" = json ]; then
    printf '{"status":"ok","data":{"root":true,"installed":%s,"version":"%s","versionCode":%s,"active":"%s","effectiveActive":"%s","fontEffectState":"%s","verificationState":"%s","verificationGrade":"%s","verificationMode":"%s","verificationReason":"%s","mountState":"%s","mountFailure":"%s","cutoverState":"%s","cutoverDecision":"%s","rollbackState":"%s","rollbackPending":%s,"rollbackTargetFont":"%s","rollbackTargetMode":"%s","taskType":"%s","taskId":"%s","taskFont":"%s","taskState":"%s","taskMessage":"%s","taskProgress":%s,"rebootRequired":%s,"rootManager":"%s","mountEngine":"%s","moduleDir":"%s","temporaryRootMode":%s,"slotTotal":%s,"slotCjk":%s,"slotDigit":%s,"slotRom":"%s"}}\n' \
        "$_installed" "$(json_escape "$_version")" "${_version_code:-0}" "$(json_escape "$_active")" \
        "$(json_escape "$_effective_active")" "$(json_escape "$_font_effect_state")" \
        "$(json_escape "$_verification_state")" "$(json_escape "$_verification_grade")" "$(json_escape "$_verification_mode")" \
        "$(json_escape "$_verification_reason")" "$(json_escape "$_mount_state")" "$(json_escape "$_mount_failed")" \
        "$(json_escape "$_cutover_state")" "$(json_escape "$_cutover_decision")" "$(json_escape "$_rollback_state")" "$_rollback_pending" \
        "$(json_escape "$_rollback_target_font")" "$(json_escape "$_rollback_target_mode")" \
        "$(json_escape "$_task_type")" "$(json_escape "$_task_id")" "$(json_escape "$_task_font")" "$(json_escape "$_task_state")" \
        "$(json_escape "$_task_message")" "$_task_progress" "$_reboot_required" \
        "$(json_escape "$(root_manager)")" "$(json_escape "$(mount_engine)")" "$(json_escape "$MODDIR")" \
        "$_temporary_root" "$_slot_total" "$_slot_cjk" "$_slot_digit" "$(json_escape "$_slot_rom")"
        return $?
    fi
    printf 'schema=ziyu-app-status-v1\nstatus=ok\nroot=true\n'
    status_field installed "$1"; shift
    status_field version "$1"; shift
    status_field versionCode "$1"; shift
    status_field active "$1"; shift
    status_field effectiveActive "$1"; shift
    status_field fontEffectState "$1"; shift
    status_field verificationState "$1"; shift
    status_field verificationGrade "$1"; shift
    status_field verificationMode "$1"; shift
    status_field verificationReason "$1"; shift
    status_field mountState "$1"; shift
    status_field mountFailure "$1"; shift
    status_field cutoverState "$1"; shift
    status_field cutoverDecision "$1"; shift
    status_field rollbackState "$1"; shift
    status_field rollbackPending "$1"; shift
    status_field rollbackTargetFont "$1"; shift
    status_field rollbackTargetMode "$1"; shift
    status_field taskType "$1"; shift
    status_field taskId "$1"; shift
    status_field taskFont "$1"; shift
    status_field taskState "$1"; shift
    status_field taskMessage "$1"; shift
    status_field taskProgress "$1"; shift
    status_field rebootRequired "$1"; shift
    status_field rootManager "$1"; shift
    status_field mountEngine "$1"; shift
    status_field moduleDir "$1"; shift
    status_field temporaryRootMode "$1"; shift
    status_field slotTotal "$1"; shift
    status_field slotCjk "$1"; shift
    status_field slotDigit "$1"; shift
    status_field slotRom "$1"; shift
    printf 'field_count=34\ncomplete=ziyu-app-status-v1\n'
}

status_json() {
    _status_root_manager=$(root_manager)
    # App refresh is also a safe late-boot convergence point. The helper will
    # never consume a marker created during this same boot.
    type ziyu_text_reboot_reconcile >/dev/null 2>&1 && \
        ZIYU_BOOT_RECONCILE_CACHED_ONLY=1 ziyu_text_reboot_reconcile >/dev/null 2>&1 || true
    _installed=false
    _version='未安装'
    _version_code=0
    if [ -f "$MODDIR/module.prop" ]; then
        _installed=true
        _version="$(read_prop "$MODDIR/module.prop" version)"
        _version_code="$(read_prop "$MODDIR/module.prop" versionCode)"
    fi
    _active="$(read_first_line "$MODDIR/config/active_font.conf")"
    [ -n "$_active" ] || _active='default'
    _universal_runtime="$MODDIR/config/universal-font-runtime.conf"
    _universal_verification="$MODDIR/config/universal-font-runtime-verification.conf"
    _verification_grade=''
    if [ -s "$_universal_runtime" ]; then
        _verification_file="$_universal_verification"
        _verification_reason=''
        _verification_active=''
        if [ -s "$_universal_verification" ]; then
            _verification_grade="$(read_prop "$_verification_file" grade)"
            _verification_reason="$(read_prop "$_verification_file" reason)"
            _verification_active="$(read_prop "$_verification_file" activeFont)"
        fi
        case "$_verification_grade" in
            PASS) _verification_state=verified; _verification_mode=universal-pass ;;
            WARN) _verification_state=warning; _verification_mode=universal-warn ;;
            FAIL) _verification_state=failed; _verification_mode=universal-fail ;;
            *)
                _verification_grade=PENDING
                _verification_state=pending
                _verification_mode=universal-pending
                [ -n "$_verification_reason" ] || _verification_reason=awaiting-runtime-verification
                ;;
        esac
        _mount_state="$(read_prop "$MODDIR/config/universal-font-mount.conf" state)"
        _mount_failed="$(read_prop "$MODDIR/config/universal-font-mount.conf" error)"
    else
        _verification_file="$MODDIR/config/device-font-load-verification.conf"
        _verification_state="$(read_prop "$_verification_file" state)"
        _verification_mode="$(read_prop "$_verification_file" mode)"
        _verification_reason="$(read_prop "$_verification_file" reason)"
        _verification_active="$(read_prop "$_verification_file" activeFont)"
        _mount_state="$(read_prop "$MODDIR/config/self-mount.conf" state)"
        _mount_failed="$(read_prop "$MODDIR/config/self-mount.conf" failed)"
        case "$_verification_state" in
            verified) _verification_grade=PASS ;;
            failed) _verification_grade=FAIL ;;
            *) _verification_grade=PENDING ;;
        esac
    fi
    [ -n "$_verification_state" ] || _verification_state='pending'
    [ -n "$_verification_mode" ] || _verification_mode='unknown'
    [ -n "$_verification_grade" ] || _verification_grade='PENDING'
    [ -n "$_mount_state" ] || _mount_state='unknown'

    # The unified transaction owns the final outcome. Legacy self/universal
    # caches can still say mounted after final route verification rolled back.
    _backend_current=$(type ziyu_current_boot_backend_state >/dev/null 2>&1 && ziyu_current_boot_backend_state)
    _backend_rollback_uncertain=false
    if [ -n "$_backend_current" ]; then
        _backend_verification=$(read_prop "$_backend_current" verification)
        _backend_active=$(read_prop "$_backend_current" active_backend)
        case "$_backend_verification" in
            partial)
                _mount_state=partial
                _mount_failed=$(read_prop "$_backend_current" mount_warning)
                _verification_state=partial; _verification_grade=WARN
                _verification_mode=mount-partial; _verification_reason="$_mount_failed"
                ;;
            failed)
                _mount_state=failed
                _mount_failed=$(read_prop "$_backend_current" last_error)
                _verification_state=failed; _verification_grade=FAIL
                _verification_mode=backend-failed; _verification_reason="$_mount_failed"
                case "$_mount_failed" in *rollback-failed*|*rollback-verification-failed*|*cleanup*|*backend-conflict*) _backend_rollback_uncertain=true ;; esac
                ;;
            passed|pass)
                case "$_backend_active" in
                    self|meta|external|manager)
                        _mount_state=mounted
                        _verification_state=verified; _verification_grade=PASS
                        _verification_mode=backend; _verification_reason=none
                        ;;
                    *) _mount_state=pending ;;
                esac
                ;;
            not-applicable)
                _mount_state=not-applicable
                if [ "$_active" = default ]; then
                    _verification_state=not-applicable; _verification_grade=PASS
                    _verification_mode=system; _verification_reason=default-font
                else
                    _mount_state=skipped
                    _verification_state=pending; _verification_grade=PENDING
                    _verification_mode=backend-skipped; _verification_reason=mount-not-performed
                fi
                ;;
            pending)
                _mount_state=pending; _verification_state=pending; _verification_grade=PENDING
                _verification_reason=backend-awaiting-verification
                ;;
        esac
    elif [ -f "$MODDIR/config/mount-backend.conf" ]; then
        _mount_state=pending; _verification_state=pending; _verification_grade=PENDING
        _verification_reason=backend-current-boot-unconfirmed
    fi
    # 后端本次启动已确认挂载通过时，过期的待重启标记不再作为“需要重启”的依据。
    if [ "$_reboot_required" = true ] && [ -n "$_backend_current" ] &&
       [ "$_mount_state" = mounted ] && { [ "$_verification_state" = verified ] || [ "$_verification_state" = partial ]; }; then
        _reboot_required=false
    fi

    _cutover_file="$MODDIR/config/universal-font-cutover.conf"
    _rollback_file="$MODDIR/config/universal-font-rollback.conf"
    _cutover_state="$(read_prop "$_cutover_file" state)"
    _cutover_decision="$(read_prop "$_cutover_file" decision)"
    _rollback_state="$(read_prop "$_rollback_file" state)"
    _rollback_target_font="$(read_prop "$_rollback_file" targetFont)"
    _rollback_target_mode="$(read_prop "$_rollback_file" targetMode)"
    _rollback_pending=false
    [ "$_rollback_state" = staged ] && _rollback_pending=true
    [ -n "$_cutover_state" ] || _cutover_state=idle
    [ -n "$_cutover_decision" ] || _cutover_decision=none
    [ -n "$_rollback_state" ] || _rollback_state=none

    _selected="$(select_task_file)"
    IFS='|' read -r _task_type _task_file <<EOF_TASK_SELECTION
$_selected
EOF_TASK_SELECTION
    _task_id=''
    _task_font=''
    _task_state='idle'
    _task_message='暂无后台任务'
    _task_progress=0
    if [ -n "$_task_file" ]; then
        _task_id="$(read_prop "$_task_file" task)"
        _task_font="$(read_prop "$_task_file" font)"
        _task_state="$(read_prop "$_task_file" state)"
        _task_message="$(read_prop "$_task_file" message)"
        if [ "$_task_type" = mix ]; then
            _task_progress="$(read_prop "$_task_file" percent)"
        elif [ "$_task_state" = success ] || [ "$_task_state" = failed ]; then
            _task_progress=100
        else
            _task_progress=10
        fi
    fi
    case "$_task_progress" in ''|*[!0-9]*) _task_progress=0 ;; esac
    [ -n "$_task_state" ] || _task_state='idle'
    [ -n "$_task_message" ] || _task_message='暂无后台任务'

    _reboot_required=false
    [ -f "$TEXT_REBOOT_REQUIRED" ] && _reboot_required=true
    _physical_pending=false
    if type ziyu_pending_physical >/dev/null 2>&1 && ziyu_pending_physical "$MODDIR"; then
        _physical_pending=true
        _reboot_required=true
    fi

    _effective_active='unknown'
    _font_effect_state='pending'
    if [ "$_rollback_pending" = true ]; then
        _effective_active=unknown
        _font_effect_state=rollback-pending
    elif type ziyu_live_current >/dev/null 2>&1 && ziyu_live_current && \
         [ ! -e "$MODDIR/config/font-live-transaction.conf" ] && \
         [ "$_mount_state" != failed ] && { [ "$_verification_state" = verified ] || [ "$_verification_state" = partial ]; }; then
        _effective_active=$_zlc_font
        if [ "$_effective_active" = "$_active" ] && [ "$_reboot_required" = false ]; then
            _font_effect_state=live
            [ "$_verification_state" != partial ] || _font_effect_state=live-partial
        else
            _font_effect_state=pending-reboot
        fi
    elif [ -e "$MODDIR/config/font-live-transaction.conf" ]; then
        _font_effect_state=failed
        _verification_reason=live-transaction-recovery-required
    elif [ "$_physical_pending" = true ]; then
        _font_effect_state=pending-reboot
        _verification_state=pending; _verification_grade=PENDING
        _verification_reason=next-boot-payload-prepared
        _pending_previous=$(read_prop "$MODDIR/config/font-payload-next.conf" previousFont)
        if [ -n "$_backend_current" ] && [ "$(read_prop "$_backend_current" verification)" = passed ] &&
           [ "$(read_prop "$_backend_current" payload_font)" = "$_pending_previous" ] && [ -n "$_pending_previous" ]; then
            _effective_active="$_pending_previous"
        fi
    elif [ "$_active" = default ]; then
        _effective_active=default
        _font_effect_state=system
    elif [ "$_reboot_required" = true ]; then
        _font_effect_state=pending-reboot
    elif [ "$_mount_state" = failed ] && [ -n "$_backend_current" ]; then
        _font_effect_state=failed
        _effective_active=default
        [ "$_backend_rollback_uncertain" = true ] && _effective_active=unknown
    elif [ "$_verification_state" = partial ] && [ "$_mount_state" = partial ]; then
        _effective_active="$_active"
        _font_effect_state=partial
    elif [ -n "$_verification_active" ] && [ "$_verification_active" != "$_active" ]; then
        _verification_state=pending
        _verification_mode=unknown
        _verification_reason=stale-verification
    elif [ "$_mount_state" = failed ]; then
        # A failed atomic mount transaction is rolled back before Android consumes
        # the payload, so the ROM default is the only safe effective-font claim.
        _effective_active=default
        _font_effect_state=failed
        _verification_reason=self-mount-failed
    elif [ "$_verification_state" = failed ]; then
        _font_effect_state=failed
        if [ -s "$_universal_runtime" ]; then
            # Runtime verification can fail on coverage/axis/geometry while the
            # Universal payload is still visible in this boot. Never claim that
            # the system default is already active unless the mount transaction
            # itself rolled back.
            _effective_active=unknown
        else
            _effective_active=default
        fi
    elif [ "$_verification_state" = verified ]; then
        case "$_verification_mode" in
            aligned|mount-verified|mount-confirmed|universal-pass|backend)
                _effective_active="$_active"
                _font_effect_state=verified
                ;;
            *) _font_effect_state=unverified ;;
        esac
    else
        _font_effect_state=unverified
    fi

    # Guard status is read-only. A blocked current boot must not inherit a
    # previous generation's verified result from the backend/activation files.
    _boot_guard_status=''
    if [ -f "$MODDIR/config/boot-safety-current-boot.conf" ] &&
       [ ! -L "$MODDIR/config/boot-safety-current-boot.conf" ] &&
       [ "$(read_prop "$MODDIR/config/boot-safety-current-boot.conf" boot_id)" = "$(read_first_line /proc/sys/kernel/random/boot_id)" ] &&
       [ -f "$MODDIR/common/boot_ota_guard.sh" ] &&
       _boot_guard_status=$(sh "$MODDIR/common/boot_ota_guard.sh" status "$MODDIR" 2>/dev/null); then
        _boot_guard_api=''; _boot_guard_blocked=0; _boot_guard_reason=''; _boot_guard_next_ready=0
        while IFS= read -r _boot_guard_line || [ -n "$_boot_guard_line" ]; do
            _boot_guard_line=${_boot_guard_line%"$_APP_BRIDGE_CR"}
            case "$_boot_guard_line" in
                status=ok) _boot_guard_api=ok ;;
                current_boot_blocked=1) _boot_guard_blocked=1 ;;
                next_generation_ready=1) _boot_guard_next_ready=1 ;;
                reason=boot-safety-safe-mode|reason=ota-fingerprint-change|reason=safe-mode|reason=ota-fingerprint)
                    _boot_guard_reason=${_boot_guard_line#reason=} ;;
            esac
        done <<EOF_ZIYU_BOOT_GUARD_STATUS
$_boot_guard_status
EOF_ZIYU_BOOT_GUARD_STATUS
        if [ "$_boot_guard_api" = ok ] && [ "$_boot_guard_blocked" = 1 ]; then
            _effective_active=unknown
            _font_effect_state=failed
            _verification_state=failed
            _verification_grade=none
            _verification_mode=boot-protection
            _verification_reason=${_boot_guard_reason:-boot-safety-blocked}
            _mount_state=blocked
            if [ "$_boot_guard_next_ready" = 1 ] &&
               { [ "$_boot_guard_reason" = ota-fingerprint-change ] || [ "$_boot_guard_reason" = ota-fingerprint ]; }; then
                _font_effect_state=pending-reboot
                _verification_state=pending
                _verification_reason=ota-generation-prepared
                _reboot_required=true
            fi
        fi
    fi

    # Task history records preparation, not the outcome of a later full reboot.
    # Return current verified state without rewriting the historical receipt.
    if [ "$_task_type" = switch ] && [ "$_task_state" = success ] &&
       [ "$_task_font" = "$_active" ] && [ "$_effective_active" = "$_active" ] &&
       [ "$_reboot_required" = false ] && [ "$_rollback_pending" = false ]; then
        case "$_font_effect_state" in
            verified) _task_message='字体已应用，本次启动挂载验证通过' ;;
            partial) _task_message='字体已应用；部分槽位存在提示，请查看验证详情' ;;
            system) _task_message='已恢复系统默认字体' ;;
        esac
    fi

    # 槽位覆盖统计：优先设备槽位发现缓存（fonts.xml + 机型清单），否则读发布记录。
    _temporary_root=false
    type temporary_root_mode >/dev/null 2>&1 && temporary_root_mode && _temporary_root=true
    _slot_total=0; _slot_cjk=0; _slot_digit=0; _slot_latin=0; _slot_rom=''
    _slot_cache="$MODDIR/config/device-slots.list"
    if [ -f "$MODDIR/common/slot_coverage.sh" ]; then
        . "$MODDIR/common/slot_coverage.sh" 2>/dev/null
        type ziyu_slot_discover >/dev/null 2>&1 && ziyu_slot_discover "$MODDIR" >/dev/null 2>&1
    fi
    if [ -s "$_slot_cache" ]; then
        while IFS= read -r _slot_line || [ -n "$_slot_line" ]; do
            case "$_slot_line" in
                count_cjk=*) _slot_cjk=${_slot_line#*=} ;;
                count_digit=*) _slot_digit=${_slot_line#*=} ;;
                count_latin=*) _slot_latin=${_slot_line#*=} ;;
                count_lang=*) _slot_lang=${_slot_line#*=} ;;
            esac
        done < "$_slot_cache"
        # 总数取已发布的负载字体文件数（= 元模块实际注入的槽位数），
        # 保证主页显示与挂载结果一致；负载还没发布时才退回探测口径。
        _slot_payload=0
        for _slot_pf in "$MODDIR/system/fonts"/*.ttf "$MODDIR/system/fonts"/*.otf; do
            [ -f "$_slot_pf" ] && _slot_payload=$((_slot_payload + 1))
        done
        if [ "${_slot_payload:-0}" -gt 0 ]; then
            _slot_total=$_slot_payload
        else
            _slot_total=$((_slot_cjk + _slot_digit + _slot_latin + _slot_lang))
        fi
        type ziyu_slot_rom >/dev/null 2>&1 && _slot_rom=$(ziyu_slot_rom "$MODDIR")
    elif [ -s "$MODDIR/config/slot-coverage.conf" ]; then
        while IFS= read -r _slot_line || [ -n "$_slot_line" ]; do
            case "$_slot_line" in
                total=*) _slot_total=${_slot_line#*=} ;;
                cjk=*) _slot_cjk=${_slot_line#*=} ;;
                digit=*) _slot_digit=${_slot_line#*=} ;;
                rom=*) _slot_rom=${_slot_line#*=} ;;
            esac
        done < "$MODDIR/config/slot-coverage.conf"
    fi
    case "$_slot_total" in ''|*[!0-9]*) _slot_total=0 ;; esac
    case "$_slot_cjk" in ''|*[!0-9]*) _slot_cjk=0 ;; esac
    case "$_slot_digit" in ''|*[!0-9]*) _slot_digit=0 ;; esac
    case "$_slot_rom" in *[!A-Za-z0-9._-]*) _slot_rom='' ;; esac

    # 后端已确认挂载/验证通过时，字体效果就是 verified；否则会一直显示“待本次启动验证”。
    if [ "$_mount_state" = mounted ] && [ "$_verification_state" = verified ] &&
       [ "$_active" != default ] && [ -n "$_active" ]; then
        _effective_active=$_active
        _font_effect_state=verified
    fi

    status_emit \
        "$_installed" "$_version" "${_version_code:-0}" "$_active" \
        "$_effective_active" "$_font_effect_state" \
        "$_verification_state" "$_verification_grade" "$_verification_mode" \
        "$_verification_reason" "$_mount_state" "$_mount_failed" \
        "$_cutover_state" "$_cutover_decision" "$_rollback_state" "$_rollback_pending" \
        "$_rollback_target_font" "$_rollback_target_mode" \
        "$_task_type" "$_task_id" "$_task_font" "$_task_state" \
        "$_task_message" "$_task_progress" "$_reboot_required" \
        "$(root_manager)" "$(mount_engine)" "$MODDIR" \
        "$_temporary_root" "$_slot_total" "$_slot_cjk" "$_slot_digit" "$_slot_rom"
}

case "$(read_prop "$MODDIR/module.prop" id)" in
    ziyu|LuoShu) ;;
    *) printf '{"status":"error","message":"模块身份未通过核对"}\n'; exit 1 ;;
esac
_as_code=$(read_prop "$MODDIR/module.prop" versionCode)
case "$_as_code" in ''|*[!0-9]*) printf '{"status":"error","message":"模块版本无法读取"}\n'; exit 1 ;; esac
status_json
exit $?
