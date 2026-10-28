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
cp "$ROOT/common/font_switch_lock.sh" "$MOD/common/font_switch_lock.sh"
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
    [ "${NM_FAIL_DEL_TARGET:-}" != "$_target" ] || exit 8
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

# NoMount's external metamount publishes module/system paths without Ziyu's
# rescue ledger. A managed soft reload retires those exact pairs as well.
printf 'id=nomount\n' > "$NM/module.prop"
export ZIYU_KSU_POST_STOP_RELOAD=1
getprop() { case "$1" in init.svc.zygote*) printf '%s\n' "${NM_TEST_ZYGOTE_STATE:-stopped}" ;; esac; }
pidof() { return 1; }
readlink() {
    case "$1" in
        /proc/self/ns/mnt) printf 'mnt:[fixture]\n' ;;
        /proc/1/ns/mnt) printf 'mnt:[%s]\n' "${NM_TEST_INIT_NAMESPACE:-fixture}" ;;
        *) command readlink "$@" ;;
    esac
}
: > "$DB"
printf '/system/fonts/Ziyu Test.ttf|%s/system/fonts/Ziyu Test.ttf\n' "$MOD" >> "$DB"
printf '/system/bin/ziyu|%s/system/bin/ziyu\n' "$MOD" >> "$DB"
printf '/system/fonts/Other.ttf|%s/other-module/system/fonts/Other.ttf\n' "$TMP" >> "$DB"
luoshu_nomount_provider_reload_cleanup "$MOD" || fail 'managed provider cleanup retires published Ziyu rules'
assert_eq "$(wc -l < "$DB" | tr -d '[:space:]')" 1 'provider cleanup keeps other modules untouched'
assert_contains "$DB" '/system/fonts/Other.ttf|' 'unrelated provider rule remains'

# A failed delete must restore all previously retired pairs, retaining history
# and the pending font rather than claiming that the next generation activated.
printf '/system/bin/ziyu|%s/system/bin/ziyu\n' "$MOD" >> "$DB"
printf '/system/fonts/Ziyu Test.ttf|%s/system/fonts/Ziyu Test.ttf\n' "$MOD" >> "$DB"
if NM_FAIL_DEL_TARGET='/system/fonts/Ziyu Test.ttf' luoshu_nomount_provider_reload_cleanup "$MOD"; then
    fail 'failed provider deletion blocks reload'
fi
assert_contains "$DB" "/system/bin/ziyu|$MOD/system/bin/ziyu" 'earlier retired rule restored on failure'
assert_contains "$DB" '/system/fonts/Ziyu Test.ttf|' 'failed rule remains intact'
assert_contains "$DB" '/system/fonts/Other.ttf|' 'rollback preserves unrelated module'
[ ! -e "$MOD/.ziyu-state/nomount-provider-reload.removed" ] || fail 'confirmed rollback clears transaction ledger'

: > "$CALLS"
if NM_TEST_INIT_NAMESPACE=other luoshu_nomount_provider_reload_cleanup "$MOD"; then
    fail 'provider cleanup requires PID1 mount namespace'
fi
assert_eq "$(cat "$CALLS")" '' 'namespace mismatch makes no provider calls'
: > "$CALLS"
if NM_TEST_ZYGOTE_STATE=running luoshu_nomount_provider_reload_cleanup "$MOD"; then fail 'running Android apps block provider cleanup'; fi
assert_eq "$(cat "$CALLS")" '' 'pre-stop cleanup performs no provider calls'
. "$ROOT/common/font_switch_lock.sh"
luoshu_font_lock_acquire "$MOD/.font_switch.lock" "$$" || fail 'fixture owns the font transaction lock'
: > "$CALLS"
if luoshu_nomount_provider_reload_cleanup "$MOD"; then fail 'a concurrent font transaction blocks provider cleanup'; fi
assert_eq "$(cat "$CALLS")" '' 'busy font transaction is never mutated by cleanup'
luoshu_font_lock_release "$MOD/.font_switch.lock" "$$" || fail 'fixture lock is released'

printf 'boot_id=boot-A\n/system/bin/ziyu|%s/system/bin/ziyu\n' "$MOD" \
  > "$MOD/.ziyu-state/nomount-provider-reload.removed"
: > "$CALLS"
if luoshu_nomount_provider_reload_cleanup "$MOD"; then fail 'an interrupted current-boot cleanup is not overwritten'; fi
assert_eq "$(cat "$CALLS")" '' 'incomplete transaction blocks before querying or deleting provider rules'
sed -i 's/^boot_id=boot-A$/boot_id=boot-previous/' "$MOD/.ziyu-state/nomount-provider-reload.removed"
luoshu_nomount_provider_reload_cleanup "$MOD" || fail 'kernel reboot retires stale cleanup ledger safely'
assert_eq "$(wc -l < "$DB" | tr -d '[:space:]')" 1 'new-boot cleanup still preserves the unrelated module'

# A source under our module cannot authorize an unrelated virtual destination.
printf '/product/overlay/unrelated.apk|%s/system/fonts/Ziyu Test.ttf\n' "$MOD" >> "$DB"
: > "$CALLS"
if luoshu_nomount_provider_reload_cleanup "$MOD"; then fail 'mismatched partition target is refused'; fi
! grep -q '^rule del ' "$CALLS" || fail 'complete plan is checked before the first deletion'

# An upgrade installs new code after the old cleanup hook has already failed.
# Run the real post-fs-data path with only the proc boot-id input redirected.
printf 'boot-A\n' > "$TMP/boot-id"
sed "s|/proc/sys/kernel/random/boot_id|$TMP/boot-id|g" "$ROOT/emulated-soft-reboot.sh" > "$MOD/emulated-soft-reboot.sh"
printf 'ziyu_live_work_root() { printf "%%s/work\\n" "$MODDIR"; }\n' > "$MOD/common/font_live_state.sh"
printf 'ziyu_provider_reload_check() { return 0; }\n' > "$MOD/common/mount_provider_reload.sh"
cp "$ROOT/common/mount_nomount_backend.sh" "$MOD/common/mount_nomount_backend.sh"
printf 'boot_id=boot-A\nprovider_id=nomount\nactive_backend=external\n' > "$MOD/config/mount-backend.conf"
: > "$CALLS"
ZIYU_KSU_POST_STOP_RELOAD=0 sh "$MOD/emulated-soft-reboot.sh" || fail 'pre-stop hook defers NoMount cleanup'
assert_contains "$MOD/config/temporary-root-soft-reboot.conf" 'state=nomount-cleanup-pending' 'managed cleanup is scheduled for post-stop'
assert_eq "$(cat "$CALLS")" '' 'actual emulated hook never writes provider rules before Android stops'
sed "s|/proc/sys/kernel/random/boot_id|$TMP/boot-id|g" "$ROOT/post-fs-data.sh" > "$MOD/post-fs-data.sh"
cp "$ROOT/common/mount_nomount_backend.sh" "$MOD/common/mount_nomount_backend.sh"
cat > "$MOD/emulated-soft-reboot.sh" <<'SH'
#!/bin/sh
printf 'cleanup\n' >> "${0%/*}/config/upgrade-stage.calls"
[ "${NM_UPGRADE_RETRY_FAIL:-0}" != 1 ] || exit 1
printf 'boot_id=boot-A\nstate=ready\n' > "${0%/*}/config/temporary-root-soft-reboot.conf"
SH
printf '#!/bin/sh\nexit 0\n' > "$MOD/common/font_live_switch.sh"
cat > "$MOD/common/next_boot_payload.sh" <<'SH'
luoshu_next_boot_activate() { printf 'activate\n' >> "$MODDIR/config/upgrade-stage.calls"; return 2; }
SH
printf '#!/bin/sh\nexit 0\n' > "$MOD/common/mount_backend_runtime.sh"
printf 'boot_id=boot-A\nstate=failed\nreason=nomount-still-routes-luoshu-payload\n' > "$MOD/config/temporary-root-soft-reboot.conf"
sh "$MOD/post-fs-data.sh" || fail 'upgraded post-fs hook recovers the old NoMount cleanup failure'
assert_eq "$(cat "$MOD/config/upgrade-stage.calls")" "$(printf 'cleanup\nactivate')" 'cleanup is verified before activation'
: > "$MOD/config/upgrade-stage.calls"
printf 'boot_id=boot-A\nstate=nomount-cleanup-pending\n' > "$MOD/config/temporary-root-soft-reboot.conf"
sh "$MOD/post-fs-data.sh" || fail 'post-fs resumes the normal deferred cleanup'
assert_eq "$(cat "$MOD/config/upgrade-stage.calls")" "$(printf 'cleanup\nactivate')" 'deferred cleanup completes before pending activation'
: > "$MOD/config/upgrade-stage.calls"
printf 'boot_id=boot-A\nstate=failed\nreason=nomount-still-routes-luoshu-payload\n' > "$MOD/config/temporary-root-soft-reboot.conf"
if NM_UPGRADE_RETRY_FAIL=1 sh "$MOD/post-fs-data.sh"; then fail 'failed cleanup retry blocks activation'; fi
assert_eq "$(cat "$MOD/config/upgrade-stage.calls")" cleanup 'failed retry never activates a pending generation'
: > "$MOD/config/upgrade-stage.calls"
printf 'boot_id=boot-A\nstate=failed\nreason=nomount-provider-owned-rule-cleanup-unconfirmed\n' > "$MOD/config/temporary-root-soft-reboot.conf"
if sh "$MOD/post-fs-data.sh"; then fail 'an unconfirmed new cleanup remains blocked'; fi
assert_eq "$(cat "$MOD/config/upgrade-stage.calls")" '' 'only the legacy failure is eligible for the bounded upgrade retry'
printf 'NoMount transaction tests passed\n'
