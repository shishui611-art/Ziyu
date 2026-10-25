#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t luoshu-customize)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

OLD="$TMP/active/LuoShu"
NEW="$TMP/update/ziyu"
mkdir -p "$OLD/config" "$NEW/config" "$NEW/common" "$NEW/.luoshu-runtime/compat/v227"
cp "$ROOT/common/install_ui.sh" "$ROOT/common/mount_backend_preferences.sh" \
    "$ROOT/common/private_payload.sh" \
    "$ROOT/common/apatch_boot_prepare.sh" "$ROOT/common/root_manager_detection.sh" \
    "$NEW/common/"
cp "$ROOT/.luoshu-runtime/compat/v227/customize.sh" "$NEW/.luoshu-runtime/compat/v227/customize.sh"
printf 'id=LuoShu\nversion=v2.0.2\nversionCode=20002\n' > "$OLD/module.prop"
printf 'id=ziyu\nversion=v-test\nversionCode=1\n' > "$NEW/module.prop"
touch "$OLD/disable" "$NEW/disable"
printf '2\n' > "$OLD/config/font-boot-failures"
printf 'state=quarantined\n' > "$OLD/config/font-payload-quarantine.conf"
printf 'preferred_backend=self\n' > "$OLD/config/mount-backend-preference.conf"

# The production identity migrator uses Android's fixed /data/adb paths. Keep
# this behavior fixture-local while asserting customize.sh passes both module
# roots through its required identity-migration entry points.
cat > "$NEW/common/identity_migration.sh" <<'EOF'
ziyu_identity_select_old() {
    [ -n "${LUOSHU_OLD_MOD:-}" ] && [ -d "$LUOSHU_OLD_MOD" ] || return 1
    printf 'select_old|%s|%s\n' "$MODPATH" "$LUOSHU_OLD_MOD" >> "$MODPATH/config/identity-migration-test.calls"
}
ziyu_identity_finish() {
    [ "${1:-}" = "$MODPATH" ] && [ "${2:-}" = "${LUOSHU_OLD_MOD:-}" ] || return 1
    printf 'finish|%s|%s\n' "$1" "$2" >> "$MODPATH/config/identity-migration-test.calls"
}
EOF
IDENTITY_CALLS="$NEW/config/identity-migration-test.calls"

# customize.sh intentionally tolerates unavailable Android commands during host-side tests. The
# regression contract is that a flash immediately re-enables both trees and migrates old backend
# preferences to the automatic boot policy.
if ! MODPATH="$NEW" LUOSHU_OLD_MOD="$OLD" sh -x "$ROOT/customize.sh" >"$TMP/direct.log" 2>&1; then
    cat "$TMP/direct.log" >&2
    echo 'direct customize execution failed' >&2
    exit 1
fi

test ! -e "$OLD/disable"
test ! -e "$NEW/disable"
test ! -e "$OLD/config/font-boot-failures"
test ! -e "$OLD/config/font-payload-quarantine.conf"
test "$(sed -n 's/^preferred_backend=//p' "$NEW/config/mount-backend-preference.conf")" = auto
grep -Fqx "select_old|$NEW|$OLD" "$IDENTITY_CALLS"
grep -Fqx "finish|$NEW|$OLD" "$IDENTITY_CALLS"
test "$(grep -Fxc "select_old|$NEW|$OLD" "$IDENTITY_CALLS")" -eq 1
test "$(grep -Fxc "finish|$NEW|$OLD" "$IDENTITY_CALLS")" -eq 1

# APatch sources customize.sh. The wrapper must return to the manager instead of
# exiting its parent shell before the staged module is committed.
SOURCED_MARKER="$TMP/source-continued"
if ! MODPATH="$NEW" LUOSHU_OLD_MOD="$OLD" sh -x -c '
    . "$1" >"$3" 2>&1
    printf "continued\n" > "$2"
' sh "$ROOT/customize.sh" "$SOURCED_MARKER" "$TMP/sourced.log"; then
    cat "$TMP/sourced.log" >&2 || true
    echo 'sourced customize execution failed' >&2
    exit 1
fi
test "$(cat "$SOURCED_MARKER")" = continued
test "$(grep -Fxc "select_old|$NEW|$OLD" "$IDENTITY_CALLS")" -eq 2
test "$(grep -Fxc "finish|$NEW|$OLD" "$IDENTITY_CALLS")" -eq 2

echo 'Module flash re-enable and APatch source-safety checks passed.'
