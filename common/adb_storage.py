#!/usr/bin/env python3
"""GPL-3.0-only. Migrate exact historical Ziyu paths, never other root modules.

Installation records the kernel boot ID. Active old mounts, locks and rollback
records stay available until the first full reboot. No system unmount occurs.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys

from user_storage import atomic, props

ADB = Path('/data/adb')
ROOT = ADB / 'ziyu'
RUNTIME = ('self-mount', 'live-mount', 'private-payload', 'universal-mount',
           'runtime-verify', 'stock-readonly', 'install-stock-scan', 'stock-snapshot-scan')
LOCKS = ('.ziyu-luoshu-stock-rescan.lock', '.ziyu-luoshu-boot-ota-scan.lock')
OWNER = 'schema=ziyu-adb-storage-v1\nowner=ziyu\n'


def exists(path):
    return path.exists() or path.is_symlink()


def secure_directory(path):
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
        raise ValueError(f'拒绝符号链接目录：{path}')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise ValueError(f'目录所有者异常：{path}')


def boot_id():
    return Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def legacy_paths():
    result = [ADB / 'luoshu' / name for name in RUNTIME]
    result += [ADB / 'luoshu-google-font-fallback', ADB / 'ziyu-data']
    result += [parent / name for parent in (ADB, ROOT / 'locks') for name in LOCKS]
    for parent in (ADB, ROOT / 'locks'):
        for path in parent.glob('.ziyu-luoshu-stock-rescan.lock.request.*'):
            if re.fullmatch(r'\.ziyu-luoshu-stock-rescan\.lock\.request\.[0-9a-f-]+(?:\.stale\.[0-9]+)?', path.name):
                result.append(path)
    return [path for path in result if exists(path)]


def busy_paths(paths):
    """One migration-only pass: leave live mounts/loops/worker references intact."""
    prefixes = [str(path) for path in paths]
    busy = set()

    def record(text):
        for prefix in prefixes:
            if prefix in text or prefix.removeprefix('/data') + '/' in text:
                busy.add(prefix)

    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit() or proc.name == str(os.getpid()):
            continue
        for name in ('mountinfo', 'maps', 'cmdline'):
            try:
                record((proc / name).read_text(errors='replace'))
            except OSError:
                pass
    for backing in Path('/sys/block').glob('loop*/loop/backing_file'):
        try:
            record(backing.read_text())
        except OSError:
            pass
    return busy


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def move_preserving(source, target):
    """Atomic rename, or retire a fully verified duplicate; no partial source moves."""
    if source.is_symlink() or any(path.is_symlink() for path in source.rglob('*')):
        raise ValueError(f'旧目录含外部链接，保留原目录：{source}')
    secure_directory(target.parent)
    if not exists(target):
        source.rename(target)
        return
    if target.is_symlink() or not target.is_dir():
        raise ValueError(f'迁移目标异常：{target}')
    before = {}
    for entry in source.rglob('*'):
        destination = target / entry.relative_to(source)
        if destination.is_symlink() or any(part.is_symlink() for part in destination.parents):
            raise ValueError(f'目标包含外部链接：{destination}')
        if entry.is_dir() and destination.is_dir():
            continue
        if (entry.is_file() and destination.is_file()
                and entry.stat().st_size == destination.stat().st_size):
            info = entry.stat()
            before[entry] = (info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
            if digest(entry) == digest(destination):
                continue
        # A pre-existing destination is never incrementally merged by moving
        # source files away: any conflict leaves the entire old store usable.
        raise ValueError(f'新旧内容不同，保留冲突项：{entry}')
    for entry, expected in before.items():
        info = entry.stat()
        if (info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns) != expected:
            raise ValueError('迁移核验期间原文件发生变化')
    shutil.rmtree(source)


def rewrite_user_pointers(module):
    old = '/data/adb/ziyu-data'
    new = '/data/adb/ziyu/user-data'
    for name in ('user-storage.conf', 'user-storage-next.conf', 'display-config-storage.conf'):
        path = module / 'config' / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 4096:
            continue
        payload = path.read_text()
        lines = payload.splitlines()
        changed = False
        for index, line in enumerate(lines):
            if line == 'path=' + old or line.startswith('path=' + old + '/'):
                lines[index] = 'path=' + new + line[len('path=' + old):]
                changed = True
        if changed:
            atomic(path, '\n'.join(lines) + '\n')


def lock_owned(path):
    if not any(path.iterdir()):
        return True
    state = props(path / 'state')
    if state.get('boot_id') == boot_id() and state.get('finished') != '1':
        return False
    return (state.get('task', '').startswith(('ziyu-stock-rescan-', 'ziyu-boot-ota-'))
            and state.get('module') in ('/data/adb/modules/ziyu', '/data/adb/modules_update/ziyu',
                                        '/data/adb/modules/LuoShu', '/data/adb/modules/Ziyu'))


def retire_companions():
    retired = []
    for parent in (ADB / 'modules', ADB / 'modules_update'):
        for name, owner, identity in (('ziyu-font-preload', 'ziyu', 'ziyu-font-preload'),
                                       ('ZiyuFontPreload', 'LuoShu', 'ZiyuFontPreload')):
            path = parent / name
            if not path.is_dir() or path.is_symlink() or path.parent.is_symlink():
                continue
            if any((path / name).is_symlink() for name in ('module.prop', 'ziyu-owner.conf')):
                continue
            meta, receipt = props(path / 'module.prop'), props(path / 'ziyu-owner.conf')
            if meta.get('id') != identity or receipt.get('owner') != owner:
                continue
            if receipt.get('schema') != 'ziyu-font-preload-owner-v1':
                continue
            # Historical companion is native-only. Do not remove an edited module
            # that has acquired scripts or partition payloads.
            allowed = {'module.prop', 'ziyu-owner.conf', 'skip_mount', 'build.json', 'zygisk', 'disable', 'remove'}
            if any(child.name not in allowed for child in path.iterdir()):
                continue
            shutil.rmtree(path)
            retired.append(str(path))
    return retired


def migrate(module, action):
    secure_directory(ROOT)
    marker = ROOT / '.owner.conf'
    if exists(marker) and (marker.is_symlink() or marker.read_text() != OWNER):
        raise ValueError('字域数据目录所有权不匹配，保留现场')
    if not marker.exists():
        atomic(marker, OWNER)
    secure_directory(ROOT / 'locks')
    lock_path = ROOT / 'locks/storage-migration.lock'
    if lock_path.is_symlink():
        raise ValueError('迁移锁不能是符号链接')
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        receipt = module / 'config/adb-storage-migration.conf'
        saved = props(receipt)
        current = boot_id()
        paths = legacy_paths()
        helpers = any((parent / name).exists() for parent in (ADB / 'modules', ADB / 'modules_update')
                      for name in ('ziyu-font-preload', 'ZiyuFontPreload'))
        if action == 'install' and (paths or helpers):
            atomic(receipt, f'schema=ziyu-adb-migration-v1\nstate=pending\ninstallBootId={current}\n')
            atomic(module / 'config/identity-migration-full-reboot.conf',
                   f'bootId={current}\nreason=adb-storage-layout-upgrade\n')
            return {'state': 'pending-full-reboot', 'legacyPaths': [str(path) for path in paths]}
        if saved.get('installBootId') == current and saved.get('state') != 'complete':
            return {'state': 'pending-full-reboot'}
        if saved.get('bootId') == current and saved.get('state') == 'partial':
            return {'state': 'partial', 'reused': True}
        if not paths and saved.get('state') == 'complete' and not helpers:
            return {'state': 'complete', 'reused': True}
        busy = busy_paths(paths) if paths else set()
        moved, removed, retained = [], [], []
        for path in paths:
            try:
                if path.is_symlink() or any(parent.is_symlink() for parent in path.parents) or not path.is_dir() or str(path) in busy:
                    raise ValueError('目录仍被占用或类型异常')
                if path.name == 'luoshu-google-font-fallback':
                    from google_font_fallback_core import validate_journal
                    for child in path.iterdir():
                        if (child.name == 'operation.lock' or re.fullmatch(r'\.journal-[A-Za-z0-9_-]+', child.name)) and child.is_file() and not child.is_symlink():
                            continue
                        match = re.fullmatch(r'user-([0-9]+)\.json', child.name)
                        if not match or child.is_symlink() or child.stat().st_size > 16384:
                            raise ValueError('Google 字体恢复目录含未识别条目')
                        validate_journal(json.loads(child.read_text()), int(match[1]))
                    move_preserving(path, ROOT / 'google-font-fallback')
                    moved.append(str(path))
                elif path.name == 'ziyu-data':
                    # This is user content: rename it intact, never treat it as cache.
                    move_preserving(path, ROOT / 'user-data')
                    rewrite_user_pointers(module)
                    moved.append(str(path))
                elif path.parent == ADB / 'luoshu' and path.name in RUNTIME:
                    shutil.rmtree(path)
                    removed.append(str(path))
                elif path.parent in (ADB, ROOT / 'locks') and path.name in LOCKS:
                    if not lock_owned(path):
                        raise ValueError('锁记录不属于字域')
                    shutil.rmtree(path)
                    removed.append(str(path))
                elif path.name.startswith('.ziyu-luoshu-stock-rescan.lock.request.'):
                    if any(child.name not in ('owner', 'reap') for child in path.iterdir()):
                        raise ValueError('请求锁包含未识别条目')
                    shutil.rmtree(path)
                    removed.append(str(path))
            except (OSError, ValueError) as error:
                retained.append({'path': str(path), 'reason': str(error)})
        if (ROOT / 'user-data').is_dir() and not exists(ADB / 'ziyu-data'):
            rewrite_user_pointers(module)  # Resume after rename/config-write interruption.
        old_root = ADB / 'luoshu'
        if old_root.is_dir() and not old_root.is_symlink():
            try:
                old_root.rmdir()  # Unknown children are retained, never blanket deleted.
            except OSError:
                retained.append({'path': str(old_root), 'reason': '目录非空，保留未识别或仍在使用的条目'})
        retired = retire_companions()
        state = 'complete' if not retained else 'partial'
        atomic(receipt, f'schema=ziyu-adb-migration-v1\nstate={state}\nbootId={current}\n')
        result = {'state': state, 'moved': moved, 'removed': removed, 'retiredModules': retired, 'retained': retained}
        atomic(ROOT / 'migration.json', json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        return result


if __name__ == '__main__':
    try:
        module = Path(sys.argv[1])
        if module.is_symlink() or module.resolve() not in (ADB / 'modules/ziyu', ADB / 'modules_update/ziyu'):
            raise ValueError('模块路径不匹配')
        if sys.argv[2] not in ('install', 'boot'):
            raise ValueError('未知迁移阶段')
        print(json.dumps(migrate(module, sys.argv[2]), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'state': 'error', 'message': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
