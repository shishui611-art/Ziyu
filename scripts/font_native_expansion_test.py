"""Exercise the real desktop build of the production HarfBuzz C++ helper.

Set ZIYU_NATIVE_TEST_HELPER to a host build of native/font-instance/instance.cpp.
The suite never installs an App, changes a device font or writes user sources.
"""
import os
from array import array
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'common'), str(ROOT/'scripts')]
from fontTools.ttLib import TTFont
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.ttLib.tables._g_l_y_f import Glyph, GlyphCoordinates
from fontTools.varLib.instancer import instantiateVariableFont
from font_instance_svg_test import fixture
from composite_collection_build_test import make_font
import font_native_instance as native
import font_native_glyphs as glyphs
import universal_font_compiler as compiler
import universal_font_plan as planner
import google_font_provider_patch as provider
import font_ttf_export as exporter
import device_font_slot_build_base as slots


def contours(glyph, glyf):
    coordinates, ends, flags = glyph.getCoordinates(glyf)
    values, start = [], 0
    for end in ends:
        points = [(int(coordinates[i][0]), int(coordinates[i][1]), flags[i] & 1) for i in range(start, end+1)]
        minimum = min(points)
        values.append(min(tuple(points[i:]+points[:i]) for i, p in enumerate(points) if p == minimum))
        start = end+1
    return values


@unittest.skipUnless(os.environ.get('ZIYU_NATIVE_TEST_HELPER'), 'real host helper not configured')
class NativeExpansionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ziyu-native-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.helper = Path(os.environ['ZIYU_NATIVE_TEST_HELPER']).resolve()
        self.source = self.root/'variable.ttf'
        fixture(self.source, 700, svg=False)

    def command(self, source, output, face, axes):
        return [str(self.helper), str(source), str(output), str(max(face, 0)),
                *(f'{tag}={value:.9g}' for tag, value in sorted(axes.items()))]

    def glyph_command(self, source, output, upem):
        return [str(self.helper), '--glyph-cache', str(source), str(output), '0', str(upem)]

    def test_native_glyphs_match_curves_composites_scaling_and_bearings(self):
        source = self.root/'static.ttf'
        with TTFont(self.source) as font:
            instantiateVariableFont(font, {'wght': 400}, inplace=True)
            names = font.getBestCmap()
            name = names[ord('中')]
            pen = TTGlyphPen(None); pen.moveTo((50, 0)); pen.qCurveTo((700, 900), (850, 0)); pen.closePath()
            font['glyf'][name] = pen.glyph()
            pen = TTGlyphPen(font.getGlyphSet()); pen.addComponent(name, (-0.75, 0.125, 0, 1, 900, 0))
            font['glyf'][names[ord('永')]] = pen.glyph()
            font['hmtx'][name] = (1000, 57)
            # A stored contour may start off-curve and contain coincident end
            # points with different roles. Preserve its closing control point.
            special = Glyph()
            special.numberOfContours = 1
            special.coordinates = GlyphCoordinates([(0, 0), (100, 100), (0, 0), (0, 0)])
            special.endPtsOfContours = [3]
            special.flags = array('B', [0, 1, 0, 1])
            from fontTools.ttLib.tables.ttProgram import Program
            special.program = Program(); special.program.fromBytecode([])
            font['glyf'][names[ord('A')]] = special
            special = Glyph()
            special.numberOfContours = 1
            special.coordinates = GlyphCoordinates([(0, 0), (100, 100), (0, 0)])
            special.endPtsOfContours = [2]
            special.flags = array('B', [1, 1, 0])
            special.program = Program(); special.program.fromBytecode([])
            font['glyf'][font.getGlyphOrder()[0]] = special
            font.save(source)
        for upem in (1000, 2048):
            with TTFont(source, lazy=True) as font, patch.object(glyphs, '_command', self.glyph_command):
                cache, info = glyphs.prepare(font, source, upem)
                self.assertEqual(info['engine'], 'native-static-glyf-v1')
                glyph_set = font.getGlyphSet()
                for name in font.getGlyphOrder():
                    expected = TTGlyphPen(None); recording = DecomposingRecordingPen(glyph_set)
                    glyph_set[name].draw(recording)
                    recording.replay(TransformPen(expected, (upem/1000, 0, 0, upem/1000, 0, 0)))
                    expected = expected.glyph(); expected.recalcBounds(font['glyf'])
                    actual = cache.glyph(name, font['glyf'])
                    self.assertIsNotNone(actual)
                    self.assertEqual(contours(actual, font['glyf']), contours(expected, font['glyf']), name)

    def test_bad_cache_is_rejected(self):
        with TTFont(self.source) as font:
            for data in (b'not-a-cache', b'ZIYUGC01'+struct.pack('>IHHI', 65535, 1000, 1000, 0)):
                with self.assertRaises(ValueError):
                    glyphs.GlyphCache(data, font, 1000)

    def test_failed_helpers_fall_back_and_changed_sources_abort(self):
        source = self.root/'static.ttf'
        with TTFont(self.source) as font:
            instantiateVariableFont(font, {'wght': 400}, inplace=True)
            font.save(source)
        failure = [sys.executable, '-c', 'raise SystemExit(2)']
        for adapter, call in (
            (glyphs, lambda font: glyphs.prepare(font, source, 1000)),
            (native, lambda font: native.subset_instance(font, source, 0, {ord('中')}, {}, lambda: None)),
        ):
            with TTFont(source) as font, patch.object(adapter, '_command', return_value=failure):
                value, info = call(font)
                self.assertIsNone(value)
                self.assertEqual(info['engine'], 'fonttools')
        # The subset wrapper constructs its own argument vector from the helper
        # executable. Test mutation using a real executable batch of Python here
        # only for the glyph adapter, whose command is passed through unchanged.
        mutation = [sys.executable, '-c', 'import sys;open(sys.argv[1],"ab").write(b"changed");raise SystemExit(2)', str(source)]
        with TTFont(source) as font, patch.object(glyphs, '_command', return_value=mutation):
            with self.assertRaisesRegex(RuntimeError, '发生变化'):
                glyphs.prepare(font, source, 1000)

    def test_native_cancellation_is_not_a_fallback(self):
        def cancel():
            raise RuntimeError('cancelled-test-task')
        with TTFont(self.source) as font, patch.object(native, '_command', self.command):
            with self.assertRaisesRegex(RuntimeError, 'cancelled-test-task'):
                native.subset_instance(font, self.source, 0, {ord('中')}, {'wght': 700}, cancel)

    def test_exhausted_cache_budget_does_not_spawn_a_helper(self):
        with TTFont(self.source) as font:
            instantiateVariableFont(font, {'wght': 400}, inplace=True)
            with patch.object(glyphs, '_command', side_effect=AssertionError('unnecessary helper')):
                cache, info = glyphs.prepare(font, self.source, 1000, budget=0)
            self.assertIsNone(cache)
            self.assertEqual(info['nativeFallback'], 'native-glyph-cache-budget')

    def test_unchanged_provider_source_skips_native_and_subset_work(self):
        source = self.root/'latin.ttf'
        with TTFont(self.source) as font:
            instantiateVariableFont(font, {'wght': 400}, inplace=True)
            for table in font['cmap'].tables:
                if table.isUnicode() and table.format != 14:
                    table.cmap = {cp: name for cp, name in table.cmap.items() if cp < 128}
            font.save(source)
        output = self.root/'unchanged-provider.ttf'
        with patch.object(native, '_command', side_effect=AssertionError('unnecessary helper')):
            report = provider.patch(source, source, output, 400)
        self.assertEqual(report['processingEngine'], {'engine': 'source', 'nativeRequired': False})
        with TTFont(source) as before, TTFont(output) as after:
            self.assertEqual(before.getBestCmap(), after.getBestCmap())
            self.assertEqual(before['hmtx'].metrics, after['hmtx'].metrics)
            self.assertEqual(before.getTableData('glyf'), after.getTableData('glyf'))

    def test_native_stock_and_saved_output_instances(self):
        with patch.object(native, '_command', self.command):
            font, location = compiler._stock_geometry_font(self.source, 0, 700, {})
            self.addCleanup(font.close)
            self.assertEqual(location['wght'], 700)
            self.assertEqual(font._ziyu_instancing['engine'], 'harfbuzz-15.0.0')
            with TTFont(self.source) as original:
                output = compiler._instance_for_validation(original, 700, {}, source_path=self.source)
                self.assertEqual(output._ziyu_instancing['engine'], 'harfbuzz-15.0.0'); output.close()
            with TTFont(self.source) as original:
                output = slots.static_instance(original, 700, self.source)
                self.assertEqual(output._ziyu_instancing['engine'], 'harfbuzz-15.0.0'); output.close()

    def test_modified_memory_validation_does_not_reread_original_file(self):
        with TTFont(self.source) as font, patch.object(native, '_command', side_effect=AssertionError('unexpected file bridge')):
            font['hhea'].ascent = 1234
            instance = compiler._instance_for_validation(font, 700, {})
            self.assertEqual(instance['hhea'].ascent, 1234); instance.close()

    def test_google_subset_and_instancing_native_and_fallback(self):
        target = self.root/'target.ttf'
        with TTFont(self.source) as font:
            instantiateVariableFont(font, {'wght': 400}, inplace=True); font.save(target)
        for use_native in (True, False):
            output = self.root/f'provider-{use_native}.ttf'
            with patch.object(native, '_command', self.command if use_native else lambda *args: None):
                report = provider.patch(self.source, target, output, 700)
            self.assertEqual(report['processingEngine']['engine'], 'harfbuzz-subset-15.0.0' if use_native else 'fonttools')
            with TTFont(output) as result:
                self.assertNotIn('fvar', result)
                self.assertEqual(result['OS/2'].usWeightClass, 700)
                self.assertNotIn(ord('中'), result.getBestCmap())
                with TTFont(self.source) as source:
                    expected = instantiateVariableFont(source, {'wght': 700}, inplace=False)
                    name = result.getBestCmap()[ord('A')]; before = expected.getBestCmap()[ord('A')]
                    actual = contours(result['glyf'][name], result['glyf'])
                    reference = contours(expected['glyf'][before], expected['glyf'])
                    self.assertEqual([len(c) for c in actual], [len(c) for c in reference])
                    for a, b in zip(sum(actual, ()), sum(reference, ())):
                        self.assertEqual(a[2], b[2])
                        self.assertLessEqual(max(abs(a[0]-b[0]), abs(a[1]-b[1])), 1)
                    expected.close()

    def test_google_variable_output_keeps_axes(self):
        output = self.root/'provider-variable.ttf'
        with patch.object(native, '_command', self.command):
            report = provider.patch(self.source, self.source, output, 400)
        self.assertEqual(report['processingEngine']['engine'], 'harfbuzz-subset-15.0.0')
        with TTFont(output) as font:
            self.assertIn('fvar', font); self.assertIn('gvar', font)

    def test_export_cff2_freezes_natively_then_converts_and_checks_layout(self):
        source = self.root/'000.otf'; make_font(source, kind='cff2')
        with TTFont(source) as font:
            FontBuilder(font=font).setupFvar([('wght', 100, 400, 900, 'Weight')], []); font.save(source)
        with patch.object(exporter, 'app_access', lambda root: None):
            exporter.list_choices(self.root)
            import json
            token = json.loads((self.root/'choices.json').read_text(encoding='utf-8'))[0]['token']
            with patch.object(native, '_command', self.command):
                report = exporter.export(self.root, token)
            self.assertEqual(report['instancing']['engine'], 'harfbuzz-15.0.0')
        with TTFont(self.root/'export.ttf') as font:
            self.assertIn('glyf', font); self.assertNotIn('fvar', font)

    def test_static_weight_engine_choice_does_not_approve_output(self):
        target = {'action': 'compile', 'status': 'conditional', 'risks': ['static-weight-fallback'],
                  'source': {'variable': False, 'weight': 500}, 'selection': {'weightMode': 'static', 'targetWeight': 400}}
        plan = {'planId': 'example', 'targets': {'/system/fonts/Test.ttf': target}}
        self.assertEqual(planner.static_weight_compatibility(plan)['status'], 'compatibility-required')
        target['source']['weight'] = 400
        self.assertIsNone(planner.static_weight_compatibility(plan))


if __name__ == '__main__':
    unittest.main()
