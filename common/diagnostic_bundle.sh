#!/system/bin/sh
# One-file diagnostic bundle for failures on devices without adb.
# Usage: sh common/diagnostic_bundle.sh dump [reason]
# Writes a plain-text bundle under logs/diagnostics/ and copies it to
# /sdcard/Ziyu/reports/ when public storage is mounted, so the file can be
# shared from any file manager without a root shell.
set +e

MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
MODULE_DIR="$MODDIR"
_REASON="${1:-manual}"

_diag_stamp() { date '+%Y%m%d-%H%M%S' 2>/dev/null || echo unknown; }
_diag_boot() { printf '%s\n' "${LUOSHU_DIAG_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}"; }
_diag_public_root() { printf '%s\n' "${LUOSHU_DIAG_PUBLIC_DIR:-/sdcard/Ziyu/reports}"; }
_diag_public_ready() {
    if [ -n "${LUOSHU_DIAG_PUBLIC_DIR:-}" ]; then
        [ -d "${LUOSHU_DIAG_PUBLIC_DIR%/*}" ] || mkdir -p "${LUOSHU_DIAG_PUBLIC_DIR%/*}" 2>/dev/null
        [ -d "${LUOSHU_DIAG_PUBLIC_DIR%/*}" ]
    else
        [ -d /sdcard ]
    fi
}

_diag_emit_file() {
    # _diag_emit_file <label> <path> [tail-lines]
    _def_label="$1"; _def_path="$2"; _def_tail="${3:-0}"
    [ -s "$_def_path" ] || { printf '\n[%s]\n(absent)\n' "$_def_label"; return 0; }
    printf '\n[%s] (%s)\n' "$_def_label" "$_def_path"
    if [ "$_def_tail" -gt 0 ] 2>/dev/null; then
        tail -n "$_def_tail" "$_def_path" 2>/dev/null
    else
        cat "$_def_path" 2>/dev/null
    fi
}

_diag_dump_once() {
    mkdir -p "$MODDIR/logs/diagnostics" 2>/dev/null || return 1
    _diag_out="$MODDIR/logs/diagnostics/diag-$(date '+%Y%m%d-%H%M%S' 2>/dev/null || echo unknown)-$_REASON.txt"
    _diag_tmp="$_diag_out.tmp.$$"
    {
        printf 'report=ziyu-diagnostic-bundle-v1\n'
        printf 'reason=%s\n' "$_REASON"
        printf 'time=%s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)"
        printf 'bootId=%s\n' "$(_diag_boot)"
        printf 'uptime=%s\n' "$(cat /proc/uptime 2>/dev/null | awk '{print $1}')"
        printf 'moduleDir=%s\n' "$MODDIR"
        printf 'sdk=%s release=%s model=%s os=%s\n' \
            "$(getprop ro.build.version.sdk 2>/dev/null)" \
            "$(getprop ro.build.version.release 2>/dev/null)" \
            "$(getprop ro.product.model 2>/dev/null)" \
            "$(getprop ro.build.version.oplusrom 2>/dev/null || getprop ro.build.display.id 2>/dev/null)"

        printf '\n[module.prop]\n'
        cat "$MODDIR/module.prop" 2>/dev/null

        printf '\n[live root detection]\n'
        [ ! -f "$MODDIR/common/root_manager_detection.sh" ] || \
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" . "$MODDIR/common/root_manager_detection.sh"
        type luoshu_detect_root_manager >/dev/null 2>&1 && \
            luoshu_detect_root_manager 2>/dev/null

        printf '\n[live mount engine / meta detection]\n'
        [ ! -f "$MODDIR/common/meta_mount_detection.sh" ] || \
            . "$MODDIR/common/meta_mount_detection.sh"
        if type luoshu_meta_mount_detect >/dev/null 2>&1; then
            luoshu_meta_mount_detect >/dev/null 2>&1
            luoshu_meta_detection_info 2>/dev/null
            printf 'META_HYBRID_ROUTE_REASON=%s\n' "${META_HYBRID_ROUTE_REASON:-}"
        fi

        printf '\n[health]\n'
        sh "$MODDIR/system/bin/luoshu-health" 2>/dev/null || echo '(health unavailable)'

        printf '\n[mount snapshot of this namespace]\n'
        grep -E 'fonts|font' /proc/self/mountinfo 2>/dev/null || echo '(no font mounts visible here)'

        printf '\n[payload files]\n'
        find "$MODDIR/.luoshu-payload" -type f 2>/dev/null | head -n 60
        _diag_emit_file 'payload manifest' "$MODDIR/config/font-payload-manifest.conf"
    } > "$_diag_tmp" 2>/dev/null

    # Config and log sections appended separately: none of these may abort the dump.
    {
        _diag_emit_file 'mount-backend.conf' "$MODDIR/config/mount-backend.conf"
        _diag_emit_file 'mount-backend-preference.conf' "$MODDIR/config/mount-backend-preference.conf"
        _diag_emit_file 'device-font-load-verification.conf' "$MODDIR/config/device-font-load-verification.conf"
        _diag_emit_file 'self-mount.conf' "$MODDIR/config/self-mount.conf"
        _diag_emit_file 'active_font.conf' "$MODDIR/config/active_font.conf"
        _diag_emit_file 'device-font-engine.conf' "$MODDIR/config/device-font-engine.conf"
        _diag_emit_file 'font-runtime legacy conf' "$MODDIR/config/font_runtime_legacy_v14_4.conf"
        _diag_emit_file 'mount-backend.log' "$MODDIR/logs/mount-backend.log" 600
        _diag_emit_file 'fontswitch.log' "$MODDIR/logs/fontswitch.log" 400
        _diag_emit_file 'service-legacy-v14.4.log' "$MODDIR/logs/service-legacy-v14.4.log" 400
        _diag_emit_file 'runtime-report.log' "$MODDIR/logs/runtime-report.log" 200
        _diag_emit_file 'app-install.log' "$MODDIR/logs/app-install.log" 120
    } >> "$_diag_tmp" 2>/dev/null

    mv -f "$_diag_tmp" "$_diag_out" 2>/dev/null || { rm -f "$_diag_tmp"; return 1; }
    chmod 0644 "$_diag_out" 2>/dev/null || true

    # Best-effort public copy for sharing without adb.
    if _diag_public_ready; then
        _diag_public_dir="$(_diag_public_root)"
        mkdir -p "$_diag_public_dir" 2>/dev/null && \
            cp -f "$_diag_out" "$_diag_public_dir/" 2>/dev/null && \
            chmod 0644 "$_diag_public_dir/$(basename "$_diag_out")" 2>/dev/null
    fi
    printf '%s\n' "$_diag_out"
    return 0
}

case "${1:-dump}" in
    dump|manual)
        _REASON="${2:-manual}"
        _diag_dump_once
        ;;
    dump-once-per-boot)
        # Auto hook: at most one automatic bundle per boot and reason.
        _REASON="${2:-auto}"
        _diag_mark="$MODDIR/logs/diagnostics/.auto-$(echo "$_REASON" | tr -c 'A-Za-z0-9._-' '_')-$(_diag_boot | cut -c1-8)"
        [ -e "$_diag_mark" ] && exit 0
        mkdir -p "$MODDIR/logs/diagnostics" 2>/dev/null
        _diag_out=$(_diag_dump_once) || exit 1
        : > "$_diag_mark" 2>/dev/null
        printf '%s\n' "$_diag_out"
        ;;
    *)
        printf 'usage: diagnostic_bundle.sh dump [reason] | dump-once-per-boot [reason]\n' >&2
        exit 2
        ;;
esac
