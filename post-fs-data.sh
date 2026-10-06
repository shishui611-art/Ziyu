#!/system/bin/sh
# Root-manager and Meta backend selection is centralized in one mount runtime.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"

# Activate a previously prepared payload before exposing any module files.
UNIVERSAL_NEXT_STATE="$MODDIR/config/universal-font-next.conf"
if [ -s "$UNIVERSAL_NEXT_STATE" ]; then
    NEXT_BOOT_HELPER="$MODDIR/common/universal_next_boot.sh"
    [ -f "$NEXT_BOOT_HELPER" ] && . "$NEXT_BOOT_HELPER"
    type universal_font_next_boot_activate >/dev/null 2>&1 && \
        universal_font_next_boot_activate >/dev/null 2>&1 || true
else
    NEXT_BOOT_HELPER="$MODDIR/common/next_boot_payload.sh"
    [ -f "$NEXT_BOOT_HELPER" ] && . "$NEXT_BOOT_HELPER"
    type luoshu_next_boot_activate >/dev/null 2>&1 && \
        luoshu_next_boot_activate >/dev/null 2>&1 || true
fi

RUNTIME="$MODDIR/common/mount_backend_runtime.sh"
V4_POST_FS="$MODDIR/.luoshu-runtime/core/post-fs-data.sh"
LEGACY_MODE="$MODDIR/config/font_runtime_legacy_v14_4.conf"
UNIVERSAL_MODE="$MODDIR/config/universal-font-runtime.conf"

# Keep the verified v2.2.7 initializer for the normal v4 payload, then let its
# core wrapper hand off to the same selector used by compatibility runtimes.
if [ ! -s "$UNIVERSAL_MODE" ] && [ ! -f "$LEGACY_MODE" ] && [ -f "$V4_POST_FS" ]; then
    exec sh "$V4_POST_FS"
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
exit "$_lpf_rc"
