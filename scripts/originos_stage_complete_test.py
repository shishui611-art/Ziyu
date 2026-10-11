#!/usr/bin/env python3
"""Release regression checks for Vivo's isolated Chinese-slot completion."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'common'))
from fontTools.ttLib import TTFont
from coloros_metrics_batch_test import font_file, stock
import originos_stage_complete as batch
from font_route_verify import physical_roles


class OriginOSStageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.module = Path(self.temp.name) / 'module'
        self.stage = self.module / '.luoshu-payload-next'
        self.donor = self.stage / 'system/fonts/.luoshu-font-store/regular.font'
        font_file(self.donor)
        with TTFont(self.donor) as font:
            for table in font['cmap'].tables:
                if table.isUnicode():
                    table.cmap.update({ord(ch): 'uni4E2D' for ch in '永国'})
            font.save(self.donor)
        (self.module / 'config').mkdir()
        self.env = patch.dict(os.environ, {'LUOSHU_BUILD_KEY': 'vivo-fixture'})
        self.env.start(); self.addCleanup(self.env.stop)

    def inventory(self, slots, build='vivo-fixture'):
        entries = {logical: {'path': logical, 'source': 'verified-scan',
                             'format': 'TTF', 'style': 'normal', **metrics}
                   for logical, metrics in slots.items()}
        (self.module / 'config/device_font_inventory.json').write_text(json.dumps({
            'schema': 'device-font-inventory-v1', 'inventoryRevision': 1,
            'state': 'ready', 'buildKey': build, 'slots': entries}))

    def test_completes_only_inventoried_chinese_slots_and_keeps_donor(self):
        logical = '/system/fonts/DroidSansFallbackBBK.ttf'
        other = '/product/fonts/DroidSansFallbackMonster.ttf'
        self.inventory({logical: stock(), other: stock(ascent=1000),
                        '/system/fonts/StatusIcons.ttf': stock()})
        original = self.donor.read_bytes()
        result = batch.build(self.module, self.stage)
        self.assertEqual(result['mapped'], 2)
        self.assertEqual(self.donor.read_bytes(), original)
        self.assertFalse((self.stage / 'system/fonts/VivoFont.ttf').exists())
        self.assertFalse((self.stage / 'system/fonts/StatusIcons.ttf').exists())
        for path, ascent in ((logical, 920), (other, 1000)):
            with TTFont(self.stage / path.lstrip('/')) as font:
                self.assertEqual(font['hhea'].ascent, ascent)
                self.assertTrue(all(ord(ch) in font.getBestCmap() for ch in '中永国'))
            self.assertIn('cjk', physical_roles(Path(path).name))

    def test_stale_inventory_does_not_add_targets(self):
        self.inventory({'/system/fonts/DroidSansFallbackBBK.ttf': stock()}, build='old')
        self.assertEqual(batch.build(self.module, self.stage)['mapped'], 0)
        self.assertFalse((self.stage / 'system/fonts/DroidSansFallbackBBK.ttf').exists())

    def test_invalid_stock_metrics_fail_before_adding_targets(self):
        self.inventory({'/system/fonts/DroidSansFallbackBBK.ttf': stock(ascent=-1)})
        with self.assertRaises(ValueError):
            batch.build(self.module, self.stage)
        self.assertFalse((self.stage / 'system/fonts/DroidSansFallbackBBK.ttf').exists())

    def test_latin_only_donor_and_live_payload_are_rejected(self):
        self.inventory({'/system/fonts/DroidSansFallbackBBK.ttf': stock()})
        with TTFont(self.donor) as font:
            for table in font['cmap'].tables:
                if table.isUnicode():
                    table.cmap.pop(ord('中'), None)
            font.save(self.donor)
        with self.assertRaises(ValueError):
            batch.build(self.module, self.stage)
        with self.assertRaises(ValueError):
            batch.build(self.module, self.module / '.luoshu-payload')


if __name__ == '__main__':
    unittest.main()
