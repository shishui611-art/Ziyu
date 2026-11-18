#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
mkdir -p "$ROOT/.runtime-work"
TMP="$(mktemp -d "$ROOT/.runtime-work/mount-preferences-test.XXXXXX")"
TMP="$(CDPATH= cd -- "$TMP" && pwd -P)"
case "$TMP" in "$ROOT"/.runtime-work/mount-preferences-test.*) ;; *) exit 1 ;; esac
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
value() { sed -n "s/^$2=//p" "$1/config/mount-backend.conf" | head -n1; }
assert_json() { grep -q "$1" "$2" || fail "$3"; }

BOOT=provider-first-test-boot
MOD=''
make_module() {
  MOD="$1"
  mkdir -p "$MOD/config" "$MOD/logs" "$MOD/common" "$MOD/.ziyu-payload/system/fonts"
  cp "$ROOT/common/mount_backend_details.sh" "$ROOT/common/legacy_identity.sh" "$MOD/common/"
  printf 'id=Ziyu\nversion=test\nversionCode=1\n' > "$MOD/module.prop"
  printf 'DemoFont\n' > "$MOD/config/active_font.conf"
  printf 'fixture-font\n' > "$MOD/.ziyu-payload/system/fonts/Roboto-Regular.ttf"
}

hook() {
  local stage="$1" provider_state="$2" provider_id="$3" verify="$4" engine=none
  [[ "$provider_id" == hybrid-mount ]] && engine=hybrid-mount
  MODDIR="$MOD" MODULE_DIR="$MOD" \
  ZIYU_BACKEND_TEST_MODE=1 \
  ZIYU_BACKEND_TEST_BOOT_ID="$BOOT" \
  ZIYU_BACKEND_TEST_MANAGER=KernelSU \
  ZIYU_BACKEND_TEST_META_ENGINE="$engine" \
  ZIYU_BACKEND_TEST_PROVIDER_STATE="$provider_state" \
  ZIYU_BACKEND_TEST_PROVIDER_ID="$provider_id" \
  ZIYU_BACKEND_TEST_PROVIDER_LAYOUT=nested-system \
  ZIYU_BACKEND_TEST_EXTERNAL_VERIFY_RESULT="$verify" \
  ZIYU_BACKEND_TEST_SELF_VERIFY_RESULT=pass \
  bash -c '. "$1/common/mount_backend_runtime.sh"; ziyu_mount_backend_hook "$2"' _ "$ROOT" "$stage"
}

pref() {
  MODDIR="$MOD" ZIYU_BACKEND_TEST_BOOT_ID="$BOOT" \
    sh "$ROOT/common/mount_backend_preferences.sh" "$@"
}

# Old saved or App-requested selections are accepted for compatibility, but
# always migrate to automatic so provider-first boot policy remains in control.
make_module "$TMP/provider"
printf 'preferred_backend=self\n' > "$MOD/config/mount-backend-preference.conf"
hook post-fs-data available hybrid-mount pass
[[ "$(value "$MOD" selected_backend)" == external ]] || fail 'legacy self preference overrode an available provider'
[[ "$(value "$MOD" active_backend)" == none ]] || fail 'provider should remain pending until its mount stage'
hook post-mount available hybrid-mount pass
[[ "$(value "$MOD" active_backend)" == external ]] || fail 'verified Hybrid provider was not activated'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'external route was not recorded as verified'

pref get > "$TMP/status"
assert_json '"preferredBackend":"auto"' "$TMP/status" 'legacy preference was not reported as automatic'
assert_json '"bootNomountObserved":false' "$TMP/status" 'NoMount capability was reported despite no current-boot NoMount probe'
assert_json '"bootNomountKernelUsable":false' "$TMP/status" 'current-boot NoMount capability was not reported'
assert_json '"selectedBackend":"external"' "$TMP/status" 'status did not expose the active provider choice'

cp "$MOD/config/mount-backend.conf" "$TMP/active-before"
pref set self > "$TMP/legacy-self"
assert_json '"preferredBackend":"auto"' "$TMP/legacy-self" 'legacy self request was not migrated to auto'
assert_json '"rebootRequired":false' "$TMP/legacy-self" 'legacy self request falsely reported a pending switch'
grep -qx 'preferred_backend=auto' "$MOD/config/mount-backend-preference.conf" || fail 'legacy self request was persisted instead of auto'
cmp "$TMP/active-before" "$MOD/config/mount-backend.conf" || fail 'legacy preference command changed live mount state'

pref set meta > "$TMP/legacy-meta"
assert_json '"preferredBackend":"auto"' "$TMP/legacy-meta" 'legacy Meta request was not migrated to auto'
assert_json '"rebootRequired":false' "$TMP/legacy-meta" 'legacy Meta request falsely reported a pending switch'
pref set manager > "$TMP/legacy-manager"
assert_json '"preferredBackend":"auto"' "$TMP/legacy-manager" 'legacy manager request was not migrated to auto'
assert_json '"policy":"automatic-provider-first"' "$TMP/legacy-manager" 'legacy manager request kept a separate policy'
cmp "$TMP/active-before" "$MOD/config/mount-backend.conf" || fail 'manager migration changed live mounts during a preference request'
pref cancel > "$TMP/cancel"
assert_json '"rebootRequired":false' "$TMP/cancel" 'cancel left a preference migration pending'
pref set overlayfs > "$TMP/overlayfs"
assert_json '"preferredBackend":"overlayfs"' "$TMP/overlayfs" 'OverlayFS preference was not persisted'
assert_json '"policy":"manual-overlayfs"' "$TMP/overlayfs" 'OverlayFS did not select its manual policy'
pref set magic > "$TMP/magic"
assert_json '"preferredBackend":"magic"' "$TMP/magic" 'Magic Mount preference was not persisted'
assert_json '"policy":"manual-magic-mount"' "$TMP/magic" 'Magic Mount did not select its manual policy'
pref set self_mount > "$TMP/self-mount"
assert_json '"preferredBackend":"self_mount"' "$TMP/self-mount" 'self-mount preference was not persisted'
assert_json '"policy":"manual-self-mount"' "$TMP/self-mount" 'self-mount did not select its manual policy'
grep -qx 'preferred_backend=self_mount' "$MOD/config/mount-backend-preference.conf" || fail 'self-mount preference was not written to disk'
pref set auto > "$TMP/auto-again"
if pref set unsafe > "$TMP/invalid"; then fail 'invalid preference was accepted'; fi
grep -qx 'preferred_backend=auto' "$MOD/config/mount-backend-preference.conf" || fail 'invalid preference changed the normalized policy'

# Explicit self-mount waits for the KernelSU mount hook and route verification.
make_module "$TMP/self"
printf 'preferred_backend=self_mount\n' > "$MOD/config/mount-backend-preference.conf"
hook post-fs-data absent none pass
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'missing provider did not select self mount'
[[ "$(value "$MOD" active_backend)" == none ]] || fail 'KernelSU self mount ran before post-mount'
hook post-mount absent none pass
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'private self-mount fallback did not activate'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'self-mount was reported before verification'

# Automatic provider route failure falls back to self and verifies before success.
make_module "$TMP/provider-failed"
hook post-fs-data available hybrid-mount pass
hook post-mount available hybrid-mount fail
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'provider failure did not select self fallback'
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'unverified provider was reported active'
[[ "$(value "$MOD" fallback_used)" == 1 ]] || fail 'provider failure did not record fallback'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'self fallback was reported before verification'
[[ -f "$MOD/config/test-self-mounted" ]] || fail 'provider failure did not try self mounting'

# An absent provider in automatic mode stages self-mount for the root manager's mount phase.
make_module "$TMP/provider-absent"
hook post-fs-data absent none pass
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'absent provider did not select automatic self mount'
[[ "$(value "$MOD" active_backend)" == none ]] || fail 'automatic self mount ran before the KernelSU mount hook'
[[ "$(value "$MOD" fallback_used)" == 0 ]] || fail 'normal automatic selection was recorded as a fallback'
[[ ! -f "$MOD/config/test-self-mounted" ]] || fail 'absent provider mounted before the KernelSU mount hook'
hook post-mount absent none pass
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'absent provider did not activate self after the mount hook'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'absent provider self route was not verified'

# A legacy manager record stays pending until the normal early mount boundary.
make_module "$TMP/legacy-manager-live"
printf 'preferred_backend=manager\n' > "$MOD/config/mount-backend-preference.conf"
printf 'boot_id=%s\npreferred_backend=manager\nselected_backend=manager\nactive_backend=none\nverification=pending\n' "$BOOT" > "$MOD/config/mount-backend.conf"
cp "$MOD/config/mount-backend.conf" "$TMP/manager-before"
pref get > "$TMP/manager-pending"
assert_json '"preferredBackend":"auto"' "$TMP/manager-pending" 'stored manager choice was not read as automatic'
assert_json '"bootPreference":"manager"' "$TMP/manager-pending" 'legacy live choice was hidden'
assert_json '"pending":true' "$TMP/manager-pending" 'legacy live manager record falsely appeared migrated'
cmp "$TMP/manager-before" "$MOD/config/mount-backend.conf" || fail 'status read changed a live manager decision'
hook post-fs-data available hybrid-mount pass
[[ "$(value "$MOD" preferred_backend)" == auto ]] || fail 'early hook restored the old manager preference'
[[ "$(value "$MOD" selected_backend)" == external ]] || fail 'legacy manager choice bypassed the detected metamodule'
hook post-mount available hybrid-mount pass
[[ "$(value "$MOD" active_backend)" == external ]] || fail 'migrated manager choice did not verify the provider'
pref get > "$TMP/manager-ready"
assert_json '"pending":false' "$TMP/manager-ready" 'migration remained pending after automatic activation'

make_module "$TMP/legacy-manager-no-provider"
printf 'preferred_backend=manager\n' > "$MOD/config/mount-backend-preference.conf"
printf 'boot_id=%s\npreferred_backend=manager\nselected_backend=manager\nactive_backend=none\nverification=pending\n' "$BOOT" > "$MOD/config/mount-backend.conf"
hook post-fs-data absent none pass
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'KSU without a provider retained the legacy manager backend'
[[ "$(value "$MOD" fallback_used)" == 0 ]] || fail 'normal KSU self selection was marked as fallback'
hook post-mount absent none pass
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'KSU without a provider did not activate self mount'

# Exclusion is an explicit provider decision, including on automatic mode.
make_module "$TMP/provider-excluded"
hook post-fs-data excluded hybrid-mount pass
[[ "$(value "$MOD" selected_backend)" == none ]] || fail 'provider exclusion selected an alternate backend'
[[ "$(value "$MOD" verification)" == not-applicable ]] || fail 'provider exclusion was not recorded as skipped'
[[ "$(value "$MOD" fallback_used)" == 0 ]] || fail 'provider exclusion triggered automatic fallback'
[[ ! -f "$MOD/config/test-self-mounted" ]] || fail 'self mount ran despite provider exclusion'

# Previous-boot observations are historical and must not be exposed as live.
make_module "$TMP/stale"
printf 'boot_id=old-boot\nselected_backend=external\nactive_backend=external\nprovider_state=available\nprovider_id=hybrid-mount\nverification=passed\n' \
  > "$MOD/config/mount-backend.conf"
BOOT=current-boot
pref get > "$TMP/stale-status"
assert_json '"bootNomountObserved":false' "$TMP/stale-status" 'stale state was marked as current-boot observed'
assert_json '"providerState":"unknown"' "$TMP/stale-status" 'stale provider state was exposed as live'
assert_json '"verification":"pending"' "$TMP/stale-status" 'stale verification was exposed as live'

printf 'Mount preference migration, provider-first selection, current-boot status and explicit mode failure checks passed\n'
