#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t ziyu-self-mount)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MODULE="$TMP/modules/Ziyu"
VISIBLE="$TMP/visible"
STATE="$TMP/state"
PRIVATE_STATE="$TMP/private-state"
FAKE_MOUNT="$TMP/fake-mount.sh"
FAKE_UMOUNT="$TMP/fake-umount.sh"
MOUNTINFO="$TMP/mounts"
PROC_MOUNTINFO="$TMP/proc-mountinfo"
FAKE_BIN="$TMP/fake-bin"
REAL_CAT=$(command -v cat)
mkdir -p \
    "$MODULE/system/fonts" \
    "$MODULE/system/etc/ziyu" \
    "$MODULE/config" \
    "$VISIBLE/system/fonts" \
    "$VISIBLE/system/etc"
ln -s "$ROOT/common" "$MODULE/common"

printf 'new-font\n' > "$MODULE/system/fonts/Roboto-Regular.ttf"
printf 'stock-font\n' > "$VISIBLE/system/fonts/Roboto-Regular.ttf"
printf 'stock-emoji\n' > "$VISIBLE/system/fonts/NotoColorEmoji.ttf"
printf 'Demo\n' > "$MODULE/config/active_font.conf"
printf 'selected_backend=self\nprovider_state=absent\nfallback_used=0\nboot_id=test-system\n' \
    > "$MODULE/config/mount-backend.conf"
printf 'id=Ziyu\nfont=Demo\nengine=self-mount\npartition=system\nnonce=test-system\n' \
    > "$MODULE/system/etc/ziyu/mount-probe.conf"
printf 'system|test-system|/system/etc/ziyu/mount-probe.conf\n' \
    > "$MODULE/config/mount-probes-expected.conf"
: > "$MOUNTINFO"
: > "$PROC_MOUNTINFO"
printf '1 0 0:1 / / rw - ext4 rootfs rw\n' > "$PROC_MOUNTINFO"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/cat" <<EOF_FAKE_CAT
#!/bin/sh
if [ "\$#" -eq 1 ] && [ "\$1" = /proc/sys/kernel/random/boot_id ]; then
    printf 'test-system\\n'
    exit 0
fi
exec "$REAL_CAT" "\$@"
EOF_FAKE_CAT
chmod 0755 "$FAKE_BIN/cat"

cat > "$FAKE_MOUNT" <<'EOF_FAKE_MOUNT'
#!/bin/sh
set -eu
mountinfo=${ZIYU_TEST_MOUNTINFO:?}
proc_mountinfo=${ZIYU_TEST_KERNEL_MOUNTINFO:?}
add_mountinfo() {
    source="$1"
    target="$2"
    filesystem="${3:-ext4}"
    options="${4:-rw}"
    super_options="${5:-$options}"
    mount_id=$(awk 'BEGIN { max=99 } $1 > max { max=$1 } END { print max + 1 }' "$proc_mountinfo")
    printf '%s 1 0:1 / %s %s - %s %s %s\n' \
        "$mount_id" \
        "$target" "$options" "$filesystem" "$source" "$super_options" >> "$proc_mountinfo"
}
remount_readonly() {
    target="$1"
    tmp="${proc_mountinfo}.tmp.$$"
    awk -v target="$target" 'BEGIN { OFS=" " } $5 == target { $6="ro" } { print }' \
        "$proc_mountinfo" > "$tmp"
    mv -f "$tmp" "$proc_mountinfo"
}
if [ "$1" = --make-private ]; then
    exit 0
fi
case "$1" in
    -o)
        case "$2" in
            bind)
                src="$3"
                dst="$4"
                backup="${dst}.ziyu-test-stock"
                rm -rf "$backup"
                if [ -d "$dst" ]; then mv "$dst" "$backup"; else mkdir -p "$backup"; fi
                mkdir -p "$dst"
                cp -a "$src/." "$dst/"
                printf 'bind %s %s %s\n' "$src" "$dst" "$backup" >> "$mountinfo"
                add_mountinfo "$src" "$dst" ext4 rw
                ;;
            private)
                [ "$3" = -t ] && [ "$4" = none ] && [ "$5" = none ]
                [ -n "$6" ]
                ;;
            remount,bind,ro)
                remount_readonly "$3"
                ;;
            *) exit 2 ;;
        esac
        ;;
    -t)
        [ "$2" = overlay ]
        [ "$3" = KSU ]
        [ "$4" = -o ]
        opts="$5"
        target="$6"
        layers=$(printf '%s' "$opts" | tr ',' '\n' | sed -n 's/^lowerdir=//p')
        source=${layers%%:*}
        stock=${layers#*:}
        backup="${target}.ziyu-test-stock"
        rm -rf "$backup"
        mv "$target" "$backup"
        mkdir -p "$target"
        cp -a "$stock/." "$target/"
        cp -a "$source/." "$target/"
        printf 'overlay %s %s %s\n' "$source" "$target" "$backup" >> "$mountinfo"
        add_mountinfo overlay "$target" overlay ro "$opts"
        ;;
    *) exit 2 ;;
esac
EOF_FAKE_MOUNT

cat > "$FAKE_UMOUNT" <<'EOF_FAKE_UMOUNT'
#!/bin/sh
set -eu
target="$1"
backup="${target}.ziyu-test-stock"
rm -rf "$target"
if [ -d "$backup" ]; then mv "$backup" "$target"; else mkdir -p "$target"; fi
proc_mountinfo=${ZIYU_TEST_KERNEL_MOUNTINFO:?}
tmp="${proc_mountinfo}.tmp.$$"
awk -v target="$target" '$5 != target { print }' "$proc_mountinfo" > "$tmp"
mv -f "$tmp" "$proc_mountinfo"
mountinfo=${ZIYU_TEST_MOUNTINFO:?}
tmp="${mountinfo}.tmp.$$"
awk -v target="$target" '$3 != target { print }' "$mountinfo" > "$tmp"
mv -f "$tmp" "$mountinfo"
EOF_FAKE_UMOUNT
chmod 0755 "$FAKE_MOUNT" "$FAKE_UMOUNT"

# Installation moves standard partition payloads into a private tree and leaves
# an empty shell that Mountify/Hybrid/Magic Mount cannot consume.
MODDIR="$MODULE" MODULE_DIR="$MODULE" \
ZIYU_PRIVATE_STATE_ROOT="$PRIVATE_STATE" \
ZIYU_PRIVATE_MOUNTINFO="$MOUNTINFO" \
ZIYU_BACKEND_TEST_BOOT_ID=test-system \
ZIYU_PRIVATE_MOUNT_COMMAND="$FAKE_MOUNT" \
ZIYU_PRIVATE_UMOUNT_COMMAND="$FAKE_UMOUNT" \
ZIYU_TEST_MOUNTINFO="$MOUNTINFO" \
sh -c '
    . "$1/common/private_payload.sh"
    ziyu_private_install_migrate "$MODDIR"
    [ -f "$MODDIR/.ziyu-payload/system/fonts/Roboto-Regular.ttf" ]
    [ -f "$MODDIR/.ziyu-payload/system/etc/ziyu/mount-probe.conf" ]
    [ -d "$MODDIR/system" ]
    [ -z "$(find "$MODDIR/system" -mindepth 1 -print -quit)" ]
    [ -e "$MODDIR/skip_mount" ]
    [ -e "$MODDIR/skip_mountify" ]
    [ -e "$MODDIR/.ziyu_skip_mount_owned" ]
    [ -e "$MODDIR/.ziyu_skip_mountify_owned" ]
    [ ! -e "$MODDIR/config/self-mount-owned" ]
    [ "$(cat "$MODDIR/.ziyu-payload/system/fonts/Roboto-Regular.ttf")" = new-font ]
    ! type ziyu_private_mount_module_view >/dev/null 2>&1
    [ -z "$(find "$MODDIR/system" -mindepth 1 -print -quit)" ]
    [ -f "$MODDIR/.ziyu-payload/system/fonts/Roboto-Regular.ttf" ]
' sh "$ROOT"

# After all metamodules finish, Ziyu reads its canonical private payload and
# mounts it itself when selected. Skip markers remain, ROM Emoji survives, and a repeated
# call verifies and reuses the same atomic transaction without stacking mounts.
MODDIR="$MODULE" MODULE_DIR="$MODULE" ZIYU_MOUNT_MODDIR="$MODULE" \
ZIYU_SELF_MOUNT_VISIBLE_ROOT="$VISIBLE" \
ZIYU_SELF_MOUNT_STATE_ROOT="$STATE" \
ZIYU_SELF_MOUNTINFO="$PROC_MOUNTINFO" \
ZIYU_PID1_MOUNTINFO="$PROC_MOUNTINFO" \
ZIYU_TEST_KERNEL_MOUNTINFO="$PROC_MOUNTINFO" \
ZIYU_SELF_PID1_ROOT=/ \
ZIYU_SELF_MOUNT_COMMAND="$FAKE_MOUNT" \
ZIYU_SELF_UMOUNT_COMMAND="$FAKE_UMOUNT" \
PATH="$FAKE_BIN:$PATH" \
ZIYU_PRIVATE_STATE_ROOT="$PRIVATE_STATE" \
ZIYU_PRIVATE_MOUNTINFO="$MOUNTINFO" \
ZIYU_BACKEND_TEST_BOOT_ID=test-system \
ZIYU_PRIVATE_MOUNT_COMMAND="$FAKE_MOUNT" \
ZIYU_PRIVATE_UMOUNT_COMMAND="$FAKE_UMOUNT" \
ZIYU_TEST_MOUNTINFO="$MOUNTINFO" \
sh -c '
    ziyu_payload_partitions() { printf "system\n"; }
    ziyu_module_id() { printf "Ziyu\n"; }
    _ziyu_probe_path() { printf "/system/etc/ziyu/mount-probe.conf\n"; }
    _ziyu_now() { printf "1\n"; }
    ziyu_mount_record() { :; }
    . "$1/common/private_payload.sh"
    . "$MODDIR/common/mount_compat.sh"
    . "$MODDIR/common/mount_self_backend.sh"
    set -eu
    _ziyu_atomic_boot_id() { printf "test-system\n"; }

    touch "$MODDIR/mount_error"
    ziyu_private_self_mount_ensure
    [ -e "$MODDIR/skip_mount" ]
    [ -e "$MODDIR/skip_mountify" ]
    [ ! -e "$MODDIR/mount_error" ]
    [ "$(sed -n "s/^state=//p" "$MODDIR/config/self-mount.conf")" = mounted ]
    [ "$(sed -n "s/^backend=//p" "$MODDIR/config/self-mount.conf")" = self-overlay ]
    [ "$(cat "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts/Roboto-Regular.ttf")" = new-font ]
    [ "$(cat "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts/NotoColorEmoji.ttf")" = stock-emoji ]
    grep -F " / $ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts ro - overlay overlay " "$ZIYU_SELF_MOUNTINFO"
    grep -F " / $ZIYU_SELF_MOUNT_STATE_ROOT/lower/system-fonts rw - ext4 " "$ZIYU_SELF_MOUNTINFO"
    ! grep -F " / $ZIYU_SELF_MOUNT_STATE_ROOT/lower/system-fonts rw - ext4 " \
        "$ZIYU_SELF_MOUNTINFO" | grep -E "shared|master|propagate_from:"
    _ziyu_atomic_verify_manifest "$MODDIR/config/self-mount-required.conf"
    mount_list="$ZIYU_SELF_MOUNT_STATE_ROOT/mounts.list"
    [ -s "$mount_list" ]
    before=$(wc -l < "$mount_list" | tr -d "[:space:]")
    before_info=$(wc -l < "$ZIYU_SELF_MOUNTINFO" | tr -d "[:space:]")
    case "$before" in ""|*[!0-9]*) exit 1 ;; esac
    [ "$before" -gt 0 ]

    ziyu_private_self_mount_ensure
    [ "$(sed -n "s/^state=//p" "$MODDIR/config/self-mount.conf")" = mounted ]
    [ "$(sed -n "s/^backend=//p" "$MODDIR/config/self-mount.conf")" = self-overlay ]
    _ziyu_atomic_verify_manifest "$MODDIR/config/self-mount-required.conf"
    after=$(wc -l < "$mount_list" | tr -d "[:space:]")
    after_info=$(wc -l < "$ZIYU_SELF_MOUNTINFO" | tr -d "[:space:]")
    case "$after" in ""|*[!0-9]*) exit 1 ;; esac
    [ "$after" -gt 0 ]
    [ "$before" = "$after" ]
    [ "$before_info" = "$after_info" ]
    "$ZIYU_SELF_UMOUNT_COMMAND" "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts"
    ! grep -F " / $ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts " "$ZIYU_SELF_MOUNTINFO"
    ! grep -F " $ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts " "$ZIYU_TEST_MOUNTINFO"
    [ "$(cat "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts/Roboto-Regular.ttf")" = stock-font ]
    [ "$(cat "$ZIYU_SELF_MOUNT_VISIBLE_ROOT/system/fonts/NotoColorEmoji.ttf")" = stock-emoji ]
' sh "$ROOT"

# APatch and KernelSU-family managers must wait until their native OverlayFS
# stage. Magisk retains the early path because it has no module post-mount hook.
sh -c '
    . "$1/common/mount_self_backend.sh"
    set -e
    [ "$(ziyu_self_mount_stage_for_manager APatch)" = post-mount ]
    [ "$(ziyu_self_mount_stage_for_manager KernelSU)" = post-mount ]
    [ "$(ziyu_self_mount_stage_for_manager SukiSU-Ultra)" = post-mount ]
    [ "$(ziyu_self_mount_stage_for_manager Magisk)" = post-fs-data ]
    [ "$(ziyu_self_mount_stage_for_manager unknown)" = post-fs-data ]
' sh "$ROOT"
grep -q 'mount_backend_runtime.sh' "$ROOT/post-fs-data.sh"

# Exercise the production router and its preserved v4 implementation together:
# APatch waits for post-mount, while Magisk still mounts in post-fs-data.
STAGE_MODULE="$TMP/stage-module"
STAGE_LOG="$TMP/stage.log"
mkdir -p "$STAGE_MODULE/common" "$STAGE_MODULE/config" "$STAGE_MODULE/.ziyu-runtime/core" "$STAGE_MODULE/.ziyu-runtime/compat/v227"
printf 'Demo\n' > "$STAGE_MODULE/config/active_font.conf"
printf 'preferred_backend=self_mount\n' > "$STAGE_MODULE/config/mount-backend-preference.conf"
cp "$ROOT/post-fs-data.sh" "$STAGE_MODULE/post-fs-data.sh"
cp "$ROOT/.ziyu-runtime/core/post-fs-data.sh" "$STAGE_MODULE/.ziyu-runtime/core/post-fs-data.sh"
cp "$ROOT/common/mount_self_backend.sh" "$STAGE_MODULE/common/mount_self_backend.sh"
cp "$ROOT/common/mount_backend_runtime.sh" "$STAGE_MODULE/common/mount_backend_runtime.sh"
cp "$ROOT/common/mount_backend_state.sh" "$STAGE_MODULE/common/mount_backend_state.sh"
cp "$ROOT/common/private_payload.sh" "$STAGE_MODULE/common/private_payload.sh"
cp "$ROOT/common/font_live_switch.sh" "$STAGE_MODULE/common/font_live_switch.sh"
cp "$ROOT/common/font_live_state.sh" "$ROOT/common/legacy_identity.sh" "$STAGE_MODULE/common/"
cp "$ROOT/common/font_switch_lock.sh" "$STAGE_MODULE/common/font_switch_lock.sh"
cp "$ROOT/common/font_next_transaction.sh" "$STAGE_MODULE/common/font_next_transaction.sh"
cp "$ROOT/common/next_boot_payload.sh" "$STAGE_MODULE/common/next_boot_payload.sh"
cp "$ROOT/common/action_control.sh" "$STAGE_MODULE/common/action_control.sh"
cp "$ROOT/common/skip_mount_ownership.sh" "$STAGE_MODULE/common/skip_mount_ownership.sh"
cp "$ROOT/common/mount_nomount_backend.sh" "$STAGE_MODULE/common/mount_nomount_backend.sh"
cp "$ROOT/common/mount_backend_preferences.sh" "$STAGE_MODULE/common/mount_backend_preferences.sh"
cp "$ROOT/common/mount_backend_policy.sh" "$STAGE_MODULE/common/mount_backend_policy.sh"
cp "$ROOT/common/mount_provider_detection.sh" "$STAGE_MODULE/common/mount_provider_detection.sh"
cp "$ROOT/common/mount_provider_payload.sh" "$STAGE_MODULE/common/mount_provider_payload.sh"
cp "$ROOT/common/root_manager_detection.sh" "$STAGE_MODULE/common/root_manager_detection.sh"
cp "$ROOT/common/nomount_rule_json.py" "$STAGE_MODULE/common/nomount_rule_json.py"
cat >"$STAGE_MODULE/.ziyu-runtime/compat/v227/post-fs-data.sh" <<'EOF_BOOT_STAGE'
ziyu_detect_root_manager() { printf '%s\n' "$ZIYU_TEST_ROOT_MANAGER"; }
ziyu_private_self_mount_ensure() { printf 'ensure\n' >>"$ZIYU_STAGE_LOG"; }
exit 0
EOF_BOOT_STAGE

: >"$STAGE_LOG"
: > "$PROC_MOUNTINFO"
ZIYU_STAGE_LOG="$STAGE_LOG" ZIYU_TEST_ROOT_MANAGER=APatch \
ZIYU_SELF_MOUNTINFO="$PROC_MOUNTINFO" ZIYU_PID1_MOUNTINFO="$PROC_MOUNTINFO" \
PATH="$FAKE_BIN:$PATH" \
ZIYU_BACKEND_TEST_BOOT_ID=test-system \
ZIYU_BACKEND_TEST_MODE=1 ZIYU_BACKEND_TEST_MANAGER=APatch \
sh "$STAGE_MODULE/post-fs-data.sh"
test ! -s "$STAGE_LOG"
! grep -qx ensure "$STAGE_LOG"

: >"$STAGE_LOG"
ZIYU_STAGE_LOG="$STAGE_LOG" ZIYU_TEST_ROOT_MANAGER=Magisk \
ZIYU_SELF_MOUNTINFO="$PROC_MOUNTINFO" ZIYU_PID1_MOUNTINFO="$PROC_MOUNTINFO" \
PATH="$FAKE_BIN:$PATH" \
ZIYU_BACKEND_TEST_BOOT_ID=magisk-test \
ZIYU_BACKEND_TEST_MODE=1 ZIYU_BACKEND_TEST_MANAGER=Magisk \
sh "$STAGE_MODULE/post-fs-data.sh"
test ! -s "$STAGE_LOG"
test -f "$STAGE_MODULE/config/test-self-mounted"

# Production policy uses the real Meta detector; the test override exercises its
# selector without requiring a rooted host.
MODE=$(MODDIR="$ROOT" MODULE_DIR="$ROOT" sh -c '. "$1/common/mount_compat.sh"; ziyu_detect_mount_engine' sh "$ROOT")
[ "$MODE" = self-mount ]
INJECTED=$(MODDIR="$ROOT" MODULE_DIR="$ROOT" ZIYU_META_TEST_ENGINE=hybrid-mount sh -c '. "$1/common/mount_compat.sh"; ziyu_detect_mount_engine' sh "$ROOT")
[ "$INJECTED" = hybrid-mount ]
grep -q 'mount_backend_runtime.sh' "$ROOT/post-mount.sh"

sh -n "$ROOT/common/private_payload.sh"
sh -n "$ROOT/common/mount_self_fallback.sh"
sh -n "$ROOT/common/mount_self_backend.sh"
sh -n "$ROOT/common/mount_self_atomic.sh"
sh -n "$ROOT/common/mount_compat_policy.sh"
sh -n "$ROOT/common/private_mount_policy.sh"
sh -n "$ROOT/common/mount_compat.sh"
sh -n "$ROOT/post-fs-data.sh"
sh -n "$ROOT/post-mount.sh"
echo 'LuoShu private self-mount regression checks passed.'
