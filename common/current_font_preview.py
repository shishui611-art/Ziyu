#!/usr/bin/env python3
"""Export one currently verified CJK slot, never the mutable font-library source."""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile

MAX_FONT = 256 * 1024 * 1024


def proof(module: Path, font: str, boot: str):
    config = module / 'config'
    manifest = (config / 'font-payload-manifest.conf').read_bytes()
    digest = hashlib.sha256(manifest).hexdigest()
    for name in ('mount-backend-verification.json', 'device-font-load-route-verification.json'):
        path = config / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 4 * 1024 * 1024:
            continue
        raw = path.read_bytes()
        report = json.loads(raw)
        if not isinstance(report, dict):
            raise ValueError('当前字体验证记录无效')
        if (report.get('state') not in ('verified', 'partial') or report.get('bootId') != boot
                or report.get('activeFont') != font or report.get('namespace') != '/proc/1/root'
                or report.get('payloadManifestSha256') != digest):
            continue
        routes = report.get('cjkRoutes', [])
        if not isinstance(routes, list):
            raise ValueError('当前字体路由记录无效')
        candidates = []
        for line in manifest.decode('utf-8').splitlines():
            fields = line.split('|')
            if len(fields) < 2:
                continue
            rel, sha = fields[:2]
            parts = PurePosixPath(rel).parts
            if (len(parts) < 3 or parts[1] != 'fonts' or rel.startswith('/')
                    or any(p in ('..', '.') or p.startswith('.') for p in rel.split('/'))
                    or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', parts[0])
                    or PurePosixPath(rel).suffix.lower() not in ('.ttf', '.otf', '.ttc', '.otc')
                    or not re.fullmatch(r'[0-9a-f]{64}', sha)
                    or PurePosixPath(rel).name not in routes):
                continue
            if report.get('mode') == 'physical-safe' and rel not in report.get('appliedFonts', []):
                continue
            candidates.append((rel, sha))
        if not candidates:
            continue
        inventory_file = config / 'device_font_inventory.json'
        inventory = json.loads(inventory_file.read_text(encoding='utf-8')) if inventory_file.is_file() else {}
        if not isinstance(inventory, dict):
            raise ValueError('系统字体索引无效')
        main = str(inventory.get('mainSlotPath', '')).lstrip('/')
        candidates.sort(key=lambda item: (item[0] != main, 'regular' not in item[0].lower(), item[0]))
        relative, expected = candidates[0]
        refs = [r for r in inventory.get('xmlGraph', {}).get('refs', [])
                if isinstance(r, dict) and r.get('resolvedPath') == '/' + relative]
        chinese = [r for r in refs if any(lang.lower() in ('zh-hans', 'zh-cn', 'zh')
                   for lang in str(r.get('familyAttributes', {}).get('lang', '')).split(','))]
        indices = {r.get('index', 0) for r in chinese or refs} if report.get('mode') == 'physical-safe' else set()
        index = next(iter(indices)) if len(indices) == 1 else None
        return path, raw, manifest, (relative, expected, index)
    raise ValueError('当前字体尚无可用的本次启动挂载证据，可继续确认应用')


def trusted_destination(destination: Path):
    text = str(destination)
    roots = ('/data/user/0/', '/data/data/')
    packages = ('io.github.shishui611_art.ziyu', 'io.github.shishui611_art.ziyu.debug')
    if not any(text.startswith(root + package + '/cache/') for root in roots for package in packages):
        raise ValueError('预览目标目录不受信任')
    if any(p in ('.', '..') for p in text.split('/')) or destination.is_symlink():
        raise ValueError('预览目标路径无效')
    # /data/data itself is an Android alias; reject links only inside App cache.
    cache = next(p for p in destination.parents if p.name == 'cache')
    for parent in (destination.parent, *destination.parent.parents):
        if parent.is_symlink():
            raise ValueError('预览缓存目录不可为链接')
        if parent == cache:
            break
    if not destination.parent.is_dir():
        raise ValueError('预览缓存目录不存在')


def export(module: Path, font: str, destination: Path, *, visible_root=Path('/proc/1/root'), boot_file=Path('/proc/sys/kernel/random/boot_id')):
    if not font or font in ('default', 'unknown'):
        raise ValueError('当前为系统字体，无法导出自定义字体样例')
    boot = boot_file.read_text().strip()
    record, raw, manifest, (relative, expected, index) = proof(module, font, boot)
    source = visible_root / relative
    temporary = None
    try:
        # The read FD pins the visible inode across a concurrent font switch.
        with source.open('rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= MAX_FONT:
                raise ValueError('当前字体文件无效')
            digest = hashlib.sha256()
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.current-font-', delete=False) as output:
                temporary = Path(output.name)
                total = 0
                while block := stream.read(1024 * 1024):
                    total += len(block)
                    if total > MAX_FONT:
                        raise ValueError('当前字体超过预览大小限制')
                    digest.update(block)
                    output.write(block)
            after = os.fstat(stream.fileno())
        fingerprint = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        if (fingerprint(before) != fingerprint(after) or fingerprint(after) != fingerprint(source.stat())
                or digest.hexdigest() != expected or boot_file.read_text().strip() != boot
                or record.read_bytes() != raw
                or (module / 'config/font-payload-manifest.conf').read_bytes() != manifest):
            raise ValueError('字体已变化，请关闭后重新打开对比')
        # Metadata is read from the exported bytes, not the library. Collection
        # routes with a nonzero/unknown face are not misrepresented as face 0.
        from fontTools.ttLib import TTFont, TTCollection
        with temporary.open('rb') as header:
            data = header.read(4)
        if data == b'ttcf':
            with TTCollection(temporary, lazy=True) as collection:
                if len(collection.fonts) == 1:
                    index = 0
                if type(index) is not int or not 0 <= index < len(collection.fonts):
                    raise ValueError('当前集合含多个字面，暂不展示可能不一致的当前侧样例')
        else:
            index = 0
        with TTFont(temporary, fontNumber=index, lazy=True) as face:
            axis = next((a for a in face['fvar'].axes if a.axisTag == 'wght'), None) if 'fvar' in face else None
            weight = None
            if axis and all(math.isfinite(v) for v in (axis.minValue, axis.defaultValue, axis.maxValue)) and axis.minValue < axis.maxValue:
                weight = dict(min=axis.minValue, default=axis.defaultValue, max=axis.maxValue)
        temporary.chmod(0o644)
        os.replace(temporary, destination)
        return dict(path=str(destination), sha256=expected, logicalPath='/' + relative, weightAxis=weight, faceIndex=index)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    try:
        if len(sys.argv) != 4:
            raise ValueError('预览参数无效')
        module, font, destination = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
        trusted_destination(destination)
        data = export(module, font, destination)
        print(json.dumps(dict(status='ok', data=data), ensure_ascii=False))
        return 0
    except Exception as error:
        print(json.dumps(dict(status='error', message=str(error)), ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
