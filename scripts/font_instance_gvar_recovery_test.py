#!/usr/bin/env python3
"""Keep damaged glyphs readable while healthy glyphs retain real weight variation."""
import importlib.util
import argparse
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "common"))
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.append(str(ROOT / "common/legacy_v14_4"))
from fontTools.ttLib import TTFont
from font_instance_svg_test import fixture, bounds


def load(relative):
    spec = importlib.util.spec_from_file_location("recovery_" + relative.replace("/", "_"), ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class GvarRecoveryTest(unittest.TestCase):
    def test_unusable_original_outline_stops_instead_of_hiding_character(self):
        from fontTools.pens.ttGlyphPen import TTGlyphPen
        for relative in ("common/font_instance.py", "common/legacy_v14_4/font_instance.py"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temporary:
                source, output = Path(temporary) / "source.ttf", Path(temporary) / "instance.ttf"
                fixture(source, 700, svg=False)
                engine = load(relative)
                def open_font(*args, **kwargs):
                    font = TTFont(*args, **kwargs)
                    name = font.getBestCmap()[ord("中")]
                    font["glyf"][name] = TTGlyphPen(None).glyph()
                    def broken(_name):
                        raise AssertionError("invalid packed delta count")
                    font["gvar"].variations.data[name] = broken
                    return font
                with patch.object(engine, "TTFont", open_font), self.assertRaisesRegex(engine.InstanceError, "原始轮廓也不可用"):
                    engine.materialize(source, output, "cjk", 900, {"wght": 900})
                self.assertFalse(output.exists())

    def test_latin_and_digit_subset_recovery_keeps_selected_character(self):
        for relative in ("common/font_instance.py", "common/legacy_v14_4/font_instance.py"):
            for role, char, healthy in (("latin", "A", "B"), ("digit", "1", "2")):
                with self.subTest(relative=relative, role=role), tempfile.TemporaryDirectory() as temporary:
                    source, output = Path(temporary) / "source.ttf", Path(temporary) / "instance.ttf"
                    fixture(source, 700, svg=False)
                    engine = load(relative)
                    def open_font(*args, **kwargs):
                        font = TTFont(*args, **kwargs)
                        def broken(_name):
                            raise AssertionError("invalid packed delta count")
                        font["gvar"].variations.data[font.getBestCmap()[ord(char)]] = broken
                        return font
                    with patch.object(engine, "TTFont", open_font):
                        report = engine.materialize(source, output, role, 900, {"wght": 900})
                    self.assertIn(f"{char}在 900 字重无法处理", report["warning"])
                    with TTFont(source) as original, TTFont(output) as instance:
                        self.assertEqual(bounds(instance, char), bounds(original, char))
                        self.assertEqual(bounds(instance, healthy), bounds(original, healthy, location={"wght": 900}))

    def test_composite_failure_reports_all_three_inputs(self):
        for relative in ("common/composite_font.py", "common/legacy_v14_4/composite_font.py"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temporary:
                source = Path(temporary) / "source.ttf"
                source.write_bytes(b"input fingerprint fixture")
                engine = load(relative)
                args = argparse.Namespace(cjk=str(source), latin=str(source), digit=str(source), output="output.ttf", weight=400)
                stderr = io.StringIO()
                with patch.object(engine, "parse_args", return_value=args), \
                     patch.object(engine, "build", side_effect=ValueError("outline copy failed")), contextlib.redirect_stderr(stderr):
                    self.assertEqual(engine.main(), 1)
                record = json.loads(stderr.getvalue())
                self.assertEqual(record["stage"], "font-composite")
                self.assertEqual(record["errorCode"], "FONT_COMPOSITE_ERROR")
                self.assertEqual(set(record["inputs"]), {"cjk", "latin", "digit"})
                self.assertEqual(record["inputs"]["cjk"]["bytes"], source.stat().st_size)
                self.assertIn("outline copy failed", record["traceback"])

    def test_error_record_keeps_task_weight_and_original_stack(self):
        for relative in ("common/font_instance.py", "common/legacy_v14_4/font_instance.py"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temporary:
                source = Path(temporary) / "字体.ttf"
                source.write_bytes(b"font fixture for fingerprint")
                engine = load(relative)
                args = argparse.Namespace(input=str(source), output="unused", role="cjk", weight=400, axes="wght=400", preserve_metrics=False)
                environment = {"LUOSHU_TASK_ID": "mix-repro-123", "LUOSHU_FONT_FAMILY": "测试字体",
                               "LUOSHU_GENERATED_WEIGHT": "100", "LUOSHU_WEIGHT_MODE": "fixed"}
                for error, code, exit_code in ((AssertionError("packed delta failure"), "FONT_VARIATION_ASSERTION", 13),
                                               (MemoryError("allocation failure"), "FONT_INSTANCE_MEMORY", 12)):
                    stderr = io.StringIO()
                    with patch.object(engine, "parse_args", return_value=args), patch.object(engine, "materialize", side_effect=error), \
                         patch.dict(os.environ, environment), contextlib.redirect_stderr(stderr):
                        self.assertEqual(engine.main(), exit_code)
                    record = json.loads(stderr.getvalue())
                    self.assertEqual(record["errorCode"], code)
                    self.assertEqual(record["exitCode"], exit_code)
                    self.assertEqual(record["taskId"], "mix-repro-123")
                    self.assertEqual(record["generatedWeight"], "100")
                    self.assertEqual(record["effectiveWeight"], 400)
                    self.assertEqual(record["axes"], "wght=400")
                    self.assertEqual(record["requestedFamily"], "测试字体")
                    self.assertEqual(record["source"]["sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
                    self.assertIn(type(error).__name__, record["traceback"])
                    self.assertIn("materialize", record["traceback"])
                    self.assertTrue(record["fontToolsVersion"])
                    self.assertEqual(len(record["scriptSha256"]), 64)

    def test_one_broken_glyph_keeps_outline_other_glyphs_get_selected_weight(self):
        for relative in ("common/font_instance.py", "common/legacy_v14_4/font_instance.py"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source, output = root / "source.ttf", root / "instance.ttf"
                fixture(source, 700, svg=False)
                before = source.read_bytes()
                engine = load(relative)
                def open_font(*args, **kwargs):
                    font = TTFont(*args, **kwargs)
                    def broken(_name):
                        raise AssertionError("invalid packed delta count")
                    font["gvar"].variations.data[font.getBestCmap()[ord("中")]] = broken
                    return font
                with patch.object(engine, "TTFont", open_font):
                    report = engine.materialize(source, output, "cjk", 900, {"wght": 900})
                self.assertEqual(source.read_bytes(), before)
                self.assertEqual(report["variationFallbacks"][0]["characters"], "中")
                self.assertEqual(report["variationFallbacks"][0]["weight"], 900)
                self.assertIn("中在 900 字重无法处理，已保留原始轮廓", report["warning"])
                self.assertIn("invalid packed delta count", report["variationFallbacks"][0]["traceback"])
                with TTFont(source) as original, TTFont(output) as instance:
                    self.assertEqual(set(original.getBestCmap()), set(instance.getBestCmap()))
                    self.assertNotIn("fvar", instance)
                    self.assertEqual(bounds(instance, "中"), bounds(original, "中"))
                    self.assertEqual(bounds(instance, "永"), bounds(original, "永", location={"wght": 900}))
                    self.assertNotEqual(bounds(instance, "永"), bounds(original, "永"))

    def test_widespread_damage_stops_instead_of_flattening_font(self):
        for relative in ("common/font_instance.py", "common/legacy_v14_4/font_instance.py"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temporary:
                source, output = Path(temporary) / "source.ttf", Path(temporary) / "instance.ttf"
                fixture(source, 700, svg=False)
                engine = load(relative)
                def open_font(*args, **kwargs):
                    font = TTFont(*args, **kwargs)
                    def broken(_name):
                        raise AssertionError("invalid packed delta count")
                    for char in "中永":
                        font["gvar"].variations.data[font.getBestCmap()[ord(char)]] = broken
                    return font
                with patch.object(engine, "TTFont", open_font):
                    with self.assertRaises(engine.InstanceError):
                        engine.materialize(source, output, "cjk", 900, {"wght": 900})
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
