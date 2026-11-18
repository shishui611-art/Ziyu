#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$ROOT/.runtime-work"
TMP=$(mktemp -d "$ROOT/.runtime-work/health-mount-test.XXXXXX")
TMP=$(CDPATH= cd -- "$TMP" && pwd -P)
case "$TMP" in "$ROOT"/.runtime-work/health-mount-test.*) ;; *) exit 1 ;; esac
trap 'rm -rf "$TMP"' EXIT
MOD="$TMP/adb/modules/ziyu"
mkdir -p "$MOD/common" "$MOD/config" "$MOD/logs" "$TMP/bin"
for helper in ziyu_health.sh root_manager_detection.sh mount_backend_details.sh mount_backend_state.sh; do
    cp "$ROOT/common/$helper" "$MOD/common/$helper"
done
printf 'id=ziyu\nversion=test\nversionCode=1\n' > "$MOD/module.prop"
printf 'DemoFont\n' > "$MOD/config/active_font.conf"

# Only the boot ID is synthetic. Run the actual status reader and CLI entry.
cat > "$TMP/bin/cat" <<'CAT'
#!/bin/sh
if [ "$#" = 1 ] && [ "$1" = /proc/sys/kernel/random/boot_id ]; then
    printf 'health-mount-test-boot\n'
else
    exec /usr/bin/cat "$@"
fi
CAT
chmod 0755 "$TMP/bin/cat"
export PATH="$TMP/bin:$PATH"

check_status() {
    local preference="$1" selected="$2" active="$3" verification="$4" provider="$5" expected="$6"
    printf 'state=mounted\nbackend=self-directory-mirror\n' > "$MOD/config/self-mount.conf"
    cat > "$MOD/config/mount-backend.conf" <<STATE
schema=ziyu-mount-backend-v3
boot_id=health-mount-test-boot
preferred_backend=$preference
selected_backend=$selected
active_backend=$active
verification=$verification
provider_id=$provider
STATE
    MODDIR="$MOD" ZIYU_ADB_ROOT="$TMP/adb" ZIYU_ROOT_MANAGER=TestRoot \
        sh "$ROOT/system/bin/ziyu-health" report > "$TMP/status"
    grep -qx "mountEngine=$expected" "$TMP/status" || { cat "$TMP/status"; exit 1; }
    if [ "$active" != self ]; then
        grep -qx 'selfMountBackend=none' "$TMP/status"
    fi
}

check_status manager manager none pending none '管理器挂载 · 待验证'
check_status manager manager manager passed none '管理器挂载'
grep -qx 'selfMountState=not-active' "$TMP/status"
check_status manager manager none failed none '管理器挂载失败'
check_status auto self self passed none '自动 · 字域自挂载'
check_status auto external external passed magisk-magic-mount '自动 · Magisk 内置 Magic Mount'
check_status auto external external passed nomount '自动 · NoMount 元模块'
printf 'Health mount labels and stale self-mount isolation passed (6 cases)\n'
