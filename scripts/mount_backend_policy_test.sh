#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/common/mount_backend_policy.sh"
assert_eq() { [[ "$1" == "$2" ]] || { printf 'FAIL: %s != %s\n' "$1" "$2" >&2; exit 1; }; }

assert_eq "$(ziyu_mount_select false available true true legacy)" external
assert_eq "$(ziyu_mount_select false unknown true true auto)" unresolved
assert_eq "$(ziyu_mount_select false absent true true auto)" self_nomount
assert_eq "$(ziyu_mount_select false absent true true legacy)" legacy_fallback
assert_eq "$(ziyu_mount_select false absent false true auto)" legacy_fallback
assert_eq "$(ziyu_mount_select false absent false false auto)" none
assert_eq "$(ziyu_mount_select true available true true auto)" disabled
assert_eq "$(ziyu_mount_select false excluded true true auto)" disabled
assert_eq "$(ziyu_mount_select false invalid true true auto)" unresolved
printf 'Mount backend policy tests passed\n'
