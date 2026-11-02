#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "${0%/*}" 2>/dev/null && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." 2>/dev/null && pwd)
ATOMIC_SCRIPT=${ATOMIC_SCRIPT:-$REPO_ROOT/common/mount_self_atomic.sh}
BACKEND_SCRIPT=${BACKEND_SCRIPT:-$REPO_ROOT/common/mount_self_backend.sh}
FINAL_SCRIPT=${FINAL_SCRIPT:-$REPO_ROOT/common/font_runtime_mount.sh}
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT INT TERM

fail() {
    echo "self-mount atomic test failed: $*" >&2
    exit 1
}

setup_case() {
    CASE_ROOT="$TEST_ROOT/$1"
    MODULE_DIR="$CASE_ROOT/module"
    ZIYU_MOUNT_MODDIR="$MODULE_DIR"
    ZIYU_SELF_MOUNT_STATE_ROOT="$CASE_ROOT/state"
    ZIYU_SELF_MOUNT_VISIBLE_ROOT="$CASE_ROOT/root"
    ZIYU_SELF_PID1_ROOT=/
    ZIYU_SELF_MOUNTINFO="$CASE_ROOT/mountinfo"
    ZIYU_PID1_MOUNTINFO="$CASE_ROOT/mountinfo"
    export MODULE_DIR ZIYU_MOUNT_MODDIR ZIYU_SELF_MOUNT_STATE_ROOT \
        ZIYU_SELF_MOUNT_VISIBLE_ROOT ZIYU_SELF_PID1_ROOT \
        ZIYU_SELF_MOUNTINFO ZIYU_PID1_MOUNTINFO
    mkdir -p "$MODULE_DIR/config" "$MODULE_DIR/logs" "$CASE_ROOT/root/system/fonts" \
        "$CASE_ROOT/root/system/etc" "$CASE_ROOT/state"
    : > "$ZIYU_SELF_MOUNTINFO"
    printf 'custom\n' > "$MODULE_DIR/config/active_font.conf"
    : > "$CASE_ROOT/unmount.log"
    FAIL_OVERLAY=''
    FAIL_BIND=''
    ZIYU_TEST_PRIVATE_PAYLOAD_ROOT=''
}

_ziyu_self_module() { printf '%s\n' "$MODULE_DIR"; }
_ziyu_self_state_root() { printf '%s\n' "$ZIYU_SELF_MOUNT_STATE_ROOT"; }
_ziyu_self_log() { printf '%s\n' "$*" >> "$MODULE_DIR/logs/self-mount.log"; }
_ziyu_self_state_write() {
    {
        printf 'state=%s\n' "$1"
        printf 'backend=%s\n' "$2"
        printf 'mounted=%s\n' "$3"
        printf 'failed=%s\n' "$4"
    } > "$MODULE_DIR/config/self-mount.conf"
}
_ziyu_self_state_value() {
    sed -n "s/^${1}=//p" "$MODULE_DIR/config/self-mount.conf" 2>/dev/null | head -n1
}
ziyu_payload_partitions() { printf '%s\n' 'system product system_ext'; }
_lfrp_partitions() { ziyu_payload_partitions; }
_lfrp_payload_root() {
    printf '%s\n' "${ZIYU_TEST_PRIVATE_PAYLOAD_ROOT:-$MODULE_DIR}"
}
_ziyu_partition_root() {
    case "$1" in
        system)
            test -d "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/system" || return 1
            printf '%s/system\n' "$ZIYU_SELF_MOUNT_VISIBLE_ROOT"
            ;;
        product|system_ext)
            test -d "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/$1" || return 1
            printf '%s/%s\n' "$ZIYU_SELF_MOUNT_VISIBLE_ROOT" "$1"
            ;;
        *) return 1 ;;
    esac
}
_ziyu_overlay_mount_dir() {
    test "${FAIL_OVERLAY:-}" != all && test "${FAIL_OVERLAY:-}" != "$3" || return 1
    cp -R "$1/." "$2/"
    printf '100 1 0:1 / %s rw - ext4 %s rw\n' "$2" "$1" >> "$ZIYU_SELF_MOUNTINFO"
}
_ziyu_test_mountinfo_add() {
    printf '100 1 0:1 / %s rw - ext4 %s rw\n' "$2" "$1" >> "$ZIYU_SELF_MOUNTINFO"
}
_ziyu_test_mountinfo_remove() {
    _test_mountinfo_tmp="${ZIYU_SELF_MOUNTINFO}.tmp"
    awk -v target="$1" '$5 != target { print }' "$ZIYU_SELF_MOUNTINFO" > "$_test_mountinfo_tmp" && \
        mv -f "$_test_mountinfo_tmp" "$ZIYU_SELF_MOUNTINFO"
}
_ziyu_mount_cmd() {
    if test "$1" = --make-private; then
        return 0
    fi
    if test "$1" = -o && test "$2" = remount,bind,ro; then
        _test_mountinfo_tmp="${ZIYU_SELF_MOUNTINFO}.tmp"
        awk -v target="$3" 'BEGIN { OFS=" " } $5 == target { $6="ro" } { print }' \
            "$ZIYU_SELF_MOUNTINFO" > "$_test_mountinfo_tmp" && \
            mv -f "$_test_mountinfo_tmp" "$ZIYU_SELF_MOUNTINFO"
        return 0
    fi
    test "$1" = -o && test "$2" = bind || return 1
    test "${FAIL_BIND:-}" != "$4" || return 1
    if test -d "$3"; then
        _test_stock="${4}.ziyu-test-stock"
        rm -rf "$_test_stock"
        if test -d "$4"; then mv "$4" "$_test_stock"; else mkdir -p "$_test_stock"; fi
        mkdir -p "$4"
        cp -a "$3/." "$4/"
    else
        _test_stock="${4}.ziyu-test-stock"
        rm -f "$_test_stock"
        if test -e "$4"; then cp -p "$4" "$_test_stock"; fi
        cp -f "$3" "$4"
    fi
    _ziyu_test_mountinfo_add "$3" "$4"
}
_ziyu_umount_cmd() {
    _test_target="$1"
    _test_stock="${_test_target}.ziyu-test-stock"
    if test -d "$_test_stock"; then
        rm -rf "$_test_target"
        mv "$_test_stock" "$_test_target"
    elif test -f "$_test_stock"; then
        mv -f "$_test_stock" "$_test_target"
    fi
    _ziyu_test_mountinfo_remove "$_test_target"
    printf '%s\n' "$_test_target" >> "$CASE_ROOT/unmount.log"
    return 0
}
ziyu_mount_record() {
    printf '%s|%s\n' "$1" "$2" > "$MODULE_DIR/config/mount-record.txt"
}

# The backend resolves sibling helpers through MODULE_DIR while it is sourced.
# Point it at the repository here; setup_case replaces it with the fixture for
# each test transaction below.
MODULE_DIR="$REPO_ROOT"
. "$BACKEND_SCRIPT"
. "$ATOMIC_SCRIPT"
[ -z "$FINAL_SCRIPT" ] || . "$FINAL_SCRIPT"
_ziyu_mirror_context() { return 0; }
# Every mount in this fixture is simulated, including the read-only remount.
# A BusyBox installed on a CI runner must never bypass the fake mount function.
_ziyu_overlay_select_loop_mounter() { return 1; }
# Model the lower bind as a stock-tree snapshot. The transaction test does not
# create real mounts or mutate the host mount namespace.
_ziyu_capture_lower_dir() {
    _test_lower_target="$1"
    _test_lower_key="$2"
    _test_lower_state=$(_ziyu_self_state_root)
    _test_lower="$_test_lower_state/lower/$_test_lower_key"
    mkdir -p "$_test_lower" || return 1
    cp -R "$_test_lower_target/." "$_test_lower/" || return 1
    printf '%s\n' "$_test_lower" >> "$_lsme_mount_list"
}
# The production backend owns the OverlayFS helper. Keep tests deterministic.
_ziyu_overlay_mount_dir() {
    test "${FAIL_OVERLAY:-}" != all && test "${FAIL_OVERLAY:-}" != "$3" || return 1
    cp -R "$1/." "$2/"
}
CURRENT_BOOT_ID=test-boot
_ziyu_atomic_boot_id() { printf '%s\n' "$CURRENT_BOOT_ID"; }

# The test transaction journal stands in for /proc/1/mountinfo ownership. A
# journaled target belongs to this fixture until the fake unmount removes it.
_ziyu_atomic_mountinfo_target_present() {
    grep -Fxq "$1" "$ZIYU_SELF_MOUNT_STATE_ROOT/mounts.list"
}
_ziyu_atomic_mountinfo_owned_target() {
    grep -Fxq "$1" "$ZIYU_SELF_MOUNT_STATE_ROOT/mounts.list"
}

setup_case success
mkdir -p "$MODULE_DIR/system/fonts" "$MODULE_DIR/system/etc"
printf 'new-font\n' > "$MODULE_DIR/system/fonts/Roboto.ttf"
printf 'new-xml\n' > "$MODULE_DIR/system/etc/fonts.xml"
printf 'stock-font\n' > "$CASE_ROOT/root/system/fonts/Roboto.ttf"
printf 'stock-xml\n' > "$CASE_ROOT/root/system/etc/fonts.xml"
ziyu_self_mount_ensure || fail 'complete overlay transaction returned failure'
grep -q '^state=mounted$' "$MODULE_DIR/config/self-mount.conf" || fail 'success was not recorded as mounted'
test "$(wc -l < "$MODULE_DIR/config/self-mount-required.conf" | tr -d ' ')" -eq 2 || fail 'required manifest is incomplete'
ziyu_mount_verify_active custom || fail 'strict verifier rejected a complete transaction'
grep -q '^verified|' "$MODULE_DIR/config/mount-record.txt" || fail 'verified record missing'
printf 'state=degraded\nbackend=legacy\nmounted=system/fonts\nfailed=system/etc\n' > "$MODULE_DIR/config/self-mount.conf"
if ziyu_mount_verify_active custom; then
    fail 'legacy degraded state was accepted'
fi

setup_case stale-journal
printf 'default\n' > "$MODULE_DIR/config/active_font.conf"
printf 'old-boot\n' > "$CASE_ROOT/state/boot-id"
printf '%s\n' "$CASE_ROOT/root/system/fonts" > "$CASE_ROOT/state/mounts.list"
ziyu_self_mount_ensure || fail 'default-font stale-journal cleanup failed'
test ! -s "$CASE_ROOT/unmount.log" || fail 'stale boot journal unmounted a potentially foreign target'

setup_case same-boot-journal
printf 'default\n' > "$MODULE_DIR/config/active_font.conf"
printf '%s\n' "$CURRENT_BOOT_ID" > "$CASE_ROOT/state/boot-id"
printf '%s\n' "$CASE_ROOT/root/system/fonts" > "$CASE_ROOT/state/mounts.list"
printf '100 1 0:1 / %s rw - ext4 %s/.ziyu-payload/system/fonts rw\n' \
    "$CASE_ROOT/root/system/fonts" "$MODULE_DIR" >> "$ZIYU_PID1_MOUNTINFO"
ziyu_self_mount_ensure || fail 'same-boot journal cleanup failed'
test -s "$CASE_ROOT/unmount.log" || fail 'same-boot Ziyu mount was not rolled back'

setup_case rollback
mkdir -p "$MODULE_DIR/system/fonts" "$MODULE_DIR/system/etc" "$MODULE_DIR/product/etc" \
    "$CASE_ROOT/root/product/etc"
printf 'new-font\n' > "$MODULE_DIR/system/fonts/Roboto.ttf"
printf 'new-xml\n' > "$MODULE_DIR/system/etc/fonts.xml"
printf 'product-xml\n' > "$MODULE_DIR/product/etc/fonts.xml"
printf 'stock-font\n' > "$CASE_ROOT/root/system/fonts/Roboto.ttf"
printf 'stock-xml\n' > "$CASE_ROOT/root/system/etc/fonts.xml"
printf 'stock-product\n' > "$CASE_ROOT/root/product/etc/fonts.xml"
FAIL_OVERLAY=product-etc
FAIL_BIND="$CASE_ROOT/root/product/etc/fonts.xml"
if ziyu_self_mount_ensure; then
    fail 'partial product/etc failure was accepted'
fi
grep -q '^state=failed$' "$MODULE_DIR/config/self-mount.conf" || fail 'rollback failure state missing'
grep -q 'product/etc-bind-incomplete' "$MODULE_DIR/config/self-mount.conf" || fail 'failed component not recorded'
test ! -e "$MODULE_DIR/config/self-mount-required.conf" || fail 'failed manifest was committed'
test -s "$CASE_ROOT/unmount.log" || fail 'partial mounts were not rolled back'
if grep -q '^state=degraded$' "$MODULE_DIR/config/self-mount.conf"; then
    fail 'degraded state survived atomic policy'
fi

setup_case missing-root
mkdir -p "$MODULE_DIR/system/fonts" "$MODULE_DIR/product/etc"
printf 'new-font\n' > "$MODULE_DIR/system/fonts/Roboto.ttf"
printf 'stock-font\n' > "$CASE_ROOT/root/system/fonts/Roboto.ttf"
printf 'product-xml\n' > "$MODULE_DIR/product/etc/fonts.xml"
if ziyu_self_mount_ensure; then
    fail 'missing payload partition root was silently skipped'
fi
grep -q 'product/root-unavailable' "$MODULE_DIR/config/self-mount.conf" || fail 'missing partition root reason absent'

setup_case missing-optional-target
mkdir -p "$MODULE_DIR/system/fonts" "$MODULE_DIR/system_ext/fonts" "$CASE_ROOT/root/system_ext"
printf 'new-font\n' > "$MODULE_DIR/system/fonts/Roboto.ttf"
printf 'extension-font\n' > "$MODULE_DIR/system_ext/fonts/Extension.ttf"
printf 'stock-font\n' > "$CASE_ROOT/root/system/fonts/Roboto.ttf"
if ziyu_self_mount_ensure; then
    fail 'missing payload font target was silently skipped'
fi
grep -q 'system_ext/fonts-target-missing' "$MODULE_DIR/config/self-mount.conf" || fail 'missing target reason absent'

if [ -n "$FINAL_SCRIPT" ]; then
    setup_case kernelsu-private-payload-missing-optional-target
    ZIYU_TEST_PRIVATE_PAYLOAD_ROOT="$MODULE_DIR/.ziyu-payload"
    mkdir -p "$ZIYU_TEST_PRIVATE_PAYLOAD_ROOT/system/fonts" "$ZIYU_TEST_PRIVATE_PAYLOAD_ROOT/system_ext/fonts" "$CASE_ROOT/root/system_ext"
    printf 'new-font\n' > "$ZIYU_TEST_PRIVATE_PAYLOAD_ROOT/system/fonts/Roboto.ttf"
    printf 'extension-font\n' > "$ZIYU_TEST_PRIVATE_PAYLOAD_ROOT/system_ext/fonts/Extension.ttf"
    printf 'stock-font\n' > "$CASE_ROOT/root/system/fonts/Roboto.ttf"
    if ziyu_self_mount_ensure; then
        fail 'KernelSU private payload skipped a missing font target'
    fi
    grep -q 'system_ext/fonts-target-missing' "$MODULE_DIR/config/self-mount.conf" || fail 'KernelSU missing target reason absent'
fi

setup_case bind-compatible-alias
mkdir -p "$MODULE_DIR/system/fonts"
printf 'font-a\n' > "$MODULE_DIR/system/fonts/A.ttf"
printf 'font-b\n' > "$MODULE_DIR/system/fonts/B.ttf"
printf 'stock-a\n' > "$CASE_ROOT/root/system/fonts/A.ttf"
FAIL_OVERLAY=system-fonts
ziyu_self_mount_ensure || fail 'bind fallback rejected a device-compatible existing target'
grep -q '^state=mounted$' "$MODULE_DIR/config/self-mount.conf" || fail 'bind fallback success was not recorded'
grep -q '^backend=self-overlay-bind$' "$MODULE_DIR/config/self-mount.conf" || fail 'bind fallback backend missing'
test "$(cat "$CASE_ROOT/root/system/fonts/A.ttf")" = 'font-a' || fail 'existing bind target was not replaced'
test ! -e "$CASE_ROOT/root/system/fonts/B.ttf" || fail 'bind fallback created an additive ROM alias'
test "$(cat "$CASE_ROOT/state/lower/system-fonts/A.ttf")" = 'stock-a' || \
    fail 'bind fallback did not preserve the stock lower view'
test ! -e "$CASE_ROOT/state/lower/system-fonts/B.ttf" || \
    fail 'stock lower was contaminated by the Ziyu payload'
ziyu_mount_verify_active custom || fail 'strict verifier rejected compatible bind fallback'

setup_case bind-symlink-alias
mkdir -p "$MODULE_DIR/system/fonts"
printf 'font-alias\n' > "$MODULE_DIR/system/fonts/Alias.ttf"
printf 'font-canonical\n' > "$MODULE_DIR/system/fonts/Canonical.ttf"
printf 'stock-canonical\n' > "$CASE_ROOT/root/system/fonts/Canonical.ttf"
ln -s Canonical.ttf "$CASE_ROOT/root/system/fonts/Alias.ttf"
FAIL_OVERLAY=system-fonts
if ! ziyu_self_mount_ensure; then
    cat "$MODULE_DIR/config/self-mount.conf" >&2 2>/dev/null || true
    tail -n 12 "$MODULE_DIR/logs/self-mount.log" >&2 2>/dev/null || true
    tail -n 12 "$MODULE_DIR/logs/mount-diagnostics.log" >&2 2>/dev/null || true
    fail 'bind fallback rejected aliases sharing one real ROM target'
fi
grep -q '^state=mounted$' "$MODULE_DIR/config/self-mount.conf" || fail 'symlink bind fallback was not committed'
test "$(cat "$CASE_ROOT/root/system/fonts/Canonical.ttf")" = 'font-canonical' || fail 'canonical bind target was overwritten by its alias'
test "$(cat "$CASE_ROOT/root/system/fonts/Alias.ttf")" = 'font-alias' || fail 'directory mirror did not preserve the alias font payload'
test ! -L "$CASE_ROOT/root/system/fonts/Alias.ttf" || fail 'directory mirror did not materialize the conflicting ROM alias'
test -L "$CASE_ROOT/state/lower/system-fonts/Alias.ttf" || fail 'stock lower did not preserve the ROM alias'
test "$(cat "$CASE_ROOT/state/lower/system-fonts/Alias.ttf")" = 'stock-canonical' || fail 'ROM alias changed in the stock lower view'
ziyu_mount_verify_active custom || fail 'strict verifier rejected a directory-mirrored alias'

setup_case bind-additive-etc
mkdir -p "$MODULE_DIR/system/fonts" "$MODULE_DIR/system/etc/ziyu"
printf 'font-a\n' > "$MODULE_DIR/system/fonts/A.ttf"
printf 'dynamic-config\n' > "$MODULE_DIR/system/etc/.ziyu-data-fonts-config.xml"
printf 'probe\n' > "$MODULE_DIR/system/etc/ziyu/mount-probe.conf"
printf 'stock-a\n' > "$CASE_ROOT/root/system/fonts/A.ttf"
FAIL_OVERLAY=all
ziyu_self_mount_ensure || fail 'additive-only system/etc rejected an otherwise complete font bind'
grep -q '^state=mounted$' "$MODULE_DIR/config/self-mount.conf" || fail 'additive-only etc skip was not committed'
test "$(wc -l < "$MODULE_DIR/config/self-mount-required.conf" | tr -d ' ')" -eq 1 || fail 'skipped additive component entered the required manifest'
test "$(cat "$CASE_ROOT/root/system/fonts/A.ttf")" = 'font-a' || fail 'font bind was lost while skipping additive etc'
test ! -e "$CASE_ROOT/root/system/etc/.ziyu-data-fonts-config.xml" || fail 'bind fallback created a missing dynamic config target'
ziyu_mount_verify_active custom || fail 'strict verifier rejected the font-only compatible bind'

setup_case bind-no-compatible-target
mkdir -p "$MODULE_DIR/system/fonts"
printf 'font-a\n' > "$MODULE_DIR/system/fonts/A.ttf"
printf 'font-b\n' > "$MODULE_DIR/system/fonts/B.ttf"
FAIL_OVERLAY=system-fonts
if ziyu_self_mount_ensure; then
    fail 'bind fallback with no device-compatible target was accepted'
fi
if [ -n "$FINAL_SCRIPT" ]; then
    grep -q 'no-existing-bind-target' "$MODULE_DIR/config/self-mount.conf" || fail 'final empty bind reason absent'
else
    grep -q 'system/fonts-bind-empty' "$MODULE_DIR/config/self-mount.conf" || fail 'empty bind reason absent'
fi

echo "self-mount transaction tests passed: ${FINAL_SCRIPT:-atomic}"

if [ -z "$FINAL_SCRIPT" ]; then
    FINAL_SCRIPT="$REPO_ROOT/common/font_runtime_mount.sh" sh "$0"
fi
