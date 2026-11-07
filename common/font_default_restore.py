#!/usr/bin/env python3
"""Remove only staged Ziyu font overlays; reveal current ROM bytes after reboot."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import sys

FONT_SUFFIXES = {'.ttf', '.otf', '.ttc', '.otc', '.font', '.woff', '.woff2'}
SIDECARS = ('.ziyu-script-report.json', '.ziyu-stock-preserved.paths',
            '.ziyu-font-role-report.json', '.ziyu-metrics-report.json',
            '.ziyu-clipping-report.json', '.ziyu-mix-generation.conf')


def checked_stage(module, stage):
    module = module.resolve(strict=True)
    expected_parent = module / '.ziyu-state/tmp'
    if (stage.is_symlink() or not stage.is_dir() or stage.parent.is_symlink()
            or stage.resolve().parent != expected_parent.resolve()
            or not stage.name.startswith('.ziyu-payload-stage.')
            or not stage.resolve().is_relative_to(module)):
        raise ValueError('恢复默认只能清理本次字体暂存目录')
    return stage.resolve()


def font_xml(path):
    if path.suffix.lower() != '.xml':
        return False
    if 'font' in path.name.lower():
        return True
    if path.is_symlink():
        return True  # A staged XML alias must not keep an older font mapping.
    with path.open('rb') as stream:
        head = stream.read(65536)
    return (b'ziyu' in head.lower() or b'luoshu' in head.lower()
            or re.search(rb'<(?:[\w-]+:)?(?:familyset|fonts-modification|fontconfig|family)(?:\s|>)', head, re.I) is not None)


def overlay_entries(stage):
    result = []
    parts = list(stage.iterdir())
    system = stage / 'system'
    if system.is_dir() and not system.is_symlink():
        # Retire old provider layouts as well as current canonical partitions.
        parts.extend(path for path in system.iterdir() if path.is_dir() or path.is_symlink())
    for part in parts:
        if part.name.startswith('.'):
            continue
        if part.is_symlink():
            raise ValueError('字体暂存分区包含链接，拒绝恢复提交')
        if part.is_file():
            if part.suffix.lower() in FONT_SUFFIXES:
                result.append(part)
            continue
        if not part.is_dir():
            continue
        fonts = part / 'fonts'
        if fonts.exists() or fonts.is_symlink():
            result.append(fonts)
        etc = part / 'etc'
        if etc.is_symlink():
            raise ValueError('字体暂存配置目录包含链接')
        if not etc.is_dir():
            continue
        for path in etc.iterdir():
            if (path.is_file() or path.is_symlink()) and font_xml(path):
                result.append(path)
    return result


def run(action, module, stage):
    stage = checked_stage(module, stage)
    if action == 'prepare':
        # Never traverse a font-root symlink: remove that staged alias itself.
        removed = []
        for path in overlay_entries(stage):
            removed.append(path.relative_to(stage).as_posix())
            if path.is_symlink() or path.is_file():
                path.unlink()
            else:
                shutil.rmtree(path)
        for name in SIDECARS:
            (stage / name).unlink(missing_ok=True)
        proofs = stage / '.ziyu-collection-proofs'
        if proofs.is_symlink():
            proofs.unlink()
        elif proofs.exists():
            shutil.rmtree(proofs)
    else:
        removed = []
    residual = overlay_entries(stage)
    if (residual or (stage / '.ziyu-collection-proofs').exists()
            or (stage / '.ziyu-collection-proofs').is_symlink()
            or any((stage / name).exists() or (stage / name).is_symlink() for name in SIDECARS)):
        raise ValueError('字体负载尚未清理完整，本次没有提交恢复')
    return {'status': 'ok', 'state': 'prepared-default-payload',
            'removed': removed, 'fontOverlays': 0, 'fontXmlOverlays': 0,
            'message': '默认字体已准备，完整重启后使用当前系统原厂字体'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'verify'))
    parser.add_argument('module', type=Path)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.action, args.module, args.stage), ensure_ascii=False))
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
