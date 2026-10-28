"""Native acceptance/fallback and variable collection weight selection regression."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'common'), str(ROOT / 'scripts')]
sys.path.append(str(ROOT / 'common/legacy_v14_4'))
from fontTools.ttLib import TTFont, TTCollection
from fontTools.varLib.instancer import instantiateVariableFont
from font_instance_svg_test import fixture
import font_native_instance as native


def load(relative):
    spec = importlib.util.spec_from_file_location('instance_under_test', ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NativeInstanceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / 'source.ttf'
        fixture(self.source, 700, svg=False)

    def fake_command(self, source, output, face, axes):
        with TTFont(source) as font:
            instantiateVariableFont(font, axes, inplace=True).save(output)
        return [sys.executable, '-c', 'pass']

    def test_validated_native_candidate_and_cancellation(self):
        with TTFont(self.source, lazy=True) as font, patch.object(native, '_command', self.fake_command):
            candidate, detail = native.attempt(font, self.source, -1, {'wght': 700.0}, 'cjk', lambda: None)
            self.assertEqual(detail['engine'], 'harfbuzz-15.0.0')
            self.assertNotIn('fvar', candidate)
            candidate.close()
            def cancelled():
                raise RuntimeError('cancelled')
            with self.assertRaisesRegex(RuntimeError, 'cancelled'):
                native.attempt(font, self.source, -1, {'wght': 700.0}, 'cjk', cancelled)

    def test_native_candidate_can_be_saved_by_both_materializers(self):
        for relative in ('common/font_instance.py', 'common/legacy_v14_4/font_instance.py'):
            module = load(relative)
            output = self.root / 'native-saved.ttf'
            with patch.object(native, '_command', self.fake_command):
                report = module.materialize(self.source, output, 'cjk', 700, {})
            self.assertEqual(report['instancing']['engine'], 'harfbuzz-15.0.0')
            with TTFont(output) as font:
                self.assertEqual(font['OS/2'].usWeightClass, 700)
                self.assertNotIn('fvar', font)

    def test_native_donor_then_subset_retains_selected_outlines(self):
        from font_instance_svg_test import bounds
        for relative in ('common/font_instance.py', 'common/legacy_v14_4/font_instance.py'):
            module = load(relative)
            for role, char in (('latin', 'A'), ('digit', '1')):
                native_out, fallback_out = self.root / 'native-donor.ttf', self.root / 'fallback-donor.ttf'
                with patch.object(native, '_command', self.fake_command):
                    native_report = module.materialize(self.source, native_out, role, 700, {})
                with patch.object(native, '_command', return_value=None):
                    module.materialize(self.source, fallback_out, role, 700, {})
                self.assertEqual(native_report['instancing']['engine'], 'harfbuzz-15.0.0')
                with TTFont(native_out) as a, TTFont(fallback_out) as b:
                    self.assertEqual(bounds(a, char), bounds(b, char))

    def test_svg_table_must_survive_native_pinning(self):
        source = self.root/'svg.ttf'
        fixture(source, 700, svg=True)
        with TTFont(source, lazy=True) as font, patch.object(native, '_command', self.fake_command):
            candidate, detail = native.attempt(font, source, -1, {'wght': 700.0}, 'cjk', lambda: None)
            self.assertEqual(detail['engine'], 'harfbuzz-15.0.0')
            self.assertEqual(font.reader['SVG '], candidate.reader['SVG '])
            candidate.close()

    def test_sparse_gvar_damage_repairs_one_glyph_then_uses_native(self):
        from font_instance_svg_test import bounds
        import font_gvar_repair
        original_bytes = self.source.read_bytes()
        def open_damaged(*args, **kwargs):
            font = TTFont(*args, **kwargs)
            name = font.getBestCmap()[ord('中')]
            def broken(_name):
                raise AssertionError('invalid packed delta count')
            font['gvar'].variations.data[name] = broken
            return font
        def helper(source, output, face, axes):
            if Path(source) == self.source:
                return [sys.executable, '-c', 'raise SystemExit(69)']
            return self.fake_command(source, output, face, axes)
        for relative in ('common/font_instance.py', 'common/legacy_v14_4/font_instance.py'):
            module = load(relative)
            output = self.root/'repaired-result.ttf'
            with patch.object(font_gvar_repair, 'TTFont', open_damaged), patch.object(native, '_command', helper):
                report = module.materialize(self.source, output, 'cjk', 900, {})
            self.assertEqual(report['instancing']['engine'], 'harfbuzz-15.0.0')
            self.assertEqual(report['instancing']['nativeRepairGlyphs'], 1)
            self.assertEqual(report['variationFallbacks'][0]['characters'], '中')
            with TTFont(self.source) as original, TTFont(output) as result:
                self.assertEqual(bounds(result, '中'), bounds(original, '中'))
                self.assertEqual(bounds(result, '永'), bounds(original, '永', location={'wght':900}))
            self.assertEqual(self.source.read_bytes(), original_bytes)
            with patch.object(font_gvar_repair, 'TTFont', open_damaged), patch.object(native, '_command', helper):
                donor_report = module.materialize(self.source, output, 'latin', 900, {})
            self.assertEqual(donor_report['instancing']['engine'], 'harfbuzz-15.0.0')
            self.assertEqual(donor_report['variationFallbacks'], [])

    def test_nonempty_output_with_missing_outlines_falls_back(self):
        def no_outlines(source, output, face, axes):
            self.fake_command(source, output, face, axes)
            with TTFont(output) as font:
                del font['glyf']; del font['loca']
                font.save(output)
            return [sys.executable, '-c', 'pass']
        with TTFont(self.source, lazy=True) as font, patch.object(native, '_command', no_outlines):
            candidate, detail = native.attempt(font, self.source, -1, {'wght': 700.0}, 'cjk', lambda: None)
            self.assertIsNone(candidate)
            self.assertIn('native-output-outline', detail['nativeFallback'])

    def test_helper_error_keeps_fonttools_and_weight_argument(self):
        for relative in ('common/font_instance.py', 'common/legacy_v14_4/font_instance.py'):
            module = load(relative)
            with patch.object(native, '_command', return_value=[sys.executable, '-c', 'raise SystemExit(69)']):
                output = self.root / 'result.ttf'
                report = module.materialize(self.source, output, 'cjk', 700, {})
                self.assertEqual(report['location']['wght'], 700)
                self.assertEqual(report['instancing']['engine'], 'fonttools')
                self.assertIn('native-exit-69', report['instancing']['nativeFallback'])
                with TTFont(output) as font:
                    self.assertNotIn('fvar', font)
                    self.assertEqual(font['OS/2'].usWeightClass, 700)

    def test_variable_face_range_beats_closer_static_default(self):
        static = TTFont(self.source)
        instantiateVariableFont(static, {'wght': 600}, inplace=True)
        variable = TTFont(self.source)
        collection = TTCollection()
        collection.fonts = [static, variable]
        target = self.root / 'two-faces.ttc'
        collection.save(target)
        collection.close()
        for relative in ('common/font_instance.py', 'common/legacy_v14_4/font_instance.py'):
            self.assertEqual(load(relative).pick_face(target, 'cjk', 800), 1)
        for relative in ('common/composite_font.py', 'common/legacy_v14_4/composite_font.py'):
            self.assertEqual(load(relative)._pick_face(target, 'cjk', 800, None), 1)

    def test_source_mutation_is_not_silently_accepted(self):
        # Mutation must happen after the pre-run digest, inside the child command.
        command = [sys.executable, '-c', 'import sys;open(sys.argv[1],"ab").write(b"changed")', str(self.source)]
        with TTFont(self.source, lazy=True) as font:
            def producer(source, output, face, axes):
                self.fake_command(source, output, face, axes)
                return command
            with patch.object(native, '_command', producer), self.assertRaisesRegex(RuntimeError, '发生变化'):
                native.attempt(font, self.source, -1, {'wght': 700.0}, 'cjk', lambda: None)


if __name__ == '__main__':
    unittest.main()
