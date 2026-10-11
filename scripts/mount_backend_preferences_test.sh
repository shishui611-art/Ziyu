#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
value() { sed -n "s/^$2=//p" "$1/config/mount-backend.conf" | head -n1; }
assert_json() { grep -q "$1" "$2" || fail "$3"; }

BOOT=provider-first-test-boot
MOD=''
make_module() {
  MOD="$1"
  mkdir -p "$MOD/config" "$MOD/logs" "$MOD/common" "$MOD/.luoshu-payload/system/fonts"
  cp "$ROOT/common/mount_backend_details.sh" "$MOD/common/"
  printf 'id=LuoShu\nversion=test\nversionCode=1\n' > "$MOD/module.prop"
  printf 'DemoFont\n' > "$MOD/config/active_font.conf"
  printf 'fixture-font\n' > "$MOD/.luoshu-payload/system/fonts/Roboto-Regular.ttf"
}

hook() {
  local stage="$1" provider_state="$2" provider_id="$3" verify="$4" engine=none
  [[ "$provider_id" == hybrid-mount ]] && engine=hybrid-mount
  MODDIR="$MOD" MODULE_DIR="$MOD" \
  LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID="$BOOT" \
  LUOSHU_BACKEND_TEST_MANAGER=KernelSU \
  LUOSHU_BACKEND_TEST_META_ENGINE="$engine" \
  LUOSHU_BACKEND_TEST_PROVIDER_STATE="$provider_state" \
  LUOSHU_BACKEND_TEST_PROVIDER_ID="$provider_id" \
  LUOSHU_BACKEND_TEST_PROVIDER_LAYOUT=nested-system \
  LUOSHU_BACKEND_TEST_EXTERNAL_VERIFY_RESULT="$verify" \
  LUOSHU_BACKEND_TEST_SELF_VERIFY_RESULT=pass \
  bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook "$2"' _ "$ROOT" "$stage"
}

pref() {
  MODDIR="$MOD" LUOSHU_BACKEND_TEST_BOOT_ID="$BOOT" \
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
assert_json '"bootNomountObserved":true' "$TMP/status" 'current-boot capability observation was not reported'
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

# Without an external provider, automatic policy waits for the KernelSU mount
# hook, then verifies the private self-mount before reporting it active.
make_module "$TMP/self"
hook post-fs-data absent none pass
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'missing provider did not select self mount'
[[ "$(value "$MOD" active_backend)" == none ]] || fail 'KernelSU self mount ran before post-mount'
hook post-mount absent none pass
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'private self-mount fallback did not activate'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'self-mount was reported before verification'

# A provider route failure activates the same verified self recovery path.
make_module "$TMP/provider-fallback"
hook post-fs-data available hybrid-mount pass
hook post-mount available hybrid-mount fail
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'failed provider route did not select self recovery'
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'self recovery did not become active'
[[ "$(value "$MOD" fallback_used)" == 1 ]] || fail 'provider failure fallback was not recorded'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'fallback success was recorded without route verification'

# Previous-boot observations are historical and must not be exposed as live.
make_module "$TMP/stale"
printf 'boot_id=old-boot\nselected_backend=external\nactive_backend=external\nprovider_state=available\nprovider_id=hybrid-mount\nverification=passed\n' \
  > "$MOD/config/mount-backend.conf"
BOOT=current-boot
pref get > "$TMP/stale-status"
assert_json '"bootNomountObserved":false' "$TMP/stale-status" 'stale state was marked as current-boot observed'
assert_json '"providerState":"unknown"' "$TMP/stale-status" 'stale provider state was exposed as live'
assert_json '"verification":"pending"' "$TMP/stale-status" 'stale verification was exposed as live'

printf 'Mount preference migration, provider-first selection, current-boot status and verified self-fallback checks passed\n'
