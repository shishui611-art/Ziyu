#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DETECT_SHELL="${LUOSHU_TEST_SHELL:-bash}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
ADB="$TMP/adb"
HYBRID="$ADB/modules/hybrid_mount"
MOD="$ADB/modules/LuoShu"
CONFIG="$ADB/hybrid-mount/config.toml"
mkdir -p "$HYBRID" "$MOD/config" "$(dirname "$CONFIG")"
printf 'id=hybrid_mount\nname=Hybrid Mount\nmetamodule=1\n' > "$HYBRID/module.prop"
printf 'id=LuoShu\n' > "$MOD/module.prop"

write_api() {
  cat > "$HYBRID/hybrid-mount" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CALL_LOG"
[ "$*" = 'runtime status' ] || exit 2
cat "$API_STATUS"
SH
  chmod +x "$HYBRID/hybrid-mount"
}
write_api
export CALL_LOG="$TMP/calls" API_STATUS="$TMP/status"
printf '{"supported":false,"reason":"boot ledger not ready","modules":[]}\n' > "$API_STATUS"
FAILURES=0
CHECKS=0
detect() {
  LUOSHU_META_DETECT_ROOT="$ADB" LUOSHU_META_TEST_ACTIVE_DIR="$HYBRID" \
  LUOSHU_META_TEST_ASSUME_ACTIVE="${ASSUME_ACTIVE:-1}" MODDIR="$MOD" \
  "$DETECT_SHELL" -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; if [ "${2:-}" = route ]; then printf "%s|%s\n" "$META_USABLE_REASON" "$META_HYBRID_DEFAULT_MODE"; else printf "%s|%s|%s\n" "$META_READY" "$META_USABLE" "$META_USABLE_REASON"; fi' _ "$ROOT" "${1:-summary}"
}
check() {
  local label="$1" expected="$2" actual
  actual=$(detect "${3:-summary}")
  CHECKS=$((CHECKS + 1))
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL: %s (expected %s, got %s)\n' "$label" "$expected" "$actual" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# Upstream defaults to Overlay, and its runtime cannot unload real mounts by module.
printf 'default_mode="overlay"\n' > "$CONFIG"
check 'official Overlay default degrades to Meta without scoped unload' '1|1|hybrid-overlay-route'
check 'Overlay diagnostics keep a separate nonempty reason and mode' 'hybrid-overlay-route|overlay' route
printf '# no default_mode: upstream uses Overlay\n[rules.other]\ndefault_mode="vfs"\n' > "$CONFIG"
check 'another module default must not become the global default' '1|1|hybrid-overlay-route'
printf 'default_mode="overlay"\n[rules."LuoShu"]\ndefault_mode="vfs"\n' > "$CONFIG"
check 'module VFS overrides global Overlay with quoted official schema' '1|1|hybrid-pure-vfs-scoped-unload'
printf '[rules.luoshu]\ndefault_mode="vfs"\n' > "$CONFIG"
check 'module IDs are case sensitive upstream' '1|1|hybrid-overlay-route'
printf 'default_mode="vfs"\n[rules.LuoShu.paths]\n"system/etc/hosts"="ignore"\n' > "$CONFIG"
check 'ignore paths do not introduce real mounts' '1|1|hybrid-pure-vfs-scoped-unload'
printf 'default_mode="vfs"\n[rules.LuoShu.paths]\n"system/fonts/Latin.ttf"="magic"\n' > "$CONFIG"
check 'quoted Magic path degrades to Meta without scoped unload' '1|1|hybrid-overlay-route'
printf 'default_mode="vfs"\n[rules.LuoShu.paths]\nsystem="overlay"\n' > "$CONFIG"
check 'bare TOML path keys also degrade to Meta' '1|1|hybrid-overlay-route'
printf 'default_mode="vfs"\n[rules.LuoShu]\npaths={system="magic"}\n' > "$CONFIG"
check 'unparsed inline paths cannot silently pass as pure VFS' '0|0|hybrid-config-unverified'
printf 'default_mode="vfs"\n[rules.LuoShu]\ndefault_mode="ignore"\n' > "$CONFIG"
check 'ignored module has a distinct exclusion reason' '0|0|hybrid-module-excluded'
rm -f "$CONFIG"
check 'missing config is distinguished from a real mount route' '0|0|hybrid-config-missing'

# Retain supported legacy colocated configs, including a custom active module directory.
CUSTOM="$ADB/modules/custom_hybrid"
mkdir -p "$CUSTOM"
cp "$HYBRID/module.prop" "$HYBRID/hybrid-mount" "$CUSTOM/"
chmod +x "$CUSTOM/hybrid-mount"
printf 'default_mode="vfs"\n' > "$CUSTOM/config.toml"
ORIGINAL="$HYBRID"
HYBRID="$CUSTOM"
check 'active module directory config is found without a hardcoded module ID' '1|1|hybrid-pure-vfs-scoped-unload'
HYBRID="$ORIGINAL"
printf 'default_mode="vfs"\n' > "$HYBRID/config.toml"
printf 'default_mode="overlay"\n' > "$CONFIG"
check 'persistent config takes precedence over a legacy colocated file' '1|1|hybrid-overlay-route'
rm -f "$HYBRID/config.toml"

printf 'default_mode="vfs"\n' > "$CONFIG"
: > "$MOD/skip_mount"
check 'foreign skip marker excludes the font module' '0|0|hybrid-module-excluded'
: > "$MOD/config/self-mount-owned"
check 'owned skip marker remains eligible for the existing transition' '1|1|hybrid-pure-vfs-scoped-unload'
rm -f "$MOD/skip_mount" "$MOD/config/self-mount-owned"
: > "$MOD/remove"
check 'font module pending removal is excluded' '0|0|hybrid-module-excluded'
rm -f "$MOD/remove"

printf 'blacklist = [\n "other",\n "LuoShu",\n]\n' > "$ADB/hybrid-mount/module_blacklist.toml"
check 'persistent upstream blacklist excludes the module' '0|0|hybrid-module-excluded'
rm -f "$ADB/hybrid-mount/module_blacklist.toml"
printf 'blacklist=["LuoShu"]\n' > "$HYBRID/module_blacklist.toml"
check 'bundled upstream blacklist also excludes the module' '0|0|hybrid-module-excluded'
rm -f "$HYBRID/module_blacklist.toml"

printf '#!/usr/bin/env bash\nexit 2\n' > "$HYBRID/hybrid-mount"
check 'old executable without a runtime API degrades to Meta' '1|1|hybrid-runtime-api-unavailable'
write_api
printf '{"supported":true,"modules":[]}\n' > "$API_STATUS"
check 'valid read-only runtime API is recognized' '1|1|hybrid-pure-vfs-scoped-unload'
printf '{"supported":true}\n' > "$API_STATUS"
check 'incomplete runtime status degrades to Meta without unload' '1|1|hybrid-runtime-api-unavailable'
rm -f "$HYBRID/hybrid-mount"
check 'missing executable has its own runtime reason' '0|0|hybrid-runtime-binary-unavailable'
write_api
ASSUME_ACTIVE=0
check 'enabled install without active selector is distinguished' '0|0|hybrid-not-active'
ASSUME_ACTIVE=1

# magic-mount failures must each name the exact blocked precondition.
MM="$ADB/modules/magic_mount_meta"
mkdir -p "$MM"
printf 'id=magic-mount\nname=Magic Mount Meta\nmetamodule=1\n' > "$MM/module.prop"
cat > "$MM/meta-mm" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$MM/meta-mm"
detect_mm() {
  LUOSHU_META_DETECT_ROOT="$ADB" LUOSHU_META_TEST_ACTIVE_DIR="$MM" \
  LUOSHU_META_TEST_ASSUME_ACTIVE="${ASSUME_ACTIVE:-1}" MODDIR="$MOD" \
  "$DETECT_SHELL" -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s\n" "$META_READY" "$META_USABLE" "$META_USABLE_REASON"' _ "$ROOT"
}
check_mm() {
  local label="$1" expected="$2" actual
  actual=$(detect_mm)
  CHECKS=$((CHECKS + 1))
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL: %s (expected %s, got %s)\n' "$label" "$expected" "$actual" >&2
    FAILURES=$((FAILURES + 1))
  fi
}
ASSUME_ACTIVE=0
check_mm 'magic-mount without an active selector names it' '0|0|magic-mount-not-active'
ASSUME_ACTIVE=1
check_mm 'magic-mount passes when every precondition holds' '1|1|magic-mount-has-no-module-scoped-unload'
printf 'id=magic-mount\nname=Magic Mount Meta\n' > "$MM/module.prop"
check_mm 'magic-mount missing the metamodule property names it' '0|0|magic-mount-not-metamodule'
printf 'id=magic-mount\nname=Magic Mount Meta\nmetamodule=1\n' > "$MM/module.prop"
mv "$MM/meta-mm" "$MM/meta-mm.bak"
check_mm 'magic-mount without a runner binary names it' '0|0|magic-mount-runner-unavailable'
mv "$MM/meta-mm.bak" "$MM/meta-mm"
: > "$MOD/skip_mount"
check_mm 'foreign skip marker excludes magic-mount by name' '0|0|magic-mount-module-excluded'
: > "$MOD/config/self-mount-owned"
check_mm 'owned skip marker keeps magic-mount eligible' '1|1|magic-mount-has-no-module-scoped-unload'
rm -f "$MOD/skip_mount" "$MOD/config/self-mount-owned"

# NoMount (kernel VFS injection metamodule): detected and reported, but never
# selected as a backend until rule-based integration exists.
NM="$ADB/modules/nomount_meta"
mkdir -p "$NM"
printf 'id=nomount\nname=NoMount Metamodule\nmetamodule=1\n' > "$NM/module.prop"
cat > "$NM/nm" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$NM/nm"
detect_nm() {
  LUOSHU_META_DETECT_ROOT="$ADB" LUOSHU_META_TEST_ACTIVE_DIR="$NM" \
  LUOSHU_META_TEST_ASSUME_ACTIVE="${ASSUME_ACTIVE:-1}" MODDIR="$MOD" \
  "$DETECT_SHELL" -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s\n" "$META_READY" "$META_USABLE" "$META_USABLE_REASON"' _ "$ROOT"
}
check_nm() {
  local label="$1" expected="$2" actual
  actual=$(detect_nm)
  CHECKS=$((CHECKS + 1))
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL: %s (expected %s, got %s)\n' "$label" "$expected" "$actual" >&2
    FAILURES=$((FAILURES + 1))
  fi
}
ASSUME_ACTIVE=0
check_nm 'nomount without an active selector names it' '0|0|nomount-not-active'
ASSUME_ACTIVE=1
mv "$NM/nm" "$NM/nm.bak"
check_nm 'nomount without the nm CLI names it' '0|0|nomount-cli-unavailable'
mv "$NM/nm.bak" "$NM/nm"
check_nm 'nomount is recognized but not yet integrated' '1|0|nomount-not-integrated'
: > "$MOD/skip_mount"
check_nm 'foreign skip marker excludes nomount by name' '0|0|nomount-module-excluded'
rm -f "$MOD/skip_mount"

# mountify failures must also be distinguishable.
MF="$ADB/modules/mountify_meta"
mkdir -p "$MF"
printf 'id=mountify\nname=Mountify\nmetamodule=1\n' > "$MF/module.prop"
LUOSHU_META_TEST_ACTIVE_DIR="$MF" LUOSHU_META_TEST_ASSUME_ACTIVE=1 MODDIR="$MOD" \
"$DETECT_SHELL" -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s\n" "$META_READY" "$META_USABLE" "$META_USABLE_REASON"' _ "$ROOT" > "$TMP/mm-out"
eq_line='0|0|mountify-runtime-unavailable'
actual_line=$(cat "$TMP/mm-out")
if [ "$actual_line" != "$eq_line" ]; then
  printf 'FAIL: mountify without runners names it (expected %s, got %s)\n' "$eq_line" "$actual_line" >&2
  FAILURES=$((FAILURES + 1))
fi
CHECKS=$((CHECKS + 1))
cat > "$MF/post-fs-data.sh" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$MF/post-fs-data.sh"
: > "$MOD/skip_mount"
LUOSHU_META_TEST_ACTIVE_DIR="$MF" LUOSHU_META_TEST_ASSUME_ACTIVE=1 MODDIR="$MOD" \
"$DETECT_SHELL" -c '. "$1/common/meta_mount_detection.sh"; luoshu_meta_mount_detect >/dev/null; printf "%s|%s|%s\n" "$META_READY" "$META_USABLE" "$META_USABLE_REASON"' _ "$ROOT" > "$TMP/mf-out"
eq_line='0|0|mountify-module-excluded'
actual_line=$(cat "$TMP/mf-out")
if [ "$actual_line" != "$eq_line" ]; then
  printf 'FAIL: mountify with a foreign skip marker names it (expected %s, got %s)\n' "$eq_line" "$actual_line" >&2
  FAILURES=$((FAILURES + 1))
fi
CHECKS=$((CHECKS + 1))
rm -f "$MOD/skip_mount"

if grep -Eq '^runtime (unload|load|reload)|^vfs ' "$CALL_LOG"; then
  printf 'FAIL: read-only detection mutated the mount engine\n' >&2
  FAILURES=$((FAILURES + 1))
fi
[ "$FAILURES" = 0 ] || { printf '%s/%s Hybrid detection checks failed\n' "$FAILURES" "$CHECKS" >&2; exit 1; }
printf 'PASS: %s Hybrid detection checks; only read-only runtime status was called\n' "$CHECKS"
