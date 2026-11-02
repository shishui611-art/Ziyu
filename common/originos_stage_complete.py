#!/usr/bin/env python3
"""Complete Vivo's inventoried upright UI slots in an isolated staged payload.

The compatibility mapper otherwise creates only AOSP Latin aliases. These
direct OEM targets are added only when a current, trusted stock inventory
contains their exact paths and valid metrics. Stock XML and TTCs stay intact.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
import time

from fontTools.ttLib import TTFont
from font_inventory import validate_inventory, _generic_text_slot_candidate
from font_scan_profile import detect
from font_metrics_io import FontMetricsIO
from font_role_policy import assert_isolated, is_code_monospace, is_clock_slot
import font_slot_weight as slot_weight
from hyperos_metrics_batch import contract_for_slot, link_copy, read_inventory, write_metrics

CJK_NAMES = frozenset(('DroidSansFallbackBBK.ttf', 'DroidSansFallbackMonster.ttf', 'VivoFont.ttf'))


def build(module: Path, stage: Path) -> dict:
    assert_isolated(module, stage)
    if not stage.is_dir():
        raise ValueError('字体暂存目录不存在')
    inventory = read_inventory(module)
    indexed = inventory.get('slots', {})
    if not isinstance(indexed, dict):
        indexed = {}
    expanded = inventory.get('romKind') == 'originos' or detect() == 'originos'
    if inventory:
        validate_inventory(inventory)
    jobs = []
    skipped = []
    for logical, slot in sorted(indexed.items()):
        name = Path(logical).name
        if not expanded and name not in CJK_NAMES:
            continue
        if not isinstance(slot, dict) or slot.get('path') != logical:
            continue
        if (slot.get('source') not in {'xml', 'heuristic', 'verified-scan'}
                or slot.get('style', 'normal') != 'normal'
                or slot.get('format') not in {'TTF', 'OTF'}
                or is_code_monospace(name, slot) or is_clock_slot(name, slot)):
            skipped.append(logical)
            continue
        if name not in CJK_NAMES and not _generic_text_slot_candidate(name, slot.get('metrics', {})):
            skipped.append(logical)
            continue
        contract = contract_for_slot(inventory, logical)
        if contract[-1] != 'stock':
            raise ValueError(f'原厂 UI 槽位度量无效：{logical}')
        source = slot_weight.source_for(stage / 'system/fonts', name,
                                      slot_weight.requested_weight(inventory, logical))
        destination = stage / logical.lstrip('/')
        if '..' in Path(logical).parts or not destination.resolve().is_relative_to(stage.resolve()):
            raise ValueError(f'字体目标超出暂存区：{logical}')
        jobs.append((source, destination, logical, contract))
    if not jobs:
        return {'mapped': 0, 'generated': 0, 'reason': 'no-inventoried-vivo-cjk-slot'}

    outputs = Path(tempfile.mkdtemp(prefix='.originos-metrics-', dir=stage))
    prepared = []
    reports = []
    cache = {}
    donors = {}
    started = time.monotonic()
    metrics_io = FontMetricsIO()
    try:
        # Generate every output before replacing any aliases or hard links.
        for source, destination, logical, contract in jobs:
            weight = slot_weight.requested_weight(inventory, logical)
            keep_variable = slot_weight.variable_target(inventory, logical)
            stat = source.stat()
            source_identity = (stat.st_dev, stat.st_ino, stat.st_size,
                               stat.st_mtime_ns, stat.st_ctime_ns)
            coverage = indexed[logical].get('metrics', {}).get('coverage', {})
            role = 'cjk' if int(coverage.get('hanCount', 0)) >= 512 or Path(logical).name in CJK_NAMES else 'latin'
            donor_key = (source_identity, weight, keep_variable, role)
            key = (donor_key, contract)
            if key not in cache:
                # Weight instancing depends on the donor and role, never on a
                # target's line metrics. Reuse the immutable prepared face for
                # every matching slot instead of decoding all VF glyphs again.
                if donor_key not in donors:
                    donor, weight_report = slot_weight.prepare(
                        source, outputs / f'{len(donors)}-donor.font', weight,
                        keep_variable, collection_role=role)
                    with TTFont(donor, lazy=True, recalcBBoxes=False) as font:
                        probe = '中永国' if role == 'cjk' else 'AaZz0129'
                        if not all(ord(ch) in (font.getBestCmap() or {}) for ch in probe):
                            raise ValueError(f'源字体缺少 {role} 槽位所需字形：{logical}')
                    after = source.stat()
                    if source_identity != (after.st_dev, after.st_ino, after.st_size,
                                           after.st_mtime_ns, after.st_ctime_ns):
                        raise ValueError('源字体在字重准备期间发生变化')
                    donors[donor_key] = (donor, weight_report)
                donor, weight_report = donors[donor_key]
                output = outputs / f'{len(cache)}.font'
                report = write_metrics(donor, output, contract, metrics_io=metrics_io)
                cache[key] = (output, {**weight_report, **report})
            output, report = cache[key]
            prepared.append((output, destination))
            reports.append({'slot': logical, 'metricsSource': 'stock',
                            'referenceUpem': contract[0], 'hhea': list(contract[1:4]),
                            'typo': list(contract[4:7]), 'win': list(contract[7:9]), **report})
        for output, destination in prepared:
            link_copy(output, destination)
        report_path = stage / '.ziyu-metrics-report.json'
        temporary = report_path.with_name(report_path.name + f'.tmp.{os.getpid()}')
        try:
            temporary.write_text(json.dumps({'schema': 'ziyu-slot-metrics-v1',
                                              'romKind': 'originos', 'slots': reports},
                                             ensure_ascii=False), encoding='utf-8')
            temporary.chmod(0o644)
            os.replace(temporary, report_path)
        finally:
            temporary.unlink(missing_ok=True)
    finally:
        shutil.rmtree(outputs, ignore_errors=True)
    return {'mapped': len(jobs), 'generated': len(cache), 'expandedUiSlots': expanded,
            'preparedDonors': len(donors), 'reusedDonors': len(cache) - len(donors),
            'durationSeconds': round(time.monotonic() - started, 3),
            'preservedSpecialSlots': skipped}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.module, args.stage), ensure_ascii=False))
        return 0
    except Exception as error:
        print(f'vivo UI 字体处理失败：{error}')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
