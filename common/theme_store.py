#!/usr/bin/env python3
"""Owned theme-font writes with durable per-file recovery, GPL-3.0-only."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile

from font_digest_cache import FontDigestCache
from font_expansion_guard import CoverageGuard, identity

STATE = Path('/data/adb/ziyu/theme-store')
SCHEMA = 'ziyu-theme-store-v2'
DIRS = {
    'coloros': ('/data/format_unclear/font', '/data/oplus/uxres/theme', '/data/system/theme/fonts'),
    'flyme': ('/data/customizecenter/font', '/data/theme/fonts', '/data/themes'),
    'originos': ('/data/bbkcore/theme', '/data/skin/fonts', '/data/system/theme/fonts'),
    'hyperos': ('/data/system/theme/fonts',),
}
ALLOWED = frozenset(Path(p) for roots in DIRS.values() for p in roots)
SUFFIXES = ('.ttf', '.otf', '.ttc')


def checked_target(value):
    path = Path(value)
    if (path.parent not in ALLOWED or path.suffix.lower() not in SUFFIXES
            or any(c in str(path) for c in '\r\n\0|')
            or path.is_symlink() or any(p.is_symlink() for p in path.parents)):
        raise ValueError('主题字体路径无效，已保留原文件')
    return path


def secure_directory(path):
    if path.is_symlink() or any(p.is_symlink() for p in path.parents):
        raise ValueError('主题备份目录不能是符号链接')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise ValueError('主题备份目录权限异常')


def atomic(path, data):
    if path.is_symlink():
        raise ValueError('主题记录不能是符号链接')
    fd, name = tempfile.mkstemp(prefix='.ziyu-record-', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def metadata(path):
    value = path.stat()
    label = None
    try:
        label = os.getxattr(path, 'security.selinux').hex()
    except OSError as error:
        if error.errno not in (errno.ENODATA, errno.ENOTSUP):
            raise
    return dict(mode=stat.S_IMODE(value.st_mode), uid=value.st_uid, gid=value.st_gid, label=label)


class ThemeStore:
    def __init__(self, module, rom):
        self.module, self.rom = module, rom
        self.path = STATE / 'journal.json'
        self.conf = module / 'config/tools/theme-store.conf'
        self.digests = FontDigestCache(path_scoped=True)
        self.guard = CoverageGuard()
        self.entries = {}

    def enabled(self):
        try:
            return 'enabled=1' in self.conf.read_text().splitlines()
        except FileNotFoundError:
            return False

    def set_enabled(self, value):
        self.conf.parent.mkdir(parents=True, exist_ok=True)
        atomic(self.conf, f'enabled={int(value)}\n'.encode())

    def save(self):
        # The pending marker is written first, before any external font changes.
        # It also makes failed uninstall recovery discoverable on reinstallation.
        if self.entries:
            atomic(STATE / 'pending', b'ziyu-theme-store-v2\n')
        atomic(self.path, (json.dumps({'schema': SCHEMA, 'entries': self.entries},
                                     ensure_ascii=False) + '\n').encode())
        if not self.entries:
            (STATE / 'pending').unlink(missing_ok=True)
            self.path.unlink(missing_ok=True)
            sync_directory(STATE)

    def load(self):
        if self.path.exists():
            if self.path.is_symlink() or self.path.stat().st_size > 1024 * 1024:
                raise ValueError('主题恢复记录无效')
            value = json.loads(self.path.read_text())
            if value.get('schema') != SCHEMA or not isinstance(value.get('entries'), dict):
                raise ValueError('主题恢复记录无效')
            self.entries = value['entries']
        for name, entry in self.entries.items():
            checked_target(name)
            if (not isinstance(entry, dict) or not isinstance(entry.get('backup'), str)
                    or entry['backup'] != hashlib.sha256(name.encode()).hexdigest() + '.font'
                    or not isinstance(entry.get('original'), str) or len(entry['original']) != 64):
                raise ValueError('主题备份记录无效')
            if (STATE / entry['backup']).is_symlink():
                raise ValueError('主题备份不能是符号链接')
        self.import_legacy()

    def copy(self, source, target, digest, attrs=None, expected_target=None):
        before = identity(source)
        fd, name = tempfile.mkstemp(prefix='.ziyu-theme-', dir=target.parent)
        temporary = Path(name)
        try:
            with os.fdopen(fd, 'wb') as output, source.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    output.write(block)
                if attrs:
                    os.fchown(output.fileno(), attrs['uid'], attrs['gid'])
                    os.fchmod(output.fileno(), attrs['mode'])
                    if attrs.get('label'):
                        os.setxattr(temporary, 'security.selinux', bytes.fromhex(attrs['label']))
                output.flush()
                os.fsync(output.fileno())
            if identity(source) != before or self.digests.sha256(temporary) != digest:
                raise ValueError('字体复制核验失败，已保留原文件')
            if expected_target is not None and identity(checked_target(target)) != expected_target:
                raise ValueError('主题字体已被其他应用修改，请重试')
            os.replace(temporary, target)
            sync_directory(target.parent)
            if self.digests.sha256(target) != digest:
                raise ValueError('主题字体写入后核验失败，恢复记录已保留')
        finally:
            temporary.unlink(missing_ok=True)

    def import_legacy(self):
        """Recover v1.5.43-45 records, including a cleared manifest with a live signature."""
        root = self.module / '.ziyu-state/theme-backup'
        manifest = root / 'manifest.txt'
        signatures = self.module / '.ziyu-state/theme-store.state'
        if not root.is_dir():
            return
        if root.is_symlink() or manifest.is_symlink() or signatures.is_symlink():
            raise ValueError('旧版主题恢复记录包含外部链接')
        names = set(manifest.read_text().splitlines()) if manifest.is_file() else set()
        records = signatures.read_text().splitlines() if signatures.is_file() else []
        old = dict(line.split('=', 1) for line in records if '=' in line)
        # Legacy backup names encode directory separators as underscores. Only
        # admit a unique reconstruction within the fixed theme-directory list.
        for backup in root.iterdir():
            matches = []
            for directory in ALLOWED:
                prefix = str(directory).lstrip('/').replace('/', '_') + '_'
                if backup.name.startswith(prefix):
                    candidate = directory / backup.name[len(prefix):]
                    if candidate.suffix.lower() in SUFFIXES:
                        matches.append(str(candidate))
            if len(matches) == 1 and backup.name in old:
                names.add(matches[0])
        consumed = set()
        for name in sorted(filter(None, names)):
            target = checked_target(name)
            key = name.lstrip('/').replace('/', '_')
            backup = root / key
            if name not in self.entries:
                if backup.is_symlink() or not backup.is_file() or not target.is_file():
                    raise ValueError('旧版主题备份不完整，已保留恢复记录')
                original = self.digests.sha256(backup)
                current = self.digests.sha256(target)
                value = target.stat()
                signature = f'{value.st_size}:{value.st_mtime_ns // 1000000000}:'
                applied = current if old.get(key, '').startswith(signature) else None
                if current != original and applied is None:
                    raise ValueError('旧版主题字体来源未确认，已保留备份')
                saved = hashlib.sha256(name.encode()).hexdigest() + '.font'
                self.copy(backup, STATE / saved, original)
                self.entries[name] = dict(backup=saved, original=original, applied=applied,
                                          metadata=metadata(target), legacy=True)
                self.save()
            consumed.add(key)
        if consumed:
            atomic(manifest, b'')
            atomic(signatures, ''.join(line + '\n' for line in records
                                      if line.split('=', 1)[0] not in consumed).encode())

    def source(self):
        for root in (self.module / 'system/fonts', self.module / '.ziyu-payload/system/fonts'):
            for name in ('SysFont-Regular.ttf', '.ziyu-font-store/regular.font',
                         'SysSans-Hans-Regular.ttf', 'Roboto-Regular.ttf'):
                path = root / name
                if path.is_file() and path.stat().st_size:
                    return path
        raise ValueError('没有已应用的字体，请先应用并重启')

    def apply(self):
        source = self.source()
        source_before = identity(source)
        source_hash = self.digests.sha256(source)
        replaced = preserved = failed = 0
        reasons = []
        roots = DIRS.get(self.rom, DIRS['hyperos'])
        targets = sorted({path for directory in roots if Path(directory).is_dir()
                          for path in Path(directory).iterdir() if path.suffix.lower() in SUFFIXES})
        if not targets:
            raise ValueError('没有找到可替换的主题字体')
        if len(targets) > 256:
            raise ValueError('主题字体过多，已保留原文件')
        for path in targets:
            try:
                try:
                    target = checked_target(path)
                except ValueError as error:
                    preserved += 1
                    reasons.append(f'{path.name}: {error}')
                    continue
                before = identity(target)
                current = self.digests.sha256(target)
                entry = self.entries.get(str(target))
                original = STATE / entry['backup'] if entry else target
                if entry and (current not in (entry['original'], entry.get('applied'), entry.get('previous_applied'))
                              or self.digests.sha256(original) != entry['original']):
                    raise OSError('主题字体已被其他应用修改，已保留备份')
                try:
                    self.guard.check(source, original)
                except (ValueError, KeyError) as error:
                    preserved += 1
                    reasons.append(f'{path.name}: {error}')
                    continue
                if identity(source) != source_before or identity(target) != before:
                    raise OSError('主题字体或源字体已变化，请重试')
                if not entry:
                    name = hashlib.sha256(str(target).encode()).hexdigest() + '.font'
                    self.copy(target, STATE / name, current)
                    entry = dict(backup=name, original=current, metadata=metadata(target))
                    self.entries[str(target)] = entry
                entry['previous_applied'] = current
                entry['applied'] = source_hash
                self.save()  # Journal precedes the replace, even on repeated applications.
                if current != source_hash:
                    self.copy(source, target, source_hash, entry['metadata'], before)
                entry.pop('previous_applied', None)
                self.save()
                replaced += 1
            except (OSError, ValueError, KeyError) as error:
                failed += 1
                reasons.append(f'{path.name}: {error}')
        unsupported = not replaced and not failed
        if unsupported:
            failed = 1
        message = f'已更新 {replaced} 项主题字体'
        if preserved:
            message += f'，{preserved} 项保留原字体'
        if failed:
            message += '；部分未完成，请重试或导出日志'
        if unsupported:
            message = '这款字体暂不能完整替换主题字体，原文件已保留'
        return dict(replaced=replaced, preserved=preserved, failed=failed,
                    message=message, reasons=reasons)

    def restore(self):
        restored = failed = 0
        reasons = []
        for name, entry in list(self.entries.items()):
            try:
                target = checked_target(name)
                before = identity(target)
                current = self.digests.sha256(target)
                backup = STATE / entry['backup']
                if backup.is_symlink() or self.digests.sha256(backup) != entry['original']:
                    raise ValueError('原件备份核验失败')
                if current != entry['original']:
                    if current not in (entry.get('applied'), entry.get('previous_applied')):
                        raise ValueError('主题字体已被其他应用修改，保留现场与备份')
                    self.copy(backup, target, entry['original'], entry['metadata'], before)
                del self.entries[name]
                try:
                    self.save()
                except Exception:
                    self.entries[name] = entry
                    raise
                backup.unlink(missing_ok=True)
                restored += 1
            except (OSError, ValueError, KeyError) as error:
                failed += 1
                reasons.append(f'{Path(name).name}: {error}')
        message = f'已还原 {restored} 项主题字体' if restored else '没有需要还原的主题字体'
        if failed:
            message = '部分主题字体未还原，请重试；备份已保留'
        if not self.entries:
            self.save()
        return dict(restored=restored, failed=failed, message=message, reasons=reasons)


@contextmanager
def locked():
    secure_directory(STATE.parent)
    secure_directory(STATE)
    fd = os.open(STATE / 'operation.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'r+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('主题字体正在处理，请稍后重试') from None
        yield


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    parser.add_argument('rom')
    parser.add_argument('action', choices=('status', 'get', 'migrate', 'set', 'apply', 'restore', 'restore-disable', 'sync'))
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    store = ThemeStore(args.module, args.rom)
    try:
        if os.geteuid() != 0:
            raise ValueError('请先授权 Root')
        with locked():
            store.load()
            if args.action in ('status', 'get', 'migrate'):
                data = dict(enabled=store.enabled(), backed_up=len(store.entries), rom=args.rom)
            else:
                restore = args.action in ('restore', 'restore-disable') or (
                    args.action == 'set' and args.value not in ('on', '1', 'true'))
                if args.action == 'sync':
                    active = args.module / 'config/active_font.conf'
                    restore = not store.enabled() or not active.is_file() or active.read_text().strip() == 'default'
                data = store.restore() if restore else store.apply()
                if not data['failed']:
                    if restore or args.action == 'set':
                        store.set_enabled(not restore)
                data['enabled'] = store.enabled()
                data['pending'] = len(store.entries)
            failed = data.get('failed', 0)
            status = 'partial' if failed and (data.get('replaced', 0) or data.get('restored', 0)) else 'error' if failed else 'ok'
            print(json.dumps({'status': status, 'message': data.get('message', ''), 'data': data}, ensure_ascii=False))
            for reason in data.get('reasons', []):
                print(reason, file=sys.stderr)
            return int(bool(failed))
    except Exception as error:
        try:
            enabled = store.enabled()
        except OSError:
            enabled = None
        print(json.dumps({'status': 'error', 'message': str(error),
                          'data': {'enabled': enabled, 'pending': len(store.entries)}}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
