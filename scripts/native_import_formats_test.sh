#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

PYTHON=${LUOSHU_TEST_PYTHON:-python3}
TTF=${LUOSHU_TEST_FONT_TTF:-}
OTF=${LUOSHU_TEST_FONT_OTF:-}
if [ ! -s "$TTF" ]; then
    TTF=$(find /usr/share/fonts -type f -iname 'DejaVuSans.ttf' -print -quit 2>/dev/null || true)
fi
if [ ! -s "$TTF" ] && [ -s /c/Windows/Fonts/arial.ttf ]; then TTF=/c/Windows/Fonts/arial.ttf; fi
if [ ! -s "$OTF" ]; then
    OTF=$(find /usr/share/fonts -type f -iname '*.otf' -print -quit 2>/dev/null || true)
fi
if [ ! -s "$OTF" ] && [ -s /c/Windows/Fonts/DavidCLM-Medium.otf ]; then OTF=/c/Windows/Fonts/DavidCLM-Medium.otf; fi
if [ ! -s "$TTF" ] || [ ! -s "$OTF" ]; then
    echo 'Native font format test skipped: a host TTF and OTF fixture are required.'
    exit 0
fi
python_path_arg() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}
TTF_PY=$(python_path_arg "$TTF")
OTF_PY=$(python_path_arg "$OTF")
FIXTURES_PY=$(python_path_arg "$TMP/fixtures")

MOD="$TMP/module"
PUBLIC="$TMP/public"
mkdir -p "$MOD/common/python/bin" "$MOD/config" "$MOD/cache" "$PUBLIC/fonts" "$PUBLIC/import" "$TMP/fixtures"
for file in native_import.sh util_functions.sh util_functions_core.sh font_check.sh font_import.sh \
            font_import_compat.sh font_web_convert.py font_import_probe.py font_structure.py font_zip_extract.py; do
    cp "$ROOT/common/$file" "$MOD/common/$file"
done

cat > "$MOD/common/python/bin/luoshu-python" <<EOF_PY
#!/bin/bash
unset PYTHONHOME PYTHONPATH LD_LIBRARY_PATH
if command -v cygpath >/dev/null 2>&1; then
    converted=()
    for arg in "\$@"; do
        case "\$arg" in /*) arg=\$(cygpath -w "\$arg") ;; esac
        converted+=("\$arg")
    done
    exec "$PYTHON" "\${converted[@]}"
fi
exec "$PYTHON" "\$@"
EOF_PY
chmod 0755 "$MOD/common/python/bin/luoshu-python"

"$PYTHON" - "$TTF_PY" "$OTF_PY" "$FIXTURES_PY" <<'PY'
from pathlib import Path
from fontTools.ttLib import TTCollection, TTFont
import sys

ttf_path, otf_path, root = map(Path, sys.argv[1:])
root.mkdir(parents=True, exist_ok=True)

def set_names(font, family: str, subfamily: str):
    for record in font['name'].names:
        if record.nameID not in (1, 2, 4, 6):
            continue
        value = {1: family, 2: subfamily, 4: f'{family} {subfamily}', 6: f'{family}-{subfamily}'}[record.nameID]
        try:
            record.string = value.encode(record.getEncoding())
        except Exception:
            record.string = value.encode('utf-16-be')

def make_ttf(family: str, subfamily: str, weight: int, target: Path):
    font = TTFont(str(ttf_path), lazy=False)
    set_names(font, family, subfamily)
    font['OS/2'].usWeightClass = weight
    font['head'].macStyle = 1 if weight >= 700 else 0
    font.flavor = None
    font.save(target)
    font.close()

make_ttf('Format TTF', 'Regular', 400, root / 'Format-TTF.ttf')
otf = TTFont(str(otf_path), lazy=False)
set_names(otf, 'Format OTF', 'Regular')
otf.flavor = None
otf.save(root / 'Format-OTF.otf')
otf.close()

faces = []
for subfamily, weight in (('Regular', 400), ('Bold', 700)):
    face = TTFont(str(ttf_path), lazy=False)
    set_names(face, 'Format TTC', subfamily)
    face['OS/2'].usWeightClass = weight
    face['head'].macStyle = 1 if weight >= 700 else 0
    face.flavor = None
    faces.append(face)
collection = TTCollection()
collection.fonts = faces
collection.save(root / 'Format-TTC.ttc')
for face in faces:
    face.close()

for kind in ('woff', 'woff2'):
    font = TTFont(str(ttf_path), lazy=False)
    set_names(font, f'Format {kind.upper()}', 'Regular')
    font.flavor = kind
    font.save(root / f'Format-{kind.upper()}.{kind}')
    font.close()

make_ttf('Zip Family', 'Regular', 400, root / 'Zip-Family-Regular.ttf')
make_ttf('Zip Family', 'Bold', 700, root / 'Zip-Family-Bold.ttf')
(root / 'invalid.ttf').write_bytes(b'NOPE' + b'\0' * 8192)
(root / 'invalid.woff').write_bytes(b'wOFF' + b'\0' * 8192)
PY

"$PYTHON" - "$FIXTURES_PY" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys
root = Path(sys.argv[1])
with ZipFile(root / 'Fixture-Module.zip', 'w', ZIP_DEFLATED) as archive:
    archive.writestr('module.prop', 'id=fixture\nname=Fixture ZIP\nversion=1\nversionCode=1\nauthor=test\n')
    archive.write(root / 'Zip-Family-Regular.ttf', 'fonts/static/Zip-Family-Regular.ttf')
    archive.write(root / 'Zip-Family-Bold.ttf', 'fonts/static/Zip-Family-Bold.ttf')
with ZipFile(root / 'No-Fonts.zip', 'w', ZIP_DEFLATED) as archive:
    archive.writestr('module.prop', 'id=empty\nname=Empty\nversion=1\nversionCode=1\n')
PY

export MODDIR="$MOD" MODULE_DIR="$MOD" LUOSHU_PUBLIC_DIR="$PUBLIC"
export LUOSHU_IMPORT_PYTHON="$MOD/common/python/bin/luoshu-python"
export LUOSHU_HOST_PYTHON="$MOD/common/python/bin/luoshu-python"
export NATIVE_IMPORT_LIBRARY_ONLY=true
. "$MOD/common/native_import.sh"
schedule_font_prewarm() { return 0; }

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
count_files() { find "$PUBLIC/fonts" -maxdepth 1 -type f | wc -l | tr -d '[:space:]'; }
expect_import() {
    _result="$1"; _family="$2"; _expected="$3"
    printf '%s\n' "$_result" | grep -q '"status":"ok"' || fail "$_family import failed: $_result"
    [ "$(find "$PUBLIC/fonts" -maxdepth 1 -type f -name "$_family*" | wc -l | tr -d '[:space:]')" = "$_expected" ] || \
        fail "$_family generated an unexpected number of persistent files"
}

R=$(import_font_file "$TMP/fixtures/Format-TTF.ttf" 'Format-TTF.ttf')
expect_import "$R" Format-TTF 2
R=$(import_font_file "$TMP/fixtures/Format-TTF.ttf" 'Format-TTF.ttf')
printf '%s\n' "$R" | grep -q '"duplicate":true' || fail 'TTF duplicate import was not deduplicated'
[ "$(count_files)" = 2 ] || fail 'duplicate TTF increased the library file count'

R=$(import_font_file "$TMP/fixtures/Format-OTF.otf" 'Format-OTF.otf')
expect_import "$R" Format-OTF 2
R=$(import_font_file "$TMP/fixtures/Format-TTC.ttc" 'Format-TTC.ttc')
expect_import "$R" Format-TTC 2
[ "$(find "$PUBLIC/fonts" -maxdepth 1 -type f -name 'Format-TTC-TTCFace*' | wc -l | tr -d '[:space:]')" = 0 ] || \
    fail 'TTC import fanned one collection out into per-face files'

R=$(import_web_font_file "$TMP/fixtures/Format-WOFF.woff" 'Format-WOFF.woff')
expect_import "$R" Format-WOFF 2
R=$(import_web_font_file "$TMP/fixtures/Format-WOFF2.woff2" 'Format-WOFF2.woff2')
expect_import "$R" Format-WOFF2 2
[ "$(find "$MOD/cache" -maxdepth 1 -type d -name 'web-font-import.*' | wc -l | tr -d '[:space:]')" = 0 ] || \
    fail 'web-font import left temporary conversion directories behind'

mkdir -p "$PUBLIC/import"
cp "$TMP/fixtures/Fixture-Module.zip" "$PUBLIC/import/Fixture-Module.zip"
R=$(import_zip_package Fixture-Module.zip)
if ! printf '%s\n' "$R" | grep -q '"status":"ok"'; then
    printf 'ZIP listing:\n' >&2
    unzip -l "$PUBLIC/import/Fixture-Module.zip" >&2 || true
    printf 'Fixture TTF validation:\n' >&2
    font_check_json "$TMP/fixtures/Zip-Family-Regular.ttf" text >&2 || true
    fail "font module ZIP import failed: $R"
fi
printf '%s\n' "$R" | grep -q '"importedText":2' || fail 'font module ZIP did not report two imported weights'
[ "$(find "$PUBLIC/fonts" -maxdepth 1 -type f -name 'Fixture-ZIP*' | wc -l | tr -d '[:space:]')" = 3 ] || \
    fail 'two-weight ZIP should create two font files and one family config'

BEFORE=$(count_files)
if import_font_file "$TMP/fixtures/invalid.ttf" 'Broken.ttf' > "$TMP/bad-ttf"; then fail 'corrupt TTF was accepted'; fi
grep -q '"status":"error"' "$TMP/bad-ttf" || fail 'corrupt TTF error was not returned'
if import_web_font_file "$TMP/fixtures/invalid.woff" 'Broken.woff' > "$TMP/bad-woff"; then fail 'corrupt WOFF was accepted'; fi
grep -q '"status":"error"' "$TMP/bad-woff" || fail 'corrupt WOFF error was not returned'
cp "$TMP/fixtures/No-Fonts.zip" "$PUBLIC/import/No-Fonts.zip"
R=$(import_zip_package No-Fonts.zip)
printf '%s\n' "$R" | grep -q '"status":"error"' || fail 'fontless ZIP was accepted'
[ "$(count_files)" = "$BEFORE" ] || fail 'failed imports changed the persistent font library'

# Exercise the actual ColorOS physical mapping stage in an isolated fixture. This
# confirms the imported SFNT/container reaches the slot-mapping code; it does not
# emulate a device boot, OEM template, mount namespace, or Android font renderer.
. "$ROOT/common/legacy_v14_4/util_functions.sh"
. "$ROOT/common/legacy_v14_4/rom_adapters.sh"
get_all_coloros_names() { printf 'SysSans-Hans-Regular\nSysSans-En-Regular\n'; }
_rom_font_target_exists() { return 0; }
IS_COLOROS=true
IS_HYPEROS=false
export IS_COLOROS IS_HYPEROS
for entry in \
    'Format-TTF|Format-TTF.ttf' \
    'Format-OTF|Format-OTF.otf' \
    'Format-TTC|Format-TTC.ttc' \
    'Format-WOFF|Format-WOFF.ttf' \
    'Format-WOFF2|Format-WOFF2.ttf' \
    'Fixture-ZIP|Fixture-ZIP-Regular.ttf'; do
    _id=${entry%%|*}
    _file=${entry#*|}
    _source="$PUBLIC/fonts/$_file"
    _destination="$TMP/apply/$_id/system/fonts"
    mkdir -p "$_destination"
    copy_as_coloros "$_source" "$_destination" quick "$_id" >/dev/null || fail "$_id failed the physical mapping stage"
    cmp -s "$_source" "$_destination/SysSans-Hans-Regular.ttf" || fail "$_id source did not reach the mapped ColorOS slot"
done

for _font in Format-TTF.ttf Format-WOFF.ttf Format-WOFF2.ttf Fixture-ZIP-Regular.ttf; do
    "$LUOSHU_IMPORT_PYTHON" "$ROOT/scripts/device_font_slot_build_test.py" \
        --font "$PUBLIC/fonts/$_font" >/dev/null 2>&1 || fail "$_font failed device font slot generation"
done
"$LUOSHU_IMPORT_PYTHON" "$ROOT/scripts/device_font_slot_build_test.py" \
    --font "$PUBLIC/fonts/Format-TTC.ttc" --source-index 0 >/dev/null 2>&1 || \
    fail 'TTC face 0 failed device font slot generation'

printf 'PASS import: TTF, OTF, TTC, WOFF, WOFF2, font-module ZIP; corrupt TTF/WOFF and fontless ZIP rejected.\n'
printf 'PASS file counts: one-file formats keep one font + one family config; two-weight ZIP keeps two fonts + one config; duplicate TTC faces were not expanded.\n'
printf 'PASS apply simulation: TrueType glyf TTF, TTC face 0, converted WOFF/WOFF2 and ZIP fonts built device font slots; all imported SFNT outputs reached ColorOS physical mapping; actual phone activation was not run.\n'
