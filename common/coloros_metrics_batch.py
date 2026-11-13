#!/usr/bin/env python3
"""Prepare existing isolated ColorOS aliases with their OEM layout contract.

The mapper's font/weight choices stay intact. Verified stock slots use checked
outline transplantation; other slots keep the established metrics-only writer.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import time

from font_inventory import FONT_EXTENSIONS, LOGICAL_FONT_ROOTS, _heuristic_candidate
from font_inventory_scan import _is_ui_family
from fontTools.ttLib import TTCollection
from font_metrics_io import FontMetricsIO
from font_digest_cache import file_identity, FontDigestCache
from font_coverage import CJK_COMMON, count_core_han
from font_face_weight import weight_distance
from font_source_profile import _italic, _weight
from font_parallel import run_batches
from font_slot_compat import SlotCompat
from composite_collection_build import identity as donor_identity
import font_clipping_compat
import stock_font_snapshot
from hyperos_metrics_batch import (bitmap_bottom_slot, contract_for_slot, link_copy,
                                  read_inventory, write_metrics)


def osans_digits_enabled(module: Path) -> bool:
    enabled = True
    try:
        lines = (module / 'config' / 'coloros_slot_groups.conf').read_text(encoding='utf-8', errors='replace').splitlines()
    except OSError:
        lines = []
    for line in lines:
        line = line.split('#', 1)[0].strip()
        key, separator, value = line.partition('=')
        if separator and key.strip() == 'osans_digits' and value.strip() in {'0', '1'}:
            enabled = value.strip() == '1'
    return enabled


def eligible_slot(slot: object, logical: str, digits_enabled: bool = True) -> bool:
    if not isinstance(slot, dict) or slot.get('path', logical) != logical:
        return False
    name = Path(logical).name
    lowered = name.lower()
    if lowered.startswith(('osans-rc', 'osans-ext')):
        return False
    if (lowered.startswith('osans-') and 'digits' in lowered
            and lowered.endswith(('.ttf', '.otf')) and not digits_enabled):
        return False
    if any(token in lowered for token in ('emoji', 'symbol', 'icon', 'math', 'music', 'serif', 'mono')):
        return False
    families = slot.get('families', [])
    return _heuristic_candidate(name) or (
        isinstance(families, list) and any(isinstance(family, str) and _is_ui_family(family) for family in families)
    )


def write_report(stage: Path, slots: list[dict]) -> None:
    report = stage / '.ziyu-metrics-report.json'
    temporary = report.with_name(report.name + f'.tmp.{os.getpid()}')
    try:
        temporary.write_text(json.dumps({'schema': 'ziyu-slot-metrics-v1',
                                         'romKind': 'coloros', 'slots': slots},
                                        ensure_ascii=False), encoding='utf-8')
        temporary.chmod(0o644)
        os.replace(temporary, report)
    finally:
        temporary.unlink(missing_ok=True)


def collection_profile(path: Path) -> dict:
    """Choose the best Chinese face while preserving the source style and weight."""
    collection = TTCollection(path, lazy=True)
    try:
        fonts = collection.fonts
        if any('fvar' in font for font in fonts):
            return {'variable': True}
        weight, italic = _weight(fonts[0]), _italic(fonts[0], '')
        scores = []
        for index, font in enumerate(fonts):
            cmap = font.getBestCmap() or {}
            scores.append((_italic(font, '') == italic, count_core_han(cmap), -weight_distance(font, weight),
                           sum(cp in cmap for cp in CJK_COMMON), -index))
        selected = -max(scores)[-1]
        return {'variable': False, 'face': selected, 'faces': len(fonts)}
    finally:
        collection.close()


def write_collection_default(source: Path, output: Path, profile: dict) -> None:
    """Swap TTC directory offsets, preserving every SFNT table byte and face."""
    count, selected = profile['faces'], profile['face']
    header_size = 12 + 4 * count
    with source.open('rb') as stream:
        header = bytearray(stream.read(header_size))
    if len(header) != header_size or struct.unpack_from('>I', header, 8)[0] != count:
        raise ValueError('字体集合目录在生成期间发生变化')
    offsets = list(struct.unpack_from(f'>{count}I', header, 12))
    offsets[0], offsets[selected] = offsets[selected], offsets[0]
    struct.pack_into(f'>{count}I', header, 12, *offsets)
    with source.open('rb') as original, output.open('wb') as target:
        original.seek(header_size)
        target.write(header)
        shutil.copyfileobj(original, target, length=1024 * 1024)
    # Check the rewritten directory and all untouched bytes without decoding or
    # recompiling outlines, layout, variation selectors or the other faces.
    with source.open('rb') as original, output.open('rb') as target:
        original.seek(header_size)
        if target.read(header_size) != header:
            raise ValueError('字体集合默认面目录校验失败')
        while True:
            expected = original.read(1024 * 1024)
            if target.read(1024 * 1024) != expected:
                raise ValueError('字体集合原始数据校验失败')
            if not expected:
                break


class _BatchProgress:
    """Publish real phase/counter updates to the existing switch task guard."""
    def __init__(self):
        value = os.environ.get('ZIYU_SWITCH_PROGRESS_FILE')
        self.path = Path(value) if value else None
        self.percent, self.last_write = 76, 0.0

    def update(self, percent, message, force=False):
        self.percent = max(self.percent, min(85, int(percent)))
        if self.path is None or (not force and time.monotonic() - self.last_write < .5):
            return
        temporary = self.path.with_name(self.path.name + f'.metrics-{os.getpid()}.tmp')
        try:
            temporary.write_text(f'percent={self.percent}\nmessage={message}\n', encoding='utf-8')
            temporary.chmod(0o644)
            temporary.replace(self.path)
            self.last_write = time.monotonic()
        except OSError:
            pass  # Reporting never changes a font result or commit decision.
        finally:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass


def build(module: Path, stage: Path) -> dict:
    started = time.perf_counter()
    live = (module / '.ziyu-payload').resolve()
    resolved = stage.resolve()
    if resolved == module.resolve() or resolved == live or live in resolved.parents:
        raise ValueError('拒绝修改本次启动正在使用的字体负载')
    if not stage.is_dir():
        raise ValueError('ColorOS 字体暂存目录不存在')
    inventory = read_inventory(module)
    digits_enabled = osans_digits_enabled(module)
    indexed = inventory.get('slots', {})
    if not isinstance(indexed, dict):
        indexed = {}
    jobs = []
    reports = []
    compatibility_aliases = []
    collection_kinds = {}
    collection_jobs = {}
    # Explicit nonzero XML indices retain their original meaning. This default
    # selection applies to aliases which Android loads at face zero.
    indexed_collections = {ref.get('resolvedPath')
                           for ref in inventory.get('xmlGraph', {}).get('refs', [])
                           if ref.get('index', 0) != 0}

    def profile_collection(path):
        key = file_identity(path.stat())
        if key not in collection_kinds:
            collection_kinds[key] = collection_profile(path)
        return collection_kinds[key]

    for partition, logical_root in LOGICAL_FONT_ROOTS:
        root = stage / partition / 'fonts'
        if not root.is_dir():
            continue
        for source in sorted(root.iterdir()):
            if not source.is_file() or source.suffix.lower() not in FONT_EXTENSIONS:
                continue
            # Inventory keys describe Android paths, even in a Windows fixture.
            logical = (logical_root / source.name).as_posix()
            report = {'slot': logical, 'metricsSource': 'preserved'}
            reports.append(report)
            slot = indexed.get(logical)
            with source.open('rb') as stream:
                signature = stream.read(4)
            if signature == b'ttcf' and eligible_slot(slot or {'path': logical}, logical, digits_enabled):
                profile = profile_collection(source)
                if not profile['variable']:
                    report.update(reason='collection-metrics-preserved',
                                  collectionFaceCount=profile['faces'])
                    if logical in indexed_collections or (slot or {}).get('faceIndex', 0) != 0:
                        report['collectionDefaultReason'] = 'explicit-face-indices-preserved'
                    else:
                        report.update(collectionSourceFace=profile['face'], collectionDefaultFace=0)
                        if profile['face'] != 0:
                            key = file_identity(source.stat())
                            collection_jobs.setdefault(key, []).append((source, report))
                    continue
            # These legacy names may be synthesized on newer ColorOS ROMs.
            # Never give a synthetic alias a different frame from its real OEM
            # slot when both were generated from the exact same donor inode.
            canonical_name = {
                'SysFont-Hans-Regular.ttf': 'SysSans-Hans-Regular.ttf',
                'SysFont-Hant-Regular.ttf': 'SysSans-Hant-Regular.ttf',
            }.get(source.name)
            if slot is None and canonical_name:
                canonical = root / canonical_name
                canonical_logical = (logical_root / canonical_name).as_posix()
                if (eligible_slot(indexed.get(canonical_logical), canonical_logical, digits_enabled)
                        and canonical.is_file() and source.samefile(canonical)):
                    # A TTC is intentionally preserved byte-for-byte because
                    # rewriting one face would discard its other faces. When
                    # this synthetic alias is already the same file, keep that
                    # verified relationship instead of requiring a rewritten
                    # canonical output that must not exist for collections.
                    with canonical.open('rb') as stream:
                        is_collection = stream.read(4) == b'ttcf'
                    if is_collection and (not font_clipping_compat.enabled() or not profile_collection(canonical)['variable']):
                        report['reason'] = 'collection-alias-preserved-with-canonical'
                        continue
                    compatibility_aliases.append((source, canonical, report, file_identity(source.stat())))
                    continue
            if not eligible_slot(slot, logical, digits_enabled):
                report['reason'] = 'missing-or-ineligible-stock-slot'
                continue
            contract = contract_for_slot(inventory, logical)
            if contract[-1] != 'stock':
                report['reason'] = 'invalid-stock-metrics'
                continue
            if signature == b'ttcf' and (not font_clipping_compat.enabled() or not profile_collection(source)['variable']):
                # A collection may be addressed at a nonzero face index by ROM
                # XML. The single-face writer must not silently discard faces.
                report['reason'] = 'collection-metrics-preserved'
                continue
            jobs.append((source, contract, report))

    outputs = Path(tempfile.mkdtemp(prefix='.coloros-metrics-', dir=stage))
    prepared = []
    metric_stats = {}
    execution = {}
    groups = {}
    identities = {}
    clipping_donors = {}
    clipping_prepare_seconds = 0.0
    clipping_template_seconds = 0.0
    clipping_template_bytes = clipping_template_faces = 0
    header_only = False
    progress = _BatchProgress()
    try:
        for index, (expected, aliases) in enumerate(collection_jobs.items()):
            source = aliases[0][0]
            output = outputs / f'collection-default-{index}.font'
            write_collection_default(source, output, collection_kinds[expected])
            for alias, report in aliases:
                identities[alias] = expected
                prepared.append((output, alias))
                report['collectionDefaultChanged'] = True
        # Pin every source/contract result before replacing any hard-linked alias.
        # Reading a later source after an earlier replacement can change its
        # frame or weight, especially when the same basename spans partitions.
        for source, contract, report in jobs:
            stat = source.stat()
            identities[source] = file_identity(stat)
            align_bottom = bitmap_bottom_slot(inventory, report['slot'], contract)
            key = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, contract, align_bottom)
            groups.setdefault(key, []).append((source, report))

        if font_clipping_compat.enabled():
            # Measure all variation deltas once before fork. Workers retain the
            # original variable source, sharing only immutable geometry facts.
            prepare_started = time.perf_counter()
            digests = FontDigestCache()
            source_facts = {}
            try:
                os.nice(5)
            except (AttributeError, OSError):
                pass
            for source, _contract, _report in jobs:
                if source.is_symlink():
                    continue  # The existing checked metrics fallback owns it.
                source_key = donor_identity(source)
                if source_key in clipping_donors:
                    continue
                source_hash = digests.sha256(source)
                if source_hash in source_facts:
                    actual, detail = source, source_facts[source_hash]
                else:
                    actual, detail = font_clipping_compat.prepare_donor(source, digests,
                        progress=lambda message, fraction: progress.update(76 + 2 * fraction, message))
                    source_facts[source_hash] = detail
                if donor_identity(source) != source_key:
                    raise ValueError('防裁切来源在批次准备期间发生变化')
                clipping_donors[source_key] = (actual, detail, donor_identity(actual), digests.sha256(actual))
            clipping_prepare_seconds = round(time.perf_counter() - prepare_started, 3)

            # Collect verified OEM ink before header fitting. Identical source
            # bytes share continuous geometry; target em changes never scale it.
            template_sources = {}
            for source, _contract, report in jobs:
                prepared_donor = clipping_donors.get(donor_identity(source))
                if prepared_donor is None or prepared_donor[1] is None:
                    continue
                actual, detail, _pin, source_hash = prepared_donor
                stock = stock_font_snapshot.resolve(report['slot'], module, digests)
                if stock is None:
                    raise ValueError('缺少可信原厂行框，当前字体保持不变')
                item = template_sources.setdefault(source_hash, {'source': actual, 'detail': detail, 'stocks': set()})
                item['stocks'].add(stock)
            template_started = time.perf_counter()
            if template_sources:
                from font_clipping_variable import prepare_templates, MAX_TEMPLATE_BYTES
                for index, (source_hash, item) in enumerate(template_sources.items()):
                    progress.update(78 + 4 * index / len(template_sources),
                                    f'正在适配字体 {index + 1}/{len(template_sources)}', force=True)
                    templates = prepare_templates(item['source'], item['stocks'], outputs / f'shared-{index}',
                        item['detail']['facts'], digests,
                        progress=lambda message, fraction: progress.update(78 + 4 * (index + fraction) / len(template_sources), message))
                    clipping_template_bytes += templates['templateBytes']
                    clipping_template_faces += len(templates['faces'])
                    if clipping_template_bytes > MAX_TEMPLATE_BYTES:
                        raise ValueError('本次共享可变字体超出暂存容量限制，当前字体未改动')
                    for key, (actual, detail, pin, digest) in list(clipping_donors.items()):
                        if digest != source_hash or detail is None:
                            continue
                        if donor_identity(actual) != pin or digests.sha256(actual) != digest:
                            raise ValueError('共享可变字体来源在准备期间发生变化')
                        bound = dict(templates, sourceIdentity=file_identity(actual.stat()))
                        clipping_donors[key] = (actual, dict(detail, templates=bound), pin, digest)
            clipping_template_seconds = round(time.perf_counter() - template_started, 3)
            # Fully variable batches share one source scale for normal text,
            # writing only headers and retaining immutable variation bytes.
            header_only = bool(template_sources) and all(
                clipping_donors.get(donor_identity(source), (None, None))[1] is not None for source, _, _ in jobs)

        def poll_progress():
            completed = 0
            for path in outputs.glob('worker-*/progress.json'):
                try:
                    completed += max(0, int(json.loads(path.read_text(encoding='utf-8'))['completed']))
                except (OSError, ValueError, KeyError, TypeError):
                    continue
            start = 82 if clipping_template_faces else 76
            progress.update(start + (85 - start) * min(completed, len(jobs)) / max(len(jobs), 1),
                            f'正在生成系统字体 {min(completed, len(jobs))}/{len(jobs)}')

        def prepare_batch(indexed_groups, private, _isolated):
            metrics_io = FontMetricsIO()
            results = []
            metrics_cache = {}
            completed = 0
            with SlotCompat(module, private, prepared_donors=clipping_donors) as slot_compat:
                for index, (key, aliases) in indexed_groups:
                    contract, align_bottom = key[-2:]
                    output = private / f'metrics-{index}.font'
                    metrics_details = None
                    for source, original_report in aliases:
                        report = dict(original_report)
                        report['slotWorker'] = private.name
                        compatible = None
                        compatibility_report = {}
                        compat_started = time.perf_counter()
                        # A verified stock-contract transplant already supplies
                        # the final OEM metrics. Patching the donor's headers
                        # first created disposable files and defeated reuse
                        # between aliases of the same immutable weight source.
                        if not source.is_symlink():
                            candidate, candidate_report = slot_compat.prepare(source, report['slot'])
                            if candidate_report.get('slotCompatibility') in (
                                    'stock-contract-preserved', 'stock-preserved', 'variable-source-preserved'):
                                compatible, compatibility_report = candidate, candidate_report
                        if compatible is None:
                            # Keep the established metrics-only path for missing
                            # snapshots, variable/color donors, unsupported stock
                            # outlines, and legacy symlink aliases.
                            if metrics_details is None:
                                cached = metrics_cache.get(key)
                                if cached is not None:
                                    output, metrics_details = cached
                                else:
                                    write_started = time.perf_counter()
                                    metrics_details = write_metrics(
                                        aliases[0][0], output, contract,
                                        align_bitmap_bottom=align_bottom, metrics_io=metrics_io)
                                    metrics_details['metricsWriteSeconds'] = round(
                                        time.perf_counter() - write_started, 3)
                                    metrics_cache[key] = (output, metrics_details)
                            details = metrics_details
                            compatible, compatibility_report = slot_compat.prepare(output, report['slot'])
                        else:
                            details = {'metricsWriteSeconds': 0, 'metricsWriteSkipped': True,
                                       'removedCjkMappings': 0}
                        report.update({'metricsSource': 'stock', 'referenceUpem': contract[0],
                                       'hhea': list(contract[1:4]), 'typo': list(contract[4:7]),
                                       'win': list(contract[7:9]), 'useTypoMetrics': contract[9], **details})
                        report.update(compatibility_report)
                        report['slotCompatibilitySeconds'] = round(time.perf_counter() - compat_started, 3)
                        if 'outputUpem' in compatibility_report:
                            report['layoutBoundsDifferFromSource'] = any(
                                abs(old / report['sourceUpem'] - new / report['outputUpem']) > 1e-6
                                for old, new in zip(report['sourceHead'], report['outputHead']))
                        results.append({'source': str(source), 'output': str(compatible), 'report': report})
                        completed += 1
                        temporary = private / 'progress.tmp'
                        try:
                            temporary.write_text(json.dumps({'completed': completed}), encoding='utf-8')
                            temporary.replace(private / 'progress.json')
                        except OSError:
                            pass
                        if not _isolated:
                            poll_progress()
                stats = {**metrics_io.stats(), **slot_compat.stats()}
            return {'results': results, 'metrics': stats}

        # Equal donor/line metrics do not mean equal OEM glyph/layout contracts.
        # In particular Hans and Hant can share a metrics group while requiring
        # two large, independent transplants. Do not serialize both in one worker.
        # Snapshot sizes are scheduling hints only; SlotCompat still resolves and
        # byte-verifies every actual stock input in the selected worker.
        snapshot_files = stock_font_snapshot.load(module).get('files', {})
        slot_jobs, costs, shared_slots = [], [], {}
        for key, aliases in groups.items():
            for source, report in aliases:
                entry = snapshot_files.get(report['slot'], {})
                size = entry.get('size', 1) if isinstance(entry, dict) else 1
                digest = entry.get('sha256') if isinstance(entry, dict) else None
                # Group only identical donors/metrics AND the same OEM bytes.
                # Hans/Hant with different snapshots must remain independent.
                # Every alias still resolves/verifies its own snapshot inside
                # SlotCompat; metadata here only affects worker assignment.
                shared_key = (key, digest) if isinstance(digest, str) and len(digest) == 64 else None
                if shared_key is not None and shared_key in shared_slots:
                    slot_jobs[shared_slots[shared_key]][1].append((source, report))
                    continue
                if shared_key is not None:
                    shared_slots[shared_key] = len(slot_jobs)
                slot_jobs.append((key, [(source, report)]))
                costs.append(max(1, min(size, 128 * 1024 * 1024)) if type(size) is int else 1)
        batches, execution = run_batches(slot_jobs, outputs,
                                         max((s.stat().st_size for s, _, _ in jobs), default=0),
                                         prepare_batch, item_costs=costs, progress_poll=poll_progress,
                                         max_workers=1 if header_only else None)
        by_slot = {report['slot']: report for report in reports}
        for batch in batches:
            for key, value in batch['metrics'].items():
                metric_stats[key] = metric_stats.get(key, 0) + value
            for item in batch['results']:
                by_slot[item['report']['slot']].update(item['report'])
                prepared.append((Path(item['output']), Path(item['source'])))
        # No alias is replaced until all workers have succeeded and all pinned inputs are unchanged.
        for source, expected in identities.items():
            if file_identity(source.stat()) != expected:
                raise ValueError(f'字体来源在并行处理期间发生变化：{source.name}')
        for alias, _canonical, _report, before in compatibility_aliases:
            if file_identity(alias.stat()) != before:
                raise ValueError(f'字体兼容别名来源发生变化：{alias.name}')
        for output, destination in prepared:
            link_copy(output, destination)
        prepared_paths = {destination for _output, destination in prepared}
        for alias, canonical, report, _before in compatibility_aliases:
            if canonical not in prepared_paths:
                raise ValueError(f'字体兼容别名缺少已校验的原厂槽输出：{alias.name}')
            link_copy(canonical, alias)
            report.update(metricsSource='stock-alias', aliasOf=str(canonical.relative_to(stage)))
        write_report(stage, reports)
        progress.update(85, f'系统字体已生成 {len(jobs)}/{len(jobs)}', force=True)
    finally:
        shutil.rmtree(outputs, ignore_errors=True)
    return {'mapped': len(jobs), 'generated': len(groups),
            'collectionDefaultSlots': sum(len(aliases) for aliases in collection_jobs.values()),
            'preservedSlots': len(reports) - len(jobs) - len(compatibility_aliases),
            'compatibilityAliasSlots': len(compatibility_aliases),
            'stockContractSlots': sum(r.get('slotCompatibility') == 'stock-contract-preserved' for r in reports),
            'continuousVariableSlots': sum(r.get('slotCompatibility') == 'variable-source-preserved' for r in reports),
            'stockOnlyCharactersRetained': sum(r.get('stockOnlyCharactersRetained', 0) for r in reports),
            'slotCompatibilityUnverified': sum(r.get('slotCompatibility') == 'unverified' for r in reports),
            'metricsBatchSeconds': round(time.perf_counter() - started, 3),
            'clippingPreparedSources': sum(detail is not None for _path, detail, _identity, _digest in clipping_donors.values()),
            'clippingSourcePreparationSeconds': clipping_prepare_seconds,
            'clippingTemplateSeconds': clipping_template_seconds,
            'clippingTemplateFaces': clipping_template_faces,
            'clippingTemplateBytes': clipping_template_bytes,
            'clippingHeaderSerial': header_only,
            'clippingFitMethod': 'shared-reference-em-fit' if clipping_template_faces else 'existing-static-path',
            'execution': execution, **metric_stats}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('module', type=Path)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    try:
        result = build(args.module, args.stage)
        print(json.dumps(result, ensure_ascii=False))
        if result['preservedSlots']:
            print(f"ColorOS：{result['preservedSlots']} 个槽位保留来源度量，详见字体度量报告", file=sys.stderr)
        return 0
    except Exception as error:
        print(f'ColorOS 字体处理失败：{error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
