#!/usr/bin/env python3
"""Choose real weight sources and pin variable donors only for static targets.

A static face is never relabelled as nine different weights: missing weights
are reported. Full variable containers keep their axes for Android selection.
"""
from __future__ import annotations

from pathlib import Path
import re
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from font_role_policy import slot_for

WEIGHT_NAMES = ((800, ('extrabold', 'extra-bold', 'ultrabold', 'ultra-bold')),
                (600, ('semibold', 'semi-bold', 'demibold', 'demi-bold')),
                (200, ('extralight', 'extra-light', 'ultralight', 'ultra-light')),
                (900, ('black', 'heavy')), (700, ('bold',)), (500, ('medium',)),
                (300, ('light',)), (100, ('thin',)),
                (400, ('regular', 'normal', 'book')))
ROLES = {100:'thin',200:'extralight',300:'light',400:'regular',500:'medium',
         600:'semibold',700:'bold',800:'extrabold',900:'black'}


def named_weight(name: str) -> int | None:
    stem = Path(name).stem.lower()
    if stem.isdigit() and 1 <= int(stem) <= 1000:
        return int(stem)
    for weight, terms in WEIGHT_NAMES:
        if any(term in stem for term in terms):
            return weight
    return None


def requested_weight(data: dict, logical: str) -> int:
    # Explicit physical style wins over a default/first XML weight entry.
    named = named_weight(Path(logical).name)
    if named is not None:
        return named
    slot = slot_for(data, logical)
    for value in (slot.get('weight'), slot.get('metrics', {}).get('weightClass')):
        try:
            weight = int(value)
            if 1 <= weight <= 1000:
                return weight
        except (TypeError, ValueError):
            pass
    return 400


def variable_target(data: dict, logical: str) -> bool:
    slot = slot_for(data, logical)
    axes = slot.get('metrics', {}).get('variationAxes')
    # Old 1.1.1 inventories did not record fvar. Preserve known variable
    # container names until a trustworthy stock refresh supplies the metadata.
    stem = Path(logical).stem.lower()
    return bool(axes) or bool(re.search(r'vf(?:$|[-_])|variable|flex', stem))


def source_for(fonts: Path, name: str, weight: int) -> Path:
    store = fonts / '.luoshu-font-store'
    role = ROLES.get(weight)
    candidates = [fonts / f'LuoShu-{weight}.ttf', store / f'wght-{weight}.font',
                  store / f'compact-wght-{weight}.font']
    if role and role != 'regular':
        candidates.append(store / f'{role}.font')
    candidates += [fonts / f'{weight}.ttf', fonts / name,
                   store / 'mix-composite.font', store / 'regular.font',
                   store / 'compact-regular.font', fonts / '400.ttf',
                   fonts / 'MiSansVF.ttf', fonts / 'Roboto-Regular.ttf']
    for path in candidates:
        if path.is_file() and path.stat().st_size:
            return path
    raise ValueError(f'没有可用的源字体：{name}')


def prepare(source: Path, output: Path, weight: int, keep_variable: bool,
            collection_role: str = 'latin') -> tuple[Path, dict]:
    with source.open('rb') as stream:
        collection = stream.read(4) == b'ttcf'
    if collection:
        from font_instance import pick_face, InstanceError
        try:
            face = pick_face(source, collection_role, weight)
        except InstanceError:
            face = pick_face(source, 'cjk', weight)
        # Older global font collections can have no Latin; keep the existing
        # CJK face selection semantics instead of choosing an arbitrary index.
    else:
        face = -1
    kwargs = {'fontNumber': face} if face >= 0 else {}
    with source.open('rb') as stream, TTFont(
            stream, lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as font:
        original = int(font['OS/2'].usWeightClass)
        axes = {a.axisTag: a for a in font['fvar'].axes} if 'fvar' in font else {}
        report = {'requestedWeight': weight, 'sourceWeight': original,
                  'actualWeight': original, 'weightAction': 'static-preserved',
                  'weightFallback': original != weight, 'sourceFace': face,
                  'variableTarget': keep_variable}
        if axes and (keep_variable or 'SVG ' in font):
            report.update(weightAction=('variable-container-preserved' if keep_variable
                                       else 'svg-variable-preserved'),
                          weightFallback=(not keep_variable and original != weight))
        elif axes:
            limits = {tag: (max(axis.minValue, min(axis.maxValue, weight))
                            if tag == 'wght' else axis.defaultValue)
                      for tag, axis in axes.items()}
            # All axes pinned, without introducing native dependencies.
            instantiateVariableFont(font, limits, inplace=True, optimize=False)
            actual = int(round(limits.get('wght', original)))
            font['OS/2'].usWeightClass = actual
            report.update(actualWeight=actual, weightAction='variable-instanced',
                          weightFallback=actual != weight, instanceLocation=limits)
            font.save(output, reorderTables=False)
            return output, report
        if collection:
            font.save(output, reorderTables=False)
            report['weightAction'] = 'collection-face-selected'
            return output, report
        return source, report
