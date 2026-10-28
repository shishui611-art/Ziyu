#!/usr/bin/env python3
"""Targeted fixtures for selected-mix TTC/OTC generation and its apply proof."""
from __future__ import annotations

import hashlib
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTCollection, TTFont
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / 'common'
sys.path.insert(0, str(COMMON))

import composite_collection_build as builder
import composite_collection_contract as contract
import font_coverage


ASCII = set(map(ord, 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'))
PUNCTUATION = set(font_coverage.PUNCTUATION)
CORE_HAN = set(range(0x4E00, 0x4E00 + font_coverage.MIN_CORE_HAN))
CORE_HAN.update(font_coverage.CJK_COMMON)


def rect(kind: str, x_min: int):
    pen = (TTGlyphPen(None) if kind == 'glyf'
           else T2CharStringPen(None if kind == 'cff2' else 620,
                                None, CFF2=kind == 'cff2'))
    pen.moveTo((x_min, 30))
    pen.lineTo((560, 30))
    pen.lineTo((560, 700))
    pen.lineTo((x_min, 700))
    pen.closePath()
    return pen.glyph() if kind == 'glyf' else pen.getCharString()


def make_font(path: Path, kind='glyf', weight=400, family='Fixture Sans',
              special=False, donor=False, uvs=False):
    cmap = {}
    if donor:
        points = sorted(ASCII | PUNCTUATION | CORE_HAN)
        cmap = {cp: f'u{cp:04X}' for cp in points}
    else:
        cmap = {cp: f'u{cp:04X}' for cp in sorted(ASCII | PUNCTUATION)}
        cmap.update({ord('中'): 'shared', 0x9FA5: 'shared', ord('国'): 'country',
                     ord('家'): 'forest', ord('龘'): 'dragon'})
    order = ['.notdef', *dict.fromkeys(cmap.values())]
    glyphs = {name: rect(kind, 70 if donor else 20) for name in order}
    font = FontBuilder(1000, isTTF=kind == 'glyf')
    font.setupGlyphOrder(order)
    font.setupCharacterMap(cmap)
    if kind == 'glyf':
        font.setupGlyf(glyphs)
    elif kind == 'cff':
        font.setupCFF('FixturePS', {'FullName': family}, glyphs, {})
    else:
        font.setupCFF2(glyphs)
    font.setupHorizontalMetrics({name: (620, 20 if not donor else 70) for name in order})
    font.setupHorizontalHeader(ascent=800, descent=-200)
    font.setupOS2(usWeightClass=weight, usWidthClass=5, fsSelection=0,
                  sTypoAscender=800, sTypoDescender=-200,
                  usWinAscent=800, usWinDescent=200)
    font.setupNameTable({'familyName': family,
                         'styleName': 'Regular',
                         'fullName': family + ' Regular'})
    font.setupPost(isFixedPitch=0)
    font.setupMaxp()
    font.setupHead(created=0xD0000000, modified=0xD0000000)
    if uvs:
        table = font.font['cmap']
        subtable = CmapSubtable.newSubtable(14)
        subtable.platformID = 0
        subtable.platEncID = 5
        subtable.language = 0
        subtable.cmap = {}
        subtable.uvsDict = {0xFE0F: [(ord('国'), None)]}
        table.tables.append(subtable)
    font.save(path)
    return path


def make_collection(path: Path, faces: list[Path]):
    collection = TTCollection()
    collection.fonts = [TTFont(face, lazy=False, recalcTimestamp=False) for face in faces]
    try:
        collection.save(path, shareTables=True)
    finally:
        collection.close()
    return path


def bounds(font: TTFont, char: str):
    glyphs = font.getGlyphSet()
    pen = BoundsPen(glyphs)
    glyphs[font.getBestCmap()[ord(char)]].draw(pen)
    return pen.bounds


class CompositeCollectionBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / 'module'
        (self.module / 'config').mkdir(parents=True)
        self.payload = self.module / '.luoshu-state/tmp/stage'
        (self.payload / 'system/fonts').mkdir(parents=True)
        self.work = self.root / 'task'
        self.work.mkdir()

        self.source = make_font(self.root / 'mix-composite.ttf', donor=True,
                                weight=600, family='Selected Mix')
        self.stock_faces = [
            make_font(self.root / 'regular.ttf', weight=400, family='Stock Regular', uvs=True),
            make_font(self.root / 'bold.ttf', kind='cff', weight=700, family='Stock Bold'),
            make_font(self.root / 'light.ttf', kind='cff2', weight=300, family='Stock Light'),
            make_font(self.root / 'emoji.ttf', weight=500, family='Emoji Fixture'),
        ]
        # The final face is specialized by its name and must remain OEM stock.
        self.stock_faces[-1] = make_font(self.root / 'emoji.ttf', weight=500,
                                         family='Emoji Fixture')
        self.stock = make_collection(self.root / 'stock.ttc', self.stock_faces)
        self.emoji_stock = make_collection(
            self.root / 'emoji-only.ttc', [self.stock_faces[-1]])
        self.targets = {
            '/system/fonts/Core.ttc': self.stock,
            '/system/fonts/Emoji.ttc': self.emoji_stock,
        }
        self.inventory = {'slots': {
            logical: {'uiEligible': True, 'source': 'verified-scan'}
            for logical in self.targets
        }}
        self.inventory_path = self.module / 'config/device_font_inventory.json'
        self.inventory_path.write_text('{}', encoding='utf-8')
        self.mix_config = self.root / 'Selected Mix.conf'
        self.mix_config.write_text(
            'combination=true\nfiles=["mix-composite.ttf"]\nrequest=fixture-request\n',
            encoding='utf-8')
        self.target_path = self.payload / 'system/fonts/Core.ttc'
        self.emoji_path = self.payload / 'system/fonts/Emoji.ttc'
        self.target_path.write_bytes(b'old generated single-face alias')
        self.emoji_path.write_bytes(b'old generated single-face alias')

    def test_variable_slot_corrects_only_one_unit_rounding_difference(self):
        with TTFont(self.stock_faces[0], lazy=False, recalcBBoxes=False) as stock, \
             TTFont(self.source, lazy=False, recalcBBoxes=False) as donor:
            FontBuilder(font=stock).setupFvar([('wght', 100, 400, 900, 'Weight')], [])
            stock_order = list(stock.getGlyphOrder())
            original = builder.protected(stock, stock_order)
            name = donor.getBestCmap()[ord('A')]
            # A stale stored source bound makes the decomposed outline differ
            # from its side bearing by one unit, as seen on the affected phone.
            donor['glyf'][name].xMin -= 1
            larger = donor.getBestCmap()[ord('B')]
            donor['glyf'][larger].xMin -= 2
            stock_b = stock.getBestCmap()[ord('B')]
            original_b_min = stock['glyf'][stock_b].xMin
            details = builder.replace_face(stock, donor, {}, require_cjk=False,
                                           preserve_unsafe=True, preserve_dependencies=True)
            target = stock.getBestCmap()[ord('A')]
            self.assertEqual(stock['glyf'][target].xMin, donor['hmtx'][name][1])
            self.assertEqual(stock['glyf'][stock_b].xMin, original_b_min)
            self.assertGreaterEqual(details['retainedUnsafeSlots'], 1)
            self.assertEqual(details['mode'], 'compiled')
            self.assertEqual(builder.protected(stock, stock_order), original)

    def run_payload_build(self, request='mix-fixture-123'):
        with patch.object(builder.stock_inventory, 'load_inventory',
                           return_value=self.inventory), \
             patch.object(builder.stock_font_snapshot, 'resolve',
                          side_effect=lambda logical, module: self.targets.get(logical)):
            result = builder.build_payload(
                self.module, self.payload, self.source, request, self.work,
                self.mix_config)
            verified = contract.validate(
                self.payload, request, self.module, require_proof=True,
                source=self.source)
        return result, verified

    def test_saved_mix_builds_each_supported_face_and_keeps_proof(self):
        result, verified = self.run_payload_build()
        self.assertEqual(result['result'], 'PASS')
        self.assertEqual(verified['result'], 'PASS', verified['errors'])
        self.assertEqual(builder.collection_faces(self.target_path), 4)
        self.assertEqual(self.emoji_path.read_bytes(), self.emoji_stock.read_bytes())

        with TTFont(self.target_path, fontNumber=0, lazy=True) as regular, \
             TTFont(self.stock, fontNumber=0, lazy=True) as original:
            self.assertEqual(bounds(regular, '家')[0], 70)
            self.assertEqual(bounds(original, '家')[0], 20)
            # The shared slot and uncovered glyph stay stock. A default UVS
            # target is an ordinary coded glyph: the transplant restyles it
            # while the format-14 table bytes stay untouched.
            self.assertEqual(bounds(regular, '中')[0], 20)
            self.assertEqual(bounds(regular, '国')[0], 70)
            self.assertEqual(bounds(regular, '龘')[0], 20)
            self.assertTrue(any(table.format == 14 for table in regular['cmap'].tables))
        for index, expected_weight in ((1, 700), (2, 300)):
            with TTFont(self.target_path, fontNumber=index, lazy=True) as generated, \
                 TTFont(self.stock, fontNumber=index, lazy=True) as original:
                self.assertEqual(bounds(generated, '家')[0], 70)
                self.assertEqual(bounds(original, '家')[0], 20)
                self.assertEqual(generated['OS/2'].usWeightClass, expected_weight)
        with TTFont(self.target_path, fontNumber=3, lazy=True) as specialized:
            self.assertEqual(specialized['OS/2'].usWeightClass, 500)

        report_path = self.payload / '.luoshu-collection-proofs' / (
            hashlib.sha256(b'system/fonts/Core.ttc').hexdigest() + '.json')
        import json
        face_report = json.loads(report_path.read_text(encoding='utf-8'))
        self.assertEqual(face_report['selectionModel'], 'selected-fixed-weight')
        self.assertFalse(face_report['selectedAxesTraceable'])
        self.assertEqual([face['targetWeight'] for face in face_report['faces']],
                         [400, 700, 300, 500])
        self.assertTrue(all(face['donorWeight'] == 600 for face in face_report['faces']))
        self.assertEqual([face['outline'] for face in face_report['faces']],
                         ['glyf', 'CFF ', 'CFF2', 'glyf'])
        self.assertEqual(face_report['faces'][3]['mode'], 'retained-specialized')
        self.assertGreaterEqual(face_report['faces'][0]['uncovered'], 1)
        self.assertGreaterEqual(face_report['faces'][0]['retainedSharedSlots'], 1)

        # All-retained collections are real OEM bytes with an explicit proof,
        # replacing any prior generated single-face alias in the staged slot.
        emoji_report = json.loads((self.payload / '.luoshu-collection-proofs' / (
            hashlib.sha256(b'system/fonts/Emoji.ttc').hexdigest() + '.json'
        )).read_text(encoding='utf-8'))
        self.assertEqual(emoji_report['mode'], 'retained-original-collection')
        self.assertEqual(emoji_report['faces'][0]['mode'], 'retained-specialized')
        self.assertEqual(emoji_report['outputSha256'], hashlib.sha256(
            self.emoji_stock.read_bytes()).hexdigest())

    def test_required_proof_rejects_missing_stale_and_tampered_outputs(self):
        _, verified = self.run_payload_build()
        self.assertEqual(verified['result'], 'PASS')
        proof_root = self.payload / '.luoshu-collection-proofs'
        proof = proof_root / (hashlib.sha256(b'system/fonts/Core.ttc').hexdigest() + '.json')
        saved = proof.read_bytes()

        proof.unlink()
        with patch.object(builder.stock_inventory, 'load_inventory',
                           return_value=self.inventory), \
             patch.object(builder.stock_font_snapshot, 'resolve',
                          side_effect=lambda logical, module: self.targets.get(logical)):
            missing = contract.validate(self.payload, 'mix-fixture-123',
                                        self.module, require_proof=True,
                                        source=self.source)
        self.assertEqual(missing['result'], 'FAIL')

        proof.write_bytes(saved)
        with patch.object(builder.stock_inventory, 'load_inventory',
                           return_value=self.inventory), \
             patch.object(builder.stock_font_snapshot, 'resolve',
                          side_effect=lambda logical, module: self.targets.get(logical)):
            stale = contract.validate(self.payload, 'mix-other-request',
                                      self.module, require_proof=True,
                                      source=self.source)
        self.assertEqual(stale['result'], 'FAIL')

        proof.write_bytes(saved)
        original = self.target_path.read_bytes()
        self.target_path.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
        with patch.object(builder.stock_inventory, 'load_inventory',
                           return_value=self.inventory), \
             patch.object(builder.stock_font_snapshot, 'resolve',
                          side_effect=lambda logical, module: self.targets.get(logical)):
            tampered = contract.validate(self.payload, 'mix-fixture-123',
                                         self.module, require_proof=True,
                                         source=self.source)
        self.assertEqual(tampered['result'], 'FAIL')

    def test_required_proof_rejects_sidecar_contract_not_matching_stock(self):
        _, verified = self.run_payload_build()
        self.assertEqual(verified['result'], 'PASS')
        proof_root = self.payload / '.luoshu-collection-proofs'
        proof = proof_root / (hashlib.sha256(b'system/fonts/Core.ttc').hexdigest() + '.json')
        import json
        report = json.loads(proof.read_text(encoding='utf-8'))
        report['faces'][0]['contract']['frame'][0] += 1
        proof.write_text(json.dumps(report, separators=(',', ':')), encoding='utf-8')
        with patch.object(builder.stock_inventory, 'load_inventory',
                           return_value=self.inventory), \
             patch.object(builder.stock_font_snapshot, 'resolve',
                          side_effect=lambda logical, module: self.targets.get(logical)):
            tampered = contract.validate(self.payload, 'mix-fixture-123',
                                         self.module, require_proof=True,
                                         source=self.source)
        self.assertEqual(tampered['result'], 'FAIL')

    def test_color_glyph_faces_are_retained(self):
        self.assertTrue(builder.specialized({'COLR': object()}))

    def test_partial_mix_keeps_uncovered_oem_glyphs_and_passes_contract(self):
        with TTFont(self.source, recalcTimestamp=False) as donor:
            allowed = ASCII | {ord('中'), ord('家')}
            for table in donor['cmap'].tables:
                if table.isUnicode() and table.format != 14:
                    table.cmap = {cp: name for cp, name in table.cmap.items() if cp in allowed}
            donor.save(self.source)
        facts = builder.inspect_donor(self.source)
        self.assertFalse(facts['capabilities']['globalUiCandidate'])
        result, verified = self.run_payload_build()
        self.assertEqual(result['result'], 'PASS')
        self.assertEqual(verified['result'], 'PASS', verified['errors'])
        with TTFont(self.target_path, fontNumber=0, lazy=True) as generated:
            self.assertEqual(bounds(generated, '家')[0], 70)
            self.assertEqual(bounds(generated, '龘')[0], 20)
            self.assertEqual(bounds(generated, '.')[0], 20)

    def test_validate_cli_binds_actual_source_and_rejects_changed_donor(self):
        self.run_payload_build()
        arguments = ['composite_collection_build.py', 'validate',
                     '--module', str(self.module), '--payload', str(self.payload),
                     '--request', 'mix-fixture-123', '--require-proof',
                     '--source', str(self.source)]

        def run_cli():
            output = StringIO()
            with patch.object(sys, 'argv', arguments), \
                 patch.object(builder.stock_inventory, 'load_inventory', return_value=self.inventory), \
                 patch.object(builder.stock_font_snapshot, 'resolve',
                              side_effect=lambda logical, module: self.targets.get(logical)), \
                 redirect_stdout(output):
                return builder.main(), output.getvalue()

        code, output = run_cli()
        self.assertEqual(code, 0, output)
        with TTFont(self.source, recalcTimestamp=False) as donor:
            donor['OS/2'].usWeightClass = 500
            donor.save(self.source)
        code, output = run_cli()
        self.assertEqual(code, 1, output)
        self.assertIn('组合来源哈希已变化', output)

    def test_collection_donor_still_rejects_color_outline_inputs(self):
        original = builder.font_source_profile._table_contract

        def color_tables(font):
            result = original(font)
            result['color'] = ['COLR']
            return result

        with patch.object(builder.font_source_profile, '_table_contract', side_effect=color_tables):
            with self.assertRaisesRegex(ValueError, '彩色字体'):
                builder.inspect_donor(self.source)

    def test_source_change_aborts_before_replacing_previous_alias(self):
        output = self.root / 'previous.ttc'
        output.write_bytes(b'previous safe collection')
        original_sha = builder.sha
        calls = {'source': 0}

        def changed_source_digest(path):
            digest = original_sha(path)
            if Path(path) == self.source:
                calls['source'] += 1
                if calls['source'] == 2:
                    return '0' * 64
            return digest

        with patch.object(builder, 'sha', side_effect=changed_source_digest):
            with self.assertRaises(ValueError):
                builder.build(self.source, self.stock, output, self.work,
                              'mix-fixture-change', '/system/fonts/Core.ttc')
        self.assertEqual(output.read_bytes(), b'previous safe collection')

    def test_cff_and_cff2_mix_donors_are_actually_compiled(self):
        for kind in ('cff', 'cff2'):
            with self.subTest(donor=kind):
                donor = make_font(self.root / f'mix-{kind}.otf', kind=kind,
                                  donor=True, weight=600, family=f'Selected {kind}')
                output = self.root / f'output-{kind}.ttc'
                report = builder.build(
                    donor, self.stock, output, self.work,
                    f'mix-{kind}-request', '/system/fonts/Core.ttc')
                self.assertEqual(report['result'], 'PASS')
                self.assertEqual(builder.collection_faces(output), 4)
                for index in (0, 1, 2):
                    with TTFont(output, fontNumber=index, lazy=True) as generated:
                        self.assertEqual(bounds(generated, '家')[0], 70)
                        self.assertEqual(generated['OS/2'].usWeightClass,
                                         (400, 700, 300)[index])

    def test_collection_path_requires_a_verified_stock_snapshot(self):
        logical = '/system/fonts/Core.ttc'
        with patch.object(builder.stock_font_snapshot, 'resolve', return_value=None):
            with self.assertRaises(ValueError):
                builder.stock_path(self.module, logical)


if __name__ == '__main__':
    unittest.main(verbosity=2)
