#!/usr/bin/env python3
"""Recover immutable OEM link metadata, never font contents or active mounts.

A legacy lower tree can be incomplete or disappear after a provider reload.
Read directory entries from the existing independent read-only block reader;
persist only the original first hop, bound to the scanned ROM identity. Mutable
/data bridge links must still be read and checked in PID 1 on every verification.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import posixpath
import stat
import time

SCHEMA = 'ziyu-stock-font-link-evidence-v1'
MAX_BYTES = 128 * 1024
MAX_ENTRIES = 4096


def _rom_context(module: Path, inventory: dict) -> dict | None:
    from font_metrics_normalize import _device_build_key
    build = _device_build_key()
    if not build or build == 'unknown' or inventory.get('buildKey') != build:
        return None
    receipt = module / 'config/stock-rom-scan.conf'
    try:
        if receipt.is_symlink() or receipt.stat().st_size > 8192:
            return None
        fields = dict(line.split('=', 1) for line in receipt.read_text().splitlines() if '=' in line)
        if (fields.get('schema') != 'ziyu-stock-rom-v1' or not fields.get('rom_identity')
                or fields.get('fingerprint') != build):
            return None
    except (OSError, ValueError):
        return None
    return {'buildKey': build, 'romIdentity': fields['rom_identity']}


def _load(path: Path, context: dict) -> dict:
    try:
        if path.is_symlink() or path.stat().st_size > MAX_BYTES:
            return {}
        data = json.loads(path.read_text())
        if (not isinstance(data, dict) or data.get('schema') != SCHEMA
                or data.get('context') != context or not isinstance(data.get('roots'), dict)):
            return {}
        if any(not isinstance(record, dict) or (record.get('state') == 'complete' and (
                record.get('method') != 'independent-read-only-erofs'
                or not isinstance(record.get('links'), dict)
                or len(record['links']) > MAX_ENTRIES)) for record in data['roots'].values()):
            return {}
        return data
    except (OSError, ValueError):
        return {}


def _save(path: Path, data: dict) -> bool:
    temporary = path.with_name(path.name + f'.tmp.{os.getpid()}')
    try:
        if path.is_symlink() or path.parent.is_symlink():
            return False
        raw = json.dumps(data, ensure_ascii=True, sort_keys=True, separators=(',', ':')) + '\n'
        if len(raw.encode()) > MAX_BYTES:
            return False
        with temporary.open('x', encoding='utf-8') as stream:
            stream.write(raw)
        temporary.chmod(0o600)
        os.replace(temporary, path)
        return True
    except OSError:
        return False
    finally:
        temporary.unlink(missing_ok=True)


def original_links(module: Path, inventory: dict, unresolved: set[str]) -> tuple[dict[str, str], dict]:
    summary = {'source': 'unavailable', 'rootsRead': 0, 'linksRecovered': 0}
    if inventory.get('state') != 'ready' or inventory.get('romKind') != 'coloros':
        return {}, summary
    # Only existing, exact visible font paths request metadata. This never expands
    # the payload, marks missing mounts successful, or accepts matching basenames.
    roots = {str(Path('/' + rel.lstrip('/')).parent) for rel in unresolved}
    roots &= {'/system/fonts', '/system_ext/fonts', '/product/fonts'}
    if not roots:
        return {}, summary
    context = _rom_context(module, inventory)
    if context is None:
        summary['reason'] = 'stock-ROM-receipt-unavailable'
        return {}, summary
    cache_file = module / 'config/stock-font-link-evidence.json'
    cached = _load(cache_file, context)
    records = cached.get('roots', {}).copy()
    try:
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    except OSError:
        return {}, summary
    now = int(time.time())
    missing = []
    for root in sorted(roots):
        record = records.get(root, {})
        if record.get('state') == 'complete' and isinstance(record.get('links'), dict):
            continue
        # Unavailable readers must not start a mount/read loop on every poll.
        elapsed = now - record.get('checkedAt', 0) if isinstance(record.get('checkedAt', 0), int) else 60
        if record.get('bootId') == boot and 0 <= elapsed < 60:
            continue
        missing.append(root)
    if missing:
        from stock_readonly_view import StockReadonlyView
        reader = StockReadonlyView()
        try:
            for logical in missing:
                try:
                    # Read the parent partition, not /system/fonts' active overlay.
                    parent = reader.pick(Path(logical).parent)
                    if parent is None:
                        raise OSError('independent read-only partition unavailable')
                    directory = parent / Path(logical).name
                    if (directory.is_symlink() or not directory.is_dir()
                            or not directory.resolve().is_relative_to(parent.resolve())):
                        raise OSError('original font directory unavailable')
                    before = directory.stat()
                    links = {}
                    count = 0
                    with os.scandir(directory) as entries:
                        for entry in entries:
                            count += 1
                            if count > MAX_ENTRIES:
                                raise OSError('original font directory entry limit exceeded')
                            if (Path(entry.name).suffix.lower() not in {'.ttf', '.otf', '.ttc', '.otc'}
                                    or not stat.S_ISLNK(entry.stat(follow_symlinks=False).st_mode)):
                                continue
                            target = os.readlink(entry.path)
                            if not target or any(c in target for c in ('\x00', '\n', '\r')):
                                raise OSError('invalid original font link')
                            links[logical + '/' + entry.name] = posixpath.normpath(
                                target if target.startswith('/') else posixpath.join(logical, target))
                    after = directory.stat()
                    identity = lambda s: (s.st_dev, s.st_ino, s.st_mtime_ns, s.st_ctime_ns)
                    if identity(before) != identity(after):
                        raise OSError('original font directory changed')
                    records[logical] = {'state': 'complete', 'links': links, 'entryCount': count,
                                        'method': 'independent-read-only-erofs', 'checkedAt': now}
                    summary['rootsRead'] += 1
                except (OSError, ValueError) as error:
                    records[logical] = {'state': 'unavailable', 'reason': str(error),
                                        'checkedAt': now, 'bootId': boot}
        finally:
            # Release only this reader's owned resources; never mutate a provider.
            reader.close()
        if _rom_context(module, inventory) != context:
            summary['reason'] = 'stock-ROM-identity-changed-during-read'
            return {}, summary
        summary['cacheSaved'] = _save(cache_file, {'schema': SCHEMA, 'context': context, 'roots': records})
    result = {}
    for root in roots:
        record = records.get(root, {})
        if record.get('state') != 'complete' or not isinstance(record.get('links'), dict):
            continue
        for logical, target in record['links'].items():
            if (isinstance(logical, str) and Path(logical).parent == Path(root)
                    and isinstance(target, str) and target.startswith('/')
                    and not any(c in logical + target for c in ('\x00', '\n', '\r'))):
                result[logical] = target
    complete = any(records.get(root, {}).get('state') == 'complete' for root in roots)
    summary['source'] = ('independent-read-only-erofs' if summary['rootsRead'] else 'same-ROM-cache') if complete else 'unavailable'
    if not complete:
        summary['reasons'] = {root: records.get(root, {}).get('reason', 'not-recorded') for root in roots}
    summary['linksRecovered'] = len(result)
    return result, summary
