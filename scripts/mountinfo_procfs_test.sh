#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# Route verification now reads mountinfo through font_route_verify.py. Model a
# readable procfs file whose stat size is zero and exercise that exact parser.
python3 - "$ROOT/common" <<'PY'
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, sys.argv[1])
from font_route_verify import mount_points

mountinfo = (
    "1 0 0:1 / /system ro - ext4 /dev/system ro\n"
    "2 0 0:2 / /system/fonts rw - tmpfs tmpfs rw\n"
    "3 0 0:3 / /data/fonts\\040private rw - tmpfs tmpfs rw\n"
)
source = Path("/proc/1/mountinfo")
with (
    patch.object(Path, "stat", return_value=SimpleNamespace(st_size=0)),
    patch.object(Path, "is_file", return_value=True),
    patch.object(Path, "read_text", return_value=mountinfo),
):
    assert source.stat().st_size == 0
    points = mount_points(source)

expected = {"/system", "/system/fonts", "/data/fonts private"}
if points != expected:
    raise SystemExit(f"FAIL: parsed mount points differ: {points!r}")
print("PASS: readable zero-stat-size procfs mountinfo is parsed by the active route verifier")
PY
