#!/system/bin/sh
# 字域 v2.0.0 原生 App 核心桥：状态、字体库、导入、预览、切换与复合任务接口。
set +e

MODDIR="${MODDIR:-}"
if [ -z "$MODDIR" ]; then
    if [ -f "${0%/*}/../module.prop" ]; then
        MODDIR="$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)"
    else
        MODDIR="/data/adb/modules/ziyu"
    fi
fi
export MODDIR
if [ "${1:-}" = app_reads ]; then
    [ "$(id -u 2>/dev/null)" = 0 ] || { printf '{"status":"error","message":"Root 尚未授权"}\n'; exit 1; }
    [ "$#" -le 2 ] || exit 2
    case "${2:-settings}" in settings|logs) ;; *) exit 2 ;; esac
    PYROOT="$MODDIR/common/python"
    PYBIN="$PYROOT/bin/ziyu-python"
    [ -x "$PYBIN" ] && [ -f "$MODDIR/common/app_read_batch.py" ] || exit 127
    export MODULE_DIR="$MODDIR" PYTHONHOME="$PYROOT"
    export PYTHONPATH="$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
    export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    exec "$PYBIN" "$MODDIR/common/app_read_batch.py" "$MODDIR" "${2:-settings}"
fi
if [ "${1:-}" = app_coverage ]; then
    [ "$(id -u 2>/dev/null)" = 0 ] || { printf '{"status":"error","message":"Root 尚未授权"}\n'; exit 1; }
    PYROOT="$MODDIR/common/python"
    PYBIN="$PYROOT/bin/ziyu-python"
    FONT_COVERAGE_LOCATOR="$MODDIR/common/font_coverage_locator.py"
    [ -x "$PYBIN" ] && [ -f "$FONT_COVERAGE_LOCATOR" ] || { printf '{"status":"error","message":"应用检查组件不可用"}\n'; exit 1; }
    case "${2:-}" in
        running|scan)
            [ "$#" -eq 2 ] || { printf '{"status":"error","message":"诊断参数无效"}\n'; exit 1; }
            ;;
        locate)
            [ "$#" -eq 3 ] || { printf '{"status":"error","message":"诊断参数无效"}\n'; exit 1; }
            ;;
        *) printf '{"status":"error","message":"未知应用检查命令"}\n'; exit 1 ;;
    esac
    export MODDIR MODULE_DIR="$MODDIR" PYTHONHOME="$PYROOT"
    export PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
    export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    shift
    exec "$PYBIN" "$FONT_COVERAGE_LOCATOR" "$@"
fi
if [ "${1:-status}" = status ]; then
    [ "$#" -le 1 ] || exit 2
    exec sh "$MODDIR/common/app_status.sh" json
fi
if [ "${1:-}" = current_font_preview ]; then
    [ "$#" -eq 3 ] || exit 2
    exec sh "$MODDIR/common/current_font_preview.sh" "$2" "$3"
fi
if [ "${1:-}" = diagnostic_capture ]; then
    shift
    exec sh "$MODDIR/common/diagnostic_capture.sh" "$@"
fi
if [ "${1:-}" = slot_stats ]; then
    . "$MODDIR/common/slot_coverage.sh" || exit 1
    ziyu_slot_stats "$MODDIR"
    exit $?
fi
if [ "${1:-}" = import_limit ]; then
    [ -f "$MODDIR/common/import_limit.sh" ] || { printf 'max_mb=256\n'; exit 1; }
    shift
    [ "$#" -gt 0 ] || set -- get
    exec sh "$MODDIR/common/import_limit.sh" "$@"
fi
if [ "${1:-}" = soft_reboot ]; then
    [ -f "$MODDIR/common/soft_reboot.sh" ] || { printf '{"status":"error","message":"软重启组件不可用"}\n'; exit 1; }
    exec sh "$MODDIR/common/soft_reboot.sh" "${2:-request}"
fi
# 保留锁屏时钟字体：默认开启（不替换厂商时钟/数字字体）。
if [ "${1:-}" = keep_lockscreen ]; then
    _kl_conf="$MODDIR/config/tools/keep-lockscreen.conf"
    _kl_value=1
    [ -f "$_kl_conf" ] && _kl_value=$(sed -n 's/^enabled=//p' "$_kl_conf" 2>/dev/null | head -n1 | tr -d ' \r')
    case "${2:-get}" in
        set)
            case "${3:-}" in off|0|false) _kl_value=0 ;; *) _kl_value=1 ;; esac
            mkdir -p "$MODDIR/config/tools" 2>/dev/null || exit 1
            printf 'enabled=%s\n' "$_kl_value" > "$_kl_conf.tmp.$$" &&
                mv -f "$_kl_conf.tmp.$$" "$_kl_conf" || exit 1
            printf '[%s] keep_lockscreen=%s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_kl_value" \
                >> "$MODDIR/logs/slot-coverage.log" 2>/dev/null
            ;;
    esac
    printf 'enabled=%s\n' "${_kl_value:-1}"
    exit 0
fi
# 主题商店字体：把当前字体写进 /data 上的主题字体文件（默认关闭）。
if [ "${1:-}" = theme_store ]; then
    [ -f "$MODDIR/common/theme_store.sh" ] || { printf '{"status":"error","message":"主题字体处理组件不可用"}\n'; exit 1; }
    exec sh "$MODDIR/common/theme_store.sh" "${2:-status}" "${3:-}"
fi
if [ "${1:-}" = temporary_root ]; then
    [ -f "$MODDIR/common/temporary_root_mode.sh" ] && . "$MODDIR/common/temporary_root_mode.sh"
    case "${2:-get}" in
        set)
            _tr_want=false
            case "${3:-}" in true|1|on) _tr_want=true ;; esac
            mkdir -p "$MODDIR/config/tools" 2>/dev/null || exit 1
            _tr_conf="$MODDIR/config/tools/temporary-root-mode.conf"
            printf 'enabled=%s\n' "$_tr_want" > "$_tr_conf.tmp.$$" &&
                mv -f "$_tr_conf.tmp.$$" "$_tr_conf" || exit 1
            ;;
    esac
    if type ziyu_temporary_root_mode >/dev/null 2>&1 && ziyu_temporary_root_mode "$MODDIR"; then
        printf 'enabled=true\n'
    else
        printf 'enabled=false\n'
    fi
    exit 0
fi
if [ "${1:-}" = slot_lang ]; then
    mkdir -p "$MODDIR/config" 2>/dev/null || true
    _sl_action="${2:-get}"
    _sl_conf="$MODDIR/config/tools/slot-coverage-lang.conf"
    [ -s "$_sl_conf" ] || _sl_conf="$MODDIR/config/slot_coverage_lang.conf"
    _sl_value=0
    [ -f "$_sl_conf" ] && _sl_value=$(sed -n 's/^enabled=//p' "$_sl_conf" 2>/dev/null | head -n1 | tr -d ' \r')
    case "$_sl_action" in
        set)
            case "${3:-}" in on|1|true) _sl_value=1 ;; *) _sl_value=0 ;; esac
            printf 'enabled=%s\n' "$_sl_value" > "$_sl_conf"
            ;;
    esac
    printf 'enabled=%s\n' "${_sl_value:-0}"
    exit 0
fi
if [ "${1:-}" = import_preview ]; then
    [ "$#" -eq 2 ] || exit 2
    exec sh "$MODDIR/common/import_preview.sh" "$2"
fi
if [ "${1:-}" = slot_rules ]; then
    if [ "${2:-}" = save ] && [ "${ZIYU_STORAGE_LOCKED:-0}" != 1 ]; then
        exec sh "$MODDIR/common/user_storage_dispatch.sh" "$MODDIR" "$@"
    fi
    shift
    exec sh "$MODDIR/common/font_slot_overrides.sh" "$@"
fi
if [ "${1:-}" = handshake ]; then
    shift
    exec sh "$MODDIR/common/app_handshake.sh" "$@"
fi
if [ "${1:-}" = rom_check ]; then
    shift
    exec sh "$MODDIR/common/app_rom_monitor.sh" "${1:-check}"
fi
if [ "${1:-}" = boot_safety ]; then
    [ "$#" -eq 2 ] || { printf 'status=error\nreason=invalid-arguments\n'; exit 2; }
    case "$2" in status|recover|rescan) ;; *) printf 'status=error\nreason=invalid-action\n'; exit 2 ;; esac
    [ -f "$MODDIR/common/boot_ota_guard.sh" ] || { printf 'status=error\nreason=helper-missing\n'; exit 1; }
    exec sh "$MODDIR/common/boot_ota_guard.sh" "$2" "$MODDIR"
fi
# Display configuration reads do not need mount discovery, font engines or
# user-directory initialization. Keep the cached path independent and short.
if [ "${1:-}" = font_clipping ]; then
    shift
    exec sh "$MODDIR/common/font_clipping_compat.sh" "$@"
fi
if [ "${1:-}" = display_typography ]; then
    shift
    exec sh "$MODDIR/common/display_typography.sh" "$@"
fi
if [ "${1:-}" = display_config_storage ]; then
    shift
    exec sh "$MODDIR/common/display_typography.sh" storage "$@"
fi
[ -f "$MODDIR/common/mount_backend_details.sh" ] && . "$MODDIR/common/mount_backend_details.sh"
[ -f "$MODDIR/common/root_manager_detection.sh" ] && . "$MODDIR/common/root_manager_detection.sh"
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
FONT_COVERAGE_LOCATOR="$MODDIR/common/font_coverage_locator.py"
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/ziyu-python"
[ ! -f "${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}/common/user_storage.sh" ] || {
    . "${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}/common/user_storage.sh"
    ziyu_user_storage_environment "${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
}
USER_FONTS_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}/fonts"
AXES_TASK_FILE="$MODDIR/config/axes_task.conf"
SWITCH_TASK_FILE="$MODDIR/config/switch_task.conf"
TEXT_REBOOT_REQUIRED="$MODDIR/config/text_reboot_required.conf"
[ -f "$MODDIR/common/util_functions.sh" ] && . "$MODDIR/common/util_functions.sh"
[ -f "$MODDIR/common/mount_compat.sh" ] && . "$MODDIR/common/mount_compat.sh"
[ -f "$MODDIR/common/font_boot_state.sh" ] && . "$MODDIR/common/font_boot_state.sh"
[ -f "$MODDIR/common/font_live_state.sh" ] && . "$MODDIR/common/font_live_state.sh"

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
    "$PYBIN" "$AXIS_INFO" "$_src" --cache "$MODDIR/config/font-axis-cache"
}

# Share the migration lock for mutations; children inherit it until completion.
case "${1:-status}" in
    switch_start|mix_start|import_file|delete|delete_many|import_system_fonts|action_undo|storage)
        if [ "${ZIYU_STORAGE_LOCKED:-0}" != 1 ]; then
            exec sh "$MODDIR/common/user_storage_dispatch.sh" "$MODDIR" "$@"
        fi
        ;;
esac
case "${1:-status}" in
    storage)
        ziyu_user_storage_command "$MODDIR" "${2:-status}" "${3:-}"
        ;;
    display_typography)
        shift
        exec sh "$MODDIR/common/display_typography.sh" "$@"
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
        MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" sh "$SOURCE_PROFILE" family "${2:-}"
        ;;
    universal_plan)
        [ -f "$UNIVERSAL_PLAN" ] || { printf '{"status":"error","message":"Universal FontPlan 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" sh "$UNIVERSAL_PLAN" build "${2:-}"
        ;;
    xml_route_plan)
        [ -f "$MINIMAL_XML_ROUTER" ] || { printf '{"status":"error","message":"Minimal XML Router 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" sh "$MINIMAL_XML_ROUTER" build "${2:-}"
        ;;
    font_compile)
        [ -f "$UNIVERSAL_COMPILER" ] || { printf '{"status":"error","message":"Universal Font Compiler 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" sh "$UNIVERSAL_COMPILER" compile "${2:-}"
        ;;
    font_deployment)
        [ -f "$UNIVERSAL_DEPLOYMENT" ] || { printf '{"status":"error","message":"Universal Deployment 组件不可用"}\n'; exit 1; }
        MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" sh "$UNIVERSAL_DEPLOYMENT" prepare "${2:-}"
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
            MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" \
                sh "$SAFE_SWITCH" action prewarm-start "${2:-}" >/dev/null 2>&1 || true
            printf '{"status":"ok","data":{"font":"%s","scheduled":true}}\n' "$(json_escape "${2:-}")"
        else
            printf '{"status":"error","message":"字体预热组件不可用"}\n'
        fi
        ;;
    validate)
        manager_ready || exit 1
        if [ -f "$SAFE_SWITCH" ] && [ -n "${2:-}" ] && [ "${2:-}" != default ]; then
            MODDIR="$MODDIR" ZIYU_PUBLIC_DIR="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}" \
                sh "$SAFE_SWITCH" action prewarm-start "${2:-}" >/dev/null 2>&1 || true
        fi
        sh "$FONT_MANAGER" action validate "${2:-}"
        ;;
    stock_scan) manager_ready || exit 1; sh "$FONT_MANAGER" action stock_scan ;;
    import_system_fonts)
        shift
        [ "$#" -gt 0 ] || set -- export
        MODDIR="$MODDIR" sh "$MODDIR/common/system_font_library.sh" "$@"
        ;;
    switch_start) switch_task_ready || exit 1; MODDIR="$MODDIR" sh "$FONT_SWITCH_TASK" start "${2:-default}" ;;
    switch_status) switch_task_ready || exit 1; MODDIR="$MODDIR" sh "$FONT_SWITCH_TASK" status "${2:-}" ;;
    # 字体应用相关入口统一走原生桥：App 不再直接调用内部脚本/工具。
    switch_history)
        shift
        _sh_tool="$MODDIR/system/bin/ziyu-history"
        [ -f "$_sh_tool" ] || _sh_tool="$MODDIR/common/ziyu_history.sh"
        [ -f "$_sh_tool" ] || { printf '{"status":"error","message":"切换历史组件不可用"}\n'; exit 1; }
        exec sh "$_sh_tool" "$@"
        ;;
    ttf_export)
        shift
        [ -f "$MODDIR/common/font_ttf_export.sh" ] || { printf '{"status":"error","message":"字体导出组件不可用"}\n'; exit 1; }
        exec sh "$MODDIR/common/font_ttf_export.sh" "$@"
        ;;
    delete) manager_ready || exit 1; sh "$FONT_MANAGER" action delete "${2:-}" ;;
    delete_many) manager_ready || exit 1; shift; sh "$FONT_MANAGER" action delete_many "$@" ;;
    mix_config) mix_ready || exit 1; sh "$MIX_ENGINE" config ;;
    mix_start) mix_ready || exit 1; sh "$MIX_ENGINE" start "${2:-}" "${3:-}" "${4:-}" "${5:-wght=400}" "${6:-wght=400}" "${7:-wght=400}" "${8:-}" "${9:-}" "${10:-}" "${11:-}" "${12:-0}" "${13:-0}" ;;
    mix_status) mix_ready || exit 1; sh "$MIX_ENGINE" status "${2:-}" ;;
    reboot)
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
