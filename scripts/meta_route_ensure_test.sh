#!/bin/sh
# The Meta preference must work without users editing Hybrid Mount TOML:
# ensure adds a LuoShu-scoped VFS rule, refuses to touch explicit user rules,
# and restores the backup when readback verification fails.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
ADB="$TMP/adb"
HYBRID="$ADB/modules/hybrid_mount"
MOD="$ADB/modules/LuoShu"
CONFIG="$ADB/hybrid-mount/config.toml"
mkdir -p "$HYBRID" "$MOD/config" "$(dirname "$CONFIG")"
printf 'id=hybrid_mount\nname=Hybrid Mount\nmetamodule=1\n' > "$HYBRID/module.prop"
printf 'id=LuoShu\n' > "$MOD/module.prop"
cat > "$HYBRID/hybrid-mount" <<'SH'
#!/usr/bin/env bash
[ "$*" = 'runtime status' ] || exit 2
printf '%s\n' '{"supported":false,"modules":[]}'
SH
chmod +x "$HYBRID/hybrid-mount"
printf 'default_mode="overlay"\n' > "$CONFIG"
printf '{"supported":false,"modules":[]}\n' > "$TMP/status"

ensure() {
    LUOSHU_META_DETECT_ROOT="$ADB" LUOSHU_META_TEST_ACTIVE_DIR="$HYBRID" \
    LUOSHU_META_TEST_ASSUME_ACTIVE=1 MODDIR="$MOD" \
    "$1" -c '
        . "$1/common/meta_mount_detection.sh"
        luoshu_meta_mount_detect >/dev/null
        if [ "${3:-}" = stub-fail ]; then
            _luoshu_meta_hybrid_selected() { return 1; }
        fi
        if luoshu_meta_hybrid_ensure_vfs_rule; then RC=0; else RC=$?; fi
        printf "rc=%s result=%s detail=%s\n" "$RC" "${LUOSHU_META_ENSURE_RESULT:-}" "${LUOSHU_META_ENSURE_DETAIL:-}"
    ' _ "$ROOT" "" "${2:-}"
}

detect_state() {
    LUOSHU_META_DETECT_ROOT="$ADB" LUOSHU_META_TEST_ACTIVE_DIR="$HYBRID" \
    LUOSHU_META_TEST_ASSUME_ACTIVE=1 MODDIR="$MOD" \
    "$1" -c '
        . "$1/common/meta_mount_detection.sh"
        luoshu_meta_mount_detect >/dev/null
        printf "%s|%s" "$META_READY" "$META_USABLE"
    ' _ "$ROOT"
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
eq() { [ "$1" = "$2" ] || fail "$3 (expected [$2], got [$1])"; }

SHELL_BIN="${LUOSHU_TEST_SHELL:-/system/bin/sh}"
command -v "${LUOSHU_TEST_SHELL:-bash}" >/dev/null 2>&1 && SHELL_BIN="${LUOSHU_TEST_SHELL:-bash}"

# 1. Upstream Overlay default gets an owned VFS rule, Meta becomes usable.
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=0 result=applied detail='"$CONFIG.luoshu-backup" 'overlay default gets a VFS rule'
eq "$(detect_state "$SHELL_BIN")" '1|1' 'rule flips detection to ready and usable'
command grep -q '\[rules.LuoShu\]' "$CONFIG" || fail 'rule block missing from config'
command grep -q 'default_mode="overlay"' "$CONFIG" || fail 'global default must be preserved'
[ -f "$CONFIG.luoshu-backup" ] || fail 'backup missing'
command grep -q 'rules.LuoShu' "$CONFIG.luoshu-backup" && fail 'backup must not contain the rule'
[ "$(command grep -c 'ziyu-luoshu-vfs-rule' "$CONFIG")" = 2 ] || fail 'marker lines wrong'

# 2. Second run is idempotent, no duplicate rule.
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=1 result=not-needed detail=hybrid-pure-vfs-scoped-unload' 'second run sees a pure VFS route'
[ "$(command grep -c '\[rules.LuoShu\]' "$CONFIG")" = 1 ] || fail 'duplicate rule appended'

# 3. Explicit module overlay rule wins and is never touched.
printf 'default_mode="overlay"\n[rules.LuoShu]\ndefault_mode="overlay"\n' > "$CONFIG"
rm -f "$CONFIG.luoshu-backup"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=2 result=explicit-rule-wins detail=module-overlay' 'explicit module rule wins'
command grep -q 'ziyu-luoshu-vfs-rule' "$CONFIG" && fail 'config was modified despite explicit rule'

# 4. Unsafe path rules are respected, not rewritten.
printf 'default_mode="vfs"\n[rules.LuoShu.paths]\nsystem="magic"\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=2 result=explicit-rule-wins detail=module-paths-unsafe' 'unsafe path rules win'
command grep -q 'ziyu-luoshu-vfs-rule' "$CONFIG" && fail 'path config was modified'

# 5. Inline (unparsed) config must never be touched.
printf 'default_mode="vfs"\n[rules.LuoShu]\npaths={system="magic"}\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=1 result=not-needed detail=hybrid-config-unverified' 'unverified config untouched'

# 6. An empty user section is refused to avoid duplicate TOML tables.
printf 'default_mode="overlay"\n[rules.LuoShu]\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=2 result=explicit-rule-wins detail=module-section-empty' 'empty module section wins'

# 7. When the route is not the blocker, nothing happens.
printf 'default_mode="vfs"\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=1 result=not-needed detail=hybrid-pure-vfs-scoped-unload' 'pure VFS route needs no rule'

# 8. Readback failure restores the backup.
printf 'default_mode="overlay"\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN" stub-fail)
eq "$out" 'rc=1 result=verify-failed detail=' 'failed verification restores backup'
command grep -q 'ziyu-luoshu-vfs-rule' "$CONFIG" && fail 'restore left the rule behind'
command grep -q 'default_mode="overlay"' "$CONFIG" || fail 'restore lost the original config'

# 9. Missing config is reported, not created.
rm -f "$CONFIG"
out=$(ensure "$SHELL_BIN")
case "$out" in
    rc=1\ result=not-needed*) ;;
    *) fail "missing config must not be created (got: $out)" ;;
esac
[ ! -e "$CONFIG" ] || fail 'config was created by ensure'

printf 'meta_route_ensure_test: PASS\n'
