#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MOD="$TMP/module"
mkdir -p "$MOD/common" "$MOD/config/font-config-source/system" "$MOD/config/universal-font-plans"
cp "$ROOT/common/minimal_xml_router.sh" "$MOD/common/"

cat > "$MOD/common/font_config_runtime.sh" <<'SH'
font_config_capture_original() {
    mkdir -p "$CONFIG_DIR/font-config-source/system"
    printf '<familyset/>\n' > "$CONFIG_DIR/font-config-source/system/fonts.xml"
    return 0
}
SH
cat > "$MOD/common/font_config_partitions.sh" <<'SH'
:
SH
cat > "$MOD/common/universal_font_plan.sh" <<'SH'
#!/bin/sh
PLAN="$CONFIG_DIR/universal-font-plans/test.json"
case "$1" in
  build)
    mkdir -p "${PLAN%/*}"
    printf '{"schema":"universal-font-plan-v1","state":"planned"}\n' > "$PLAN"
    printf '{"status":"ok"}\n'
    ;;
  path)
    printf '%s\n' "$PLAN"
    ;;
  *)
    exit 2
    ;;
esac
SH
chmod 0755 "$MOD/common/universal_font_plan.sh"

cat > "$MOD/common/minimal_xml_router.py" <<'PY'
#!/usr/bin/env python3
import argparse, json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--font-plan", required=True)
p.add_argument("--snapshot-root")
p.add_argument("--output")
p.add_argument("--validate")
a = p.parse_args()
assert Path(a.font_plan).is_file()
if a.validate:
    assert Path(a.validate).is_file()
    print('{"status":"ok","schema":"minimal-xml-route-plan-v1"}')
else:
    assert a.snapshot_root and Path(a.snapshot_root).is_dir()
    out = Path(a.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "schema": "minimal-xml-route-plan-v1",
        "state": "planned",
        "mutatesSystem": False,
    }), encoding="utf-8")
    print('{"status":"ok","schema":"minimal-xml-route-plan-v1"}')
PY
chmod 0755 "$MOD/common/minimal_xml_router.py"

export MODDIR="$MOD"
export MODULE_DIR="$MOD"
export CONFIG_DIR="$MOD/config"
export ZIYU_PYTHON=python3

BUILD=$(sh "$MOD/common/minimal_xml_router.sh" build DemoFamily)
printf '%s\n' "$BUILD" | grep -q '"status":"ok"'

PLAN=$(sh "$MOD/common/minimal_xml_router.sh" path DemoFamily)
test -s "$PLAN"
test -s "$MOD/config/font-config-source/system/fonts.xml"

VALID=$(sh "$MOD/common/minimal_xml_router.sh" validate DemoFamily)
printf '%s\n' "$VALID" | grep -q '"status":"ok"'

echo "minimal_xml_router_bridge_test: PASS"
