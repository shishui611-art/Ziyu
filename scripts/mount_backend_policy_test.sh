#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/common/mount_backend_policy.sh"
assert_eq() { [[ "$1" == "$2" ]] || { printf 'FAIL: %s != %s\n' "$1" "$2" >&2; exit 1; }; }

assert_eq "$(ziyu_mount_select false available true auto)" external
assert_eq "$(ziyu_mount_select false unknown true auto)" unresolved
assert_eq "$(ziyu_mount_select false absent true auto)" self
assert_eq "$(ziyu_mount_select false available true manager)" external
assert_eq "$(ziyu_mount_select false absent true manager)" self
assert_eq "$(ziyu_mount_select false absent false auto)" none
assert_eq "$(ziyu_mount_select true available true auto)" none
assert_eq "$(ziyu_mount_select false excluded true auto)" excluded
assert_eq "$(ziyu_mount_select false invalid true auto)" unresolved
assert_eq "$(ziyu_mount_select false available true magic)" self
assert_eq "$(ziyu_mount_select false available true overlayfs)" self
assert_eq "$(ziyu_mount_select false available true self_mount)" self
printf 'Mount backend policy tests passed\n'
