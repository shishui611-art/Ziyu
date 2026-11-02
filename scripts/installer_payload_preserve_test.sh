#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MODDIR="$ROOT" . "$ROOT/common/module_update_state.sh"

# Private storage is authoritative even if the public module view exposes only
# system/bin. A generated mix can survive without its old source recipe.
OLD="$TMP/old"
NEW="$TMP/new"
mkdir -p "$OLD/config" "$OLD/system/bin" "$OLD/.ziyu-payload/system/fonts" \
    "$OLD/.ziyu-payload/system/etc" "$OLD/.ziyu-payload/product/fonts/.ziyu-font-store" "$NEW/config" "$NEW/system/bin" "$NEW/common"
cp "$ROOT/common/font_switch_lock.sh" "$NEW/common/"
cp "$ROOT/common/font_live_state.sh" "$ROOT/common/legacy_identity.sh" "$NEW/common/"
printf 'id=Ziyu\nversionCode=11003\n' > "$OLD/module.prop"
printf 'mix\n' > "$OLD/config/active_font.conf"
printf 'old-schema\n' > "$OLD/config/font-payload-schema.conf"
printf 'original Chinese artifact\n' > "$OLD/.ziyu-payload/system/fonts/SysFont-Hans-Regular.ttf"
printf 'original digit artifact\n' > "$OLD/.ziyu-payload/product/fonts/.ziyu-font-store/digits.font"
ln -s .ziyu-font-store/digits.font "$OLD/.ziyu-payload/product/fonts/OSans-Solid-Digits-VF.ttf"
printf '<familyset><family lang="zh-Hans"><font>SysFont-Hans-Regular.ttf</font></family></familyset>\n' > "$OLD/.ziyu-payload/system/etc/fonts.xml"
printf 'new CLI\n' > "$NEW/system/bin/洛书"
test ! -e "$OLD/common/legacy_identity.sh"
MODPATH="$NEW" ziyu_migrate_active_install "$OLD" "$NEW" || { echo 'FAIL: private mix must migrate without its recipe' >&2; exit 1; }
test "$(cat "$NEW/config/active_font.conf")" = mix
cmp "$OLD/.ziyu-payload/system/fonts/SysFont-Hans-Regular.ttf" "$NEW/system/fonts/SysFont-Hans-Regular.ttf"
cmp "$OLD/.ziyu-payload/product/fonts/OSans-Solid-Digits-VF.ttf" "$NEW/product/fonts/OSans-Solid-Digits-VF.ttf"
cmp "$OLD/.ziyu-payload/system/etc/fonts.xml" "$NEW/system/etc/fonts.xml"
test "$(cat "$NEW/system/bin/洛书")" = 'new CLI'
test "$ZIYU_UPDATE_REBUILD_REQUIRED" = true
test "$(cat "$OLD/config/font-payload-schema.conf")" = old-schema
echo 'PASS: private mix payload preserved without recipe or schema mutation'

ANCHOR="$TMP/anchor-only"
mkdir -p "$ANCHOR/.ziyu-payload/system/fonts/.ziyu-font-store"
printf 'font anchor\n' > "$ANCHOR/.ziyu-payload/system/fonts/.ziyu-font-store/cjk.font"
# On Android aliases point to .font anchors. Test the actual regular files;
# MSYS ln -s may copy instead, which would hide this bug on Windows.
ziyu_update_has_font_payload "$ANCHOR" "$ANCHOR/.ziyu-payload" || { echo 'FAIL: .font anchor was missed'; exit 1; }
echo 'PASS: font-store anchors count as an existing private font payload'

# A committed next-boot selection can coexist with an empty stock boot tree.
# The installer must migrate both states without activating the queued payload.
PENDING="$TMP/pending"
PENDING_NEW="$TMP/pending-new"
mkdir -p "$PENDING/config" "$PENDING/.ziyu-payload/system/fonts" \
    "$PENDING/.ziyu-payload-next/system/fonts" "$PENDING_NEW/common"
cp "$ROOT/common/font_switch_lock.sh" "$ROOT/common/physical_payload_manifest.sh" "$PENDING_NEW/common/"
printf 'id=ziyu\nversionCode=14024\n' > "$PENDING/module.prop"
printf 'ZiyuMix1424b0da7bfde3f4\n' > "$PENDING/config/active_font.conf"
printf 'schema=legacy-physical-safe-v1\n' > "$PENDING/config/font-payload-schema.conf"
printf 'state=prepared\nfont=ZiyuMix1424b0da7bfde3f4\npreviousFont=default\nrequestId=task-1\n' > "$PENDING/config/font-payload-next.conf"
printf 'schema=ziyu-next-payload-v1\nfont=ZiyuMix1424b0da7bfde3f4\nrequestId=task-1\n' > "$PENDING/.ziyu-payload-next/.ziyu-next-transaction.conf"
printf 'generated font bytes\n' > "$PENDING/.ziyu-payload-next/system/fonts/SysSans-Hans-Regular.ttf"
ziyu_migrate_active_install "$PENDING" "$PENDING_NEW" || { echo "FAIL: queued font rejected: $ZIYU_UPDATE_FAILURE_REASON"; exit 1; }
cmp "$PENDING/config/font-payload-next.conf" "$PENDING_NEW/config/font-payload-next.conf"
cmp "$PENDING/.ziyu-payload-next/system/fonts/SysSans-Hans-Regular.ttf" "$PENDING_NEW/.ziyu-payload-next/system/fonts/SysSans-Hans-Regular.ttf"
test ! -e "$PENDING_NEW/system/fonts/SysSans-Hans-Regular.ttf"
test ! -e "$PENDING_NEW/config/font-payload-manifest.conf"
test "$(cat "$PENDING_NEW/config/active_font.conf")" = ZiyuMix1424b0da7bfde3f4
printf 'schema=ziyu-next-payload-v1\nfont=ZiyuMix1424b0da7bfde3f4\nrequestId=wrong\n' > "$PENDING/.ziyu-payload-next/.ziyu-next-transaction.conf"
if ziyu_migrate_active_install "$PENDING" "$TMP/pending-bad"; then
    echo 'FAIL: mismatched pending transaction accepted' >&2; exit 1
fi
test "$ZIYU_UPDATE_FAILURE_REASON" = pending-font-transaction-unconfirmed
test -s "$PENDING/.ziyu-payload-next/system/fonts/SysSans-Hans-Regular.ttf"
echo 'PASS: queued physical font migrated only with matching transaction and verified bytes'

mkdir -p "$OLD/.ziyu-payload/.ziyu-runtime/deployment" "$NEW/.ziyu-runtime/core"
mkdir -p "$OLD/.ziyu-payload/.ziyu-dynamic/target"
printf 'generated dynamic CJK\n' > "$OLD/.ziyu-payload/.ziyu-dynamic/target/font.ttf"
printf 'pipeline=universal-font-deployment-v1\n' > "$OLD/config/universal-font-runtime.conf"
for artifact in deployment.json font-plan.json artifact-manifest.json; do
    printf '{"fixture":"%s"}\n' "$artifact" > "$OLD/.ziyu-payload/.ziyu-runtime/deployment/$artifact"
done
printf 'new boot runtime\n' > "$NEW/.ziyu-runtime/core/service.sh"
ziyu_migrate_active_install "$OLD" "$NEW" || { echo 'FAIL: universal private metadata did not migrate'; exit 1; }
cmp "$OLD/.ziyu-payload/.ziyu-runtime/deployment/deployment.json" "$NEW/.ziyu-payload/.ziyu-runtime/deployment/deployment.json"
cmp "$OLD/.ziyu-payload/.ziyu-dynamic/target/font.ttf" "$NEW/.ziyu-payload/.ziyu-dynamic/target/font.ttf"
test "$(cat "$NEW/.ziyu-runtime/core/service.sh")" = 'new boot runtime'
echo 'PASS: universal deployment and dynamic-font artifacts preserved alongside private fonts'

rm "$OLD/.ziyu-payload/.ziyu-runtime/deployment/font-plan.json"
if ziyu_migrate_active_install "$OLD" "$TMP/universal-rejected"; then
    echo 'FAIL: universal runtime without its route plan accepted'; exit 1
fi
test "$ZIYU_UPDATE_FAILURE_REASON" = universal-artifact-missing:font-plan.json
rm "$OLD/config/universal-font-runtime.conf"
echo 'PASS: incomplete universal artifact set rejected before migration'

mkdir -p "$OLD/.ziyu-payload/oplus/fonts"
printf 'oplus\n' > "$OLD/config/device_font_partitions.conf"
printf 'extra OEM Chinese font\n' > "$OLD/.ziyu-payload/oplus/fonts/CJK.ttf"
_crc=$(cksum "$OLD/.ziyu-payload/oplus/fonts/CJK.ttf" | awk '{print $1 "|" $2}')
printf 'oplus/fonts/CJK.ttf|%s\n' "$_crc" > "$OLD/config/font-payload-manifest.conf"
cp "$OLD/config/font-payload-manifest.conf" "$TMP/crc-before"
ziyu_migrate_active_install "$OLD" "$TMP/crc-update" || { echo 'FAIL: CRC manifest or extra OEM partition rejected'; exit 1; }
cmp "$OLD/.ziyu-payload/oplus/fonts/CJK.ttf" "$TMP/crc-update/oplus/fonts/CJK.ttf"
grep -Eq '^oplus/fonts/CJK.ttf\|[0-9a-f]{64}$' "$TMP/crc-update/config/font-payload-manifest.conf"
cmp "$TMP/crc-before" "$OLD/config/font-payload-manifest.conf"
echo 'PASS: legacy CRC ledger checked and normalized; extra OEM partition retained'

PHYS="$TMP/physical-old"
PHYS_NEW="$TMP/physical-new"
mkdir -p "$PHYS/config" "$PHYS/.ziyu-payload/system/fonts" "$PHYS_NEW/common"
printf 'id=Ziyu\nversionCode=11005\n' > "$PHYS/module.prop"
printf 'generated-mix\n' > "$PHYS/config/active_font.conf"
printf 'enabled=true\ncore=physical-safe-v1\n' > "$PHYS/config/font_runtime_legacy_v14_4.conf"
printf 'schema=legacy-physical-safe-v1\n' > "$PHYS/config/font-payload-schema.conf"
printf 'physical CJK bytes\n' > "$PHYS/.ziyu-payload/system/fonts/SysSans-Hans-Regular.ttf"
cp "$ROOT/common/physical_payload_manifest.sh" "$PHYS_NEW/common/"
ziyu_migrate_active_install "$PHYS" "$PHYS_NEW" || { echo 'FAIL: physical missing ledger upgrade rejected'; exit 1; }
grep -Eq '^system/fonts/SysSans-Hans-Regular.ttf\|[0-9a-f]{64}$' "$PHYS_NEW/config/font-payload-manifest.conf"
test ! -e "$PHYS/config/font-payload-manifest.conf"
echo 'PASS: physical update creates new verified ledger without writing the running module'

# If a manifest exists, missing fonts must reject the update before a selected
# mix is relabelled as stock. The old module remains untouched.
printf 'system/fonts/Missing-CJK.ttf|%064d\n' 0 > "$OLD/config/font-payload-manifest.conf"
if ziyu_migrate_active_install "$OLD" "$TMP/rejected"; then
    echo 'FAIL: incomplete manifest was accepted' >&2; exit 1
fi
test "$(cat "$OLD/config/active_font.conf")" = mix
test ! -e "$TMP/rejected/config/active_font.conf"
test -n "$ZIYU_UPDATE_FAILURE_REASON"
echo 'PASS: incomplete manifest rejected without changing active installation'

# Exercise the production installer migration segment, not a copied decision.
BAD="$TMP/bad"
TARGET="$TMP/target"
mkdir -p "$BAD/config" "$TARGET/common" "$TARGET/config"
printf 'id=Ziyu\nversionCode=11004\n' > "$BAD/module.prop"
printf 'id=Ziyu\nversion=v1.1.5\nversionCode=11005\n' > "$TARGET/module.prop"
printf 'mix\n' > "$BAD/config/active_font.conf"
printf 'default\n' > "$TARGET/config/active_font.conf"
cp "$ROOT/common/module_update_state.sh" "$TARGET/common/"
awk '/^if \[ -f "\$MODPATH\/common\/font_weight_retire.sh" \]; then/ { exit } { print }' \
    "$ROOT/.ziyu-runtime/compat/v227/customize.sh" > "$TMP/installer-segment.sh"
if ( ui_print() { printf '%s\n' "$*"; }; abort() { printf 'ABORT: %s\n' "$*"; }; \
    ensure_public_storage() { :; }; MODPATH="$TARGET" ZIYU_OLD_MOD="$BAD"; \
    . "$TMP/installer-segment.sh" ) > "$TMP/install.log" 2>&1; then
    echo 'FAIL: installer must reject missing active payload' >&2; cat "$TMP/install.log"; exit 1
fi
grep -q 'ABORT:' "$TMP/install.log"
test "$(cat "$BAD/config/active_font.conf")" = mix
test ! -f "$TARGET/config/previous_font.conf"
echo 'PASS: actual installer rejects active migration failure instead of silently booting stock'
