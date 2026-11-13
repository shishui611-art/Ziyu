#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t ziyu-soft-reboot-pid)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MODULE="$TMP/module"
CONFIG="$MODULE/config"
mkdir -p "$MODULE/common" "$MODULE/logs" "$CONFIG" "$TMP/bin"
cp "$ROOT/common/soft_reboot_finish.sh" "$ROOT/common/soft_reboot_verify.sh" "$MODULE/common/"
BOOT_ID=soft-reboot-test-boot

cat > "$MODULE/common/pid_reload.sh" <<'EOF_WRAP_OK'
#!/bin/sh
printf 'state=wrapped\nrestored=1\nobserved_pid=321\n'
exit 0
EOF_WRAP_OK
cat > "$CONFIG/temporary-root-soft-reboot.conf" <<EOF_READY
boot_id=$BOOT_ID
state=ready
zygote_service=zygote64
old_zygote_pid=9000
EOF_READY
ZIYU_TEST_BOOT_ID="$BOOT_ID" MODDIR="$MODULE" sh "$MODULE/common/soft_reboot_finish.sh"
grep -q '^state=reload-prepared$' "$CONFIG/temporary-root-soft-reboot.conf"
grep -q '^pid_wrap_state=wrapped$' "$CONFIG/temporary-root-soft-reboot.conf"

cat > "$TMP/bin/getprop" <<'EOF_GETPROP'
#!/bin/sh
case "${1:-}" in
    sys.boot_completed) printf '1\n' ;;
    init.svc.zygote64) printf 'running\n' ;;
esac
EOF_GETPROP
cat > "$TMP/bin/pidof" <<'EOF_PIDOF'
#!/bin/sh
[ "${1:-}" = zygote64 ] && printf '%s\n' "${ZIYU_TEST_ZYGOTE_PID:-3000}"
EOF_PIDOF
chmod 0755 "$TMP/bin/getprop" "$TMP/bin/pidof"
PATH="$TMP/bin:$PATH" ZIYU_TEST_BOOT_ID="$BOOT_ID" ZIYU_TEST_ZYGOTE_PID=3000 MODDIR="$MODULE" \
    sh "$MODULE/common/soft_reboot_verify.sh"
grep -q '^state=ppid-reset-verified$' "$CONFIG/temporary-root-soft-reboot.conf"
grep -q '^new_zygote_pid=3000$' "$CONFIG/temporary-root-soft-reboot.conf"

cat > "$CONFIG/temporary-root-soft-reboot.conf" <<EOF_HIGH
boot_id=$BOOT_ID
state=reload-prepared
pid_wrap_rc=0
pid_wrap_state=wrapped
zygote_service=zygote64
old_zygote_pid=9000
EOF_HIGH
if PATH="$TMP/bin:$PATH" ZIYU_TEST_BOOT_ID="$BOOT_ID" ZIYU_TEST_ZYGOTE_PID=6000 MODDIR="$MODULE" \
   sh "$MODULE/common/soft_reboot_verify.sh"; then
    echo 'high zygote PID passed reset verification' >&2
    exit 1
fi
grep -q '^state=ppid-reset-failed$' "$CONFIG/temporary-root-soft-reboot.conf"
grep -q '^reason=zygote-pid-above-4999$' "$CONFIG/temporary-root-soft-reboot.conf"

cat > "$MODULE/common/pid_reload.sh" <<'EOF_WRAP_FAIL'
#!/bin/sh
printf 'state=unavailable\n'
exit 8
EOF_WRAP_FAIL
cat > "$CONFIG/temporary-root-soft-reboot.conf" <<EOF_FAIL
boot_id=$BOOT_ID
state=ready
zygote_service=zygote64
old_zygote_pid=9000
EOF_FAIL
if ZIYU_TEST_BOOT_ID="$BOOT_ID" MODDIR="$MODULE" sh "$MODULE/common/soft_reboot_finish.sh"; then
    echo 'failed PID wrap was accepted' >&2
    exit 1
fi
grep -q '^state=ppid-reset-failed$' "$CONFIG/temporary-root-soft-reboot.conf"

echo 'Kernel soft reboot reports success only after PID wrap and zygote parent verification.'
