#!/usr/bin/env python3
"""Read-only coverage contracts for additional aliases and OEM theme fonts.

No outlines are rebuilt, no variable font is instantiated. One short-lived pass
shares profiles and verified stock digests across all aliases.
"""
from __future__ import annotations

import argparse
import math
import os
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import unicodedata


def identity(path):
    value = Path(path).stat()
    return (value.st_dev, value.st_ino, value.st_size,
            value.st_mtime_ns, value.st_ctime_ns)


@dataclass
class Profile:
    points: frozenset
    variants: frozenset
    axes: dict
    layout: dict
    variable_outlines: bool


class CoverageGuard:
    def __init__(self):
        self.profiles = {}

    def profile(self, path):
        from fontTools.ttLib import TTFont
        path = Path(path)
        before = identity(path)
        # Paths participate in the key: providers can virtualize inode numbers.
        key = (str(path), before)
        if key in self.profiles:
            return self.profiles[key]
        with path.open('rb') as stream:
            if stream.read(4) == b'ttcf':
                raise ValueError('字体集合保留原文件')
        with TTFont(path, lazy=True, recalcTimestamp=False) as font:
            if any(tag in font for tag in ('COLR', 'CBDT', 'sbix', 'SVG ')):
                raise ValueError('彩色字体保留原文件')
            glyphs = set(font.getGlyphOrder()) - {'.notdef'}
            points = frozenset(cp for cp, glyph in (font.getBestCmap() or {}).items()
                               if glyph in glyphs)
            if not points:
                raise ValueError('字体没有可用字符映射')
            variants = frozenset((cp, selector) for table in font['cmap'].tables
                                 if table.format == 14
                                 for selector, records in table.uvsDict.items()
                                 for cp, glyph in records
                                 if (glyph is None and cp in points) or glyph in glyphs)
            axes = {a.axisTag: (a.minValue, a.maxValue) for a in font['fvar'].axes} if 'fvar' in font else {}
            layout = {}
            for tag in ('GSUB', 'GPOS'):
                if tag not in font:
                    layout[tag] = frozenset()
                    continue
                table = font[tag].table
                features = getattr(getattr(table, 'FeatureList', None), 'FeatureRecord', []) or []
                evidence = set()
                for script in getattr(getattr(table, 'ScriptList', None), 'ScriptRecord', []) or []:
                    languages = [('dflt', script.Script.DefaultLangSys)]
                    languages += [(lang.LangSysTag, lang.LangSys) for lang in script.Script.LangSysRecord]
                    for language, system in languages:
                        if system is None:
                            continue
                        indices = list(system.FeatureIndex)
                        if system.ReqFeatureIndex != 0xFFFF:
                            indices.append(system.ReqFeatureIndex)
                        for index in indices:
                            if not 0 <= index < len(features):
                                raise ValueError('字体排版表无效')
                            evidence.add((script.ScriptTag, language, features[index].FeatureTag))
                layout[tag] = frozenset(evidence)
            result = Profile(points, variants, axes, layout, 'gvar' in font or 'CFF2' in font)
        if identity(path) != before:
            raise ValueError('字体在读取期间发生变化')
        self.profiles[key] = result
        return result

    def check(self, source, target, *, allow_missing=False):
        donor, original = self.profile(source), self.profile(target)
        if not allow_missing:
            required = {cp for cp in original.points
                        if unicodedata.category(chr(cp))[0] in ('L', 'M', 'N')}
            if not required <= donor.points:
                raise ValueError('所选字体缺少该字体的文字，保留原字体')
            if not original.variants <= donor.variants:
                raise ValueError('所选字体缺少变体字符，保留原字体')
        for tag, (minimum, maximum) in original.axes.items():
            donor_range = donor.axes.get(tag)
            if (not donor.variable_outlines or donor_range is None
                    or not all(math.isfinite(value) for value in (minimum, maximum, *donor_range))
                    or minimum >= maximum or donor_range[0] >= donor_range[1]
                    or donor_range[0] > minimum or donor_range[1] < maximum):
                raise ValueError('所选字体不能保留系统连续调整范围')
        # Feature inventories for Latin/CJK often differ without affecting basic
        # rendering. Keep strict shaping checks for complex scripts, where missing
        # features can change or break words, rather than requiring every feature.
        complex_scripts = {'arab', 'syrc', 'nko ', 'deva', 'dev2', 'beng', 'bng2',
                           'guru', 'gur2', 'gujr', 'gjr2', 'orya', 'ory2', 'taml',
                           'tml2', 'telu', 'tel2', 'knda', 'knd2', 'mlym', 'mlm2',
                           'sinh', 'thai', 'lao ', 'tibt', 'mymr', 'mym2', 'khmr'}
        for tag in ('GSUB', 'GPOS'):
            required_layout = {item for item in original.layout[tag] if item[0].lower() in complex_scripts}
            if not required_layout <= donor.layout[tag]:
                raise ValueError('所选字体缺少对应文字排版支持')


def filter_plan(module, target_root):
    from font_digest_cache import FontDigestCache
    from font_role_policy import is_clock_slot, keep_lockscreen
    from font_slot_overrides import exclusion, load as load_overrides
    import stock_font_snapshot

    guard = CoverageGuard()
    digests = FontDigestCache()
    keep_clock = keep_lockscreen(module)
    rules = load_overrides(module / 'config/font-slot-overrides.json')
    planned = []
    verified = []
    for line in sys.stdin:
        fields = line.rstrip('\r\n').split('|')
        if len(fields) != 2:
            raise ValueError('字体扩展计划格式无效')
        source, name = fields
        if not re.fullmatch(r'[A-Za-z0-9_+.-]+\.(?:ttf|otf|TTF|OTF)', name) or '..' in name:
            raise ValueError('字体槽位名称无效')
        logical = str(target_root / name)
        if exclusion(logical, rules) or (keep_clock and is_clock_slot(name)):
            print(f'[SLOT-COVERAGE] preserve={logical} reason=按设置保留原字体', file=sys.stderr)
            continue
        planned.append((source, name, logical))
    if planned and os.environ.get('ZIYU_SLOT_PREPARE_STOCK') == '1':
        result = stock_font_snapshot.ensure_expansion(module, [row[2] for row in planned], allow_partial=True)
        print(f'[SLOT-COVERAGE] stock-preparation={result}', file=sys.stderr)
    snapshot = stock_font_snapshot.load(module)
    for source, name, logical in planned:
        try:
            if logical not in snapshot.get('files', {}):
                raise ValueError('缺少当前 ROM 原厂字库，保留原字体')
            original = stock_font_snapshot.resolve(logical, module, digests)
            if original is None:
                raise ValueError('原厂字库核验未通过，保留原字体')
            guard.check(source, original, allow_missing=True)
            verified.append((Path(source), name, identity(source), original, identity(original)))
        except (OSError, ValueError, KeyError) as error:
            print(f'[SLOT-COVERAGE] preserve={logical} reason={error}', file=sys.stderr)
    # Print only after the entire pass is stable: callers never consume a
    # partial stdout plan from an interrupted verification.
    for source, _name, before, original, stock_before in verified:
        if (identity(source) != before or identity(original) != stock_before
                or stock_font_snapshot.resolve(str(target_root / _name), module, digests) != original):
            raise ValueError('字体扩展检查期间文件发生变化')
    for source, name, *_ in verified:
        print(f'{source}|{name}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    parser.add_argument('target_root', type=Path)
    args = parser.parse_args()
    try:
        filter_plan(args.module, args.target_root)
    except Exception as error:
        print(f'[SLOT-COVERAGE] coverage check failed: {error}', file=sys.stderr)
        raise SystemExit(1)
