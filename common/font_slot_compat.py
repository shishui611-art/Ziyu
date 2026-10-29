#!/usr/bin/env python3
"""Keep the OEM character/glyph contract in static ColorOS font replacements.

This is a compilation step in the existing isolated switch transaction, not a
mount backend or a process hook. It reuses the collection compiler's checked
outline transplant for single SFNT faces. Stock cmap/UVS, glyph IDs, names,
layout, axes and line metrics remain unchanged; uncovered characters stay OEM.
"""
from __future__ import annotations

import gc
import os
import shutil
from pathlib import Path

from fontTools.ttLib import TTFont, TTLibError

from composite_collection_build import (identity, protected, replace_face, verify_dependency_copies,
                                       raw_table_hashes, contract_matches)
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
        self._donor = None
        self._donor_identity = None
        self._recordings = {}
        self._outline_cache = {}
        self._donor_loads = 0
        self._donor_reuses = 0
        self._template_hits = 0
        self._template_peak = 0
        self._native_batches = self._native_hits = self._native_bytes = 0
        self._native_cff_hits = self._native_cff_bytes = 0

    def __enter__(self):
        return self

    def __exit__(self, *_exception):
        self.close()

    def close(self):
        if self._donor is not None:
            self._donor.close()
            self._donor = None
            self._donor_identity = None
            self._template_hits += self._outline_cache.get('templateHits', 0)
            self._template_peak = max(self._template_peak, self._outline_cache.get('templateBytes', 0))
            self._native_batches += self._outline_cache.get('nativeGlyphBatches', 0)
            self._native_hits += self._outline_cache.get('nativeGlyphHits', 0)
            self._native_bytes = max(self._native_bytes, self._outline_cache.get('nativeGlyphBytes', 0))
            self._native_cff_hits += self._outline_cache.get('nativeCffHits', 0)
            self._native_cff_bytes = max(self._native_cff_bytes, self._outline_cache.get('nativeCffBytes', 0))
            self._recordings.clear()
            self._outline_cache.clear()
            # Release FontTools table/glyph cycles before loading another
            # weight source, rather than accumulating graphs inside a worker.
            gc.collect()

    def _source(self, path, expected):
        if self._donor_identity != expected:
            self.close()
            self._donor = TTFont(path, lazy=True, recalcBBoxes=False, recalcTimestamp=False)
            self._donor_identity = expected
            self._donor_loads += 1
        else:
            self._donor_reuses += 1
        return self._donor

    def stats(self):
        return {'compatDonorLoads': self._donor_loads,
                'compatDonorReuses': self._donor_reuses,
                'glyphTemplateHits': self._template_hits + self._outline_cache.get('templateHits', 0),
                'glyphTemplatePeakBytes': max(self._template_peak, self._outline_cache.get('templateBytes', 0)),
                'nativeGlyphBatches': self._native_batches + self._outline_cache.get('nativeGlyphBatches', 0),
                'nativeGlyphHits': self._native_hits + self._outline_cache.get('nativeGlyphHits', 0),
                'nativeCffHits': self._native_cff_hits + self._outline_cache.get('nativeCffHits', 0),
                'nativeCffBytes': max(self._native_cff_bytes, self._outline_cache.get('nativeCffBytes', 0)),
                'nativeGlyphBytes': max(self._native_bytes, self._outline_cache.get('nativeGlyphBytes', 0))}

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
        stock_hash = self.digests.sha256(stock)
        key = (before, stock_before, stock_hash)
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
            source = self._source(donor, before)
            with TTFont(stock, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as base:
                # Keep variable/color sources on their established path. Freezing
                # them here would lose the user's axis/bitmap/color behaviour.
                if 'fvar' in source or any(tag in source for tag in font_source_profile.COLOR_TABLES):
                    return donor, {'slotCompatibility': 'unverified',
                                   'compatibilityReason': 'variable-or-color-source-preserved'}
                if 'glyf' not in base:
                    return donor, {'slotCompatibility': 'unverified',
                                   'compatibilityReason': 'non-truetype-stock-outline-preserved'}
                if any(tag not in source for tag in ('head', 'hhea', 'OS/2')):
                    raise ValueError('字体来源缺少必要的布局度量表')
                source['hhea']
                source['OS/2']
                stock_order = list(base.getGlyphOrder())
                raw_tables = raw_table_hashes(base)
                original = protected(base, stock_order, capture_bounds=True)
                original_frame = list(original['frame'])
                source_upem = int(source['head'].unitsPerEm)
                if not 16 <= source_upem <= 16384:
                    raise ValueError('源字体 unitsPerEm 无效')
                source_head = [int(source['head'].yMin), int(source['head'].yMax)]
                output_upem = int(base['head'].unitsPerEm)
                output_head = [int(base['head'].yMin), int(base['head'].yMax)]
                source_points = set((source.getBestCmap() or {}).keys())
                stock_points = set((base.getBestCmap() or {}).keys())
                if before[2] == stock_before[2] and self.digests.sha256(donor) == stock_hash:
                    # The complete requested font is already byte-identical to
                    # this verified snapshot. Return those exact bytes, after
                    # the normal parse/contract validation; rebuilding all its
                    # outlines would add work and can only introduce changes.
                    # Publication chmods staged aliases. Do not hard-link the
                    # read-only snapshot itself into that writable staging tree.
                    shutil.copyfile(stock, output)
                    if (self.digests.sha256(output) != stock_hash
                            or identity(donor) != before or identity(stock) != stock_before):
                        raise ValueError('相同字体复用期间来源或快照发生变化')
                    report = {'slotCompatibility': 'stock-contract-preserved',
                              'outlineEngine': {'engine': 'identical-source-stock'},
                              'stockOnlyCharactersRetained': 0,
                              'unsafeGlyphsRetained': 0, 'unsafeGlyphs': [],
                              'retainedComponentCopies': 0,
                              'replacedCharacters': {'cjk': 0, 'latin': 0, 'digit': 0},
                              'stockGlyphIdsPreserved': True, 'stockCmapPreserved': True,
                              'stockLayoutPreserved': True, 'stockAxesPreserved': True,
                              'sourceUpem': source_upem, 'sourceHead': source_head,
                              'outputUpem': output_upem, 'outputHead': output_head,
                              'layoutBoundsSource': 'stock', 'bitmapBaselineCorrection': 0,
                              'bitmapBaselineReason': 'stock-preserved',
                              'replacementVariation': 'selected-fixed-weight',
                              'slotCompatibilityReused': True,
                              'slotCompatibilityReuseReason': 'identical-source-stock-bytes'}
                    self.outputs[key] = (output, report, identity(output), stock_hash)
                    return output, report
                details = replace_face(base, source, self._recordings, require_cjk=False,
                                       preserve_dependencies=True, preserve_unsafe=True,
                                       outline_cache=self._outline_cache, native_source=donor,
                                       expand_bounds=True)
                if protected(base, stock_order, bounds_reference=original) != original:
                    raise ValueError('原厂字符映射或字体槽契约在生成时发生变化')
                updated_frame = [int(base['head'].unitsPerEm), int(base['head'].xMin),
                                 int(base['head'].yMin), int(base['head'].xMax), int(base['head'].yMax)]
                output_head = [int(base['head'].yMin), int(base['head'].yMax)]
                frame_expansion = ({'before': original_frame, 'after': updated_frame}
                                   if original_frame != updated_frame else None)
                if details['mode'] != 'compiled':
                    if identity(donor) != before or identity(stock) != stock_before:
                        raise ValueError('字体来源或原厂快照在字体槽生成期间发生变化')
                    return stock, {'slotCompatibility': 'stock-preserved',
                                   'compatibilityReason': details['mode'],
                                   'sourceUpem': source_upem, 'sourceHead': source_head,
                                   'outputUpem': output_upem, 'outputHead': output_head,
                                   'layoutBoundsSource': 'stock',
                                   'bitmapBaselineCorrection': 0,
                                   'bitmapBaselineReason': 'stock-preserved',
                                   'replacedCharacters': details.get('replaced', {})}
                for tag in raw_tables:
                    base.tables.pop(tag, None)
                base.save(output, reorderTables=False)
            with TTFont(output, lazy=True, recalcBBoxes=False, recalcTimestamp=False) as check:
                matched = contract_matches(check, original, raw_tables, stock_order,
                                           allow_bounds_expansion=True)
                if raw_tables and not matched:
                    matched = contract_matches(check, original, stock_order=stock_order,
                                               allow_bounds_expansion=True)
                if not matched:
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
                      'outlineEngine': details.get('outlineEngine', {'engine': 'fonttools'}),
                      'stockOnlyCharactersRetained': len(stock_points - source_points),
                      'unsafeGlyphsRetained': details['retainedUnsafeSlots'],
                      'unsafeGlyphs': details.get('retainedUnsafeGlyphs', []),
                      'retainedComponentCopies': len((details.get('dependencyProof') or {}).get('helpers', {})),
                      'dependencyCompatibilityReason': (details.get('dependencyProof') or {}).get('reason', 'isolated-stock-components'),
                      'replacedCharacters': details['replaced'],
                      'stockGlyphIdsPreserved': True, 'stockCmapPreserved': True,
                      'stockLayoutPreserved': True, 'stockAxesPreserved': True,
                      'sourceUpem': source_upem, 'sourceHead': source_head,
                      'outputUpem': output_upem, 'outputHead': output_head,
                      'fontFrameExpansion': frame_expansion,
                      'metricExtremaExpansion': details.get('metricExtremaExpansion'),
                      'layoutBoundsSource': 'stock', 'bitmapBaselineCorrection': 0,
                      'bitmapBaselineReason': 'stock-preserved',
                      'replacementVariation': 'selected-fixed-weight',
                      'slotCompatibilityReused': False}
            self.outputs[key] = (output, report, identity(output), self.digests.sha256(output))
            return output, report
        except (TTLibError, KeyError, IndexError, TypeError, NotImplementedError) as error:
            output.unlink(missing_ok=True)
            raise ValueError(f'字体槽兼容生成无法解析字体：{logical}：{error}') from error
