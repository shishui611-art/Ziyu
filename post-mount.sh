#!/system/bin/sh
# KSU, SukiSU Ultra and APatch reach their verified self-mount stage here.
set +e
MODDIR="${0%/*}"
MODULE_DIR="$MODDIR"
mkdir -p "$MODDIR/logs" 2>/dev/null || true

RUNTIME="$MODDIR/common/mount_backend_runtime.sh"
V4_POST_MOUNT="$MODDIR/.ziyu-runtime/core/post-mount.sh"
LEGACY_MODE="$MODDIR/config/font_runtime_legacy_v14_4.conf"
UNIVERSAL_MODE="$MODDIR/config/universal-font-runtime.conf"

if [ ! -s "$UNIVERSAL_MODE" ] && [ ! -f "$LEGACY_MODE" ] && [ -f "$V4_POST_MOUNT" ]; then
    sh "$V4_POST_MOUNT"
    _pm_rc=$?
else
    [ -f "$RUNTIME" ] || { printf 'runtime-loader-missing\n' > "$MODDIR/config/mount-backend-failed"; exit 1; }
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$RUNTIME" hook post-mount \
        >> "$MODDIR/logs/mount-backend.log" 2>&1
    _pm_rc=$?
fi
if [ "$_pm_rc" = 0 ]; then
    # Check the tiny marker before spawning anything on a normal cold boot.
    _pm_soft_state=''
    if [ -s "$MODDIR/config/temporary-root-soft-reboot.conf" ]; then
        while IFS='=' read -r _pm_k _pm_v; do
            [ "$_pm_k" != state ] || _pm_soft_state=$_pm_v
        done < "$MODDIR/config/temporary-root-soft-reboot.conf"
    fi
    if [ "$_pm_soft_state" = ready ]; then
        if ! MODDIR="$MODDIR" sh "$MODDIR/common/soft_reboot_finish.sh" >> "$MODDIR/logs/soft-reboot.log" 2>&1; then
            printf '[SOFT-REBOOT] PPID reset did not pass; see pid-wrap-last.conf\n' >> "$MODDIR/logs/soft-reboot.log"
            _pm_rc=1
        elif [ -f "$MODDIR/common/soft_reboot_verify.sh" ]; then
            (MODDIR="$MODDIR" sh "$MODDIR/common/soft_reboot_verify.sh") </dev/null \
                >/dev/null 2>&1 &
        fi
    fi
fi
exit "$_pm_rc"
