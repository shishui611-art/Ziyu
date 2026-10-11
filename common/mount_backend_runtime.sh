#!/system/bin/sh
# One decision per boot: automatic provider selection or the user's self-mount mode.
# Provider mode publishes before provider scan. Failures never change the user's
# preference or hand a self-mount transaction to an external provider.
set +e
MODDIR="${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}"
MODULE_DIR="$MODDIR"
_lbr_state="$MODDIR/config/mount-backend.conf"
_lbr_log="$MODDIR/logs/mount-backend.log"
_lbr_boot="${LUOSHU_BACKEND_TEST_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"
[ -n "$_lbr_boot" ] || { printf 'boot-id-unavailable\n' >&2; return 1 2>/dev/null || exit 1; }
_lbr_source_common="$MODDIR/common"
_lbr_runtime_file="${BASH_SOURCE:-$0}"
case "$_lbr_runtime_file" in */common/mount_backend_runtime.sh) _lbr_source_common="${_lbr_runtime_file%/*}" ;; esac

luoshu_backend_load_dependencies() {
    for _lbr_dependency in private_payload skip_mount_ownership mount_backend_preferences \
        mount_provider_detection mount_backend_policy mount_provider_payload root_manager_detection; do
        _lbr_helper="$MODDIR/common/$_lbr_dependency.sh"
        [ -f "$_lbr_helper" ] || _lbr_helper="$_lbr_source_common/$_lbr_dependency.sh"
        [ ! -f "$_lbr_helper" ] || . "$_lbr_helper"
    done
    [ "${LUOSHU_BACKEND_TEST_MODE:-0}" != 1 ] || return 0
    for _lbr_dependency in util_functions font_config_runtime font_config_partitions mount_compat mount_self_backend; do
        _lbr_helper="$MODDIR/common/$_lbr_dependency.sh"
        [ ! -f "$_lbr_helper" ] || . "$_lbr_helper"
    done
}

_lbr_log() {
    mkdir -p "$MODDIR/logs" 2>/dev/null || true
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" >> "$_lbr_log" 2>/dev/null || true
}


_lbr_value() {
    sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'
}


_lbr_log_mount_summary() {
    _lbr_summary_result="$1"
    _lbr_summary_stage="${2:-unknown}"
    _lbr_summary_reason="${3:-none}"
    _lbr_summary_selected=$(_lbr_value "$_lbr_state" selected_backend)
    _lbr_summary_active=$(_lbr_value "$_lbr_state" active_backend)
    _lbr_summary_verify=$(_lbr_value "$_lbr_state" verification)
    _lbr_summary_fallback=$(_lbr_value "$_lbr_state" fallback_used)
    _lbr_summary_fallback_reason=$(_lbr_value "$_lbr_state" fallback_reason)
    _lbr_summary_self_backend=$(_lbr_value "$_lbr_state" selected_self_backend)
    _lbr_summary_provider="${PROVIDER_ID:-unknown}"
    _lbr_summary_provider_name="$_lbr_summary_provider"
    _lbr_summary_selected_name="$_lbr_summary_selected"
    _lbr_summary_active_name="$_lbr_summary_active"
    _lbr_summary_method='未确认'
    case "$_lbr_summary_selected" in
        self) _lbr_summary_selected_name='字域自挂载 (self)' ;;
        external|meta) _lbr_summary_selected_name='外部提供者 (external)' ;;
        none) _lbr_summary_selected_name='无 (none)' ;;
        unresolved) _lbr_summary_selected_name='未能确定 (unresolved)' ;;
    esac
    case "$_lbr_summary_active" in
        self) _lbr_summary_active_name='字域自挂载 (self)' ;;
        external|meta) _lbr_summary_active_name='外部提供者 (external)' ;;
        none|'') _lbr_summary_active_name='无 (none)' ;;
    esac
    case "$_lbr_summary_result" in
        success)
            _lbr_summary_label='成功'
            [ "$_lbr_summary_verify" != partial ] || _lbr_summary_label='部分应用成功（有警告）'
            ;;
        failed) _lbr_summary_label='失败' ;;
        pending) _lbr_summary_label='待实际挂载/验证'; _lbr_summary_method='尚未确认，等待启动挂载阶段' ;;
        skipped) _lbr_summary_label='已跳过'; _lbr_summary_method='未执行' ;;
        not-applicable) _lbr_summary_label='无需挂载'; _lbr_summary_method='不适用（系统默认字体）' ;;
        *) _lbr_summary_label='状态未知' ;;
    esac
    if [ "$_lbr_summary_result" = success ] && \
       [ "${LUOSHU_BACKEND_TEST_MODE:-0}" != 1 ] && \
       [ -f "$MODDIR/common/mount_backend_details.sh" ]; then
        . "$MODDIR/common/mount_backend_details.sh"
        luoshu_mount_backend_details
        _lbr_summary_provider_name="${MOUNT_PROVIDER_NAME:-$_lbr_summary_provider}"
        _lbr_summary_method="${MOUNT_METHOD:-未确认}"
    elif [ "$_lbr_summary_result" = failed ]; then
        _lbr_summary_method='未能确认成功；查看失败原因与回滚状态'
    fi
    [ -n "$_lbr_summary_selected" ] || _lbr_summary_selected=unknown
    [ -n "$_lbr_summary_active" ] || _lbr_summary_active=none
    [ -n "$_lbr_summary_verify" ] || _lbr_summary_verify=unknown
    [ -n "$_lbr_summary_fallback" ] || _lbr_summary_fallback=0
    [ -n "$_lbr_summary_fallback_reason" ] || _lbr_summary_fallback_reason=none
    [ -n "$_lbr_summary_self_backend" ] || _lbr_summary_self_backend=none
    _lbr_log "[挂载结果] 状态=$_lbr_summary_label; 选择后端=$_lbr_summary_selected_name; 生效后端=$_lbr_summary_active_name; 检测到的元模块=$_lbr_summary_provider; 实际后端说明=$_lbr_summary_provider_name; 实现方式=$_lbr_summary_method; 路由验证=$_lbr_summary_verify; 自挂载实现=$_lbr_summary_self_backend; 回退=$_lbr_summary_fallback; 回退原因=$_lbr_summary_fallback_reason; 阶段=$_lbr_summary_stage; 原因=$_lbr_summary_reason"
}


_lbr_active_font() {
    if type ziyu_live_font >/dev/null 2>&1; then ziyu_live_font && return 0; fi
    _lbr_active=$(head -n1 "$MODDIR/config/active_font.conf" 2>/dev/null | tr -d '\r\n')
    [ -n "$_lbr_active" ] || _lbr_active=default
    printf '%s\n' "$_lbr_active"
}

_lbr_restore_default_provider_payload() {
    # A prior provider publication can remain in its scan tree after the active
    # font becomes default. Retract it before that provider scans modules again.
    _lbr_cleanup_nomount_rules || return 1
    [ "${PROVIDER_STATE:-unknown}" = available ] || return 0
    case "${PROVIDER_LAYOUT:-}" in nested-system|nomount-partition-roots) ;; *) return 0 ;; esac
    _lbr_publish_root="${PROVIDER_CONTENT_ROOT:-}"
    [ -n "$_lbr_publish_root" ] || return 0
    _lbr_publish_receipt="$_lbr_publish_root/.ziyu-state/provider-published.conf"
    _lbr_publish_owned=0
    if [ -f "$_lbr_publish_receipt" ]; then
        _lbr_receipt_layout=$(_lbr_value "$_lbr_publish_receipt" layout)
        if [ "$_lbr_receipt_layout" != "$PROVIDER_LAYOUT" ]; then
            _lbr_log "default-font provider cleanup refused: receipt layout mismatch root=$_lbr_publish_root receipt=${_lbr_receipt_layout:-missing} detected=$PROVIDER_LAYOUT"
            return 1
        fi
        _lbr_publish_owned=1
    else
        # Older builds did not always write a publisher receipt. A prior boot's
        # matching external-backend record is an equivalent ownership proof.
        _lbr_previous_root=$(_lbr_value "$_lbr_state" provider_content_root)
        _lbr_previous_id=$(_lbr_value "$_lbr_state" provider_id)
        _lbr_previous_layout=$(_lbr_value "$_lbr_state" provider_layout)
        _lbr_previous_active=$(_lbr_value "$_lbr_state" active_backend)
        _lbr_previous_selected=$(_lbr_value "$_lbr_state" selected_backend)
        if [ -z "$_lbr_previous_root" ] && [ "$_lbr_publish_root" = "$MODDIR" ]; then
            # Early provider-first builds recorded the backend but not its content
            # root. Native module trees are the only safe inferred destination.
            _lbr_previous_root="$MODDIR"
        fi
        if [ "$_lbr_previous_root" = "$_lbr_publish_root" ] && \
           [ "$_lbr_previous_id" = "$PROVIDER_ID" ] && \
           [ "$_lbr_previous_layout" = "$PROVIDER_LAYOUT" ] && \
           { [ "$_lbr_previous_active" = external ] || [ "$_lbr_previous_selected" = external ]; }; then
            _lbr_publish_owned=1
        fi
    fi
    [ "$_lbr_publish_owned" -eq 1 ] || return 0
    if ! ziyu_provider_payload_publish "$MODDIR" "$_lbr_publish_root" "$PROVIDER_LAYOUT"; then
        _lbr_log "default-font provider cleanup failed root=$_lbr_publish_root layout=$PROVIDER_LAYOUT"
        return 1
    fi
    _lbr_log "default-font provider cleanup published current empty font payload root=$_lbr_publish_root layout=$PROVIDER_LAYOUT"
    return 0
}


_lbr_write_state() {
    _lbr_selected="$1"
    _lbr_active="$2"
    _lbr_fallback="$3"
    _lbr_meta_result="$4"
    _lbr_self_result="$5"
    _lbr_stage="$6"
    _lbr_verify="$7"
    _lbr_error="$8"
    mkdir -p "$MODDIR/config" "$MODDIR/logs" 2>/dev/null || return 1
    _lbr_tmp="${_lbr_state}.tmp.$$"
    {
        printf 'schema=ziyu-mount-backend-v3\n'
        printf 'boot_id=%s\n' "$_lbr_boot"
        printf 'root_manager=%s\n' "${ROOT_MANAGER:-unknown}"
        printf 'root_version=%s\n' "${ROOT_VERSION:-unknown}"
        printf 'root_version_code=%s\n' "${ROOT_VERSION_CODE:-0}"
        printf 'root_detection_source=%s\n' "${ROOT_DETECTION_SOURCE:-unknown}"
        printf 'meta_version=%s\n' "${META_DISPLAY_VERSION:-}"
        printf 'meta_hybrid_mode=%s\n' "${META_HYBRID_DEFAULT_MODE:-}"
        printf 'meta_engine=%s\n' "${META_ENGINE:-none}"
        printf 'meta_available=%s\n' "${META_AVAILABLE:-0}"
        printf 'meta_enabled=%s\n' "${META_ENABLED:-0}"
        printf 'meta_usable=%s\n' "${META_USABLE:-0}"
        printf 'meta_ready=%s\n' "${META_READY:-0}"
        printf 'meta_usable_reason=%s\n' "${META_USABLE_REASON:-unknown}"
        printf 'meta_cleanup_capability=%s\n' "${META_CLEANUP_CAPABILITY:-none}"
        printf 'meta_active_dir=%s\n' "${META_ACTIVE_DIR:-}"
        printf 'meta_module_dir=%s\n' "${META_MODULE_DIR:-}"
        printf 'meta_detection_checks=%s\n' "${META_DETECTION_CHECKS:-}"
        printf 'provider_state=%s\n' "${PROVIDER_STATE:-unknown}"
        printf 'provider_id=%s\n' "${PROVIDER_ID:-unknown}"
        printf 'provider_layout=%s\n' "${PROVIDER_LAYOUT:-unknown}"
        printf 'provider_reason=%s\n' "${PROVIDER_REASON:-unknown}"
        printf 'provider_content_root=%s\n' "${PROVIDER_CONTENT_ROOT:-$MODDIR}"
        printf 'nomount_kernel_usable=%s\n' "${NOMOUNT_KERNEL_USABLE:-0}"
        printf 'preferred_backend=%s\n' "${_lbr_preference:-auto}"
        printf 'preference_failure=%s\n' "${_lbr_preference_failure:-none}"
        printf 'selected_backend=%s\n' "$_lbr_selected"
        printf 'selected_self_backend=%s\n' "${_lbr_self_backend_selected:-legacy}"
        printf 'active_self_backend=%s\n' "${_lbr_self_backend_active:-none}"
        printf 'active_backend=%s\n' "$_lbr_active"
        printf 'backend_conflict=%s\n' "${BACKEND_CONFLICT:-0}"
        printf 'fallback_used=%s\n' "$_lbr_fallback"
        printf 'fallback_reason=%s\n' "${_lbr_fallback_reason:-none}"
        printf 'fallback_service_retry=%s\n' "${_lbr_fallback_service_retry:-0}"
        printf 'meta_result=%s\n' "$_lbr_meta_result"
        printf 'self_result=%s\n' "$_lbr_self_result"
        printf 'mount_stage=%s\n' "$_lbr_stage"
        printf 'verification=%s\n' "$_lbr_verify"
        printf 'last_error=%s\n' "${_lbr_error:-none}"
        printf 'mount_warning=%s\n' "${FONT_ROUTE_WARNING:-}"
        printf 'time=%s\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$_lbr_tmp" 2>/dev/null || return 1
    chmod 0600 "$_lbr_tmp" 2>/dev/null || true
    mv -f "$_lbr_tmp" "$_lbr_state" 2>/dev/null || return 1
}


_lbr_stage_for_root() {
    case "$1" in
        Magisk) printf 'post-fs-data\n' ;;
        KernelSU|SukiSU\ Ultra|APatch) printf 'post-mount\n' ;;
        # Unknown root identities still get a best-effort early self-mount.
        # Service verification can retry if another provider covers it later.
        *) printf 'post-fs-data\n' ;;
    esac
}


_lbr_verify_font_route() {
    _lbr_backend="${1:-unknown}"
    _lbr_active="$(_lbr_active_font)"
    FONT_ROUTE_VERIFY_RESULT=failed
    FONT_ROUTE_WARNING=''
    if [ "$_lbr_active" = default ]; then
        FONT_ROUTE_VERIFY_RESULT=not-applicable
        _lbr_log '[Final Verify] no custom font selected; XML/CJK/Latin/Digit route check is not applicable'
        return 0
    fi
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        case "$_lbr_backend" in
            meta) _lbr_test_verify="${LUOSHU_BACKEND_TEST_META_VERIFY_RESULT:-${LUOSHU_BACKEND_TEST_VERIFY_RESULT:-pass}}" ;;
            external) _lbr_test_verify="${LUOSHU_BACKEND_TEST_EXTERNAL_VERIFY_RESULT:-${LUOSHU_BACKEND_TEST_VERIFY_RESULT:-pass}}" ;;
            *) _lbr_test_verify="${LUOSHU_BACKEND_TEST_SELF_VERIFY_RESULT:-${LUOSHU_BACKEND_TEST_VERIFY_RESULT:-pass}}" ;;
        esac
        [ "$_lbr_test_verify" = pass ] && FONT_ROUTE_VERIFY_RESULT=passed
        [ "$_lbr_test_verify" = pass ]
        return $?
    fi
    _lbr_python="$MODDIR/common/python/bin/luoshu-python"
    _lbr_verify_mode=auto
    if [ "$_lbr_backend" = self ] && [ "${_lbr_self_backend_active:-legacy}" = nomount ]; then
        _lbr_verify_mode=nomount
    elif [ "$_lbr_backend" = external ] && [ "${PROVIDER_ID:-}" = nomount ]; then
        _lbr_verify_mode=nomount
    fi
    if [ -n "${LUOSHU_PYTHON:-}" ]; then
        _lbr_python="$LUOSHU_PYTHON"
        _lbr_py_rc=$("$_lbr_python" "$MODDIR/common/font_route_verify.py" \
            --module-root "$MODDIR" --visible-root "${LUOSHU_FONT_VERIFY_VISIBLE_ROOT:-/proc/1/root}" \
            --mountinfo "${LUOSHU_BACKEND_MOUNTINFO:-/proc/1/mountinfo}" --mode "$_lbr_verify_mode" \
            --output "$MODDIR/config/mount-backend-verification.json" 2>>"$_lbr_log")
    else
        _lbr_pyroot="$MODDIR/common/python"
        _lbr_py_rc=$(PYTHONHOME="$_lbr_pyroot" PYTHONPATH="$MODDIR/common:$_lbr_pyroot/lib/python3.14:$_lbr_pyroot/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_lbr_pyroot/lib:$_lbr_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            "$_lbr_python" "$MODDIR/common/font_route_verify.py" \
            --module-root "$MODDIR" --visible-root "${LUOSHU_FONT_VERIFY_VISIBLE_ROOT:-/proc/1/root}" \
            --mountinfo "${LUOSHU_BACKEND_MOUNTINFO:-/proc/1/mountinfo}" --mode "$_lbr_verify_mode" \
            --output "$MODDIR/config/mount-backend-verification.json" 2>>"$_lbr_log")
    fi
    _lbr_rc=$?
    _lbr_log "[Final Verify] route-closure rc=$_lbr_rc result=$_lbr_py_rc"
    [ "$_lbr_rc" -eq 0 ] || return 1

    if [ -s "$MODDIR/config/universal-font-runtime.conf" ]; then
        LUOSHU_VERIFY_BOOT_COMPLETED=1 LUOSHU_VERIFY_SETTLE_SECONDS=0 \
        LUOSHU_VERIFY_MAIN_NAMESPACE=1 LUOSHU_VERIFY_NO_CUTOVER=1 \
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/universal_font_runtime_verify.sh" verify >>"$_lbr_log" 2>&1 || return 1
        [ "$(_lbr_value "$MODDIR/config/universal-font-runtime-verification.conf" grade)" != FAIL ] || return 1
    fi
    FONT_ROUTE_VERIFY_RESULT=passed
    if [ "$(_lbr_value "$MODDIR/config/font-apply-result.conf" state)" = partial ]; then
        FONT_ROUTE_VERIFY_RESULT=partial
        FONT_ROUTE_WARNING=$(_lbr_value "$MODDIR/config/font-apply-result.conf" warning)
        # Deduplicate repeated post-mount/service checks within the same boot.
        if [ "$(_lbr_value "$_lbr_state" mount_warning)" != "$FONT_ROUTE_WARNING" ]; then
            printf '[%s] [MOUNT] WARN %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$FONT_ROUTE_WARNING" >> "$MODDIR/logs/fontswitch.log"
        fi
    fi
    return 0
}


_lbr_detect() {
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        ROOT_MANAGER="${LUOSHU_BACKEND_TEST_MANAGER:-unknown}"
        ROOT_VERSION=test
        ROOT_DETECTION_SOURCE=test
        META_ENGINE="${LUOSHU_BACKEND_TEST_META_ENGINE:-none}"
        PROVIDER_STATE="${LUOSHU_BACKEND_TEST_PROVIDER_STATE:-absent}"
        PROVIDER_ID="${LUOSHU_BACKEND_TEST_PROVIDER_ID:-none}"
        PROVIDER_LAYOUT="${LUOSHU_BACKEND_TEST_PROVIDER_LAYOUT:-nested-system}"
        PROVIDER_REASON=test
        PROVIDER_CONTENT_ROOT="$MODDIR"
        return 0
    fi
    luoshu_detect_root_manager >/dev/null 2>&1 || return 1
    LUOSHU_META_PROVIDER_SCAN=1
    export LUOSHU_META_PROVIDER_SCAN
    luoshu_meta_mount_detect >/dev/null 2>&1
    _lbr_detect_rc=$?
    META_DISPLAY_VERSION=$(_lbr_value "${META_MODULE_DIR:-/nonexistent}/module.prop" version)
    unset LUOSHU_META_PROVIDER_SCAN
    [ "$_lbr_detect_rc" -eq 0 ] || return 1
    ziyu_mount_provider_detect "$MODDIR"
}

_lbr_set_self_skip() {
    ziyu_skip_marker_claim "$MODDIR" skip_mount || return 1
    ziyu_skip_marker_claim "$MODDIR" skip_mountify
    _lbr_skip_rc=$?
    [ "$_lbr_skip_rc" -eq 0 ] || [ "$_lbr_skip_rc" -eq 2 ]
}

_lbr_restore_boot_choice() {
    ROOT_MANAGER=$(_lbr_value "$_lbr_state" root_manager)
    ROOT_VERSION=$(_lbr_value "$_lbr_state" root_version)
    ROOT_VERSION_CODE=$(_lbr_value "$_lbr_state" root_version_code)
    ROOT_DETECTION_SOURCE=$(_lbr_value "$_lbr_state" root_detection_source)
    META_DISPLAY_VERSION=$(_lbr_value "$_lbr_state" meta_version)
    META_HYBRID_DEFAULT_MODE=$(_lbr_value "$_lbr_state" meta_hybrid_mode)
    META_ENGINE=$(_lbr_value "$_lbr_state" meta_engine)
    META_AVAILABLE=$(_lbr_value "$_lbr_state" meta_available)
    META_ENABLED=$(_lbr_value "$_lbr_state" meta_enabled)
    META_USABLE=$(_lbr_value "$_lbr_state" meta_usable)
    META_READY=$(_lbr_value "$_lbr_state" meta_ready)
    META_USABLE_REASON=$(_lbr_value "$_lbr_state" meta_usable_reason)
    META_CLEANUP_CAPABILITY=$(_lbr_value "$_lbr_state" meta_cleanup_capability)
    META_ACTIVE_DIR=$(_lbr_value "$_lbr_state" meta_active_dir)
    META_MODULE_DIR=$(_lbr_value "$_lbr_state" meta_module_dir)
    META_DETECTION_CHECKS=$(_lbr_value "$_lbr_state" meta_detection_checks)
    FONT_ROUTE_WARNING=$(_lbr_value "$_lbr_state" mount_warning)
    PROVIDER_STATE=$(_lbr_value "$_lbr_state" provider_state)
    PROVIDER_ID=$(_lbr_value "$_lbr_state" provider_id)
    PROVIDER_LAYOUT=$(_lbr_value "$_lbr_state" provider_layout)
    PROVIDER_REASON=$(_lbr_value "$_lbr_state" provider_reason)
    PROVIDER_CONTENT_ROOT=$(_lbr_value "$_lbr_state" provider_content_root)
    NOMOUNT_KERNEL_USABLE=$(_lbr_value "$_lbr_state" nomount_kernel_usable)
    [ -n "$ROOT_VERSION_CODE" ] || ROOT_VERSION_CODE=0
    [ -n "$META_AVAILABLE" ] || META_AVAILABLE=0
    [ -n "$META_ENABLED" ] || META_ENABLED=0
    [ -n "$META_USABLE" ] || META_USABLE=0
    [ -n "$META_READY" ] || META_READY=0
    [ -n "$META_USABLE_REASON" ] || META_USABLE_REASON=unknown
    [ -n "$META_CLEANUP_CAPABILITY" ] || META_CLEANUP_CAPABILITY=none
    [ -n "$NOMOUNT_KERNEL_USABLE" ] || NOMOUNT_KERNEL_USABLE=0
    _lbr_selected=$(_lbr_value "$_lbr_state" selected_backend)
    _lbr_preference=$(_lbr_value "$_lbr_state" preferred_backend)
    _lbr_preference_failure=$(_lbr_value "$_lbr_state" preference_failure)
    _lbr_self_backend_selected=$(_lbr_value "$_lbr_state" selected_self_backend)
    _lbr_self_backend_active=$(_lbr_value "$_lbr_state" active_self_backend)
    _lbr_fallback=$(_lbr_value "$_lbr_state" fallback_used)
    _lbr_fallback_reason=$(_lbr_value "$_lbr_state" fallback_reason)
    _lbr_fallback_service_retry=$(_lbr_value "$_lbr_state" fallback_service_retry)
    [ -n "$_lbr_fallback" ] || _lbr_fallback=0
    [ -n "$_lbr_fallback_reason" ] || _lbr_fallback_reason=none
    [ -n "$_lbr_fallback_service_retry" ] || _lbr_fallback_service_retry=0
}

_lbr_cleanup_nomount_rules() {
    [ -e "$MODDIR/.ziyu-state/nomount-rules.current" ] || return 0
    . "$MODDIR/common/mount_nomount_backend.sh" || return 1
    luoshu_nomount_cleanup "$MODDIR"
}

_lbr_rollback_self_attempt() {
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        [ "${LUOSHU_BACKEND_TEST_ROLLBACK_RC:-0}" -eq 0 ] || return 1
        : > "$MODDIR/config/test-self-rollback"
        [ "${LUOSHU_BACKEND_TEST_ROLLBACK_STILL_ACTIVE:-0}" != 1 ] || return 1
        return 0
    fi
    if ! type _luoshu_atomic_rollback >/dev/null 2>&1; then
        [ -f "$MODDIR/common/mount_compat.sh" ] || return 1
        . "$MODDIR/common/mount_compat.sh" || return 1
    fi
    _lbr_cleanup_nomount_rules || return 1
    if [ -s "$MODDIR/config/universal-font-runtime.conf" ]; then
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/universal_mount_runtime.sh" rollback || return 1
    fi
    _lbr_mounts="$(_luoshu_self_state_root)/mounts.list"
    if [ ! -s "$_lbr_mounts" ]; then
        _luoshu_self_state_mounts_remain payload && return 1
        return 0
    fi
    # Kernel mounts disappear on reboot. Never unload targets from an old journal.
    _lbr_journal_boot=$(cat "$(_luoshu_self_state_root)/boot-id" 2>/dev/null | tr -d '\r\n')
    [ "$_lbr_journal_boot" = "$_lbr_boot" ] || return 0
    _lbr_keep_work=''
    type ziyu_live_current >/dev/null 2>&1 && ziyu_live_current && _lbr_keep_work=detach
    _luoshu_atomic_rollback "$_lbr_mounts" "$_lbr_keep_work"
}

_lbr_run_self() {
    _lbr_stage="$1"
    _lbr_expected_stage=$(_lbr_stage_for_root "$ROOT_MANAGER")
    if [ "$_lbr_stage" != "$_lbr_expected_stage" ]; then
        [ "$_lbr_stage" = service ] && [ "$_lbr_fallback" = 1 ] || return 2
    fi
    case "$_lbr_preference" in
        magic|overlayfs|self_mount) ;;
        *)
            _lbr_write_state self none 0 not-selected failed "$_lbr_stage" failed self-backend-not-requested
            return 1 ;;
    esac
    [ "$_lbr_selected" = self ] || return 1
    _lbr_self_mount_mode="${_lbr_self_backend_selected:-self_mount}"
    case "$_lbr_self_mount_mode" in magic|overlayfs|self_mount) ;; *) _lbr_self_mount_mode=self_mount ;; esac
    _lbr_self_backend_active="$_lbr_self_mount_mode"
    LUOSHU_SELF_MOUNT_MODE="$_lbr_self_mount_mode"
    export LUOSHU_SELF_MOUNT_MODE
    _lbr_set_self_skip || return 1
    if [ "${LUOSHU_BACKEND_TEST_MODE:-0}" = 1 ]; then
        _lbr_rc="${LUOSHU_BACKEND_TEST_SELF_RC:-0}"
        [ "$_lbr_rc" -ne 0 ] || : > "$MODDIR/config/test-self-mounted"
    elif [ -s "$MODDIR/config/universal-font-runtime.conf" ]; then
        _lbr_allow_service_fallback=0
        [ "$_lbr_stage" != service ] || [ "$_lbr_fallback" != 1 ] || _lbr_allow_service_fallback=1
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
            LUOSHU_UNIVERSAL_ALLOW_SERVICE_FALLBACK="$_lbr_allow_service_fallback" \
            LUOSHU_SELF_MOUNT_MODE="$_lbr_self_mount_mode" \
            sh "$MODDIR/common/universal_mount_runtime.sh" hook "$_lbr_stage"
        _lbr_rc=$?
    else
        if [ ! -s "$MODDIR/config/font-payload-manifest.conf" ]; then
            . "$MODDIR/common/physical_payload_manifest.sh" || return 1
            luoshu_physical_manifest_ensure "$MODDIR" "$MODDIR/.luoshu-payload" || {
                _lbr_write_state self none "$_lbr_fallback" not-selected failed "$_lbr_stage" failed physical-integrity-contract-missing
                _lbr_log_mount_summary failed "$_lbr_stage" physical-integrity-contract-missing
                return 1
            }
        fi
        luoshu_private_self_mount_ensure
        _lbr_rc=$?
    fi
    _lbr_error=none
    if [ "$_lbr_rc" -ne 0 ]; then
        _lbr_error=self-mount-failed
        _lbr_self_detail=$(_lbr_value "$MODDIR/config/self-mount.conf" failed)
        [ -z "$_lbr_self_detail" ] || _lbr_error="$_lbr_error:$_lbr_self_detail"
    elif ! _lbr_verify_font_route self; then
        _lbr_error=font-route-verification-failed
    fi
    if [ "$_lbr_error" != none ]; then
        _lbr_failed_active=none
        if ! _lbr_rollback_self_attempt; then
            _lbr_error="$_lbr_error;rollback-failed"
            _lbr_failed_active=self
        else
            _lbr_self_backend_active=none
        fi
        _lbr_write_state self "$_lbr_failed_active" "$_lbr_fallback" not-selected failed "$_lbr_stage" failed "$_lbr_error"
        _lbr_log_mount_summary failed "$_lbr_stage" "$_lbr_error"
        return 1
    fi
    _lbr_self_actual=$(_lbr_value "$MODDIR/config/self-mount.conf" backend)
    [ -z "$_lbr_self_actual" ] || _lbr_self_backend_active="$_lbr_self_actual"
    _lbr_write_state self self "$_lbr_fallback" not-selected passed "$_lbr_stage" "$FONT_ROUTE_VERIFY_RESULT" none
    _lbr_log "ACTIVE_BACKEND=self stage=$_lbr_stage"
    _lbr_log_mount_summary success "$_lbr_stage" none
}

luoshu_mount_backend_hook() {
    _lbr_stage="$1"
    mkdir -p "$MODDIR/config" "$MODDIR/logs" || return 1
    if [ -e "$MODDIR/config/font-live-transaction.conf" ]; then
        _lbr_log 'live transaction in progress or requires recovery; boot backend verification deferred'
        return 0
    fi
    _lbr_self_backend_selected=none
    _lbr_self_backend_active=none
    _lbr_preference=auto
    _lbr_preference_failure=none
    FONT_ROUTE_WARNING=''
    _lbr_fallback=0
    _lbr_fallback_reason=none
    _lbr_fallback_service_retry=0
    _lbr_detect_failure=none
    BACKEND_CONFLICT=0

    # Honor a manual exclusion before detection, activation or publication.
    if ziyu_foreign_skip_mount_present "$MODDIR" || [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
        _lbr_cleanup_nomount_rules || {
            _lbr_restore_boot_choice
            _lbr_write_state none external 0 blocked not-run "$_lbr_stage" failed disabled-nomount-cleanup-failed
            _lbr_log_mount_summary failed "$_lbr_stage" disabled-nomount-cleanup-failed
            return 1
        }
        if [ "$(_lbr_value "$_lbr_state" boot_id)" = "$_lbr_boot" ] && \
           [ "$(_lbr_value "$_lbr_state" active_backend)" = self ]; then
            _lbr_restore_boot_choice
            _lbr_rollback_self_attempt || {
                _lbr_write_state none self 0 blocked failed "$_lbr_stage" failed disabled-cleanup-failed
                _lbr_log_mount_summary failed "$_lbr_stage" disabled-cleanup-failed
                return 1
            }
        fi
        _lbr_write_state none none 0 skipped skipped "$_lbr_stage" not-applicable foreign-skip-mount
        _lbr_log_mount_summary skipped "$_lbr_stage" foreign-skip-mount
        return 0
    fi

    if [ "$(_lbr_value "$_lbr_state" boot_id)" = "$_lbr_boot" ] && \
       { [ "$_lbr_stage" != post-fs-data ] || [ "$(_lbr_value "$_lbr_state" last_error)" != early-hook-missing ]; }; then
        _lbr_restore_boot_choice
    else
        # The initial choice and publication have a single early-boot boundary.
        if [ "$_lbr_stage" != post-fs-data ]; then
            _lbr_write_state unresolved none 0 not-run not-run "$_lbr_stage" pending early-hook-missing
            _lbr_log_mount_summary pending "$_lbr_stage" early-hook-missing
            return 1
        fi
        if ! _lbr_detect; then
            _lbr_detect_failure=provider-detection-failed
            PROVIDER_STATE=unknown
            PROVIDER_ID=unknown
            PROVIDER_LAYOUT=unknown
            PROVIDER_REASON=provider-detection-failed
        fi
        _lbr_preference=$(luoshu_mount_preference_get)
        _lbr_has_font=false
        [ "$(_lbr_active_font)" = default ] || _lbr_has_font=true
        _lbr_selected=$(ziyu_mount_select false "$PROVIDER_STATE" "$_lbr_has_font" "$_lbr_preference")
        _lbr_log "provider=$PROVIDER_STATE id=$PROVIDER_ID selected=$_lbr_selected"
        case "$_lbr_selected" in
            external)
                # Publish files first; clear only owned exclusions after success.
                if ! ziyu_provider_payload_publish "$MODDIR" "$PROVIDER_CONTENT_ROOT" "$PROVIDER_LAYOUT"; then
                    _lbr_write_state external none 0 failed not-run "$_lbr_stage" failed provider-publish-failed
                    _lbr_log_mount_summary failed "$_lbr_stage" provider-publish-failed
                    return 1
                elif ! ziyu_skip_release_owned "$MODDIR"; then
                    _lbr_write_state external none 0 failed not-run "$_lbr_stage" failed owned-skip-cleanup-failed
                    _lbr_log_mount_summary failed "$_lbr_stage" owned-skip-cleanup-failed
                    return 1
                else
                    _lbr_write_state external none 0 prepared not-run "$_lbr_stage" pending provider-scan-pending
                    _lbr_log_mount_summary pending "$_lbr_stage" provider-scan-pending
                    return 0
                fi
                ;;
            self)
                case "$_lbr_preference" in
                    magic|overlayfs|self_mount)
                        _lbr_self_backend_selected="$_lbr_preference"
                        # Reclaim only rules owned by old versions before self mount.
                        if ! _lbr_cleanup_nomount_rules; then
                            _lbr_write_state self external 0 failed not-run "$_lbr_stage" failed previous-nomount-cleanup-unconfirmed
                            return 1
                        fi
                        rm -f "$MODDIR/config/provider-rescue.conf"
                        _lbr_fallback=0
                        _lbr_fallback_reason=none
                        _lbr_set_self_skip || {
                            _lbr_write_state self none 0 not-selected failed "$_lbr_stage" failed self-skip-marker-failed
                            return 1
                        }
                        _lbr_write_state self none 0 not-selected pending "$_lbr_stage" pending \
                            "user-selected-self-mount:$_lbr_preference" || return 1
                        _lbr_log "self mount mode selected by user mode=$_lbr_preference stage=$_lbr_stage"
                        _lbr_log_mount_summary pending "$_lbr_stage" "user-selected-self-mount:$_lbr_preference"
                        ;;
                    *)
                        _lbr_write_state self none 0 not-selected failed "$_lbr_stage" failed invalid-self-preference
                        return 1
                        ;;
                esac
                ;;
            unresolved)
                _lbr_write_state external none 0 unavailable not-run "$_lbr_stage" failed "provider-unavailable:$PROVIDER_REASON"
                _lbr_log_mount_summary failed "$_lbr_stage" "provider-unavailable:$PROVIDER_REASON"
                return 1
                ;;
            none)
                if ! _lbr_restore_default_provider_payload; then
                    _lbr_write_state none none 0 not-run not-run "$_lbr_stage" failed provider-default-payload-cleanup-failed
                    _lbr_log_mount_summary failed "$_lbr_stage" provider-default-payload-cleanup-failed
                    return 1
                fi
                _lbr_write_state none none 0 not-run not-run "$_lbr_stage" not-applicable no-custom-font-selected
                _lbr_log '[挂载说明] 当前选择系统默认字体；未执行自定义字体挂载，字体路由验证不适用'
                _lbr_log_mount_summary not-applicable "$_lbr_stage" no-custom-font-selected
                return 0
                ;;
            *)
                _lbr_write_state unresolved none 0 unavailable not-run "$_lbr_stage" failed "provider-selection-invalid:$PROVIDER_REASON"
                return 1
                ;;
        esac
    fi

    case "$_lbr_selected" in
        external)
            [ "$_lbr_stage" != post-fs-data ] || return 0
            if [ "$PROVIDER_STATE" != available ]; then
                # Refresh read-only evidence after the metamodule hook. Never
                # publish late, inject a private mount or change user preference.
                _lbr_detect || :
                if [ "$PROVIDER_STATE" != available ]; then
                    if [ -z "${META_ACTIVE_DIR:-}" ] || [ "${META_ENABLED:-0}" != 1 ]; then
                        _lbr_write_state external none 0 unavailable not-run "$_lbr_stage" failed "provider-unavailable:$PROVIDER_REASON"
                        _lbr_log_mount_summary failed "$_lbr_stage" "provider-unavailable:$PROVIDER_REASON"
                        return 1
                    fi
                    PROVIDER_ID="${META_ENGINE:-unknown}"
                fi
            fi
            if _lbr_verify_font_route external; then
                _lbr_external_self_result=$(_lbr_value "$_lbr_state" self_result)
                _lbr_write_state external external "$_lbr_fallback" passed "${_lbr_external_self_result:-not-run}" "$_lbr_stage" "$FONT_ROUTE_VERIFY_RESULT" none
                _lbr_log_mount_summary success "$_lbr_stage" none
                return 0
            fi
            _lbr_write_state external none 0 failed not-run "$_lbr_stage" failed external-route-verification-failed
            _lbr_log_mount_summary failed "$_lbr_stage" external-route-verification-failed
            return 1
            ;;
        self)
            [ "$(_lbr_value "$_lbr_state" self_result)" != failed ] || return 1
            # service normally verifies; only a selected fallback may inject there.
            if [ "$_lbr_stage" = service ] && [ "$(_lbr_value "$_lbr_state" active_backend)" = self ]; then
                if _lbr_verify_font_route self; then
                    _lbr_write_state self self "$_lbr_fallback" not-selected passed service "$FONT_ROUTE_VERIFY_RESULT" none
                    _lbr_log_mount_summary success service none
                    return 0
                fi
                if [ "$_lbr_fallback" = 1 ] && [ ! -e "$MODDIR/config/font-live.conf" ] && [ "$_lbr_fallback_service_retry" != 1 ] && \
                   _lbr_rollback_self_attempt; then
                    _lbr_self_backend_active=none
                    _lbr_fallback_service_retry=1
                    _lbr_fallback_reason="${_lbr_fallback_reason}:service-route-retry"
                    _lbr_write_state self none 1 not-selected pending service pending \
                        "self-fallback-retry:$_lbr_fallback_reason" || return 1
                    _lbr_run_self service
                    return $?
                fi
                _lbr_error=font-route-verification-failed
                _lbr_failed_active=none
                if _lbr_rollback_self_attempt; then
                    _lbr_self_backend_active=none
                else
                    _lbr_failed_active=self
                    _lbr_error='font-route-verification-failed;rollback-failed'
                fi
                _lbr_write_state self "$_lbr_failed_active" "$_lbr_fallback" not-selected failed service failed "$_lbr_error"
                _lbr_log_mount_summary failed service "$_lbr_error"
                return 1
            fi
            if [ "$_lbr_stage" = service ] && [ "$_lbr_fallback" = 1 ] && \
               [ "$(_lbr_value "$_lbr_state" active_backend)" != self ]; then
                _lbr_run_self service
                return $?
            fi
            [ "$_lbr_stage" = "$(_lbr_stage_for_root "$ROOT_MANAGER")" ] || return 0
            if [ "$(_lbr_value "$_lbr_state" active_backend)" = self ] && \
               [ "$(_lbr_value "$_lbr_state" verification)" = passed ]; then
                return 0
            fi
            _lbr_run_self "$_lbr_stage"
            ;;
        *) return 0 ;;
    esac
}

luoshu_backend_load_dependencies
if [ "${0##*/}" = mount_backend_runtime.sh ]; then
    case "${1:-hook}" in
        hook) luoshu_mount_backend_hook "${2:-post-fs-data}" ;;
        detect) _lbr_detect; printf 'ROOT_MANAGER=%s\nPROVIDER_STATE=%s\nPROVIDER_ID=%s\nPROVIDER_LAYOUT=%s\nMETA_ENGINE=%s\n' "$ROOT_MANAGER" "$PROVIDER_STATE" "$PROVIDER_ID" "$PROVIDER_LAYOUT" "$META_ENGINE" ;;
        status) cat "$_lbr_state" 2>/dev/null ;;
        *) printf 'usage: %s {hook STAGE|detect|status}\n' "$0" >&2; exit 2 ;;
    esac
fi
