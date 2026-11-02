#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t ziyu-v4-validate)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MOD="$TMP/module"
mkdir -p "$MOD/system/fonts/.ziyu-font-store" "$MOD/system/etc" "$MOD/config"
dd if=/dev/zero of="$MOD/system/fonts/.ziyu-font-store/mix-composite.font" bs=2048 count=1 2>/dev/null
ln "$MOD/system/fonts/.ziyu-font-store/mix-composite.font" "$MOD/system/fonts/Ziyu-400.ttf"

# 120 aliases share one inode. The foreground validator must use metadata only.
: > "$MOD/config/font-target-aliases.conf"
i=1
while [ "$i" -le 120 ]; do
    name=$(printf 'Alias%03d.ttf' "$i")
    ln "$MOD/system/fonts/.ziyu-font-store/mix-composite.font" "$MOD/system/fonts/$name"
    printf 'system/fonts/%s|system/fonts.xml|400|Test\n' "$name" >> "$MOD/config/font-target-aliases.conf"
    i=$((i + 1))
done
cat > "$MOD/config/font-target-coverage.conf" <<'EOF'
targets=120
mapped=120
status=full
EOF
cat > "$MOD/system/etc/fonts.xml" <<'EOF'
<familyset><family name="sans"><font weight="400">Ziyu-400.ttf</font></family></familyset>
EOF
printf 'mix\n' > "$MOD/config/active_font.conf"

MODULE_DIR="$MOD"
MODDIR="$MOD"
export MODULE_DIR MODDIR
_ziyu_safety_module() { printf '%s\n' "$MOD"; }
_ziyu_safety_config() { printf '%s/config\n' "$MOD"; }
_ziyu_payload_parts() { printf '%s\n' system; }
_ziyu_font_config_specs() {
    printf 'system/fonts.xml|/system/etc/fonts.xml|%s/system/etc/fonts.xml|%s/system/fonts\n' "$MOD" "$MOD"
}
_ziyu_fast_font_ok() {
    [ -f "$1" ] || return 1
    size=$(stat -c '%s' "$1" 2>/dev/null || wc -c < "$1")
    [ "$size" -ge 1024 ]
}
_ziyu_safety_log() { :; }
ziyu_meta_content_roots() { return 0; }
# Old 94% code invoked this embedded-Python path for every XML. Any call is a failure here.
_ziyu_font_config_validate() { echo 'heavy XML validator was called' >&2; return 99; }

. "$ROOT/common/font_validate_fast_v4.sh"
ziyu_payload_validate_current mix
[ "$ZIYU_PAYLOAD_VALIDATED_ACTIVE" = mix ]

# Quarantine removes generated payload but keeps selection instead of relabeling it default.
printf 'mix\n' > "$MOD/config/active_font.conf"
ziyu_payload_quarantine
[ "$(cat "$MOD/config/active_font.conf")" = mix ]
grep -q '^selectedFont=mix$' "$MOD/config/font-payload-quarantine.conf"
grep -q '^effectiveFont=stock$' "$MOD/config/font-payload-quarantine.conf"

echo 'v4 fast validate test: ok'
