#!/usr/bin/env python3
"""Portable user-font backup, independent of published system/bin mounts."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

CONFIGS = ('font_mix.conf', 'axes_mix.conf')
EXTENSIONS = {'.ttf', '.otf', '.ttc', '.woff', '.woff2', '.conf', '.json'}
PACKAGES = {'io.github.shishui611_art.ziyu', 'io.github.shishui611_art.ziyu.debug',
            'io.github.xgl34222220.ziyu', 'io.github.xgl34222220.ziyu.debug'}


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def identity(path):
    st = path.stat()
    return st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns


def copy_checked(source, target):
    if source.is_symlink() or not source.is_file():
        raise ValueError(f'备份来源不是普通文件：{source.name}')
    before = identity(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_name(target.name + f'.ziyu-backup.{os.getpid()}')
    try:
        shutil.copyfile(source, temp)
        if digest(source) != digest(temp) or identity(source) != before:
            raise ValueError(f'备份复制或来源变化校验失败：{source.name}')
        os.chmod(temp, 0o644)
        os.replace(temp, target)
    finally:
        temp.unlink(missing_ok=True)


def trusted_stage(raw):
    path = Path(raw)
    if not path.is_absolute() or '..' in path.parts or path.is_symlink():
        raise ValueError('备份暂存路径无效')
    resolved = path.resolve(strict=True)
    parts = resolved.parts
    # /data/data resolves to /data/user/0; package id comes from the current App.
    if len(parts) < 8 or parts[1:3] != ('data', 'user') or not parts[3].isdigit() or parts[4] not in PACKAGES or parts[5:7] != ('cache', 'full_backup'):
        raise ValueError('备份暂存目录不属于字域 App')
    if resolved.stat().st_uid < 10000:
        raise ValueError('备份暂存目录的 App 所有权不匹配')
    return resolved


def files(directory):
    if not directory.exists():
        return []
    result = []
    for path in directory.iterdir():
        if path.suffix.lower() in EXTENSIONS:
            if path.is_symlink() or not path.is_file():
                raise ValueError(f'字体库条目无效：{path.name}')
            result.append(path)
    return sorted(result)


def main():
    module, public, command, raw = sys.argv[1:5]
    module, fonts, stage = Path(module), Path(public) / 'fonts', trusted_stage(raw)
    uid, gid = stage.stat().st_uid, stage.stat().st_gid
    if command == 'export-root':
        target = stage / 'root'
        if target.exists():
            raise ValueError('备份暂存区已存在，请重新发起导出')
        target.mkdir()
        source_files = files(fonts)
        before = {str(p): identity(p) for p in source_files}
        for source in source_files:
            copy_checked(source, target / 'fonts' / source.name)
        (target / 'fonts').mkdir(exist_ok=True)
        for name in CONFIGS:
            source = module / 'config' / name
            if source.is_file():
                copy_checked(source, target / 'config' / name)
        if before != {str(p): identity(p) for p in files(fonts)}:
            raise ValueError('备份期间字体库发生变化，请重试；未生成不完整成功结果')
        for path in [target, *target.rglob('*')]:
            os.chown(path, uid, gid)
            os.chmod(path, 0o755 if path.is_dir() else 0o644)
        if Path('/system/bin/restorecon').is_file():
            subprocess.run(['/system/bin/restorecon', '-RF', str(target)], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        result = {'fontFiles': len(source_files), 'fontBytes': sum(p.stat().st_size for p in source_files)}
    elif command == 'restore-root':
        entries = [(p, fonts / p.name) for p in files(stage / 'root/fonts')]
        for name in CONFIGS:
            p = stage / 'root/config' / name
            if p.is_file():
                entries.append((p, module / 'config' / name))
        changes = []
        work = Path(tempfile.mkdtemp(prefix='.ziyu-restore-', dir=module))
        retain = False
        try:
            try:
                for index, (source, target) in enumerate(entries):
                    previous = None
                    if target.exists():
                        previous = work / str(index)
                        copy_checked(target, previous)
                    changes.append((target, previous))
                    copy_checked(source, target)
            except Exception:
                failures = []
                for target, previous in reversed(changes):
                    try:
                        if previous is None:
                            target.unlink(missing_ok=True)
                        else:
                            copy_checked(previous, target)
                    except Exception:
                        failures.append(str(target))
                if failures:
                    # Retain the only rollback copies when automatic restoration fails.
                    retain = True
                    (work / 'rollback-index.json').write_text(json.dumps(
                        [{'target': str(target), 'previous': str(previous) if previous else None}
                         for target, previous in changes], ensure_ascii=False), encoding='utf-8')
                    raise ValueError(f'恢复及回滚未全部确认，回滚文件已保留：{work.name}')
                raise
        finally:
            if not retain:
                shutil.rmtree(work)
        for name in ('native_font_index.json', 'native_font_index.key'):
            (module / 'config' / name).unlink(missing_ok=True)
        result = {'restoredFiles': len(entries), 'message': '字体与便携配置已恢复，请重新应用并重启'}
    else:
        raise ValueError('未知备份操作')
    print(json.dumps({'status': 'ok', **result}, ensure_ascii=False, separators=(',', ':')))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(1)
