#!/usr/bin/env python3
"""Fit oversized UI numerals using verified OEM advances. GPL-3.0-only.

Only header units and line metrics change. Every outline, layout, bitmap, SVG
and variation table stays byte-identical, including continuous weight axes.
"""
from __future__ import annotations

import hashlib
import math
from pathlib import Path

from fontTools.ttLib import TTFont, TTLibError
from font_digest_cache import FontDigestCache, file_identity
from font_metrics_io import FontMetricsIO
import stock_font_snapshot

HEADERS = frozenset(('head', 'hhea', 'OS/2'))


def dedicated_digit_slot(logical):
    # Changing unitsPerEm scales every glyph in the file. Only apply it
    # to the dedicated numerals face, never to shared text families.
    return Path(logical).name.lower() == 'osans-solid-digits-vf.ttf'


def glyph_closure(font, names):
    names = set(names)
    if 'GSUB' not in font:
        return names
    lookups = getattr(font['GSUB'].table.LookupList, 'Lookup', [])
    before = set()
    while before != names:
        before = set(names)
        for lookup in lookups:
            for table in lookup.SubTable:
                table = getattr(table, 'ExtSubTable', table)
                for original, replacement in getattr(table, 'mapping', {}).items():
                    if original in names:
                        if not isinstance(replacement, str):
                            raise ValueError('数字多字形替换需要独立字宽适配')
                        names.add(replacement)
                for original, alternatives in getattr(table, 'alternates', {}).items():
                    if original in names:
                        names.update(alternatives)
                if any(original in names for original in getattr(table, 'ligatures', {})):
                    raise ValueError('数字连字需要独立字宽适配')
        if len(names) > 256:
            raise ValueError('数字替代字形超出适配范围')
    return names


def advance_upper(font, name):
    advance = font['hmtx'].metrics[name][0]
    if 'HVAR' in font:
        table = font['HVAR'].table
        mapping = table.AdvWidthMap
        index = mapping.mapping[name] if mapping is not None else font.getGlyphID(name)
        if index != 0xFFFFFFFF:
            data = table.VarStore.VarData[index >> 16]
            # Region scalars are in [0, 1]. Summing positive deltas bounds the
            # full continuous design space without freezing or sampling axes.
            advance += sum(max(0, delta) for delta in data.Item[index & 0xFFFF])
    elif 'gvar' in font:
        for variation in font['gvar'].variations.get(name, []):
            left, right = variation.coordinates[-4:-2]
            advance += max(0, (right[0] if right else 0) - (left[0] if left else 0))
    if not math.isfinite(advance) or not 0 < advance <= 65535:
        raise ValueError('数字字宽数据无效')
    return advance


def clock_width(font, continuous):
    cmap = font.getBestCmap() or {}
    if not all(code in cmap for code in (*range(48, 58), 58)):
        raise ValueError('缺少完整数字和冒号，保留当前比例')
    digits = glyph_closure(font, (cmap[code] for code in range(48, 58)))
    colons = glyph_closure(font, [cmap[58]])
    width = (lambda name: advance_upper(font, name)) if continuous else (lambda name: font['hmtx'].metrics[name][0])
    return 4 * max(map(width, digits)) + max(map(width, colons))


class UiWidthFit:
    def __init__(self, module, workspace):
        self.module, self.workspace = Path(module), Path(workspace)
        self.digests = FontDigestCache()
        self.io = FontMetricsIO()
        self.profiles = {}
        self.outputs = {}
        self.view = None

    def close(self):
        if self.view is not None:
            self.view.close()

    def reference(self, logical):
        if logical in self.profiles:
            return self.profiles[logical]
        source = stock_font_snapshot.resolve(logical, self.module, self.digests)
        if source is None:
            from stock_readonly_view import StockReadonlyView
            if self.view is None:
                self.view = StockReadonlyView()
            root = self.view.pick(Path(logical).parent)
            if root is None:
                raise ValueError('缺少可信原厂数字字宽，保留当前比例')
            source = root / Path(logical).name
        before = file_identity(source.stat())
        if source.is_symlink():
            raise ValueError('原厂数字字体链接需先补齐可信快照')
        sha = self.digests.sha256(source)
        with TTFont(source, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as font:
            result = (clock_width(font, False), int(font['head'].unitsPerEm), sha)
        if before != file_identity(source.stat()) or self.digests.sha256(source) != sha:
            raise ValueError('原厂数字字宽来源已变化')
        self.profiles[logical] = result
        return result

    def prepare(self, source, logical):
        source = Path(source)
        try:
            reference, reference_upem, reference_sha = self.reference(logical)
            before = file_identity(source.stat())
            key = before, reference, reference_upem
            if key in self.outputs:
                output, report, pinned, sha = self.outputs[key]
                if file_identity(output.stat()) != pinned or self.digests.sha256(output) != sha:
                    raise ValueError('数字字宽输出在本批次中变化')
                return output, dict(report, uiWidthReused=True)
            with TTFont(source, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as font:
                if any(tag in font for tag in ('CBDT', 'CBLC', 'sbix', 'EBDT', 'EBLC', 'bdat', 'bloc')):
                    raise ValueError('位图数字需要独立比例适配，保留当前比例')
                old_upem = int(font['head'].unitsPerEm)
                if not 16 <= old_upem <= 16384:
                    raise ValueError('来源字体单位比例无效')
                if not 16 <= reference_upem <= 16384 or reference <= 0:
                    raise ValueError('原厂数字比例无效')
                width = clock_width(font, True)
                new_upem = max(old_upem, math.ceil(width * reference_upem / reference))
                if new_upem == old_upem:
                    return source, {'uiWidthState': 'fits', 'uiWidthScale': 1.0}
                if new_upem > 16384:
                    raise ValueError('数字比例超出保留连续字重的适配范围')
                unchanged = {str(tag): hashlib.sha256(font.reader[tag]).hexdigest()
                             for tag in font.reader.keys() if str(tag) not in HEADERS}
                factor = new_upem / old_upem
                hhea, os2 = font['hhea'], font['OS/2']
                fields = [(hhea, name, -32768, 32767) for name in ('ascent', 'descent', 'lineGap')]
                fields += [(os2, name, -32768, 32767) for name in ('sTypoAscender', 'sTypoDescender', 'sTypoLineGap')]
                fields += [(os2, name, 0, 65535) for name in ('usWinAscent', 'usWinDescent')]
                for table, name, low, high in fields:
                    value = round(getattr(table, name) * factor)
                    if not low <= value <= high:
                        raise ValueError('数字比例所需行高超出字体字段范围')
                    setattr(table, name, value)
                font['head'].unitsPerEm = new_upem
                output = self.workspace / f'ui-width-{len(self.outputs)}.font'
                self.io.save(font, output, before, HEADERS)
            # This is an output contract in the normal apply path. It binds
            # every original data table, including all variation information.
            with TTFont(output, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
                actual = {str(tag): hashlib.sha256(check.reader[tag]).hexdigest()
                          for tag in check.reader.keys() if str(tag) not in HEADERS}
                if actual != unchanged or check['head'].unitsPerEm != new_upem:
                    raise ValueError('数字字宽输出未保留原始字形和连续字重数据')
            if before != file_identity(source.stat()):
                raise ValueError('数字字宽来源在生成时变化')
            output.chmod(0o644)
            report = {'uiWidthState': 'fitted', 'uiWidthScale': old_upem / new_upem,
                      'uiWidthSourceUpem': old_upem, 'uiWidthOutputUpem': new_upem,
                      'uiWidthReferenceSha256': reference_sha,
                      'uiWidthVariationTablesPreserved': True,
                      'uiWidthScope': 'dedicated-digit-slot'}
            self.outputs[key] = output, report, file_identity(output.stat()), self.digests.sha256(output)
            return output, report
        except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError, TTLibError) as error:
            return source, {'uiWidthState': 'preserved', 'uiWidthReason': str(error)}
