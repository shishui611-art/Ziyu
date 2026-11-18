#!/usr/bin/env python3
"""Ziyu-owned Emoji assets and immutable next-boot payloads. GPL-3.0-only."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from font_route_verify import RouteError, device_partitions, namespace_file

MAX_FONT = 96 * 1024 * 1024
MAX_ARCHIVE = 128 * 1024 * 1024
MARKER = '.ziyu-emoji.json'
REQUEST = '.ziyu-emoji-request.json'
SELECTION = 'emoji-selection.json'
SCHEMA = 'ziyu-emoji-v1'
IDENTIFIER = re.compile(r'[0-9a-f]{64}')
TARGET = re.compile(r'[A-Za-z][A-Za-z0-9_]*/fonts/[A-Za-z0-9_.-]*emoji[A-Za-z0-9_.-]*\.(?:ttf|otf)', re.I)


def identity(path):
    value = path.stat()
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def digest(path):
    before = identity(path)
    with path.open('rb') as stream:
        result = hashlib.file_digest(stream, 'sha256').hexdigest()
    if before != identity(path):
        raise ValueError('Emoji 文件在读取时发生变化，请重试')
    return result


def read_json(path, maximum=65536):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ValueError('Emoji 状态文件无效')
    result = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(result, dict):
        raise ValueError('Emoji 状态格式无效')
    return result


def atomic_json(path, value):
    # Never truncate metadata hardlinked from an older immutable generation.
    fd, temporary = tempfile.mkstemp(prefix='.emoji-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def marker(payload):
    if payload.is_symlink() or (payload.exists() and not payload.is_dir()):
        raise ValueError('Emoji 负载目录无效')
    path = payload / MARKER
    if not path.exists() and not path.is_symlink():
        return {'schema': SCHEMA, 'id': 'default', 'name': '系统默认', 'targets': []}
    value = read_json(path)
    selected = value.get('id', '')
    targets = value.get('targets')
    if (value.get('schema') != SCHEMA or not isinstance(selected, str)
            or (selected != 'default' and not IDENTIFIER.fullmatch(selected))
            or not isinstance(targets, list) or len(targets) > 64
            or any(not isinstance(p, str) or not TARGET.fullmatch(p) or '..' in PurePosixPath(p).parts for p in targets)
            or len(targets) != len(set(targets))
            or (selected == 'default' and targets) or (selected != 'default' and not targets)):
        raise ValueError('Emoji 负载记录无效，请导出日志')
    return value


def request_record(value):
    selected = value.get('id', '')
    if (value.get('schema') != SCHEMA or not isinstance(selected, str)
            or (selected != 'default' and not IDENTIFIER.fullmatch(selected))):
        raise ValueError('Emoji 选择记录无效，请重新应用 Emoji')
    return {'schema': SCHEMA, 'id': selected, 'name': str(value.get('name', 'Emoji'))[:160]}


def requested(module):
    selection = module / 'config' / SELECTION
    if selection.exists() or selection.is_symlink():
        return request_record(read_json(selection))
    # Upgrade the old payload-local selection without requiring its files to
    # exist. Only an explicit Emoji operation chooses a different asset.
    try:
        payload = selected_payload(module)
    except (OSError, ValueError):
        # Older text-only recovery queues may lack a new-style receipt. Their
        # validation belongs to the text transaction, not the Emoji chooser.
        payload = applied_payload(module)
    if (payload / REQUEST).is_file():
        return request_record(read_json(payload / REQUEST))
    return request_record(marker(payload))


def publish_request(module):
    payload = module / '.ziyu-payload-next'
    path = payload / REQUEST
    if not path.exists():
        return None
    value = request_record(read_json(path))
    atomic_json(module / 'config' / SELECTION, value)
    return value


def migrate_component(module, previous, payload):
    """Repair only the replacement module, leaving the running module intact."""
    previous = previous.resolve(strict=True)
    if previous == module or (module / '.git').exists() or not (module / 'module.prop').is_file():
        raise ValueError('Emoji 模块迁移目录无效')
    issue = ''
    try:
        current = marker(payload)
    except (OSError, ValueError) as error:
        current = {'schema': SCHEMA, 'id': 'default', 'name': '系统默认', 'targets': []}
        issue = str(error)
    try:
        selection = requested(previous)
    except (OSError, ValueError):
        selection = request_record(current)
    source = None
    if current['id'] != 'default':
        try:
            verified_marker(payload)
            source = payload / current['targets'][0]
        except (OSError, ValueError):
            try:
                if not (previous / 'config/emoji-library').is_dir():
                    raise ValueError('Emoji 导入资源缺失')
                source, _info = asset(previous, current['id'])
            except (OSError, ValueError) as error:
                current = {'schema': SCHEMA, 'id': 'default', 'name': '系统默认', 'targets': []}
                issue = str(error)
    temporary = module / '.ziyu-state/tmp'
    if temporary.is_symlink() or (module / '.ziyu-state').is_symlink():
        raise ValueError('Emoji 迁移暂存目录包含链接')
    temporary.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.ziyu-payload-stage.migration-', dir=temporary) as directory:
        component = Path(directory)
        before = identity(source) if source is not None else None
        for relative in current['targets']:
            destination = component / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(source, destination)
            except OSError:
                shutil.copyfile(source, destination)
                destination.chmod(0o644)
        if source is not None and identity(source)[:-1] != before[:-1]:
            raise ValueError('Emoji 迁移来源在读取时变化')
        if issue:
            current['issue'] = 'Emoji 需要重新应用。'
            current['issueDetail'] = issue
        atomic_json(component / MARKER, current)
        verified_marker(component)
        # Old copied Emoji paths are component-owned. Text paths and their
        # original manifest hashes are untouched by this repair.
        for part in device_partitions(module):
            fonts = module / part / 'fonts'
            if fonts.is_symlink() or (module / part).is_symlink():
                raise ValueError('Emoji 迁移字体目录包含链接')
            for path in fonts.glob('*'):
                if TARGET.fullmatch(path.relative_to(module).as_posix()):
                    path.unlink(missing_ok=True)
        for relative in current['targets']:
            destination = module / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            os.replace(component / relative, destination)
        private = module / '.ziyu-payload'
        if private.is_symlink():
            raise ValueError('Emoji 迁移负载目录包含链接')
        private.mkdir(exist_ok=True)
        os.replace(component / MARKER, private / MARKER)
    ledger = module / 'config/font-payload-manifest.conf'
    if ledger.is_file() and current['id'] == 'default':
        original = ledger.read_text()
        retained = ''.join(line + '\n' for line in original.splitlines()
                           if not TARGET.fullmatch(line.split('|', 1)[0]))
        if retained != original:
            from font_live_payload import atomic
            atomic(ledger, retained)
    atomic_json(module / 'config' / SELECTION, selection)
    return current


def selected_payload(module):
    pending = module / '.ziyu-payload-next'
    state = module / 'config/font-payload-next.conf'
    if pending.exists() or state.exists():
        if pending.is_symlink() or not pending.is_dir() or state.is_symlink() or not state.is_file():
            raise ValueError('待应用字体事务尚未完成，请稍后重试')
        conf = dict(line.split('=', 1) for line in state.read_text().splitlines() if '=' in line)
        receipt = pending / '.ziyu-next-transaction.conf'
        if receipt.is_symlink() or not receipt.is_file():
            raise ValueError('待应用字体回执缺失')
        queued = dict(line.split('=', 1) for line in receipt.read_text().splitlines() if '=' in line)
        if (conf.get('state') != 'prepared' or not conf.get('requestId')
                or conf.get('requestId') != queued.get('requestId') or conf.get('font') != queued.get('font')):
            raise ValueError('待应用字体回执不一致')
        return pending
    return applied_payload(module)


def applied_payload(module):
    # Historical live generations have their own immutable source directory.
    from font_live_payload import current
    live = current(module)
    source = Path(live['source']) if live else module / '.ziyu-payload'
    if source.is_symlink():
        raise ValueError('字体负载目录包含外部链接')
    return source


def library(module):
    path = module / 'config/emoji-library'
    if path.is_symlink():
        raise ValueError('Emoji 字库目录无效')
    path.mkdir(parents=True, exist_ok=True)
    return path


def asset(module, selected):
    if not IDENTIFIER.fullmatch(selected):
        raise ValueError('Emoji 标识无效')
    root = library(module)
    path = root / (selected + '.ttf')
    info = read_json(root / (selected + '.json'))
    if path.is_symlink() or not path.is_file() or info.get('id') != selected or digest(path) != selected:
        raise ValueError('Emoji 来源已变化，请重新导入')
    return path, info


def app_cache(path):
    raw = path.as_posix()
    if not re.fullmatch(r'/data/(?:user/\d+|data)/io\.github\.shishui611_art\.ziyu/cache/emoji-[A-Za-z0-9-]+\.(?:ttf|zip)', raw):
        raise ValueError('Emoji 文件须来自字域临时目录')
    if path.is_symlink() or not path.is_file() or path.parent.is_symlink():
        raise ValueError('Emoji 临时文件无效')
    return path


def inspect_font(path):
    from fontTools.ttLib import TTFont
    if not 0 < path.stat().st_size <= MAX_FONT:
        raise ValueError('Emoji 字体为空或超过 96 MiB')
    with path.open('rb') as stream:
        header = stream.read(12)
    if header[:4] not in (b'\x00\x01\x00\x00', b'OTTO') or not 0 < int.from_bytes(header[4:6], 'big') <= 256:
        raise ValueError('请选择单个 TTF 或 OTF 彩色 Emoji 字体')
    with TTFont(path, lazy=True) as font:
        for tag in ('head', 'maxp', 'cmap', 'name'):
            if tag not in font:
                raise ValueError('Emoji 字体缺少必要表 ' + tag)
        for entry in font.reader.tables.values():
            if entry.offset < 12 or entry.length < 0 or entry.offset + entry.length > path.stat().st_size:
                raise ValueError('Emoji 字体表越界')
        if not ({'CBDT', 'CBLC'} <= set(font.keys()) or {'COLR', 'CPAL'} <= set(font.keys())):
            raise ValueError('请选择 CBDT 或 COLR 彩色 Emoji 字体')
        cmap = font.getBestCmap() or {}
        if not any(0x1F000 <= code <= 0x1FAFF or 0x2600 <= code <= 0x27BF for code in cmap):
            raise ValueError('字体中未找到 Emoji 字符')
        # Load shaping/color indexes now, while the imported bytes are isolated.
        for tag in ('GSUB', 'CBLC', 'COLR', 'CPAL'):
            if tag in font:
                font[tag]
        names = font['name']
        name = names.getDebugName(4) or names.getDebugName(1) or 'Emoji'
        return {'name': name[:160], 'glyphs': int(font['maxp'].numGlyphs), 'codepoints': len(cmap)}


def copy_bounded(source, destination, limit):
    total = 0
    with destination.open('xb') as output:
        while block := source.read(1024 * 1024):
            total += len(block)
            if total > limit:
                raise ValueError('Emoji 文件超过大小上限')
            output.write(block)
        output.flush()
        os.fsync(output.fileno())


def import_font(module, source):
    source = app_cache(source)
    if not 0 < source.stat().st_size <= MAX_ARCHIVE:
        raise ValueError('导入文件为空或超过 128 MiB')
    before = identity(source)
    root = library(module)
    # The shared font lock proves these belong to interrupted older imports.
    for stale in root.glob('.import-*'):
        if stale.is_dir() and not stale.is_symlink():
            shutil.rmtree(stale)
    with tempfile.TemporaryDirectory(prefix='.import-', dir=root) as temporary:
        font_path = Path(temporary) / 'font.ttf'
        if source.suffix == '.zip':
            # Bound the central directory before ZipFile allocates its entry list.
            with source.open('rb') as stream:
                stream.seek(max(0, source.stat().st_size - 65557))
                tail = stream.read()
            index = tail.rfind(b'PK\x05\x06')
            if index < 0 or len(tail[index:]) < 22:
                raise ValueError('ZIP 目录无效')
            count = int.from_bytes(tail[index + 10:index + 12], 'little')
            size = int.from_bytes(tail[index + 12:index + 16], 'little')
            if (not 0 < count <= 4096 or size > 4 * 1024 * 1024
                    or tail[max(0, index - 20):index - 16] == b'PK\x06\x07'):
                raise ValueError('ZIP 文件列表超过上限')
            with zipfile.ZipFile(source) as archive:
                entries = [entry for entry in archive.infolist()
                           if not entry.is_dir() and PurePosixPath(entry.filename).suffix.lower() in ('.ttf', '.otf')]
                preferred = [entry for entry in entries if PurePosixPath(entry.filename).name.lower() == 'notocoloremoji.ttf']
                candidates = preferred or [entry for entry in entries if 'emoji' in entry.filename.lower()]
                if len(candidates) != 1:
                    raise ValueError('压缩包中有多份 Emoji 字体或未找到字体，请解压后导入单个字体')
                entry = candidates[0]
                if (entry.flag_bits & 1 or not 0 < entry.file_size <= MAX_FONT
                        or stat.S_ISLNK(entry.external_attr >> 16)):
                    raise ValueError('ZIP 中的 Emoji 字体无效或已加密')
                # Read one entry as data. No archive path or script is extracted.
                with archive.open(entry) as stream:
                    copy_bounded(stream, font_path, MAX_FONT)
        else:
            with source.open('rb') as stream:
                copy_bounded(stream, font_path, MAX_FONT)
        if before != identity(source):
            raise ValueError('导入来源已变化，请重试')
        info = inspect_font(font_path)
        selected = digest(font_path)
        info['id'] = selected
        info['bytes'] = font_path.stat().st_size
        destination = root / (selected + '.ttf')
        if destination.is_symlink():
            raise ValueError('Emoji 字库文件无效')
        if destination.exists():
            if digest(destination) != selected:
                # Replace the library pathname with newly verified bytes. Any
                # older mounted inode remains owned by its original payload.
                os.chmod(font_path, 0o644)
                os.replace(font_path, destination)
        else:
            os.chmod(font_path, 0o644)
            os.replace(font_path, destination)
        atomic_json(root / (selected + '.json'), info)
        return info


def discover_targets(module, root=Path('/proc/1/root')):
    parts = device_partitions(module)
    targets = set()
    def add(logical):
        try:
            visible, relative = namespace_file(root, logical)
            if (visible.is_file() and TARGET.fullmatch(relative)
                    and PurePosixPath(relative).parts[0] in parts):
                targets.add(relative)
        except (OSError, ValueError, RouteError):
            pass
    for partition in parts:
        fonts = root / partition / 'fonts'
        if fonts.is_dir():
            for path in fonts.iterdir():
                if TARGET.fullmatch(f'{partition}/fonts/{path.name}'):
                    add(f'{partition}/fonts/{path.name}')
        etc = root / partition / 'etc'
        if not etc.is_dir():
            continue
        for path in etc.glob('*font*.xml'):
            if not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
                continue
            try:
                document = ET.parse(path)
            except (OSError, ET.ParseError):
                continue
            for node in document.iter('font'):
                declared = (node.text or '').strip()
                if 'emoji' not in declared.lower():
                    continue
                if declared.startswith('/'):
                    add(declared)
                else:
                    for part in dict.fromkeys((partition, 'system', *parts)):
                        try:
                            visible, _ = namespace_file(root, f'{part}/fonts/{declared}')
                        except (OSError, RouteError):
                            continue
                        if visible.is_file():
                            add(f'{part}/fonts/{declared}')
                            break
    if not targets or len(targets) > 64:
        raise ValueError('未找到可替换的系统 Emoji 字体，请导出日志')
    return sorted(targets)


def checked_stage(module, stage):
    if (stage.is_symlink() or stage.parent.is_symlink()
            or stage.parent.resolve() != (module / '.ziyu-state/tmp').resolve()
            or not stage.name.startswith('.ziyu-payload-stage.') or not stage.is_dir()):
        raise ValueError('Emoji 暂存目录无效')


def verified_marker(payload):
    value = marker(payload)
    hashes = {}
    for relative in value['targets']:
        path = payload / relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(payload.resolve()):
            raise ValueError('Emoji 负载文件缺失')
        key = identity(path)
        if key not in hashes:
            hashes[key] = digest(path)
        if hashes[key] != value['id']:
            raise ValueError('Emoji 负载校验失败')
    return value


def stage_selection(module, stage, selected):
    checked_stage(module, stage)
    old = marker(stage)
    for relative in old['targets']:
        path = stage / relative
        if path.parent.is_symlink() or path.parent.parent.is_symlink():
            raise ValueError('Emoji 暂存路径包含链接')
        path.unlink(missing_ok=True)
    if selected == 'default':
        value = {'schema': SCHEMA, 'id': 'default', 'name': '系统默认', 'targets': []}
    else:
        source, info = asset(module, selected)
        source_identity = identity(source)
        targets = discover_targets(module)
        for relative in targets:
            destination = stage / relative
            if destination.parent.is_symlink() or destination.parent.parent.is_symlink():
                raise ValueError('Emoji 暂存路径包含链接')
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.resolve().is_relative_to(stage.resolve()):
                raise ValueError('Emoji 暂存路径超出字体负载')
            # Only an owned Emoji target can be replaced inside the new generation.
            destination.unlink(missing_ok=True)
            try:
                os.link(source, destination)
            except OSError:
                shutil.copyfile(source, destination)
            os.chmod(destination, 0o644)
        # Creating hardlinks changes ctime without changing the font bytes.
        if identity(source)[:-1] != source_identity[:-1]:
            raise ValueError('Emoji 来源在生成时发生变化，请重试')
        value = {'schema': SCHEMA, 'id': selected, 'name': info['name'], 'targets': targets}
    atomic_json(stage / MARKER, value)
    atomic_json(stage / REQUEST, request_record(value))
    verified_marker(stage)
    return value


def inherit(module, stage):
    """Retain the independently selected Emoji without gating text on it."""
    checked_stage(module, stage)
    for path in stage.glob('*/fonts/*'):
        if TARGET.fullmatch(path.relative_to(stage).as_posix()):
            if path.parent.is_symlink() or path.parent.parent.is_symlink():
                raise ValueError('Emoji 暂存路径包含链接')
            path.unlink(missing_ok=True)
    # Old text caches can contain a receipt without its Emoji bytes. Cache
    # bookkeeping has no authority over the user's independent selection.
    (stage / MARKER).unlink(missing_ok=True)
    (stage / REQUEST).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='.ziyu-payload-stage.emoji-', dir=stage.parent) as temporary:
        component = Path(temporary)
        selection = None
        ready = False
        try:
            selection = requested(module)
            stage_selection(module, component, selection['id'])
            ready = True
        except (OSError, ValueError, RouteError) as error:
            issue = str(error)
        # A missing library asset may still have a good immutable applied copy.
        # A failed attempt is isolated from the text generation being committed.
        if not ready and selection is not None and selection['id'] != 'default':
            try:
                source = selected_payload(module)
                current = verified_marker(source)
                if current['id'] != selection['id']:
                    raise ValueError('当前 Emoji 与所选 Emoji 不一致')
                shutil.rmtree(component)
                component.mkdir()
                for relative in current['targets']:
                    destination = component / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    try:
                        os.link(source / relative, destination)
                    except OSError:
                        shutil.copyfile(source / relative, destination)
                        destination.chmod(0o644)
                atomic_json(component / MARKER, current)
                atomic_json(component / REQUEST, selection)
                verified_marker(component)
                ready = True
            except (OSError, ValueError, RouteError) as error:
                issue = str(error)
        if not ready:
            shutil.rmtree(component)
            component.mkdir()
            stage_selection(module, component, 'default')
            value = marker(component)
            value['issue'] = 'Emoji 文件未能保留，请在 Emoji 中重新应用。'
            value['issueDetail'] = issue
            atomic_json(component / MARKER, value)
            if selection is not None:
                atomic_json(component / REQUEST, selection)
            else:
                (component / REQUEST).unlink(missing_ok=True)
        # Only a completely prepared component enters the new text generation.
        # Failures writing this shared output are ordinary transaction failures.
        value = marker(component)
        for relative in value['targets']:
            destination = stage / relative
            if destination.parent.is_symlink() or destination.parent.parent.is_symlink():
                raise ValueError('Emoji 暂存路径包含链接')
            destination.parent.mkdir(parents=True, exist_ok=True)
            os.replace(component / relative, destination)
        os.replace(component / MARKER, stage / MARKER)
        if (component / REQUEST).exists():
            os.replace(component / REQUEST, stage / REQUEST)
        return value


def clone(module, stage):
    checked_stage(module, stage)
    if any(stage.iterdir()):
        raise ValueError('Emoji 暂存目录尚未清理')
    source = selected_payload(module)
    if not source.exists():
        return
    old = marker(source)
    def copy(source_file, destination):
        try:
            os.link(source_file, destination)
        except OSError:
            shutil.copy2(source_file, destination)
    # Live publication trees and their font bytes are never modified here.
    shutil.copytree(source, stage, copy_function=copy, symlinks=True, dirs_exist_ok=True)
    if source == module / '.ziyu-payload-next':
        state = module / 'config/font-payload-next.conf'
        conf = dict(line.split('=', 1) for line in state.read_text().splitlines() if '=' in line)
        expected = conf.get('payloadDigest', '')
        if not IDENTIFIER.fullmatch(expected):
            raise ValueError('待应用字体缺少完整性记录')
        check_payload(stage, expected)
    elif ((module / 'config/active_font.conf').is_file()
          and (module / 'config/active_font.conf').read_text().strip() not in ('', 'default')):
        # Reuse the existing frozen text ledger to verify the cloned text.
        # The old Emoji is being replaced or restored, so damaged old Emoji
        # bytes must not prevent removal of that owned overlay.
        from font_update_manifest import verify
        ledger = module / 'config/font-payload-manifest.conf'
        records = [line for line in ledger.read_text().splitlines()
                   if line and line.split('|', 1)[0] not in old['targets']]
        expected_paths = {line.split('|', 1)[0] for line in records}
        parts = set(device_partitions(module))
        for part in parts:
            for path in (stage / part / 'fonts').rglob('*'):
                if path.is_file() and path.suffix.lower() in ('.ttf', '.otf', '.ttc', '.font'):
                    relative = path.relative_to(stage).as_posix()
                    if relative not in expected_paths and relative not in old['targets']:
                        raise ValueError('文字字体负载存在未登记文件，请重新应用文字字体')
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=stage.parent,
                                         prefix='.emoji-text-ledger-', delete=False) as stream:
            filtered = Path(stream.name)
            stream.write('\n'.join(records) + '\n')
        try:
            verify(stage, filtered, False, parts)
        finally:
            filtered.unlink(missing_ok=True)


def status(module):
    try:
        selected_root = selected_payload(module)
    except (OSError, ValueError):
        selected_root = applied_payload(module)
    prepared = marker(selected_root)
    problem = str(prepared.get('issue', ''))
    try:
        selection = requested(module)
    except (OSError, ValueError) as error:
        selection = request_record(prepared)
        problem = str(error)
    active = marker(applied_payload(module))
    items = []
    for path in sorted(library(module).glob('*.json')):
        value = read_json(path)
        if IDENTIFIER.fullmatch(str(value.get('id', ''))) and (path.parent / (value['id'] + '.ttf')).is_file():
            items.append(value)
    # Read the existing current-boot verification receipt instead of hashing
    # a large font every time the settings page opens.
    verification = module / 'config/mount-backend-verification.json'
    mounted = active['id'] == 'default'
    if active['id'] != 'default' and verification.is_file():
        report = read_json(verification, 4 * 1024 * 1024)
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        manifest = module / 'config/font-payload-manifest.conf'
        mounted = (report.get('bootId') == boot and report.get('emojiId') == active['id']
                   and report.get('state') in ('verified', 'partial') and manifest.is_file()
                   and report.get('payloadManifestSha256') == digest(manifest)
                   and set(active['targets']).issubset(report.get('mountedFonts', []))
                   and bool(set(active['targets']).intersection(report.get('emojiRoutes', []))))
    pending = selected_root == module / '.ziyu-payload-next' and prepared['id'] != active['id']
    if not problem and selection['id'] != prepared['id']:
        problem = '所选 Emoji 需要重新应用。'
    return {'items': items, 'selected': selection['id'], 'active': active['id'],
            'pending': pending, 'mounted': mounted, 'problem': problem,
            'selectedName': selection.get('name', 'Emoji'), 'activeName': active.get('name', 'Emoji')}


def preview(module, selected, destination):
    # App creates the file first so its uid and SELinux label remain intact.
    destination = app_cache(destination)
    source, info = asset(module, selected)
    before = identity(source)
    with source.open('rb') as input_stream, destination.open('wb') as output:
        shutil.copyfileobj(input_stream, output, 1024 * 1024)
    if before != identity(source) or digest(destination) != selected:
        raise ValueError('Emoji 预览复制校验失败')
    return info


def check_payload(payload, expected=''):
    value = marker(payload)
    if expected:
        from font_live_payload import scan_payload
        entries, hashes, _needed, _directories, actual = scan_payload(payload)
        if not IDENTIFIER.fullmatch(expected) or actual != expected:
            raise ValueError('待应用 Emoji 字体负载摘要不一致')
        font_hashes = {relative.as_posix(): hashes[key].hex() for _path, relative, key in entries}
        if any(font_hashes.get(relative) != value['id'] for relative in value['targets']):
            raise ValueError('待应用 Emoji 文件摘要不一致')
    else:
        verified_marker(payload)
    return value


def main():
    module = Path(sys.argv[1]).resolve(strict=True)
    action = sys.argv[2]
    if action == 'status':
        result = status(module)
    elif action == 'import':
        result = import_font(module, Path(sys.argv[3]))
    elif action == 'preview':
        result = preview(module, sys.argv[3], Path(sys.argv[4]))
    elif action == 'clone':
        result = clone(module, Path(sys.argv[3]))
    elif action == 'stage':
        result = stage_selection(module, Path(sys.argv[3]), sys.argv[4])
    elif action == 'inherit':
        result = inherit(module, Path(sys.argv[3]))
    elif action == 'publish-request':
        result = publish_request(module)
    elif action == 'migrate':
        result = migrate_component(module, Path(sys.argv[3]), Path(sys.argv[4]))
    elif action == 'check':
        result = check_payload(Path(sys.argv[3]), sys.argv[4] if len(sys.argv) > 4 else '')
    else:
        raise ValueError('未知 Emoji 操作')
    print(json.dumps({'status': 'ok', 'data': result}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        sys.exit(1)
