#!/usr/bin/env python3
"""Restart-bound, verified copy of portable user data; keep the source intact."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time

FOLDERS = ('fonts', 'import', 'backup', 'backups', 'reports')


def props(path):
    try:
        return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if '=' in line)
    except FileNotFoundError:
        return {}


def atomic(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + f'.tmp.{os.getpid()}')
    try:
        with tmp.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def active(module):
    value = props(module / 'config/user-storage.conf').get('path')
    if value:
        return Path(value)
    if Path('/sdcard/LuoShu/fonts').is_dir():
        return Path('/sdcard/LuoShu')
    return Path('/sdcard/ziyu')


def destination(raw):
    path = Path(raw)
    if not path.is_absolute() or any(c in raw for c in '\r\n\0|') or len(raw.encode()) > 500 or '..' in path.parts:
        raise ValueError('请输入不含换行或上级跳转的绝对目录路径')
    resolved = path.resolve()
    prefixes = ('/storage/emulated/0/', '/data/adb/ziyu-data/')
    external = len(resolved.parts) >= 4 and resolved.parts[1] == 'storage' and resolved.parts[2] not in {'emulated', 'self'}
    if not (external or any(str(resolved).startswith(prefix) for prefix in prefixes)) or str(resolved) in {'/storage/emulated/0', '/storage/emulated', '/storage/self'}:
        raise ValueError('请选择共享存储中的专用子目录，或 /data/adb/ziyu-data 下的子目录')
    if any(part in {'Android', 'modules', 'modules_update'} for part in resolved.parts):
        raise ValueError('用户字体目录不能放在 Android 应用数据或 Root 模块目录中')
    if path.is_symlink():
        raise ValueError('目标目录不能是符号链接')
    if path.exists() and any(path.iterdir()) and not (path / '.ziyu-user-storage').is_file():
        raise ValueError('请选择空目录，或以前由 ziyu 使用的用户目录')
    return resolved


def inventory(root):
    entries = {}
    for folder in FOLDERS:
        directory = root / folder
        if directory.is_symlink():
            raise ValueError(f'用户目录包含符号链接，未迁移：{folder}')
        if not directory.exists():
            continue
        for path in directory.rglob('*'):
            if path.is_symlink():
                raise ValueError(f'用户数据包含符号链接，未迁移：{path.name}')
            if path.is_file():
                st = path.stat()
                entries[path.relative_to(root).as_posix()] = (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)
    return entries


def identity(path):
    st = path.stat()
    return st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def boot_id():
    return Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def apply(module):
    pending_file = module / 'config/user-storage-next.conf'
    pending = props(pending_file)
    source = active(module)
    if not pending and source == Path('/sdcard/LuoShu'):
        pending = {'path': '/sdcard/ziyu'}
    if not pending:
        return {'activePath': str(source), 'pendingPath': ''}
    for task in (module / 'config').glob('*_task.conf'):
        state = props(task)
        if state.get('state') in {'running', 'queued'} and state.get('bootId') == boot_id():
            raise ValueError('字体任务尚未结束，迁移已保留到下次启动')
    if pending.get('bootId') == boot_id():
        return {'activePath': str(source), 'pendingPath': pending['path'], 'rebootRequired': True}
    target = destination(pending['path'])
    if source.resolve() == target.resolve():
        pending_file.unlink(missing_ok=True)
        return {'activePath': str(source), 'pendingPath': ''}
    if source.resolve() in target.resolve().parents or target.resolve() in source.resolve().parents:
        raise ValueError('新旧目录不能互相包含')
    before = inventory(source)
    target.mkdir(parents=True, exist_ok=True)
    owner = target / '.ziyu-user-storage'
    if owner.exists() and owner.read_text(encoding='utf-8').strip() != 'ziyu-user-storage-v1':
        raise ValueError('目标目录的所有权标记不匹配')
    if not owner.exists():
        owner.write_text('ziyu-user-storage-v1\n', encoding='utf-8', newline='\n')
    for folder in FOLDERS:
        child = target / folder
        if child.is_symlink() or (child.exists() and not child.is_dir()):
            raise ValueError(f'目标目录条目无效：{folder}')
    verified_outputs = {}
    for name, expected in before.items():
        original, output = source / name, target / name
        output.parent.mkdir(parents=True, exist_ok=True)
        if any(part.is_symlink() for part in [output, *output.parents] if part != target.parent):
            # The returned destination is resolved, so no /sdcard alias is involved.
            raise ValueError(f'目标条目包含符号链接：{name}')
        if output.exists():
            if not output.is_file() or output.is_symlink() or digest(original) != digest(output):
                raise ValueError(f'目标已有不同内容，原目录保持不变：{name}')
            verified_outputs[name] = identity(output)
            continue
        if shutil.disk_usage(target).free < expected[2] + 32 * 1024 * 1024:
            raise ValueError('目标可用空间不足，原目录保持不变')
        temporary = output.with_name(output.name + f'.ziyu-copy.{os.getpid()}')
        value = hashlib.sha256()
        try:
            with original.open('rb') as src, temporary.open('xb') as dst:
                for chunk in iter(lambda: src.read(1024 * 1024), b''):
                    dst.write(chunk)
                    value.update(chunk)
                dst.flush()
                os.fsync(dst.fileno())
            if digest(temporary) != value.hexdigest():
                raise ValueError(f'复制校验未通过：{name}')
            os.chmod(temporary, 0o644)
            os.replace(temporary, output)
            verified_outputs[name] = identity(output)
        finally:
            temporary.unlink(missing_ok=True)
    if inventory(source) != before:
        raise ValueError('迁移期间源文件变化，原目录继续生效，下次启动重试')
    if any(identity(target / name) != expected for name, expected in verified_outputs.items()):
        raise ValueError('迁移期间目标文件变化，原目录继续生效')
    for folder in FOLDERS:
        (target / folder).mkdir(exist_ok=True)
    atomic(module / 'config/user-storage.conf', f'schema=ziyu-user-storage-v1\npath={target}\n')
    pending_file.unlink(missing_ok=True)
    return {'activePath': str(target), 'pendingPath': '', 'copiedFiles': len(before), 'sourceRetained': True}


def migrating(module):
    marker = props(module / 'config/user-storage-migration.conf')
    if marker.get('bootId') != boot_id():
        return False
    try:
        os.kill(int(marker.get('pid', '0')), 0)
        return True
    except (ValueError, ProcessLookupError, PermissionError):
        return False


def main():
    module, command = Path(sys.argv[1]), sys.argv[2]
    next_file = module / 'config/user-storage-next.conf'
    if command == 'dispatch':
        lock = (module / '.user-storage.lock').open('a')
        try:
            fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('用户文件正在迁移，请稍后重试')
        os.set_inheritable(lock.fileno(), True)
        env = dict(os.environ, ZIYU_STORAGE_LOCKED='1', MODDIR=str(module))
        env.pop('LUOSHU_PUBLIC_DIR', None)
        args = sys.argv[3:]
        tool = module / 'common/app_bridge.sh'
        if args and args[0] == '--backup':
            args = args[1:]
            tool = module / 'common/full_backup.sh'
        os.execve('/system/bin/sh', ['sh', str(tool), *args], env)
    elif command == 'request':
        requested = Path(sys.argv[3])
        source = active(module).resolve()
        if requested.is_absolute() and requested.resolve() == source:
            next_file.unlink(missing_ok=True)
            result = {'activePath': str(active(module)), 'pendingPath': '', 'rebootRequired': False}
        else:
            target = destination(sys.argv[3])
            if source in target.parents or target in source.parents:
                raise ValueError('新旧目录不能互相包含')
            atomic(next_file, f'schema=ziyu-user-storage-next-v1\npath={target}\ntime={int(time.time())}\nbootId={boot_id()}\n')
            result = {'activePath': str(active(module)), 'pendingPath': str(target), 'rebootRequired': True}
    elif command == 'apply':
        with (module / '.user-storage.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError('已有用户文件迁移任务')
            marker = module / 'config/user-storage-migration.conf'
            atomic(marker, f'pid={os.getpid()}\nbootId={boot_id()}\n')
            try:
                result = apply(module)
            finally:
                marker.unlink(missing_ok=True)
    elif command == 'status':
        result = {'activePath': str(active(module)), 'pendingPath': props(next_file).get('path', ''), 'migrating': migrating(module)}
    else:
        raise ValueError('未知存储操作')
    print(json.dumps({'status': 'ok', 'data': result}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
