#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT/scripts/assert.sh"
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t ziyu-coloros)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MODULE_DIR="$TMP/module"
MODDIR="$MODULE_DIR"
ZIYU_PUBLIC_DIR="$TMP/public"
USER_FONTS_DIR="$ZIYU_PUBLIC_DIR/fonts"
ZIYU_COLOROS_SYSTEM_FONTS_ROOT="$TMP/real/system/fonts"
ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT="$TMP/real/system_ext/fonts"
ZIYU_COLOROS_PRODUCT_FONTS_ROOT="$TMP/real/product/fonts"
ZIYU_COLOROS_MY_PRODUCT_FONTS_ROOT="$TMP/real/my_product/fonts"
export MODULE_DIR MODDIR ZIYU_PUBLIC_DIR USER_FONTS_DIR \
    ZIYU_COLOROS_SYSTEM_FONTS_ROOT ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT \
    ZIYU_COLOROS_PRODUCT_FONTS_ROOT ZIYU_COLOROS_MY_PRODUCT_FONTS_ROOT

mkdir -p "$MODULE_DIR/common" "$MODULE_DIR/system/fonts" "$USER_FONTS_DIR" \
    "$ZIYU_COLOROS_SYSTEM_FONTS_ROOT" "$ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT" \
    "$ZIYU_COLOROS_PRODUCT_FONTS_ROOT" "$ZIYU_COLOROS_MY_PRODUCT_FONTS_ROOT"
cp "$ROOT/common/coloros_global.sh" "$MODULE_DIR/common/coloros_global.sh"

make_font() {
    _name="$1"
    _marker="$2"
    {
        printf '%s\n' "$_marker"
        dd if=/dev/zero bs=2048 count=1 2>/dev/null
    } > "$USER_FONTS_DIR/$_name"
}

make_font Demo-Regular.ttf regular-source
make_font Demo-Medium.ttf medium-source
make_font Demo-Bold.ttf bold-source

# Safe ColorOS mapping deliberately covers system/system_ext/product only. my_product is present
# in the fixture to prove Ziyu does not recreate the bootloop-prone broad OEM overlay.
touch "$ZIYU_COLOROS_SYSTEM_FONTS_ROOT/SysFont-Regular.ttf"
touch "$ZIYU_COLOROS_PRODUCT_FONTS_ROOT/GoogleSansText-Regular.ttf"
touch "$ZIYU_COLOROS_PRODUCT_FONTS_ROOT/GoogleSansText-Medium.ttf"
touch "$ZIYU_COLOROS_PRODUCT_FONTS_ROOT/GoogleSansText-Bold.ttf"
touch "$ZIYU_COLOROS_PRODUCT_FONTS_ROOT/GoogleSansText-VF.ttf"
touch "$ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT/Roboto-Medium.ttf"
touch "$ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT/OSans-Solid-Digits-VF.ttf"
touch "$ZIYU_COLOROS_MY_PRODUCT_FONTS_ROOT/Opposans-En-Bold.ttf"

_log_step() { :; }
_ziyu_font_config_module() { printf '%s\n' "$MODULE_DIR"; }
. "$ROOT/common/util_functions.sh"
. "$ROOT/common/rom_adapters.sh"
. "$ROOT/common/font_config_partitions.sh"

CASE='ColorOS 安全分区槽位映射'
ok type get_all_coloros_names >/dev/null
ok type copy_as_coloros >/dev/null
ok copy_as_coloros "$USER_FONTS_DIR/Demo-Regular.ttf" "$MODULE_DIR/system/fonts" quick Demo

ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$MODULE_DIR/system/fonts/SysFont-Regular.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$MODULE_DIR/product/fonts/GoogleSansText-Regular.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Medium.ttf" "$MODULE_DIR/product/fonts/GoogleSansText-Medium.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Bold.ttf" "$MODULE_DIR/product/fonts/GoogleSansText-Bold.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$MODULE_DIR/product/fonts/GoogleSansText-VF.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Medium.ttf" "$MODULE_DIR/system_ext/fonts/Roboto-Medium.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$MODULE_DIR/system_ext/fonts/OSans-Solid-Digits-VF.ttf"

# Never fan the ColorOS payload back into boot-sensitive OEM partitions merely because a font exists there.
no test -e "$MODULE_DIR/my_product/fonts/Opposans-En-Bold.ttf"

# A product-only slot must not be misplaced into system/fonts; that was the regression that left
# Google Play text fields on the stock GoogleSansText family.
no test -e "$MODULE_DIR/system/fonts/GoogleSansText-Regular.ttf"
COLOROS_NAMES=$(get_all_coloros_names)
printf '%s\n' "$COLOROS_NAMES" > "$TMP/coloros-names"
ok grep -qx 'GoogleSansText-Regular' "$TMP/coloros-names"
ok grep -qx 'SysFont-Regular' "$TMP/coloros-names"
ok grep -qx 'OSans-Solid-Digits-VF' "$TMP/coloros-names"

. "$ROOT/common/font_runtime_policy.sh"
ok test "$(_lfrp_target_kind OSans-Solid-Digits-VF.ttf)" = latin

# Run the real foreground target list -> fast mapper -> private payload checks
# against actual dangling/cyclic OEM pathnames. The stock links are discovery
# evidence only: the resulting overlay must be a nonempty file and pass the
# existing full payload manifest check before it is considered usable.
FAST_MODULE="$TMP/fast-module"
MODULE_DIR="$FAST_MODULE"
MODDIR="$FAST_MODULE"
IS_COLOROS=true
ZIYU_COLOROS_CACHE_KEY=coloros-fast-route-fixture
ZIYU_COLOROS_SYSTEM_FONTS_ROOT="$TMP/fast-real/system/fonts"
ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT="$TMP/fast-real/system_ext/fonts"
ZIYU_COLOROS_PRODUCT_FONTS_ROOT="$TMP/fast-real/product/fonts"
export MODULE_DIR MODDIR IS_COLOROS ZIYU_COLOROS_CACHE_KEY \
    ZIYU_COLOROS_SYSTEM_FONTS_ROOT ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT \
    ZIYU_COLOROS_PRODUCT_FONTS_ROOT
mkdir -p "$FAST_MODULE/config" "$FAST_MODULE/logs" \
    "$ZIYU_COLOROS_SYSTEM_FONTS_ROOT" "$ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT" \
    "$ZIYU_COLOROS_PRODUCT_FONTS_ROOT"
make_fixture_link() {
    _mfl_target="$1"
    _mfl_link="$2"
    if [ -n "${ZIYU_TEST_PYTHON:-}" ]; then
        command -v cygpath >/dev/null 2>&1 && _mfl_link=$(cygpath -w "$_mfl_link")
        "$ZIYU_TEST_PYTHON" -c 'import os,sys; os.symlink(sys.argv[1],sys.argv[2])' \
            "$_mfl_target" "$_mfl_link"
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c 'import os,sys; os.symlink(sys.argv[1],sys.argv[2])' \
            "$_mfl_target" "$_mfl_link"
    else
        ln -s "$_mfl_target" "$_mfl_link"
    fi
}
make_fixture_link missing-stock-target.ttf "$ZIYU_COLOROS_SYSTEM_FONTS_ROOT/SysFont-Dangling-Regular.ttf"
make_fixture_link SysFont-Cycle-Regular.ttf "$ZIYU_COLOROS_SYSTEM_FONTS_ROOT/SysFont-Cycle-Regular.ttf"
make_fixture_link missing-digit-target.ttf "$ZIYU_COLOROS_SYSTEM_EXT_FONTS_ROOT/OSans-Solid-Digits-VF.ttf"

. "$ROOT/common/coloros_global.sh"
. "$ROOT/common/device_font_payload_policy.sh"
# Keep this fixture focused on names discovered from the physical directories.
_coloros_core_files() { :; }
_coloros_google_text_files() { :; }
_coloros_vendor_files() { :; }
_coloros_oem_ui_files() { :; }

printf 'osans_digits = 1 # enabled\r\n' > "$FAST_MODULE/config/coloros_slot_groups.conf"
FAST_TARGETS=$(get_all_coloros_files)
printf '%s\n' "$FAST_TARGETS" > "$TMP/fast-targets-enabled"
ok grep -qx 'SysFont-Dangling-Regular.ttf' "$TMP/fast-targets-enabled"
ok grep -qx 'SysFont-Cycle-Regular.ttf' "$TMP/fast-targets-enabled"
ok grep -qx 'OSans-Solid-Digits-VF.ttf' "$TMP/fast-targets-enabled"
ok _device_font_fast_map "$USER_FONTS_DIR/Demo-Regular.ttf" Demo quick Demo
ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$FAST_MODULE/system/fonts/SysFont-Dangling-Regular.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$FAST_MODULE/system/fonts/SysFont-Cycle-Regular.ttf"
ok cmp -s "$USER_FONTS_DIR/Demo-Regular.ttf" "$FAST_MODULE/system_ext/fonts/OSans-Solid-Digits-VF.ttf"

. "$ROOT/common/font_safety.sh"
. "$ROOT/common/font_runtime_policy.sh"
ok ziyu_payload_validate_current ColorOsFixture
ok ziyu_payload_build_manifest
ok ziyu_payload_validate_manifest_full
ok grep -q '^system/fonts/SysFont-Dangling-Regular.ttf|' "$FAST_MODULE/config/font-payload-manifest.conf"
ok grep -q '^system/fonts/SysFont-Cycle-Regular.ttf|' "$FAST_MODULE/config/font-payload-manifest.conf"
ok grep -q '^system_ext/fonts/OSans-Solid-Digits-VF.ttf|' "$FAST_MODULE/config/font-payload-manifest.conf"

printf 'osans_digits = 0 # disabled\r\n' > "$FAST_MODULE/config/coloros_slot_groups.conf"
FAST_TARGETS=$(get_all_coloros_files)
printf '%s\n' "$FAST_TARGETS" > "$TMP/fast-targets-disabled"
no grep -qx 'OSans-Solid-Digits-VF.ttf' "$TMP/fast-targets-disabled"
ok _device_font_fast_map "$USER_FONTS_DIR/Demo-Regular.ttf" Demo quick Demo
no test -e "$FAST_MODULE/system_ext/fonts/OSans-Solid-Digits-VF.ttf"
ok ziyu_payload_build_manifest
ok ziyu_payload_validate_manifest_full
no grep -q '^system_ext/fonts/OSans-Solid-Digits-VF.ttf|' "$FAST_MODULE/config/font-payload-manifest.conf"

printf 'ColorOS safe partition mapping tests passed.\n'
