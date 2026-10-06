#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
. "$ROOT/common/physical_payload_manifest.sh"
M="$TMP/module"
mkdir -p "$M/config" "$M/.luoshu-payload/system/fonts" "$M/.luoshu-payload/oplus/fonts"
printf 'CJK bytes\n' > "$M/.luoshu-payload/system/fonts/SysSans-Hans-Regular.ttf"
printf 'digits bytes\n' > "$M/.luoshu-payload/oplus/fonts/OSans-Solid-Digits-VF.ttf"
printf 'oplus\n' > "$M/config/device_font_partitions.conf"
printf 'enabled=true\ncore=physical-safe-v1\n' > "$M/config/font_runtime_legacy_v14_4.conf"
printf 'schema=legacy-physical-safe-v1\n' > "$M/config/font-payload-schema.conf"
luoshu_physical_manifest_ensure "$M" "$M/.luoshu-payload"
grep -Eq '^system/fonts/SysSans-Hans-Regular.ttf\|[0-9a-f]{64}$' "$M/config/font-payload-manifest.conf"
grep -Eq '^oplus/fonts/OSans-Solid-Digits-VF.ttf\|[0-9a-f]{64}$' "$M/config/font-payload-manifest.conf"
cp "$M/config/font-payload-manifest.conf" "$TMP/before"
printf 'changed CJK\n' > "$M/.luoshu-payload/system/fonts/SysSans-Hans-Regular.ttf"
luoshu_physical_manifest_ensure "$M" "$M/.luoshu-payload"
cmp "$TMP/before" "$M/config/font-payload-manifest.conf"
echo 'PASS: physical missing ledger bootstrapped; existing integrity ledger never overwritten'
rm "$M/config/font-payload-manifest.conf"
printf 'schema=generic-legacy\n' > "$M/config/font-payload-schema.conf"
if luoshu_physical_manifest_ensure "$M" "$M/.luoshu-payload"; then echo 'FAIL: unknown schema accepted'; exit 1; fi
test ! -e "$M/config/font-payload-manifest.conf"
echo 'PASS: missing generic legacy ledger is rejected'
printf 'schema=legacy-physical-safe-v1\n' > "$M/config/font-payload-schema.conf"
: > "$M/.luoshu-payload/system/fonts/SysSans-Hans-Regular.ttf"
if luoshu_physical_manifest_build "$M" "$M/.luoshu-payload"; then echo 'FAIL: empty artifact accepted'; exit 1; fi
test ! -e "$M/config/font-payload-manifest.conf"
echo 'PASS: empty font rejects complete transaction without committing a partial ledger'
# Exercise the actual next-boot mode writer, with an installed helper.
cp "$ROOT/common/next_boot_payload.sh" "$TMP/next_boot_payload.sh"
mkdir -p "$M/common"
cp "$ROOT/common/physical_payload_manifest.sh" "$M/common/"
printf 'new CJK\n' > "$M/.luoshu-payload/system/fonts/SysSans-Hans-Regular.ttf"
. "$TMP/next_boot_payload.sh"
set -eu
luoshu_next_boot_write_mode "$M" fixture legacy
test -s "$M/config/font-payload-manifest.conf"
grep -q '^core=physical-safe-v1$' "$M/config/font_runtime_legacy_v14_4.conf"
echo 'PASS: next-boot physical activation commits its ledger before exposing the mode'
