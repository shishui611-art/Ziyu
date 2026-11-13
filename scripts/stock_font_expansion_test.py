"""Regression checks for partial stock snapshots and ColorOS font link routing."""
from pathlib import Path
from contextlib import redirect_stdout, redirect_stderr
import hashlib
import io
import os
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
import font_inventory as inventory
import stock_font_snapshot as snapshot
import font_expansion_guard as expansion


class SlotReplacementPolicyTest(unittest.TestCase):
    def profile(self, *, points=(65,), variants=(), axes=None, layout=None, variable=False):
        return expansion.Profile(frozenset(points), frozenset(variants), axes or {},
                                 layout or {'GSUB': frozenset(), 'GPOS': frozenset()}, variable)

    def check(self, donor, original, *, allow_missing=False):
        guard = expansion.CoverageGuard()
        with patch.object(guard, 'profile', side_effect=[donor, original]):
            guard.check('donor', 'original', allow_missing=allow_missing)

    def test_strict_validation_rejects_missing_letters_marks_and_numbers(self):
        original = self.profile(points=(65, 66, 0x627), variants=((66, 0xFE00),),
                                layout={'GSUB': frozenset({('arab', 'dflt', 'init')}),
                                        'GPOS': frozenset()})
        with self.assertRaises(ValueError):
            self.check(self.profile(), original)

    def test_slot_expansion_allows_missing_source_characters_for_android_fallback(self):
        original = self.profile(points=(65, 66, 0x627), variants=((66, 0xFE00),))
        self.check(self.profile(points=(65,)), original, allow_missing=True)

    def test_strict_validation_rejects_missing_variants(self):
        original = self.profile(points=(65, 66), variants=((66, 0xFE00),))
        with self.assertRaises(ValueError):
            self.check(self.profile(points=(65, 66)), original)

    def test_original_variable_axes_remain_continuously_adjustable(self):
        donor = self.profile(axes={'wght': (100, 900), 'opsz': (1000, 1100), 'wdth': (50, 100)}, variable=True)
        original = self.profile(axes={'wght': (100, 700), 'opsz': (1010, 1090), 'wdth': (75, 100)}, variable=True)
        self.check(donor, original)

    def test_static_source_cannot_replace_adjustable_weight(self):
        with self.assertRaises(ValueError):
            self.check(self.profile(), self.profile(axes={'wght': (100, 900)}, variable=True))

    def test_missing_original_design_axis_is_rejected(self):
        donor = self.profile(axes={'wght': (100, 900)}, variable=True)
        original = self.profile(axes={'wght': (100, 700), 'opsz': (8, 72)}, variable=True)
        with self.assertRaises(ValueError):
            self.check(donor, original)

    def test_shorter_weight_range_is_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.profile(axes={'wght': (100, 900)}, variable=True),
                       self.profile(axes={'wght': (100, 1000)}, variable=True))

    def test_degenerate_or_invalid_weight_and_absent_outlines_are_rejected(self):
        for weight, variable in (((400, 400), True), ((100, float('nan')), True), ((100, 900), False)):
            with self.subTest(weight=weight, variable=variable), self.assertRaises(ValueError):
                self.check(self.profile(axes={'wght': weight}, variable=variable),
                           self.profile(axes={'wght': (100, 700)}, variable=True))


class StockFontExpansionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.logical = self.base / 'system/fonts'
        self.stock = self.base / 'readonly/fonts'
        self.bridge = self.base / 'data/font'
        for path in (self.logical, self.stock, self.bridge):
            path.mkdir(parents=True)
        self.root = inventory.FontRoot('system', self.logical, self.stock)
        self.links = {}
        self.real_lstat = Path.lstat
        self.real_readlink = os.readlink
        self.addCleanup(patch.stopall)
        patch.object(inventory, 'DATA_FONT_LINK_ROOTS', frozenset({self.bridge})).start()
        patch.object(Path, 'lstat', lambda path, *args, **kwargs: self.lstat(path, *args, **kwargs)).start()
        patch('os.readlink', side_effect=self.readlink).start()
        snapshot._loaded.clear()
        snapshot._verified.clear()

    def lstat(self, path, *args, **kwargs):
        if str(path) in self.links:
            return os.stat_result((stat.S_IFLNK | 0o777, 1, 1, 1, 0, 0, 1, 0, 0, 0))
        return self.real_lstat(path, *args, **kwargs)

    def readlink(self, path, *args, **kwargs):
        if str(path) in self.links:
            value = self.links[str(path)]
            return value() if callable(value) else value
        return self.real_readlink(path, *args, **kwargs)

    def routed_font(self):
        source = self.stock / 'Regular.ttf'
        source.write_bytes(b'verified stock bytes')
        (self.logical / 'Regular.ttf').write_bytes(b'active replacement bytes')
        self.links[str(self.stock / 'Alias.ttf')] = str(self.bridge / 'Regular.ttf')
        self.links[str(self.bridge / 'Regular.ttf')] = str(self.logical / 'Regular.ttf')
        return source

    def test_data_bridge_reads_stock_view_instead_of_live_font(self):
        source = self.routed_font()
        links = {}
        actual = inventory._stock_font_path(self.root, self.stock / 'Alias.ttf', [self.root], routing_links=links)
        self.assertEqual(actual, source)
        self.assertEqual(actual.read_bytes(), b'verified stock bytes')
        self.assertEqual(links, {str(self.bridge / 'Regular.ttf'): str(self.logical / 'Regular.ttf')})

    def test_strict_existing_callers_still_require_stock_only_links(self):
        self.routed_font()
        with self.assertRaises(inventory.InventoryError):
            inventory._stock_font_path(self.root, self.stock / 'Alias.ttf', [self.root])

    def test_real_data_file_is_not_used_as_stock(self):
        self.routed_font()
        del self.links[str(self.bridge / 'Regular.ttf')]
        (self.bridge / 'Regular.ttf').write_bytes(b'theme font bytes')
        with self.assertRaises(inventory.InventoryError):
            inventory._stock_font_path(self.root, self.stock / 'Alias.ttf', [self.root], routing_links={})

    def test_external_target_and_link_cycle_are_rejected(self):
        self.routed_font()
        for target in (str(self.base / 'external.ttf'), str(self.bridge / 'Regular.ttf')):
            with self.subTest(target=target):
                self.links[str(self.bridge / 'Regular.ttf')] = target
                with self.assertRaises(inventory.InventoryError):
                    inventory._stock_font_path(self.root, self.stock / 'Alias.ttf', [self.root], routing_links={})

    def test_changing_link_is_rejected_before_returning_a_stock_file(self):
        self.routed_font()
        values = iter([str(self.logical / 'Regular.ttf'), str(self.logical / 'Other.ttf')])
        self.links[str(self.bridge / 'Regular.ttf')] = lambda: next(values)
        with self.assertRaises(inventory.InventoryError):
            inventory._stock_font_path(self.root, self.stock / 'Alias.ttf', [self.root], routing_links={})

    def test_snapshot_route_change_invalidates_cached_font(self):
        self.routed_font()
        module = self.base / 'module'
        data = b'original cached font'
        digest = hashlib.sha256(data).hexdigest()
        directory = module / 'config/stock-font-snapshot/files'
        directory.mkdir(parents=True)
        saved = directory / (digest + '.font')
        saved.write_bytes(data)
        logical = str(self.logical / 'Alias.ttf')
        entry = {'sha256': digest, 'size': len(data),
                 'routingLinks': {str(self.bridge / 'Regular.ttf'): str(self.logical / 'Regular.ttf')}}
        with patch.object(snapshot, 'load', return_value={'files': {logical: entry}}):
            self.assertEqual(snapshot.resolve(logical, module), saved)
            self.links[str(self.bridge / 'Regular.ttf')] = str(self.logical / 'Other.ttf')
            self.assertIsNone(snapshot.resolve(logical, module))

    def test_capture_deduplicates_bytes_and_records_each_alias_route(self):
        self.routed_font()
        module = self.base / 'module'
        alias, regular = str(self.logical / 'Alias.ttf'), str(self.logical / 'Regular.ttf')
        with patch.object(snapshot, 'build_identity', return_value=('test-build', 'test-build', '')), \
             patch.object(inventory, 'current_build_key', return_value=('test-build', '', '')):
            result = snapshot.capture(module, requested_paths=[alias, regular], views=[[self.root]])
            self.assertEqual(result['state'], 'complete')
            self.assertEqual(result['newCopies'], 1)
            manifest = snapshot.load(module)
            self.assertIn('routingLinks', manifest['files'][alias])
            self.assertNotIn('routingLinks', manifest['files'][regular])
            self.assertIsNotNone(snapshot.resolve(alias, module))
            self.links[str(self.bridge / 'Regular.ttf')] = str(self.logical / 'Other.ttf')
            self.assertIsNone(snapshot.resolve(alias, module))
            self.assertIsNotNone(snapshot.resolve(regular, module))

    def test_partial_snapshot_is_opt_in_and_owned_view_is_closed(self):
        available, missing = str(self.logical / 'Available.ttf'), str(self.logical / 'Missing.ttf')
        view = Mock()
        view.pick.return_value = self.stock
        def resolve(logical, module):
            return self.stock / 'Available.ttf' if logical == available else None
        with patch.object(inventory, 'LOGICAL_FONT_ROOTS', (('system', self.logical),)), \
             patch.object(snapshot, 'resolve', side_effect=resolve), \
             patch.object(snapshot, 'capture', return_value={'state': 'partial', 'missing': 1}), \
             patch.dict(sys.modules, {'stock_readonly_view': SimpleNamespace(StockReadonlyView=lambda: view)}):
            with self.assertRaises(ValueError):
                snapshot.ensure_expansion(self.base, [available, missing])
            result = snapshot.ensure_expansion(self.base, [available, missing], allow_partial=True)
            self.assertEqual(result['unavailable'], 1)
            self.assertEqual(view.close.call_count, 2)

    def test_filter_outputs_verified_slots_when_another_original_is_missing(self):
        source = self.routed_font()
        logical = str(self.logical / 'Available.ttf')
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, {'ZIYU_SLOT_PREPARE_STOCK': '1'}), \
             patch.object(sys, 'stdin', io.StringIO(f'{source}|Available.ttf\n{source}|Missing.ttf\n')), \
             patch.object(snapshot, 'ensure_expansion', return_value={'state': 'partial'}) as prepare, \
             patch.object(snapshot, 'load', return_value={'files': {logical: {}}}), \
             patch.object(snapshot, 'resolve', return_value=source), \
             patch.object(expansion.CoverageGuard, 'check') as check, \
             patch('font_role_policy.keep_lockscreen', return_value=False), \
             patch('font_slot_overrides.load', return_value={}), \
             patch('font_slot_overrides.exclusion', return_value=False), \
             redirect_stdout(stdout), redirect_stderr(stderr):
            expansion.filter_plan(self.base, self.logical)
        self.assertEqual(stdout.getvalue(), f'{source}|Available.ttf\n')
        self.assertIn('Missing.ttf', stderr.getvalue())
        self.assertTrue(prepare.call_args.kwargs['allow_partial'])
        self.assertEqual(check.call_args.args, (str(source), source))
        self.assertEqual(check.call_args.kwargs, {'allow_missing': True})


if __name__ == '__main__':
    unittest.main(verbosity=2)
