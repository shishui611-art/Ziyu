#!/system/bin/sh
# Explicit controls never reboot or replace the mounted live payload.
luoshu_undo_capture_config() {
    _luc_module="$1"
    _luc_stage="$_luc_module/config/.font-undo-config-stage.$$"
    mkdir -p "$_luc_stage" || return 1
    for _luc_name in font-config-overlay.conf font_runtime_legacy_v14_4.conf font-payload-schema.conf font-payload-manifest.conf universal-font-runtime.conf font-runtime-targets.conf font-target-aliases.conf font-target-coverage.conf device-font-engine.conf; do
        [ ! -f "$_luc_module/config/$_luc_name" ] || cp -fp "$_luc_module/config/$_luc_name" "$_luc_stage/$_luc_name" || return 1
    done
}

luoshu_undo_commit_record() {
    _lucr_module="$1"; _lucr_activated="$2"
    # A requested undo consumes the saved snapshot. Keep it through activation;
    # do not replace it with the font that the user just chose to undo.
    if [ "${3:-false}" = true ]; then
        _lucr_restored=$(sed -n 's/^font=//p' "$_lucr_activated" 2>/dev/null | head -n1)
        printf 'state=applied\ntargetFont=%s\n' "$_lucr_restored" > "$_lucr_module/config/font-undo-result.conf.tmp.$$" || return 1
        mv -f "$_lucr_module/config/font-undo-result.conf.tmp.$$" "$_lucr_module/config/font-undo-result.conf" || return 1
        rm -rf "$_lucr_module/config/.font-undo-config-stage.$$"
        return 0
    fi
    rm -f "$_lucr_module/config/font-undo-result.conf"
    cp -fp "$_lucr_activated" "$_lucr_module/config/font-undo.conf.tmp.$$" || return 1
    mv -f "$_lucr_module/config/font-undo.conf.tmp.$$" "$_lucr_module/config/font-undo.conf" || return 1
    _lucr_saved="$_lucr_module/config/.font-undo-config"
    [ ! -d "$_lucr_saved" ] || rm -rf "$_lucr_saved"
    mv "$_lucr_module/config/.font-undo-config-stage.$$" "$_lucr_saved" || return 1
}

luoshu_undo_restore_config() {
    _lurc_module="$1"; _lurc_state="$2"
    [ "$(sed -n 's/^undo=//p' "$_lurc_state" 2>/dev/null | head -n1)" = true ] || return 0
    _lurc_saved="$_lurc_module/config/.font-undo-config"
    [ -d "$_lurc_saved" ] || return 0
    luoshu_undo_restore_dir "$_lurc_module" "$_lurc_saved"
}

luoshu_undo_restore_dir() {
    _lurd_module="$1"; _lurd_saved="$2"
    for _lurd_name in font-config-overlay.conf font_runtime_legacy_v14_4.conf font-payload-schema.conf font-payload-manifest.conf universal-font-runtime.conf font-runtime-targets.conf font-target-aliases.conf font-target-coverage.conf device-font-engine.conf; do
        rm -f "$_lurd_module/config/$_lurd_name" || return 1
        [ ! -f "$_lurd_saved/$_lurd_name" ] || cp -fp "$_lurd_saved/$_lurd_name" "$_lurd_module/config/$_lurd_name" || return 1
    done
}

luoshu_undo_prune_retired() {
    _lupr_module="$1"
    MODDIR="$_lupr_module" LUOSHU_ACTION_CONTROL_LIBRARY=false sh "$_lupr_module/common/action_control.sh" prune-retired
}

luoshu_undo_cancel_pending_boot() {
    _lucpb_module="$1"; _lucpb_state="$2"
    _lucpb_task=$(sed -n 's/^taskId=//p' "$_lucpb_state" 2>/dev/null | head -n1)
    [ -n "$_lucpb_task" ] || return 1
    _lucpb_cancel=$(sed -n 's/^task=//p' "$_lucpb_module/config/switch_task.cancel" 2>/dev/null | head -n1)
    [ "$_lucpb_task" = "$_lucpb_cancel" ] || return 1
    # A stop request arriving after a committed hot switch cannot discard the
    # matching boot queue or rename its label back to a font no longer mounted.
    _lucpb_live="$_lucpb_module/config/font-live.conf"
    if [ "$(sed -n 's/^request_id=//p' "$_lucpb_live" | head -n1)" = "$(sed -n 's/^requestId=//p' "$_lucpb_state" | head -n1)" ] && \
       [ -n "$(sed -n 's/^requestId=//p' "$_lucpb_state" | head -n1)" ]; then return 1; fi
    _lucpb_previous=$(sed -n 's/^previousFont=//p' "$_lucpb_state" 2>/dev/null | head -n1)
    [ -n "$_lucpb_previous" ] || _lucpb_previous=default
    # The next payload is never mounted; live payload/config XML stay untouched.
    [ ! -L "$_lucpb_module/.luoshu-payload-next" ] || return 1
    rm -rf "$_lucpb_module/.luoshu-payload-next" || return 1
    rm -f "$_lucpb_state" "$_lucpb_module/config/text_reboot_required.conf" || return 1
    printf '%s\n' "$_lucpb_previous" > "$_lucpb_module/config/active_font.conf" || return 1
    return 0
}

if [ "${LUOSHU_ACTION_CONTROL_LIBRARY:-false}" = true ] || [ "${1:-}" = --library ]; then
    return 0 2>/dev/null || exit 0
fi
_lac_module="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
_lac_home="$_lac_module/common/python"
_lac_python="${LUOSHU_PYTHON:-$_lac_home/bin/luoshu-python}"
# Serialize undo against new switch starts and the shared font preparation lock.
if [ "${1:-}" = undo ]; then
    MODULE_DIR="$_lac_module"; export MODULE_DIR
    [ ! -f "$_lac_module/common/util_functions.sh" ] || . "$_lac_module/common/util_functions.sh"
    if ! type luoshu_font_lock_acquire >/dev/null 2>&1; then
        printf '{"status":"error","message":"字体回退锁组件不可用"}\n'; exit 1
    fi
    luoshu_font_lock_acquire "$_lac_module/.font_switch_start.lock" "$$" || {
        printf '{"status":"error","message":"正在启动字体任务，请稍后撤销"}\n'; exit 1;
    }
    trap 'luoshu_font_lock_release "$_lac_module/.font_switch_start.lock" "$$" >/dev/null 2>&1 || true; luoshu_font_lock_release "$_lac_module/.font_switch.lock" "$$" >/dev/null 2>&1 || true' EXIT
    luoshu_font_lock_acquire "$_lac_module/.font_switch.lock" "$$" || {
        printf '{"status":"error","message":"字体任务正在处理，请先停止任务"}\n'; exit 1;
    }
    if [ -n "${LUOSHU_PYTHON:-}" ]; then
        "$_lac_python" "$_lac_module/common/action_control.py" --module "$_lac_module" "$@"
    else
        PYTHONHOME="$_lac_home" PYTHONPATH="$_lac_home/lib/python3.14:$_lac_home/lib/python3.14/site-packages" \
        LD_LIBRARY_PATH="$_lac_home/lib:$_lac_home/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            "$_lac_python" "$_lac_module/common/action_control.py" --module "$_lac_module" "$@"
    fi
    exit $?
fi
if [ -n "${LUOSHU_PYTHON:-}" ]; then
    exec "$_lac_python" "$_lac_module/common/action_control.py" --module "$_lac_module" "$@"
fi
[ -x "$_lac_python" ] || { printf '{"status":"error","message":"任务控制组件不可用"}\n'; exit 1; }
PYTHONHOME="$_lac_home" PYTHONPATH="$_lac_home/lib/python3.14:$_lac_home/lib/python3.14/site-packages" \
LD_LIBRARY_PATH="$_lac_home/lib:$_lac_home/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    exec "$_lac_python" "$_lac_module/common/action_control.py" --module "$_lac_module" "$@"
