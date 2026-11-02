#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FONT="$1"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

mkdir -p "$TMP/module/common" "$TMP/public/fonts"
cp "$ROOT/common/font_source_profile.sh" "$TMP/module/common/"
cp "$ROOT/common/font_source_profile.py" "$TMP/module/common/"
cp "$ROOT/common/font_web_convert.py" "$TMP/module/common/"
cp "$ROOT/common/font_coverage.py" "$TMP/module/common/"
cp "$FONT" "$TMP/public/fonts/Demo-Regular.ttf"

OUT=$(MODDIR="$TMP/module" ZIYU_PUBLIC_DIR="$TMP/public" ZIYU_PYTHON=python3 \
    sh "$TMP/module/common/font_source_profile.sh" family Demo-Regular)
printf '%s\n' "$OUT" | grep -q '"status":"ok"'
printf '%s\n' "$OUT" | grep -q '"latinUi":true'

PROFILE=$(MODDIR="$TMP/module" ZIYU_PUBLIC_DIR="$TMP/public" ZIYU_PYTHON=python3 \
    sh "$TMP/module/common/font_source_profile.sh" path Demo-Regular)
test -s "$PROFILE"

VALID=$(MODDIR="$TMP/module" ZIYU_PUBLIC_DIR="$TMP/public" ZIYU_PYTHON=python3 \
    sh "$TMP/module/common/font_source_profile.sh" validate Demo-Regular)
printf '%s\n' "$VALID" | grep -q '"status":"ok"'

if MODDIR="$TMP/module" ZIYU_PUBLIC_DIR="$TMP/public" ZIYU_PYTHON=python3 \
    sh "$TMP/module/common/font_source_profile.sh" family '../bad' >/dev/null 2>&1; then
    echo "unsafe family id unexpectedly accepted" >&2
    exit 1
fi

echo "font_source_profile_bridge_test: PASS"
