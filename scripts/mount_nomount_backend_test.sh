#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3 (expected '$2', got '$1')"; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$3 (missing '$2')"; }
assert_not_contains() { ! grep -Fq -- "$2" "$1" || fail "$3 (unexpected '$2')"; }

MOD="$TMP/modules/Ziyu"
NM="$TMP/modules/nomount"
DB="$TMP/rules.db"
CALLS="$TMP/nm.calls"
mkdir -p "$MOD/config" "$MOD/.ziyu-state" "$MOD/.luoshu-payload/system/fonts" \
  "$MOD/.luoshu-payload/system/etc" "$NM/bin" "$MOD/common"
cp "$ROOT/common/nomount_rule_json.py" "$MOD/common/nomount_rule_json.py"
printf 'font-bytes\n' > "$MOD/.luoshu-payload/system/fonts/Ziyu Test.ttf"
printf '<fonts />\n' > "$MOD/.luoshu-payload/system/etc/fonts.xml"
FONT_HASH=$(sha256sum "$MOD/.luoshu-payload/system/fonts/Ziyu Test.ttf" | awk '{print $1}')
XML_HASH=$(sha256sum "$MOD/.luoshu-payload/system/etc/fonts.xml" | awk '{print $1}')
printf 'system/fonts/Ziyu Test.ttf|%s\nsystem/etc/fonts.xml|%s\n' "$FONT_HASH" "$XML_HASH" \
  > "$MOD/config/font-payload-manifest.conf"
: > "$DB"
: > "$CALLS"

cat > "$NM/bin/nm" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$NM_CALLS"
case "$1 ${2:-} ${3:-}" in
  'version  ')
    printf 'NoMount v2.0.0\n'
    ;;
  'rule list --json')
    printf '['
    _sep=''
    while IFS='|' read -r _target _source; do
      [ -n "$_target" ] || continue
      printf '%s{"virtual":"%s","real":"%s"}' "$_sep" "$_target" "$_source"
      _sep=,
    done < "$NM_DB"
    printf ']\n'
    ;;
  'rule add '* )
    _target="$3"
    _source="$4"
    [ "${NM_FAIL_TARGET:-}" != "$_target" ] || exit 7
    printf '%s|%s\n' "$_target" "$_source" >> "$NM_DB"
    ;;
  'rule del '* )
    _target="$3"
    _tmp="${NM_DB}.tmp"
    : > "$_tmp"
    while IFS='|' read -r _entry_target _entry_source; do
      [ "$_entry_target" = "$_target" ] || printf '%s|%s\n' "$_entry_target" "$_entry_source" >> "$_tmp"
    done < "$NM_DB"
    mv "$_tmp" "$NM_DB"
    ;;
  *) exit 2 ;;
esac
SH
chmod +x "$NM/bin/nm"

export MODDIR="$MOD" MODULE_DIR="$MOD" META_MODULE_DIR="$NM" META_NOMOUNT_CLI="$NM/bin/nm"
export LUOSHU_NOMOUNT_TEST_BOOT_ID=boot-A NM_DB="$DB" NM_CALLS="$CALLS"
. "$ROOT/common/mount_nomount_backend.sh"

luoshu_nomount_apply "$MOD" || fail 'two NoMount rules commit as one backend transaction'
assert_contains "$DB" "/system/fonts/Ziyu Test.ttf|$MOD/.luoshu-payload/system/fonts/Ziyu Test.ttf" 'font rule points into the private payload'
assert_contains "$DB" "/system/etc/fonts.xml|$MOD/.luoshu-payload/system/etc/fonts.xml" 'font XML rule is included in the same transaction'
GENERATION=$(sha256sum "$MOD/config/font-payload-manifest.conf" | awk '{print $1}')
assert_contains "$MOD/.ziyu-state/nomount-rules.current" "boot-A|$GENERATION|/system/fonts/Ziyu Test.ttf|$MOD/.luoshu-payload/system/fonts/Ziyu Test.ttf" 'ledger records boot, generation, target, and source'
assert_contains "$MOD/.ziyu-state/nomount-rules.current" 'state=committed' 'ledger commits only after verification'

# A failed second add must remove only this transaction's first rule and clear
# its partial ledger before the caller is allowed to fall back to legacy mounts.
luoshu_nomount_cleanup "$MOD" || fail 'cleanup of committed NoMount rules succeeds'
: > "$DB"
: > "$CALLS"
if NM_FAIL_TARGET=/system/etc/fonts.xml luoshu_nomount_apply "$MOD"; then
  fail 'a failed second rule must fail the NoMount transaction'
else
  RC=$?
fi
assert_eq "$RC" 1 'a cleanly rolled-back apply reports clean failure'
assert_eq "$(wc -l < "$DB" | tr -d '[:space:]')" 0 'partial NoMount rule was rolled back before fallback'
if [ -f "$MOD/.ziyu-state/nomount-rules.current" ]; then
  assert_not_contains "$MOD/.ziyu-state/nomount-rules.current" 'state=committed' 'failed transaction is never committed'
fi

# A target already owned by a user rule is a conflict. Roll back earlier Ziyu
# additions while leaving the pre-existing target and source untouched.
: > "$DB"
printf '/system/etc/fonts.xml|%s/user-fonts.xml\n' "$TMP" > "$DB"
: > "$CALLS"
if luoshu_nomount_apply "$MOD"; then fail 'a pre-existing user target must not be overwritten'; else RC=$?; fi
assert_eq "$RC" 1 'a target conflict with clean rollback is a clean failure'
assert_eq "$(wc -l < "$DB" | tr -d '[:space:]')" 1 'conflict rollback preserves the user rule and removes Ziyu additions'
assert_contains "$DB" "/system/etc/fonts.xml|$TMP/user-fonts.xml" 'user rule source is preserved'
assert_not_contains "$DB" '/system/fonts/Ziyu Test.ttf|' 'earlier Ziyu rule is rolled back on later conflict'

# Cleanup deletes only exact target/source pairs from this boot's ledger.
: > "$DB"
: > "$MOD/.ziyu-state/nomount-rules.current"
printf 'schema=ziyu-nomount-rules-v1\nstate=committed\nboot_id=boot-A\npayload_generation=%s\n' "$GENERATION" \
  > "$MOD/.ziyu-state/nomount-rules.current"
printf 'boot-A|%s|/system/fonts/Ziyu Test.ttf|%s/.luoshu-payload/system/fonts/Ziyu Test.ttf\n' "$GENERATION" "$MOD" \
  >> "$MOD/.ziyu-state/nomount-rules.current"
printf '/system/fonts/Ziyu Test.ttf|%s/.luoshu-payload/system/fonts/Ziyu Test.ttf\n' "$MOD" >> "$DB"
printf '/system/fonts/User-Regular.ttf|%s/user-fonts.ttf\n' "$TMP" >> "$DB"
luoshu_nomount_cleanup "$MOD" || fail 'current-boot cleanup succeeds'
assert_eq "$(wc -l < "$DB" | tr -d '[:space:]')" 1 'cleanup leaves an unrelated user rule intact'
assert_contains "$DB" '/system/fonts/User-Regular.ttf|' 'unrelated user rule remains active'

# The kernel rules are RAM-only. A prior boot's ledger is stale and must not
# issue rule del against a target that could now belong to another actor.
: > "$CALLS"
printf 'schema=ziyu-nomount-rules-v1\nstate=committed\nboot_id=boot-previous\npayload_generation=%s\n' "$GENERATION" \
  > "$MOD/.ziyu-state/nomount-rules.current"
printf 'boot-previous|%s|/system/fonts/Ziyu Test.ttf|%s/.luoshu-payload/system/fonts/Ziyu Test.ttf\n' "$GENERATION" "$MOD" \
  >> "$MOD/.ziyu-state/nomount-rules.current"
printf '/system/fonts/Ziyu Test.ttf|%s/user-replaced.ttf\n' "$TMP" > "$DB"
luoshu_nomount_cleanup "$MOD" || fail 'stale prior-boot ledger is discarded without CLI mutation'
assert_eq "$(cat "$CALLS")" '' 'stale boot ledger never calls nm rule del'
assert_contains "$DB" '/system/fonts/Ziyu Test.ttf|' 'prior-boot cleanup preserves current rule state'

printf 'NoMount transaction tests passed\n'
