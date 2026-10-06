#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/common/mount_provider_detection.sh"
assert_eq() { [[ "$1" == "$2" ]] || { printf 'FAIL: %s != %s\n' "$1" "$2" >&2; exit 1; }; }

export MODDIR="$ROOT" LUOSHU_PROVIDER_TEST_MODE=0
export ROOT_MANAGER='SukiSU Ultra' ROOT_DETECTION_SOURCE=env
export META_ENGINE=nomount META_INSTALLED=1 META_ENABLED=1 META_READY=1
export META_ACTIVE_DIR=/tmp/active-nomount META_USABLE=0 META_USABLE_REASON=nomount-not-integrated
META_NOMOUNT_CLI=/tmp/should-not-be-executed
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" available
assert_eq "$PROVIDER_ID" nomount
assert_eq "$PROVIDER_LAYOUT" nomount-partition-roots
assert_eq "$PROVIDER_REASON" active-meta-standard-tree

META_READY=0
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" unknown
assert_eq "$PROVIDER_REASON" meta-not-ready-nomount-not-integrated

META_ENGINE=none META_INSTALLED=0 META_ENABLED=0 META_ACTIVE_DIR= META_READY=0
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" absent

ROOT_MANAGER=Magisk
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" available
assert_eq "$PROVIDER_ID" magisk-magic-mount

ROOT_MANAGER=APatch
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" available
assert_eq "$PROVIDER_LAYOUT" nested-system

ROOT_MANAGER=unknown ROOT_DETECTION_SOURCE=ambiguous-root-installations
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" unknown

LUOSHU_PROVIDER_TEST_MODE=1 LUOSHU_PROVIDER_TEST_STATE=excluded
ziyu_mount_provider_detect "$ROOT"
assert_eq "$PROVIDER_STATE" excluded
printf 'Mount provider detection tests passed\n'
