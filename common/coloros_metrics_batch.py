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
import sys
import tempfile
import time

from font_inventory import FONT_EXTENSIONS, LOGICAL_FONT_ROOTS, _heuristic_candidate
from font_inventory_scan import _is_ui_family
from font_metrics_io import FontMetricsIO
from font_digest_cache import file_identity
from font_parallel import run_batches
from font_slot_compat import SlotCompat
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
    report = stage / '.luoshu-metrics-report.json'
    temporary = report.with_name(report.name + f'.tmp.{os.getpid()}')
    try:
        temporary.write_text(json.dumps({'schema': 'luoshu-slot-metrics-v1',
                                         'romKind': 'coloros', 'slots': slots},
                                        ensure_ascii=False), encoding='utf-8')
        temporary.chmod(0o644)
        os.replace(temporary, report)
    finally:
        temporary.unlink(missing_ok=True)


def build(module: Path, stage: Path) -> dict:
    started = time.perf_counter()
    live = (module / '.luoshu-payload').resolve()
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
                    compatibility_aliases.append((source, canonical, report, file_identity(source.stat())))
                    continue
            if not eligible_slot(slot, logical, digits_enabled):
                report['reason'] = 'missing-or-ineligible-stock-slot'
                continue
            contract = contract_for_slot(inventory, logical)
            if contract[-1] != 'stock':
                report['reason'] = 'invalid-stock-metrics'
                continue
            with source.open('rb') as stream:
                signature = stream.read(4)
            if signature == b'ttcf':
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
    try:
        # Pin every source/contract result before replacing any hard-linked alias.
        # Reading a later source after an earlier replacement can change its
        # frame or weight, especially when the same basename spans partitions.
        for source, contract, report in jobs:
            stat = source.stat()
            identities[source] = file_identity(stat)
            align_bottom = bitmap_bottom_slot(inventory, report['slot'], contract)
            key = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, contract, align_bottom)
            groups.setdefault(key, []).append((source, report))

        def prepare_batch(indexed_groups, private, _isolated):
            metrics_io = FontMetricsIO()
            results = []
            metrics_cache = {}
            with SlotCompat(module, private) as slot_compat:
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
                                    'stock-contract-preserved', 'stock-preserved'):
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
                                         prepare_batch, item_costs=costs)
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
    finally:
        shutil.rmtree(outputs, ignore_errors=True)
    return {'mapped': len(jobs), 'generated': len(groups),
            'preservedSlots': len(reports) - len(jobs) - len(compatibility_aliases),
            'compatibilityAliasSlots': len(compatibility_aliases),
            'stockContractSlots': sum(r.get('slotCompatibility') == 'stock-contract-preserved' for r in reports),
            'stockOnlyCharactersRetained': sum(r.get('stockOnlyCharactersRetained', 0) for r in reports),
            'slotCompatibilityUnverified': sum(r.get('slotCompatibility') == 'unverified' for r in reports),
            'metricsBatchSeconds': round(time.perf_counter() - started, 3),
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
