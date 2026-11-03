#!/usr/bin/env python3
"""Fit isolated non-ColorOS payloads against verified OEM slots before commit."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import tempfile

from composite_collection_build import (build as build_collection, collection_faces,
                                       identity, MAX_COLLECTION_BYTES, MAX_COLLECTION_FACE_TOTAL)
from font_slot_compat import SlotCompat
from font_role_policy import assert_isolated
from hyperos_metrics_batch import link_copy, read_inventory
import font_clipping_compat
import stock_font_snapshot


def fit_stage(module: Path, stage: Path, defer_collections: bool = False):
    assert_isolated(module, stage)
    if not stage.is_dir() or stage.is_symlink() or not stage.resolve().is_relative_to(module.resolve()):
        raise ValueError('字体暂存目录不属于当前模块')
    if not font_clipping_compat.enabled():
        return {'state': 'disabled'}
    inventory = read_inventory(module)
    if not inventory:
        raise ValueError('请先扫描原厂字体，再启用防裁切')
    indexed = inventory.get('slots', {})
    prepared, reports, pinned = [], [], []
    collection_bytes = collection_count = 0
    with tempfile.TemporaryDirectory(prefix='.ziyu-clipping-', dir=stage) as temporary:
        workspace = Path(temporary)
        with SlotCompat(module, workspace) as compat:
            for logical in sorted(indexed):
                parts = PurePosixPath(logical).parts
                if (not logical.startswith('/') or len(parts) < 4 or parts[2] != 'fonts'
                        or any(p in ('.', '..') for p in parts)
                        or PurePosixPath(logical).suffix.lower() not in ('.ttf', '.otf', '.ttc', '.otc')):
                    continue
                target = stage / logical.lstrip('/')
                if not target.is_file():
                    continue  # Role/rule protection may have intentionally removed this slot.
                donor = target.resolve(strict=True)
                if not donor.is_relative_to(stage.resolve()):
                    raise ValueError('字体来源超出暂存目录')
                stock = stock_font_snapshot.resolve(logical, module, compat.digests)
                if stock is None:
                    raise ValueError(f'原厂字体未核验，请重新扫描：{Path(logical).name}')
                before = identity(target)
                pinned.append((target, before))
                with stock.open('rb') as stream:
                    is_collection = stream.read(4) == b'ttcf'
                if is_collection and defer_collections:
                    continue  # The existing composite builder fits and proves every face below.
                if is_collection:
                    count = collection_faces(stock)
                    collection_count += count
                    if collection_count > MAX_COLLECTION_FACE_TOTAL:
                        raise ValueError('本次字体集合字面过多，请关闭防裁切后重试')
                    output = workspace / f'collection-{len(prepared)}.ttc'
                    report = build_collection(donor, stock, output, workspace,
                        f'clipping-{os.getpid()}-{len(prepared)}', logical,
                        output_budget=MAX_COLLECTION_BYTES - collection_bytes)
                    collection_bytes += output.stat().st_size
                else:
                    output, report = compat.prepare(donor, logical)
                prepared.append((output, target, identity(output), compat.digests.sha256(output)))
                reports.append({'slot': logical, 'fit': report})
            if not prepared and not defer_collections:
                raise ValueError('没有可适配的系统字体，请先重新扫描原厂字体')
            for path, before in pinned:
                if identity(path) != before:
                    raise ValueError('字体在防裁切处理期间发生变化')
            for output, _, before, digest in prepared:
                if identity(output) != before or compat.digests.sha256(output) != digest:
                    raise ValueError('防裁切输出校验失败')
            # All outputs pass before changing staged aliases. The outer switch
            # still validates, activates and rolls back the complete transaction.
            for output, target, _, _ in prepared:
                link_copy(output, target)
    path = stage / '.ziyu-clipping-report.json'
    path.write_text(json.dumps({'schema': 'ziyu-clipping-stage-v1', 'slots': reports}, ensure_ascii=False), encoding='utf-8')
    path.chmod(0o644)
    return {'state': 'prepared', 'slots': len(prepared)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    parser.add_argument('stage', type=Path)
    parser.add_argument('--defer-collections', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(fit_stage(args.module, args.stage, args.defer_collections), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
