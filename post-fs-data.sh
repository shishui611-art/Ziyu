#!/system/bin/sh
# Root-manager and Meta backend selection is centralized in one mount runtime.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"

_soft_stage="$MODDIR/config/temporary-root-soft-reboot.conf"
_soft_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
if [ -n "$_soft_boot" ] &&
   [ "$(sed -n 's/^boot_id=//p' "$_soft_stage" 2>/dev/null | head -n1)" = "$_soft_boot" ] &&
   [ "$(sed -n 's/^state=//p' "$_soft_stage" 2>/dev/null | head -n1)" = failed ]; then
    printf '[TEMP-ROOT] refusing payload activation after failed soft reboot cleanup\n' >> "$MODDIR/logs/mount-backend.log"
    exit 1
fi

# Manual exclusions are checked before even activating the pending generation.
[ ! -f "$MODDIR/common/skip_mount_ownership.sh" ] || . "$MODDIR/common/skip_mount_ownership.sh"
if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || \
   { type ziyu_foreign_skip_mount_present >/dev/null 2>&1 && ziyu_foreign_skip_mount_present "$MODDIR"; }; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/mount_backend_runtime.sh" hook post-fs-data
    exit $?
fi

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
