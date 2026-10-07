#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

FONT=$(find /usr/share/fonts -type f -iname 'DejaVuSans.ttf' -print -quit 2>/dev/null || true)
if [ ! -s "$FONT" ]; then
    echo 'Native ZIP import test skipped: DejaVu Sans is unavailable.'
    exit 0
fi
mkdir -p "$TMP/pkg" "$TMP/stylepkg" "$TMP/public/import" "$TMP/public/fonts" "$TMP/cache" "$TMP/config"

# 文件名故意不含任何字重提示；导入器必须读取 OS/2.usWeightClass。
PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S - "$FONT" "$TMP/pkg/Mystery-A.ttf" 200 <<'PY'
import sys
from fontTools.ttLib import TTFont
font = TTFont(sys.argv[1])
font['OS/2'].usWeightClass = int(sys.argv[3])
font.save(sys.argv[2])
PY
PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S - "$FONT" "$TMP/pkg/Mystery-B.ttf" 800 <<'PY'
import sys
from fontTools.ttLib import TTFont
font = TTFont(sys.argv[1])
font['OS/2'].usWeightClass = int(sys.argv[3])
font.save(sys.argv[2])
PY

test "$(PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S "$ROOT/common/font_import_probe.py" "$TMP/pkg/Mystery-A.ttf" | cut -d'|' -f3)" = 200
test "$(PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S "$ROOT/common/font_import_probe.py" "$TMP/pkg/Mystery-B.ttf" | cut -d'|' -f3)" = 800
test "$(PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S "$ROOT/common/font_import_probe.py" "$TMP/pkg/Mystery-B.ttf" | cut -d'|' -f6)" = false

# Import a TTC collection as its original file. It must not fan out into one
# library TTF per face, and importing the same collection twice is a no-op.
PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S - \
  "$TMP/pkg/Mystery-A.ttf" "$TMP/pkg/Mystery-B.ttf" "$TMP/collection.ttc" <<'PY'
import sys
from fontTools.ttLib import TTCollection, TTFont

first, second, output = sys.argv[1:]
fonts = [TTFont(first), TTFont(second)]
try:
    collection = TTCollection()
    collection.fonts = fonts
    collection.save(output)
finally:
    for font in fonts:
        font.close()
PY
mkdir -p "$TMP/module/config"
ln -s "$ROOT/common" "$TMP/module/common"
mkdir -p "$TMP/ttc-public/fonts"
TTC_HASH_PREFIX=$(sha256sum "$TMP/collection.ttc" | awk '{print substr($1, 1, 10)}')
printf 'old extracted face\n' > "$TMP/ttc-public/fonts/Legacy-TTCFace01-${TTC_HASH_PREFIX}.ttf"
TTC_OUTPUT=$(sh -c '
    set -eu
    MODDIR="$1/module"; MODULE_DIR="$1/module"
    LUOSHU_PUBLIC_DIR="$1/ttc-public"
    LUOSHU_IMPORT_PYTHON=python3
    PYTHONPATH="$2/common/python/lib/python3.14/site-packages"
    export MODDIR MODULE_DIR LUOSHU_PUBLIC_DIR LUOSHU_IMPORT_PYTHON PYTHONPATH
    mkdir -p "$LUOSHU_PUBLIC_DIR/fonts" "$MODDIR/config"
    NATIVE_IMPORT_LIBRARY_ONLY=true
    export NATIVE_IMPORT_LIBRARY_ONLY
    . "$MODDIR/common/native_import.sh"
    schedule_font_prewarm() { return 0; }
    import_font_file "$1/collection.ttc" "Collection Fixture.ttc"
' sh "$TMP" "$ROOT")
printf '%s\n' "$TTC_OUTPUT" | grep -q '"status":"ok"'
printf '%s\n' "$TTC_OUTPUT" | grep -q '"format":"TTC"'
test -s "$TMP/ttc-public/fonts/Collection Fixture.ttc"
cmp -s "$TMP/collection.ttc" "$TMP/ttc-public/fonts/Collection Fixture.ttc"
test ! -e "$TMP/ttc-public/fonts/Legacy-TTCFace01-${TTC_HASH_PREFIX}.ttf"
test "$(find "$TMP/ttc-public/fonts" -maxdepth 1 -type f | wc -l | tr -d '[:space:]')" = 2
test "$(find "$TMP/ttc-public/fonts" -maxdepth 1 -type f -iname '*.ttf' | wc -l | tr -d '[:space:]')" = 0
TTC_DUPLICATE=$(sh -c '
    MODDIR="$1/module"; MODULE_DIR="$1/module"
    LUOSHU_PUBLIC_DIR="$1/ttc-public"
    LUOSHU_IMPORT_PYTHON=python3
    PYTHONPATH="$2/common/python/lib/python3.14/site-packages"
    export MODDIR MODULE_DIR LUOSHU_PUBLIC_DIR LUOSHU_IMPORT_PYTHON PYTHONPATH
    NATIVE_IMPORT_LIBRARY_ONLY=true
    export NATIVE_IMPORT_LIBRARY_ONLY
    . "$MODDIR/common/native_import.sh"
    schedule_font_prewarm() { return 0; }
    import_font_file "$1/collection.ttc" "Collection Fixture.ttc"
' sh "$TMP" "$ROOT")
printf '%s\n' "$TTC_DUPLICATE" | grep -q '"duplicate":true'

cat > "$TMP/pkg/module.prop" <<'PROP'
id=luoshu_zip_regression
name=洛书 ZIP 回归字体
version=1.0
versionCode=1
author=LuoShu CI
PROP
(cd "$TMP/pkg" && zip -q -r "$TMP/public/import/regression.zip" .)

SOURCE_RESULT=$(sh -c '
    zip_path="$1"
    checker="$2"
    set -- "$zip_path"
    . "$checker"
    printf sourced-ok
' sh "$TMP/public/import/regression.zip" "$ROOT/common/font_check.sh")
test "$SOURCE_RESULT" = sourced-ok

# App 的原生导入桥必须直接装配安全 ZIP 导入器与第三方模块字重兼容层。
grep -q 'common/font_import.sh' "$ROOT/common/native_import.sh"
grep -q 'common/font_import_compat.sh' "$ROOT/common/native_import.sh"
grep -Fq 'font_web_convert.py' "$ROOT/common/native_import.sh"
grep -Fq 'woff|woff2)' "$ROOT/common/native_import.sh"
grep -Fq 'import_web_font_file' "$ROOT/common/native_import.sh"
grep -Fq 'web-font-import.$$' "$ROOT/common/native_import.sh"
grep -Fq 'source-font-profiles' "$ROOT/common/native_import.sh"
grep -q 'import_zip_package "$(basename "$_target")"' "$ROOT/common/native_import.sh"
! grep -q 'action import_zip' "$ROOT/common/native_import.sh"

OUTPUT=$(sh -c '
    set -eu
    MODDIR="$1"; MODULE_DIR="$1"; CONFIG_DIR="$2/config"
    LUOSHU_PUBLIC_DIR="$2/public"; USER_FONTS_DIR="$2/public/fonts"
    USER_IMPORT_DIR="$2/public/import"; IMPORT_CACHE_DIR="$2/cache"
    LUOSHU_IMPORT_PYTHON=python3
    PYTHONPATH="$1/common/python/lib/python3.14/site-packages"
    export LUOSHU_IMPORT_PYTHON PYTHONPATH
    json_escape() {
        printf "%s" "$1" | sed "s/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g" | tr "\n\r" "  "
    }
    . "$1/common/util_functions.sh"
    . "$1/common/font_check.sh"
    . "$1/common/font_import.sh"
    . "$1/common/font_import_compat.sh"
    import_zip_package regression.zip
' sh "$ROOT" "$TMP")
printf '%s\n' "$OUTPUT" | grep -q '"status":"ok"'
printf '%s\n' "$OUTPUT" | grep -q '"mode":"family"'
printf '%s\n' "$OUTPUT" | grep -q '"importedText":2'
find "$TMP/public/fonts" -maxdepth 1 -type f -name '*-ExtraLight.ttf' -print -quit | grep -q .
find "$TMP/public/fonts" -maxdepth 1 -type f -name '*-ExtraBold.ttf' -print -quit | grep -q .

# 复现“超级花轮丸”类模块：文件名属于同一个 RobotoFake 字体族，但字体内部
# family 名故意随字重变化。BlackItalic 必须保留，Thin 也不能被伪装成 Regular。
PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S - "$FONT" "$TMP/stylepkg/RobotoFake-Thin.ttf" 100 'RobotoFake Thin Internal' Thin false <<'PY'
import sys
from fontTools.ttLib import TTFont

source, target, weight, family, subfamily, italic = sys.argv[1:]
font = TTFont(source)
font['OS/2'].usWeightClass = int(weight)
font['head'].macStyle = int(font['head'].macStyle) & ~0x02
font['OS/2'].fsSelection = int(font['OS/2'].fsSelection) & ~0x01
font['post'].italicAngle = 0
name = font['name']
for platform_id, encoding_id, language_id in ((3, 1, 0x409), (1, 0, 0)):
    for name_id, value in ((1, family), (16, family), (2, subfamily), (17, subfamily),
                           (4, f'{family} {subfamily}'), (6, 'RobotoFake-Thin-Internal')):
        name.setName(value, name_id, platform_id, encoding_id, language_id)
font.save(target)
PY
PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S - "$FONT" "$TMP/stylepkg/RobotoFake-BlackItalic.ttf" 900 'RobotoFake Black Internal' 'Black Italic' true <<'PY'
import sys
from fontTools.ttLib import TTFont

source, target, weight, family, subfamily, italic = sys.argv[1:]
font = TTFont(source)
font['OS/2'].usWeightClass = int(weight)
font['head'].macStyle = int(font['head'].macStyle) | 0x02
font['OS/2'].fsSelection = (int(font['OS/2'].fsSelection) | 0x01) & ~(1 << 6)
font['post'].italicAngle = -12
name = font['name']
for platform_id, encoding_id, language_id in ((3, 1, 0x409), (1, 0, 0)):
    for name_id, value in ((1, family), (16, family), (2, subfamily), (17, subfamily),
                           (4, f'{family} {subfamily}'), (6, 'RobotoFake-BlackItalic-Internal')):
        name.setName(value, name_id, platform_id, encoding_id, language_id)
font.save(target)
PY
cat > "$TMP/stylepkg/module.prop" <<'PROP'
id=super_hualunwan_regression
name=超级花轮丸
version=1.0
versionCode=1
author=LuoShu CI
PROP
(cd "$TMP/stylepkg" && zip -q -r "$TMP/public/import/super-hualunwan.zip" .)

STYLE_OUTPUT=$(sh -c '
    set -eu
    MODDIR="$1"; MODULE_DIR="$1"; CONFIG_DIR="$2/config"
    LUOSHU_PUBLIC_DIR="$2/public"; USER_FONTS_DIR="$2/public/fonts"
    USER_IMPORT_DIR="$2/public/import"; IMPORT_CACHE_DIR="$2/cache"
    LUOSHU_IMPORT_PYTHON=python3
    PYTHONPATH="$1/common/python/lib/python3.14/site-packages"
    export LUOSHU_IMPORT_PYTHON PYTHONPATH
    json_escape() {
        printf "%s" "$1" | sed "s/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g" | tr "\n\r" "  "
    }
    . "$1/common/util_functions.sh"
    . "$1/common/font_check.sh"
    . "$1/common/font_import.sh"
    . "$1/common/font_import_compat.sh"
    import_zip_package super-hualunwan.zip
' sh "$ROOT" "$TMP")
printf '%s\n' "$STYLE_OUTPUT" | grep -q '"status":"ok"'
printf '%s\n' "$STYLE_OUTPUT" | grep -q '"mode":"family"'
printf '%s\n' "$STYLE_OUTPUT" | grep -q '"importedText":2'
printf '%s\n' "$STYLE_OUTPUT" | grep -q '"id":"超级花轮丸"'
printf '%s\n' "$STYLE_OUTPUT" | grep -q '"supportsCjk":false'
THIN="$TMP/public/fonts/超级花轮丸-Thin.ttf"
BLACK_ITALIC="$TMP/public/fonts/超级花轮丸-Italic-Black.ttf"
test -s "$THIN"
test -s "$BLACK_ITALIC"
test ! -e "$TMP/public/fonts/超级花轮丸-Regular.ttf"
test "$(PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S "$ROOT/common/font_import_probe.py" "$THIN" | cut -d'|' -f3)" = 100
test "$(PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S "$ROOT/common/font_import_probe.py" "$BLACK_ITALIC" | cut -d'|' -f3)" = 900
test "$(PYTHONPATH="$ROOT/common/python/lib/python3.14/site-packages" python3 -S "$ROOT/common/font_import_probe.py" "$BLACK_ITALIC" | cut -d'|' -f4)" = true
FAMILY_RESULT=$(sh -c '
    MODULE_DIR="$1"; LUOSHU_PUBLIC_DIR="$2/public"; USER_FONTS_DIR="$2/public/fonts"
    . "$1/common/util_functions.sh"
    printf "%s|%s\n" "$(detect_font_family "超级花轮丸-Italic-Black.ttf")" "$(scan_family_weights "超级花轮丸")"
' sh "$ROOT" "$TMP")
test "$FAMILY_RESULT" = '超级花轮丸|thin,black'
grep -q '^supports_cjk=false$' "$TMP/public/fonts/超级花轮丸.conf"
grep -q '_candidate_size.*_source_size' "$ROOT/common/native_import.sh"

echo 'Native font-module ZIP import bridge, internal-weight and italic-family regression tests passed.'
