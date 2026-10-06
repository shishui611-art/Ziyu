#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
. "$ROOT/common/module_update_state.sh"

# Private storage is authoritative even if the public module view exposes only
# system/bin. A generated mix can survive without its old source recipe.
OLD="$TMP/old"
NEW="$TMP/new"
mkdir -p "$OLD/config" "$OLD/system/bin" "$OLD/.luoshu-payload/system/fonts" \
    "$OLD/.luoshu-payload/system/etc" "$OLD/.luoshu-payload/product/fonts/.luoshu-font-store" "$NEW/config" "$NEW/system/bin"
printf 'id=LuoShu\nversionCode=11003\n' > "$OLD/module.prop"
printf 'mix\n' > "$OLD/config/active_font.conf"
printf 'old-schema\n' > "$OLD/config/font-payload-schema.conf"
printf 'original Chinese artifact\n' > "$OLD/.luoshu-payload/system/fonts/SysFont-Hans-Regular.ttf"
printf 'original digit artifact\n' > "$OLD/.luoshu-payload/product/fonts/.luoshu-font-store/digits.font"
ln -s .luoshu-font-store/digits.font "$OLD/.luoshu-payload/product/fonts/OSans-Solid-Digits-VF.ttf"
printf '<familyset><family lang="zh-Hans"><font>SysFont-Hans-Regular.ttf</font></family></familyset>\n' > "$OLD/.luoshu-payload/system/etc/fonts.xml"
printf 'new CLI\n' > "$NEW/system/bin/洛书"
luoshu_migrate_active_install "$OLD" "$NEW" || { echo 'FAIL: private mix must migrate without its recipe' >&2; exit 1; }
test "$(cat "$NEW/config/active_font.conf")" = mix
cmp "$OLD/.luoshu-payload/system/fonts/SysFont-Hans-Regular.ttf" "$NEW/system/fonts/SysFont-Hans-Regular.ttf"
cmp "$OLD/.luoshu-payload/product/fonts/OSans-Solid-Digits-VF.ttf" "$NEW/product/fonts/OSans-Solid-Digits-VF.ttf"
cmp "$OLD/.luoshu-payload/system/etc/fonts.xml" "$NEW/system/etc/fonts.xml"
test "$(cat "$NEW/system/bin/洛书")" = 'new CLI'
test "$LUOSHU_UPDATE_REBUILD_REQUIRED" = true
test "$(cat "$OLD/config/font-payload-schema.conf")" = old-schema
echo 'PASS: private mix payload preserved without recipe or schema mutation'

ANCHOR="$TMP/anchor-only"
mkdir -p "$ANCHOR/.luoshu-payload/system/fonts/.luoshu-font-store"
printf 'font anchor\n' > "$ANCHOR/.luoshu-payload/system/fonts/.luoshu-font-store/cjk.font"
# On Android aliases point to .font anchors. Test the actual regular files;
# MSYS ln -s may copy instead, which would hide this bug on Windows.
luoshu_update_has_font_payload "$ANCHOR" || { echo 'FAIL: .font anchor was missed'; exit 1; }
echo 'PASS: font-store anchors count as an existing private font payload'

mkdir -p "$OLD/.luoshu-payload/.luoshu-runtime/deployment" "$NEW/.luoshu-runtime/core"
mkdir -p "$OLD/.luoshu-payload/.luoshu-dynamic/target"
printf 'generated dynamic CJK\n' > "$OLD/.luoshu-payload/.luoshu-dynamic/target/font.ttf"
printf 'pipeline=universal-font-deployment-v1\n' > "$OLD/config/universal-font-runtime.conf"
for artifact in deployment.json font-plan.json artifact-manifest.json; do
    printf '{"fixture":"%s"}\n' "$artifact" > "$OLD/.luoshu-payload/.luoshu-runtime/deployment/$artifact"
done
printf 'new boot runtime\n' > "$NEW/.luoshu-runtime/core/service.sh"
luoshu_migrate_active_install "$OLD" "$NEW" || { echo 'FAIL: universal private metadata did not migrate'; exit 1; }
cmp "$OLD/.luoshu-payload/.luoshu-runtime/deployment/deployment.json" "$NEW/.luoshu-payload/.luoshu-runtime/deployment/deployment.json"
cmp "$OLD/.luoshu-payload/.luoshu-dynamic/target/font.ttf" "$NEW/.luoshu-payload/.luoshu-dynamic/target/font.ttf"
test "$(cat "$NEW/.luoshu-runtime/core/service.sh")" = 'new boot runtime'
echo 'PASS: universal deployment and dynamic-font artifacts preserved alongside private fonts'

rm "$OLD/.luoshu-payload/.luoshu-runtime/deployment/font-plan.json"
if luoshu_migrate_active_install "$OLD" "$TMP/universal-rejected"; then
    echo 'FAIL: universal runtime without its route plan accepted'; exit 1
fi
test "$LUOSHU_UPDATE_FAILURE_REASON" = universal-artifact-missing:font-plan.json
rm "$OLD/config/universal-font-runtime.conf"
echo 'PASS: incomplete universal artifact set rejected before migration'

mkdir -p "$OLD/.luoshu-payload/oplus/fonts"
printf 'oplus\n' > "$OLD/config/device_font_partitions.conf"
printf 'extra OEM Chinese font\n' > "$OLD/.luoshu-payload/oplus/fonts/CJK.ttf"
_crc=$(cksum "$OLD/.luoshu-payload/oplus/fonts/CJK.ttf" | awk '{print $1 "|" $2}')
printf 'oplus/fonts/CJK.ttf|%s\n' "$_crc" > "$OLD/config/font-payload-manifest.conf"
cp "$OLD/config/font-payload-manifest.conf" "$TMP/crc-before"
luoshu_migrate_active_install "$OLD" "$TMP/crc-update" || { echo 'FAIL: CRC manifest or extra OEM partition rejected'; exit 1; }
cmp "$OLD/.luoshu-payload/oplus/fonts/CJK.ttf" "$TMP/crc-update/oplus/fonts/CJK.ttf"
grep -Eq '^oplus/fonts/CJK.ttf\|[0-9a-f]{64}$' "$TMP/crc-update/config/font-payload-manifest.conf"
cmp "$TMP/crc-before" "$OLD/config/font-payload-manifest.conf"
echo 'PASS: legacy CRC ledger checked and normalized; extra OEM partition retained'

PHYS="$TMP/physical-old"
PHYS_NEW="$TMP/physical-new"
mkdir -p "$PHYS/config" "$PHYS/.luoshu-payload/system/fonts" "$PHYS_NEW/common"
printf 'id=LuoShu\nversionCode=11005\n' > "$PHYS/module.prop"
printf 'generated-mix\n' > "$PHYS/config/active_font.conf"
printf 'enabled=true\ncore=physical-safe-v1\n' > "$PHYS/config/font_runtime_legacy_v14_4.conf"
printf 'schema=legacy-physical-safe-v1\n' > "$PHYS/config/font-payload-schema.conf"
printf 'physical CJK bytes\n' > "$PHYS/.luoshu-payload/system/fonts/SysSans-Hans-Regular.ttf"
cp "$ROOT/common/physical_payload_manifest.sh" "$PHYS_NEW/common/"
luoshu_migrate_active_install "$PHYS" "$PHYS_NEW" || { echo 'FAIL: physical missing ledger upgrade rejected'; exit 1; }
grep -Eq '^system/fonts/SysSans-Hans-Regular.ttf\|[0-9a-f]{64}$' "$PHYS_NEW/config/font-payload-manifest.conf"
test ! -e "$PHYS/config/font-payload-manifest.conf"
echo 'PASS: physical update creates new verified ledger without writing the running module'

# If a manifest exists, missing fonts must reject the update before a selected
# mix is relabelled as stock. The old module remains untouched.
printf 'system/fonts/Missing-CJK.ttf|%064d\n' 0 > "$OLD/config/font-payload-manifest.conf"
if luoshu_migrate_active_install "$OLD" "$TMP/rejected"; then
    echo 'FAIL: incomplete manifest was accepted' >&2; exit 1
fi
test "$(cat "$OLD/config/active_font.conf")" = mix
test ! -e "$TMP/rejected/config/active_font.conf"
test -n "$LUOSHU_UPDATE_FAILURE_REASON"
echo 'PASS: incomplete manifest rejected without changing active installation'

# Exercise the production installer migration segment, not a copied decision.
BAD="$TMP/bad"
TARGET="$TMP/target"
mkdir -p "$BAD/config" "$TARGET/common" "$TARGET/config"
printf 'id=LuoShu\nversionCode=11004\n' > "$BAD/module.prop"
printf 'id=LuoShu\nversion=v1.1.5\nversionCode=11005\n' > "$TARGET/module.prop"
printf 'mix\n' > "$BAD/config/active_font.conf"
printf 'default\n' > "$TARGET/config/active_font.conf"
cp "$ROOT/common/module_update_state.sh" "$TARGET/common/"
awk '/^if \[ -f "\$MODPATH\/common\/font_weight_retire.sh" \]; then/ { exit } { print }' \
    "$ROOT/.luoshu-runtime/compat/v227/customize.sh" > "$TMP/installer-segment.sh"
if ( ui_print() { printf '%s\n' "$*"; }; abort() { printf 'ABORT: %s\n' "$*"; }; \
    ensure_public_storage() { :; }; MODPATH="$TARGET" LUOSHU_OLD_MOD="$BAD"; \
    . "$TMP/installer-segment.sh" ) > "$TMP/install.log" 2>&1; then
    echo 'FAIL: installer must reject missing active payload' >&2; cat "$TMP/install.log"; exit 1
fi
grep -q 'ABORT:' "$TMP/install.log"
test "$(cat "$BAD/config/active_font.conf")" = mix
test ! -f "$TARGET/config/previous_font.conf"
echo 'PASS: actual installer rejects active migration failure instead of silently booting stock'
