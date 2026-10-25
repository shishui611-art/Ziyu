#!/usr/bin/env python3
"""Move only display preferences; boot/mount/rollback state stays private."""
import json
import os
from pathlib import Path
import sys
import tempfile

from user_storage import atomic, props

SCHEMA = 'ziyu-display-config-storage-v1'
OWNER = 'ziyu-display-config-v1'
NAME = 'display-typography.conf'


def portable_atomic(path, text):
    # Shared-storage file modes may be fixed by FUSE. mkstemp requests 0600;
    # there is no need to reject a successful write solely over chmod support.
    fd, name = tempfile.mkstemp(prefix='.ziyu-config-', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def identity(path):
    value = path.stat()
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def directory(raw, module):
    path = Path(raw)
    if (not path.is_absolute() or '..' in path.parts or len(raw.encode('utf-8')) > 500
            or any(ord(c) < 32 or ord(c) == 127 for c in raw) or '|' in raw):
        raise ValueError('请输入不含上级跳转或控制字符的绝对目录')
    resolved = path.resolve()
    if resolved == (module / 'config').resolve():
        return resolved
    external = len(resolved.parts) >= 4 and resolved.parts[1] == 'storage' and resolved.parts[2] not in {'emulated', 'self'}
    if not (external or str(resolved).startswith(('/storage/emulated/0/', '/data/adb/ziyu-data/'))):
        raise ValueError('请选择共享存储的专用子目录，或 /data/adb/ziyu-data 下的子目录')
    if any(part in {'Android', 'modules', 'modules_update'} for part in resolved.parts) or path.is_symlink():
        raise ValueError('请勿使用应用数据、其他模块目录或符号链接')
    marker = resolved / '.ziyu-display-config'
    if resolved.exists() and (not resolved.is_dir() or
            any(resolved.iterdir()) and (marker.is_symlink() or not marker.is_file() or marker.read_text().strip() != OWNER)):
        raise ValueError('请选择空目录，或字域已经使用的显示配置目录')
    return resolved


def read_snapshot(path):
    try:
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 16 * 1024:
            return None
        before = identity(path)
        payload = path.read_text(encoding='utf-8')
        after = identity(path)
    except (OSError, UnicodeError):
        # An unavailable card or malformed mirror must not prevent selecting a
        # usable directory. The next status read initializes a missing cache.
        return None
    if after != before:
        raise ValueError('显示配置在读取期间变化，请重试')
    values = dict(line.split('=', 1) for line in payload.splitlines() if '=' in line)
    if values.get('schema') != 'ziyu-display-typography-v1' or values.get('status') != 'ok':
        return None
    try:
        numbers = {key: int(values[key]) for key in ('user', 'sdk', 'scale', 'weight', 'density', 'baseDensity', 'densityMin', 'densityMax')}
        generation = int(values.get('generation', '0'))
    except (ValueError, KeyError):
        return None
    if (numbers['user'] < 0 or numbers['sdk'] < 28 or not 1 <= numbers['scale'] <= 1000
            or not -1000 <= numbers['weight'] <= 1000 or numbers['baseDensity'] <= 0
            or not 0 < numbers['densityMin'] <= numbers['density'] <= numbers['densityMax']
            or not 0 <= generation <= 2147483646):
        return None
    return generation, payload, before


def current(module):
    pointer = module / 'config/display-config-storage.conf'
    try:
        values = props(pointer) if pointer.is_file() and not pointer.is_symlink() and pointer.stat().st_size <= 4096 else {}
    except (OSError, UnicodeError):
        values = {}
    path = values.get('path') if values.get('schema') == SCHEMA else ''
    if path:
        value = Path(path)
        external = len(value.parts) >= 4 and value.parts[1] == 'storage' and value.parts[2] not in {'emulated', 'self'}
        if (value.is_absolute() and '..' not in value.parts and len(path.encode()) <= 500 and '|' not in path
                and not any(ord(c) < 32 or ord(c) == 127 for c in path)
                and (external or path.startswith(('/storage/emulated/0/', '/data/adb/ziyu-data/')))):
            return value
    return module / 'config'


def select_directory(module, raw):
    private = module / 'config'
    old = current(module)
    target = directory(raw, module)
    snapshots = []
    sources = set() if (private / 'display-typography-dirty').exists() else {private / NAME, old / NAME}
    for source in sources:
        snapshot = read_snapshot(source)
        if snapshot is not None:
            snapshots.append((source, snapshot))
    target.mkdir(parents=True, exist_ok=True)
    if target != private.resolve():
        portable_atomic(target / '.ziyu-display-config', OWNER + '\n')
    if snapshots:
        source, (_, payload, before) = max(snapshots, key=lambda entry: entry[1][0])
        output = target / NAME
        if output.is_symlink():
            raise ValueError('目标配置文件不能是符号链接')
        if output.resolve() != source.resolve():
            portable_atomic(output, payload)
            if output.read_text(encoding='utf-8') != payload or identity(source) != before:
                raise ValueError('配置复制校验失败，原保存位置仍生效')
        mirror = private / NAME
        if mirror.resolve() != source.resolve():
            portable_atomic(mirror, payload)
            if mirror.read_text(encoding='utf-8') != payload or identity(source) != before:
                raise ValueError('内部配置镜像校验失败，原保存位置仍生效')
    pointer = private / 'display-config-storage.conf'
    if target == private.resolve():
        pointer.unlink(missing_ok=True)
    else:
        atomic(pointer, f'schema={SCHEMA}\npath={target}\n')
    return {'path': str(target), 'defaultPath': str(private), 'sourceRetained': True,
            'message': '保存位置已切换；内部保留小型镜像，自选目录暂不可用时继续读取本地值。'}


def main():
    module, command = Path(sys.argv[1]), sys.argv[2]
    if command == 'status':
        result = {'path': str(current(module)), 'defaultPath': str(module / 'config')}
    elif command == 'set' and len(sys.argv) == 4:
        result = select_directory(module, sys.argv[3])
    else:
        raise ValueError('未知显示配置目录操作')
    print(json.dumps({'status': 'ok', 'data': result}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
