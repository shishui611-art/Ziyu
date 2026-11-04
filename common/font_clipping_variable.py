#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fit TrueType variation geometry without pinning or removing design axes.

The interval envelope encloses every tuple scalar in [0, 1], including IUP
inferred deltas and nested XY-positioned components. It is deliberately
conservative rather than a sample of a few weights. Only private staged fonts
are written; the normal switch transaction owns verification and publication.
"""
from __future__ import annotations

import gc
import hashlib
import json
import math
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont, TTCollection
from fontTools.ttLib.scaleUpem import ScalerVisitor
from fontTools.ttLib.tables._g_l_y_f import (
    SCALED_COMPONENT_OFFSET, UNSCALED_COMPONENT_OFFSET, SCALE_COMPONENT_OFFSET_DEFAULT)
from fontTools.varLib.iup import iup_delta

from font_digest_cache import FontDigestCache, file_identity
from font_instance import check_mix_cancellation
from font_native_instance import _cmap

MAX_BYTES = 128 * 1024 * 1024
MAX_DELTA_POINTS = 100_000_000
UNSUPPORTED = frozenset(('CFF2', 'VARC', 'COLR', 'CBDT', 'CBLC', 'EBDT', 'EBLC', 'EBSC', 'bdat', 'bloc', 'sbix', 'SVG ',
    'morx', 'mort', 'kerx', 'ankr', 'Silf', 'Glat', 'Gloc', 'Feat', 'Sill',
    'trak', 'bsln', 'opbd', 'prop', 'lcar', 'just'))
HINT_TABLES = ('cvt ', 'cvar', 'fpgm', 'prep', 'hdmx', 'VDMX', 'LTSH', 'DSIG')


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


def _contract(font):
    """Bind glyph mappings, all axis ranges/instances, and tuple topology."""
    _supported(font)
    digest = hashlib.sha256()
    order = font.getGlyphOrder()
    mappings, uvs = _cmap(font)
    for item in (order, sorted(mappings), sorted(uvs, key=lambda row: (row[0], row[1], str(row[2])))):
        digest.update(json.dumps(item, ensure_ascii=True, separators=(',', ':')).encode())
    axes = {tag: hashlib.sha256(font.getTableData(tag)).hexdigest()
            for tag in ('fvar', 'avar', 'STAT') if tag in font}
    active = set()
    tuples = 0
    for name in order:
        for variation in font['gvar'].variations.get(name, []):
            tuples += 1
            digest.update(json.dumps((name, sorted(variation.axes.items()), len(variation.coordinates)),
                                     separators=(',', ':')).encode())
            if any(xy is not None and (xy[0] or xy[1]) for xy in variation.coordinates[:-4]):
                active.update(tag for tag, values in variation.axes.items() if values[1])
    return {'mappingAndTopology': digest.hexdigest(), 'axisTables': axes,
            'activeOutlineAxes': sorted(active), 'tuples': tuples,
            'variationTables': sorted(tag for tag in ('gvar', 'HVAR', 'VVAR') if tag in font)}


def envelope(font):
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
        for variation in variations.get(name, []):
            if len(variation.coordinates) != len(coords):
                raise ValueError('字体可变轮廓点数不匹配')
            work += size
            if work > MAX_DELTA_POINTS:
                raise ValueError('字体可变轮廓过于复杂，未转换为固定字重')
            delta = variation.coordinates
            if not glyph.isComposite() and None in delta:
                # Do not expand the stored sparse tuples: that increases output
                # size and changes the source representation unnecessarily.
                delta = iup_delta(delta, coords, controls.endPts)
            for i in range(size):
                value = delta[i]
                if value is None:
                    continue  # Composite placements do not use IUP.
                x, y = value
                if not math.isfinite(x) or not math.isfinite(y):
                    raise ValueError('字体可变轮廓含无效坐标')
                lo_x[i] += min(0, x); hi_x[i] += max(0, x)
                lo_y[i] += min(0, y); hi_y[i] += max(0, y)
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
    for index, name in enumerate(font.getGlyphOrder()):
        if index % 128 == 0:
            check_mix_cancellation()
        box = bounds(name)
        if box is not None:
            boxes.append(box)
    if not boxes:
        raise ValueError('字体没有可测量的可变轮廓')
    result = [min(b[0] for b in boxes), min(b[1] for b in boxes),
              max(b[2] for b in boxes), max(b[3] for b in boxes)]
    if not all(math.isfinite(v) for v in result):
        raise ValueError('字体可变边界无效')
    return {'bounds': result, 'glyphs': len(boxes), 'deltaPoints': work,
            'method': 'all-glyphs-all-tuples-interval-envelope'}


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


def inspect_source(source):
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
                geometry_key = (int(font['head'].unitsPerEm), tuple(
                    (tag, font.reader.tables[tag].offset, font.reader.tables[tag].length)
                    for tag in ('glyf', 'loca', 'gvar', 'hmtx', 'vmtx', 'maxp', 'post', 'fvar') if tag in font))
                geometry = geometries.get(geometry_key)
                if geometry is None:
                    geometry = envelope(font)
                    geometries[geometry_key] = geometry
                faces.append({'face': index, 'geometry': geometry,
                    'contract': _contract(font), 'upem': int(font['head'].unitsPerEm),
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


def container_contract(stock):
    count, collection = _face_count(stock)
    result = []
    for index in range(count):
        kwargs = {'fontNumber': index} if collection else {}
        with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as face:
            result.append(line_contract(face))
    return tuple(result)


def _fit_face(source, stock, output, facts, digests, source_hash, collection=False):
    source, output = Path(source), Path(output)
    if source.is_symlink() or output.is_symlink() or source.resolve() == output.resolve():
        raise ValueError('防裁切不能覆盖来源字体')
    before = file_identity(source.stat())
    if not 12 <= before[2] <= MAX_BYTES:
        raise ValueError('可变字体大小无效')
    contract = line_contract(stock)
    upem = facts['upem']
    unit = upem / contract[0]
    tops = [v * unit for v in (contract[1], contract[4], contract[7], contract[11]) if v > 0]
    bottoms = [v * unit for v in (contract[2], contract[5], -contract[8], contract[10]) if v < 0]
    if not 16 <= upem <= 16384 or not tops or not bottoms:
        raise ValueError('原厂可变字体行框无效')
    top, bottom = min(tops), max(bottoms)
    if top - bottom <= 32:
        raise ValueError('原厂字体行框过小')
    low, high = facts['geometry']['bounds'][1], facts['geometry']['bounds'][3]
    scale = min(1.0, (top - 16) / high if high > 0 else 1.0,
                (bottom + 16) / low if low < 0 else 1.0)
    if not 1 / 64 <= scale <= 1:
        raise ValueError('字体需要过度缩小，已保留原字体与可变字重')
    output_frame = (min(round(contract[10] * unit), math.floor(low * scale - 16)),
                    max(round(contract[11] * unit), math.ceil(high * scale + 16)))
    if not all(-32768 <= value <= 32767 for value in output_frame):
        raise ValueError('可变轮廓边界超出字体字段范围')
    try:
        kwargs = {'fontNumber': facts['face']} if collection else {}
        with TTFont(source, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as font:
            _supported(font)
            check_mix_cancellation()
            if scale < 1:
                # The official visitor scales base points, sparse gvar deltas,
                # component placements and layout/metric variation stores together.
                # avar v2's VarStore contains normalized axis coordinates,
                # not design-unit distances. Preserve it outside the metric
                # visitor so width/weight normalization stays byte-identical.
                axis_mapping = font['avar'] if 'avar' in font else None
                if axis_mapping is not None:
                    del font['avar']
                try:
                    ScalerVisitor(scale).visit(font)
                finally:
                    if axis_mapping is not None:
                        font['avar'] = axis_mapping
                font['head'].unitsPerEm = upem
                for glyph in font['glyf'].glyphs.values():
                    glyph.removeHinting()
                for tag in HINT_TABLES:
                    if tag in font:
                        del font[tag]
            h, o, head = font['hhea'], font['OS/2'], font['head']
            h.ascent, h.descent, h.lineGap = [round(v * unit) for v in contract[1:4]]
            o.sTypoAscender, o.sTypoDescender, o.sTypoLineGap = [round(v * unit) for v in contract[4:7]]
            o.usWinAscent, o.usWinDescent = [round(v * unit) for v in contract[7:9]]
            o.fsSelection = (o.fsSelection & ~128) | (128 if contract[9] else 0)
            # A stock default-instance bbox cannot stand in for a variable
            # outline envelope. Keep OEM line metrics, but declare enough global
            # bounds for every fitted instance rather than clipping its head.
            head.yMin, head.yMax = output_frame
            if 'MVAR' in font:
                del font['MVAR']  # Match the established fixed OEM line-metric path.
            output.parent.mkdir(parents=True, exist_ok=True)
            font.save(output, reorderTables=False)
        del font
        gc.collect()
        if not 12 <= output.stat().st_size <= MAX_BYTES:
            raise ValueError('可变防裁切输出大小无效')
        with TTFont(output, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
            actual = _contract(check)
            expected = facts['contract']
            if (actual['mappingAndTopology'] != expected['mappingAndTopology']
                    or actual['axisTables'] != expected['axisTables']
                    or actual['variationTables'] != expected['variationTables']
                    or not set(expected['activeOutlineAxes']) <= set(actual['activeOutlineAxes'])):
                raise ValueError('可变字重或字符映射未通过输出校验')
            verified = envelope(check)
            if (verified['bounds'][1] < bottom or verified['bounds'][3] > top
                    or verified['bounds'][1] < check['head'].yMin
                    or verified['bounds'][3] > check['head'].yMax):
                raise ValueError('序列化后的可变轮廓超出行框')
        del check
        gc.collect()
        if file_identity(source.stat()) != before or digests.sha256(source) != source_hash:
            raise ValueError('可变防裁切处理期间来源发生变化')
        output.chmod(0o644)
        return {'slotCompatibility': 'variable-source-preserved',
            'sourceUpem': upem, 'outputUpem': upem, 'sourceHead': facts['sourceHead'],
            'outputHead': list(output_frame),
            'layoutBoundsSource': 'stock-line-and-variable-outline-envelope', 'sourceCmapPreserved': True,
            'stockCmapPreserved': False, 'stockGlyphIdsPreserved': False,
            'sourceGlyphIdsPreserved': True, 'sourceVariationAxesPreserved': True,
            'replacementVariation': 'continuous-source-axes',
            'clippingFit': {'enabled': True, 'state': 'fitted' if scale < 1 else 'already-fits',
                'scale': scale, 'baseline': 0, 'targetTop': top, 'targetBottom': bottom,
                'continuousWeight': True, 'measurement': verified},
            'sourceSha256': source_hash, 'outputSha256': digests.sha256(output)}
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def fit_container(source, stock, output, facts, digests=None):
    """Keep source face indexes, or fill an OEM collection from one variable face."""
    source, stock, output = map(Path, (source, stock, output))
    digests = digests if digests is not None else FontDigestCache()
    if (source.is_symlink() or stock.is_symlink() or output.is_symlink()
            or output.resolve() in (source.resolve(), stock.resolve())):
        raise ValueError('可变防裁切输出不能覆盖来源或原厂字体')
    before = file_identity(source.stat()), file_identity(stock.stat())
    source_hash, stock_hash = digests.sha256(source), digests.sha256(stock)
    if source_hash != facts.get('sourceSha256'):
        raise ValueError('可变防裁切测量与来源摘要不一致')
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
                face = source_faces[index if len(source_faces) > 1 else 0]
                stock_args = {'fontNumber': index} if stock_collection else {}
                path = Path(folder) / f'face-{index}.ttf'
                with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **stock_args) as target:
                    report = _fit_face(source, target, path, face, digests, source_hash, facts['collection'])
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
                # The isolated face is already checked. Rename, without copying
                # the complete font again or exposing a partially written result.
                paths[0].replace(output)
            if not 12 <= output.stat().st_size <= MAX_BYTES:
                raise ValueError('可变防裁切集合超出大小限制')
            if make_collection:
                actual_count, is_collection = _face_count(output)
                if not is_collection or actual_count != count:
                    raise ValueError('可变防裁切集合字面数量发生变化')
                for index, report in enumerate(reports):
                    with TTFont(output, fontNumber=index, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
                        expected = source_faces[index if len(source_faces) > 1 else 0]['contract']
                        actual = _contract(check)
                        if (actual['mappingAndTopology'] != expected['mappingAndTopology']
                                or actual['axisTables'] != expected['axisTables']
                                or actual['variationTables'] != expected['variationTables']
                                or not set(expected['activeOutlineAxes']) <= set(actual['activeOutlineAxes'])):
                            raise ValueError('集合合并后可变字重或字符映射校验失败')
                        # Shared-table serialization must preserve the exact
                        # already-verified geometry bytes for this face.
                        with TTFont(paths[index], lazy=True, recalcBBoxes=False, recalcTimestamp=False) as original:
                            for tag in ('glyf', 'gvar', 'hmtx', 'HVAR', 'VVAR'):
                                if tag in original and check.reader[tag] != original.reader[tag]:
                                    raise ValueError('集合合并后可变轮廓或度量数据发生变化')
                    del check
                    gc.collect()
            if ((file_identity(source.stat()), file_identity(stock.stat())) != before
                    or digests.sha256(source) != source_hash or digests.sha256(stock) != stock_hash):
                raise ValueError('可变防裁切集合生成期间来源已变化')
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
