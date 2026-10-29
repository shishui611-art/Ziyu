"""Regression fixtures for encoded letters and unencoded Latin ligature slots."""
from io import BytesIO
from pathlib import Path
import sys
import unittest

from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
import composite_font
sys.path.append(str(Path(__file__).resolve().parents[1] / 'common/legacy_v14_4'))
from legacy_v14_4 import composite_font as legacy
from composite_collection_build import replace_face, protected
from font_ligatures import plan_latin_ligatures


def fixture(donor=False, ligature=True, extension=False):
    order = ['.notdef', 'f', 'i', 'l', 'ff', 'fi', 'ffi', 'fl', 'frame']
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({ord(c): c for c in 'fil'})
    outlines = {}
    for name in order:
        pen = TTGlyphPen(None)
        x = 80 if donor else 20
        width = 350 if donor else 200
        if name == 'frame':
            x, width = -100, 2300
        pen.moveTo((x, 0)); pen.lineTo((x + width, 0))
        pen.lineTo((x + width, 700)); pen.lineTo((x, 700)); pen.closePath()
        outlines[name] = pen.glyph()
    fb.setupGlyf(outlines)
    fb.setupHorizontalMetrics({name: (2500 if name == 'frame' else 500,
                                      -100 if name == 'frame' else 80 if donor else 20)
                               for name in order})
    fb.setupHorizontalHeader(ascent=900, descent=-200)
    fb.setupOS2(sTypoAscender=900, sTypoDescender=-200, usWinAscent=900, usWinDescent=200)
    fb.setupNameTable({'familyName': 'Fixture', 'styleName': 'Regular'})
    fb.setupPost(); fb.setupMaxp()
    if ligature:
        keyword = ' useExtension' if extension else ''
        addOpenTypeFeaturesFromString(fb.font, '''
            languagesystem DFLT dflt;
            lookup Latin%s { sub f i by fi; sub f l by fl; sub f f by ff; } Latin;
            lookup Nested { sub ff i by ffi; } Nested;
            feature liga { lookup Latin; lookup Nested; } liga;
        ''' % keyword)
    stream = BytesIO(); fb.font.save(stream); stream.seek(0)
    font = TTFont(stream, recalcTimestamp=False, recalcBBoxes=False)
    font['hhea'].minLeftSideBearing = font['hhea'].minRightSideBearing = -500
    font['hhea'].xMaxExtent = font['hhea'].advanceWidthMax = 3000
    return font


class LigatureTest(unittest.TestCase):
    def test_both_mix_engines_preserve_layout_and_use_donor_ligatures(self):
        for engine in (composite_font, legacy):
            with self.subTest(engine=engine.__name__):
                base, donor = fixture(), fixture(True)
                gsub, order = base.getTableData('GSUB'), base.getGlyphOrder()[:]
                engine._replace_codepoints(base, donor, map(ord, 'fil'), 'latin', transform=(1, 0))
                for name in ('f', 'i', 'fi', 'ff', 'ffi', 'fl'):
                    self.assertEqual(base['glyf'][name].xMin, 80, name)
                self.assertEqual(base.getTableData('GSUB'), gsub)
                self.assertEqual(base.getGlyphOrder(), order)

    def test_oem_path_preserves_contract_with_dedicated_or_joined_ligatures(self):
        for real_ligature in (True, False):
            for extension in (True, False):
                with self.subTest(real=real_ligature, extension=extension):
                    base, donor = fixture(extension=extension), fixture(True, real_ligature)
                    contract = protected(base)
                    result = replace_face(base, donor, {}, require_cjk=False, preserve_unsafe=True)
                    self.assertEqual(result['replacedLatinLigatures'], 4)
                    self.assertEqual(base['glyf']['fi'].xMin, 80)
                    self.assertEqual(base['hmtx']['fi'][0], 500 if real_ligature else 1000)
                    self.assertEqual(protected(base), contract)
                    out = BytesIO(); base.save(out); out.seek(0)
                    with TTFont(out) as reopened:
                        self.assertEqual(reopened['glyf']['fi'].xMin, 80)
                        self.assertEqual(protected(reopened), contract)

    def test_unreplaced_components_and_other_script_alias_are_retained(self):
        base, donor = fixture(), fixture(True)
        self.assertNotIn('fi', plan_latin_ligatures(base, donor, {'f': 'f'})[0])
        for table in base['cmap'].tables:
            if table.isUnicode():
                table.cmap[0x4E2D] = 'fi'
        extra, _, _ = plan_latin_ligatures(base, donor, dict(f='f', i='i', l='l'))
        self.assertNotIn('fi', extra)
        self.assertIn('fl', extra)

    def test_encoded_presentation_ligature_without_donor_presentation(self):
        base, donor = fixture(), fixture(True, False)
        for table in base['cmap'].tables:
            if table.isUnicode():
                table.cmap[0xFB01] = 'fi'
        result = replace_face(base, donor, {}, require_cjk=False, preserve_unsafe=True)
        self.assertEqual(result['replacedLatinLigatures'], 4)
        self.assertEqual(base['glyf']['fi'].xMin, 80)
        self.assertEqual(base['hmtx']['fi'][0], 1000)


if __name__ == '__main__':
    unittest.main()
