"""Compact numeric SFNTs are admitted by structure and role, never byte count."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "common"))
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, TTCollection
from font_structure import validate
from font_role_check import check
from font_import_probe import inspect
from font_extract_faces import extract
from font_instance import materialize


def digit_fixture(path, *, empty=None):
    builder = FontBuilder(1000, isTTF=True)
    order = [".notdef", *"0123456789"]
    builder.setupGlyphOrder(order)
    builder.setupCharacterMap({ord(name): name for name in order[1:]})
    glyphs = {}
    for name in order:
        pen = TTGlyphPen(None)
        if name != empty:
            pen.moveTo((0, 0)); pen.lineTo((300, 0)); pen.lineTo((300, 700)); pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (500, 0) for name in order})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupNameTable({"familyName": "Digits", "styleName": "Regular"})
    builder.setupPost(); builder.setupMaxp(); builder.save(path)


class SmallDigitFontTest(unittest.TestCase):
    def test_structural_role_and_import_checks_accept_genuine_small_digits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "Digits-Regular.ttf", root / "instance.ttf"
            digit_fixture(source)
            self.assertLess(source.stat().st_size, 4096)
            self.assertEqual(source.stat().st_size, 908)
            validate(source)
            self.assertTrue(check(source, "digit")["valid"])
            self.assertFalse(check(source, "cjk")["valid"])
            self.assertFalse(check(source, "latin")["valid"])
            self.assertFalse(inspect(source)[-1])
            result = materialize(source, output, "digit", 400, {"wght": 400})
            self.assertEqual(result["status"], "ok")
            self.assertTrue(check(output, "digit")["valid"])
            collection = TTCollection()
            collection.fonts = [TTFont(source)]
            collection.save(root / "digits.ttc")
            collection.close()
            faces = extract(root / "digits.ttc", root / "faces", "digits")
            self.assertEqual(faces["status"], "ok")
            self.assertTrue(check(Path(faces["data"]["faces"][0]["path"]), "digit")["valid"])

    def test_empty_required_digit_and_truncated_tables_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "digits.ttf"
            digit_fixture(source, empty="7")
            self.assertIn("U+0037", check(source, "digit")["missing"])
            digit_fixture(source)
            source.write_bytes(source.read_bytes()[:-35])
            with self.assertRaises(Exception):
                validate(source)
            with self.assertRaises(Exception):
                check(source, "digit")

    def test_shell_check_uses_real_structure_for_compact_fonts(self):
        shell = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else "bash"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "digits.ttf"
            digit_fixture(source)
            env = {**os.environ, "MODDIR": ROOT.as_posix(), "LUOSHU_HOST_PYTHON": Path(sys.executable).as_posix()}
            for check_script in ("common/font_check.sh", "common/legacy_v14_4/font_check.sh"):
                result = subprocess.run([shell, (ROOT / check_script).as_posix(), "--json", source.as_posix()],
                                        env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn('"valid":true', result.stdout)
            source.write_bytes(b"\x00\x01\x00\x00" + b"cmapheadmaxp" * 30)
            result = subprocess.run([shell, (ROOT / "common/font_check.sh").as_posix(), "--json", source.as_posix()],
                                    env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('"valid":false', result.stdout)


if __name__ == "__main__":
    unittest.main()
