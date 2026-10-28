#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MOD="$TMP/module"
VISIBLE="$TMP/visible"
mkdir -p "$MOD/common" "$MOD/config" "$MOD/logs" "$MOD/.luoshu-payload/.luoshu-runtime/deployment" "$MOD/.luoshu-payload/.luoshu-dynamic" "$MOD/.luoshu-payload/system/fonts" "$VISIBLE/data/fonts/files"
cp "$ROOT/common/universal_mount_runtime.sh" "$MOD/common/"
# The fake mount copies ordinary files on the host filesystem. Model verity
# checks here; the real seal implementation has its own tests and requires a
# supporting kernel/filesystem. Never make the production check optional.
cat > "$MOD/common/font_verity.sh" <<'SH'
#!/bin/sh
[ "$1" = check ] && [ -f "$2" ] || exit 1
[ "${FAIL_VERITY:-0}" != 1 ] || exit 1
printf 'fixture-verity-%s\n' "$(sha256sum "$2" | awk '{print $1}')"
SH
printf 'system-font-content\n' > "$MOD/.luoshu-payload/system/fonts/Fake.ttf"
printf '{}\n' > "$MOD/.luoshu-payload/.luoshu-runtime/deployment/deployment.json"
cat > "$MOD/common/universal_font_deployment.py" <<'PY'
#!/usr/bin/env python3
import sys
# Mount runtime only needs a zero/non-zero integrity verdict here; the real
# deployment validator is covered by universal_font_deployment_test.py.
raise SystemExit(0 if "--validate-payload-only" in sys.argv else 2)
PY
printf 'dynamic-font-content\n' > "$MOD/.luoshu-payload/.luoshu-dynamic/test.ttf"
HASH=$(sha256sum "$MOD/.luoshu-payload/.luoshu-dynamic/test.ttf" | awk '{print $1}')
printf '.luoshu-dynamic/test.ttf|/data/fonts/files/runtime.ttf|%s\n' "$HASH" > "$MOD/.luoshu-payload/.luoshu-runtime/deployment/dynamic-mounts.conf"
printf 'stock\n' > "$VISIBLE/data/fonts/files/runtime.ttf"
cat > "$MOD/config/universal-font-runtime.conf" <<'EOF'
state=active
pipeline=universal-font-deployment-v1
font=DemoFamily
deploymentId=sha256:test
payloadDigest=sha256:payload
EOF

cat > "$TMP/system-mount-ok.sh" <<EOF
#!/bin/sh
printf mounted > "$TMP/system-mounted"
exit 0
EOF
cat > "$TMP/system-rollback.sh" <<EOF
#!/bin/sh
printf rollback > "$TMP/system-rollback"
exit 0
EOF
cat > "$TMP/fake-mount.sh" <<'SH'
#!/bin/sh
if [ "${FAIL_DYNAMIC:-0}" = 1 ]; then exit 1; fi
case "$1" in
  --bind) cp "$2" "$3"; exit $? ;;
  -o)
    case "$2" in
      bind) cp "$3" "$4"; exit $? ;;
      *) exit 0 ;;
    esac
    ;;
esac
exit 0
SH
cat > "$TMP/fake-umount.sh" <<'SH'
#!/bin/sh
exit 0
SH
chmod 0755 "$TMP/system-mount-ok.sh" "$TMP/system-rollback.sh" "$TMP/fake-mount.sh" "$TMP/fake-umount.sh"

run_manager() {
  manager="$1"; hook="$2"
  rm -f "$TMP/system-mounted" "$TMP/system-rollback"
  rm -rf "$TMP/state"; mkdir -p "$TMP/state"
  printf 'stock\n' > "$VISIBLE/data/fonts/files/runtime.ttf"
  MODDIR="$MOD" MODULE_DIR="$MOD" CONFIG_DIR="$MOD/config" \
  LUOSHU_PYTHON="${LUOSHU_PYTHON:-python3}" \
  LUOSHU_UNIVERSAL_TEST_MANAGER="$manager" \
  LUOSHU_UNIVERSAL_TEST_ASSUME_RO=1 \
  LUOSHU_UNIVERSAL_TEST_VISIBLE_ROOT="$VISIBLE" \
  LUOSHU_UNIVERSAL_MOUNT_STATE_ROOT="$TMP/state" \
  LUOSHU_UNIVERSAL_TEST_SYSTEM_MOUNT_COMMAND="$TMP/system-mount-ok.sh" \
  LUOSHU_UNIVERSAL_TEST_SYSTEM_ROLLBACK_COMMAND="$TMP/system-rollback.sh" \
  LUOSHU_UNIVERSAL_MOUNT_COMMAND="$TMP/fake-mount.sh" \
  LUOSHU_UNIVERSAL_UMOUNT_COMMAND="$TMP/fake-umount.sh" \
    sh "$MOD/common/universal_mount_runtime.sh" hook "$hook"
  test -s "$TMP/system-mounted"
  cmp -s "$MOD/.luoshu-payload/.luoshu-dynamic/test.ttf" "$VISIBLE/data/fonts/files/runtime.ttf"
  grep -q "^manager=$manager$" "$MOD/config/universal-font-mount.conf"
  grep -q "^stage=$hook$" "$MOD/config/universal-font-mount.conf"
}

echo "PHASE7_MOUNT Magisk"
run_manager Magisk post-fs-data
echo "PHASE7_MOUNT KernelSU"
run_manager KernelSU post-mount
echo "PHASE7_MOUNT APatch"
run_manager APatch post-mount

echo "PHASE7_MOUNT wrong-hook"
# Wrong hook is a no-op, not a mount failure.
set +e
MODDIR="$MOD" MODULE_DIR="$MOD" CONFIG_DIR="$MOD/config" LUOSHU_PYTHON="${LUOSHU_PYTHON:-python3}" \
  LUOSHU_UNIVERSAL_TEST_MANAGER=KernelSU LUOSHU_UNIVERSAL_TEST_ASSUME_RO=1 \
  sh "$MOD/common/universal_mount_runtime.sh" hook post-fs-data >/dev/null 2>&1
RC=$?
set -e
test "$RC" -eq 2

echo "PHASE7_MOUNT dynamic-only"
# Dynamic-only payloads must not require the system self-mount path.
rm -rf "$MOD/.luoshu-payload/system"
rm -f "$TMP/system-mounted"
printf 'stock\n' > "$VISIBLE/data/fonts/files/runtime.ttf"
MODDIR="$MOD" MODULE_DIR="$MOD" CONFIG_DIR="$MOD/config" LUOSHU_PYTHON="${LUOSHU_PYTHON:-python3}" \
  LUOSHU_UNIVERSAL_TEST_MANAGER=Magisk \
  LUOSHU_UNIVERSAL_TEST_ASSUME_RO=1 \
  LUOSHU_UNIVERSAL_TEST_VISIBLE_ROOT="$VISIBLE" \
  LUOSHU_UNIVERSAL_MOUNT_STATE_ROOT="$TMP/state-dynamic-only" \
  LUOSHU_UNIVERSAL_TEST_SYSTEM_MOUNT_COMMAND="$TMP/system-mount-ok.sh" \
  LUOSHU_UNIVERSAL_TEST_SYSTEM_ROLLBACK_COMMAND="$TMP/system-rollback.sh" \
  LUOSHU_UNIVERSAL_MOUNT_COMMAND="$TMP/fake-mount.sh" \
  LUOSHU_UNIVERSAL_UMOUNT_COMMAND="$TMP/fake-umount.sh" \
    sh "$MOD/common/universal_mount_runtime.sh" hook post-fs-data
test ! -e "$TMP/system-mounted"
cmp -s "$MOD/.luoshu-payload/.luoshu-dynamic/test.ttf" "$VISIBLE/data/fonts/files/runtime.ttf"

# Restore a partition payload for the rollback transaction case.
mkdir -p "$MOD/.luoshu-payload/system/fonts"
printf 'system-font-content\n' > "$MOD/.luoshu-payload/system/fonts/Fake.ttf"

for failure in dynamic verity; do
echo "PHASE7_MOUNT rollback-$failure"
# A dynamic mount failure or rejected verity check must roll back the system
# payload transaction. An unsupported verifier never authorizes a bind mount.
rm -f "$TMP/system-rollback"
printf 'stock\n' > "$VISIBLE/data/fonts/files/runtime.ttf"
fail_dynamic=0; fail_verity=0
case "$failure" in dynamic) fail_dynamic=1 ;; verity) fail_verity=1 ;; esac
set +e
FAIL_DYNAMIC="$fail_dynamic" FAIL_VERITY="$fail_verity" MODDIR="$MOD" MODULE_DIR="$MOD" CONFIG_DIR="$MOD/config" LUOSHU_PYTHON="${LUOSHU_PYTHON:-python3}" \
  LUOSHU_UNIVERSAL_TEST_MANAGER=Magisk \
  LUOSHU_UNIVERSAL_TEST_ASSUME_RO=1 \
  LUOSHU_UNIVERSAL_TEST_VISIBLE_ROOT="$VISIBLE" \
  LUOSHU_UNIVERSAL_MOUNT_STATE_ROOT="$TMP/state-fail" \
  LUOSHU_UNIVERSAL_TEST_SYSTEM_MOUNT_COMMAND="$TMP/system-mount-ok.sh" \
  LUOSHU_UNIVERSAL_TEST_SYSTEM_ROLLBACK_COMMAND="$TMP/system-rollback.sh" \
  LUOSHU_UNIVERSAL_MOUNT_COMMAND="$TMP/fake-mount.sh" \
  LUOSHU_UNIVERSAL_UMOUNT_COMMAND="$TMP/fake-umount.sh" \
    sh "$MOD/common/universal_mount_runtime.sh" hook post-fs-data >/dev/null 2>&1
RC=$?
set -e
test "$RC" -eq 1
test -s "$TMP/system-rollback"
grep -q '^state=failed$' "$MOD/config/universal-font-mount.conf"
grep -q '^error=dynamic-mount-failed$' "$MOD/config/universal-font-mount.conf"
test "$(cat "$VISIBLE/data/fonts/files/runtime.ttf")" = stock
done

echo "universal_mount_runtime_test: PASS"
