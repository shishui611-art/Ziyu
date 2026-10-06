#!/usr/bin/env python3
"""Exercise three-role variable-font mixing without the optional native lxml module."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "common"))

# Match the embedded Android runtime even on CI hosts that already have lxml.
sys.modules["lxml"] = None
sys.modules["lxml.etree"] = None
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables.S_V_G_ import SVGDocument
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from composite_font import build
from font_instance import materialize


def fixture(path: Path, width: int, *, svg: bool) -> None:
    chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789中永国"
    cmap = {ord(char): f"u{ord(char):04X}" for char in chars}
    order = [".notdef", *cmap.values()]
    glyphs = {}
    variations = {}
    for name in order:
        pen = TTGlyphPen(None)
        pen.moveTo((50, 0)); pen.lineTo((50 + width, 0))
        pen.lineTo((50 + width, 700)); pen.lineTo((50, 700)); pen.closePath()
        glyphs[name] = pen.glyph()
        variations[name] = [
            TupleVariation({"wght": (0, 1, 1)}, [(0, 0), (120, 0), (120, 0), (0, 0)] + [(0, 0)] * 4),
            TupleVariation({"wght": (-1, -1, 0)}, [(0, 0), (-60, 0), (-60, 0), (0, 0)] + [(0, 0)] * 4),
        ]
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder(order)
    builder.setupCharacterMap(cmap)
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (1000, 50) for name in order})
    builder.setupHorizontalHeader(ascent=900, descent=-250)
    builder.setupOS2(sTypoAscender=900, sTypoDescender=-250,
                    usWinAscent=900, usWinDescent=250)
    # Retained name metadata keeps the tiny digit-only fixture above the production
    # size gate, as real fonts with hinting and layout tables already are.
    builder.setupNameTable({"familyName": "SvgVariableFixture", "styleName": "Regular",
                           "copyright": "Synthetic font fixture. " * 220})
    builder.setupPost(); builder.setupMaxp()
    builder.setupFvar([("wght", 100, 400, 900, "Weight")], [])
    builder.setupGvar(variations)
    if svg:
        table = newTable("SVG ")
        documents = []
        for char in ("A", "1"):
            gid = order.index(cmap[ord(char)])
            document = (f'<svg xmlns="http://www.w3.org/2000/svg">'
                        f'<g id="glyph{gid}"><path fill="red" '
                        f'd="M50 0 H{50 + width} V700 H50 Z"/></g></svg>')
            documents.append(SVGDocument(document, gid, gid))
        table.docList = sorted(documents, key=lambda item: item.startGlyphID)
        builder.font["SVG "] = table
    builder.save(path)


def bounds(font: TTFont, char: str, **kwargs):
    glyphs = font.getGlyphSet(**kwargs)
    pen = BoundsPen(glyphs)
    glyphs[font.getBestCmap()[ord(char)]].draw(pen)
    return pen.bounds


class SvgInstanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cjk = self.root / "cjk.ttf"
        self.latin = self.root / "latin-svg.ttf"
        self.digit = self.root / "digit-svg.ttf"
        fixture(self.cjk, 700, svg=False)
        fixture(self.latin, 500, svg=True)
        fixture(self.digit, 300, svg=True)
        no_xml = patch("fontTools.subset.svg.etree", None)
        no_xml.start(); self.addCleanup(no_xml.stop)

    def test_outline_subset_keeps_real_weight_shapes_without_lxml(self):
        for role, source, char in (("latin", self.latin, "A"), ("digit", self.digit, "1")):
            before = source.read_bytes()
            widths = []
            for weight in range(100, 901, 100):
                with self.subTest(role=role, weight=weight):
                    output = self.root / f"{role}-{weight}.ttf"
                    report = materialize(source, output, role, weight, {"wght": weight},
                                         preserve_metrics=True)
                    self.assertEqual(report["status"], "ok")
                    with TTFont(source) as original, TTFont(output) as instance:
                        self.assertIn("SVG ", original)
                        self.assertNotIn("SVG ", instance)
                        self.assertNotIn("fvar", instance)
                        self.assertNotIn("gvar", instance)
                        self.assertEqual(instance["OS/2"].usWeightClass, weight)
                        self.assertEqual(bounds(instance, char),
                                         bounds(original, char, location={"wght": weight}))
                        self.assertIsNotNone(bounds(instance, char))
                        self.assertNotIn(ord("中"), instance.getBestCmap())
                        widths.append(bounds(instance, char)[2])
            self.assertLess(widths[0], widths[3])
            self.assertLess(widths[3], widths[-1])
            self.assertEqual(source.read_bytes(), before, "imported font was modified")

    def test_all_nine_weights_build_from_three_separate_sources(self):
        self.check_three_source_builds(materialize, build)

    def test_app_router_runtime_combines_all_weights_without_lxml(self):
        module = self.root / "module"
        common = module / "common"
        legacy = common / "legacy_v14_4"
        legacy.mkdir(parents=True)
        for source in (ROOT / "common").iterdir():
            if source.name != "legacy_v14_4":
                (common / source.name).symlink_to(source, target_is_directory=source.is_dir())
        for source in (ROOT / "common/legacy_v14_4").iterdir():
            if source.name != "v14_mix.sh":
                (legacy / source.name).symlink_to(source)
        # Keep the real App controller, router and runtime setup. Stub only the
        # downstream Android worker so recovery cannot access phone resources.
        (legacy / "v14_mix.sh").write_text("#!/bin/sh\nprintf '{\"status\":\"ok\"}\\n'\n")
        (module / "module.prop").write_text("id=LuoShu\nversion=test\n")
        result = subprocess.run(
            [shutil.which("sh") or r"C:\Program Files\Git\bin\sh.exe", str(common / "font_mix_controller.sh"), "recover"],
            env={**os.environ, "MODDIR": module.as_posix(),
                 "LUOSHU_PUBLIC_DIR": (self.root / "public").as_posix()},
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["status"], "ok")
        runtime = module / ".legacy-v14-runtime/common"
        for name in ("font_instance.py", "composite_font.py", "composite_layout.py"):
            self.assertEqual((runtime / name).resolve(),
                             (ROOT / "common/legacy_v14_4" / name).resolve())

        def load(name):
            spec = importlib.util.spec_from_file_location(f"svg_runtime_{name}", runtime / f"{name}.py")
            loaded = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(loaded)
            return loaded

        runtime_instance = load("font_instance")
        runtime_layout = load("composite_layout")
        previous_layout = sys.modules.get("composite_layout")
        sys.modules["composite_layout"] = runtime_layout
        try:
            runtime_composite = load("composite_font")
            self.check_three_source_builds(runtime_instance.materialize, runtime_composite.build)
        finally:
            if previous_layout is None:
                sys.modules.pop("composite_layout", None)
            else:
                sys.modules["composite_layout"] = previous_layout

    def check_three_source_builds(self, materializer, builder):
        before = {path: path.read_bytes() for path in (self.cjk, self.latin, self.digit)}
        for weight in range(100, 901, 100):
            with self.subTest(weight=weight):
                sources = {}
                for role, source in (("cjk", self.cjk), ("latin", self.latin), ("digit", self.digit)):
                    output = self.root / f"prepared-{role}-{weight}.ttf"
                    materializer(source, output, role, weight, {"wght": weight})
                    sources[role] = str(output)
                composite = self.root / f"composite-{weight}.ttf"
                report = builder(SimpleNamespace(**sources, output=str(composite), weight=weight,
                                               cjk_face=None, latin_face=None, digit_face=None,
                                               progress=None))
                self.assertGreaterEqual(report["replaced"]["latin"], 52)
                self.assertGreaterEqual(report["replaced"]["digit"], 10)
                with TTFont(composite) as font:
                    for char in "中Aa019":
                        self.assertIsNotNone(bounds(font, char))
                    self.assertNotEqual(bounds(font, "A"), bounds(font, "1"))
        for path, contents in before.items():
            self.assertEqual(path.read_bytes(), contents)


if __name__ == "__main__":
    unittest.main(verbosity=2)
