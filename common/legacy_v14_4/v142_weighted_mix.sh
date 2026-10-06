#!/system/bin/sh
# 洛书 v14.2 RC2：异步真实字重与多轴字体组合桥。
# 任务状态持久化在模块目录，App 或 WebUI 退出后可重新接管。
set +e

MODDIR="${MODDIR:-}"
if [ -z "$MODDIR" ]; then
    if [ -f "${0%/*}/../module.prop" ]; then
        MODDIR="$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)"
    else
        MODDIR="/data/adb/modules/LuoShu"
    fi
fi

CONFIG_DIR="$MODDIR/config"
export LUOSHU_PREPARE_CACHE="$MODDIR/cache/prepared-selected-v1"
CACHE_ROOT="$MODDIR/cache/axes-mix"
USER_FONTS_DIR="${LUOSHU_PUBLIC_DIR:-/sdcard/LuoShu}/fonts"
BASE_ENGINE="$MODDIR/common/font_mix.sh"
INSTANCE_PY="$MODDIR/common/font_instance.py"
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/luoshu-python"
BASE_TASK_FILE="$CONFIG_DIR/mix_task.conf"
TASK_FILE="$CONFIG_DIR/axes_task.conf"
AXES_CONF="$CONFIG_DIR/axes_mix.conf"
MIX_CONF="$CONFIG_DIR/font_mix.conf"
ACTIVE_CONF="$CONFIG_DIR/active_font.conf"
PROGRESS_FILE="$CONFIG_DIR/composite_progress.json"
TEXT_REBOOT_REQUIRED="$CONFIG_DIR/text_reboot_required.conf"
LOCK_FILE="$MODDIR/.font_switch.lock"
WORKER_PID="$CONFIG_DIR/axes_worker.pid"
AUTO_WORKER_PID="$CONFIG_DIR/auto_multiweight_worker.pid"
LOG_FILE="$MODDIR/logs/fontswitch.log"

MODULE_DIR="$MODDIR"
[ -f "$MODDIR/common/util_functions.sh" ] && . "$MODDIR/common/util_functions.sh"
[ -f "$MODDIR/common/font_check.sh" ] && . "$MODDIR/common/font_check.sh"
[ -f "$MODDIR/common/background_task.sh" ] && . "$MODDIR/common/background_task.sh"
[ -f "$MODDIR/common/mix_task_handoff.sh" ] && . "$MODDIR/common/mix_task_handoff.sh"
[ -f "$MODDIR/common/font_switch_lock.sh" ] && . "$MODDIR/common/font_switch_lock.sh"

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\r' '  '
}

read_value() {
    sed -n "s/^${2}=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'
}

clean_spec() {
    printf '%s' "$1" | tr -d '\r\n'
}

clear_worker_pid() {
    _cw_task="${1:-}"
    if type luoshu_clear_task_pid >/dev/null 2>&1; then
        luoshu_clear_task_pid "$WORKER_PID" "$_cw_task"
    else
        rm -f "$WORKER_PID" 2>/dev/null || true
    fi
}

task_worker_alive() {
    _twa_task="$1"
    if type luoshu_task_pid_alive >/dev/null 2>&1; then
        luoshu_task_pid_alive "$WORKER_PID" "$_twa_task" && return 0
        luoshu_task_pid_alive "$AUTO_WORKER_PID" "$_twa_task" && return 0
    fi
    for _twa_file in "$WORKER_PID" "$AUTO_WORKER_PID"; do
        _twa_pid=$(sed -n '1{s/[^0-9].*$//;p;}' "$_twa_file" 2>/dev/null)
        if [ -n "$_twa_pid" ] && kill -0 "$_twa_pid" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

reconcile_task() {
    [ -s "$TASK_FILE" ] || return 0
    _rt_state=$(read_value "$TASK_FILE" state)
    case "$_rt_state" in queued|running) ;; *) return 0 ;; esac
    _rt_task=$(read_value "$TASK_FILE" task)
    [ -n "$_rt_task" ] || return 0
    task_worker_alive "$_rt_task" && return 0
    _rt_started=$(read_value "$TASK_FILE" started)
    _rt_now=$(date +%s 2>/dev/null || echo 0)
    case "$_rt_started:$_rt_now" in *[!0-9:]*|:*) ;; *)
        [ $((_rt_now - _rt_started)) -gt 20 ] 2>/dev/null || return 0
        ;;
    esac
    update_task "$_rt_task" failed '字体组合后台进程已退出，任务已自动释放，请重新应用' 100 '' "$_rt_now"
    clear_worker_pid "$_rt_task"
}

axis_value() {
    _spec="$1"
    _tag="$2"
    _fallback="$3"
    _value=$(printf '%s' "$_spec" | tr ',' '\n' | sed -n "s/^${_tag}=//p" | head -n1)
    case "$_value" in ''|*[!0-9.-]*) _value="$_fallback" ;; esac
    printf '%s' "$_value"
}

safe_weight() {
    _weight=$(axis_value "$1" wght 400)
    _weight=${_weight%%.*}
    case "$_weight" in ''|*[!0-9]*) _weight=400 ;; esac
    [ "$_weight" -ge 1 ] 2>/dev/null || _weight=1
    [ "$_weight" -le 1000 ] 2>/dev/null || _weight=1000
    printf '%s' "$_weight"
}

role_weight() {
    case "$1" in
        thin) echo 100 ;;
        extralight) echo 200 ;;
        light) echo 300 ;;
        regular|normal) echo 400 ;;
        medium) echo 500 ;;
        semibold) echo 600 ;;
        bold) echo 700 ;;
        extrabold) echo 800 ;;
        black|heavy) echo 900 ;;
        variable) echo "$2" ;;
        *) echo 400 ;;
    esac
}

write_task() {
    _wt_task="$1"
    _wt_state="$2"
    _tmp="$TASK_FILE.tmp.$$"
    {
        printf 'task=%s\n' "$1"
        printf 'state=%s\n' "$2"
        printf 'message=%s\n' "$3"
        printf 'cjk=%s\nlatin=%s\ndigit=%s\n' "$4" "$5" "$6"
        printf 'cjkAxes=%s\nlatinAxes=%s\ndigitAxes=%s\n' "$7" "$8" "$9"
        shift 9
        printf 'root=%s\nchildTask=%s\nstarted=%s\nfinished=%s\npercent=%s\n' "$1" "$2" "$3" "$4" "$5"
        if [ "$_wt_state" != cancelled ] && [ "$(read_value "$TASK_FILE" task)" = "$_wt_task" ]; then
            for _wt_field in result generatedFontId generatedFontName previewSource; do
                _wt_value=$(read_value "$TASK_FILE" "$_wt_field")
                [ -z "$_wt_value" ] || printf '%s=%s\n' "$_wt_field" "$_wt_value"
            done
        fi
    } >"$_tmp" 2>/dev/null && mv -f "$_tmp" "$TASK_FILE" 2>/dev/null
    chmod 0644 "$TASK_FILE" 2>/dev/null || true
}

update_task() {
    _wanted="$1"
    _state="$2"
    _message="$3"
    _percent="$4"
    _child="$5"
    _finished="$6"
    [ "$(read_value "$TASK_FILE" task)" = "$_wanted" ] || return 1
    _cjk=$(read_value "$TASK_FILE" cjk)
    _latin=$(read_value "$TASK_FILE" latin)
    _digit=$(read_value "$TASK_FILE" digit)
    _cjk_axes=$(read_value "$TASK_FILE" cjkAxes)
    _latin_axes=$(read_value "$TASK_FILE" latinAxes)
    _digit_axes=$(read_value "$TASK_FILE" digitAxes)
    _root=$(read_value "$TASK_FILE" root)
    _started=$(read_value "$TASK_FILE" started)
    [ -n "$_child" ] || _child=$(read_value "$TASK_FILE" childTask)
    [ -n "$_finished" ] || _finished=$(read_value "$TASK_FILE" finished)
    write_task "$_wanted" "$_state" "$_message" "$_cjk" "$_latin" "$_digit" \
        "$_cjk_axes" "$_latin_axes" "$_digit_axes" "$_root" "$_child" "$_started" "$_finished" "$_percent"
}

find_best_source() {
    _family="$1"
    _target="$2"
    _best=""
    _best_score=99999
    for _font in "$USER_FONTS_DIR"/*.ttf "$USER_FONTS_DIR"/*.otf "$USER_FONTS_DIR"/*.ttc \
                 "$USER_FONTS_DIR"/*.TTF "$USER_FONTS_DIR"/*.OTF "$USER_FONTS_DIR"/*.TTC; do
        [ -f "$_font" ] || continue
        _name=$(basename "$_font")
        _detected=$(detect_font_family "$_name")
        [ "$_detected" = "$_family" ] || continue
        if is_variable_font "$_font" 2>/dev/null; then
            printf '%s\n' "$_font"
            return 0
        fi
        _role=$(detect_font_weight "$_name")
        _number=$(role_weight "$_role" "$_target")
        _score=$((_number - _target))
        [ "$_score" -ge 0 ] 2>/dev/null || _score=$((-_score))
        if [ -z "$_best" ] || [ "$_score" -lt "$_best_score" ] 2>/dev/null; then
            _best="$_font"
            _best_score="$_score"
        fi
    done
    [ -n "$_best" ] || return 1
    printf '%s\n' "$_best"
}

persist_fixed_report() (
    _pfr_source="$1"
    _pfr_dir="$MODDIR/logs/font-diagnostics"
    _pfr_id=$(printf '%s-%s-%s' "${_wanted:-unknown}" "${_role:-unknown}" "${_weight:-0}" | tr -cd 'a-zA-Z0-9_.-')
    mkdir -p "$_pfr_dir" || return 1
    cp "$_pfr_source" "$_pfr_dir/${_pfr_id}.json" || return 1
    _pfr_count=0
    for _pfr_old in $(ls -1t "$_pfr_dir"/*.json 2>/dev/null); do
        _pfr_count=$((_pfr_count + 1))
        [ "$_pfr_count" -le 24 ] || rm -f "$_pfr_old"
    done
)

run_instance() {
    _source="$1"
    _destination="$2"
    _role="$3"
    _axes="$4"
    _weight=$(safe_weight "$_axes")
    _report="${_destination}.json"
    _error="${_destination}.err"
    [ -x "$PYBIN" ] || chmod 0755 "$PYBIN" 2>/dev/null || true
    (
        export LUOSHU_TASK_ID="${_wanted:-unknown}" LUOSHU_FONT_FAMILY="$_family"
        export LUOSHU_GENERATED_WEIGHT="$_weight" LUOSHU_WEIGHT_MODE=fixed
        export PYTHONHOME="$PYROOT"
        # CPython on Android resolves font_instance.py's symlink into the
        # legacy directory; sys.path[0] therefore cannot find runtime helpers.
        export PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
        export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        export TMPDIR="${TMPDIR:-$MODDIR/cache/tmp}"
        mkdir -p "$TMPDIR" 2>/dev/null || true
        "$PYBIN" "$INSTANCE_PY" --input "$_source" --output "$_destination" \
            --role "$_role" --weight "$_weight" --axes "$_axes"
    ) >"$_report" 2>"$_error"
    _code=$?
    if [ "$_code" -ne 0 ] || [ ! -s "$_destination" ]; then
        _message=$(sed -n 's/^.*"message":"\([^"]*\)".*$/\1/p' "$_error" "$_report" 2>/dev/null | tail -n1)
        [ -n "$_message" ] || _message=$(tail -n1 "$_error" 2>/dev/null | tr -d '\r')
        [ -n "$_message" ] || _message="字体实例化失败（代码 $_code）"
        _last_prepare_error="$_message"
        printf '[MIX_ERROR] task=%s stage=font-instance role=%s family=%s effective_weight=%s axes=%s exit_code=%s source=%s\n' \
            "${_wanted:-unknown}" "$_role" "$_family" "$_weight" "$_axes" "$_code" "$_source" >>"$LOG_FILE"
        cat "$_error" >>"$LOG_FILE" 2>/dev/null || true
        persist_fixed_report "$_error" || true
        echo "错误：$_message" >&2
        return 1
    fi
    if grep -q '"variationFallbacks":\[{' "$_report" 2>/dev/null; then
        cat "$_report" >>"$LOG_FILE"
        persist_fixed_report "$_report" || true
        _warning=$(sed -n 's/.*"warning":"\([^"]*\)".*/\1/p' "$_report" | head -n1)
        case "$_role" in cjk) _label=中文;; latin) _label=英文;; *) _label=数字;; esac
        [ -z "$_warning" ] || printf '%s：%s\n' "$_label" "$_warning" >>"$_root/variation-warnings.txt"
    fi
    rm -f "$_error" 2>/dev/null || true
    chmod 0644 "$_destination" "$_report" 2>/dev/null || true
    return 0
}

prepare_slot() {
    _last_prepare_error=''
    _role="$1"
    _family="$2"
    _axes="$3"
    _root="$4"
    _internal="$5"
    _weight=$(safe_weight "$_axes")
    _source=$(find_best_source "$_family" "$_weight")
    printf '[MIX_PREPARE] task=%s stage=prepare-source role=%s family=%s selected_weight=%s mode=fixed axes=%s source=%s\n' \
        "${_wanted:-unknown}" "$_role" "$_family" "$_weight" "$_axes" "$_source" >>"$LOG_FILE"
    [ -f "$_source" ] || { _last_prepare_error="找不到字体族 $_family"; echo "错误：$_last_prepare_error" >&2; return 1; }
    font_validate "$_source" text || { _last_prepare_error="字体 $_family 无效：$FONT_CHECK_ERROR"; echo "错误：$_last_prepare_error" >&2; return 1; }
    _destination="$_root/fonts/${_internal}-Regular.ttf"
    mkdir -p "${_destination%/*}" 2>/dev/null || return 1
    if [ "$FONT_CHECK_VARIABLE" = true ] || [ "$FONT_CHECK_FORMAT" = TTC ] || [ "$_role" != cjk ]; then
        run_instance "$_source" "$_destination" "$_role" "$_axes" || return 1
    else
        cp -f "$_source" "$_destination" 2>/dev/null || return 1
        chmod 0644 "$_destination" 2>/dev/null || true
    fi
    # Check the exact selected instance, including TTC face and static family
    # weight; a different valid member of the family cannot satisfy this slot.
    if [ -f "$MODDIR/common/font_role_check.py" ]; then
        _selected_check=$(PYTHONHOME="$PYROOT" \
            PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            "$PYBIN" "$MODDIR/common/font_role_check.py" "$_destination" "$_role" 2>&1)
        [ "$?" -eq 0 ] || { _last_prepare_error="所选字体实例缺少必要字形：$_selected_check"; return 1; }
    fi
    [ -s "$_destination" ]
}

start_prepare_job() {
    _spj_role="$1"
    _spj_family="$2"
    _spj_axes="$3"
    _spj_root="$4"
    _spj_internal="$5"
    _spj_status="$_spj_root/prepare-${_spj_role}.status"
    rm -f "$_spj_status" "$_spj_status.tmp."* 2>/dev/null || true
    (
        export ZIYU_DEFER_PREPARE_CACHE_CLEANUP=1
        if prepare_slot "$_spj_role" "$_spj_family" "$_spj_axes" "$_spj_root" "$_spj_internal"; then
            _spj_state=success
            _spj_message=''
            _spj_code=0
        else
            _spj_code=$?
            _spj_state=failed
            _spj_message="${_last_prepare_error:-字体准备失败（代码 $_spj_code）}"
        fi
        _spj_tmp="$_spj_status.tmp.$$"
        {
            printf 'state=%s\nmessage=%s\nexitCode=%s\n' "$_spj_state" "$_spj_message" "$_spj_code"
        } >"$_spj_tmp" 2>/dev/null && mv -f "$_spj_tmp" "$_spj_status" 2>/dev/null
        rm -f "$_spj_tmp" 2>/dev/null || true
        [ "$_spj_state" = success ]
    ) >"$_spj_root/prepare-${_spj_role}.log" 2>&1 &
    PREPARE_JOB_PID=$!
}

prune_prepare_cache() {
    [ -x "$PYBIN" ] || return 0
    PYTHONHOME="$PYROOT" \
        PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages" \
        LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$PYBIN" "$MODDIR/common/font_prepare_cache.py" --prune "$CACHE_ROOT" \
        >/dev/null 2>>"$LOG_FILE" || printf '[MIX_PREPARE] cache cleanup skipped after parallel preparation\n' >>"$LOG_FILE"
}

prepare_jobs_finished() {
    _pjf_root="$1"
    _pjf_completed=0
    _pjf_failed_role=''
    _pjf_failed_message=''
    for _pjf_role in cjk latin digit; do
        _pjf_status="$_pjf_root/prepare-${_pjf_role}.status"
        _pjf_state=$(read_value "$_pjf_status" state)
        case "$_pjf_state" in
            success) _pjf_completed=$((_pjf_completed + 1)) ;;
            failed)
                if [ -z "$_pjf_failed_role" ]; then
                    _pjf_failed_role="$_pjf_role"
                    _pjf_failed_message=$(read_value "$_pjf_status" message)
                fi
                _pjf_completed=$((_pjf_completed + 1))
                ;;
        esac
    done
    PREPARE_JOBS_COMPLETED="$_pjf_completed"
    PREPARE_JOBS_FAILED_ROLE="$_pjf_failed_role"
    PREPARE_JOBS_FAILED_MESSAGE="$_pjf_failed_message"
}

parallel_prepare_slots() {
    _pps_wanted="$1"
    _pps_cjk="$2"; _pps_latin="$3"; _pps_digit="$4"
    _pps_cjk_axes="$5"; _pps_latin_axes="$6"; _pps_digit_axes="$7"
    _pps_root="$8"

    update_task "$_pps_wanted" running '正在并行准备中文、英文和数字字体' 4 '' ''
    start_prepare_job cjk "$_pps_cjk" "$_pps_cjk_axes" "$_pps_root" LuoShuMixCJK
    _pps_cjk_pid="$PREPARE_JOB_PID"
    start_prepare_job latin "$_pps_latin" "$_pps_latin_axes" "$_pps_root" LuoShuMixLatin
    _pps_latin_pid="$PREPARE_JOB_PID"
    start_prepare_job digit "$_pps_digit" "$_pps_digit_axes" "$_pps_root" LuoShuMixDigit
    _pps_digit_pid="$PREPARE_JOB_PID"

    _pps_last_completed=-1
    while :; do
        prepare_jobs_finished "$_pps_root"
        if [ "$PREPARE_JOBS_COMPLETED" -ne "$_pps_last_completed" ]; then
            _pps_last_completed="$PREPARE_JOBS_COMPLETED"
            case "$_pps_last_completed" in
                0) _pps_percent=4 ;;
                1) _pps_percent=14 ;;
                2) _pps_percent=24 ;;
                *) _pps_percent=30 ;;
            esac
            update_task "$_pps_wanted" running "字体准备中（$_pps_last_completed/3）" "$_pps_percent" '' ''
        fi
        [ "$PREPARE_JOBS_COMPLETED" -eq 3 ] && break
        sleep 1
    done

    wait "$_pps_cjk_pid" 2>/dev/null; _pps_cjk_rc=$?
    wait "$_pps_latin_pid" 2>/dev/null; _pps_latin_rc=$?
    wait "$_pps_digit_pid" 2>/dev/null; _pps_digit_rc=$?
    prepare_jobs_finished "$_pps_root"
    for _pps_role in cjk latin digit; do
        [ ! -f "$_pps_root/prepare-${_pps_role}.log" ] || cat "$_pps_root/prepare-${_pps_role}.log" >>"$LOG_FILE" 2>/dev/null
    done
    prune_prepare_cache
    if [ "$PREPARE_JOBS_COMPLETED" -ne 3 ] || [ "$_pps_cjk_rc" -ne 0 ] || \
       [ "$_pps_latin_rc" -ne 0 ] || [ "$_pps_digit_rc" -ne 0 ] || \
       [ -n "$PREPARE_JOBS_FAILED_ROLE" ]; then
        [ -n "$PREPARE_JOBS_FAILED_ROLE" ] || PREPARE_JOBS_FAILED_ROLE=unknown
        [ -n "$PREPARE_JOBS_FAILED_MESSAGE" ] || PREPARE_JOBS_FAILED_MESSAGE='字体准备进程异常退出，请查看详细日志'
        return 1
    fi
    return 0
}

rewrite_public_config() {
    [ -s "$TASK_FILE" ] || return 0
    [ "$(read_value "$TASK_FILE" state)" = success ] || return 0
    _cjk=$(read_value "$TASK_FILE" cjk)
    _latin=$(read_value "$TASK_FILE" latin)
    _digit=$(read_value "$TASK_FILE" digit)
    _cjk_axes=$(read_value "$TASK_FILE" cjkAxes)
    _latin_axes=$(read_value "$TASK_FILE" latinAxes)
    _digit_axes=$(read_value "$TASK_FILE" digitAxes)
    _tmp="$MIX_CONF.axes.$$"
    {
        printf 'cjk=%s\nlatin=%s\ndigit=%s\n' "$_cjk" "$_latin" "$_digit"
        printf 'cjkWeight=%s\nlatinWeight=%s\ndigitWeight=%s\n' \
            "$(safe_weight "$_cjk_axes")" "$(safe_weight "$_latin_axes")" "$(safe_weight "$_digit_axes")"
        printf 'cjkAxes=%s\nlatinAxes=%s\ndigitAxes=%s\n' "$_cjk_axes" "$_latin_axes" "$_digit_axes"
        printf 'cjkMode=fixed\nlatinMode=fixed\ndigitMode=fixed\n'
        [ ! -f "$MIX_CONF" ] || grep -v -E '^(cjk|latin|digit|cjkWeight|latinWeight|digitWeight|cjkAxes|latinAxes|digitAxes|cjkMode|latinMode|digitMode)=' "$MIX_CONF" 2>/dev/null
    } >"$_tmp" 2>/dev/null && mv -f "$_tmp" "$MIX_CONF" 2>/dev/null
    cp -f "$MIX_CONF" "$AXES_CONF" 2>/dev/null || true
    chmod 0644 "$MIX_CONF" "$AXES_CONF" 2>/dev/null || true
}

mix_cancel_checkpoint() {
    if [ "${MIX_LOCAL_CANCEL:-false}" = true ] || \
       { [ -n "${_wanted:-}" ] && [ "$(read_value "${LUOSHU_MIX_CANCEL_FILE:-$CONFIG_DIR/mix_task.cancel}" task)" = "$_wanted" ]; }; then
        case "${_root:-}" in "$CACHE_ROOT"/axes-*) rm -rf "$_root" 2>/dev/null || true ;; esac
        _mcc_module="${LUOSHU_REAL_MODDIR:-$MODDIR}"
        _mcc_state="$_mcc_module/config/mix-stage-next.conf"
        if [ -n "${LUOSHU_MIX_REQUEST_ID:-}" ] && \
           [ "$(read_value "$_mcc_state" requestId)" = "$LUOSHU_MIX_REQUEST_ID" ]; then
            rm -rf "$_mcc_module/.luoshu-mix-stage" 2>/dev/null || true
            rm -f "$_mcc_state" 2>/dev/null || true
        fi
        update_task "$_wanted" cancelled '组合任务已取消' 100 '' "$(date +%s)"
        clear_worker_pid "$_wanted"
        exit 0
    fi
}

worker() {
    trap '' HUP
    trap 'MIX_LOCAL_CANCEL=true' TERM INT
    _wanted="$1"
    export LUOSHU_MIX_PARENT_TASK="$_wanted"
    export LUOSHU_MIX_CANCEL_FILE="${LUOSHU_REAL_MODDIR:-$MODDIR}/config/mix_task.cancel"
    [ "$(read_value "$TASK_FILE" task)" = "$_wanted" ] || exit 0
    _cjk=$(read_value "$TASK_FILE" cjk)
    _latin=$(read_value "$TASK_FILE" latin)
    _digit=$(read_value "$TASK_FILE" digit)
    _cjk_axes=$(read_value "$TASK_FILE" cjkAxes)
    _latin_axes=$(read_value "$TASK_FILE" latinAxes)
    _digit_axes=$(read_value "$TASK_FILE" digitAxes)
    _root=$(read_value "$TASK_FILE" root)

    mix_cancel_checkpoint
    parallel_prepare_slots "$_wanted" "$_cjk" "$_latin" "$_digit" \
        "$_cjk_axes" "$_latin_axes" "$_digit_axes" "$_root" || {
        mix_cancel_checkpoint
        case "$PREPARE_JOBS_FAILED_ROLE" in
            cjk) _failed_label=中文 ;;
            latin) _failed_label=英文 ;;
            digit) _failed_label=数字 ;;
            *) _failed_label=字体 ;;
        esac
        update_task "$_wanted" failed "${_failed_label}字体准备失败${PREPARE_JOBS_FAILED_MESSAGE:+：$PREPARE_JOBS_FAILED_MESSAGE}" 100 '' "$(date +%s)"
        rm -rf "$_root"; clear_worker_pid "$_wanted"; exit 1
    }

    mix_cancel_checkpoint
    update_task "$_wanted" running '正在启动完整复合字体引擎' 34 '' ''
    _previous_child=$(read_value "$BASE_TASK_FILE" task)
    _response_file="$_root/base-engine-start.json"
    : >"$_response_file" 2>/dev/null || true
    (
        trap '' HUP
        LUOSHU_PUBLIC_DIR="$_root" MODDIR="$MODDIR" sh "$BASE_ENGINE" start \
            LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit >"$_response_file" 2>&1
    ) &
    _starter_pid=$!
    _child=''
    _start_loops=0
    while [ "$_start_loops" -lt 20 ]; do
        if type luoshu_resolve_nested_mix_task >/dev/null 2>&1; then
            _child=$(luoshu_resolve_nested_mix_task "$_response_file" "$BASE_TASK_FILE" "$_previous_child" \
                LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit 2>/dev/null)
        else
            _child=$(sed -n 's/^.*"task":"\([^"]*\)".*$/\1/p' "$_response_file" 2>/dev/null | tail -n1)
        fi
        [ -z "$_child" ] || break
        kill -0 "$_starter_pid" 2>/dev/null || break
        sleep 1
        _start_loops=$((_start_loops + 1))
    done
    if [ -z "$_child" ] && type luoshu_resolve_nested_mix_task >/dev/null 2>&1; then
        _child=$(luoshu_resolve_nested_mix_task "$_response_file" "$BASE_TASK_FILE" "$_previous_child" \
            LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit 2>/dev/null)
    fi
    if [ -z "$_child" ]; then
        kill "$_starter_pid" 2>/dev/null || true
        if type luoshu_mix_task_message_from_response >/dev/null 2>&1; then
            _message=$(luoshu_mix_task_message_from_response "$_response_file" 2>/dev/null)
        else
            _message=$(sed -n 's/^.*"message":"\([^"]*\)".*$/\1/p' "$_response_file" 2>/dev/null | tail -n1)
        fi
        [ -n "$_message" ] || _message='完整复合字体子任务在 20 秒内未登记，已停止等待'
        update_task "$_wanted" failed "$_message" 100 '' "$(date +%s)"
        rm -rf "$_root"; clear_worker_pid "$_wanted"; exit 1
    fi
    rm -f "$_response_file" 2>/dev/null || true

    update_task "$_wanted" running '完整复合字体正在后台生成' 36 "$_child" ''
    _loops=0
    while [ "$_loops" -lt 360 ]; do
        _base_task=$(read_value "$BASE_TASK_FILE" task)
        _base_state=$(read_value "$BASE_TASK_FILE" state)
        if [ "$_base_task" = "$_child" ]; then
            _base_message=$(read_value "$BASE_TASK_FILE" message)
            _base_percent=0
            if [ -s "$PROGRESS_FILE" ]; then
                _base_percent=$(sed -n 's/^.*"percent":\([0-9][0-9]*\).*$/\1/p' "$PROGRESS_FILE" 2>/dev/null | head -n1)
            fi
            case "$_base_percent" in ''|*[!0-9]*) _base_percent=0 ;; esac
            _mapped=$((36 + (_base_percent * 64 / 100)))
            [ "$_mapped" -le 99 ] || _mapped=99
            [ -n "$_base_message" ] || _base_message='完整复合字体正在后台生成'
            case "$_base_state" in
                success)
                    mix_cancel_checkpoint
                    update_task "$_wanted" running '正在保存组合到字体库' 99 "$_child" ''
                    if ! MODDIR="${LUOSHU_REAL_MODDIR:-$MODDIR}" sh "${LUOSHU_REAL_MODDIR:-$MODDIR}/common/legacy_v14_4/mix_router.sh" finalize >>"$LOG_FILE" 2>&1; then
                        mix_cancel_checkpoint
                        update_task "$_wanted" failed '保存组合到字体库失败，请查看日志' 100 "$_child" "$(date +%s)"
                        rm -rf "$_root"; clear_worker_pid "$_wanted"; exit 1
                    fi
                    _base_message='字体组合已保存，请预览后确认应用'
                    if [ -s "$_root/variation-warnings.txt" ]; then
                        _warning_summary=$(head -n3 "$_root/variation-warnings.txt" | awk '{printf "%s%s", (NR == 1 ? "" : "；"), $0}')
                        _base_message="$_base_message；兼容提醒：$_warning_summary。其余字形已按所选字重正常处理，完整详情见日志"
                    fi
                    update_task "$_wanted" success "$_base_message" 100 "$_child" "$(date +%s)"
                    # Generation records selections; applying uses the library id.
                    rm -rf "$_root"; clear_worker_pid "$_wanted"; exit 0
                    ;;
                failed)
                    mix_cancel_checkpoint
                    update_task "$_wanted" failed "$_base_message" 100 "$_child" "$(date +%s)"
                    rm -rf "$_root"; clear_worker_pid "$_wanted"; exit 1
                    ;;
                *) update_task "$_wanted" running "$_base_message" "$_mapped" "$_child" '' ;;
            esac
        fi
        sleep 2
        _loops=$((_loops + 1))
    done

    update_task "$_wanted" failed '完整复合字体生成超时' 100 "$_child" "$(date +%s)"
    rm -rf "$_root"; clear_worker_pid "$_wanted"
    exit 1
}

status_json() {
    reconcile_task
    _wanted="$1"
    [ -s "$TASK_FILE" ] || { printf '{"status":"error","message":"暂无字体组合任务"}\n'; return; }
    _task=$(read_value "$TASK_FILE" task)
    [ -z "$_wanted" ] || [ "$_wanted" = "$_task" ] || {
        printf '{"status":"error","message":"任务不存在或已被新任务替换"}\n'; return
    }
    _state=$(read_value "$TASK_FILE" state)
    _message=$(read_value "$TASK_FILE" message)
    _cjk=$(read_value "$TASK_FILE" cjk)
    _latin=$(read_value "$TASK_FILE" latin)
    _digit=$(read_value "$TASK_FILE" digit)
    _cjk_axes=$(read_value "$TASK_FILE" cjkAxes)
    _latin_axes=$(read_value "$TASK_FILE" latinAxes)
    _digit_axes=$(read_value "$TASK_FILE" digitAxes)
    _started=$(read_value "$TASK_FILE" started)
    _finished=$(read_value "$TASK_FILE" finished)
    _percent=$(read_value "$TASK_FILE" percent)
    printf '{"status":"ok","data":{"task":"%s","state":"%s","message":"%s","cjk":"%s","latin":"%s","digit":"%s","cjkWeight":%s,"latinWeight":%s,"digitWeight":%s,"cjkAxes":"%s","latinAxes":"%s","digitAxes":"%s","started":%s,"finished":%s,"progress":{"message":"%s","percent":%s}}}\n' \
        "$(json_escape "$_task")" "$(json_escape "$_state")" "$(json_escape "$_message")" \
        "$(json_escape "$_cjk")" "$(json_escape "$_latin")" "$(json_escape "$_digit")" \
        "$(safe_weight "$_cjk_axes")" "$(safe_weight "$_latin_axes")" "$(safe_weight "$_digit_axes")" \
        "$(json_escape "$_cjk_axes")" "$(json_escape "$_latin_axes")" "$(json_escape "$_digit_axes")" \
        "${_started:-0}" "${_finished:-0}" "$(json_escape "$_message")" "${_percent:-0}"
}

config_json() {
    rewrite_public_config
    _source="$AXES_CONF"
    [ -s "$_source" ] || _source="$MIX_CONF"
    _cjk=$(read_value "$_source" cjk)
    _latin=$(read_value "$_source" latin)
    _digit=$(read_value "$_source" digit)
    _cjk_axes=$(read_value "$_source" cjkAxes)
    _latin_axes=$(read_value "$_source" latinAxes)
    _digit_axes=$(read_value "$_source" digitAxes)
    [ -n "$_cjk_axes" ] || _cjk_axes="wght=$(read_value "$_source" cjkWeight)"
    [ -n "$_latin_axes" ] || _latin_axes="wght=$(read_value "$_source" latinWeight)"
    [ -n "$_digit_axes" ] || _digit_axes="wght=$(read_value "$_source" digitWeight)"
    _active=$(head -n1 "$ACTIVE_CONF" 2>/dev/null | tr -d '\r\n')
    _enabled=false
    [ "$_active" = mix ] && _enabled=true
    printf '{"status":"ok","data":{"enabled":%s,"cjk":"%s","latin":"%s","digit":"%s","cjkWeight":%s,"latinWeight":%s,"digitWeight":%s,"cjkAxes":"%s","latinAxes":"%s","digitAxes":"%s"}}\n' \
        "$_enabled" "$(json_escape "$_cjk")" "$(json_escape "$_latin")" "$(json_escape "$_digit")" \
        "$(safe_weight "$_cjk_axes")" "$(safe_weight "$_latin_axes")" "$(safe_weight "$_digit_axes")" \
        "$(json_escape "$_cjk_axes")" "$(json_escape "$_latin_axes")" "$(json_escape "$_digit_axes")"
}

start_mix() {
    _cjk="$1"
    _latin="$2"
    _digit="$3"
    _cjk_axes=$(clean_spec "$4")
    _latin_axes=$(clean_spec "$5")
    _digit_axes=$(clean_spec "$6")
    [ -n "$_cjk" ] && [ -n "$_latin" ] && [ -n "$_digit" ] || {
        printf '{"status":"error","message":"请选择中文、英文和数字字体"}\n'; return
    }
    [ "${LUOSHU_MIX_PREPARE_ONLY:-false}" = true ] || [ ! -f "$TEXT_REBOOT_REQUIRED" ] || {
        printf '{"status":"error","message":"本次开机已更改文字字体，请先重启手机"}\n'; return
    }
    if [ -s "$WORKER_PID" ]; then
        _old=$(cat "$WORKER_PID" 2>/dev/null)
        [ -z "$_old" ] || ! kill -0 "$_old" 2>/dev/null || {
            printf '{"status":"error","message":"已有字体组合任务正在运行"}\n'; return
        }
    fi
    if type luoshu_font_lock_busy >/dev/null 2>&1; then
        if luoshu_font_lock_busy "$LOCK_FILE"; then
            printf '{"status":"error","message":"字体正在切换中"}\n'; return
        fi
        luoshu_font_lock_reap_stale "$LOCK_FILE" >/dev/null 2>&1 || true
    else
        [ ! -e "$LOCK_FILE" ] || { printf '{"status":"error","message":"字体正在切换中"}\n'; return; }
    fi
    [ -n "$_cjk_axes" ] || _cjk_axes='wght=400'
    [ -n "$_latin_axes" ] || _latin_axes='wght=400'
    [ -n "$_digit_axes" ] || _digit_axes='wght=400'
    mkdir -p "$CONFIG_DIR" "$CACHE_ROOT" "$MODDIR/cache/tmp" "$MODDIR/logs" 2>/dev/null || {
        printf '{"status":"error","message":"无法创建字体组合缓存目录"}\n'; return
    }
    _request="axes-$(date +%s)-$$"
    _root="$CACHE_ROOT/$_request"
    mkdir -p "$_root/fonts" 2>/dev/null || {
        printf '{"status":"error","message":"无法创建字体暂存目录"}\n'; return
    }
    write_task "$_request" queued '任务已进入后台队列' "$_cjk" "$_latin" "$_digit" \
        "$_cjk_axes" "$_latin_axes" "$_digit_axes" "$_root" '' "$(date +%s)" '' 1
    if type luoshu_start_detached >/dev/null 2>&1; then
        luoshu_start_detached "$WORKER_PID" "$_request" "$LOG_FILE" sh "$0" worker "$_request" || {
            update_task "$_request" failed '无法启动独立后台任务' 100 '' "$(date +%s)"
            printf '{"status":"error","message":"无法启动独立后台任务"}\n'
            return
        }
    else
        ( trap '' HUP; MODDIR="$MODDIR" sh "$0" worker "$_request" ) </dev/null >>"$LOG_FILE" 2>&1 &
        printf '%s\n' "$!" >"$WORKER_PID" 2>/dev/null || true
    fi
    printf '{"status":"ok","data":{"task":"%s"}}\n' "$(json_escape "$_request")"
}

recover_task() {
    MODDIR="$MODDIR" sh "$BASE_ENGINE" recover >/dev/null 2>&1 || true
    if [ -s "$TASK_FILE" ]; then
        _state=$(read_value "$TASK_FILE" state)
        _task=$(read_value "$TASK_FILE" task)
        _root=$(read_value "$TASK_FILE" root)
        case "$_state" in
            queued|running) update_task "$_task" failed '上次字体组合任务被开机恢复中止' 100 '' "$(date +%s)" ;;
        esac
        [ -z "$_root" ] || rm -rf "$_root" 2>/dev/null || true
    fi
    clear_worker_pid "${_task:-}"
    printf '{"status":"ok"}\n'
}

case "${1:-config}" in
    start) start_mix "$2" "$3" "$4" "${5:-wght=400}" "${6:-wght=400}" "${7:-wght=400}" ;;
    status) status_json "${2:-}" ;;
    config) config_json ;;
    worker) worker "$2" ;;
    recover) recover_task ;;
    *) printf '{"status":"error","message":"未知多轴组合命令"}\n' ;;
esac
exit 0
