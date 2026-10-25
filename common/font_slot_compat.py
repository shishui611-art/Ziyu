#!/usr/bin/env python3
"""Keep the OEM character/glyph contract in static ColorOS font replacements.

This is a compilation step in the existing isolated switch transaction, not a
mount backend or a process hook. It reuses the collection compiler's checked
outline transplant for single SFNT faces. Stock cmap/UVS, glyph IDs, names,
layout, axes and line metrics remain unchanged; uncovered characters stay OEM.
"""
from __future__ import annotations

import os
from pathlib import Path

from fontTools.ttLib import TTFont, TTLibError

from composite_collection_build import identity, protected, replace_face, verify_dependency_copies
from font_digest_cache import FontDigestCache
import font_source_profile
import stock_font_snapshot

MAX_FONT_BYTES = 128 * 1024 * 1024


class SlotCompat:
    """Finite-batch reuse by immutable donor and verified stock identities."""

    def __init__(self, module: Path, workspace: Path):
        self.module = module
        self.workspace = workspace
        self.digests = FontDigestCache()
        self.outputs = {}

    def prepare(self, donor: Path, logical: str) -> tuple[Path, dict]:
        if donor.is_symlink():
            raise ValueError('字体槽兼容来源不是受限的普通字体文件')
        before = identity(donor)
        if not 0 < before[2] <= MAX_FONT_BYTES:
            raise ValueError('字体槽兼容来源不是受限的普通字体文件')
        stock = stock_font_snapshot.resolve(logical, self.module, self.digests)
        if stock is None:
            return donor, {'slotCompatibility': 'unverified',
                           'compatibilityReason': 'verified-stock-snapshot-unavailable'}
        stock_before = identity(stock)
        if not 0 < stock_before[2] <= MAX_FONT_BYTES:
            raise ValueError('原厂字体槽快照超出大小限制')
        key = (before, stock_before, self.digests.sha256(stock))
        cached = self.outputs.get(key)
        if cached is not None:
            path, report, output_identity, output_hash = cached
            if (identity(path) != output_identity or self.digests.sha256(path) != output_hash
                    or identity(donor) != before or identity(stock) != stock_before):
                raise ValueError('字体槽兼容缓存或来源在本批次中发生变化')
            return path, dict(report, slotCompatibilityReused=True)
        with stock.open('rb') as stream:
            stock_magic = stream.read(4)
        with donor.open('rb') as stream:
            donor_magic = stream.read(4)
        if stock_magic == b'ttcf' or donor_magic == b'ttcf':
            return donor, {'slotCompatibility': 'unverified',
                           'compatibilityReason': 'collection-requires-per-face-pipeline'}
        output = self.workspace / f'stock-slot-{len(self.outputs)}.font'
        try:
            with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as base, \
                    TTFont(donor, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as source:
                # Keep variable/color sources on their established path. Freezing
                # them here would lose the user's axis/bitmap/color behaviour.
                if 'fvar' in source or any(tag in source for tag in font_source_profile.COLOR_TABLES):
                    return donor, {'slotCompatibility': 'unverified',
                                   'compatibilityReason': 'variable-or-color-source-preserved'}
                if 'glyf' not in base:
                    return donor, {'slotCompatibility': 'unverified',
                                   'compatibilityReason': 'non-truetype-stock-outline-preserved'}
                stock_order = list(base.getGlyphOrder())
                original = protected(base, stock_order)
                output_upem = int(base['head'].unitsPerEm)
                output_head = [int(base['head'].yMin), int(base['head'].yMax)]
                source_points = set((source.getBestCmap() or {}).keys())
                stock_points = set((base.getBestCmap() or {}).keys())
                details = replace_face(base, source, {}, require_cjk=False,
                                       preserve_dependencies=True, preserve_unsafe=True)
                if protected(base, stock_order) != original:
                    raise ValueError('原厂字符映射或字体槽契约在生成时发生变化')
                if details['mode'] != 'compiled':
                    if identity(donor) != before or identity(stock) != stock_before:
                        raise ValueError('字体来源或原厂快照在字体槽生成期间发生变化')
                    return stock, {'slotCompatibility': 'stock-preserved',
                                   'compatibilityReason': details['mode'],
                                   'replacedCharacters': details.get('replaced', {})}
                base.save(output, reorderTables=False)
            with TTFont(output, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
                if protected(check, stock_order) != original:
                    raise ValueError('字体槽输出未保留原厂字符映射、字形索引或布局契约')
                verify_dependency_copies(check, details.get('dependencyProof'))
            if output.stat().st_size > MAX_FONT_BYTES:
                raise ValueError('字体槽兼容输出超出大小限制')
            # The source snapshot is pinned by a verified digest and full stat
            # identity. The outer switch also rechecks all original user inputs.
            if identity(donor) != before or identity(stock) != stock_before:
                raise ValueError('字体来源或原厂快照在字体槽生成期间发生变化')
            os.chmod(output, 0o644)
            report = {'slotCompatibility': 'stock-contract-preserved',
                      'stockOnlyCharactersRetained': len(stock_points - source_points),
                      'unsafeGlyphsRetained': details['retainedUnsafeSlots'],
                      'retainedComponentCopies': len((details.get('dependencyProof') or {}).get('helpers', {})),
                      'dependencyCompatibilityReason': (details.get('dependencyProof') or {}).get('reason', 'isolated-stock-components'),
                      'replacedCharacters': details['replaced'],
                      'stockGlyphIdsPreserved': True, 'stockCmapPreserved': True,
                      'stockLayoutPreserved': True, 'stockAxesPreserved': True,
                      'outputUpem': output_upem, 'outputHead': output_head,
                      'layoutBoundsSource': 'stock', 'bitmapBaselineCorrection': 0,
                      'bitmapBaselineReason': 'stock-preserved',
                      'replacementVariation': 'selected-fixed-weight',
                      'slotCompatibilityReused': False}
            self.outputs[key] = (output, report, identity(output), self.digests.sha256(output))
            return output, report
        except (TTLibError, KeyError, IndexError, TypeError, NotImplementedError) as error:
            output.unlink(missing_ok=True)
            raise ValueError(f'字体槽兼容生成无法解析字体：{logical}：{error}') from error
