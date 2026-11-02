#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MOD="$TMP/module"
SYSTEM_ETC="$TMP/real/system/etc"
PRODUCT_ETC="$TMP/real/product/etc"
SYSTEM_EXT_ETC="$TMP/real/system_ext/etc"
MY_PRODUCT_ETC="$TMP/real/my_product/etc"
VENDOR_ETC="$TMP/real/vendor/etc"
ODM_ETC="$TMP/real/odm/etc"
mkdir -p "$MOD/common" "$MOD/config" "$MOD/system/fonts" \
    "$SYSTEM_ETC" "$PRODUCT_ETC" "$SYSTEM_EXT_ETC" \
    "$MY_PRODUCT_ETC" "$VENDOR_ETC" "$ODM_ETC"
cp -f "$ROOT/common/font_config_overlay.py" "$MOD/common/font_config_overlay.py"
cp -f "$ROOT/common/font_config_runtime.sh" "$MOD/common/font_config_runtime.sh"
cp -f "$ROOT/common/font_config_partitions.sh" "$MOD/common/font_config_partitions.sh"
cp -f "$ROOT/common/font_config_targets.py" "$MOD/common/font_config_targets.py"
cp -f "$ROOT/common/font_safety.sh" "$MOD/common/font_safety.sh"

cat > "$SYSTEM_ETC/fonts.xml" <<'XML'
<familyset>
  <family name="sans-serif">
    <font weight="400">Roboto-Regular.ttf</font>
    <font weight="700">Roboto-Bold.ttf</font>
  </family>
  <family name="serif"><font weight="400">NotoSerif-Regular.ttf</font></family>
</familyset>
XML
cat > "$SYSTEM_ETC/font_fallback.xml" <<'XML'
<familyset>
  <family name="sans-serif-condensed"><font weight="400">RobotoCondensed-Regular.ttf</font></family>
  <family lang="und-Arab"><font weight="400">NotoNaskhArabic-Regular.ttf</font></family>
</familyset>
XML
cat > "$PRODUCT_ETC/mi_fonts_customization.xml" <<'XML'
<familyset>
  <family name="misans"><font weight="500">MiSansVF.ttf</font></family>
  <family name="mitype-clock"><font weight="400">Mitype2019.ttf</font></family>
</familyset>
XML
cat > "$SYSTEM_EXT_ETC/fonts_customization.xml" <<'XML'
<familyset>
  <family name="google-sans"><font weight="400">GoogleSans-Regular.ttf</font></family>
  <family name="material-icons"><font weight="400">MaterialIcons.ttf</font></family>
</familyset>
XML
cat > "$MY_PRODUCT_ETC/oplus_fonts_customization.xml" <<'XML'
<familyset>
  <family name="op-sans-en"><font weight="500">OPSans-En-Medium.ttf</font></family>
  <family name="material-icons-rounded"><font weight="400">MaterialIcons-Rounded.ttf</font></family>
</familyset>
XML
cat > "$VENDOR_ETC/fonts.xml" <<'XML'
<familyset>
  <family name="sans-serif"><font weight="600">VendorSans-SemiBold.ttf</font></family>
  <family name="monospace"><font weight="400">VendorMono-Regular.ttf</font></family>
</familyset>
XML
cat > "$ODM_ETC/fonts_customization.xml" <<'XML'
<familyset>
  <family name="roboto"><font weight="300">Roboto-Light.ttf</font></family>
  <family name="mitype-clock"><font weight="400">Mitype2019.ttf</font></family>
</familyset>
XML

for prefix in Ziyu; do
    for weight in 100 200 300 400 500 600 700 800 900; do
        dd if=/dev/zero of="$MOD/system/fonts/${prefix}-${weight}.ttf" bs=2048 count=1 2>/dev/null
        chmod 0644 "$MOD/system/fonts/${prefix}-${weight}.ttf"
    done
done

export MODDIR="$MOD"
export MODULE_DIR="$MOD"
export CONFIG_DIR="$MOD/config"
export ZIYU_PYTHON=python3
export ZIYU_SYSTEM_ETC_ROOT="$SYSTEM_ETC"
export ZIYU_PRODUCT_ETC_ROOT="$PRODUCT_ETC"
export ZIYU_SYSTEM_EXT_ETC_ROOT="$SYSTEM_EXT_ETC"
export ZIYU_MY_PRODUCT_ETC_ROOT="$MY_PRODUCT_ETC"
export ZIYU_VENDOR_ETC_ROOT="$VENDOR_ETC"
export ZIYU_ODM_ETC_ROOT="$ODM_ETC"
. "$MOD/common/font_config_runtime.sh"
. "$MOD/common/font_config_partitions.sh"
set -eu

font_config_generate DemoFamily

for overlay in \
    "$MOD/system/etc/fonts.xml" \
    "$MOD/system/etc/font_fallback.xml" \
    "$MOD/product/etc/mi_fonts_customization.xml" \
    "$MOD/system_ext/etc/fonts_customization.xml" \
    "$MOD/my_product/etc/oplus_fonts_customization.xml" \
    "$MOD/vendor/etc/fonts.xml" \
    "$MOD/odm/etc/fonts_customization.xml"; do
    test -s "$overlay"
done

grep -q 'Ziyu-400.ttf' "$MOD/system/etc/fonts.xml"
grep -q 'Ziyu-700.ttf' "$MOD/system/etc/fonts.xml"
grep -q 'NotoSerif-Regular.ttf' "$MOD/system/etc/fonts.xml"
grep -q 'Ziyu-500.ttf' "$MOD/product/etc/mi_fonts_customization.xml"
grep -q 'Mitype2019.ttf' "$MOD/product/etc/mi_fonts_customization.xml"
grep -q 'Ziyu-400.ttf' "$MOD/system_ext/etc/fonts_customization.xml"
grep -q 'MaterialIcons.ttf' "$MOD/system_ext/etc/fonts_customization.xml"
grep -q 'Ziyu-500.ttf' "$MOD/my_product/etc/oplus_fonts_customization.xml"
grep -q 'MaterialIcons-Rounded.ttf' "$MOD/my_product/etc/oplus_fonts_customization.xml"
grep -q 'Ziyu-600.ttf' "$MOD/vendor/etc/fonts.xml"
! grep -q 'ZiyuMono-400.ttf' "$MOD/vendor/etc/fonts.xml"
grep -q 'VendorMono-Regular.ttf' "$MOD/vendor/etc/fonts.xml"
grep -q 'Ziyu-300.ttf' "$MOD/odm/etc/fonts_customization.xml"
grep -q 'Mitype2019.ttf' "$MOD/odm/etc/fonts_customization.xml"

test -s "$MOD/config/font-config-source/system/fonts.xml"
test -s "$MOD/config/font-config-source/product/mi_fonts_customization.xml"
test -s "$MOD/config/font-config-source/system_ext/fonts_customization.xml"
test -s "$MOD/config/font-config-source/my_product/oplus_fonts_customization.xml"
test -s "$MOD/config/font-config-source/vendor/fonts.xml"
test -s "$MOD/config/font-config-source/odm/fonts_customization.xml"

for partition in system product system_ext my_product vendor odm; do
    for prefix in Ziyu; do
        for weight in 100 200 300 400 500 600 700 800 900; do
            test -s "$MOD/$partition/fonts/${prefix}-${weight}.ttf"
        done
    done
done

# A missing UI alias in any OEM partition must disable every generated XML before boot.
rm -f "$MOD/my_product/fonts/Ziyu-500.ttf"
if font_config_boot_guard DemoFamily; then
    echo 'boot guard unexpectedly accepted a missing my_product UI weight alias' >&2
    exit 1
fi
for overlay in \
    "$MOD/system/etc/fonts.xml" \
    "$MOD/product/etc/mi_fonts_customization.xml" \
    "$MOD/system_ext/etc/fonts_customization.xml" \
    "$MOD/my_product/etc/oplus_fonts_customization.xml" \
    "$MOD/vendor/etc/fonts.xml" \
    "$MOD/odm/etc/fonts_customization.xml"; do
    test ! -e "$overlay"
done

# Regeneration repairs every partition alias from the validated system UI weight sets.
mkdir -p "$MOD/system/fonts"
for prefix in Ziyu; do
    for weight in 100 200 300 400 500 600 700 800 900; do
        dd if=/dev/zero of="$MOD/system/fonts/${prefix}-${weight}.ttf" bs=2048 count=1 2>/dev/null
    done
done
font_config_generate DemoFamily
test -s "$MOD/my_product/fonts/Ziyu-500.ttf"
test ! -e "$MOD/my_product/fonts/ZiyuMono-500.ttf"
font_config_disable
for partition in system product system_ext my_product vendor odm; do
    test ! -e "$MOD/$partition/fonts/Ziyu-400.ttf"
    test ! -e "$MOD/$partition/fonts/ZiyuMono-400.ttf"
done
for overlay in \
    "$MOD/system/etc/fonts.xml" \
    "$MOD/product/etc/mi_fonts_customization.xml" \
    "$MOD/system_ext/etc/fonts_customization.xml" \
    "$MOD/my_product/etc/oplus_fonts_customization.xml" \
    "$MOD/vendor/etc/fonts.xml" \
    "$MOD/odm/etc/fonts_customization.xml"; do
    test ! -e "$overlay"
done



mk_weights() {
    mkdir -p "$MOD/system/fonts"
    for _prefix in Ziyu; do
        for _weight in 100 200 300 400 500 600 700 800 900; do
            dd if=/dev/zero of="$MOD/system/fonts/${_prefix}-${_weight}.ttf" bs=2048 count=1 2>/dev/null
            chmod 0644 "$MOD/system/fonts/${_prefix}-${_weight}.ttf"
        done
    done
}
font_config_disable
mk_weights
cat > "$PRODUCT_ETC/hihonor_magic_fonts.xml" <<'XML'
<familyset>
  <family name="honor-sans"><font weight="400">HonorSans-Regular.ttf</font></family>
  <family name="honor-icons"><font weight="400">HonorIcons.ttf</font></family>
</familyset>
XML
cat > "$PRODUCT_ETC/ACME_FONT_CONFIG.xml" <<'XML'
<familyset>
  <family name="sans-serif"><font weight="500">AcmeSans-Medium.ttf</font></family>
</familyset>
XML
font_config_generate DemoFamily
test -s "$MOD/product/etc/hihonor_magic_fonts.xml"
grep -q 'Ziyu-400.ttf' "$MOD/product/etc/hihonor_magic_fonts.xml"
grep -q 'HonorIcons.ttf' "$MOD/product/etc/hihonor_magic_fonts.xml"
test -s "$MOD/product/etc/ACME_FONT_CONFIG.xml"
grep -q 'Ziyu-500.ttf' "$MOD/product/etc/ACME_FONT_CONFIG.xml"
test "$(_ziyu_font_config_specs | sort | uniq -d | wc -l)" -eq 0

# One unusable overlay partition does not veto validated XML from the others.
font_config_disable
mk_weights
rm -rf "$MOD/odm"
: > "$MOD/odm"
font_config_generate DemoFamily
test -s "$MOD/system/etc/fonts.xml"
grep -q 'Ziyu-400.ttf' "$MOD/system/etc/fonts.xml"
test -s "$MOD/product/etc/mi_fonts_customization.xml"
test ! -e "$MOD/odm/etc/fonts_customization.xml"
rm -f "$MOD/odm"

# Missing master weights are a global XML failure.
font_config_disable
mk_weights
rm -f "$MOD/system/fonts/Ziyu-500.ttf"
if _ziyu_font_config_generate_base DemoFamily; then
    echo 'XML overlay unexpectedly generated without a complete nine-weight set' >&2
    exit 1
fi
test ! -e "$MOD/system/etc/fonts.xml"
test ! -e "$MOD/product/etc/mi_fonts_customization.xml"
test ! -e "$MOD/product/etc/hihonor_magic_fonts.xml"
test ! -e "$MOD/product/etc/ACME_FONT_CONFIG.xml"

printf 'Font configuration runtime tests passed.\n'
