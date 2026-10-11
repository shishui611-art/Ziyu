#!/system/bin/sh
# 字域 v2.0.0 原生 App 核心桥：状态、字体库、导入、预览、切换与复合任务接口。
set +e

MODDIR="${MODDIR:-}"
if [ -z "$MODDIR" ]; then
    if [ -f "${0%/*}/../module.prop" ]; then
        MODDIR="$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)"
    else
        MODDIR="/data/adb/modules/LuoShu"
    fi
fi
[ -f "$MODDIR/common/mount_backend_details.sh" ] && . "$MODDIR/common/mount_backend_details.sh"
[ -f "$MODDIR/common/root_manager_detection.sh" ] && . "$MODDIR/common/root_manager_detection.sh"
[ -f "$MODDIR/common/temporary_root_mode.sh" ] && . "$MODDIR/common/temporary_root_mode.sh"
[ -f "$MODDIR/common/meta_mount_detection.sh" ] && . "$MODDIR/common/meta_mount_detection.sh"
FONT_MANAGER="$MODDIR/common/font_manager.sh"
FONT_SWITCH_TASK="$MODDIR/common/font_switch_task.sh"
SAFE_SWITCH="$MODDIR/common/legacy_v14_4/font_switch_safe.sh"
MIX_ENGINE="$MODDIR/common/font_mix_controller.sh"
NATIVE_IMPORT="$MODDIR/common/native_import.sh"
AXIS_INFO="$MODDIR/common/font_axis_info.py"
SOURCE_PROFILE="$MODDIR/common/font_source_profile.sh"
UNIVERSAL_PLAN="$MODDIR/common/universal_font_plan.sh"
MINIMAL_XML_ROUTER="$MODDIR/common/minimal_xml_router.sh"
UNIVERSAL_COMPILER="$MODDIR/common/universal_font_compiler.sh"
UNIVERSAL_DEPLOYMENT="$MODDIR/common/universal_font_deployment.sh"
UNIVERSAL_VERIFY="$MODDIR/common/universal_font_runtime_verify.sh"
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/luoshu-python"
USER_FONTS_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}/fonts"
AXES_TASK_FILE="$MODDIR/config/axes_task.conf"
SWITCH_TASK_FILE="$MODDIR/config/switch_task.conf"
TEXT_REBOOT_REQUIRED="$MODDIR/config/text_reboot_required.conf"
[ -f "$MODDIR/common/util_functions.sh" ] && . "$MODDIR/common/util_functions.sh"
[ -f "$MODDIR/common/mount_compat.sh" ] && . "$MODDIR/common/mount_compat.sh"
[ -f "$MODDIR/common/font_boot_state.sh" ] && . "$MODDIR/common/font_boot_state.sh"

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
    if type luoshu_detect_root_manager >/dev/null 2>&1; then
        _root=$(luoshu_detect_root_manager 2>/dev/null)
        [ -n "$_root" ] && [ "$_root" != unknown ] && { printf '%s' "$_root"; return; }
    fi
    # Keep the App label aligned with boot-hook identity. An unknown `su`
    # provider is shown generically instead of being guessed from /data/adb/ksu.
    printf 'Root'
}

temporary_root_mode() {
    case "$(root_manager)" in KernelSU|SukiSU\ Ultra) ;; *) return 1 ;; esac
    ziyu_temporary_root_mode "$MODDIR"
}

mount_engine() {
    _backend_file=$(type luoshu_current_boot_backend_state >/dev/null 2>&1 && luoshu_current_boot_backend_state)
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
        luoshu_mount_backend_details
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
    [ -f "$MIX_ENGINE" ] && MODDIR="$MODDIR" sh "$MIX_ENGINE" reconcile >/dev/null 2>&1 || true
    [ -f "$FONT_SWITCH_TASK" ] && MODDIR="$MODDIR" sh "$FONT_SWITCH_TASK" reconcile >/dev/null 2>&1 || true
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

status_json() {
    # App refresh is also a safe late-boot convergence point. The helper will
    # never consume a marker created during this same boot.
    type luoshu_text_reboot_reconcile >/dev/null 2>&1 && \
        LUOSHU_BOOT_RECONCILE_CACHED_ONLY=1 luoshu_text_reboot_reconcile >/dev/null 2>&1 || true
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
    _backend_current=$(type luoshu_current_boot_backend_state >/dev/null 2>&1 && luoshu_current_boot_backend_state)
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
                case "$_backend_active" in self|meta|external) _mount_state=mounted ;; *) _mount_state=pending ;; esac
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
    _task_state='idle'
    _task_message='暂无后台任务'
    _task_progress=0
    if [ -n "$_task_file" ]; then
        _task_id="$(read_prop "$_task_file" task)"
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
    _temporary_root=false
    temporary_root_mode && _temporary_root=true

    _effective_active='unknown'
    _font_effect_state='pending'
    if [ "$_rollback_pending" = true ]; then
        _effective_active=unknown
        _font_effect_state=rollback-pending
    elif ziyu_live_current && [ ! -e "$MODDIR/config/font-live-transaction.conf" ] && \
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
            aligned|mount-verified|mount-confirmed|universal-pass)
                _effective_active="$_active"
                _font_effect_state=verified
                ;;
            *) _font_effect_state=unverified ;;
        esac
    else
        _font_effect_state=unverified
    fi

    printf '{"status":"ok","data":{"root":true,"installed":%s,"version":"%s","versionCode":%s,"active":"%s","effectiveActive":"%s","fontEffectState":"%s","verificationState":"%s","verificationGrade":"%s","verificationMode":"%s","verificationReason":"%s","mountState":"%s","mountFailure":"%s","cutoverState":"%s","cutoverDecision":"%s","rollbackState":"%s","rollbackPending":%s,"rollbackTargetFont":"%s","rollbackTargetMode":"%s","taskType":"%s","taskId":"%s","taskState":"%s","taskMessage":"%s","taskProgress":%s,"rebootRequired":%s,"temporaryRootMode":%s,"rootManager":"%s","mountEngine":"%s","moduleDir":"%s"}}\n' \
        "$_installed" "$(json_escape "$_version")" "${_version_code:-0}" "$(json_escape "$_active")" \
        "$(json_escape "$_effective_active")" "$(json_escape "$_font_effect_state")" \
        "$(json_escape "$_verification_state")" "$(json_escape "$_verification_grade")" "$(json_escape "$_verification_mode")" \
        "$(json_escape "$_verification_reason")" "$(json_escape "$_mount_state")" "$(json_escape "$_mount_failed")" \
        "$(json_escape "$_cutover_state")" "$(json_escape "$_cutover_decision")" "$(json_escape "$_rollback_state")" "$_rollback_pending" \
        "$(json_escape "$_rollback_target_font")" "$(json_escape "$_rollback_target_mode")" \
        "$(json_escape "$_task_type")" "$(json_escape "$_task_id")" "$(json_escape "$_task_state")" \
        "$(json_escape "$_task_message")" "$_task_progress" "$_reboot_required" "$_temporary_root" \
        "$(json_escape "$(root_manager)")" "$(json_escape "$(mount_engine)")" "$(json_escape "$MODDIR")"
}

manager_ready() {
    [ -x "$FONT_MANAGER" ] || [ -f "$FONT_MANAGER" ] || {
        printf '{"status":"error","message":"字体管理器不存在"}\n'
        return 1
    }
    return 0
}

switch_task_ready() {
    [ -x "$FONT_SWITCH_TASK" ] || [ -f "$FONT_SWITCH_TASK" ] || {
        printf '{"status":"error","message":"字体切换守卫不存在"}\n'
        return 1
    }
    return 0
}

mix_ready() {
    [ -x "$MIX_ENGINE" ] || [ -f "$MIX_ENGINE" ] || {
        printf '{"status":"error","message":"复合字体引擎不存在"}\n'
        return 1
    }
    return 0
}

font_file_sha256() {
    _file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$_file" 2>/dev/null | awk '{print $1}'
    elif command -v busybox >/dev/null 2>&1; then
        busybox sha256sum "$_file" 2>/dev/null | awk '{print $1}'
    fi
}

preview_role_number() {
    case "$1" in
        thin) echo 100 ;; extralight) echo 200 ;; light) echo 300 ;; regular|normal) echo 400 ;;
        medium) echo 500 ;; semibold) echo 600 ;; bold) echo 700 ;; extrabold) echo 800 ;;
        black|heavy) echo 900 ;; *) echo 400 ;;
    esac
}

find_preview_source() {
    _family="$1"
    _target="${2:-400}"
    case "$_target" in ''|*[!0-9]*) _target=400 ;; esac
    _variable=''
    _best=''
    _best_score=99999
    for _f in "$USER_FONTS_DIR"/*.ttf "$USER_FONTS_DIR"/*.otf "$USER_FONTS_DIR"/*.ttc \
              "$USER_FONTS_DIR"/*.TTF "$USER_FONTS_DIR"/*.OTF "$USER_FONTS_DIR"/*.TTC; do
        [ -f "$_f" ] || continue
        if type detect_font_family >/dev/null 2>&1; then
            _detected="$(detect_font_family "$(basename "$_f")")"
        else
            _detected="$(basename "$_f")"; _detected="${_detected%.*}"; _detected="${_detected%-Regular}"
        fi
        [ "$_detected" = "$_family" ] || continue
        if type is_variable_font >/dev/null 2>&1 && is_variable_font "$_f" 2>/dev/null; then
            [ -n "$_variable" ] || _variable="$_f"
            continue
        fi
        _role=regular
        type detect_font_weight >/dev/null 2>&1 && _role="$(detect_font_weight "$(basename "$_f")")"
        _number="$(preview_role_number "$_role")"
        _score=$((_number - _target)); [ "$_score" -ge 0 ] 2>/dev/null || _score=$((-_score))
        if [ -z "$_best" ] || [ "$_score" -lt "$_best_score" ] 2>/dev/null; then
            _best="$_f"; _best_score="$_score"
        fi
    done
    if [ -n "$_variable" ]; then printf '%s\n' "$_variable"
    elif [ -n "$_best" ]; then printf '%s\n' "$_best"
    else return 1
    fi
}

preview_source_json() {
    _family="$1"
    _src="$(find_preview_source "$_family" "${2:-400}")"
    [ -f "$_src" ] || { printf '{"status":"error","message":"找不到预览字体"}\n'; return 1; }
    _bytes="$(wc -c < "$_src" 2>/dev/null | tr -d '[:space:]')"
    case "$_bytes" in ''|*[!0-9]*) _bytes=0 ;; esac
    _sha="$(font_file_sha256 "$_src")"
    printf '{"status":"ok","data":{"family":"%s","file":"%s","bytes":%s,"sha256":"%s"}}\n' \
        "$(json_escape "$_family")" "$(json_escape "$(basename "$_src")")" "$_bytes" "$(json_escape "$_sha")"
}

preview_export() {
    _family="$1"
    _dest="$2"
    _weight="${3:-400}"
    case "$_dest" in
        /data/user/0/io.github.shishui611_art.ziyu/cache/*|/data/data/io.github.shishui611_art.ziyu/cache/*|\
        /data/user/0/io.github.shishui611_art.ziyu.debug/cache/*|/data/data/io.github.shishui611_art.ziyu.debug/cache/*) ;;
        *) printf '{"status":"error","message":"预览目标目录不受信任"}\n'; return 1 ;;
    esac
    _src="$(find_preview_source "$_family" "$_weight")"
    [ -f "$_src" ] || { printf '{"status":"error","message":"找不到预览字体"}\n'; return 1; }
    mkdir -p "${_dest%/*}" 2>/dev/null || true
    cp -f "$_src" "$_dest" 2>/dev/null || { printf '{"status":"error","message":"无法导出预览字体"}\n'; return 1; }
    chmod 0644 "$_dest" 2>/dev/null || true
    _sha="$(font_file_sha256 "$_src")"
    printf '{"status":"ok","data":{"path":"%s","source":"%s","sha256":"%s"}}\n' \
        "$(json_escape "$_dest")" "$(json_escape "$(basename "$_src")")" "$(json_escape "$_sha")"
}

weight_axis_info() {
    _family="$1"
    _src="$(find_preview_source "$_family")"
    [ -f "$_src" ] || { printf '{"status":"error","message":"找不到字体轴来源"}\n'; return 1; }
    [ -f "$AXIS_INFO" ] && [ -x "$PYBIN" ] || { printf '{"status":"error","message":"字体轴分析器不可用"}\n'; return 1; }
    export PYTHONHOME="$PYROOT"
    export PYTHONPATH="$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
    export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    "$PYBIN" "$AXIS_INFO" "$_src"
}

case "${1:-status}" in
    app_reads)
        [ -x "$PYBIN" ] && [ -f "$MODDIR/common/app_read_batch.py" ] || exit 127
        export MODDIR MODULE_DIR="$MODDIR" PYTHONHOME="$PYROOT"
        export PYTHONPATH="$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
        export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        "$PYBIN" "$MODDIR/common/app_read_batch.py" "$MODDIR" "${2:-settings}"
        ;;
    action_cancel) MODDIR="$MODDIR" sh "$MODDIR/common/action_control.sh" cancel "${2:-}" "${3:-}" ;;
    action_undo) MODDIR="$MODDIR" sh "$MODDIR/common/action_control.sh" undo ;;
    action_status) MODDIR="$MODDIR" sh "$MODDIR/common/action_control.sh" status ;;
    log_review) MODDIR="$MODDIR" sh "$MODDIR/common/log_review.sh" "${2:-status}" ;;
    diagnostic_bundle)
        # Full (unsanitized but non-secret) module-side bundle, usable even when
        # the in-app sanitized export cannot run its flow.
        [ -f "$MODDIR/common/diagnostic_bundle.sh" ] || { printf '{"ok":false,"error":"bundle-helper-missing"}\n'; exit 4; }
        _db_path=$(MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/diagnostic_bundle.sh" dump "${2:-app-manual}")
        _db_rc=$?
        if [ "$_db_rc" -eq 0 ] && [ -s "$_db_path" ]; then
            _db_public="/sdcard/Ziyu/reports/$(basename "$_db_path")"
            [ -s "$_db_public" ] || _db_public=""
            printf '{"ok":true,"path":"%s","publicPath":"%s"}\n' "$(json_escape "$_db_path")" "$(json_escape "$_db_public")"
        else
            printf '{"ok":false,"error":"bundle-dump-failed"}\n'
        fi
        exit "$_db_rc"
        ;;
    mount_preferences)
        _mp=$(MODDIR="$MODDIR" sh "$MODDIR/common/mount_backend_preferences.sh" "${2:-get}" "${3:-}")
        _mp_rc=$?
        printf '%s\n' "$_mp"
        exit "$_mp_rc"
        ;;
    status) status_json ;;
    fonts)
        manager_ready || exit 1
        if [ "${2:-}" = refresh ]; then sh "$FONT_MANAGER" action list refresh
        else sh "$FONT_MANAGER" action list
        fi
        ;;
    import_file)
        if [ -f "$NATIVE_IMPORT" ]; then
            MODDIR="$MODDIR" sh "$NATIVE_IMPORT" "${2:-}" "${3:-}"
        else
            printf '{"status":"error","message":"原生导入组件不可用"}\n'
        fi
        ;;
    preview_source) preview_source_json "${2:-}" "${3:-400}" ;;
    preview_export) preview_export "${2:-}" "${3:-}" "${4:-400}" ;;
    weight_axis) weight_axis_info "${2:-}" ;;
    source_profile)
        [ -f "$SOURCE_PROFILE" ] || { printf '{"status":"error","message":"源字体 Profile 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" sh "$SOURCE_PROFILE" family "${2:-}"
        ;;
    universal_plan)
        [ -f "$UNIVERSAL_PLAN" ] || { printf '{"status":"error","message":"Universal FontPlan 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" sh "$UNIVERSAL_PLAN" build "${2:-}"
        ;;
    xml_route_plan)
        [ -f "$MINIMAL_XML_ROUTER" ] || { printf '{"status":"error","message":"Minimal XML Router 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" sh "$MINIMAL_XML_ROUTER" build "${2:-}"
        ;;
    font_compile)
        [ -f "$UNIVERSAL_COMPILER" ] || { printf '{"status":"error","message":"Universal Font Compiler 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" sh "$UNIVERSAL_COMPILER" compile "${2:-}"
        ;;
    font_deployment)
        [ -f "$UNIVERSAL_DEPLOYMENT" ] || { printf '{"status":"error","message":"Universal Deployment 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" sh "$UNIVERSAL_DEPLOYMENT" prepare "${2:-}"
        ;;
    font_runtime_verify)
        [ -f "$UNIVERSAL_VERIFY" ] || { printf '{"status":"error","message":"Runtime Verification 组件不可用"}\n'; exit 1; }
        case "${2:-status}" in
            run) MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$UNIVERSAL_VERIFY" run ;;
            schedule) MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$UNIVERSAL_VERIFY" schedule ;;
            *) MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$UNIVERSAL_VERIFY" status ;;
        esac
        ;;
    prewarm)
        if [ -f "$SAFE_SWITCH" ]; then
            MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" \
                sh "$SAFE_SWITCH" action prewarm-start "${2:-}" >/dev/null 2>&1 || true
            printf '{"status":"ok","data":{"font":"%s","scheduled":true}}\n' "$(json_escape "${2:-}")"
        else
            printf '{"status":"error","message":"字体预热组件不可用"}\n'
        fi
        ;;
    validate)
        manager_ready || exit 1
        if [ -f "$SAFE_SWITCH" ] && [ -n "${2:-}" ] && [ "${2:-}" != default ]; then
            MODDIR="$MODDIR" LUOSHU_PUBLIC_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}" \
                sh "$SAFE_SWITCH" action prewarm-start "${2:-}" >/dev/null 2>&1 || true
        fi
        sh "$FONT_MANAGER" action validate "${2:-}"
        ;;
    stock_scan) manager_ready || exit 1; sh "$FONT_MANAGER" action stock_scan ;;
    import_system_fonts)
        MODDIR="$MODDIR" sh "$MODDIR/common/system_font_library.sh"
        ;;
    switch_start) switch_task_ready || exit 1; MODDIR="$MODDIR" sh "$FONT_SWITCH_TASK" start "${2:-default}" ;;
    switch_status) switch_task_ready || exit 1; MODDIR="$MODDIR" sh "$FONT_SWITCH_TASK" status "${2:-}" ;;
    delete) manager_ready || exit 1; sh "$FONT_MANAGER" action delete "${2:-}" ;;
    delete_many) manager_ready || exit 1; shift; sh "$FONT_MANAGER" action delete_many "$@" ;;
    mix_config) mix_ready || exit 1; sh "$MIX_ENGINE" config ;;
    mix_start) mix_ready || exit 1; sh "$MIX_ENGINE" start "${2:-}" "${3:-}" "${4:-}" "${5:-wght=400}" "${6:-wght=400}" "${7:-wght=400}" "${8:-}" "${9:-}" "${10:-}" "${11:-}" ;;
    mix_status) mix_ready || exit 1; sh "$MIX_ENGINE" status "${2:-}" ;;
    temporary_root)
        case "${2:-status}" in
            status)
                _tr_enabled=false
                temporary_root_mode && _tr_enabled=true
                printf '{"status":"ok","data":{"enabled":%s,"rootManager":"%s"}}\n' "$_tr_enabled" "$(json_escape "$(root_manager)")"
                ;;
            enable|disable)
                case "$(root_manager)" in KernelSU|SukiSU\ Ultra) ;; *) printf '{"status":"error","message":"仅支持 KernelSU 系列管理器"}\n'; exit 1 ;; esac
                _tr_enabled=false
                [ "$2" != enable ] || _tr_enabled=true
                _tr_file="$MODDIR/config/temporary-root-mode.conf"
                printf 'enabled=%s\n' "$_tr_enabled" > "${_tr_file}.tmp.$$" &&
                    chmod 0600 "${_tr_file}.tmp.$$" && mv -f "${_tr_file}.tmp.$$" "$_tr_file" || exit 1
                temporary_root_mode && _tr_enabled=true
                printf '{"status":"ok","data":{"enabled":%s}}\n' "$_tr_enabled"
                ;;
            *) printf '{"status":"error","message":"未知临时 Root 设置"}\n'; exit 1 ;;
        esac
        ;;
    soft_reboot)
        exec sh "$MODDIR/common/soft_reboot.sh" "${2:-request}"
        ;;
    reboot)
        if temporary_root_mode; then
            exec sh "$MODDIR/common/soft_reboot.sh" request
        fi
        manager_ready || exit 1
        sh "$FONT_MANAGER" action reboot_device
        ;;
    logs)
        _lines="${2:-160}"
        case "$_lines" in ''|*[!0-9]*) _lines=160 ;; esac
        [ "$_lines" -le 500 ] 2>/dev/null || _lines=500
        tail -n "$_lines" "$MODDIR/logs/fontswitch.log" 2>/dev/null
        ;;
    *) printf '{"status":"error","message":"未知 App 桥命令"}\n' ;;
esac
exit 0
