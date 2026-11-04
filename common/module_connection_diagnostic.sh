#!/system/bin/sh
# Standalone collector: no font apply, mount/unmount, repair, reboot or upload.
# Run: su -c 'sh /sdcard/Download/ziyu-module-handshake.sh'
set +e
umask 077
if [ "$(id -u 2>/dev/null)" != 0 ]; then
    printf '%s\n' '请用 Root 运行：' "su -c 'sh /sdcard/Download/ziyu-module-handshake.sh'"
    exit 3
fi
MOD=''
for candidate in /data/adb/modules/ziyu /data/adb/modules/LuoShu; do
    [ -r "$candidate/module.prop" ] || continue
    module_id=$(sed -n 's/^id=//p' "$candidate/module.prop" | head -n 1 | tr -d '\r')
    case "$module_id" in ziyu|LuoShu) MOD=$candidate; break ;; esac
done
[ -n "$MOD" ] || { printf '%s\n' '没有找到已安装的字域模块。'; exit 4; }
# Report lives beside this standalone script, never in /data/adb or a fixed path.
case "$0" in */*) script_parent=${0%/*} ;; *) script_parent=. ;; esac
OUT_BASE=$(CDPATH= cd -- "$script_parent" 2>/dev/null && pwd) || exit 5
run_name="Ziyu-handshake-$(date '+%Y%m%d-%H%M%S')-$$"
OUT="$OUT_BASE/.$run_name.work"
mkdir "$OUT" || { printf '%s\n' '脚本目录不可写，请放到 Download 后重试。'; exit 5; }
summary="$OUT_BASE/$run_name.txt"
(set -C; : > "$summary") 2>/dev/null || { rmdir "$OUT"; exit 5; }
TIMEOUT=''
if command -v timeout >/dev/null 2>&1; then TIMEOUT=timeout
elif [ -x /system/bin/toybox ] && /system/bin/toybox timeout 1 sh -c 'exit 0' >/dev/null 2>&1; then TIMEOUT=toybox
fi
[ -n "$TIMEOUT" ] || {
    printf '%s\n' '系统缺少限时命令，未执行模块命令。' > "$summary"
    rmdir "$OUT"
    printf '报告：%s\n' "$summary"; exit 6
}
{
    printf 'schema=ziyu-module-handshake-report-v1\ncollector_version=1.4.98\n'
    printf 'time=%s\nmodule_dir=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$MOD"
    printf 'context=%s\n' "$(id 2>/dev/null)"
    printf 'selinux_context=%s\n' "$(cat /proc/self/attr/current 2>/dev/null)"
    printf 'mount_namespace=%s\npid1_mount_namespace=%s\n' "$(readlink /proc/self/ns/mnt 2>/dev/null)" "$(readlink /proc/1/ns/mnt 2>/dev/null)"
    printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    printf 'sdk=%s\nrelease=%s\n' "$(getprop ro.build.version.sdk)" "$(getprop ro.build.version.release)"
    printf 'model=%s\nkernel=%s\n' "$(getprop ro.product.model)" "$(uname -r)"
    printf 'PATH=%s\ninherited_MODDIR=%s\ninherited_MODULE_DIR=%s\ninherited_MODPATH=%s\n' "$PATH" "${MODDIR:-}" "${MODULE_DIR:-}" "${MODPATH:-}"
    printf 'scope=terminal Root context; cannot prove App su pipe or per-App Root policy\n'
    printf 'operations=handshake and installed status only; no font apply/mount/repair/reboot/upload\n'
    printf 'legacy_status_note=installed status may reconcile existing task/reboot bookkeeping\n'
} > "$summary"
stage() {
    label=$1; limit=$2; shift 2
    printf '正在检查：%s\n' "$label"
    start=$(date +%s)
    (
        # Per-file cap prevents a verbose broken shell filling shared storage.
        ulimit -f 2048 2>/dev/null || exit 7
        if [ "$TIMEOUT" = toybox ]; then /system/bin/toybox timeout -s KILL "$limit" "$@"
        else timeout -s KILL "$limit" "$@"; fi
    ) > "$OUT/$label.stdout.txt" 2> "$OUT/$label.stderr.txt"
    code=$?
    finish=$(date +%s)
    {
        printf '\n[%s]\nexit_code=%s\nduration_seconds=%s\n' "$label" "$code" "$((finish-start))"
        printf 'stdout_bytes=%s\nstderr_bytes=%s\n' "$(wc -c < "$OUT/$label.stdout.txt")" "$(wc -c < "$OUT/$label.stderr.txt")"
        printf 'stdout_head_hex='; head -c 160 "$OUT/$label.stdout.txt" | od -An -tx1 | tr '\n' ' '; printf '\n'
        printf 'stderr_tail:\n'; tail -c 2048 "$OUT/$label.stderr.txt"; printf '\n'
        case "$code" in 124|137) printf 'timeout_or_kill=true\n' ;; 153) printf 'output_limit_hit=true\n' ;; esac
        printf '\n[%s:stdout]\n' "$label"; cat "$OUT/$label.stdout.txt"; printf '\n'
        printf '\n[%s:stderr]\n' "$label"; cat "$OUT/$label.stderr.txt"; printf '\n'
    } >> "$summary"
    return "$code"
}
stage root-channels 3 sh -c 'printf "stdout=ready\n"; printf "stderr=ready\n" >&2'
stage handshake 8 sh -c 'unset MODPATH MODULE_DIR MODDIR; export MODDIR="$1" MODULE_DIR="$1"; exec sh "$1/common/app_handshake.sh" probe-v2' sh "$MOD"
# Keep the original environment in this stage, matching a normal existing call.
stage installed-status 20 sh -c 'exec sh "$1/common/app_bridge.sh" status' sh "$MOD"
STATUS_CODE=$?
stage clean-status 20 sh -c 'unset MODPATH MODULE_DIR MODDIR PYTHONHOME PYTHONPATH; export MODDIR="$1" MODULE_DIR="$1"; exec sh "$1/common/app_bridge.sh" status' sh "$MOD"
if [ -r "$MOD/common/app_status.sh" ]; then
    stage typed-status 20 sh -c 'unset MODPATH MODULE_DIR MODDIR; export MODDIR="$1" MODULE_DIR="$1"; exec sh "$1/common/app_status.sh" kv' sh "$MOD"
fi
# Trace only a bad legacy result, once. Byte/elapsed limits remain in force.
if [ "$STATUS_CODE" -ne 0 ] || ! grep -q '"status"[[:space:]]*:[[:space:]]*"ok"' "$OUT/installed-status.stdout.txt"; then
    stage status-trace 12 sh -c 'export PS4="+ZIYU: "; exec sh -x "$1/common/app_bridge.sh" status' sh "$MOD"
fi
{
    printf '\n[module-files]\n'
    for rel in module.prop disable remove common/app_handshake.sh common/app_bridge.sh common/app_status.sh common/root_manager_detection.sh common/mount_backend_state.sh common/font_live_state.sh common/legacy_identity.sh common/util_functions.sh common/util_functions_core.sh common/mount_compat.sh common/mount_compat_base.sh common/device_font_payload_runtime.sh common/device_font_payload_bridge.sh common/font_config_partitions.sh; do
        printf '\nfile=%s\n' "$rel"
        if [ -e "$MOD/$rel" ]; then
            ls -ldZ "$MOD/$rel" 2>/dev/null
            [ ! -f "$MOD/$rel" ] || sha256sum "$MOD/$rel" 2>/dev/null
        else printf 'missing\n'; fi
    done
    printf '\n[loaded-runtime-source]\n'
    # This fixed runtime file is source code, not user settings. Capture it as
    # data only; never source it in the collector or attempt an automatic repair.
    runtime_source="$MOD/common/device_font_payload_runtime.sh"
    if [ -r "$runtime_source" ]; then
        printf 'bytes=%s\nfirst_bytes_hex=' "$(wc -c < "$runtime_source")"
        head -c 64 "$runtime_source" | od -An -tx1 | tr '\n' ' '; printf '\n'
        head -c 32768 "$runtime_source"; printf '\n'
    else printf 'missing-or-unreadable\n'; fi
    printf '\n[module-properties]\n'; head -c 4096 "$MOD/module.prop"
    printf '\n[pending-properties]\n'; head -c 4096 /data/adb/modules_update/ziyu/module.prop 2>/dev/null
    printf '\n[state-records]\n'
    for rel in active_font.conf mount-backend.conf font-live.conf font-payload-next.conf device-font-load-verification.conf universal-font-runtime-verification.conf switch_task.conf axes_task.conf boot-safety-current-boot.conf; do
        printf '\n[%s]\n' "$rel"
        if [ -r "$MOD/config/$rel" ]; then head -c 8192 "$MOD/config/$rel"; printf '\n'; else printf 'missing\n'; fi
    done
    printf '\n[app-root-policy]\nnot_collected; check Root permission and hiding policy for the Ziyu App separately\n'
    printf '\n[return-file]\nThis TXT includes stdout/stderr for every stage; send this file.\n'
} >> "$summary"
# Remove only exact temporary files created by this invocation; no recursive delete.
for label in root-channels handshake installed-status clean-status typed-status status-trace; do
    rm -f "$OUT/$label.stdout.txt" "$OUT/$label.stderr.txt"
done
rmdir "$OUT" 2>/dev/null || true
printf '\n检查结束。报告保存在脚本同一目录，请发回这个文件：\n%s\n' "$summary"
printf '%s\n' '无需重启。报告可能包含字体名和路径，请先确认再分享。'
exit 0
