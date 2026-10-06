#!/system/bin/sh
# KSU, SukiSU Ultra and APatch reach their verified self-mount stage here.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
RUNTIME="$MODDIR/common/mount_backend_runtime.sh"
V4_POST_MOUNT="$MODDIR/.luoshu-runtime/core/post-mount.sh"
LEGACY_MODE="$MODDIR/config/font_runtime_legacy_v14_4.conf"
UNIVERSAL_MODE="$MODDIR/config/universal-font-runtime.conf"

if [ ! -s "$UNIVERSAL_MODE" ] && [ ! -f "$LEGACY_MODE" ] && [ -f "$V4_POST_MOUNT" ]; then
    exec sh "$V4_POST_MOUNT"
fi

mkdir -p "$MODDIR/logs" "$MODDIR/config" 2>/dev/null || true
if [ -f "$RUNTIME" ]; then
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$RUNTIME" hook post-mount \
        >> "$MODDIR/logs/mount-backend.log" 2>&1
    exit $?
fi

printf 'runtime-loader-missing\n' > "$MODDIR/config/mount-backend-failed" 2>/dev/null || true
exit 1
