#!/bin/sh
# Staging-only build used for fast debug deliverables: runs every gate of
# scripts/build.sh EXCEPT the full check.sh suite (already passed for this
# tree) and uses a Python zip helper so Unix exec modes are preserved.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export PATH="/tmp/zy-pybin:$PATH"
. "$ROOT/scripts/version.sh"
OUT="$ROOT/dist"
STAGE="$OUT/Ziyu"
ZIP="$OUT/Ziyu-${LUOSHU_ARTIFACT_VERSION}.zip"
ZIP_NAME=$(basename "$ZIP")
SIZE_REPORT="$OUT/Ziyu-${LUOSHU_ARTIFACT_VERSION}-size.txt"
APP_APK="${LUOSHU_APP_APK:-}"
ALLOW_DEBUG_APP="${LUOSHU_ALLOW_DEBUG_APP:-0}"
EXPECTED_VERSION_CODE="$LUOSHU_APP_VERSION_CODE"
MAX_ZIP_BYTES="${LUOSHU_MAX_ZIP_BYTES:-11796480}"

[ -s "$APP_APK" ] || { echo 'LUOSHU_APP_APK is required.' >&2; exit 65; }
APP_PACKAGE="${LUOSHU_APP_PACKAGE:-}"
APP_VERSION_CODE="${LUOSHU_APP_VERSION_CODE:-}"
case "$APP_PACKAGE" in
  io.github.xgl34222220.ziyu) ;;
  io.github.xgl34222220.ziyu.debug)
    [ "$ALLOW_DEBUG_APP" = 1 ] || { echo 'Debug App packaging requires LUOSHU_ALLOW_DEBUG_APP=1.' >&2; exit 68; } ;;
  *) echo "Unexpected APK package: $APP_PACKAGE" >&2; exit 68 ;;
esac
[ "$APP_VERSION_CODE" -eq "$EXPECTED_VERSION_CODE" ] || {
  echo "APK versionCode mismatch: expected $EXPECTED_VERSION_CODE, got $APP_VERSION_CODE" >&2; exit 67; }

rm -rf "$STAGE"
mkdir -p "$STAGE" "$OUT"
PAYLOAD_MANIFEST="$ROOT/scripts/module_payload_manifest.txt"
test -s "$PAYLOAD_MANIFEST"
while IFS= read -r path || [ -n "$path" ]; do
  case "$path" in ''|\#*) continue ;; esac
  [ -e "$ROOT/$path" ] || { echo "Missing payload manifest entry: $path" >&2; exit 69; }
  mkdir -p "$(dirname "$STAGE/$path")"
  cp -a "$ROOT/$path" "$STAGE/$path"
done < "$PAYLOAD_MANIFEST"
[ ! -f "$STAGE/config/version_notes.conf" ] || sed -i "s/^version=.*/version=$LUOSHU_VERSION/" "$STAGE/config/version_notes.conf"

mkdir -p "$STAGE/bundled"
cp -f "$APP_APK" "$STAGE/bundled/Ziyu-App.apk"
APP_SHA256=$(sha256sum "$STAGE/bundled/Ziyu-App.apk" | awk '{print $1}')
{
  printf 'package=%s\n' "$APP_PACKAGE"
  printf 'versionCode=%s\n' "$APP_VERSION_CODE"
  printf 'versionName=%s\n' "$LUOSHU_VERSION"
  printf 'sha256=%s\n' "$APP_SHA256"
} > "$STAGE/bundled/app.prop"
chmod 0644 "$STAGE/bundled/Ziyu-App.apk" "$STAGE/bundled/app.prop"

find "$STAGE" -type f -name '*.log' -delete
find "$STAGE" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
find "$STAGE" -type f -name '*.pyc' -delete
rm -rf "$STAGE/webroot" "$STAGE/logs" "$STAGE/backup" "$STAGE/config/recovery"
rm -f "$STAGE/config/webui_font_list.json" "$STAGE/config/webui_font_list.key" \
  "$STAGE/config/native_font_index.json" "$STAGE/config/native_font_index.key" \
  "$STAGE/config/recent_fonts.conf" "$STAGE/config/previous_font.conf" \
  "$STAGE/config/switch_task.conf" "$STAGE/config/mix_task.conf" \
  "$STAGE/config/axes_task.conf" "$STAGE/config/font_mix.conf" \
  "$STAGE/config/active_emoji.conf" "$STAGE/config/emoji_task.conf" \
  "$STAGE/config/emoji_reboot_required.conf" "$STAGE/config/font_weight_reboot_required.conf" \
  "$STAGE/config/mount_compat.conf" "$STAGE/config/app_install_pending" \
  "$STAGE/config/app_install_state.conf"
rm -f "$STAGE/system/fonts/NotoColorEmoji.ttf" "$STAGE/system/fonts/NotoColorEmojiLegacy.ttf"
rm -f "$STAGE/common/stability.sh" "$STAGE/common/fonts_xml_template.sh" \
  "$STAGE/common/play_font_bridge.sh" "$STAGE/common/wechat_xweb_bridge.sh"

sh "$ROOT/scripts/prune_python_runtime.sh" "$STAGE"

find "$STAGE/common" -maxdepth 1 -type f -exec chmod 0755 {} +
chmod 0755 "$STAGE"/*.sh "$STAGE/system/bin/luoshud" "$STAGE/common/python/bin/luoshu-python"
find "$STAGE/system/fonts" -type f -exec chmod 0644 {} + 2>/dev/null || true

test ! -e "$STAGE/webroot"
test -s "$STAGE/bundled/Ziyu-App.apk"
test -s "$STAGE/bundled/app.prop"
grep -qx "package=$APP_PACKAGE" "$STAGE/bundled/app.prop"
grep -qx "versionCode=$EXPECTED_VERSION_CODE" "$STAGE/bundled/app.prop"
grep -Eq '^sha256=[0-9a-f]{64}$' "$STAGE/bundled/app.prop"

for forbidden in \
  webroot config/active_emoji.conf config/emoji_task.conf config/emoji_reboot_required.conf \
  system/fonts/NotoColorEmoji.ttf system/fonts/NotoColorEmojiLegacy.ttf \
  common/stability.sh common/fonts_xml_template.sh common/play_font_bridge common/wechat_xweb_bridge common/volume_key.sh common/legacy_data_fonts_cleanup.sh; do
  [ ! -e "$STAGE/$forbidden" ] || { echo "forbidden payload: $forbidden" >&2; exit 88; }
done

rm -f "$ZIP" "$ZIP.sha256" "$SIZE_REPORT"
python3 "$ROOT/.runtime-work/make_zip.py" "$STAGE" "$ZIP"
(cd "$OUT" && sha256sum "$ZIP_NAME" > "$ZIP_NAME.sha256")
python3 - "$ZIP" > "$SIZE_REPORT" <<'PY'
import collections
import os
import sys
import zipfile

path = sys.argv[1]
groups = collections.defaultdict(lambda: [0, 0, 0])
with zipfile.ZipFile(path) as archive:
    for item in archive.infolist():
        group = item.filename.split('/', 1)[0] or '(root)'
        groups[group][0] += item.file_size
        groups[group][1] += item.compress_size
        groups[group][2] += 1
print(f"artifact={os.path.basename(path)}")
print(f"zip_bytes={os.path.getsize(path)}")
for name, (raw, compressed, count) in sorted(groups.items(), key=lambda entry: entry[1][1], reverse=True):
    print(f"{name}\traw={raw}\tcompressed={compressed}\tfiles={count}")
PY

ZIP_BYTES=$(wc -c < "$ZIP" | tr -d '[:space:]')
[ "$ZIP_BYTES" -le "$MAX_ZIP_BYTES" ] || {
  echo "Module ZIP grew beyond budget: $ZIP_BYTES > $MAX_ZIP_BYTES bytes" >&2
  cat "$SIZE_REPORT" >&2
  exit 90
}

printf 'Built: %s\n' "$ZIP"
printf 'Bundled App: %s (%s)\n' "$STAGE/bundled/Ziyu-App.apk" "$APP_PACKAGE"
printf 'Size report: %s (%s / %s bytes budget)\n' "$SIZE_REPORT" "$ZIP_BYTES" "$MAX_ZIP_BYTES"
rm -rf "$STAGE"
