#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t luoshu-self-mount)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MODULE="$TMP/modules/LuoShu"
VISIBLE="$TMP/visible"
STATE="$TMP/state"
PRIVATE_STATE="$TMP/private-state"
FAKE_MOUNT="$TMP/fake-mount.sh"
FAKE_UMOUNT="$TMP/fake-umount.sh"
MOUNTINFO="$TMP/mounts"
mkdir -p \
    "$MODULE/system/fonts" \
    "$MODULE/system/etc/luoshu" \
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
printf 'id=LuoShu\nfont=Demo\nengine=self-mount\npartition=system\nnonce=test-system\n' \
    > "$MODULE/system/etc/luoshu/mount-probe.conf"
printf 'system|test-system|/system/etc/luoshu/mount-probe.conf\n' \
    > "$MODULE/config/mount-probes-expected.conf"
: > "$MOUNTINFO"

cat > "$FAKE_MOUNT" <<'EOF_FAKE_MOUNT'
#!/bin/sh
set -eu
mountinfo=${LUOSHU_TEST_MOUNTINFO:?}
case "$1" in
    -o)
        [ "$2" = bind ]
        src="$3"
        dst="$4"
        backup="${dst}.luoshu-test-stock"
        rm -rf "$backup"
        if [ -d "$dst" ]; then mv "$dst" "$backup"; else mkdir -p "$backup"; fi
        mkdir -p "$dst"
        cp -a "$src/." "$dst/"
        printf 'bind %s %s %s\n' "$src" "$dst" "$backup" >> "$mountinfo"
        ;;
    -t)
        [ "$2" = overlay ]
        [ "$3" = KSU ]
        [ "$4" = -o ]
        opts="$5"
        target="$6"
        layers=$(printf '%s' "$opts" | sed -n 's/^lowerdir=//p')
        source=${layers%%:*}
        stock=${layers#*:}
        backup="${target}.luoshu-test-stock"
        rm -rf "$backup"
        mv "$target" "$backup"
        mkdir -p "$target"
        cp -a "$stock/." "$target/"
        cp -a "$source/." "$target/"
        printf 'overlay %s %s %s\n' "$source" "$target" "$backup" >> "$mountinfo"
        ;;
    *) exit 2 ;;
esac
EOF_FAKE_MOUNT

cat > "$FAKE_UMOUNT" <<'EOF_FAKE_UMOUNT'
#!/bin/sh
set -eu
target="$1"
backup="${target}.luoshu-test-stock"
rm -rf "$target"
if [ -d "$backup" ]; then mv "$backup" "$target"; else mkdir -p "$target"; fi
EOF_FAKE_UMOUNT
chmod 0755 "$FAKE_MOUNT" "$FAKE_UMOUNT"

# Installation moves standard partition payloads into a private tree and leaves
# an empty shell that Mountify/Hybrid/Magic Mount cannot consume.
MODDIR="$MODULE" MODULE_DIR="$MODULE" \
LUOSHU_PRIVATE_STATE_ROOT="$PRIVATE_STATE" \
LUOSHU_PRIVATE_MOUNTINFO="$MOUNTINFO" \
LUOSHU_BACKEND_TEST_BOOT_ID=test-system \
LUOSHU_PRIVATE_MOUNT_COMMAND="$FAKE_MOUNT" \
LUOSHU_PRIVATE_UMOUNT_COMMAND="$FAKE_UMOUNT" \
LUOSHU_TEST_MOUNTINFO="$MOUNTINFO" \
sh -c '
    . "$1/common/private_payload.sh"
    luoshu_private_install_migrate "$MODDIR"
    [ -f "$MODDIR/.luoshu-payload/system/fonts/Roboto-Regular.ttf" ]
    [ -f "$MODDIR/.luoshu-payload/system/etc/luoshu/mount-probe.conf" ]
    [ -d "$MODDIR/system" ]
    [ -z "$(find "$MODDIR/system" -mindepth 1 -print -quit)" ]
    [ -e "$MODDIR/skip_mount" ]
    [ -e "$MODDIR/skip_mountify" ]
    [ -e "$MODDIR/.ziyu_skip_mount_owned" ]
    [ -e "$MODDIR/.ziyu_skip_mountify_owned" ]
    [ ! -e "$MODDIR/config/self-mount-owned" ]
    [ "$(cat "$MODDIR/.luoshu-payload/system/fonts/Roboto-Regular.ttf")" = new-font ]
    ! type luoshu_private_mount_module_view >/dev/null 2>&1
    [ -z "$(find "$MODDIR/system" -mindepth 1 -print -quit)" ]
    [ -f "$MODDIR/.luoshu-payload/system/fonts/Roboto-Regular.ttf" ]
' sh "$ROOT"

# After all metamodules finish, LuoShu reads its canonical private payload and
# mounts it itself when selected. Skip markers remain, ROM Emoji survives, and a repeated
# call verifies and reuses the same atomic transaction without stacking mounts.
MODDIR="$MODULE" MODULE_DIR="$MODULE" LUOSHU_MOUNT_MODDIR="$MODULE" \
LUOSHU_SELF_MOUNT_VISIBLE_ROOT="$VISIBLE" \
LUOSHU_SELF_MOUNT_STATE_ROOT="$STATE" \
LUOSHU_SELF_PID1_ROOT=/ \
LUOSHU_SELF_MOUNT_COMMAND="$FAKE_MOUNT" \
LUOSHU_SELF_UMOUNT_COMMAND="$FAKE_UMOUNT" \
LUOSHU_PRIVATE_STATE_ROOT="$PRIVATE_STATE" \
LUOSHU_PRIVATE_MOUNTINFO="$MOUNTINFO" \
LUOSHU_BACKEND_TEST_BOOT_ID=test-system \
LUOSHU_PRIVATE_MOUNT_COMMAND="$FAKE_MOUNT" \
LUOSHU_PRIVATE_UMOUNT_COMMAND="$FAKE_UMOUNT" \
LUOSHU_TEST_MOUNTINFO="$MOUNTINFO" \
sh -c '
    luoshu_payload_partitions() { printf "system\n"; }
    luoshu_module_id() { printf "LuoShu\n"; }
    _luoshu_probe_path() { printf "/system/etc/luoshu/mount-probe.conf\n"; }
    _luoshu_now() { printf "1\n"; }
    luoshu_mount_record() { :; }
    . "$1/common/private_payload.sh"
    . "$MODDIR/common/mount_compat.sh"
    . "$MODDIR/common/mount_self_backend.sh"
    set -eu

    touch "$MODDIR/mount_error"
    luoshu_private_self_mount_ensure
    [ -e "$MODDIR/skip_mount" ]
    [ -e "$MODDIR/skip_mountify" ]
    [ ! -e "$MODDIR/mount_error" ]
    [ "$(sed -n "s/^state=//p" "$MODDIR/config/self-mount.conf")" = mounted ]
    [ "$(sed -n "s/^backend=//p" "$MODDIR/config/self-mount.conf")" = self-overlay ]
    [ "$(cat "$LUOSHU_SELF_MOUNT_VISIBLE_ROOT/system/fonts/Roboto-Regular.ttf")" = new-font ]
    [ "$(cat "$LUOSHU_SELF_MOUNT_VISIBLE_ROOT/system/fonts/NotoColorEmoji.ttf")" = stock-emoji ]
    _luoshu_atomic_verify_manifest "$MODDIR/config/self-mount-required.conf"
    mount_list="$LUOSHU_SELF_MOUNT_STATE_ROOT/mounts.list"
    [ -s "$mount_list" ]
    before=$(wc -l < "$mount_list" | tr -d "[:space:]")
    case "$before" in ""|*[!0-9]*) exit 1 ;; esac
    [ "$before" -gt 0 ]

    luoshu_private_self_mount_ensure
    [ "$(sed -n "s/^state=//p" "$MODDIR/config/self-mount.conf")" = mounted ]
    [ "$(sed -n "s/^backend=//p" "$MODDIR/config/self-mount.conf")" = self-overlay ]
    _luoshu_atomic_verify_manifest "$MODDIR/config/self-mount-required.conf"
    after=$(wc -l < "$mount_list" | tr -d "[:space:]")
    case "$after" in ""|*[!0-9]*) exit 1 ;; esac
    [ "$after" -gt 0 ]
    [ "$before" = "$after" ]
' sh "$ROOT"

# APatch and KernelSU-family managers must wait until their native OverlayFS
# stage. Magisk retains the early path because it has no module post-mount hook.
sh -c '
    . "$1/common/mount_self_backend.sh"
    set -e
    [ "$(luoshu_self_mount_stage_for_manager APatch)" = post-mount ]
    [ "$(luoshu_self_mount_stage_for_manager KernelSU)" = post-mount ]
    [ "$(luoshu_self_mount_stage_for_manager SukiSU-Ultra)" = post-mount ]
    [ "$(luoshu_self_mount_stage_for_manager Magisk)" = post-fs-data ]
    [ "$(luoshu_self_mount_stage_for_manager unknown)" = post-fs-data ]
' sh "$ROOT"
grep -q 'mount_backend_runtime.sh' "$ROOT/post-fs-data.sh"

# Exercise the production router and its preserved v4 implementation together:
# APatch waits for post-mount, while Magisk still mounts in post-fs-data.
STAGE_MODULE="$TMP/stage-module"
STAGE_LOG="$TMP/stage.log"
mkdir -p "$STAGE_MODULE/common" "$STAGE_MODULE/config" "$STAGE_MODULE/.luoshu-runtime/core" "$STAGE_MODULE/.luoshu-runtime/compat/v227"
printf 'Demo\n' > "$STAGE_MODULE/config/active_font.conf"
cp "$ROOT/post-fs-data.sh" "$STAGE_MODULE/post-fs-data.sh"
cp "$ROOT/.luoshu-runtime/core/post-fs-data.sh" "$STAGE_MODULE/.luoshu-runtime/core/post-fs-data.sh"
cp "$ROOT/common/mount_self_backend.sh" "$STAGE_MODULE/common/mount_self_backend.sh"
cp "$ROOT/common/mount_backend_runtime.sh" "$STAGE_MODULE/common/mount_backend_runtime.sh"
cp "$ROOT/common/private_payload.sh" "$STAGE_MODULE/common/private_payload.sh"
cp "$ROOT/common/skip_mount_ownership.sh" "$STAGE_MODULE/common/skip_mount_ownership.sh"
cp "$ROOT/common/mount_nomount_backend.sh" "$STAGE_MODULE/common/mount_nomount_backend.sh"
cp "$ROOT/common/mount_backend_preferences.sh" "$STAGE_MODULE/common/mount_backend_preferences.sh"
cp "$ROOT/common/mount_backend_policy.sh" "$STAGE_MODULE/common/mount_backend_policy.sh"
cp "$ROOT/common/mount_provider_detection.sh" "$STAGE_MODULE/common/mount_provider_detection.sh"
cp "$ROOT/common/mount_provider_payload.sh" "$STAGE_MODULE/common/mount_provider_payload.sh"
cp "$ROOT/common/root_manager_detection.sh" "$STAGE_MODULE/common/root_manager_detection.sh"
cp "$ROOT/common/nomount_rule_json.py" "$STAGE_MODULE/common/nomount_rule_json.py"
cat >"$STAGE_MODULE/.luoshu-runtime/compat/v227/post-fs-data.sh" <<'EOF_BOOT_STAGE'
luoshu_detect_root_manager() { printf '%s\n' "$LUOSHU_TEST_ROOT_MANAGER"; }
luoshu_private_self_mount_ensure() { printf 'ensure\n' >>"$LUOSHU_STAGE_LOG"; }
exit 0
EOF_BOOT_STAGE

: >"$STAGE_LOG"
LUOSHU_STAGE_LOG="$STAGE_LOG" LUOSHU_TEST_ROOT_MANAGER=APatch \
LUOSHU_BACKEND_TEST_BOOT_ID=test-system \
LUOSHU_BACKEND_TEST_MODE=1 LUOSHU_BACKEND_TEST_MANAGER=APatch \
sh "$STAGE_MODULE/post-fs-data.sh"
test ! -s "$STAGE_LOG"
! grep -qx ensure "$STAGE_LOG"

: >"$STAGE_LOG"
LUOSHU_STAGE_LOG="$STAGE_LOG" LUOSHU_TEST_ROOT_MANAGER=Magisk \
LUOSHU_BACKEND_TEST_BOOT_ID=magisk-test \
LUOSHU_BACKEND_TEST_MODE=1 LUOSHU_BACKEND_TEST_MANAGER=Magisk \
sh "$STAGE_MODULE/post-fs-data.sh"
test ! -s "$STAGE_LOG"
test -f "$STAGE_MODULE/config/test-self-mounted"

# Production policy uses the real Meta detector; the test override exercises its
# selector without requiring a rooted host.
MODE=$(MODDIR="$ROOT" MODULE_DIR="$ROOT" sh -c '. "$1/common/mount_compat.sh"; luoshu_detect_mount_engine' sh "$ROOT")
[ "$MODE" = self-mount ]
INJECTED=$(MODDIR="$ROOT" MODULE_DIR="$ROOT" LUOSHU_META_TEST_ENGINE=hybrid-mount sh -c '. "$1/common/mount_compat.sh"; luoshu_detect_mount_engine' sh "$ROOT")
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
