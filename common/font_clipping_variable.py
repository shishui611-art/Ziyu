#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fit TrueType variation geometry without pinning or removing design axes.

Bounds cover continuous variation supports, including IUP and nested XY
components. Mutually exclusive supports are never added together. Fitting
changes only SFNT headers, retaining the source outline/variation/layout bytes.
The normal switch transaction still owns verification and publication.
"""
from __future__ import annotations

import gc
import hashlib
import math
import struct
from functools import lru_cache
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont, TTCollection
from fontTools.ttLib.tables._g_l_y_f import (
    SCALED_COMPONENT_OFFSET, UNSCALED_COMPONENT_OFFSET, SCALE_COMPONENT_OFFSET_DEFAULT)
from fontTools.varLib.iup import iup_delta
from fontTools.varLib.models import supportScalar

from font_digest_cache import FontDigestCache, file_identity
from font_instance import check_mix_cancellation
from font_metrics_io import FontMetricsIO

from ziyu_limits import limit_bytes as _limit_bytes
MAX_BYTES = _limit_bytes()
MAX_TEMPLATE_BYTES = max(384 * 1024 * 1024, _limit_bytes())
MAX_DELTA_POINTS = 100_000_000
UNSUPPORTED = frozenset(('CFF2', 'VARC', 'COLR', 'CBDT', 'CBLC', 'EBDT', 'EBLC', 'EBSC', 'bdat', 'bloc', 'sbix', 'SVG ',
    'morx', 'mort', 'kerx', 'ankr', 'Silf', 'Glat', 'Gloc', 'Feat', 'Sill',
    'trak', 'bsln', 'opbd', 'prop', 'lcar', 'just'))
HEADER_TABLES = frozenset(('head', 'hhea', 'OS/2'))
_STOCK_GEOMETRY = {}


def _text_group(cp):
    """Sizing ranges, not a coverage corpus or a claim about every glyph."""
    if 0x30 <= cp <= 0x39:
        return 'digits'
    if 0x41 <= cp <= 0x5a or 0x61 <= cp <= 0x7a:
        return 'latin'
    if 0x21 <= cp <= 0x7e:
        return 'ascii-punctuation'
    if 0x3400 <= cp <= 0x4dbf or 0x4e00 <= cp <= 0x9fff or 0xf900 <= cp <= 0xfaff:
        return 'cjk'
    if 0x3001 <= cp <= 0x301f or 0xff01 <= cp <= 0xff60:
        return 'cjk-punctuation'
    if 0x3041 <= cp <= 0x3096 or 0x30a1 <= cp <= 0x30fa:
        return 'kana'
    if 0xac00 <= cp <= 0xd7a3:
        return 'hangul'
    return None


def _reference_profile(font, glyph_bounds):
    """Aggregate complete sizing ranges; never sample variable axis values."""
    groups = {}
    for index, (cp, name) in enumerate((font.getBestCmap() or {}).items()):
        if index % 512 == 0:
            check_mix_cancellation()
        group = _text_group(cp)
        if group is not None:
            groups.setdefault(group, set()).add(name)
    rows = []
    for group, names in sorted(groups.items()):
        boxes = [box for name in names if (box := glyph_bounds(name)) is not None]
        if boxes:
            rows.append((group, min(b[0] for b in boxes), max(b[1] for b in boxes), len(boxes)))
    return tuple(rows)


@lru_cache(maxsize=256)
def _exclusive_groups(signatures):
    """Partition supports into pairwise disjoint sets, without axis sampling."""
    if len(signatures) > 128:
        return tuple((index,) for index in range(len(signatures)))

    def separate(left, right):
        for tag in left.keys() & right.keys():
            a, b = left[tag], right[tag]
            # Same nonparticipating-axis rules as OpenType supportScalar.
            if any(peak == 0 or lo > peak or peak > hi or lo < 0 < hi
                   for lo, peak, hi in (a, b)):
                continue
            lo, hi = max(a[0], b[0]), min(a[2], b[2])
            if lo > hi or (lo == hi and not (
                    supportScalar({tag: lo}, {tag: a}) and supportScalar({tag: lo}, {tag: b}))):
                return True
        return False

    supports = [dict(value) for value in signatures]
    groups = []
    for index, support in enumerate(supports):
        if any(not all(math.isfinite(v) for v in values) for values in support.values()):
            raise ValueError('字体可变支持区域无效')
        for group in groups:
            if all(separate(support, supports[other]) for other in group):
                group.append(index)
                break
        else:
            groups.append([index])
    return tuple(tuple(group) for group in groups)


def _supported(font):
    if (not {'fvar', 'gvar', 'glyf', 'head', 'hhea', 'OS/2', 'hmtx', 'cmap'} <= set(font.keys())
            or UNSUPPORTED.intersection(font.keys())):
        raise ValueError('此字体暂不支持保留可变字重的防裁切；未转换为固定字重')
    if not any(axis.axisTag == 'wght' for axis in font['fvar'].axes):
        raise ValueError('字体没有可用的字重轴，未转换为固定字重')
    if any(not all(math.isfinite(v) for v in (a.minValue, a.defaultValue, a.maxValue))
           or not a.minValue <= a.defaultValue <= a.maxValue or a.minValue >= a.maxValue
           for a in font['fvar'].axes):
        raise ValueError('字体可变轴范围无效')


def _contract(font, progress=None):
    """Bind every unchanged table; identical gvar/cmap bytes prove continuity."""
    _supported(font)
    tags = [str(tag) for tag in font.reader.keys()
            if tag not in HEADER_TABLES and tag not in ('MVAR', 'DSIG')]
    hashes = {}
    for index, tag in enumerate(tags):
        check_mix_cancellation()
        hashes[tag] = hashlib.sha256(font.reader[tag]).hexdigest()
        if progress:
            progress(f'核对字形数据 {index + 1}/{len(tags)}', (index + 1) / max(len(tags), 1))
    return {'tableHashes': hashes, 'proof': 'unchanged-source-table-bytes',
            'variationTables': sorted(tag for tag in ('fvar', 'avar', 'gvar', 'HVAR', 'VVAR', 'STAT') if tag in font)}


def envelope(font, progress=None, *, details=None):
    """Conservative control-point bounds over the full normalized design space."""
    _supported(font)
    glyf, variations = font['glyf'], font['gvar'].variations
    hmtx = font['hmtx'].metrics
    vmtx = font['vmtx'].metrics if 'vmtx' in font else None
    memo, visiting = {}, set()
    work = 0

    def bounds(name, depth=0):
        nonlocal work
        if name in memo:
            return memo[name]
        if name in visiting or depth > 32:
            raise ValueError('字体组件存在循环或嵌套过深，未应用防裁切')
        visiting.add(name)
        glyph = glyf[name]
        if not glyph.numberOfContours:
            visiting.remove(name); memo[name] = None
            return None
        if glyph.isComposite() and any(hasattr(c, 'firstPt') for c in glyph.components):
            raise ValueError('此字体的对齐点组件暂不支持可变防裁切，未固定字重')
        coords, controls = glyf._getCoordinatesAndControls(name, hmtx, vmtx)
        size = len(coords) - 4
        lo_x = [xy[0] for xy in coords[:size]]
        hi_x = list(lo_x)
        lo_y = [xy[1] for xy in coords[:size]]
        hi_y = list(lo_y)
        tuples = variations.get(name, [])
        signatures = tuple(tuple(sorted(v.axes.items())) for v in tuples)
        for group in _exclusive_groups(signatures):
            # Within one group at most one tuple has a nonzero scalar. The
            # envelope is their union, not the sum of incompatible extremes.
            dx0, dx1, dy0, dy1 = ([0] * size for _ in range(4))
            for index in group:
                variation = tuples[index]
                if len(variation.coordinates) != len(coords):
                    raise ValueError('字体可变轮廓点数不匹配')
                work += size
                if work > MAX_DELTA_POINTS:
                    raise ValueError('字体可变轮廓过于复杂，未转换为固定字重')
                delta = variation.coordinates
                if not glyph.isComposite() and None in delta:
                    delta = iup_delta(delta, coords, controls.endPts)
                for i in range(size):
                    value = delta[i]
                    if value is None:
                        continue
                    x, y = value
                    if not math.isfinite(x) or not math.isfinite(y):
                        raise ValueError('字体可变轮廓含无效坐标')
                    dx0[i] = min(dx0[i], x); dx1[i] = max(dx1[i], x)
                    dy0[i] = min(dy0[i], y); dy1[i] = max(dy1[i], y)
            for i in range(size):
                lo_x[i] += dx0[i]; hi_x[i] += dx1[i]
                lo_y[i] += dy0[i]; hi_y[i] += dy1[i]
        if glyph.isComposite():
            boxes = []
            for i, component in enumerate(glyph.components):
                child = bounds(component.glyphName, depth + 1)
                if child is None:
                    continue
                xx, xy, yx, yy = (component.transform[0][0], component.transform[0][1],
                    component.transform[1][0], component.transform[1][1]) if hasattr(component, 'transform') else (1, 0, 0, 1)
                if not all(math.isfinite(v) for v in (xx, xy, yx, yy)):
                    raise ValueError('字体组件矩阵无效')

                def transform(box):
                    xs = [xx * x + yx * y for x in (box[0], box[2]) for y in (box[1], box[3])]
                    ys = [xy * x + yy * y for x in (box[0], box[2]) for y in (box[1], box[3])]
                    return min(xs), min(ys), max(xs), max(ys)

                shifted = (lo_x[i], lo_y[i], hi_x[i], hi_y[i])
                scaled = component.flags & SCALED_COMPONENT_OFFSET
                unscaled = component.flags & UNSCALED_COMPONENT_OFFSET
                if scaled and unscaled:
                    raise ValueError('字体组件偏移标记冲突')
                if scaled or (not unscaled and SCALE_COMPONENT_OFFSET_DEFAULT):
                    shifted = transform(shifted)
                ink = transform(child)
                boxes.append((ink[0] + shifted[0], ink[1] + shifted[1],
                              ink[2] + shifted[2], ink[3] + shifted[3]))
            result = ((min(b[0] for b in boxes), min(b[1] for b in boxes),
                       max(b[2] for b in boxes), max(b[3] for b in boxes)) if boxes else None)
        else:
            result = (min(lo_x), min(lo_y), max(hi_x), max(hi_y)) if size else None
        visiting.remove(name); memo[name] = result
        return result

    boxes = []
    order = font.getGlyphOrder()
    for index, name in enumerate(order):
        if index % 128 == 0:
            check_mix_cancellation()
            if progress:
                progress(f'测量字形 {index}/{len(order)}', index / max(len(order), 1))
        box = bounds(name)
        if box is not None:
            boxes.append(box)
    if not boxes:
        raise ValueError('字体没有可测量的可变轮廓')
    result = [min(b[0] for b in boxes), min(b[1] for b in boxes),
              max(b[2] for b in boxes), max(b[3] for b in boxes)]
    if not all(math.isfinite(v) for v in result):
        raise ValueError('字体可变边界无效')
    if progress:
        progress('字形测量完成', 1)
    if details is not None:
        # Retain this graph only during TTC inspection. Different cmaps can
        # reuse identical outlines without rewalking millions of gvar points.
        details['glyphBounds'] = memo
    return {'bounds': result, 'glyphs': len(boxes), 'deltaPoints': work,
            'method': 'all-glyphs-support-exclusive-interval-envelope'}


def _face_count(source):
    with Path(source).open('rb') as stream:
        collection = stream.read(4) == b'ttcf'
    if not collection:
        return 1, False
    fonts = TTCollection(source, lazy=True)
    try:
        count = len(fonts.fonts)
        if not 1 <= count <= 64:
            raise ValueError('可变字体集合字面数量超出限制')
        return count, True
    finally:
        fonts.close()


def inspect_source(source, progress=None):
    try:
        count, collection = _face_count(source)
        faces, geometries = [], {}
        for index in range(count):
            kwargs = {'fontNumber': index} if collection else {}
            with TTFont(source, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as font:
                _supported(font)
                # Region faces in a TTC can share the same glyf/gvar storage.
                # Its immutable table spans prove reuse without decoding all
                # those outline deltas again for each cmap/name variant.
                geometry_key = (int(font['head'].unitsPerEm), int(font['head'].indexToLocFormat), tuple(
                    (tag, font.reader.tables[tag].offset, font.reader.tables[tag].length)
                    for tag in ('glyf', 'loca', 'gvar', 'hmtx', 'hhea', 'vmtx', 'vhea', 'maxp', 'post', 'fvar') if tag in font))
                measured = geometries.get(geometry_key)
                if measured is None:
                    # One outline graph at a time, even for a heterogeneous
                    # 64-face TTC. Adjacent region faces still reuse the graph.
                    geometries.clear()
                    glyph_bounds = None
                    details = {}
                    geometry = envelope(font, (lambda message, fraction: progress(message, (index + .7 * fraction) / count)) if progress else None,
                                        details=details)
                    measured = geometry, details['glyphBounds']
                    geometries[geometry_key] = measured
                geometry, glyph_bounds = measured
                def vertical_bounds(name):
                    box = glyph_bounds.get(name)
                    return (box[1], box[3]) if box is not None else None
                geometry = dict(geometry, references=_reference_profile(font, vertical_bounds))
                faces.append({'face': index, 'geometry': geometry,
                    'contract': _contract(font, (lambda message, fraction: progress(message, (index + .7 + .3 * fraction) / count)) if progress else None), 'upem': int(font['head'].unitsPerEm),
                    'sourceHead': [int(font['head'].yMin), int(font['head'].yMax)]})
            del font
            gc.collect()
        return {'schema': 'ziyu-variable-clipping-v1', 'collection': collection, 'faces': faces}
    finally:
        # Only immutable summaries cross a worker fork, not FontTools graphs.
        gc.collect()


def line_contract(stock):
    h, o, head = stock['hhea'], stock['OS/2'], stock['head']
    return (int(head.unitsPerEm), h.ascent, h.descent, h.lineGap,
        o.sTypoAscender, o.sTypoDescender, o.sTypoLineGap,
        o.usWinAscent, o.usWinDescent, bool(o.fsSelection & 128), head.yMin, head.yMax)


def _stock_geometry(stock):
    """Read checked glyf bounding headers, without expanding any point data."""
    head = stock['head']
    if 'glyf' not in stock or 'loca' not in stock or stock.reader is None:
        return int(head.yMin), int(head.yMax), 'stock-declared-outline-bounds', ()
    filename = getattr(stock.reader.file, 'name', None)
    try:
        pin = file_identity(Path(filename).stat())
        key = (str(filename), pin, int(head.indexToLocFormat), tuple(
            (tag, stock.reader.tables[tag].offset, stock.reader.tables[tag].length)
            for tag in ('glyf', 'loca', 'cmap', 'maxp', 'post') if tag in stock))
    except (TypeError, OSError):
        key = None
    if key is not None and key in _STOCK_GEOMETRY:
        return _STOCK_GEOMETRY[key]
    raw, offsets = stock.reader['glyf'], stock['loca'].locations
    if (len(offsets) != stock['maxp'].numGlyphs + 1 or not offsets or offsets[0] != 0
            or offsets[-1] > len(raw) or any(a > b for a, b in zip(offsets, offsets[1:]))):
        raise ValueError('原厂字形索引无效')
    low, high = math.inf, -math.inf
    boxes = []
    for index, (start, end) in enumerate(zip(offsets, offsets[1:])):
        if index % 512 == 0:
            check_mix_cancellation()
        if start == end:
            boxes.append(None)
            continue
        if end - start < 10:
            raise ValueError('原厂字形边界头不完整')
        contours, x0, y0, x1, y1 = struct.unpack_from('>5h', raw, start)
        if contours < -1 or x0 > x1 or y0 > y1:
            raise ValueError('原厂字形边界无效')
        if contours:
            low, high = min(low, y0), max(high, y1)
        boxes.append((y0, y1) if contours else None)
    if not math.isfinite(low) or high <= low:
        raise ValueError('原厂字体没有可用字形边界')
    glyph_ids = stock.getReverseGlyphMap()
    result = (low, high, 'stock-glyf-bounding-headers',
              _reference_profile(stock, lambda name: boxes[glyph_ids[name]]))
    if key is not None:
        if len(_STOCK_GEOMETRY) >= 128:
            _STOCK_GEOMETRY.clear()
        _STOCK_GEOMETRY[key] = result
    return result


def fitting_contract(stock):
    return line_contract(stock) + _stock_geometry(stock)


def container_contract(stock):
    count, collection = _face_count(stock)
    result = []
    for index in range(count):
        kwargs = {'fontNumber': index} if collection else {}
        with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as face:
            result.append(fitting_contract(face))
    return tuple(result)


def _reference_limits(facts, contract):
    source_upem, stock_upem = facts['upem'], contract[0]
    if not all(16 <= value <= 16384 for value in (source_upem, stock_upem)):
        raise ValueError('字体 UPEM 无效')
    unit = source_upem / stock_upem
    asc, desc = (contract[4], contract[5]) if contract[9] else (contract[1], contract[2])
    source = {row[0]: row[1:] for row in facts['geometry'].get('references', ())}
    limits = []
    for group, low, high, count in contract[15]:
        if group in source:
            source_low, source_high, _ = source[group]
            limits.append((group, source_low, source_high,
                           min(desc, low) * unit, max(asc, high) * unit))
    if not limits:
        # Other scripts/formats keep the conservative full-envelope policy.
        bounds = facts['geometry']['bounds']
        limits = [('full-envelope', bounds[1], bounds[3],
                   min(desc, contract[12]) * unit, max(asc, contract[13]) * unit)]
    for _, low, high, bottom, top in limits:
        if (not all(math.isfinite(v) for v in (low, high, bottom, top))
                or not (low <= high and 0 < top and bottom <= 0 and top - bottom > 2)):
            raise ValueError('原厂可变字体行框无效')
    return tuple(limits)


def _required_upem(facts, limits):
    scale = 1.0
    for _, low, high, bottom, top in limits:
        if low < bottom or high > top:
            scale = min(scale, (top - 2) / high if high > 0 else 1.0,
                        (bottom + 2) / low if low < 0 else 1.0)
    if not math.isfinite(scale) or scale <= 0:
        raise ValueError('无法计算字体比例，当前字体未改动')
    source_upem = facts['upem']
    output_upem = max(source_upem, math.ceil(source_upem / scale - 1e-12))
    if output_upem > 16384:
        raise ValueError('此字体超出可保留连续字重的适配范围')
    return output_upem


def _fit_parameters(facts, contract, output_upem):
    limits = _reference_limits(facts, contract)
    if not _required_upem(facts, limits) <= output_upem <= 16384:
        raise ValueError('统一字体比例不能放入参照行框')
    source_upem, stock_upem = facts['upem'], contract[0]
    low, high = facts['geometry']['bounds'][1], facts['geometry']['bounds'][3]
    frame = (math.floor(min(facts['sourceHead'][0], low)),
             math.ceil(max(facts['sourceHead'][1], high)))
    if not all(-32768 <= value <= 32767 for value in frame):
        raise ValueError('可变轮廓边界超出字体字段范围')
    # Full bounds still protect head; sizing limits protect normal text rather
    # than forcing a rare symbol/combining stack to determine every glyph size.
    factor = output_upem / source_upem
    limits = tuple((group, low, high, bottom * factor, top * factor)
                   for group, low, high, bottom, top in limits)
    asc, desc = (contract[4], contract[5]) if contract[9] else (contract[1], contract[2])
    return (output_upem / stock_upem, max(asc, contract[13]) * output_upem / stock_upem,
            min(desc, contract[12]) * output_upem / stock_upem, frame, limits)


def _set_line_metrics(font, contract, output_upem, unit, frame):
    h, o, head = font['hhea'], font['OS/2'], font['head']
    h.ascent, h.descent, h.lineGap = [round(v * unit) for v in contract[1:4]]
    o.sTypoAscender, o.sTypoDescender, o.sTypoLineGap = [round(v * unit) for v in contract[4:7]]
    o.usWinAscent, o.usWinDescent = [round(v * unit) for v in contract[7:9]]
    if (not all(-32768 <= value <= 32767 for value in (
            h.ascent, h.descent, h.lineGap, o.sTypoAscender, o.sTypoDescender, o.sTypoLineGap))
            or not all(0 <= value <= 65535 for value in (o.usWinAscent, o.usWinDescent))):
        raise ValueError('原厂行高超出字体字段范围')
    o.fsSelection = (o.fsSelection & ~128) | (128 if contract[9] else 0)
    head.unitsPerEm = output_upem
    head.yMin, head.yMax = frame
    # Existing policy: target OEM line metrics override source MVAR line deltas.
    for tag in ('MVAR', 'DSIG'):
        if tag in font:
            del font[tag]


def prepare_templates(source, stocks, workspace, facts, digests=None, progress=None):
    """Reference each deeply checked source face; do not scale or copy it."""
    source = Path(source)
    digests = digests if digests is not None else FontDigestCache()
    source_identity = file_identity(source.stat())
    source_hash = digests.sha256(source)
    if source.is_symlink() or source_hash != facts.get('sourceSha256'):
        raise ValueError('防裁切测量与来源不一致')
    faces, targets, pinned = facts['faces'], [[] for _ in facts['faces']], {}
    for stock in sorted({Path(path) for path in stocks}):
        if stock.is_symlink():
            raise ValueError('原厂字体快照不能是符号链接')
        pinned[stock] = file_identity(stock.stat()), digests.sha256(stock)
        count, collection = _face_count(stock)
        if collection and len(faces) not in (1, count):
            raise ValueError('来源与原厂集合的字面索引不兼容，未固定字重')
        for index in range(count if collection else len(faces)):
            kwargs = {'fontNumber': index} if collection else {}
            with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as font:
                contract = fitting_contract(font)
            face_index = index if len(faces) > 1 else 0
            targets[face_index].append(contract)
    if not targets or any(not rows for rows in targets):
        raise ValueError('缺少经过核验的原厂目标')
    prepared = []
    for index, face in enumerate(faces):
        check_mix_cancellation()
        if progress:
            progress(f'正在核对原字形 {index + 1}/{len(faces)}', index / len(faces))
        hashes = face['contract']['tableHashes']
        contracts = sorted(set(targets[index]))
        output_upem = max(_required_upem(face, _reference_limits(face, contract)) for contract in contracts)
        prepared.append({'path': str(source), 'identity': source_identity, 'sha256': source_hash,
            'face': face['face'], 'collection': facts['collection'], 'tableHashes': hashes,
            'targetContracts': contracts, 'outputUpem': output_upem})
    if (file_identity(source.stat()) != source_identity or digests.sha256(source) != source_hash
            or any((file_identity(path.stat()), digests.sha256(path)) != pin for path, pin in pinned.items())):
        raise ValueError('防裁切准备期间来源或原厂快照已变化')
    if progress:
        progress('原字形核对完成', 1)
    return {'sourceSha256': source_hash, 'sourceIdentity': source_identity,
            'faces': prepared, 'templateBytes': 0, 'method': 'shared-reference-em-fit'}


def _verify_retarget(font, template, report):
    if set(map(str, font.reader.keys())) != set(template['tableHashes']) | HEADER_TABLES:
        raise ValueError('可变字体的输出表清单发生变化')
    for tag, expected in template['tableHashes'].items():
        if hashlib.sha256(font.reader[tag]).hexdigest() != expected:
            raise ValueError(f'可变字体输出的 {tag} 表发生变化')
    if (line_contract(font) != tuple(report['lineMetricsContract'])
            or font['head'].unitsPerEm != report['outputUpem']
            or font['head'].unitsPerEm != template['outputUpem']):
        raise ValueError('字体比例或原厂行高未通过校验')
    fit = report['clippingFit']
    bounds = fit['measurement']['bounds']
    if bounds[1] < font['head'].yMin or bounds[3] > font['head'].yMax:
        raise ValueError('连续可变轮廓超出字体声明边界')
    for _, low, high, bottom, top in fit['referenceLimits']:
        if low < bottom or high > top:
            raise ValueError('常用文字的连续可变轮廓超出参照行框')


def _retarget_face(template, stock, output, facts, digests, source_hash, metrics_io):
    path, output = Path(template['path']), Path(output)
    pin = tuple(template['identity'])
    if path.is_symlink() or file_identity(path.stat()) != pin or digests.sha256(path) != template['sha256']:
        raise ValueError('已校验的可变字体发生变化')
    contract = fitting_contract(stock)
    if contract not in map(tuple, template['targetContracts']):
        raise ValueError('原厂字形信息已变化，请重新应用字体')
    output_upem = template['outputUpem']
    unit, top, bottom, frame, limits = _fit_parameters(facts, contract, output_upem)
    bounds = facts['geometry']['bounds']
    report = {'slotCompatibility': 'variable-source-preserved',
        'sourceUpem': facts['upem'], 'outputUpem': output_upem, 'sourceHead': facts['sourceHead'],
        'outputHead': list(frame), 'layoutBoundsSource': contract[14],
        'sourceCmapPreserved': True, 'stockCmapPreserved': False, 'stockGlyphIdsPreserved': False,
        'sourceGlyphIdsPreserved': True, 'sourceVariationAxesPreserved': True,
        'replacementVariation': 'continuous-source-axes', 'sharedVariableOutline': True,
        'sourceOutlineTablesByteIdentical': True, 'sourceSha256': source_hash,
        'clippingFit': {'enabled': True, 'state': 'reference-fitted' if output_upem != facts['upem'] else 'reference-fits',
            'scale': facts['upem'] / output_upem, 'baseline': 0, 'targetTop': top, 'targetBottom': bottom,
            'stockInk': list(contract[12:14]), 'continuousWeight': True, 'sharedSourceScale': True,
            'sizingPolicy': 'common-script-ranges' if limits[0][0] != 'full-envelope' else 'full-envelope-fallback',
            'referenceLimits': limits, 'allGlyphsFit': bounds[1] >= bottom and bounds[3] <= top,
            'otherGlyphs': 'preserved-with-full-bounds; fixed-height clipping not guaranteed',
            'transform': 'em-only', 'measurement': facts['geometry']}}
    try:
        kwargs = {'fontNumber': template['face']} if template['collection'] else {}
        cache_key = pin + (template['face'],)
        with TTFont(path, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as font:
            metrics_io.seed(font, cache_key)
            _set_line_metrics(font, contract, output_upem, unit, frame)
            report['lineMetricsContract'] = list(line_contract(font))
            output.parent.mkdir(parents=True, exist_ok=True)
            metrics_io.save(font, output, cache_key, HEADER_TABLES)
            metrics_io.remember(font, cache_key)
        if not 12 <= output.stat().st_size <= MAX_BYTES:
            raise ValueError('可变字体输出大小无效')
        with TTFont(output, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
            _verify_retarget(check, template, report)
        if file_identity(path.stat()) != pin or digests.sha256(path) != template['sha256']:
            raise ValueError('可变字体在写入期间发生变化')
        output.chmod(0o644)
        report['outputSha256'] = digests.sha256(output)
        return report
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def fit_container(source, stock, output, facts, digests=None, *, templates=None, metrics_io=None):
    """Preserve every source face and continuous variation, with raw table proof."""
    source, stock, output = map(Path, (source, stock, output))
    digests = digests if digests is not None else FontDigestCache()
    if (source.is_symlink() or stock.is_symlink() or output.is_symlink()
            or output.resolve() in (source.resolve(), stock.resolve())):
        raise ValueError('防裁切输出不能覆盖来源或原厂字体')
    before = file_identity(source.stat()), file_identity(stock.stat())
    source_hash, stock_hash = digests.sha256(source), digests.sha256(stock)
    if source_hash != facts.get('sourceSha256'):
        raise ValueError('防裁切测量与来源摘要不一致')
    if templates is None:
        templates = prepare_templates(source, [stock], output.parent, facts, digests)
    if (templates['sourceSha256'] != source_hash or tuple(templates['sourceIdentity']) != before[0]
            or len(templates['faces']) != len(facts['faces'])):
        raise ValueError('共享可变字体与本次来源不一致')
    metrics_io = metrics_io if metrics_io is not None else FontMetricsIO()
    stock_count, stock_collection = _face_count(stock)
    source_faces = facts['faces']
    if stock_collection and len(source_faces) not in (1, stock_count):
        raise ValueError('来源与原厂集合的字面索引不兼容，未固定字重')
    count = stock_count if stock_collection else len(source_faces)
    make_collection = stock_collection or facts['collection']
    output.parent.mkdir(parents=True, exist_ok=True)
    reports, paths = [], []
    try:
        with tempfile.TemporaryDirectory(prefix='variable-clipping-', dir=output.parent) as folder:
            for index in range(count):
                check_mix_cancellation()
                source_index = index if len(source_faces) > 1 else 0
                stock_args = {'fontNumber': index} if stock_collection else {}
                path = Path(folder) / f'face-{index}.ttf'
                with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **stock_args) as target:
                    report = _retarget_face(templates['faces'][source_index], target, path,
                        source_faces[source_index], digests, source_hash, metrics_io)
                reports.append(report); paths.append(path)
            if make_collection:
                from composite_collection_build import _save_collection
                fonts = [TTFont(path, lazy=True, recalcBBoxes=False, recalcTimestamp=False) for path in paths]
                try:
                    _save_collection(fonts, output, MAX_BYTES)
                finally:
                    for font in fonts:
                        font.close()
            else:
                paths[0].replace(output)
            if not 12 <= output.stat().st_size <= MAX_BYTES:
                raise ValueError('可变防裁切集合超出大小限制')
            if make_collection:
                actual_count, is_collection = _face_count(output)
                if not is_collection or actual_count != count:
                    raise ValueError('可变防裁切集合字面数量发生变化')
                for index, report in enumerate(reports):
                    with TTFont(output, fontNumber=index, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
                        _verify_retarget(check, templates['faces'][index if len(source_faces) > 1 else 0], report)
            if ((file_identity(source.stat()), file_identity(stock.stat())) != before
                    or digests.sha256(source) != source_hash or digests.sha256(stock) != stock_hash):
                raise ValueError('可变防裁切生成期间来源已变化')
        output.chmod(0o644)
        if not make_collection:
            return dict(reports[0], stockSha256=stock_hash, outputSha256=digests.sha256(output))
        return {'slotCompatibility': 'variable-source-preserved',
                'sourceVariationAxesPreserved': True, 'sourceCmapPreserved': True,
                'stockCmapPreserved': False, 'replacementVariation': 'continuous-source-axes',
                'sourceFacesPreserved': len(source_faces), 'outputFaces': count,
                'clippingFit': {'enabled': True, 'continuousWeight': True, 'faces': reports},
                'sourceSha256': source_hash, 'stockSha256': stock_hash,
                'outputSha256': digests.sha256(output)}
    except BaseException:
        output.unlink(missing_ok=True)
        raise
