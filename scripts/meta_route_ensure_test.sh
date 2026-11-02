#!/bin/sh
# The Meta preference must work without users editing Hybrid Mount TOML:
# ensure adds a Ziyu-scoped VFS rule, refuses to touch explicit user rules,
# and restores the backup when readback verification fails.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
ADB="$TMP/adb"
HYBRID="$ADB/modules/hybrid_mount"
MOD="$ADB/modules/Ziyu"
CONFIG="$ADB/hybrid-mount/config.toml"
mkdir -p "$HYBRID" "$MOD/config" "$(dirname "$CONFIG")"
printf 'id=hybrid_mount\nname=Hybrid Mount\nmetamodule=1\n' > "$HYBRID/module.prop"
printf 'id=Ziyu\n' > "$MOD/module.prop"
cat > "$HYBRID/hybrid-mount" <<'SH'
#!/usr/bin/env bash
[ "$*" = 'runtime status' ] || exit 2
printf '%s\n' '{"supported":false,"modules":[]}'
SH
chmod +x "$HYBRID/hybrid-mount"
printf 'default_mode="overlay"\n' > "$CONFIG"
printf '{"supported":false,"modules":[]}\n' > "$TMP/status"

ensure() {
    ZIYU_META_DETECT_ROOT="$ADB" ZIYU_META_TEST_ACTIVE_DIR="$HYBRID" \
    ZIYU_META_TEST_ASSUME_ACTIVE=1 MODDIR="$MOD" \
    "$1" -c '
        . "$1/common/meta_mount_detection.sh"
        ziyu_meta_mount_detect >/dev/null
        if [ "${3:-}" = stub-fail ]; then
            _ziyu_meta_hybrid_selected() { return 1; }
        fi
        if ziyu_meta_hybrid_ensure_vfs_rule; then RC=0; else RC=$?; fi
        printf "rc=%s result=%s detail=%s\n" "$RC" "${ZIYU_META_ENSURE_RESULT:-}" "${ZIYU_META_ENSURE_DETAIL:-}"
    ' _ "$ROOT" "" "${2:-}"
}

detect_state() {
    ZIYU_META_DETECT_ROOT="$ADB" ZIYU_META_TEST_ACTIVE_DIR="$HYBRID" \
    ZIYU_META_TEST_ASSUME_ACTIVE=1 MODDIR="$MOD" \
    "$1" -c '
        . "$1/common/meta_mount_detection.sh"
        ziyu_meta_mount_detect >/dev/null
        printf "%s|%s" "$META_READY" "$META_USABLE"
    ' _ "$ROOT"
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
eq() { [ "$1" = "$2" ] || fail "$3 (expected [$2], got [$1])"; }

SHELL_BIN="${ZIYU_TEST_SHELL:-/system/bin/sh}"
command -v "${ZIYU_TEST_SHELL:-bash}" >/dev/null 2>&1 && SHELL_BIN="${ZIYU_TEST_SHELL:-bash}"

# 1. Upstream Overlay default gets an owned VFS rule, Meta becomes usable.
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=0 result=applied detail='"$CONFIG.ziyu-backup" 'overlay default gets a VFS rule'
eq "$(detect_state "$SHELL_BIN")" '1|1' 'rule flips detection to ready and usable'
command grep -q '\[rules.Ziyu\]' "$CONFIG" || fail 'rule block missing from config'
command grep -q 'default_mode="overlay"' "$CONFIG" || fail 'global default must be preserved'
[ -f "$CONFIG.ziyu-backup" ] || fail 'backup missing'
command grep -q 'rules.Ziyu' "$CONFIG.ziyu-backup" && fail 'backup must not contain the rule'
[ "$(command grep -c 'ziyu-vfs-rule' "$CONFIG")" = 2 ] || fail 'marker lines wrong'

# 2. Second run is idempotent, no duplicate rule.
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=1 result=not-needed detail=hybrid-pure-vfs-scoped-unload' 'second run sees a pure VFS route'
[ "$(command grep -c '\[rules.Ziyu\]' "$CONFIG")" = 1 ] || fail 'duplicate rule appended'

# 3. Explicit module overlay rule wins and is never touched.
printf 'default_mode="overlay"\n[rules.Ziyu]\ndefault_mode="overlay"\n' > "$CONFIG"
rm -f "$CONFIG.ziyu-backup"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=2 result=explicit-rule-wins detail=module-overlay' 'explicit module rule wins'
command grep -q 'ziyu-vfs-rule' "$CONFIG" && fail 'config was modified despite explicit rule'

# 4. Unsafe path rules are respected, not rewritten.
printf 'default_mode="vfs"\n[rules.Ziyu.paths]\nsystem="magic"\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=2 result=explicit-rule-wins detail=module-paths-unsafe' 'unsafe path rules win'
command grep -q 'ziyu-vfs-rule' "$CONFIG" && fail 'path config was modified'

# 5. Inline (unparsed) config must never be touched.
printf 'default_mode="vfs"\n[rules.Ziyu]\npaths={system="magic"}\n' > "$CONFIG"
out=$(ensure "$SHELL_BIN")
eq "$out" 'rc=1 result=not-needed detail=hybrid-config-unverified' 'unverified config untouched'

# 6. An empty user section is refused to avoid duplicate TOML tables.
printf 'default_mode="overlay"\n[rules.Ziyu]\n' > "$CONFIG"
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
command grep -q 'ziyu-vfs-rule' "$CONFIG" && fail 'restore left the rule behind'
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
