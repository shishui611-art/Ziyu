#!/usr/bin/env python3
"""Release regressions for verified font cache targets and alias identity."""
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
import font_preload_state as preload


class PreloadCatalogueTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.module = Path(self.tmp.name) / 'module'
        self.payload = self.module / '.luoshu-payload'
        self.root = Path(self.tmp.name) / 'pid1'
        self.payload.mkdir(parents=True)
        self.root.mkdir()

    def font(self, rel, data=b'font fixture'):
        for base in (self.payload, self.root):
            path = base / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        return rel

    def rows(self, paths, **extras):
        text, count, omitted = preload.catalogue(self.module, {
            'mode': 'physical-safe', 'mountedFonts': paths, **extras,
        }, self.root)
        rows = [line[5:].split('|') for line in text.splitlines()]
        self.assertEqual(count, len(rows))
        return rows, omitted

    def test_many_latin_aliases_do_not_evict_cjk_and_digits(self):
        latin = [self.font(f'system/fonts/Roboto-{i:03}.ttf') for i in range(205)]
        cjk = self.font('system/fonts/NotoSansCJK-Regular.ttc')
        digit = self.font('system/fonts/OSans-Solid-Digits.ttf')
        rows, omitted = self.rows(latin + [cjk, digit])
        first = [row[0] for row in rows[:4]]
        self.assertIn('/' + cjk, first)
        self.assertIn('/' + digit, first)
        self.assertEqual(len(rows), preload.LIMIT)
        self.assertGreater(omitted, 0)

    def test_provider_copy_uses_visible_identity_not_source_inode(self):
        rel = self.font('system/fonts/SysSans.ttf')
        rows, _ = self.rows([rel])
        self.assertEqual(rows[0][2], preload.identity(self.payload / rel))
        self.assertEqual(rows[0][3], preload.identity(self.root / rel))
        self.assertNotEqual(rows[0][2], rows[0][3])

    def test_only_same_file_partition_alias_is_preloaded(self):
        rel = self.font('product/fonts/OplusOSUI.ttf')
        alias = self.root / 'system/product/fonts/OplusOSUI.ttf'
        alias.parent.mkdir(parents=True)
        os.link(self.root / rel, alias)
        rows, _ = self.rows([rel])
        self.assertIn('/system/product/fonts/OplusOSUI.ttf', [r[0] for r in rows])
        alias.unlink()
        alias.write_bytes((self.root / rel).read_bytes())
        rows, _ = self.rows([rel])
        self.assertNotIn('/system/product/fonts/OplusOSUI.ttf', [r[0] for r in rows])

    def test_declared_alias_keeps_actual_font_cache_key(self):
        rel = self.font('system/fonts/SysSans.ttf')
        alias = self.root / 'system/fonts/SysFont.ttf'
        os.link(self.root / rel, alias)
        rows, _ = self.rows([rel], routeEvidence={rel: {
            'kind': 'stock-xml', 'declared': 'SysFont.ttf', 'roles': 'cjk',
        }})
        self.assertIn('/system/fonts/SysFont.ttf', [r[0] for r in rows])

    def test_partial_proof_never_scans_unverified_payload_fonts(self):
        good = self.font('system/fonts/SysSans.ttf')
        self.font('system/fonts/NotMounted.ttf')
        rows, _ = self.rows([good])
        self.assertEqual([r[0] for r in rows], ['/' + good])

    def test_unreadable_font_preserves_other_verified_fonts(self):
        good = self.font('system/fonts/SysSans.ttf')
        missing = self.font('system/fonts/Missing.ttf')
        (self.root / missing).unlink()
        rows, omitted = self.rows([good, missing])
        self.assertEqual([r[0] for r in rows], ['/' + good])
        self.assertEqual(omitted, 1)

    def test_data_fonts_and_path_escapes_are_rejected(self):
        good = self.font('system/fonts/SysSans.ttf')
        invalid = ['/data/fonts/files/GoogleSans.ttf', 'system/fonts/../etc/x.ttf',
                   'system//fonts/x.ttf', 'system/fonts/x.ttf|other', None, {}]
        rows, omitted = self.rows([good, *invalid])
        self.assertEqual([r[0] for r in rows], ['/' + good])
        self.assertEqual(omitted, len(invalid))


if __name__ == '__main__':
    unittest.main()
