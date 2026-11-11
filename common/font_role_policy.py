#!/usr/bin/env python3
"""Role protection for isolated staged fonts; never modifies a live ROM file.

Code monospace families retain their original faces. OEM clock/numeral fonts
such as MitypeMono are a distinct role, unless XML explicitly assigns them to
monospace. This module uses no subprocesses, watchers or background services.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
from font_slot_overrides import VENDOR_CLOCK, load as load_overrides, restricted, exclusion


def families(slot: dict | None) -> list[str]:
    values = (slot or {}).get('families', [])
    return [re.sub(r'[ _]+', '-', v.strip().lower()) for v in values
            if isinstance(v, str)] if isinstance(values, list) else []


def is_clock_slot(name: str, slot: dict | None = None) -> bool:
    label = ' '.join([name.lower(), *families(slot)])
    return bool(VENDOR_CLOCK.fullmatch(name)) or any(word in label for word in ('clock', 'mitype', 'lockscreen', 'lock-screen', 'numeral'))


def keep_lockscreen(module: Path) -> bool:
    """是否保留厂商锁屏时钟/数字字体（默认保留，不替换）。

    默认 True：ColorOS 等厂商的时钟数字字体宽度是专门调的，换掉会让锁屏/桌面
    时钟排版错位。用户在「字体工具 → 保留锁屏时钟字体」关掉时不保留。
    """
    conf = module / 'config/tools/keep-lockscreen.conf'
    try:
        for line in conf.read_text(encoding='utf-8', errors='replace').splitlines():
            if line.startswith('enabled='):
                value = line.split('=', 1)[1].strip().lower()
                return value not in ('0', 'off', 'false', 'no')
    except OSError:
        pass
    return True


def is_code_monospace(name: str, slot: dict | None = None) -> bool:
    names = families(slot)
    # An explicit code-family assignment wins even for an OEM clock filename.
    explicit = ('monospace', 'sans-serif-monospace', 'ui-monospace', 'serif-monospace',
                'roboto-mono', 'noto-mono', 'noto-sans-mono', 'noto-serif-mono',
                'droid-sans-mono', 'courier', 'monaco')
    if any(f == m or f.startswith(m + '-') for f in names for m in explicit):
        return True
    if is_clock_slot(name, slot):
        return False
    if any('mono' in f for f in names):
        return True
    lowered = name.lower()
    if any(word in lowered for word in ('mono', 'courier', 'consolas', 'sourcecode')):
        return True
    # Optional evidence captured from trusted stock; do not inspect the active
    # replacement under /system and mistake its metrics for original metrics.
    metrics = (slot or {}).get('metrics', {})
    return isinstance(metrics, dict) and metrics.get('isFixedPitch') is True


def slot_for(data: dict, logical: str) -> dict:
    indexed = data.get('slots', {})
    slot = indexed.get(logical, {}) if isinstance(indexed, dict) else {}
    return slot if isinstance(slot, dict) else {}


def assert_isolated(module: Path, stage: Path) -> None:
    live = (module / '.ziyu-payload').resolve()
    resolved = stage.resolve()
    if resolved == module.resolve() or resolved == live or live in resolved.parents:
        raise ValueError('拒绝修改本次启动正在使用的字体负载')
    for root in stage.glob('*/fonts'):
        if root.is_symlink() or not root.resolve().is_relative_to(resolved):
            raise ValueError('字体暂存目录包含指向外部的链接')


def protected_aliases(stage: Path, data: dict, keep_clock: bool = True) -> list[Path]:
    result = []
    for root in sorted(stage.glob('*/fonts')):
        if not root.is_dir() or root.is_symlink():
            continue
        for path in sorted(root.iterdir()):
            if path.suffix.lower() not in ('.ttf', '.otf', '.ttc', '.otc'):
                continue
            logical = '/' + path.relative_to(stage).as_posix()
            if is_code_monospace(path.name, slot_for(data, logical)):
                result.append(path)
            elif keep_clock and VENDOR_CLOCK.fullmatch(path.name):
                result.append(path)
    return result


def record_preserved(stage: Path, logicals: list[str]) -> list[str]:
    sidecar = stage / '.ziyu-stock-preserved.paths'
    existing = sidecar.read_text().splitlines() if sidecar.is_file() else []
    # This file is consumed by boot repair with grep -Fx, not evaluated as shell.
    paths = sorted({p for p in [*existing, *logicals]
                    if p.startswith('/') and '\n' not in p and '\r' not in p
                    and len(Path(p).parts) >= 4 and Path(p).parts[2] == 'fonts'
                    and '..' not in Path(p).parts})
    temporary = sidecar.with_name(sidecar.name + f'.tmp.{os.getpid()}')
    try:
        temporary.write_text(''.join(p + '\n' for p in paths), encoding='utf-8')
        temporary.chmod(0o644)
        os.replace(temporary, sidecar)
    finally:
        temporary.unlink(missing_ok=True)
    return paths


def cleanup(module: Path, stage: Path) -> dict:
    assert_isolated(module, stage)
    if not stage.is_dir():
        raise ValueError('字体暂存目录不存在')
    try:
        data = json.loads((module / 'config/device_font_inventory.json').read_text())
        if not isinstance(data, dict) or data.get('state') != 'ready':
            data = {}
    except (OSError, ValueError):
        data = {}
    aliases = protected_aliases(stage, data, keep_lockscreen(module))
    rules = load_overrides(module / 'config/font-slot-overrides.json')
    if restricted(rules):
        from font_role_shadow import _classification
        for root in sorted(stage.glob('*/fonts')):
            for path in sorted(root.rglob('*')):
                if not path.is_file() or path.suffix.lower() not in ('.ttf', '.otf', '.ttc', '.otc'):
                    continue
                if any(part.startswith('.') for part in path.relative_to(root).parts):
                    continue
                logical = '/' + path.relative_to(stage).as_posix()
                role = _classification(logical, slot_for(data, logical))
                if (exclusion(logical, rules) or role['action'] in ('preserve', 'review')) and path not in aliases:
                    aliases.append(path)
    logicals = ['/' + p.relative_to(stage).as_posix() for p in aliases]
    # Write boot-repair exclusions first; a removed face must not be recreated
    # by the old missing-slot mapper on the following boot.
    paths = record_preserved(stage, logicals)
    for alias in aliases:
        alias.unlink(missing_ok=True)
    result = {'schema': 'ziyu-font-role-protection-v1',
              'removedStagedAliases': logicals, 'preservedStockPaths': paths}
    report = stage / '.ziyu-font-role-report.json'
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    report.chmod(0o644)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    print(json.dumps(cleanup(args.module, args.stage), ensure_ascii=False))
