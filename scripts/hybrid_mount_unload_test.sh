#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
value() { sed -n "s/^$2=//p" "$1/config/mount-backend.conf" | head -n1; }

MOD="$TMP/module"
META="$TMP/hybrid"
mkdir -p "$MOD/config" "$MOD/logs" "$MOD/.ziyu-payload/system/fonts" "$META"
printf 'id=Ziyu\nversion=test\nversionCode=1\n' > "$MOD/module.prop"
printf 'DemoFont\n' > "$MOD/config/active_font.conf"
printf 'fixture-font\n' > "$MOD/.ziyu-payload/system/fonts/Roboto-Regular.ttf"

# If Hybrid's route cannot be verified in automatic mode, try the self backend.
# Its route must pass verification; the fallback does not rewrite provider config.
run_hook() {
  local stage="$1" verify="$2"
  MODDIR="$MOD" MODULE_DIR="$MOD" META_MODULE_DIR="$META" \
  ZIYU_BACKEND_TEST_MODE=1 \
  ZIYU_BACKEND_TEST_BOOT_ID=hybrid-fallback-test \
  ZIYU_BACKEND_TEST_MANAGER=KernelSU \
  ZIYU_BACKEND_TEST_META_ENGINE=hybrid-mount \
  ZIYU_BACKEND_TEST_PROVIDER_STATE=available \
  ZIYU_BACKEND_TEST_PROVIDER_ID=hybrid-mount \
  ZIYU_BACKEND_TEST_META_VERIFY_RESULT="$verify" \
  ZIYU_BACKEND_TEST_EXTERNAL_VERIFY_RESULT="$verify" \
  ZIYU_BACKEND_TEST_SELF_VERIFY_RESULT=pass \
  bash -c '. "$1/common/mount_backend_runtime.sh"; ziyu_mount_backend_hook "$2"' _ "$ROOT" "$stage"
}

run_hook post-fs-data pass
[[ "$(value "$MOD" selected_backend)" == external ]] || fail 'Hybrid should be selected as the boot provider'
[[ "$(value "$MOD" active_backend)" == none ]] || fail 'provider mount must wait for its mount stage'

run_hook post-mount fail
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'failed Hybrid route did not select self fallback'
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'unverified provider was reported active'
[[ "$(value "$MOD" fallback_used)" == 1 ]] || fail 'failed provider did not record fallback'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'self route was not verified'
[[ -f "$MOD/config/test-self-mounted" ]] || fail 'provider failure did not try the self backend'

printf 'Hybrid route failure tries self mounting and reports success only after route verification.\n'
