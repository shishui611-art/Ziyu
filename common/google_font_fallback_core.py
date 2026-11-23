#!/usr/bin/env python3
"""Explicit, reversible GMS FontsProvider + UpdateSchedulerService + cache-seal guard (GPL-3.0).

Concept: MrCarb0n/killgmsfont, extended with read-only empty cache sealing and boot re-enforcement.
Never auto-enables without user opt-in; never disables GMS as a whole; never deletes account data.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import json
from legacy_identity import schema as identity_schema
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Any

GMS = 'com.google.android.gms'
PROVIDER = GMS + '.fonts.provider.FontsProvider'
COMPONENT = GMS + '/' + PROVIDER
UPDATER = GMS + '.fonts.update.UpdateSchedulerService'
UPDATER_COMPONENT = GMS + '/' + UPDATER
CACHE_PACKAGES = ('com.google.android.gms', 'com.android.vending')
STORE = Path('/data/adb/ziyu/google-font-fallback')
MODULE = Path('/data/adb/modules/ziyu')
SCHEMA = 'ziyu-google-font-fallback-v1'


class FallbackError(RuntimeError):
    pass


def parse_snapshot(text: str, user: int) -> dict[str, Any]:
    """Read live dumpsys, never package-restrictions.xml (which can lag).

    Restrict overrides to the installed package's requested User block; do not
    read the older ROM copy in Hidden system packages or another user's flags.
    Unknown/malformed output is an error, never assumed to be 'default'.
    """
    if not 0 <= user <= 21474 or any(x in text.lower() for x in (
            'dump timed out', 'dump timeout', 'permission denial', 'can\'t find service')):
        raise FallbackError('无法可靠读取包管理器状态；未修改组件。')
    sections = re.findall(r'(?ms)^Packages:\s*\n(.*?)(?=^\S|\Z)', text)
    if len(sections) != 1:
        raise FallbackError('未找到唯一的已安装软件包区域；未猜测组件状态。')
    packages = re.findall(r'(?ms)^  Package \[' + re.escape(GMS)
                          + r'\][^\n]*\n(.*?)(?=^  Package \[|\Z)', sections[0])
    if len(packages) != 1:
        raise FallbackError('未找到唯一的 Google Play 服务软件包。')
    package = packages[0]
    appid = re.search(r'^\s+(?:appId|userId)=(\d+)\b', package, re.M)
    version = re.search(r'\bversionCode=(\d+)\b', package)
    users = list(re.finditer(r'(?m)^( +)User (\d+):([^\n]*)$', package))
    matches = [(i, m) for i, m in enumerate(users) if int(m[2]) == user]
    if not appid or not version or len(matches) != 1:
        raise FallbackError('GMS 用户、版本或安装身份不完整；未修改组件。')
    i, entry = matches[0]
    if not re.search(r'\binstalled=true\b', entry[3]):
        raise FallbackError('此用户未安装 Google Play 服务。')
    app_enabled = re.search(r'\benabled=(\d+)\b', entry[3])
    if not app_enabled:
        raise FallbackError('GMS 整包启用状态未知。')
    part = package[entry.end(): users[i + 1].start() if i + 1 < len(users) else len(package)]
    first = re.search(r'^\s+firstInstallTime=([^\n]+)', part, re.M)
    if first is None:  # Older Android prints a package-wide install time.
        first = re.search(r'^\s+firstInstallTime=([^\n]+)', package[:users[0].start()], re.M)
    if first is None:
        raise FallbackError('当前用户的 GMS 安装身份不完整；未修改组件。')
    # Nested user components have greater indentation than the User header.
    user_indent = len(entry[1])
    state_value = 0
    updater_state = 0
    seen = set()
    updater_seen = set()
    component_list = None
    list_indent = 0
    for line in part.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        indent = len(line) - len(line.lstrip(' '))
        if indent <= user_indent:
            break
        if ('enabledComponents' in stripped or 'disabledComponents' in stripped) and stripped not in ('enabledComponents:', 'disabledComponents:'):
            raise FallbackError('组件列表格式未知；未猜测原始状态。')
        if stripped in ('enabledComponents:', 'disabledComponents:'):
            component_list = 1 if stripped == 'enabledComponents:' else 2
            list_indent = indent
            continue
        if component_list is not None and indent <= list_indent:
            component_list = None
        if component_list is not None:
            name = stripped
            if '/' in name:
                pkg, name = name.split('/', 1)
                if pkg != GMS:
                    continue
            if name.startswith('.'):
                name = GMS + name
            if name == PROVIDER:
                seen.add(component_list)
                state_value = component_list
            elif name == UPDATER:
                updater_seen.add(component_list)
                updater_state = component_list
    if len(seen) > 1:
        raise FallbackError('字体组件同时出现在启用和停用列表；拒绝修改。')
    if len(updater_seen) > 1:
        updater_state = 0
    # Require evidence from live registered provider metadata, not merely its
    # name in a stale disabled-component override left after a GMS update.
    prefix = text.split('Packages:', 1)[0]
    declared = bool(re.search(re.escape(GMS) + r'/(?:'
                              + re.escape(PROVIDER) + r'|\.fonts\.provider\.FontsProvider)'
                              + r'(?=[\s}\]])', prefix))
    return {'user': user, 'appId': int(appid[1]), 'versionCode': int(version[1]),
            'firstInstallTime': first[1].strip(), 'packageState': int(app_enabled[1]),
            'component': COMPONENT, 'componentState': state_value, 'declared': declared,
            'updaterComponent': UPDATER_COMPONENT, 'updaterState': updater_state}


def _cache_targets_for_user(user: int) -> list[Path]:
    if not isinstance(user, int) or not 0 <= user <= 21474:
        return []
    targets: list[Path] = []
    for base in (f'/data/user/{user}', f'/data/user_de/{user}'):
        for pkg in CACHE_PACKAGES:
            targets.append(Path(base) / pkg / 'files' / 'fonts')
    return targets


def _is_safe_real_dir(path: Path) -> bool:
    try:
        info = path.lstat()
    except OSError:
        return False
    return stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode)


def _purge_directory_contents(directory: Path) -> int:
    """Safely remove entries inside a verified font cache directory without following symlinks."""
    if not _is_safe_real_dir(directory):
        return 0
    removed = 0
    stack: list[tuple[Path, bool]] = [(directory, False)]
    while stack:
        current, visited = stack.pop()
        if visited:
            if current != directory:
                try:
                    current.rmdir()
                    removed += 1
                except OSError:
                    pass
            continue
        try:
            entries = list(os.scandir(current))
        except OSError:
            continue
        if current != directory:
            stack.append((current, True))
        for entry in entries:
            child = Path(entry.path)
            try:
                if entry.is_dir(follow_symlinks=False):
                    stack.append((child, False))
                else:
                    child.unlink(missing_ok=True)
                    removed += 1
            except OSError:
                continue
    return removed


def _same_inode(a: Path, b: Path) -> bool:
    try:
        sa = os.stat(a)
        sb = os.stat(b)
    except OSError:
        return False
    return sa.st_dev == sb.st_dev and sa.st_ino == sb.st_ino


def _related_mount_namespace_pids() -> list[int]:
    """Return representative PIDs for PID 1 plus distinct GMS/Play mount namespaces."""
    proc = Path('/proc')
    seen_ns: set[str] = set()
    pids: list[int] = []
    try:
        init_ns = os.readlink('/proc/1/ns/mnt')
        seen_ns.add(init_ns)
        pids.append(1)
    except OSError:
        pass
    try:
        entries = list(os.scandir(proc))
    except OSError:
        return pids
    for entry in entries:
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        if pid == 1:
            continue
        try:
            raw = (proc / entry.name / 'cmdline').read_bytes()
            name = raw.split(b'\0', 1)[0].decode('ascii', 'ignore')
            if not (name.startswith('com.google.android.gms') or name.startswith('com.android.vending')):
                continue
            ns = os.readlink(f'/proc/{pid}/ns/mnt')
            if ns not in seen_ns:
                seen_ns.add(ns)
                pids.append(pid)
        except OSError:
            continue
    return pids


class Android:
    def run(self, *args: str) -> str:
        try:
            completed = subprocess.run(list(args), text=True, encoding='utf-8',
                                       errors='replace', capture_output=True, timeout=12)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise FallbackError('Android 命令不可用或超时：' + args[0]) from error
        if completed.returncode != 0:
            raise FallbackError('Android 命令失败：' + ' '.join(args[:2]))
        if 'exception' in completed.stdout.lower() or 'error:' in completed.stdout.lower():
            raise FallbackError('Android 拒绝操作；未把错误输出当作成功。')
        return completed.stdout

    def current_user(self) -> int:
        value = self.run('/system/bin/am', 'get-current-user').strip()
        if not value.isdigit() or not 0 <= int(value) <= 21474:
            raise FallbackError('无法确定当前 Android 用户；未默认修改用户 0。')
        return int(value)

    def snapshot(self, user: int) -> dict[str, Any]:
        return parse_snapshot(self.run('/system/bin/dumpsys', '-t', '10', 'package', GMS), user)

    def change(self, user: int, state_value: int) -> None:
        # Never accept a caller-supplied package/component or a shell fragment.
        command = {0: 'default-state', 1: 'enable', 2: 'disable'}[state_value]
        self.run('/system/bin/pm', command, '--user', str(user), COMPONENT)

    def change_updater(self, user: int, state_value: int) -> bool:
        """Disable/restore GMS UpdateSchedulerService; ignore cleanly if absent on this GMS build."""
        command = {0: 'default-state', 1: 'enable', 2: 'disable'}[state_value]
        try:
            completed = subprocess.run(
                ['/system/bin/pm', command, '--user', str(user), UPDATER_COMPONENT],
                text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=12,
            )
        except (OSError, subprocess.TimeoutExpired):
            return False
        if completed.returncode != 0:
            return False
        combined = (completed.stdout + '\n' + completed.stderr).lower()
        if 'exception' in combined or 'error:' in combined or 'does not exist' in combined:
            return False
        return True

    def _empty_seal_dir(self) -> Path | None:
        seal = STORE / 'empty-ro'
        try:
            if seal.is_symlink():
                return None
            seal.mkdir(mode=0o555, parents=True, exist_ok=True)
            info = seal.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid():
                return None
            # Keep the mount source strictly empty and read-only.
            _purge_directory_contents(seal)
            os.chmod(seal, 0o555)
            return seal
        except OSError:
            return None

    def _ns_bind_ro(self, pid: int, source: Path, target: Path) -> bool:
        proc_target = Path(f'/proc/{pid}/root{target}')
        if _same_inode(source, proc_target):
            return True
        cmd_prefix = ['nsenter', '-t', str(pid), '-m', '--'] if pid != os.getpid() else []
        try:
            r1 = subprocess.run(
                cmd_prefix + ['mount', '--bind', str(source), str(target)],
                capture_output=True, text=True, timeout=8,
            )
            if r1.returncode != 0:
                r2 = subprocess.run(
                    cmd_prefix + ['mount', '-o', 'bind', str(source), str(target)],
                    capture_output=True, text=True, timeout=8,
                )
                if r2.returncode != 0:
                    return False
            subprocess.run(
                cmd_prefix + ['mount', '-o', 'remount,bind,ro', str(target)],
                capture_output=True, text=True, timeout=8,
            )
            subprocess.run(
                cmd_prefix + ['mount', '-o', 'bind,remount,ro', str(target)],
                capture_output=True, text=True, timeout=8,
            )
            return _same_inode(source, proc_target)
        except (OSError, subprocess.TimeoutExpired):
            return False

    def _ns_unmount(self, pid: int, source: Path, target: Path) -> bool:
        proc_target = Path(f'/proc/{pid}/root{target}')
        if not _same_inode(source, proc_target):
            return True
        cmd_prefix = ['nsenter', '-t', str(pid), '-m', '--'] if pid != os.getpid() else []
        try:
            for _ in range(8):
                if not _same_inode(source, proc_target):
                    return True
                r = subprocess.run(cmd_prefix + ['umount', str(target)], capture_output=True, timeout=8)
                if r.returncode != 0:
                    subprocess.run(cmd_prefix + ['umount', '-l', str(target)], capture_output=True, timeout=8)
                    break
            return not _same_inode(source, proc_target)
        except (OSError, subprocess.TimeoutExpired):
            return False

    def seal_font_caches(self, user: int) -> dict[str, Any]:
        """Purge GMS/Play downloaded font caches for this user and bind-mount an empty RO seal."""
        purged = 0
        sealed: list[str] = []
        seal = self._empty_seal_dir()
        pids = _related_mount_namespace_pids()
        for target in _cache_targets_for_user(user):
            parent = target.parent
            if not _is_safe_real_dir(parent):
                continue
            if target.is_symlink():
                continue
            already = seal is not None and _same_inode(seal, target)
            if not already:
                if target.exists():
                    if not _is_safe_real_dir(target):
                        continue
                    purged += _purge_directory_contents(target)
                else:
                    try:
                        pstat = parent.lstat()
                        target.mkdir(mode=0o700, exist_ok=True)
                        os.chown(target, pstat.st_uid, pstat.st_gid)
                        subprocess.run(['chcon', '--reference=' + str(parent), str(target)],
                                       capture_output=True, timeout=5)
                    except OSError:
                        continue
            if seal is not None:
                mounted_any = False
                for pid in pids:
                    if self._ns_bind_ro(pid, seal, target):
                        mounted_any = True
                if mounted_any:
                    sealed.append(str(target))
        mounts_file = STORE / f'user-{user}.mounts'
        try:
            if sealed:
                mounts_file.write_text('\n'.join(sealed) + '\n', encoding='utf-8')
                os.chmod(mounts_file, 0o600)
            else:
                mounts_file.unlink(missing_ok=True)
        except OSError:
            pass
        return {'purged': purged, 'sealed': sealed}

    def unseal_font_caches(self, user: int) -> dict[str, Any]:
        """Remove Ziyu-owned read-only empty cache binds for this user."""
        seal = STORE / 'empty-ro'
        unsealed: list[str] = []
        if not _is_safe_real_dir(seal):
            return {'unsealed': unsealed}
        targets = set(_cache_targets_for_user(user))
        mounts_file = STORE / f'user-{user}.mounts'
        if mounts_file.is_file() and not mounts_file.is_symlink():
            for line in mounts_file.read_text(encoding='utf-8', errors='ignore').splitlines():
                p = Path(line.strip())
                if p in targets:
                    targets.add(p)
        pids = _related_mount_namespace_pids()
        for target in sorted(targets):
            for pid in pids:
                if _same_inode(seal, Path(f'/proc/{pid}/root{target}')):
                    if self._ns_unmount(pid, seal, target) and str(target) not in unsealed:
                        unsealed.append(str(target))
        try:
            mounts_file.unlink(missing_ok=True)
        except OSError:
            pass
        return {'unsealed': unsealed}

    def sealed_cache_count(self, user: int) -> int:
        seal = STORE / 'empty-ro'
        if not _is_safe_real_dir(seal):
            return 0
        count = 0
        for target in _cache_targets_for_user(user):
            if _same_inode(seal, target):
                count += 1
        return count


def same_install(saved: dict, current: dict) -> bool:
    return all(saved[key] == current[key] for key in ('user', 'appId', 'firstInstallTime'))


def validate_journal(data: dict, user: int) -> None:
    if (not isinstance(data, dict) or identity_schema(data.get('schema')) != SCHEMA
            or data.get('component') != COMPONENT or type(data.get('user')) is not int
            or data['user'] != user or type(data.get('appId')) is not int
            or not isinstance(data.get('firstInstallTime'), str)
            or type(data.get('original')) is not int or data['original'] not in (0, 1)):
        raise FallbackError('恢复记录无效；不会执行其中的任意组件或命令。')
    if 'updaterOwned' in data and type(data['updaterOwned']) is not bool:
        raise FallbackError('恢复记录中的更新调度状态无效。')
    if 'updaterOriginal' in data and (type(data['updaterOriginal']) is not int or data['updaterOriginal'] not in (0, 1)):
        raise FallbackError('恢复记录中的更新调度原状态无效。')


def secure_file(path: Path) -> None:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise FallbackError('恢复记录所有者或权限不安全。')


class Journal:
    def __init__(self, directory: Path, user: int):
        self.directory = directory
        self.user = user
        self.path = directory / f'user-{user}.json'

    def read(self) -> dict | None:
        if not self.path.exists() and not self.path.is_symlink():
            return None
        secure_file(self.path)
        if self.path.stat().st_size > 16384:
            raise FallbackError('恢复记录大小异常。')
        with self.path.open() as stream:
            data = json.load(stream)
        validate_journal(data, self.user)
        return data

    def save(self, data: dict) -> None:
        validate_journal(data, self.user)
        fd, raw = tempfile.mkstemp(prefix='.journal-', dir=self.directory)
        try:
            with os.fdopen(fd, 'w') as stream:
                json.dump(data, stream, ensure_ascii=False, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(raw, self.path)
            self.sync_directory()
        finally:
            Path(raw).unlink(missing_ok=True)

    def sync_directory(self) -> None:
        fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    def clear(self) -> None:
        self.path.unlink(missing_ok=True)
        self.sync_directory()


@contextmanager
def locked_store(directory: Path):
    if directory.parent.is_symlink() or directory.is_symlink():
        raise FallbackError('恢复目录不能是符号链接。')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise FallbackError('恢复目录不是当前 Root 独占的真实目录。')
    fd = os.open(directory / 'operation.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise FallbackError('另一项字体来源操作正在执行。') from error
        yield
    finally:
        os.close(fd)  # Keep lock inode stable; a killed process releases flock.


def _restore_updater_if_owned(backend: Any, saved: dict, current: dict, user: int) -> None:
    if not saved.get('updaterOwned'):
        return
    orig = saved.get('updaterOriginal')
    if orig not in (0, 1):
        return
    cur_u = current.get('updaterState', 0)
    if cur_u == orig:
        return
    if cur_u != 2:
        return
    changer = getattr(backend, 'change_updater', None)
    if callable(changer):
        changer(user, orig)


def restore(backend: Android, journal: Journal) -> dict:
    saved = journal.read()
    if saved is None:
        return {'status': 'unchanged', 'message': '没有本工具的修改记录；未擅自启用字体组件。'}
    unseal = getattr(backend, 'unseal_font_caches', None)
    if callable(unseal):
        unseal(journal.user)
    current = backend.snapshot(journal.user)
    if not same_install(saved, current) or not current['declared']:
        raise FallbackError('GMS 安装身份或组件已变化；保留记录，未覆盖新安装状态。')
    value = current['componentState']
    if value == saved['original']:
        _restore_updater_if_owned(backend, saved, current, journal.user)
        journal.clear()
        return {'status': 'restored', 'message': '组件已处于开启前状态，恢复记录已清理。'}
    if value != 2:
        raise FallbackError('组件已被其他操作改动；保留现场，不强制覆盖。')
    backend.change(journal.user, saved['original'])
    after = backend.snapshot(journal.user)
    if not same_install(saved, after) or after['componentState'] != saved['original']:
        raise FallbackError('恢复尚未验证成功；保留恢复记录，可再次运行 restore。')
    _restore_updater_if_owned(backend, saved, after, journal.user)
    journal.clear()
    return {'status': 'restored', 'message': '已恢复原组件状态；请完整重启。',
            'originalState': saved['original'], 'user': journal.user}


def _disable_updater_best_effort(backend: Any, user: int, current: dict, saved: dict) -> bool:
    """Disable UpdateSchedulerService when available; record original state only if owned."""
    cur_u = current.get('updaterState', 0)
    if cur_u == 2:
        return True
    changer = getattr(backend, 'change_updater', None)
    if not callable(changer):
        return False
    if 'updaterOriginal' not in saved and cur_u in (0, 1):
        saved['updaterOriginal'] = cur_u
    if not changer(user, 2):
        saved.pop('updaterOriginal', None) if not saved.get('updaterOwned') else None
        return False
    after = backend.snapshot(user)
    if after.get('updaterState') == 2:
        saved['updaterOwned'] = True
        return True
    return False


def enable(backend: Android, journal: Journal) -> dict:
    saved = journal.read()
    current = backend.snapshot(journal.user)
    if not current['declared'] or current['packageState'] not in (0, 1):
        raise FallbackError('没有确认字体提供组件存在，或 GMS 整包已停用；未修改。')
    if saved is not None:
        if not same_install(saved, current):
            raise FallbackError('GMS 安装身份已变化；请先检查旧恢复记录。')
        if current['componentState'] == 2:
            return {'status': 'component-disabled', 'message': '字体提供组件已停用；未重复修改。'}
        raise FallbackError('已有恢复记录且组件状态已变化；先运行 restore 再决定是否开启。')
    if current['componentState'] == 2:
        return {'status': 'externally-disabled',
                'message': '该组件本来就已停用，不归本工具接管；反弹需要排查其他字体来源。'}
    saved = {**current, 'schema': SCHEMA, 'original': current['componentState']}
    journal.save(saved)  # Durable write-ahead record before any Android mutation.
    try:
        # Re-read just before writing; do not overwrite an intervening user's edit.
        latest = backend.snapshot(journal.user)
        if not same_install(saved, latest) or latest['componentState'] != saved['original']:
            journal.clear()
            raise FallbackError('操作前状态发生变化；已取消，未修改组件。')
        backend.change(journal.user, 2)
        after = backend.snapshot(journal.user)
        if not same_install(saved, after) or after['componentState'] != 2:
            raise FallbackError('停用后的状态未验证通过。')
    except FallbackError as error:
        try:
            restore(backend, journal)
        except FallbackError:
            raise FallbackError(str(error) + ' 回滚待确认，恢复记录保留，请运行 restore。') from error
        raise FallbackError(str(error) + ' 已恢复开启前状态。') from error

    updater_disabled = _disable_updater_best_effort(backend, journal.user, after, saved)
    if saved.get('updaterOwned'):
        journal.save(saved)
    cache_result = {'purged': 0, 'sealed': []}
    sealer = getattr(backend, 'seal_font_caches', None)
    if callable(sealer):
        cache_result = sealer(journal.user)
    return {
        'status': 'component-disabled',
        'user': journal.user,
        'updaterDisabled': updater_disabled,
        'purgedFiles': cache_result.get('purged', 0),
        'sealedCaches': len(cache_result.get('sealed', [])),
        'message': '已停用 Google 字体服务与更新调度并封堵字体缓存。请完整重启后检查 Google 应用。',
        'undo': '在设置页点击恢复原设置；卸载字域模块前先恢复。',
    }


def enforce(backend: Android, journal: Journal) -> dict:
    """Re-enforce user-opted-in compatibility after GMS self-updates or on boot."""
    saved = journal.read()
    if saved is None:
        return {'status': 'unchanged', 'message': '尚未开启 Google 字体兼容，跳过加固。'}
    current = backend.snapshot(journal.user)
    if not current['declared'] or current['packageState'] not in (0, 1):
        raise FallbackError('未确认字体提供组件可用，或 GMS 整包已停用。')
    if not same_install(saved, current):
        raise FallbackError('GMS 安装身份已变化；保留记录，未自动覆盖。')
    changed = False
    if current['componentState'] != 2:
        backend.change(journal.user, 2)
        after = backend.snapshot(journal.user)
        if not same_install(saved, after) or after['componentState'] != 2:
            raise FallbackError('重新停用 FontsProvider 未验证通过。')
        current = after
        changed = True
    updater_disabled = _disable_updater_best_effort(backend, journal.user, current, saved)
    if saved.get('versionCode') != current.get('versionCode') or saved.get('updaterOwned'):
        saved['versionCode'] = current.get('versionCode', saved.get('versionCode'))
        journal.save(saved)
    cache_result = {'purged': 0, 'sealed': []}
    sealer = getattr(backend, 'seal_font_caches', None)
    if callable(sealer):
        cache_result = sealer(journal.user)
    return {
        'status': 'enforced',
        'user': journal.user,
        'reDisabledProvider': changed,
        'updaterDisabled': updater_disabled,
        'purgedFiles': cache_result.get('purged', 0),
        'sealedCaches': len(cache_result.get('sealed', [])),
        'message': '已重新停用 Google 字体组件并封堵缓存。如 Google 应用仍在后台运行，请重启应用或手机。',
    }


def module_ready(module: Path) -> bool:
    try:
        return (any(value in (module / 'module.prop').read_text(encoding='utf-8').splitlines() for value in ('id=ziyu', 'id=LuoShu'))
                and not any((module / flag).exists() for flag in ('disable', 'remove'))
                and (module / 'config/active_font.conf').read_text().strip() not in ('', 'default'))
    except OSError:
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description='字域 Google 字体来源开关：停用字体组件、封堵缓存、可恢复。')
    parser.add_argument('action', choices=('status', 'enable', 'enforce', 'restore'), nargs='?', default='status')
    parser.add_argument('--user', type=int, help='仅操作指定用户；默认通过系统获取当前用户，不处理全部用户。')
    args = parser.parse_args()
    try:
        if os.geteuid() != 0:
            raise FallbackError('需要 Root；未执行任何修改。')
        backend = Android()
        user = args.user if args.user is not None else backend.current_user()
        if not 0 <= user <= 21474:
            raise FallbackError('无效的 Android 用户编号。')
        if args.action == 'status':
            current = backend.snapshot(user)
            print(json.dumps({'status': 'diagnostic', **current}, ensure_ascii=False, indent=2))
            return 0
        if args.action in ('enable', 'enforce') and not module_ready(MODULE):
            raise FallbackError('请先启用字域并应用自定义字体；未停用 Google 字体提供组件。')
        with locked_store(STORE):
            journal = Journal(STORE, user)
            if args.action == 'enable':
                result = enable(backend, journal)
            elif args.action == 'enforce':
                result = enforce(backend, journal)
            else:
                result = restore(backend, journal)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (FallbackError, OSError, ValueError) as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
