#!/system/bin/sh
# Remove the private module view before the verified v2.2.7 cleanup runs.
# Delegated dynamic cleanup contract: device-font-dynamic-mount.conf
# Delegated Flyme restore contract: luoshu_flyme_pending_apply
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
MODDIR="$MODDIR" sh "$MODDIR/common/font_live_switch.sh" retire || {
    echo '字域：热挂载清理尚未确认，保留运行文件并停止卸载。' >&2
    exit 1
}
# Stop the watcher and its active child before undoing mounts; otherwise an
# in-flight FontTools/bind child can recreate a view after restore completes.
[ ! -f "$MODDIR/common/font_switch_lock.sh" ] || . "$MODDIR/common/font_switch_lock.sh"
[ ! -f "$MODDIR/common/background_task.sh" ] || . "$MODDIR/common/background_task.sh"
if type luoshu_font_lock_active >/dev/null 2>&1 && type luoshu_terminate_task_tree >/dev/null 2>&1; then
    for _provider_lock in "$MODDIR/.google-font-provider.lock" "$MODDIR/.google-font-provider-bridge.lock"; do
        if luoshu_font_lock_active "$_provider_lock"; then
            _provider_pid=$(luoshu_font_lock_pid "$_provider_lock")
            case "$_provider_pid" in ''|*[!0-9]*|0|1) continue ;; esac
            if grep -aq -e 'google_font_provider_service.sh' -e 'google_font_provider_bridge.sh' -e 'hyperos_theme_font_bridge.sh' \
                "/proc/$_provider_pid/cmdline" 2>/dev/null; then
                luoshu_terminate_task_tree "$_provider_pid"
            fi
        fi
    done
fi
for _bridge in google_font_provider_bridge.sh hyperos_theme_font_bridge.sh; do
    [ ! -f "$MODDIR/common/$_bridge" ] || MODDIR="$MODDIR" sh "$MODDIR/common/$_bridge" restore >/dev/null 2>&1 || true
done
if [ -e "$MODDIR/.ziyu-state/nomount-rules.current" ]; then
    . "$MODDIR/common/mount_nomount_backend.sh" || exit 1
    luoshu_nomount_cleanup "$MODDIR" || {
        echo '字域：NoMount 救援规则清理未确认，停止卸载脚本并保留诊断记录。' >&2
        exit 1
    }
fi
[ -f "$MODDIR/common/private_payload.sh" ] && . "$MODDIR/common/private_payload.sh"
type luoshu_private_unmount_module_view >/dev/null 2>&1 && \
    luoshu_private_unmount_module_view "$MODDIR" >/dev/null 2>&1 || true
# Restore recorded component overrides before removing the runtime.
if [ -f "$MODDIR/common/google_font_fallback.sh" ]; then
    sh "$MODDIR/common/google_font_fallback.sh" restore-owned --json || \
        echo '字域：Google 字体兼容恢复未全部完成，恢复记录仍保留。' >&2
fi
# Undo the LuoShu VFS rule this module may have written into Hybrid Mount.
# Only restore when the live config still carries our marker; otherwise the
# user edited the file afterwards and their version wins.
for _hybrid_cfg in \
    /data/adb/hybrid-mount/config.toml \
    /data/adb/modules/hybrid_mount/config.toml \
    /data/adb/modules/meta-hybrid_mount/config.toml \
    /data/adb/modules/hybrid-mount/config.toml; do
    [ -f "$_hybrid_cfg" ] || continue
    command grep -q 'ziyu-luoshu-vfs-rule' "$_hybrid_cfg" 2>/dev/null || continue
    [ -f "$_hybrid_cfg.luoshu-backup" ] || continue
    cat "$_hybrid_cfg.luoshu-backup" > "$_hybrid_cfg" 2>/dev/null && \
        rm -f "$_hybrid_cfg.luoshu-backup"
done
. "$MODDIR/.luoshu-runtime/compat/v227/uninstall.sh"
