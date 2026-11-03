#!/usr/bin/env python3
"""Same-ROM recovery for devices whose loop EROFS mount returns EIO."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
if os.name == 'nt':
    # These tests exercise snapshot recovery, never Linux mount/ioctl calls.
    # An empty stub makes any accidental ioctl attempt fail, rather than pass.
    import types
    sys.modules.setdefault('fcntl', types.ModuleType('fcntl'))
import stock_inventory_scan as scan
import stock_font_snapshot as snapshot


class StockRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.module = Path(self.temp.name)/'module'; self.config=self.module/'config'; self.config.mkdir(parents=True)
        root=self.config/'stock-font-snapshot'; (root/'files').mkdir(parents=True)
        self.font=root/'files'/(hashlib.sha256(b'original-font').hexdigest()+'.font'); self.font.write_bytes(b'original-font')
        self.logical=PurePosixPath('/system/fonts'); self.name='/system/fonts/Stock.ttf'
        self.manifest=root/'manifest.json'
        self.data=dict(schema=snapshot.SCHEMA, buildKey='ROM-build', state='complete',
                       files={self.name:dict(sha256=self.font.stem, size=self.font.stat().st_size)})
        self.manifest.write_text(json.dumps(self.data))
        (self.config/'stock-rom-scan.conf').write_text('rom_identity=system-file-v2:same-rom\n')
        (self.config/'device_font_inventory.json').write_text(json.dumps(dict(slots={self.name:{}},xmlGraph=dict(refs=[]))))
        stage=Path(self.temp.name)/'scan';stage.mkdir()
        snapshot._loaded.clear();snapshot._verified.clear()
        for obj, name, value in [(scan,'_ACTIVE_OVERLAY_MODULE',self.module),(scan,'_SAVED_ROOTS',{}),
                                 (scan,'_SAVED_SCAN_BASE',stage),(scan,'Path',PurePosixPath)]:
            patcher=patch.object(obj,name,value);patcher.start();self.addCleanup(patcher.stop)
        for patcher in [patch.dict(os.environ,ZIYU_SCAN_ROM_IDENTITY='system-file-v2:same-rom'),
                        patch.object(snapshot,'build_identity',return_value=('ROM-build','ROM-build',{})),
                        patch.object(scan.inventory,'validate_inventory')]:
            patcher.start();self.addCleanup(patcher.stop)

    def test_complete_same_rom_snapshot_recovers_before_block_remount(self):
        with patch.object(scan, '_private_root_overlaid', return_value=True), \
             patch.object(scan, 'live_work_root', return_value=Path(self.temp.name)/'no-lower'), \
             patch.object(scan.inventory, 'MIRROR_PREFIXES', ()), \
             patch.object(scan, '_installer_namespace_is_stock', return_value=False), \
             patch.object(scan, '_bind_parent_stock_snapshot', return_value=None), \
             patch.object(scan._READONLY_VIEW, 'pick', side_effect=OSError(5, 'EROFS mount EIO')) as mount:
            target=scan._safe_pick_actual_root(self.logical, None, True)
            mount.assert_not_called()
        self.assertEqual((target/'Stock.ttf').read_bytes(),b'original-font')
        self.assertEqual((target/'Stock.ttf').stat().st_ino,self.font.stat().st_ino)
        self.assertEqual(scan._saved_stock_root(self.logical),target)

    def test_changed_rom_does_not_reuse_old_snapshot(self):
        with patch.dict(os.environ,ZIYU_SCAN_ROM_IDENTITY='system-file-v2:new-rom'):
            self.assertIsNone(scan._saved_stock_root(self.logical))

    def test_corrupt_content_and_missing_required_slot_are_rejected(self):
        self.font.write_bytes(b'corrupted-font')
        self.assertIsNone(scan._saved_stock_root(self.logical))
        self.font.write_bytes(b'original-font')
        (self.config/'device_font_inventory.json').write_text(json.dumps(dict(slots={self.name:{},'/system/fonts/Missing.ttf':{}})))
        self.assertIsNone(scan._saved_stock_root(self.logical))

    def test_partial_snapshot_cannot_be_promoted_to_stock(self):
        self.data['state']='partial';self.manifest.write_text(json.dumps(self.data))
        self.assertIsNone(scan._saved_stock_root(self.logical))


if __name__=='__main__':unittest.main()
