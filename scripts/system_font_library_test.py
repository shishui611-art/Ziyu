#!/usr/bin/env python3
"""Exercise safe stock export, TTC faces, axes and source preservation."""
from pathlib import Path
import hashlib
import os
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTCollection, TTFont
import system_font_library as library


def make_font(path, family, codepoints, weight=400, variable=False):
    fb = FontBuilder(1000, isTTF=True)
    cmap = {cp: f'u{cp:04X}' for cp in sorted(codepoints)}
    glyphs = ['.notdef', *cmap.values()]
    fb.setupGlyphOrder(glyphs)
    fb.setupCharacterMap(cmap)
    outlines = {}
    for name in glyphs:
        pen = TTGlyphPen(None)
        pen.moveTo((80, 0)); pen.lineTo((480, 0)); pen.lineTo((480, 700)); pen.lineTo((80, 700)); pen.closePath()
        outlines[name] = pen.glyph()
    fb.setupGlyf(outlines)
    fb.setupHorizontalMetrics({name: (600, 80) for name in glyphs})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({'familyName': family, 'styleName': 'Regular' if weight == 400 else 'Bold',
                       'fullName': family + (' Regular' if weight == 400 else ' Bold'),
                       'psName': family.replace(' ', '') + str(weight), 'version': 'Version 1.0'})
    fb.setupOS2(usWeightClass=weight, sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    fb.setupPost(); fb.setupMaxp()
    if variable:
        fb.setupFvar([('wght', 100, 400, 900, 'Weight')], [])
    fb.save(path)


class SystemFontLibraryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / 'module'
        (self.module / 'config').mkdir(parents=True)
        (self.module / 'config/active_font.conf').write_text('custom\n')
        (self.module / 'config/device_font_inventory.json').write_text('{"mainSlotPath":"/system/fonts/Roboto.ttf"}')
        payload = self.module / 'system/fonts'
        payload.mkdir(parents=True)
        (payload / 'Roboto.ttf').write_bytes(b'active custom overlay must never be imported')
        self.stock = self.root / 'state/lower/system-fonts'
        self.stock.mkdir(parents=True)
        self.out = self.root / 'library'
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {'LUOSHU_SELF_MOUNT_STATE_ROOT': str(self.root / 'state'), 'LUOSHU_STOCK_VIEW_VERIFIED': '0'}).start()
        patch.object(library.stock, '_logical_font_roots', return_value=[('system', Path('/system/fonts'))]).start()

    def fonts(self, variable=False):
        all_chars = library.CJK | library.LATIN | library.DIGITS
        make_font(self.stock / 'NotoSC-Regular.ttf', 'Noto Sans SC', all_chars, variable=variable)
        if not variable:
            make_font(self.stock / 'NotoSC-Bold.ttf', 'Noto Sans SC', all_chars, weight=700)
        make_font(self.stock / 'Roboto.ttf', 'Roboto', library.LATIN | library.DIGITS)
        make_font(self.stock / 'Clock-solid-digits.ttf', 'Clock', library.DIGITS)
        (self.stock / 'Broken.ttf').write_bytes(b'broken font')

    def test_lower_stock_multiweight_roles_and_repeat_import(self):
        self.fonts()
        original = {p.name: hashlib.sha256(p.read_bytes()).digest() for p in self.stock.iterdir()}
        report = library.import_system_fonts(self.module, self.out)
        self.assertEqual(report['status'], 'ok')
        self.assertEqual(len(report['data']['fonts']), 3)
        self.assertTrue((self.out / 'SystemDefaultCjk-Bold.ttf').is_file())
        with TTFont(self.out / 'SystemDefaultLatin-Regular.ttf') as f:
            self.assertEqual(f['name'].getBestFamilyName(), 'Roboto')
        with TTFont(self.out / 'SystemDefaultDigit-Regular.ttf') as f:
            self.assertEqual(f['name'].getBestFamilyName(), 'Clock')
        self.assertEqual(original, {p.name: hashlib.sha256(p.read_bytes()).digest() for p in self.stock.iterdir()})
        (self.stock / 'NotoSC-Bold.ttf').unlink()
        library.import_system_fonts(self.module, self.out)
        self.assertFalse((self.out / 'SystemDefaultCjk-Bold.ttf').exists())
        self.assertIn('name=系统默认 · 中文', (self.out / 'SystemDefaultCjk.conf').read_text())

    def test_variable_axis_preserved(self):
        self.fonts(variable=True)
        library.import_system_fonts(self.module, self.out)
        with TTFont(self.out / 'SystemDefaultCjk-Variable.ttf') as f:
            self.assertEqual([(a.axisTag, a.minValue, a.defaultValue, a.maxValue) for a in f['fvar'].axes], [('wght', 100, 400, 900)])
        common = Path(library.__file__).parent
        for helper in (common / 'util_functions_core.sh', common / 'legacy_v14_4/util_functions.sh'):
            result = subprocess.check_output(['sh', '-c', '. "$1"; detect_font_family SystemDefaultCjk-Variable.ttf; detect_font_weight SystemDefaultCjk-Variable.ttf', 'test', str(helper)], text=True)
            self.assertEqual(result.splitlines(), ['SystemDefaultCjk', 'variable'])

    def test_collection_selects_simplified_chinese_face(self):
        self.fonts()
        regular = self.stock / 'NotoSC-Regular.ttf'
        make_font(self.stock / 'Japanese.ttf', 'Noto Sans CJK JP', library.CJK | library.LATIN | library.DIGITS)
        collection = TTCollection()
        collection.fonts = [TTFont(self.stock / 'Japanese.ttf'), TTFont(regular)]
        collection.save(self.stock / 'NotoSansCJK.ttc')
        collection.close()
        regular.unlink(); (self.stock / 'NotoSC-Bold.ttf').unlink(); (self.stock / 'Japanese.ttf').unlink()
        library.import_system_fonts(self.module, self.out)
        with TTFont(self.out / 'SystemDefaultCjk-Regular.ttf') as f:
            self.assertEqual(f['name'].getBestFamilyName(), 'Noto Sans SC')
        self.assertEqual((self.out / 'SystemDefaultCjk-Regular.ttf').read_bytes()[:4], b'\x00\x01\x00\x00')

    def test_missing_cjk_does_not_modify_existing_library(self):
        make_font(self.stock / 'Roboto.ttf', 'Roboto', library.LATIN | library.DIGITS)
        self.out.mkdir(); (self.out / 'keep.txt').write_text('untouched')
        with self.assertRaisesRegex(ValueError, '中文'):
            library.import_system_fonts(self.module, self.out)
        self.assertEqual(list(self.out.iterdir()), [self.out / 'keep.txt'])


if __name__ == '__main__':
    unittest.main()
