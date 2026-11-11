#!/system/bin/sh
# Root-manager and Meta backend selection is centralized in one mount runtime.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
mkdir -p "$MODDIR/logs" || exit 1
# 开机阶段进程计数与耗时诊断：只读 /proc 并使用内建运算，不额外派生进程。
if [ -f "$MODDIR/common/boot_profile.sh" ]; then
    . "$MODDIR/common/boot_profile.sh"
    ziyu_boot_profile_start "$MODDIR"
    trap 'ziyu_boot_profile_end "$MODDIR" 2>/dev/null || true' EXIT
fi
# 空负载分区目录也会被发布带进模块根，先清掉。
if [ -d "$MODDIR/.ziyu-payload" ]; then
    for _pf_pdir in "$MODDIR/.ziyu-payload"/*/; do
        [ -d "$_pf_pdir" ] || continue
        [ -z "$(ls -A "$_pf_pdir" 2>/dev/null)" ] && rmdir "$_pf_pdir" 2>/dev/null
    done
fi

# 空的分区目录会让 NoMount 中止全部 VFS injection（如设备无 mi_ext）。
for _pf_part in system system_ext product vendor odm oem mi_ext cust hw_product \
    my_product my_engineering my_company my_preload my_region my_stock \
    oplus_product oplus_engineering oplus_version oplus_region; do
    [ -d "$MODDIR/$_pf_part" ] && [ -z "$(ls -A "$MODDIR/$_pf_part" 2>/dev/null)" ] && rm -rf "$MODDIR/$_pf_part" 2>/dev/null
    # nested-system 发布留下的空 system/<partition> 也清掉。
    [ -d "$MODDIR/system/$_pf_part" ] && [ -z "$(ls -A "$MODDIR/system/$_pf_part" 2>/dev/null)" ] && rmdir "$MODDIR/system/$_pf_part" 2>/dev/null
done

# 新增设置统一放在 config/tools/；旧版本散落在 config/ 下的迁移过来。
mkdir -p "$MODDIR/config/tools" 2>/dev/null
for _tools_pair in "font-import-limit.conf:font-import-limit.conf" "slot_coverage_lang.conf:slot-coverage-lang.conf" "temporary-root-mode.conf:temporary-root-mode.conf"; do
    _tools_old=${_tools_pair%%:*}; _tools_new=${_tools_pair#*:}
    if [ -s "$MODDIR/config/$_tools_old" ] && [ ! -e "$MODDIR/config/tools/$_tools_new" ]; then
        mv -f "$MODDIR/config/$_tools_old" "$MODDIR/config/tools/$_tools_new" 2>/dev/null || true
    fi
done

# 恢复默认后若还有残留发布字体树（任何挂载后端），开机先强制清理回原厂。
_pf_active=''
IFS= read -r _pf_active < "$MODDIR/config/active_font.conf" 2>/dev/null || _pf_active=''
if [ -z "$_pf_active" ] || [ "$_pf_active" = default ]; then
    if [ -d "$MODDIR/system/fonts" ] || [ -d "$MODDIR/product/fonts" ] || [ -d "$MODDIR/system_ext/fonts" ]; then
        if [ -f "$MODDIR/common/restore_stock_factory.sh" ]; then
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
                sh "$MODDIR/common/restore_stock_factory.sh" "$MODDIR" >/dev/null 2>&1 || true
        fi
    fi
fi

# 临时 Root/软重启：上一次软重启清理失败时，本次不再激活负载，避免重复挂载错乱。
_soft_stage="$MODDIR/config/temporary-root-soft-reboot.conf"
_soft_boot=''
IFS= read -r _soft_boot < /proc/sys/kernel/random/boot_id 2>/dev/null || _soft_boot=''
if [ -s "$_soft_stage" ] && [ -n "$_soft_boot" ]; then
    _soft_saved_boot=''
    _soft_saved_state=''
    while IFS= read -r _soft_line || [ -n "$_soft_line" ]; do
        case "$_soft_line" in
            boot_id=*) _soft_saved_boot=${_soft_line#boot_id=} ;;
            state=*) _soft_saved_state=${_soft_line#state=} ;;
        esac
    done < "$_soft_stage"
    if [ "$_soft_saved_boot" = "$_soft_boot" ] && [ "$_soft_saved_state" = failed ]; then
        printf '[TEMP-ROOT] refusing payload activation after failed soft reboot cleanup\n' \
            >> "$MODDIR/logs/mount-backend.log"
        exit 1
    fi
fi
# NoMount 元模块的脚本接管会扫描模块根下所有 *.sh 并逐个 stat/检查；字域运行库
# 有约 160 个脚本，会把挂载阶段拖长数秒。把运行库移入 data/（NoMount 会裁剪该目录）
# 并留下同名相对符号链接：所有路径保持不变，但开机不再被逐个扫描。
if [ -d "$MODDIR/common" ] && [ ! -L "$MODDIR/common" ]; then
    if mkdir -p "$MODDIR/data" 2>/dev/null &&
       mv "$MODDIR/common" "$MODDIR/data/common" 2>/dev/null &&
       ln -s data/common "$MODDIR/common" 2>/dev/null; then
        :
    else
        [ -d "$MODDIR/data/common" ] && [ ! -e "$MODDIR/common" ] &&
            mv "$MODDIR/data/common" "$MODDIR/common" 2>/dev/null
    fi
fi
# Migration is one-shot and never unmounts live fonts or touches other modules.
if [ -f "$MODDIR/common/adb_storage.sh" ]; then
    . "$MODDIR/common/adb_storage.sh"
    ziyu_adb_storage "$MODDIR" boot || printf '[STORAGE] migration incomplete; see adb-storage.log\n' >> "$MODDIR/logs/mount-backend.log"
fi
type ziyu_boot_profile_mark >/dev/null 2>&1 && ziyu_boot_profile_mark "$MODDIR" storage

# 轻量启动：不再在开机阶段做 OTA/开机保护判定，避免拉长 zygote 前的启动窗口。
type ziyu_boot_profile_mark >/dev/null 2>&1 && ziyu_boot_profile_mark "$MODDIR" ota-guard-skipped

# APatch scans providers before module post-fs-data. Its owned common hook
# runs this preparation first, including a staged modules_update installation.
if [ -f "$MODDIR/common/apatch_boot_prepare.sh" ]; then
    . "$MODDIR/common/apatch_boot_prepare.sh"
    if [ "${ZIYU_APATCH_PRE_MOUNT:-0}" = 1 ]; then
        _zap_identity && _zap_module_safe "$MODDIR" || exit 1
    elif ziyu_apatch_boot_ready "$MODDIR" "$(cat /proc/sys/kernel/random/boot_id | tr -d '\r\n')"; then
        printf '[APATCH-PRE-MOUNT] reusing preparation; provider has already scanned this generation\n' >> "$MODDIR/logs/mount-backend.log"
        MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data
        exit $?
    fi
fi

# Manual exclusions are checked before even activating the pending generation.
[ ! -f "$MODDIR/common/skip_mount_ownership.sh" ] || . "$MODDIR/common/skip_mount_ownership.sh"
if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || \
   { type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$MODDIR"; }; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data
    exit $?
fi

# 轻量启动：跳过开机原厂字体快照（改为只在安装/手动操作时执行）。
type ziyu_boot_profile_mark >/dev/null 2>&1 && ziyu_boot_profile_mark "$MODDIR" stock-snapshot-skipped

# Activate a previously prepared payload before exposing any module files.
# Recover publication under the same lock before deciding which next-state wins.
# An interrupted physical publish may still have a Universal receipt in config.
if [ -d "$MODDIR/.ziyu-state/backup/next-transaction" ] &&
   [ -f "$MODDIR/common/font_next_transaction.sh" ] &&
   [ -f "$MODDIR/common/font_switch_lock.sh" ]; then
    . "$MODDIR/common/font_next_transaction.sh"
    . "$MODDIR/common/font_switch_lock.sh"
    ziyu_font_lock_acquire "$MODDIR/.font_switch.lock" "$$" || exit 1
    ziyu_next_transaction_recover "$MODDIR"
    _pending_recovery_rc=$?
    ziyu_font_lock_release "$MODDIR/.font_switch.lock" "$$" >/dev/null 2>&1 || true
    if [ "$_pending_recovery_rc" -ne 0 ]; then
        mkdir -p "$MODDIR/logs"
        printf '[NEXT-TRANSACTION] recovery unconfirmed rc=%s; activation deferred\n' \
            "$_pending_recovery_rc" >> "$MODDIR/logs/fontswitch.log"
        exit 1
    fi
fi
type ziyu_boot_profile_mark >/dev/null 2>&1 && ziyu_boot_profile_mark "$MODDIR" transaction
UNIVERSAL_NEXT_STATE="$MODDIR/config/universal-font-next.conf"
# 没有待激活负载时跳过旧热挂载清理与激活，避免每次开机做无效工作。
if [ -s "$UNIVERSAL_NEXT_STATE" ] || [ -s "$MODDIR/config/font-payload-next.conf" ] || \
   [ -d "$MODDIR/.ziyu-payload-next" ]; then
    MODDIR="$MODDIR" sh "$MODDIR/common/font_live_switch.sh" retire >> "$MODDIR/logs/font-live.log" 2>&1 || {
        echo '字域：旧热挂载清理未确认，保留字体负载并停止本次启动切换。' >&2
        exit 1
    }
    type ziyu_boot_profile_mark >/dev/null 2>&1 && ziyu_boot_profile_mark "$MODDIR" live-retire
    if [ -s "$UNIVERSAL_NEXT_STATE" ]; then
        NEXT_BOOT_HELPER="$MODDIR/common/universal_next_boot.sh"
        [ -f "$NEXT_BOOT_HELPER" ] && . "$NEXT_BOOT_HELPER"
        type universal_font_next_boot_activate >/dev/null 2>&1 || exit 1
        universal_font_next_boot_activate >> "$MODDIR/logs/fontswitch.log" 2>&1
        _activation_rc=$?
    else
        NEXT_BOOT_HELPER="$MODDIR/common/next_boot_payload.sh"
        [ -f "$NEXT_BOOT_HELPER" ] && . "$NEXT_BOOT_HELPER"
        type ziyu_next_boot_activate >/dev/null 2>&1 || exit 1
        ziyu_next_boot_activate >> "$MODDIR/logs/fontswitch.log" 2>&1
        _activation_rc=$?
    fi
    case "$_activation_rc" in 0|2) ;; *)
        printf '[NEXT-BOOT] activation failed rc=%s; refusing to publish a different generation\n' "$_activation_rc" >> "$MODDIR/logs/mount-backend.log"
        exit 1 ;;
    esac
    type ziyu_boot_profile_mark >/dev/null 2>&1 && ziyu_boot_profile_mark "$MODDIR" activation
fi

RUNTIME="$MODDIR/common/mount_backend_runtime.sh"
V4_POST_FS="$MODDIR/.ziyu-runtime/core/post-fs-data.sh"
LEGACY_MODE="$MODDIR/config/font_runtime_legacy_v14_4.conf"
UNIVERSAL_MODE="$MODDIR/config/universal-font-runtime.conf"

# Keep the verified v2.2.7 initializer for the normal v4 payload, then let its
# core wrapper hand off to the same selector used by compatibility runtimes.
if [ ! -s "$UNIVERSAL_MODE" ] && [ ! -f "$LEGACY_MODE" ] && [ -f "$V4_POST_FS" ]; then
    sh "$V4_POST_FS"
    _ziyu_v4_rc=$?
    type ziyu_boot_profile_end >/dev/null 2>&1 && ziyu_boot_profile_end "$MODDIR"
    exit "$_ziyu_v4_rc"
fi

mkdir -p "$MODDIR/logs" "$MODDIR/config" 2>/dev/null || true
if [ -f "$RUNTIME" ]; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$RUNTIME" hook post-fs-data \
        >> "$MODDIR/logs/mount-backend.log" 2>&1
    _lpf_rc=$?
else
    _lpf_rc=1
    printf 'runtime-loader-missing\n' > "$MODDIR/config/mount-backend-failed" 2>/dev/null || true
fi
type ziyu_boot_profile_end >/dev/null 2>&1 && ziyu_boot_profile_end "$MODDIR"
exit "$_lpf_rc"
