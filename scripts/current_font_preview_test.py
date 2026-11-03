#!/usr/bin/env python3
"""Current preview must be proven by this boot, manifest and visible bytes."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
import current_font_preview as preview
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, TTCollection


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.module = self.root / 'module'
        self.config = self.module / 'config'; self.config.mkdir(parents=True)
        self.visible = self.root / 'visible'; fonts = self.visible / 'system/fonts'; fonts.mkdir(parents=True)
        self.source = fonts / 'Current.ttf'
        builder = FontBuilder(1000, isTTF=True)
        builder.setupGlyphOrder(['.notdef', 'A']); builder.setupCharacterMap({65: 'A'})
        glyph = TTGlyphPen(None).glyph()
        builder.setupGlyf({'.notdef': glyph, 'A': glyph})
        builder.setupHorizontalMetrics({'.notdef': (500, 0), 'A': (500, 0)})
        builder.setupHorizontalHeader(ascent=800, descent=-200)
        builder.setupNameTable({'familyName': 'Current', 'styleName': 'Regular'})
        builder.setupOS2(); builder.setupPost(); builder.save(self.source)
        self.rel = 'system/fonts/Current.ttf'; self.boot = self.root/'boot'; self.boot.write_text('this-boot')
        self.destination = self.root/'export.ttf'; self.report = self.config/'mount-backend-verification.json'
        self.publish()

    def publish(self, **changes):
        sha = hashlib.sha256(self.source.read_bytes()).hexdigest()
        manifest = f'{self.rel}|{sha}\n'.encode()
        (self.config/'font-payload-manifest.conf').write_bytes(manifest)
        data = dict(state='verified', mode='physical-safe', bootId='this-boot', activeFont='mounted',
                    namespace='/proc/1/root', payloadManifestSha256=hashlib.sha256(manifest).hexdigest(),
                    cjkRoutes=[self.source.name], appliedFonts=[self.rel])
        data.update(changes); self.report.write_text(json.dumps(data))

    def export(self):
        return preview.export(self.module, 'mounted', self.destination, visible_root=self.visible, boot_file=self.boot)

    def test_exports_visible_file_without_any_library_source(self):
        data = self.export()
        self.assertEqual(self.destination.read_bytes(), self.source.read_bytes())
        self.assertEqual(data['logicalPath'], '/'+self.rel)
        self.assertEqual(data['sha256'], hashlib.sha256(self.destination.read_bytes()).hexdigest())

    def test_old_boot_font_and_pending_records_are_rejected(self):
        for fields in [dict(bootId='old'), dict(activeFont='new-selection'), dict(state='pending'), dict(namespace='/')]:
            self.publish(**fields)
            with self.assertRaises(ValueError): self.export()
            self.assertFalse(self.destination.exists())

    def test_manifest_and_visible_changes_are_rejected(self):
        self.publish(payloadManifestSha256='0'*64)
        with self.assertRaises(ValueError): self.export()
        self.publish(); self.source.write_bytes(b'replaced')
        with self.assertRaises(ValueError): self.export()
        self.assertFalse(self.destination.exists())

    def test_partial_report_cannot_preview_an_unapplied_slot(self):
        self.publish(state='partial', appliedFonts=[])
        with self.assertRaises(ValueError): self.export()

    def test_change_during_copy_preserves_previous_export(self):
        self.destination.write_bytes(b'previous')
        original = preview.tempfile.NamedTemporaryFile
        def changed(*args, **kwargs):
            result = original(*args, **kwargs); self.publish(bootId='changed'); return result
        with patch.object(preview.tempfile, 'NamedTemporaryFile', side_effect=changed):
            with self.assertRaises(ValueError): self.export()
        self.assertEqual(self.destination.read_bytes(), b'previous')
        self.assertEqual(list(self.root.glob('.current-font-*')), [])

    def test_collection_uses_proven_chinese_face(self):
        with TTFont(self.source) as first, TTFont(self.source) as second:
            collection = TTCollection(); collection.fonts = [first, second]; collection.save(self.root/'faces.ttc')
        self.source.unlink(); self.source = self.source.with_suffix('.ttc'); self.source.write_bytes((self.root/'faces.ttc').read_bytes())
        self.rel = 'system/fonts/Current.ttc'; self.publish()
        (self.config/'device_font_inventory.json').write_text(json.dumps(dict(xmlGraph=dict(refs=[dict(
            resolvedPath='/'+self.rel, index=1, familyAttributes=dict(lang='zh-Hans'))]))))
        self.assertEqual(self.export()['faceIndex'], 1)

    def test_ambiguous_collection_and_untrusted_destinations(self):
        for path in ['/data/adb/ziyu/file', '/sdcard/test.ttf', '/data/user/0/other/cache/font.ttf']:
            with self.assertRaises(ValueError): preview.trusted_destination(Path(path))
        with TTFont(self.source) as first, TTFont(self.source) as second:
            collection = TTCollection(); collection.fonts = [first, second]; collection.save(self.root/'faces.ttc')
        self.source.unlink(); self.source = self.source.with_suffix('.ttc'); self.source.write_bytes((self.root/'faces.ttc').read_bytes())
        self.rel = 'system/fonts/Current.ttc'; self.publish()
        with self.assertRaises(ValueError): self.export()
        self.assertFalse(self.destination.exists())


if __name__ == '__main__': unittest.main()
