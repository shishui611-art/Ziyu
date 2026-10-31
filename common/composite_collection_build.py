#!/usr/bin/env python3
"""Build an index-preserving CJK collection inside a finite font task.

Each stock face remains the base: cmap/UVS, glyph IDs, layout tables, names,
style and line metrics stay at their original indexes. Supported donor outlines
replace existing encoded slots. Specialized faces and uncovered glyphs stay stock.
No system XML, live font, user source or mounting implementation is modified.

Adapted from LuoShu's composite_collection_build.py (GPL-3.0); Ziyu integration
uses byte-verified stock snapshots and the selected, already-instanced mix donor.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from contextlib import nullcontext
import gc
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import sys
import tempfile
import time

from fontTools.misc.psCharStrings import T2CharString
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import DecomposingRecordingPen, RecordingPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.reverseContourPen import ReverseContourPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection, TTFont, TTLibError
from fontTools.ttLib.sfnt import writeTTCHeader
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from font_native_glyphs import packed_glyph
from fontTools.varLib.builder import buildVarData, buildVarIdxMap

from composite_collection_contract import collection_faces
import composite_collection_contract
from font_ligatures import plan_latin_ligatures
from composite_font import LATIN_CODEPOINTS, DIGIT_CODEPOINTS, _pick_face, _progress
import font_inventory as stock_inventory
import font_parallel
import font_clipping_compat
import font_source_profile
import stock_font_snapshot

SCHEMA = 'composite-collection-build-v1'
PROTECTED = ('cmap', 'GSUB', 'GPOS', 'GDEF', 'name', 'hhea', 'OS/2', 'vhea', 'vmtx', 'VORG',
             'fvar', 'avar', 'STAT', 'MVAR', 'BASE', 'cvar', 'VVAR')
METRIC_EXTREMA = {'hhea': ('advanceWidthMax', 'minLeftSideBearing', 'minRightSideBearing', 'xMaxExtent'),
                  'vhea': ('advanceHeightMax', 'minTopSideBearing', 'minBottomSideBearing', 'yMaxExtent')}
CJK_RANGES = ((0x2E80, 0x31EF), (0x3400, 0x9FFF), (0xF900, 0xFAFF),
              (0xFF00, 0xFFEF), (0x20000, 0x323AF))
MAX_COLLECTION_TARGETS = 16
MAX_COLLECTION_BYTES = 384 * 1024 * 1024
MAX_COLLECTION_FACE_TOTAL = 512
MAX_STOCK_FILE_BYTES = 128 * 1024 * 1024
MAX_OUTPUT_FACE_BYTES = 128 * 1024 * 1024
MAX_MIX_SOURCE_BYTES = 128 * 1024 * 1024
PROOF_DIRECTORY = '.luoshu-collection-proofs'
MAX_GLYPH_TEMPLATE_BYTES = 64 * 1024 * 1024
# Only tables which replace_face does not intentionally mutate. The complete
# canonical contract is checked before any loaded object is discarded.
RAW_PRESERVABLE = frozenset(('cmap', 'GSUB', 'GPOS', 'GDEF', 'name', 'OS/2',
                             'fvar', 'avar', 'STAT', 'MVAR', 'BASE', 'cvar'))


class _ExactCubicT2Pen(T2CharStringPen):
    """Elevate each quadratic exactly before the existing Type 2 rounding.

    BasePen supplies the quadratic-to-cubic degree elevation, including implied
    points. Avoid Qu2CuPen's costly search for an approximate merged spline.
    Keep the previous unsupported-outline fallback for off-curve-only paths;
    broadening supported outlines is separate from this performance change.
    """

    def qCurveTo(self, *points):
        if points and points[-1] is None:
            raise NotImplementedError('off-curve-only contours retain the OEM glyph')
        super().qCurveTo(*points)


class _BoundedCollectionStream:
    """Limit file extent, including SFNT directory seeks and table padding."""

    def __init__(self, stream, limit):
        self.stream, self.limit = stream, limit

    def write(self, data):
        if self.stream.tell() + len(data) > self.limit:
            raise ValueError(f'生成后的字体集合超出容量预算（{self.limit} 字节）')
        return self.stream.write(data)

    def seek(self, offset, whence=0):
        previous = self.stream.tell()
        position = self.stream.seek(offset, whence)
        if position > self.limit:
            self.stream.seek(previous)
            raise ValueError(f'生成后的字体集合超出容量预算（{self.limit} 字节）')
        return position

    def tell(self):
        return self.stream.tell()


def _save_collection(fonts, path, limit):
    # TTCollection.save(file_object) buffers the complete TTC in BytesIO.
    # Use the same FontTools SFNT writer and whole-table sharing directly on
    # disk instead, so the capacity check runs before each extending write.
    with Path(path).open('wb') as stream:
        bounded = _BoundedCollectionStream(stream, limit)
        directory = writeTTCHeader(bounded, len(fonts))
        offsets, shared_tables = [], {}
        for font in fonts:
            offsets.append(bounded.tell())
            font._save(bounded, tableCache=shared_tables)
            bounded.seek(0, 2)
        bounded.seek(directory)
        bounded.write(struct.pack(f'>{len(offsets)}L', *offsets))


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def canonical(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def identity(path):
    value = Path(path).stat()
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def role(cp):
    if cp in DIGIT_CODEPOINTS:
        return 'digit'
    if cp in LATIN_CODEPOINTS:
        return 'latin'
    return 'cjk' if any(lo <= cp <= hi for lo, hi in CJK_RANGES) else None


def protected(font, stock_order=None, *, skip_tables=(), capture_bounds=False,
              bounds_reference=None):
    recalc = font.recalcBBoxes
    font.recalcBBoxes = False
    try:
        return _protected(font, stock_order, skip_tables, capture_bounds, bounds_reference)
    finally:
        font.recalcBBoxes = recalc


def _glyph_ymax(glyf, name):
    # Checking vertical origins needs the stored header, not all coordinates.
    # Leave unopened OEM glyphs packed so saving can copy their exact bytes.
    glyph = glyf.glyphs[name]
    raw = getattr(glyph, 'data', None)
    if raw is not None:
        if not raw:
            return 0
        if len(raw) >= 10:
            return struct.unpack_from('>h', raw, 8)[0]
    return getattr(glyf[name], 'yMax', 0)


def _metric_extrema(font):
    return {tag: [int(getattr(font[tag], field)) for field in fields]
            for tag, fields in METRIC_EXTREMA.items() if tag in font}


def _extrema_expand(before, after):
    return (isinstance(before, (list, tuple)) and isinstance(after, (list, tuple))
            and len(before) == len(after) == 4
            and all(isinstance(value, int) for value in (*before, *after))
            and after[0] >= before[0] and after[1] <= before[1]
            and after[2] <= before[2] and after[3] >= before[3])


def _protected(font, stock_order=None, skip_tables=(), capture_bounds=False,
               bounds_reference=None):
    order = font.getGlyphOrder()
    if stock_order is not None:
        if order[:len(stock_order)] != stock_order:
            raise ValueError('原厂字形索引顺序在生成时发生变化')
        order = stock_order
    # Canonical compiled bytes survive a FontTools round trip. Keeping the
    # original glyph order makes the retained layout/UVS indexes meaningful.
    # getTableData returns original bytes for lazy tables, compiled bytes for
    # loaded ones. Load every protected table before hashing either snapshot.
    for tag in PROTECTED:
        if tag not in skip_tables and tag in font:
            font[tag]
    extrema = _metric_extrema(font) if capture_bounds or bounds_reference is not None else None
    if bounds_reference is not None:
        reference_extrema = bounds_reference.get('metricExtrema')
        if (not isinstance(reference_extrema, dict) or reference_extrema.keys() != extrema.keys()
                or any(not _extrema_expand(reference_extrema[tag], values)
                       for tag, values in extrema.items())):
            raise ValueError('全局度量统计只能扩展原厂范围')
    tables = {}
    for tag in PROTECTED:
        if tag in skip_tables or tag not in font:
            continue
        if tag == 'VVAR' and stock_order is not None:
            # Appended component copies need extra maps, while every original
            # glyph must keep the same effective row and unchanged delta store.
            table = font[tag].table
            store = table.VarStore
            maps = {}
            for field in ('AdvHeightMap', 'TsbMap', 'BsbMap', 'VOrgMap'):
                mapping = getattr(table, field, None)
                maps[field] = ([mapping.mapping[name] for name in order] if mapping is not None else
                               list(range(len(order))) if field == 'AdvHeightMap' else None)
            tables[tag] = canonical({'version': table.Version, 'maps': maps,
                'storeFormat': store.Format, 'axes': store.VarRegionList.RegionAxisCount,
                'regions': [[(axis.StartCoord, axis.PeakCoord, axis.EndCoord)
                             for axis in region.VarRegionAxis]
                            for region in store.VarRegionList.Region],
                'data': [(item.VarRegionIndex, item.Item) for item in store.VarData]})
            continue
        if tag == 'vmtx' and 'glyf' in font:
            # TrueType vertical origins are yMax + top side bearing. A new
            # outline needs a different bearing to keep that same origin.
            glyf = font['glyf']
            tables[tag] = canonical({name: [font['vmtx'][name][0],
                                          _glyph_ymax(glyf, name) + font['vmtx'][name][1]]
                                     for name in order})
            continue
        data = font.getTableData(tag)
        # Metric serialization may change its run-length compression count.
        # Every other header field still binds the original line metrics.
        if tag in ('hhea', 'vhea'):
            if bounds_reference is not None:
                # These four values describe extrema, not ascent/descent or
                # line spacing. Bind every other header byte to the OEM proof.
                data = data[:10] + struct.pack('>Hhhh', *reference_extrema[tag]) + data[18:]
            data = data[:-2]
        tables[tag] = hashlib.sha256(data).hexdigest()
    frame = [font['head'].unitsPerEm, font['head'].xMin, font['head'].yMin,
             font['head'].xMax, font['head'].yMax]
    if bounds_reference is not None:
        before = bounds_reference.get('frame')
        if (not isinstance(before, list) or len(before) != 5 or before[0] != frame[0]
                or frame[1] > before[1] or frame[2] > before[2]
                or frame[3] < before[3] or frame[4] < before[4]):
            raise ValueError('全局轮廓边界只能扩展原厂范围')
        frame, extrema = before, reference_extrema
    result = {'tables': tables, 'glyphOrder': canonical(order), 'frame': frame}
    if extrema is not None:
        result['metricExtrema'] = extrema
    return result


def raw_table_hashes(font):
    """Snapshot only known invariant tables from this face's original reader."""
    if font.reader is None:
        return {}
    return {tag: hashlib.sha256(font.reader[tag]).hexdigest()
            for tag in sorted(RAW_PRESERVABLE) if tag in font.reader}


def contract_matches(font, expected, raw_tables=None, stock_order=None, *,
                     allow_bounds_expansion=False):
    """Compare an unopened output/stock face to a byte-preservation proof.

    A reader still exposes old bytes after an in-memory change. Refuse raw
    verification for already-loaded proof tables instead of overlooking edits.
    """
    if raw_tables is None:
        raw_tables = {}
    if (not isinstance(raw_tables, dict) or not set(raw_tables).issubset(RAW_PRESERVABLE)
            or any(not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value)
                   for value in raw_tables.values())):
        raise ValueError('原始字体表证明无效')
    if raw_tables:
        if font.reader is None:
            return False
        for tag, digest in raw_tables.items():
            if (font.isLoaded(tag) or tag not in font.reader
                    or hashlib.sha256(font.reader[tag]).hexdigest() != digest):
                return False
    try:
        actual = protected(font, stock_order, skip_tables=raw_tables,
                           capture_bounds='metricExtrema' in expected,
                           bounds_reference=expected if allow_bounds_expansion else None)
    except (ValueError, TypeError, struct.error):
        return False
    trimmed = dict(expected)
    trimmed['tables'] = {tag: value for tag, value in expected['tables'].items()
                         if tag not in raw_tables}
    return actual == trimmed


def specialized(font):
    # Color glyphs can draw from auxiliary tables or embedded bitmap/SVG
    # assets. Replacing only their base outline would silently change the
    # glyph while leaving those color layers stale, so preserve the OEM face.
    if any(tag in font for tag in font_source_profile.COLOR_TABLES):
        return True
    names = font['name'] if 'name' in font else None
    label = ' '.join(filter(None, (names.getDebugName(i) for i in (1, 2, 16, 17)))) if names else ''
    return (bool(font['post'].isFixedPitch) if 'post' in font else False) or any(
        word in label.lower() for word in ('mono', 'serif', 'emoji', 'symbol', 'icon')) or bool(
        font['head'].macStyle & 2 or ('OS/2' in font and font['OS/2'].fsSelection & 1))


def target_weight(font):
    return font_source_profile._weight(font)


def face_identity(font):
    return {
        'weight': target_weight(font),
        'style': font_source_profile._best_subfamily(font),
        'axes': font_source_profile._axes(font),
    }


def draw_record(glyphs, name):
    pen = DecomposingRecordingPen(glyphs)
    glyphs[name].draw(pen)
    return pen


def guard_retained_components(font, mapping):
    if 'glyf' in font:
        glyf = font['glyf']
        for name in font.getGlyphOrder():
            if name in mapping:
                continue  # Its complete outline will be replaced.
            raw = getattr(glyf.glyphs[name], 'data', None)
            if raw is not None and (len(raw) < 2 or int.from_bytes(raw[:2], 'big', signed=True) >= 0):
                continue
            glyph = glyf[name]
            if glyph.isComposite() and any(component.glyphName in mapping for component in glyph.components):
                raise ValueError('原厂保留组合字形依赖被替换槽，当前不能保持其完整形状与边界')
    elif 'CFF ' in font and not hasattr(font['CFF '].cff.topDictIndex[0], 'ROS'):
        # Non-CID CFF can have seac components addressed by standard names.
        glyphs = font.getGlyphSet()
        for name in font.getGlyphOrder():
            if name in mapping:
                continue
            pen = RecordingPen()
            glyphs[name].draw(pen)
            if any(operation == 'addComponent' and arguments[0] in mapping
                   for operation, arguments in pen.value):
                raise ValueError('原厂保留 CFF 组合字形依赖被替换槽，当前不能保持其完整形状与边界')


def freeze_horizontal_variations(font, mapping):
    """Detach only replaced slots from stock width/side-bearing variation data.

    Keep the existing store and each retained glyph's exact indices. In
    particular, an implicit advance mapping must be expanded before changing
    selected slots; editing a shared delta row would corrupt uncovered glyphs.
    """
    if 'HVAR' not in font:
        return
    table = font['HVAR'].table
    store = table.VarStore
    if len(store.VarData) >= 65535:
        raise ValueError('水平变化存储没有可用索引')
    zero = len(store.VarData) << 16
    store.VarData.append(buildVarData([], [[]], optimize=False))
    store.VarDataCount = len(store.VarData)
    order = font.getGlyphOrder()
    for field in ('AdvWidthMap', 'LsbMap', 'RsbMap'):
        old = getattr(table, field, None)
        if old is None and field != 'AdvWidthMap':
            continue
        indices = [zero if name in mapping else (old.mapping[name] if old else index)
                   for index, name in enumerate(order)]
        setattr(table, field, buildVarIdxMap(indices, order))


def retained_vertical_deltas(font, name):
    if 'gvar' not in font or 'vmtx' not in font:
        return []
    if 'VVAR' in font and getattr(font['VVAR'].table, 'TsbMap', None) is not None:
        raise ValueError('可变 TrueType 垂直侧距映射需要独立重编译，不能沿用旧轮廓数据')
    variations = font['gvar'].variations.get(name, [])
    if not variations:
        return []
    glyf = font['glyf']
    glyph = glyf.glyphs[name]
    raw = getattr(glyph, 'data', None)
    if raw is not None and not raw:
        point_count = 0
    elif raw is not None and len(raw) >= 10 and struct.unpack_from('>h', raw)[0] >= 0:
        contours = struct.unpack_from('>h', raw)[0]
        if len(raw) < 10 + contours * 2:
            raise ValueError('原厂可变字形端点数据不完整')
        point_count = struct.unpack_from('>H', raw, 8 + contours * 2)[0] + 1 if contours else 0
    else:
        glyph = glyf[name]
        point_count = (len(glyph.components) if glyph.isComposite()
                       else len(glyph.getCoordinates(glyf)[0]))
    values = []
    for variation in variations:
        if len(variation.coordinates) != point_count + 4 or variation.getCoordWidth() == 1:
            raise ValueError('原厂可变字形度量点数量不一致')
        # IUP treats each of the four phantom points as its own one-point
        # contour. An omitted metric delta is exactly (0, 0), independent of
        # every outline coordinate. No deepcopy or outline interpolation is
        # needed to preserve the two vertical phantom points.
        top, bottom = (delta if delta is not None else (0, 0)
                       for delta in variation.coordinates[-2:])
        if any(top) or any(bottom):
            values.append((variation.axes, top, bottom))
    return values


def _dependency_glyph_contract(font, name, restore_names):
    """Compare retained outlines, hinting and variations after a font round trip."""
    glyph = deepcopy(font['glyf'][name])
    if glyph.isComposite():
        for component in glyph.components:
            component.glyphName = restore_names.get(component.glyphName, component.glyphName)
    variations = []
    if 'gvar' in font:
        variations = [(item.axes, item.coordinates)
                      for item in font['gvar'].variations.get(name, [])]
    maps = {}
    index = font.getGlyphID(name)
    for tag, fields in (('HVAR', ('AdvWidthMap', 'LsbMap', 'RsbMap')),
                        ('VVAR', ('AdvHeightMap', 'TsbMap', 'BsbMap', 'VOrgMap'))):
        if tag not in font:
            continue
        table = font[tag].table
        for field in fields:
            mapping = getattr(table, field, None)
            maps[tag + '.' + field] = (mapping.mapping[name] if mapping is not None else
                                      index if field in {'AdvWidthMap', 'AdvHeightMap'} else None)
    return canonical({'outline': hashlib.sha256(
        glyph.compile(font['glyf'], recalcBBoxes=False)).hexdigest(),
        'horizontal': font['hmtx'][name],
        'vertical': font['vmtx'][name] if 'vmtx' in font else None,
        'variations': variations, 'maps': maps})


def verify_dependency_copies(font, proof):
    if not proof:
        return
    order = font.getGlyphOrder()
    if len(order) != proof['originalCount'] + len(proof['helpers']):
        raise ValueError('原厂部件副本的字形清单不一致')
    # post format 3 does not store glyph names. Bind the proof to stable glyph
    # IDs so FontTools' names for newly appended, unencoded glyphs may differ.
    restore = {order[proof['originalCount'] + i]: source
               for i, source in enumerate(proof['helpers'].values())}
    for index, expected in proof['contracts'].items():
        if _dependency_glyph_contract(font, order[int(index)], restore) != expected:
            raise ValueError('原厂保留字形或部件副本在生成时发生变化')


def copy_retained_dependencies(font, mapping):
    """Keep OEM compounds independent of the slots receiving donor outlines.

    Append private copies; all original glyph IDs, cmap and layout references
    stay fixed. Copy nested components and their variation/metric mappings too.
    Only the retained compounds' component references change, never their shape.
    """
    glyf = font['glyf']
    order = list(font.getGlyphOrder())
    retained = {}
    for name in order:
        if name in mapping:
            continue
        raw = getattr(glyf.glyphs[name], 'data', None)
        if raw is not None and (len(raw) < 2 or int.from_bytes(raw[:2], 'big', signed=True) >= 0):
            continue
        glyph = glyf[name]
        if glyph.isComposite() and any(c.glyphName in mapping for c in glyph.components):
            retained[name] = deepcopy(glyph)
    if not retained:
        return None
    needed, pending = set(), [c.glyphName for glyph in retained.values()
                              for c in glyph.components if c.glyphName in mapping]
    while pending:
        name = pending.pop()
        if name in needed:
            continue
        needed.add(name)
        glyph = glyf[name]
        if glyph.isComposite():
            pending.extend(c.glyphName for c in glyph.components if c.glyphName in mapping)
    if len(order) + len(needed) > 65535:
        # A full SFNT has no room for private copies. Retain only the affected
        # dependency chain, rather than failing this font or the entire switch.
        users = {}
        for name in order:
            glyph = glyf[name]
            if glyph.isComposite():
                for component in glyph.components:
                    users.setdefault(component.glyphName, set()).add(name)
        pending = [name for name, parents in users.items() if parents.difference(mapping)]
        while pending:
            name = pending.pop()
            if name not in mapping:
                continue
            mapping.pop(name)
            glyph = glyf[name]
            if glyph.isComposite():
                pending.extend(c.glyphName for c in glyph.components if c.glyphName in mapping)
        return {'originalCount': len(order), 'helpers': {}, 'contracts': {},
                'reason': 'stock-glyph-capacity-preserved'}
    copies, helpers, visiting = {}, {}, set()
    expected = {name: _dependency_glyph_contract(font, name, {}) for name in retained}

    def clone(name):
        if name in visiting:
            raise ValueError('原厂组合字形部件存在循环引用')
        if name in copies:
            return copies[name]
        if len(order) + len(copies) >= 65535:
            raise ValueError('原厂字形与保留部件超过 SFNT 字形数限制')
        visiting.add(name)
        expected[name] = _dependency_glyph_contract(font, name, {})
        helper = f'ziyu.stock.{len(copies)}'
        while helper in glyf or helper in helpers:
            helper += '_'
        copies[name] = helper
        helpers[helper] = name
        glyph = deepcopy(glyf[name])
        # A copied compound must never refer back to an original slot which
        # will receive donor outlines. Unchanged components can still be shared.
        if glyph.isComposite():
            for component in glyph.components:
                if component.glyphName in mapping:
                    component.glyphName = clone(component.glyphName)
        glyf[helper] = glyph
        font['hmtx'][helper] = font['hmtx'][name]
        if 'vmtx' in font:
            font['vmtx'][helper] = font['vmtx'][name]
        if 'gvar' in font:
            font['gvar'].variations[helper] = deepcopy(font['gvar'].variations.get(name, []))
        visiting.remove(name)
        return helper

    for glyph in retained.values():
        for component in glyph.components:
            if component.glyphName in mapping:
                component.glyphName = clone(component.glyphName)
    font.setGlyphOrder(order + list(helpers))
    if 'post' in font and font['post'].formatType not in {2.0, 3.0}:
        # Fixed Macintosh/Unicode name lists cannot describe appended private
        # glyphs. Format 2 preserves the existing names and font-wide metadata.
        font['post'].formatType = 2.0
        font['post'].extraNames = []
        font['post'].mapping = {}
    # Implicit advance maps use glyph IDs. New private IDs must explicitly point
    # to the original component's variation row, rather than past the old store.
    for tag, fields in (('HVAR', ('AdvWidthMap', 'LsbMap', 'RsbMap')),
                        ('VVAR', ('AdvHeightMap', 'TsbMap', 'BsbMap', 'VOrgMap'))):
        if tag not in font:
            continue
        table = font[tag].table
        for field in fields:
            old = getattr(table, field, None)
            if old is None and field not in {'AdvWidthMap', 'AdvHeightMap'}:
                continue
            original = {name: old.mapping[name] if old is not None else i
                        for i, name in enumerate(order)}
            indices = [original[name] for name in order]
            indices.extend(original[name] for name in helpers.values())
            setattr(table, field, buildVarIdxMap(indices, font.getGlyphOrder()))
    for name, glyph in retained.items():
        glyf[name] = glyph
    contracts = {font.getGlyphID(name): expected[name] for name in retained}
    contracts.update({font.getGlyphID(helper): expected[source] for helper, source in helpers.items()})
    proof = {'originalCount': len(order), 'helpers': helpers,
             'contracts': contracts}
    verify_dependency_copies(font, proof)
    return proof


def _grow_output_bounds(font, mapping, cff_bounds):
    """Extend descriptive bounds; keep line spacing and each vertical origin."""
    head, horizontal = font['head'], font['hhea']
    before_frame = [int(head.unitsPerEm), int(head.xMin), int(head.yMin),
                    int(head.xMax), int(head.yMax)]
    before_extrema = _metric_extrema(font)
    vertical = font['vhea'] if 'vhea' in font and 'vmtx' in font else None
    cff_top = font['CFF '].cff.topDictIndex[0] if 'CFF ' in font else None
    for name in mapping:
        width, bearing = font['hmtx'][name]
        horizontal.advanceWidthMax = max(horizontal.advanceWidthMax, width)
        if 'glyf' in font:
            # Imported outlines have a checked header already. __getitem__
            # expands native bytes even when only this bounding box is needed.
            glyph = font['glyf'].glyphs[name]
            bounds = ([glyph.xMin, glyph.yMin, glyph.xMax, glyph.yMax]
                      if glyph.numberOfContours else None)
        else:
            bounds = cff_bounds.get(name)
        if bounds is None:
            continue
        x0, y0, x1, y1 = (math.floor(bounds[0]), math.floor(bounds[1]),
                           math.ceil(bounds[2]), math.ceil(bounds[3]))
        head.xMin, head.yMin = min(head.xMin, x0), min(head.yMin, y0)
        head.xMax, head.yMax = max(head.xMax, x1), max(head.yMax, y1)
        horizontal.minLeftSideBearing = min(horizontal.minLeftSideBearing, bearing)
        horizontal.minRightSideBearing = min(horizontal.minRightSideBearing, width - bearing - (x1 - x0))
        horizontal.xMaxExtent = max(horizontal.xMaxExtent, bearing + x1 - x0)
        if vertical is not None:
            advance, top = font['vmtx'][name]
            vertical.advanceHeightMax = max(vertical.advanceHeightMax, advance)
            vertical.minTopSideBearing = min(vertical.minTopSideBearing, top)
            vertical.minBottomSideBearing = min(vertical.minBottomSideBearing, advance - top - (y1 - y0))
            vertical.yMaxExtent = max(vertical.yMaxExtent, top + y1 - y0)
        if cff_top is not None:
            bbox = cff_top.FontBBox
            cff_top.FontBBox = [min(bbox[0], x0), min(bbox[1], y0),
                               max(bbox[2], x1), max(bbox[3], y1)]
    after_frame = [int(head.unitsPerEm), int(head.xMin), int(head.yMin), int(head.xMax), int(head.yMax)]
    after_extrema = _metric_extrema(font)
    if any(value < -32768 or value > 32767 for value in after_frame[1:]):
        raise ValueError('替换字形超出全局边界字段范围')
    for values in after_extrema.values():
        if not 0 <= values[0] <= 65535 or any(not -32768 <= value <= 32767 for value in values[1:]):
            raise ValueError('替换字形超出全局度量统计字段范围')
    return {'fontFrameExpansion': {'before': before_frame, 'after': after_frame},
            'metricExtremaExpansion': {'before': before_extrema, 'after': after_extrema}}


def replace_face(font, donor, recordings, *, require_cjk=True, preserve_dependencies=False,
                 preserve_unsafe=False, outline_cache=None, native_source=None,
                 expand_bounds=False):
    target = face_identity(font)
    donor_info = face_identity(donor)
    donor_info['weight'] = target_weight(donor)
    donor_info['axes'] = font_source_profile._axes(donor)
    donor_report = {
        'targetWeight': target['weight'], 'targetStyle': target['style'],
        'donorWeight': donor_info['weight'], 'donorAxes': donor_info['axes'],
        'selectedAxesTraceable': False,
        'selectedAxesNote': '组合产物已静态绑定生成时的选择；原始选轴值未保存在组合库清单中',
    }
    kind = 'glyf' if 'glyf' in font else 'CFF ' if 'CFF ' in font else 'CFF2' if 'CFF2' in font else None
    if specialized(font):
        return {'mode': 'retained-specialized', 'replaced': {}, 'uncovered': 0,
                'outline': kind, **donor_report, 'targetStylePreserved': True}
    if kind is None:
        raise ValueError('本机 CJK 集合包含不支持的轮廓')
    cmap, source = font.getBestCmap() or {}, donor.getBestCmap() or {}
    source_glyphs = donor.getGlyphSet()
    donor_metrics = donor['hmtx'].metrics
    clipping = font_clipping_compat.plan(font, donor, outline_cache, recordings)
    scale = font['head'].unitsPerEm / donor['head'].unitsPerEm * clipping['scale']
    baseline = clipping['baseline']

    def transformed_bounds(bounds):
        if bounds is None:
            return None
        x0, y0, x1, y1 = bounds
        return (x0 * scale, y0 * scale + baseline, x1 * scale, y1 * scale + baseline)
    converted = {}
    # Recordings belong to this donor and are not changed by replacement.
    # Reuse only the raw outline bounds between faces with the same outline kind
    # and target scale. Variable TrueType applies a one-unit origin correction,
    # so its bounds must never reuse a static target's uncorrected bounds.
    if outline_cache is None:
        outline_bounds = {}
    else:
        outline_bounds = outline_cache.setdefault((kind, scale, baseline, kind == 'glyf' and 'fvar' in font), {})

    # Cache immutable, already-rounded simple glyph bytes by source and UPEM
    # scale. Each target gets its own Glyph object; variation/metric edits never
    # leak between stock faces. Scope is one donor in one finite worker.
    templates = (outline_cache.setdefault(('glyf-templates', scale, baseline, 'fvar' in font), {})
                 if outline_cache is not None and kind == 'glyf' else {})
    cff_templates = (outline_cache.setdefault(('cff-templates', kind, scale, baseline), {})
                     if outline_cache is not None and kind != 'glyf' else {})

    native_cache, native_report = None, {'engine': 'fonttools'}
    if (not clipping['transformed'] and kind == 'glyf' and native_source is not None and outline_cache is not None
            and font['maxp'].numGlyphs >= 512):
        from font_native_glyphs import prepare as native_glyphs
        native_key = ('native-glyphs', font['head'].unitsPerEm)
        if native_key not in outline_cache:
            remaining = (MAX_GLYPH_TEMPLATE_BYTES - outline_cache.get('nativeGlyphBytes', 0) - outline_cache.get('nativeCffBytes', 0)
                         - outline_cache.get('templateBytes', 0))
            outline_cache[native_key] = native_glyphs(donor, native_source, font['head'].unitsPerEm, budget=remaining)
            if outline_cache[native_key][0] is not None:
                outline_cache['nativeGlyphBatches'] = outline_cache.get('nativeGlyphBatches', 0) + 1
                outline_cache['nativeGlyphBytes'] = (outline_cache.get('nativeGlyphBytes', 0)
                                                     + outline_cache[native_key][0].byte_size)
        native_cache, native_report = outline_cache[native_key]

    native_cff = None
    if not clipping['transformed'] and kind != 'glyf' and native_source is not None and outline_cache is not None:
        from font_native_cff import prepare as prepare_cff
        cff_key = ('native-cff', font['head'].unitsPerEm)
        if cff_key not in outline_cache:
            remaining = (MAX_GLYPH_TEMPLATE_BYTES - outline_cache.get('nativeGlyphBytes', 0)
                         - outline_cache.get('nativeCffBytes', 0) - outline_cache.get('templateBytes', 0))
            outline_cache[cff_key] = prepare_cff(donor, native_source, font['head'].unitsPerEm,
                                                budget=remaining)
            if outline_cache[cff_key][0] is not None:
                outline_cache['nativeCffBytes'] = (outline_cache.get('nativeCffBytes', 0)
                                                  + outline_cache[cff_key][0].byte_size)
        native_cff, native_report = outline_cache[cff_key]

    def converted_glyph(source_name):
        if native_cache is not None and source_name in native_cache.ids:
            glyph = native_cache.packed_glyph(source_name)
            if glyph is not None:
                if glyph.numberOfContours:
                    bearing = round(donor_metrics[source_name][1] * scale)
                    shift = bearing - glyph.xMin
                    if 'fvar' in font and abs(shift) == 1:
                        glyph.expand(font['glyf'])
                        glyph.coordinates.translate((shift, 0))
                        glyph.recalcBounds(font['glyf'])
                        glyph = packed_glyph(glyph.compile(font['glyf'], recalcBBoxes=False))
                outline_cache['nativeGlyphHits'] = outline_cache.get('nativeGlyphHits', 0) + 1
                return glyph
        data = templates.get(source_name)
        if data is not None:
            outline_cache['templateHits'] = outline_cache.get('templateHits', 0) + 1
            return packed_glyph(data)
        pen = TTGlyphPen(None)
        if source_name not in recordings:
            recordings[source_name] = draw_record(source_glyphs, source_name)
        output = Cu2QuPen(pen, max_err=max(0.5, font['head'].unitsPerEm / 2000),
                          reverse_direction='glyf' not in donor)
        recordings[source_name].replay(TransformPen(output, (scale, 0, 0, scale, 0, baseline)))
        glyph = pen.glyph()
        glyph.recalcBounds(font['glyf'])
        if 'fvar' in font and glyph.numberOfContours > 0:
            # Decomposition and integer rounding can move xMin by one unit
            # relative to the source advance origin. Variable TrueType needs
            # xMin == lsb so its phantom points stay stable. Correct only this
            # rounding discrepancy; larger disagreements remain protected by
            # the existing per-glyph safety checks below.
            bearing = round(donor_metrics[source_name][1] * scale)
            shift = bearing - glyph.xMin
            if abs(shift) == 1:
                glyph.coordinates.translate((shift, 0))
                glyph.recalcBounds(font['glyf'])
        if outline_cache is not None:
            used = outline_cache.get('templateBytes', 0)
            limit = MAX_GLYPH_TEMPLATE_BYTES - outline_cache.get('nativeGlyphBytes', 0) - outline_cache.get('nativeCffBytes', 0)
            if used + len(source_name) * 4 + 256 >= limit:
                return glyph
            try:
                data = glyph.compile(font['glyf'], recalcBBoxes=False)
            except (ValueError, OverflowError, struct.error, AssertionError, NotImplementedError):
                # Preflight still needs this glyph's bounds to decide whether
                # to retain the OEM slot. A cache serialization failure must
                # not bypass that decision or turn a retained slot into failure.
                return glyph
            # Include dictionary/key overhead; keep the worker's extra cache finite.
            cost = len(data) + len(source_name) * 4 + 256
            if used + cost <= limit:
                templates[source_name] = data
                outline_cache['templateBytes'] = used + cost
                return packed_glyph(data)
        return glyph

    def bounds_for(source_name):
        if source_name not in outline_bounds:
            if native_cff is not None and native_cff.supports(source_name):
                outline_bounds[source_name] = native_cff.bounds(source_name)
                return outline_bounds[source_name]
            if source_name not in recordings and (kind != 'glyf' or native_cache is None):
                recordings[source_name] = draw_record(source_glyphs, source_name)
            if kind == 'glyf':
                # TrueType metrics bind the integer control-point bounding box,
                # not the tight Bezier bounds. Use the same converted glyph in
                # preflight and publication, including rounding during cu2qu.
                glyph = converted_glyph(source_name)
                converted[(source_name,)] = glyph
                outline_bounds[source_name] = (
                    (glyph.xMin / scale, (glyph.yMin - baseline) / scale,
                     glyph.xMax / scale, (glyph.yMax - baseline) / scale)
                    if glyph.numberOfContours else None)
            else:
                pen = BoundsPen(None)
                recordings[source_name].replay(pen)
                outline_bounds[source_name] = pen.bounds
        return outline_bounds[source_name]


    mapping, counts, uncovered = {}, dict(cjk=0, latin=0, digit=0), 0
    retained_conflicts = set()
    shared_conflicts = {}
    for cp, target_name in sorted(cmap.items()):
        category = role(cp)
        if not category:
            continue
        source_name = source.get(cp)
        if not source_name:
            uncovered += 1
            continue
        if target_name in mapping and mapping[target_name] != source_name:
            previous = mapping[target_name]
            for name in (previous, source_name):
                if name not in recordings:
                    recordings[name] = draw_record(source_glyphs, name)
            if (recordings[previous].value != recordings[source_name].value or
                    donor_metrics[previous] != donor_metrics[source_name]):
                # One OEM glyph cannot carry two different donor outlines or
                # advances (for example a stock space shared by U+0020 and
                # U+00A0 when the donor gives them separate glyphs). Keeping
                # that single OEM slot is the only lossless choice; the rest of
                # the collection must not be abandoned because of it.
                retained_conflicts.add(target_name)
                observed = shared_conflicts.setdefault(target_name, [])
                if len(observed) < 8:
                    observed.append(cp)
                continue
        mapping[target_name] = source_name
        counts[category] += 1
    # A covered character can share its glyph ID with an uncovered character
    # (or a character outside these roles). Keep that whole slot stock.
    blocked = {name for table in font['cmap'].tables
               if table.isUnicode() and table.format != 14
               for cp, name in table.cmap.items()
               if not role(cp) or (cp not in source and not 0xFB00 <= cp <= 0xFB06)}
    blocked.update(retained_conflicts)
    # Preserve non-default UVS targets: their format-14 entry names a glyph
    # directly, and that glyph can be a variant that is not in the base cmap.
    # Default UVS entries resolve through the base cmap instead, so their glyph
    # is an ordinary coded glyph: replacing its outline keeps the glyph ID and
    # the format-14 bytes untouched, and a whole-font transplant is expected to
    # restyle that glyph. Only a non-default target therefore stays stock.
    for table in font['cmap'].tables:
        if table.format != 14:
            continue
        for entries in getattr(table, 'uvsDict', {}).values():
            for codepoint, glyph_name in entries:
                if glyph_name:
                    blocked.add(glyph_name)
    retained_shared = len(blocked.intersection(mapping))
    for name in blocked:
        mapping.pop(name, None)
    ligatures, source_glyphs, donor_metrics = plan_latin_ligatures(
        font, donor, mapping, source_glyphs, blocked)
    mapping.update(ligatures)
    retained_unsafe = 0
    unsafe_details = []
    if preserve_unsafe:
        frame, header = font['head'], font['hhea']
        reverse_cmap = None
        for name, source_name in list(mapping.items()):
            bounds = bounds_for(source_name)
            width, bearing = (round(value * scale) for value in donor_metrics[source_name])
            reasons = []
            if name == '.notdef':
                reasons.append('.notdef')
            if not 0 <= width <= 65535 or not -32768 <= bearing <= 32767:
                reasons.append('sfnt-range')
            if 'gvar' in font and 'vmtx' in font and 'VVAR' in font:
                if getattr(font['VVAR'].table, 'TsbMap', None) is not None:
                    reasons.append('VVAR-TsbMap')
            outline_width = 0
            if bounds is None and kind == 'glyf' and 'fvar' in font:
                if bearing != 0:
                    reasons.append('empty-glyph-bearing')
            if bounds is not None:
                x0, y0, x1, y1 = transformed_bounds(bounds)
                outline_width = x1 - x0
                if expand_bounds and any(value < -32768 or value > 32767 for value in (x0, y0, x1, y1)):
                    reasons.append('frame-range')
                if x0 < frame.xMin - 1 and not expand_bounds:
                    reasons.append('frame-xMin')
                if y0 < frame.yMin - 1 and not expand_bounds:
                    reasons.append('frame-yMin')
                if x1 > frame.xMax + 1 and not expand_bounds:
                    reasons.append('frame-xMax')
                if y1 > frame.yMax + 1 and not expand_bounds:
                    reasons.append('frame-yMax')
                if kind == 'glyf' and 'fvar' in font:
                    if bearing != round(x0):
                        reasons.append('xMin-bearing-diff=%d' % (bearing - round(x0)))
                if kind == 'glyf' and 'vmtx' in font:
                    old_advance, old_bearing = font['vmtx'][name]
                    origin = _glyph_ymax(font['glyf'], name) + old_bearing
                    new_bearing, height = origin - round(y1), round(y1) - round(y0)
                    vertical = font['vhea']
                    if not -32768 <= new_bearing <= 32767:
                        reasons.append('vmtx-range')
                    if new_bearing < vertical.minTopSideBearing and not expand_bounds:
                        reasons.append('vmtx-minTopSideBearing')
                    if old_advance - new_bearing - height < vertical.minBottomSideBearing and not expand_bounds:
                        reasons.append('vmtx-minBottomSideBearing')
                    if new_bearing + height > vertical.yMaxExtent and not expand_bounds:
                        reasons.append('vmtx-yMaxExtent')
                    if expand_bounds and (old_advance - new_bearing - height < -32768
                                          or new_bearing + height > 32767):
                        reasons.append('vhea-range')
            if expand_bounds and (width - bearing - outline_width < -32768 or bearing + outline_width > 32767):
                reasons.append('hhea-range')
            if width > header.advanceWidthMax and not expand_bounds:
                reasons.append('hhea-advanceWidthMax')
            if bearing < header.minLeftSideBearing and not expand_bounds:
                reasons.append('hhea-minLeftSideBearing')
            if width - bearing - outline_width < header.minRightSideBearing - 1 and not expand_bounds:
                reasons.append('hhea-minRightSideBearing')
            if bearing + outline_width > header.xMaxExtent + 1 and not expand_bounds:
                reasons.append('hhea-xMaxExtent')
            if reasons:
                mapping.pop(name)
                retained_unsafe += 1
                if len(unsafe_details) < 16:
                    if reverse_cmap is None:
                        reverse_cmap = {}
                        for codepoint, glyph_name in cmap.items():
                            reverse_cmap.setdefault(glyph_name, []).append(codepoint)
                    unsafe_details.append({
                        'glyph': name,
                        'codepoints': ['U+%04X' % codepoint
                                       for codepoint in sorted(reverse_cmap.get(name, []))[:8]],
                        'reasons': reasons, 'width': width, 'bearing': bearing,
                        'bounds': None if bounds is None
                        else [round(value, 3) for value in transformed_bounds(bounds)]})
    dependency_proof = (copy_retained_dependencies(font, mapping)
                        if preserve_dependencies and 'glyf' in font else None)
    counts = dict.fromkeys(counts, 0)
    for cp, name in cmap.items():
        if name in mapping:
            category = role(cp)
            if category in counts:
                counts[category] += 1
    if (not counts['cjk'] if require_cjk else not any(counts.values())):
        return {'mode': 'retained-no-cjk-match', 'replaced': counts, 'uncovered': uncovered,
                **donor_report, 'targetStylePreserved': True}
    if '.notdef' in mapping:
        raise ValueError('原厂编码映射指向缺字槽，不能覆盖 .notdef')
    frame = font['head']
    conversion_retained, failed_sources, cff_bounds = [], set(), {}
    # Generated programs have no subroutines/hints/blends. Their rounded
    # geometry depends on donor/kind/scale, not the target FD or encoded width.
    # Share these few envelope-outlier bounds between faces under the existing
    # worker cache budget, without keeping decoded CharString programs alive.
    converted_cff_bounds = {}
    shared_cff_bounds = (outline_cache.setdefault(('cff-output-bounds', kind, scale, baseline), {})
                         if outline_cache is not None and kind != 'glyf' else {})
    for target_name, source_name in mapping.items():
        bounds = bounds_for(source_name)
        # Descriptive bounds can grow without shrinking individual donor glyphs
        # or changing the stock ascent, descent, line spacing or vertical origin.
        if bounds is not None and not expand_bounds:
            x0, y0, x1, y1 = transformed_bounds(bounds)
            if x0 < frame.xMin - 1 or y0 < frame.yMin - 1 or x1 > frame.xMax + 1 or y1 > frame.yMax + 1:
                raise ValueError(f'来源字形 {source_name} 超出本机原厂字体面边界')
        width, bearing = donor_metrics[source_name]
        width, bearing = round(width * scale), round(bearing * scale)
        if not 0 <= width <= 65535 or not -32768 <= bearing <= 32767:
            raise ValueError('来源水平度量超出 SFNT 范围')
        header = font['hhea']
        outline_width = 0 if bounds is None else (bounds[2] - bounds[0]) * scale
        if not expand_bounds and (width > header.advanceWidthMax or bearing < header.minLeftSideBearing or
                width - bearing - outline_width < header.minRightSideBearing - 1 or
                bearing + outline_width > header.xMaxExtent + 1):
            raise ValueError(f'来源字形 {source_name} 水平度量超出本机原厂字体面边界')
        if kind == 'glyf':
            key = (source_name,)
            if key not in converted:
                if source_name in failed_sources:
                    conversion_retained.append(target_name)
                    continue
                try:
                    converted[key] = converted_glyph(source_name)
                except NotImplementedError:
                    # FontTools cannot build this particular outline (for
                    # example an on-curve-less contour). Keep that OEM glyph.
                    if not preserve_unsafe:
                        raise
                    failed_sources.add(source_name)
                    conversion_retained.append(target_name)
                    continue
            glyph = converted[key]
            font_clipping_compat.verify_bounds(
                (glyph.xMin, glyph.yMin, glyph.xMax, glyph.yMax) if glyph.numberOfContours else None,
                clipping)
            vertical = retained_vertical_deltas(font, target_name)
            old_origin = None
            if 'vmtx' in font:
                old_advance, old_bearing = font['vmtx'][target_name]
                old_origin = _glyph_ymax(font['glyf'], target_name) + old_bearing
            # Every target was selected from an existing OEM glyph ID. The
            # table setter linearly scans glyphOrder on each assignment;
            # direct replacement keeps the same IDs without a quadratic scan.
            if target_name not in font['glyf'].glyphs:
                raise ValueError('替换目标不是原厂已有字形槽')
            font['glyf'].glyphs[target_name] = glyph
            if not hasattr(glyph, 'xMin'):
                glyph.xMin = glyph.yMin = glyph.xMax = glyph.yMax = 0
            # recalcBBoxes=False keeps the stock frame but also disables maxp
            # recomputation. Retain stock interpreter limits while reserving
            # enough point/contour capacity for the newly imported outlines.
            maxp = font['maxp']
            points = getattr(glyph, 'preparedPointCount', None)
            if points is None:
                points = len(glyph.getCoordinates(font['glyf'])[0])
            maxp.maxPoints = max(maxp.maxPoints, points)
            maxp.maxContours = max(maxp.maxContours, glyph.numberOfContours)
            if 'fvar' in font and bearing != glyph.xMin:
                raise ValueError('可变 TrueType 的来源侧距必须等于轮廓 xMin')
            if old_origin is not None:
                new_bearing = old_origin - glyph.yMax
                if not -32768 <= new_bearing <= 32767:
                    raise ValueError('原厂垂直原点无法保持在 SFNT 度量范围内')
                header = font['vhea']
                height = glyph.yMax - glyph.yMin
                if not expand_bounds and (new_bearing < header.minTopSideBearing or
                        old_advance - new_bearing - height < header.minBottomSideBearing or
                        new_bearing + height > header.yMaxExtent):
                    raise ValueError('来源字形垂直度量超出本机原厂字体面边界')
                font['vmtx'][target_name] = (old_advance, new_bearing)
            if 'gvar' in font:
                font['gvar'].variations[target_name] = [
                    TupleVariation(axes, [(0, 0)] * (points + 2) + [top, bottom])
                    for axes, top, bottom in vertical]
        else:
            if kind == 'CFF2' and 'vmtx' in font and 'VORG' not in font:
                raise ValueError('CFF2 集合缺少独立垂直原点，不能沿用旧轮廓的垂直侧距')
            cff = font[kind].cff
            top = cff.topDictIndex[0]
            _, selector = top.CharStrings.getItemAndSelector(target_name)
            private = top.FDArray[selector or 0].Private if hasattr(top, 'FDArray') else top.Private
            key = (source_name, selector, width)
            if key not in converted:
                if source_name in failed_sources:
                    conversion_retained.append(target_name)
                    continue
                encoded_width = None if kind == 'CFF2' or width == private.defaultWidthX else width - private.nominalWidthX
                # Generated Type 2 programs contain only explicit outlines and
                # the encoded width: no hints, subroutine calls or blend state.
                # Reuse immutable bytes, never another face's Private/FDArray
                # or mutable CharString. CFF1 width encoding is part of the key.
                template_key = (source_name, encoded_width)
                data = cff_templates.get(template_key)
                try:
                    if data is not None:
                        char = T2CharString(bytecode=data, private=private,
                                            globalSubrs=cff.GlobalSubrs)
                        outline_cache['cffTemplateHits'] = outline_cache.get('cffTemplateHits', 0) + 1
                    elif native_cff is not None and native_cff.supports(source_name):
                        data = native_cff.body(source_name, encoded_width, kind == 'CFF2')
                        char = T2CharString(bytecode=data, private=private, globalSubrs=cff.GlobalSubrs)
                        outline_cache['nativeCffHits'] = outline_cache.get('nativeCffHits', 0) + 1
                    else:
                        recording = recordings[source_name]
                        pen = _ExactCubicT2Pen(encoded_width, None, CFF2=kind == 'CFF2')
                        # TrueType and PostScript contours use opposite winding.
                        # Degree elevation preserves the curve; retain the same
                        # scaling, integer rounding and Type 2 specialization.
                        output = ReverseContourPen(pen) if 'glyf' in donor else pen
                        recording.replay(TransformPen(output, (scale, 0, 0, scale, 0, baseline)))
                        char = pen.getCharString(private=private, globalSubrs=cff.GlobalSubrs)
                        if outline_cache is not None:
                            outline_cache['cffConversions'] = outline_cache.get('cffConversions', 0) + 1
                            used = outline_cache.get('templateBytes', 0)
                            overhead = len(source_name) * 4 + 320
                            limit = MAX_GLYPH_TEMPLATE_BYTES - outline_cache.get('nativeGlyphBytes', 0) - outline_cache.get('nativeCffBytes', 0)
                            if used + overhead < limit:
                                char.compile(isCFF2=kind == 'CFF2')
                                data = char.bytecode
                                cost = len(data) + overhead
                                if used + cost <= limit:
                                    cff_templates[template_key] = data
                                    outline_cache['templateBytes'] = used + cost
                except NotImplementedError:
                    # FontTools cannot build this particular outline (for
                    # example an on-curve-less contour). Keep that OEM glyph.
                    if not preserve_unsafe:
                        raise
                    failed_sources.add(source_name)
                    conversion_retained.append(target_name)
                    continue
                if selector is not None:
                    char.fdSelectIndex = selector
                converted[key] = char
            top.CharStrings[target_name] = converted[key]
            if expand_bounds and bounds is not None:
                # Existing source bounds are cached. Decode the emitted CFF
                # program only when rounding could require a larger envelope.
                x0, y0, x1, y1 = transformed_bounds(bounds)
                bbox = top.FontBBox if kind == 'CFF ' else [frame.xMin, frame.yMin, frame.xMax, frame.yMax]
                vertical_growth = False
                if 'vhea' in font and 'vmtx' in font:
                    advance, top_bearing = font['vmtx'][target_name]
                    vheader = font['vhea']
                    vertical_growth = (top_bearing < vheader.minTopSideBearing
                        or advance - top_bearing - (y1 - y0 + 2) < vheader.minBottomSideBearing
                        or top_bearing + y1 - y0 + 2 > vheader.yMaxExtent)
                if (clipping['enabled'] or x0 - 1 < max(frame.xMin, bbox[0]) or y0 - 1 < max(frame.yMin, bbox[1])
                        or x1 + 1 > min(frame.xMax, bbox[2]) or y1 + 1 > min(frame.yMax, bbox[3])
                        or bearing < header.minLeftSideBearing
                        or width - bearing - (x1 - x0 + 2) < header.minRightSideBearing
                        or bearing + x1 - x0 + 2 > header.xMaxExtent
                        or vertical_growth):
                    if key not in converted_cff_bounds:
                        if source_name in shared_cff_bounds:
                            actual = shared_cff_bounds[source_name]
                        else:
                            char = converted[key]
                            data = char.bytecode
                            try:
                                actual = char.calcBounds(top.CharStrings)
                            finally:
                                # BoundsPen decompiles even native bytecode.
                                # Restore immutable output instead of compiling
                                # the same program again during publication.
                                if data is not None:
                                    char.setBytecode(data)
                            if outline_cache is not None:
                                used = outline_cache.get('templateBytes', 0)
                                cost = len(source_name) * 4 + 320
                                limit = MAX_GLYPH_TEMPLATE_BYTES - outline_cache.get('nativeGlyphBytes', 0) - outline_cache.get('nativeCffBytes', 0)
                                if used + cost <= limit:
                                    shared_cff_bounds[source_name] = actual
                                    outline_cache['templateBytes'] = used + cost
                        converted_cff_bounds[key] = actual
                    cff_bounds[target_name] = converted_cff_bounds[key]
                    font_clipping_compat.verify_bounds(cff_bounds[target_name], clipping)
        font['hmtx'][target_name] = (width, bearing)
    if conversion_retained:
        for name in conversion_retained:
            mapping.pop(name, None)
    guard_retained_components(font, mapping)
    if conversion_retained:
        counts = dict.fromkeys(counts, 0)
        for cp, name in cmap.items():
            if name in mapping:
                category = role(cp)
                if category in counts:
                    counts[category] += 1
    freeze_horizontal_variations(font, mapping)
    bounds_report = _grow_output_bounds(font, mapping, cff_bounds) if expand_bounds else {}
    verify_dependency_copies(font, dependency_proof)
    for tag in ('DSIG', 'LTSH', 'hdmx', 'VDMX'):
        if tag in font:
            del font[tag]
    conflict_report = [{'glyph': name, 'codepoints': ['U+%04X' % cp for cp in codepoints]}
                       for name, codepoints in sorted(shared_conflicts.items())]
    if clipping['enabled']:
        print('[CLIP-FIT] ' + json.dumps({
            'phase': 'generated-awaiting-transaction',
            'target': target, 'fit': clipping,
        }, ensure_ascii=False, sort_keys=True), file=sys.stderr)
    return {'mode': 'compiled', 'replaced': counts, 'uncovered': uncovered,
            'outlineEngine': native_report,
            'retainedUnsafeSlots': retained_unsafe,
            'retainedUnsafeGlyphs': unsafe_details,
            'retainedConversionSlots': len(conversion_retained),
            'retainedSharedSlots': retained_shared,
            'replacedLatinLigatures': sum(name in mapping for name in ligatures),
            'retainedUnencodedGlyphs': True,
            'retainedSharedConflictSlots': len(shared_conflicts),
            'retainedSharedConflicts': conflict_report[:16],
            'boundsExpansion': expand_bounds, 'clippingFit': clipping, **bounds_report,
            **({'dependencyProof': dependency_proof} if dependency_proof else {}),
            'outline': kind, 'retainedVariationAxes': 'fvar' in font,
            'replacementVariation': 'selected-fixed-weight',
            **donor_report, 'targetStylePreserved': True}


def _compile_face_task(task, recordings, outline_cache, donor=None):
    """Compile one OEM face in an isolated process or in the caller.

    The returned dict must stay JSON-serialisable: bounded workers hand results
    back through a private result file, while the caller keeps publication.
    """
    index = task['face']
    started = time.monotonic()
    hits_before = outline_cache.get('cffTemplateHits', 0)
    conversions_before = outline_cache.get('cffConversions', 0)
    native_cff_before = outline_cache.get('nativeCffHits', 0)
    with TTFont(task['stock'], fontNumber=index, lazy=True, recalcBBoxes=False,
                recalcTimestamp=False) as font:
        original = protected(font, capture_bounds=True)
        raw_tables = raw_table_hashes(font)
        context = nullcontext(donor) if donor is not None else TTFont(
            task['donor'], lazy=True, recalcTimestamp=False)
        with context as donor:
            details = replace_face(font, donor, recordings, preserve_unsafe=True,
                                   outline_cache=outline_cache, native_source=task['donor'],
                                   expand_bounds=True)
        if protected(font, bounds_reference=original) != original:
            raise ValueError(f'集合面 {index} 的原厂布局契约已变化')
        compiled_at = time.monotonic()
        output = task['output']
        if details['mode'] == 'compiled':
            # The complete in-memory contract was just verified. Keep all
            # mutable/unknown tables loaded; release only explicitly invariant
            # ones so FontTools can copy their original bytes from the reader.
            for tag in raw_tables:
                font.tables.pop(tag, None)
            font.save(output, reorderTables=False)
        else:
            # Retained faces need no expensive outline round trip.
            with TTFont(task['stock'], fontNumber=index, lazy=True, recalcBBoxes=False,
                        recalcTimestamp=False) as untouched:
                untouched.save(output, reorderTables=False)
    saved_at = time.monotonic()
    if Path(output).stat().st_size > MAX_OUTPUT_FACE_BYTES:
        raise ValueError(f'字体面 {index} 生成结果超出单面大小限制')
    with TTFont(output, lazy=True, recalcTimestamp=False) as check:
        # A dependency may cause FontTools to recompile a table after it was
        # released. In that case retain the original canonical validation path.
        matched = contract_matches(check, original, raw_tables,
                                   allow_bounds_expansion=details.get('boundsExpansion') is True)
        if raw_tables and not matched:
            raw_tables = {}
            matched = contract_matches(check, original,
                                       allow_bounds_expansion=details.get('boundsExpansion') is True)
        if not matched:
            raise ValueError(f'集合面 {index} 序列化后布局契约已变化')
        verify_dependency_copies(check, details.get('dependencyProof'))
    details['performance'] = {
        'compileSeconds': round(compiled_at - started, 3),
        'saveSeconds': round(saved_at - compiled_at, 3),
        'verifySeconds': round(time.monotonic() - saved_at, 3),
        'cffConversions': outline_cache.get('cffConversions', 0) - conversions_before,
        'nativeCffHits': outline_cache.get('nativeCffHits', 0) - native_cff_before,
        'cffTemplateHits': outline_cache.get('cffTemplateHits', 0) - hits_before,
        'workerTemplateBytes': outline_cache.get('templateBytes', 0),
    }
    return {'index': index, 'contract': original, 'details': details,
            'rawTables': raw_tables}


def inspect_donor(source):
    source = Path(source)
    face_index = _pick_face(source, 'cjk', 400, None)
    kwargs = {'fontNumber': face_index} if face_index >= 0 else {}
    with TTFont(source, lazy=True, recalcTimestamp=False, **kwargs) as donor:
        cmap = donor.getBestCmap() or {}
        if any(cp not in cmap for cp in map(ord, '中ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789')):
            raise ValueError('组合来源缺少必需的中文、英文字母或数字')
        tables = font_source_profile._table_contract(donor)
        axes = font_source_profile._axes(donor)
        coverage = font_source_profile._coverage(donor)
        capabilities = font_source_profile._capabilities(
            coverage, tables, axes, font_source_profile._fixed_pitch(donor))
        # This path replaces supported outlines inside the OEM collection.
        # Missing donor characters keep their OEM glyphs, unlike a whole-face
        # global replacement. Keep coverage facts for the report, but do not
        # reject a saved mix merely for missing punctuation or fewer Han glyphs.
        if tables['outline'] not in ('glyf', 'CFF', 'CFF2'):
            raise ValueError('组合来源不包含可用轮廓')
        if tables['color']:
            raise ValueError('彩色字体不能作为通用集合轮廓来源')
        return {'face': face_index, 'weight': target_weight(donor), 'axes': axes,
                'tables': tables, 'capabilities': capabilities}


def build(source, stock, output, workspace, request, target, progress=None,
          source_identity=None, source_sha256=None, stock_sha256=None,
          verify_source_hash=True, donor_facts=None, output_budget=None):
    source, stock, output = map(Path, (source, stock, output))
    if source.resolve() == output.resolve() or stock.resolve() == output.resolve():
        raise ValueError('集合输出不能覆盖来源或原厂文件')
    if source.stat().st_size <= 0 or source.stat().st_size > MAX_MIX_SOURCE_BYTES:
        raise ValueError('组合字体大小超出集合生成限制')
    if stock.stat().st_size <= 0 or stock.stat().st_size > MAX_STOCK_FILE_BYTES:
        raise ValueError('原厂集合大小超出安全限制')
    source_stat = identity(source)
    if source_identity is not None and source_stat != source_identity:
        raise ValueError('组合来源在集合生成前已变化')
    before = (source_stat, identity(stock))
    source_hash = source_sha256 or sha(source)
    stock_hash = stock_sha256 or sha(stock)
    count = collection_faces(stock)
    # A TTC can contain several distinct CFF tables after transplantation,
    # even when its OEM faces originally shared one small outline table.
    # Keep the per-face cap and the request-wide cap independently bounded.
    output_limit = min(MAX_COLLECTION_BYTES, count * MAX_OUTPUT_FACE_BYTES)
    if output_budget is not None:
        if not isinstance(output_budget, int) or output_budget <= 0:
            raise ValueError('本次字体集合生成已无剩余容量预算')
        output_limit = min(output_limit, output_budget)
    donor_facts = donor_facts or inspect_donor(source)
    face_index = donor_facts['face']
    kwargs = {'fontNumber': face_index} if face_index >= 0 else {}
    report = {'schema': SCHEMA, 'requestId': request, 'target': target,
              'sourceSha256': source_hash, 'stockSha256': stock_hash,
              'sourceFace': face_index, 'stockFaces': count, 'faces': [],
              'outputLimitBytes': output_limit,
              'selectionModel': 'selected-fixed-weight',
              'targetStylePolicy': 'preserve-stock-style-and-weight-metadata',
              'selectedAxesTraceable': False,
              'selectedAxesNote': '静态组合产物哈希绑定实际输出；组合库未保存源选轴数值'}
    with tempfile.TemporaryDirectory(prefix='collection-', dir=workspace) as temporary:
        temporary = Path(temporary)
        face_paths = []
        with TTFont(source, lazy=True, recalcTimestamp=False, **kwargs) as donor:
            cmap = donor.getBestCmap() or {}
            if any(cp not in cmap for cp in map(ord, '中ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789')):
                raise ValueError('组合来源缺少必需的中文、英文字母或数字')
            if not any(tag in donor for tag in ('glyf', 'CFF ', 'CFF2')):
                raise ValueError('组合来源不包含可用轮廓')
            donor_tables = donor_facts['tables']
            donor_axes = donor_facts['axes']
            donor_capabilities = donor_facts['capabilities']
            report['donor'] = {
                'weight': donor_facts['weight'], 'axes': donor_axes,
                'outline': donor_tables['outline'],
                'capabilities': donor_capabilities,
            }
        # Prepare each geometry once before fork. Immutable byte buffers are
        # inherited read-only, not regenerated independently in every worker.
        shared_native_cff, native_cff_reports, native_cff_bytes = {}, {}, 0
        from font_native_cff import prepare as prepare_cff
        cff_upems = set()
        for index in range(count):
            with TTFont(stock, fontNumber=index, lazy=True, recalcTimestamp=False) as stock_face:
                if 'CFF ' in stock_face or 'CFF2' in stock_face:
                    cff_upems.add(stock_face['head'].unitsPerEm)
        if cff_upems:
            _progress(progress, 'collection-native', '正在原生批量准备 CJK 曲线', 95)
            with TTFont(source, lazy=True, recalcTimestamp=False, **kwargs) as donor:
                for upem in sorted(cff_upems):
                    cache, info = prepare_cff(donor, source, upem, face=max(face_index, 0),
                                             budget=MAX_GLYPH_TEMPLATE_BYTES - native_cff_bytes)
                    shared_native_cff[('native-cff', upem)] = (cache, info)
                    native_cff_reports[str(upem)] = info
                    if cache is not None:
                        native_cff_bytes += cache.byte_size
        # Each face is an independent compile: bounded workers reuse one donor
        # outline/bounds cache inside their own process. Every safety check,
        # contract digest, source-change check and publication stays here.
        budget = font_parallel.worker_budget(count, source.stat().st_size)
        _progress(progress, 'collection',
                  f'正在并行编译本机 CJK 集合面（{budget["workers"]} 个进程）', 95)
        tasks = [{'face': index, 'stock': str(stock), 'donor': str(source),
                  'output': str(temporary / f'face-{index}.font')}
                 for index in range(count)]

        last_completed = -1
        next_progress_poll = 0.0

        def report_faces(force=False):
            nonlocal last_completed, next_progress_poll
            now = time.monotonic()
            if not force and now < next_progress_poll:
                return
            next_progress_poll = now + 1.0
            completed = sum((temporary / f'completed-{index}').is_file() for index in range(count))
            if completed != last_completed:
                last_completed = completed
                _progress(progress, 'collection-faces', f'本机 CJK 集合已编译 {completed}/{count} 面',
                          95 + completed * 2 // max(count, 1))

        def run_batch(items, private, is_child):
            shared_recordings = {}
            shared_bounds = dict(shared_native_cff, nativeCffBytes=native_cff_bytes)
            with TTFont(source, lazy=True, recalcTimestamp=False, **kwargs) as donor:
                results = []
                for _, task in items:
                    results.append(_compile_face_task(task, shared_recordings, shared_bounds, donor))
                    (temporary / f'completed-{task["face"]}').touch()
                    if not is_child:
                        report_faces(force=True)
                return results

        compile_started = time.monotonic()
        batches, _budget = font_parallel.run_batches(
            tasks, temporary / 'workers', source.stat().st_size, run_batch, progress_poll=report_faces)
        report_faces(force=True)
        report['performance'] = {'execution': _budget,
                                 'nativeCffPreparation': native_cff_reports,
                                 'nativeCffSharedBytes': native_cff_bytes,
                                 'facesWallSeconds': round(time.monotonic() - compile_started, 3)}
        compiled = sorted((face for batch in batches for face in batch),
                          key=lambda item: item['index'])
        if [item['index'] for item in compiled] != list(range(count)):
            raise ValueError('集合逐面编译结果与原厂面索引不一致')
        for item in compiled:
            report['faces'].append(dict(index=item['index'],
                                        contract=item['contract'], rawTables=item['rawTables'],
                                        **item['details']))
        for field in ('cffConversions', 'cffTemplateHits', 'nativeCffHits'):
            report['performance'][field] = sum(item['details']['performance'][field] for item in compiled)
        merge_started = time.monotonic()
        for index in range(count):
            face_path = temporary / f'face-{index}.font'
            if not face_path.is_file() or face_path.stat().st_size <= 0:
                raise ValueError(f'集合面 {index} 的输出缺失或为空')
            if face_path.stat().st_size > MAX_OUTPUT_FACE_BYTES:
                raise ValueError(f'字体面 {index} 生成结果超出单面大小限制')
            face_paths.append(face_path)
        gc.collect()
        if not any(face['mode'] == 'compiled' for face in report['faces']):
            if stock.stat().st_size > output_limit:
                raise ValueError('保留原厂字体集合超出剩余容量预算')
            # The requested alias may already contain a generated single-face
            # font from the ROM mapper. Preserve the real OEM collection there
            # even when every OEM face is specialized or has no safe CJK slot.
            report['result'] = 'PASS'
            report['mode'] = 'retained-original-collection'
            report['outputSha256'] = stock_hash
            report['outputBytes'] = stock.stat().st_size
            _check_sources(source, stock, before, source_hash, stock_hash,
                           verify_source_hash=verify_source_hash)
            _atomic_copy(stock, output, expected_hash=stock_hash,
                         precommit=lambda: _check_sources(source, stock, before,
                                                          source_hash, stock_hash,
                                                          verify_source_hash=verify_source_hash))
            return report
        collection = TTCollection()
        _progress(progress, 'collection-merge', '正在合并本机 CJK 集合', 97)
        collection.fonts = [TTFont(path, lazy=True, recalcBBoxes=False, recalcTimestamp=False)
                            for path in face_paths]
        staged = temporary / 'compiled.ttc'
        try:
            _save_collection(collection.fonts, staged, output_limit)
        finally:
            collection.close()
        report['performance']['mergeSeconds'] = round(time.monotonic() - merge_started, 3)
        verify_started = time.monotonic()
        _progress(progress, 'collection-verify', '正在校验并提交本机 CJK 集合', 98)
        if collection_faces(staged) != count:
            raise ValueError('生成后的集合面数量变化')
        for index, face in enumerate(report['faces']):
            with TTFont(staged, fontNumber=index, lazy=True, recalcTimestamp=False) as check:
                if not contract_matches(check, face['contract'], face.get('rawTables'),
                                        allow_bounds_expansion=face.get('boundsExpansion') is True):
                    raise ValueError(f'集合面 {index} 合集后布局契约已变化')
        report['outputBytes'] = staged.stat().st_size
        if report['outputBytes'] > output_limit:
            raise ValueError('生成后的字体集合超出剩余容量预算')
        _check_sources(source, stock, before, source_hash, stock_hash,
                       verify_source_hash=verify_source_hash)
        report['outputSha256'] = sha(staged)
        report['result'] = 'PASS'
        output.parent.mkdir(parents=True, exist_ok=True)
        # Rename on the output filesystem; keep the old alias until all checks pass.
        _atomic_copy(staged, output, expected_hash=report['outputSha256'],
                     precommit=lambda: _check_sources(source, stock, before,
                                                      source_hash, stock_hash,
                                                      verify_source_hash=verify_source_hash))
        report['performance']['verifyAndPublishSeconds'] = round(time.monotonic() - verify_started, 3)
    return report


def _check_sources(source, stock, before, source_hash, stock_hash,
                   verify_source_hash=True):
    if (before != (identity(source), identity(stock))
            or (verify_source_hash and sha(source) != source_hash)
            or sha(stock) != stock_hash):
        raise ValueError('原厂集合或组合来源在编译期间发生变化')


def _atomic_copy(source, output, expected_hash=None, precommit=None):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.collection-', dir=output.parent)
    try:
        with os.fdopen(fd, 'wb') as stream, Path(source).open('rb') as data:
            shutil.copyfileobj(data, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, 0o644)
        if expected_hash is not None and sha(name) != expected_hash:
            raise ValueError('集合暂存字节与验证后的输出哈希不符')
        if precommit is not None:
            precommit()
        os.replace(name, output)
    finally:
        Path(name).unlink(missing_ok=True)


def stock_path(module, target):
    logical = str(target)
    path = PurePosixPath(logical)
    if (not path.is_absolute() or len(path.parts) != 4 or path.parts[0] != '/'
            or path.parts[2] != 'fonts' or path.suffix.lower() not in ('.ttc', '.otc')):
        raise ValueError('集合目标必须是准确的系统字体路径')
    allowed = dict(stock_inventory.LOGICAL_FONT_ROOTS)
    if path.parts[1] not in allowed:
        raise ValueError('集合目标分区不受支持')
    stock = stock_font_snapshot.resolve(logical, Path(module))
    if stock is None:
        raise ValueError(f'缺少可验证的本机原厂字体快照：{logical}')
    return stock


def _snapshot_digest(module, logical, stock):
    entry = stock_font_snapshot.load(Path(module)).get('files', {}).get(logical)
    digest = entry.get('sha256', '') if isinstance(entry, dict) else ''
    if (isinstance(digest, str) and re.fullmatch(r'[0-9a-f]{64}', digest)
            and entry.get('size') == Path(stock).stat().st_size):
        return digest
    return sha(stock)


def owned_workspace():
    """Scratch space inside the finite supervisor's own task directory.

    The caller binds this to its own offline stage. Its cleanup trap and
    next-request stale-stage cleanup after a hard kill own that whole stage.
    Normal completion removes the scratch child here.
    """
    raw = os.environ.get('LUOSHU_TASK_SCOPE_TMPDIR', '')
    scope = Path(raw)
    if not raw or not scope.is_absolute() or scope.is_symlink() or not scope.is_dir():
        raise ValueError('缺少任务专属字体工作区')
    scope = scope.resolve()
    return Path(tempfile.mkdtemp(prefix='collection-build.', dir=scope)), scope


def _valid_request(value):
    return bool(value and len(value) <= 160 and re.fullmatch(r'[A-Za-z0-9_.:-]+', value))


def _inventory_targets(module):
    path = Path(module) / 'config/device_font_inventory.json'
    data = stock_inventory.load_inventory(path)
    candidates = []
    for logical, entry in data['slots'].items():
        logical_path = PurePosixPath(logical)
        if logical_path.suffix.lower() not in ('.ttc', '.otc'):
            continue
        if not (entry.get('uiEligible') is True or entry.get('source') == 'verified-scan'):
            continue
        candidates.append(logical)
    candidates = sorted(set(candidates))
    if len(candidates) > MAX_COLLECTION_TARGETS:
        raise ValueError(f'本机 UI 字体集合目标过多：{len(candidates)}')
    return candidates


def _payload_output(payload, logical):
    relative = Path(logical.lstrip('/'))
    if relative.is_absolute() or '..' in relative.parts or len(relative.parts) != 3:
        raise ValueError(f'字体集合暂存路径无效：{logical}')
    if relative.parts[1] != 'fonts' or relative.suffix.lower() not in ('.ttc', '.otc'):
        raise ValueError(f'字体集合不在受支持的分区 fonts 目录：{logical}')
    output = Path(payload) / relative
    root = Path(payload).resolve()
    parent = output.parent.resolve()
    if not parent.is_relative_to(root) or output.is_symlink() or output.is_dir():
        raise ValueError(f'字体集合输出路径越界或包含链接：{logical}')
    return output


def _write_proof(payload, relative, report):
    root = Path(payload) / PROOF_DIRECTORY
    if root.is_symlink():
        raise ValueError('字体集合证明目录不能是符号链接')
    root.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(relative.encode('utf-8')).hexdigest() + '.json'
    target = root / key
    fd, temporary = tempfile.mkstemp(prefix='.proof-', dir=root)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=True, separators=(',', ':'))
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, target)
    finally:
        Path(temporary).unlink(missing_ok=True)


def _read_mix_manifest(path, source):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024:
        raise ValueError('组合字体清单不存在或无效')
    fields = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if '=' not in line:
            continue
        key, value = line.split('=', 1)
        if key in fields:
            raise ValueError('组合字体清单包含重复字段')
        fields[key] = value
    if fields.get('combination') != 'true':
        raise ValueError('所选字体不是已保存的组合字体')
    try:
        files = json.loads(fields.get('files', '[]'))
    except json.JSONDecodeError as error:
        raise ValueError('组合字体文件清单无效') from error
    if (not isinstance(files, list) or not files or len(files) > 64
            or any(not isinstance(name, str) or Path(name).name != name for name in files)
            or source.name not in files):
        raise ValueError('组合字体来源与已保存清单不符')
    return {'mixRequestId': fields.get('request', ''),
            'sourceIds': {role: fields.get(role + '_source_id', '')
                          for role in ('cjk', 'latin', 'digit')}}


def build_payload(module, payload, source, request, task_workspace, mix_config):
    module, payload, source = map(Path, (module, payload, source))
    if not _valid_request(request):
        raise ValueError('字体集合请求标识无效')
    if not payload.is_dir() or payload.is_symlink():
        raise ValueError('字体切换暂存负载不存在或包含链接')
    if not source.is_file() or source.is_symlink() or source.stat().st_size <= 0:
        raise ValueError('已保存组合字体不存在或无效')
    mix = _read_mix_manifest(mix_config, source)
    workspace = Path(task_workspace)
    if not workspace.is_absolute() or workspace.is_symlink() or not workspace.is_dir():
        raise ValueError('缺少任务专属字体工作区')
    workspace = workspace.resolve()
    payload_root = payload.resolve()
    if not payload_root.is_relative_to(module.resolve()):
        raise ValueError('字体切换暂存负载不属于当前模块')
    proof_root = payload / PROOF_DIRECTORY
    if proof_root.is_symlink():
        raise ValueError('字体集合证明目录不能是符号链接')
    if proof_root.exists():
        shutil.rmtree(proof_root)

    targets = _inventory_targets(module)
    total_stock = 0
    report = {'schema': 'composite-collection-payload-v1', 'requestId': request,
              'mixRequestId': mix['mixRequestId'], 'result': 'PASS',
              'built': [], 'skipped': []}
    source_before = identity(source)
    source_hash = sha(source)
    donor_facts = inspect_donor(source)
    total_faces = 0
    total_output = 0
    for logical in targets:
        stock = stock_path(module, logical)
        size = stock.stat().st_size
        if size <= 0 or size > MAX_STOCK_FILE_BYTES:
            raise ValueError(f'原厂集合大小超出安全限制：{logical}')
        total_stock += size
        if total_stock > MAX_COLLECTION_BYTES:
            raise ValueError('原厂字体集合总大小超出安全限制')
        faces = collection_faces(stock)
        total_faces += faces
        if total_faces > MAX_COLLECTION_FACE_TOTAL:
            raise ValueError('本次字体集合总字体面数量超出安全限制')
        output = _payload_output(payload, logical)
        relative = output.relative_to(payload).as_posix()
        result = build(source, stock, output, workspace, request, logical,
                       os.environ.get('LUOSHU_COLLECTION_PROGRESS_FILE') or str(module / 'config/composite_progress.json'),
                       source_identity=source_before, source_sha256=source_hash,
                       stock_sha256=_snapshot_digest(module, logical, stock),
                       verify_source_hash=False, donor_facts=donor_facts,
                       output_budget=MAX_COLLECTION_BYTES - total_output)
        _write_proof(payload, relative, result)
        total_output += output.stat().st_size
        if total_output > MAX_COLLECTION_BYTES:
            raise ValueError('本次生成的字体集合总大小超出安全限制')
        report['built'].append({'path': relative, 'faces': result['stockFaces'],
                                'outputSha256': result['outputSha256'],
                                'mode': result.get('mode', 'compiled-or-retained'),
                                'performance': result.get('performance', {}),
                                'donor': result.get('donor', {})})
    if identity(source) != source_before or sha(source) != source_hash:
        raise ValueError('组合字体在集合生成期间发生变化')
    return report


def _parser():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    build_parser = sub.add_parser('build')
    build_parser.add_argument('--module', type=Path, required=True)
    build_parser.add_argument('--payload', type=Path, required=True)
    build_parser.add_argument('--source', type=Path, required=True)
    build_parser.add_argument('--mix-config', type=Path, required=True)
    build_parser.add_argument('--request', required=True)
    validate_parser = sub.add_parser('validate')
    validate_parser.add_argument('--module', type=Path, required=True)
    validate_parser.add_argument('--payload', type=Path, required=True)
    validate_parser.add_argument('--request', required=True)
    validate_parser.add_argument('--source', type=Path)
    validate_parser.add_argument('--require-proof', action='store_true')
    validate_parser.add_argument('--cleanup-proofs', action='store_true')
    return parser


def main():
    args = _parser().parse_args()
    workspace = None
    try:
        if args.command == 'build':
            workspace, _scope = owned_workspace()
            result = build_payload(args.module, args.payload, args.source,
                                   args.request, workspace, args.mix_config)
        else:
            contract = composite_collection_contract.validate(
                args.payload, args.request, args.module,
                require_proof=args.require_proof, source=args.source)
            if contract['result'] != 'PASS':
                for error in contract['errors']:
                    print('[MIX] 集合格式核查失败：' + error['path'] + '：' + error['reason'], flush=True)
                raise ValueError('字体集合输出契约校验失败')
            result = contract
            if args.cleanup_proofs:
                proof_root = args.payload / PROOF_DIRECTORY
                if proof_root.exists() and not proof_root.is_symlink():
                    shutil.rmtree(proof_root)
        print(json.dumps({'status': 'ok', 'data': result}, ensure_ascii=False,
                         separators=(',', ':')))
        return 0
    except (OSError, ValueError, RuntimeError, TTLibError,
            stock_inventory.InventoryError) as error:
        print('[MIX] CJK 集合生成失败：' + str(error), flush=True)
        return 1
    finally:
        if workspace is not None:
            shutil.rmtree(workspace, ignore_errors=True)


if __name__ == '__main__':
    raise SystemExit(main())
