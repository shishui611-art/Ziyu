#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
value() { sed -n "s/^$2=//p" "$1/config/mount-backend.conf" | head -n1; }

MOD="$TMP/module"
META="$TMP/hybrid"
mkdir -p "$MOD/config" "$MOD/logs" "$MOD/.luoshu-payload/system/fonts" "$META"
printf 'id=LuoShu\nversion=test\nversionCode=1\n' > "$MOD/module.prop"
printf 'DemoFont\n' > "$MOD/config/active_font.conf"
printf 'fixture-font\n' > "$MOD/.luoshu-payload/system/fonts/Roboto-Regular.ttf"

# The provider owns its active mounts. If Hybrid's published font route cannot
# be verified, the current contract is to activate LuoShu's own recovery mount
# and leave the provider configuration and runtime alone until reboot.
run_hook() {
  local stage="$1" verify="$2"
  MODDIR="$MOD" MODULE_DIR="$MOD" META_MODULE_DIR="$META" \
  LUOSHU_BACKEND_TEST_MODE=1 \
  LUOSHU_BACKEND_TEST_BOOT_ID=hybrid-fallback-test \
  LUOSHU_BACKEND_TEST_MANAGER=KernelSU \
  LUOSHU_BACKEND_TEST_META_ENGINE=hybrid-mount \
  LUOSHU_BACKEND_TEST_PROVIDER_STATE=available \
  LUOSHU_BACKEND_TEST_PROVIDER_ID=hybrid-mount \
  LUOSHU_BACKEND_TEST_META_VERIFY_RESULT="$verify" \
  LUOSHU_BACKEND_TEST_EXTERNAL_VERIFY_RESULT="$verify" \
  LUOSHU_BACKEND_TEST_SELF_VERIFY_RESULT=pass \
  bash -c '. "$1/common/mount_backend_runtime.sh"; luoshu_mount_backend_hook "$2"' _ "$ROOT" "$stage"
}

run_hook post-fs-data pass
[[ "$(value "$MOD" selected_backend)" == external ]] || fail 'Hybrid should be selected as the boot provider'
[[ "$(value "$MOD" active_backend)" == none ]] || fail 'provider mount must wait for its mount stage'

run_hook post-mount fail
[[ "$(value "$MOD" selected_backend)" == self ]] || fail 'failed Hybrid verification should select self fallback'
[[ "$(value "$MOD" active_backend)" == self ]] || fail 'self fallback should become active after provider verification fails'
[[ "$(value "$MOD" fallback_used)" == 1 ]] || fail 'fallback should be recorded'
[[ "$(value "$MOD" verification)" == passed ]] || fail 'self route should be verified before reporting success'
[[ -f "$MOD/config/test-self-mounted" ]] || fail 'self-mount recovery route did not run'

printf 'Hybrid route failure activates verified self-mount fallback without provider unload.\n'
