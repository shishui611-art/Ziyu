#!/usr/bin/env python3
"""Persist verified ROM font bytes before provider mounts; never mount anything.

Content addressed copies belong to this module. A mounted replacement is not
admitted just because its name or metrics resemble stock. Compilation verifies
the build identity and content hash before using a saved copy.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
from functools import lru_cache

import font_inventory as inventory
from font_scan_profile import walk

SCHEMA = 'ziyu-stock-font-snapshot-v1'
MAX_BYTES = 384 * 1024 * 1024
MAX_FILE = 128 * 1024 * 1024
_verified = {}
_loaded = {}


@lru_cache(maxsize=1)
def build_identity():
    # Android build properties are immutable during this short-lived process.
    return inventory.current_build_key()


def identity(path: Path):
    s = path.stat()
    return (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)


def load(module: Path):
    manifest = module / 'config/stock-font-snapshot/manifest.json'
    build, _, _ = build_identity()
    try:
        key = (str(manifest), identity(manifest), build)
        if key not in _loaded:
            if manifest.is_symlink() or manifest.stat().st_size > 2 * 1024 * 1024:
                return {}
            data = json.loads(manifest.read_text(encoding='utf-8'))
            if (data.get('schema') != SCHEMA or data.get('buildKey') != build
                    or build == 'unknown' or not isinstance(data.get('files'), dict)):
                return {}
            _loaded[key] = data
        return _loaded[key]
    except (OSError, ValueError):
        return {}


def resolve(logical: str, module: Path | None = None, digest_cache=None) -> Path | None:
    module = module or Path(os.environ.get('MODDIR', os.environ.get('MODULE_DIR', '/data/adb/modules/ziyu')))
    data = load(module)
    entry = data.get('files', {}).get(logical)
    if not isinstance(entry, dict):
        return None
    digest = entry.get('sha256', '')
    if not isinstance(digest, str) or len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
        return None
    root = module / 'config/stock-font-snapshot'
    path = root / 'files' / (digest + '.font')
    try:
        if (root.is_symlink() or (root / 'files').is_symlink() or path.is_symlink()
                or not path.resolve().is_relative_to(root.resolve())):
            return None
        before = identity(path)
        if before[2] != entry.get('size'):
            return None
        key = (str(path), before, digest)
        if key not in _verified:
            if digest_cache is not None:
                actual = digest_cache.sha256(path)
            else:
                with path.open('rb') as stream:
                    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != digest or identity(path) != before:
                return None
            _verified[key] = True
        return path
    except (OSError, ValueError):
        return None


def capture(module: Path) -> dict:
    build, fingerprint, _ = build_identity()
    if build == 'unknown' or not fingerprint:
        return {'state': 'deferred', 'reason': 'unknown-build'}
    root = module / 'config/stock-font-snapshot'
    if root.is_symlink() or (root / 'files').is_symlink():
        raise ValueError('原厂快照目录包含外部链接')
    existing = load(module)
    current = inventory._load_json(module / 'config/device_font_inventory.json') or {}
    requested = set()
    if current.get('buildKey') == build:
        requested.update(current.get('slots', {}))
        for node in current.get('xmlGraph', {}).get('refs', []):
            if isinstance(node, dict):
                value = node.get('resolvedPath') or node.get('logicalPath') or node.get('path')
                if isinstance(value, str):
                    requested.add(value)
    # Same ROM and unchanged target set need no font copying/hashing at boot.
    if (existing.get('state') == 'complete' and requested
            and requested.issubset(existing.get('files', {}))
            and all((root / 'files' / (e['sha256'] + '.font')).is_file()
                    for e in existing['files'].values())):
        return {'state': 'reused', 'count': len(existing['files'])}
    # Reused snapshots do not need to import the font compiler and its graph.
    # Keep the original source/build/hash checks for any actual capture/use.
    from universal_font_compiler import _stock_views
    views = _stock_views()
    if not views:
        return {'state': 'deferred', 'reason': 'no-verified-stock-view',
                'retained': len(existing.get('files', {}))}
    files = dict(existing.get('files', {}))
    root.mkdir(parents=True, exist_ok=True)
    (root / 'files').mkdir(exist_ok=True)
    used = sum(size for _, size in {(e['sha256'], e['size']) for e in files.values()})
    copied = {}
    warnings = []
    for roots in views:
        for font_root in roots:
            for actual in walk(font_root.actual, depth=4, limit=8192):
                if actual.suffix.lower() not in inventory.FONT_EXTENSIONS:
                    continue
                logical = str(font_root.logical / actual.relative_to(font_root.actual))
                if requested and logical not in requested:
                    continue
                if logical in files:
                    continue
                try:
                    source = inventory._stock_font_path(font_root, actual, roots, preserve_namespace=True)
                    before = identity(source)
                    if before[2] <= 0 or before[2] > MAX_FILE:
                        raise ValueError('原厂字体大小超出快照限制')
                    if before in copied:
                        files[logical] = copied[before]
                        continue
                    if used + before[2] > MAX_BYTES:
                        raise ValueError('原厂快照达到空间上限')
                    fd, temporary = tempfile.mkstemp(prefix='.copy-', dir=root / 'files')
                    temporary = Path(temporary)
                    try:
                        digest = hashlib.sha256()
                        with os.fdopen(fd, 'wb') as out, source.open('rb') as stream:
                            while chunk := stream.read(1024 * 1024):
                                digest.update(chunk)
                                out.write(chunk)
                            out.flush()
                            os.fsync(out.fileno())
                        if identity(source) != before or temporary.stat().st_size != before[2]:
                            raise ValueError('原厂字体在复制期间发生变化')
                        sha = digest.hexdigest()
                        destination = root / 'files' / (sha + '.font')
                        temporary.chmod(0o444)
                        os.replace(temporary, destination)
                        entry = {'sha256': sha, 'size': before[2]}
                        files[logical] = copied[before] = entry
                        used += before[2]
                    finally:
                        temporary.unlink(missing_ok=True)
                except (OSError, ValueError, inventory.InventoryError) as error:
                    warnings.append(f'{logical}: {error}')
    state = 'complete' if files and not warnings and (not requested or requested.issubset(files)) else 'partial'
    inventory._atomic_write(root / 'manifest.json', {
        'schema': SCHEMA, 'buildKey': build, 'buildFingerprint': fingerprint,
        'state': state, 'capturedAt': int(time.time()), 'files': files,
        'missing': sorted(requested - files.keys()), 'warnings': warnings[:32]})
    # Remove only unreferenced private content files after committing the receipt.
    retained = {e['sha256'] + '.font' for e in files.values()}
    for path in (root / 'files').glob('*.font'):
        if path.name not in retained and not path.is_symlink():
            path.unlink()
    return {'state': state, 'count': len(files), 'newCopies': len(copied),
            'bytes': used, 'missing': len(requested - files.keys())}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(capture(args.module), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'state': 'deferred', 'reason': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
