#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
MODDIR="$TMP/module"
mkdir -p "$MODDIR/common" "$MODDIR/config" "$MODDIR/logs"
cp "$ROOT/common/util_functions.sh" "$ROOT/common/util_functions_core.sh" "$MODDIR/common/"
MODULE_DIR="$MODDIR"; export MODULE_DIR
. "$MODDIR/common/util_functions.sh"
LOCK="$MODDIR/.font_switch.lock"
fail(){ echo "font_switch_lock_recovery_test: FAIL - $1" >&2; exit 1; }
stale(){ rm -rf "$LOCK" 2>/dev/null || true; mkdir "$LOCK"; printf '999999\nstarttime=1\nboot_id=stale\n' > "$LOCK/pid"; }

stale
rm -f "$LOCK" 2>/dev/null || true
[ -e "$LOCK" ] || fail 'rm -f premise changed'
luoshu_font_lock_force_clear "$LOCK" || fail 'directory force_clear failed'
[ ! -e "$LOCK" ] || fail 'directory lock remains'
printf '999999\n' > "$LOCK"
luoshu_font_lock_force_clear "$LOCK" || fail 'legacy force_clear failed'
stale
: > "$LOCK.owner.a"
luoshu_font_lock_force_clear "$LOCK" || fail 'owner cleanup failed'
[ ! -e "$LOCK.owner.a" ] || fail 'owner temp remains'

stale
luoshu_font_lock_busy "$LOCK" && fail 'stale lock marked busy' || true
[ -e "$LOCK" ] || fail 'busy must not reap'
luoshu_font_lock_force_clear "$LOCK" >/dev/null 2>&1 || true
luoshu_font_lock_acquire "$LOCK" "$$" || fail 'live acquire failed'
luoshu_font_lock_busy "$LOCK" || fail 'live lock not busy'
luoshu_font_lock_release "$LOCK" "$$" || fail 'live release failed'

mkdir "$LOCK"
( sleep 0.2; printf '%s\n' "$$" > "$LOCK/pid" ) &
w=$!
LUOSHU_FONT_LOCK_INIT_GRACE_SECONDS=1; export LUOSHU_FONT_LOCK_INIT_GRACE_SECONDS
luoshu_font_lock_busy "$LOCK" || fail 'initializing lock falsely idle'
wait "$w"
luoshu_font_lock_force_clear "$LOCK" >/dev/null 2>&1 || true

mkdir "$LOCK"
LUOSHU_FONT_LOCK_INIT_GRACE_SECONDS=0.1; export LUOSHU_FONT_LOCK_INIT_GRACE_SECONDS
luoshu_font_lock_busy "$LOCK" && fail 'abandoned empty lock stayed busy' || true
[ -d "$LOCK" ] || fail 'busy reaped lock'
luoshu_font_lock_reap_stale "$LOCK" >/dev/null 2>&1 || true
[ ! -e "$LOCK" ] || fail 'reap failed'

cat > "$MODDIR/common/mix_weight_mode.sh" <<'EOS'
infer_mix_weight_mode() { printf 'auto\n'; }
EOS

# The compatibility name delegates to this same worker, sharing its busy guard.
# Submitting both consecutively would correctly reject the second running job.
grep -q 'exec sh.*weighted_mix_task.sh' "$ROOT/common/multiweight_mix_task.sh" || fail 'compatibility worker missing'
grep -q 'mix_router.sh' "$ROOT/common/weighted_mix_task.sh" || fail 'compatibility entry missing preparation router'
grep -q 'luoshu_font_lock_reap_stale' "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh" || fail 'selected worker missing stale-lock recovery'


grep -q 'luoshu_font_lock_force_clear' "$ROOT/.luoshu-runtime/compat/v227/post-fs-data.sh" || fail 'post-fs force_clear missing'
for f in common/legacy_v14_4/v142_weighted_mix.sh .luoshu-runtime/core/service.sh common/device_font_cache.sh common/device_font_boot_verify.sh; do
  grep -q 'luoshu_font_lock_busy' "$ROOT/$f" || fail "$f busy missing"
done
# Root service is now a router: it must select the preserved v4 service when legacy mode is absent,
# while legacy mode skips v4 rebuild work and consumes the shared mount verifier.
grep -q '.luoshu-runtime/core/service.sh' "$ROOT/service.sh" || fail 'service router missing v4 backend'
grep -q 'font_runtime_legacy_v14_4.conf' "$ROOT/service.sh" || fail 'service router missing legacy mode guard'
grep -q 'sh "$MODDIR/common/device_font_load_verify.sh" verify' "$ROOT/service.sh" || fail 'legacy router missing shared PID 1 readback'
grep -q 'backend-load-verifier-missing' "$ROOT/service.sh" || fail 'legacy router can downgrade missing verifier to marker success'
grep -q 'luoshu_font_lock_force_clear' "$ROOT/common/module_update_state.sh" || fail 'update force_clear missing'
echo 'font_switch_lock_recovery_test: PASS'
