#!/usr/bin/env python3
from __future__ import annotations

import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "common"))
from font_config_overlay import generated_references, rewrite_tree, validate_generated_references  # noqa: E402

xml = """<familyset>
  <family name="sans-serif"><font weight="400">Roboto-Regular.ttf</font></family>
  <family name="monospace"><font weight="400">RobotoMono-Regular.ttf</font></family>
  <family name="emoji"><font weight="400">NotoColorEmoji.ttf</font></family>
</familyset>"""
tree = ET.ElementTree(ET.fromstring(xml))
report = rewrite_tree(tree, "Ziyu", "ZiyuMono")
families = {family.attrib.get("name"): family.find("font").text for family in tree.getroot()}
assert families["sans-serif"] == "Ziyu-400.ttf"
assert families["monospace"] == "RobotoMono-Regular.ttf"
assert families["emoji"] == "NotoColorEmoji.ttf"
assert report["changed_mono_families"] == []
refs = generated_references(tree, ("Ziyu", "ZiyuMono"))
assert refs == ["Ziyu-400.ttf"]
with tempfile.TemporaryDirectory() as directory:
    font_dir = Path(directory)
    for filename in refs:
        (font_dir / filename).write_bytes(b"0" * 2048)
    assert validate_generated_references(tree, ("Ziyu", "ZiyuMono"), font_dir) == 1
print("UI overlay and stock monospace preservation passed.")
