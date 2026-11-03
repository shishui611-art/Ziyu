#!/usr/bin/env python3
"""Explicit bounded log capture. No boot hook, upload, or persistent daemon."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import time
import uuid
import zipfile
from font_slot_overrides import write

LIMIT = 12 * 1024 * 1024
ROOT = Path('/data/adb/ziyu/diagnostic-captures')


def boot_id():
    return Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def identity(pid):
    try:
        return Path(f'/proc/{int(pid)}/stat').read_text().rsplit(')', 1)[1].split()[19]
    except (OSError, ValueError, IndexError):
        return ''


def alive(state):
    return (state.get('bootId') == boot_id() and bool(state.get('startTime'))
            and identity(state.get('pid', 0)) == state['startTime'])


def stop_child(child):
    # Only a Popen handle created in this worker can enter this function.
    if child.poll() is None:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            child.wait(timeout=2)
            return
        try:
            child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=2)


def bounded_command(argv, limit=131072, seconds=3):
    content = bytearray()
    try:
        child = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 stdin=subprocess.DEVNULL, start_new_session=True)
    except OSError as error:
        return str(error).encode()
    end = time.monotonic() + seconds
    try:
        while len(content) < limit and time.monotonic() < end:
            ready, _, _ = select.select([child.stdout], [], [], min(.2, max(0, end-time.monotonic())))
            if ready:
                data = os.read(child.stdout.fileno(), min(8192, limit-len(content)))
                if not data:
                    break
                content.extend(data)
            elif child.poll() is not None:
                break
    finally:
        stop_child(child)
        child.stdout.close()
    return bytes(content) + (b'\n[output/time bounded]\n' if len(content) >= limit or time.monotonic() >= end else b'')


def read_bounded(path, maximum=65536, *, tail=False):
    try:
        with Path(path).open('rb') as stream:
            if tail:
                stream.seek(0, 2)
                stream.seek(max(0, stream.tell()-maximum))
            content = stream.read(maximum+1)
        return content[:maximum] + (b'\n[truncated]\n' if len(content) > maximum else b'')
    except OSError as error:
        return str(error).encode()


def snapshot(module, directory, phase, sensitive):
    parts = []
    commands = [('system', ['getprop', 'ro.build.fingerprint']),
                ('android', ['getprop', 'ro.build.version.release']),
                ('root-manager', ['sh', '-c', '. "$1"; ziyu_root_manager_info', 'sh', str(module/'common/root_manager_detection.sh')]),
                ('selinux', ['getenforce']), ('kernel-errors', ['sh', '-c', 'dmesg | tail -c 131072'])]
    for label, command in commands:
        parts.append(f'\n[{label}]\n'.encode() + bounded_command(command))
    for relative in ('module.prop', 'config/mount-backend.conf', 'config/self-mount.conf',
                     'config/device-font-load-verification.conf', 'config/font-live.conf',
                     'config/boot-ota-guard.conf', 'config/font-apply-result.conf',
                     'config/device_font_roles.json', 'logs/fontswitch.log'):
        parts.append(f'\n[{relative}]\n'.encode() + read_bounded(module / relative, tail=relative.startswith('logs/')))
    roles = module/'config/device_font_roles.json'
    if roles.is_file() and roles.stat().st_size <= 4*1024*1024:
        try:
            data = json.loads(roles.read_text(encoding='utf-8'))
            write(directory/f'{phase}-font-summary.json', {key: data.get(key) for key in
                  ('schema', 'roleRevision', 'buildKey', 'romKind', 'summary', 'slotOverridesDigest')})
        except (OSError, ValueError):
            pass
    parts.append(b'\n[PID 1 mountinfo]\n' + read_bounded('/proc/1/mountinfo', 262144))
    if sensitive:
        parts.append(b'\n[optional DropBox]\n' + bounded_command(['dumpsys', 'dropbox', '--print'], limit=131072))
        candidates = sorted(Path('/data/anr').glob('*'), key=lambda p: p.stat().st_mtime, reverse=True)
        if candidates and candidates[0].is_file() and not candidates[0].is_symlink():
            parts.append(b'\n[optional latest ANR]\n' + read_bounded(candidates[0], 131072))
    (directory / f'{phase}.txt').write_bytes(b''.join(parts)[:2*1024*1024])


def state_for(session=None):
    if session is None:
        current = ROOT / 'current.json'
        if not current.is_file():
            return None, None
        session = json.loads(current.read_text())['session']
    if not re.fullmatch(r'[a-f0-9]{32}', session):
        raise ValueError('采集记录编号无效')
    directory = ROOT / session
    if directory.is_symlink():
        raise ValueError('采集目录无效')
    state = json.loads((directory / 'state.json').read_text())
    return directory, state


def package(directory, state):
    errors = []
    matches = {'crash': 0, 'anr': 0, 'avc': 0, 'font': 0}
    for path in directory.iterdir():
        if path.is_file() and not path.is_symlink() and (path.suffix == '.txt' or path.name.startswith('logcat.log')):
            for line in read_bounded(path, 1048576).decode(errors='replace').splitlines():
                for key, pattern in [('crash', r'FATAL EXCEPTION|Fatal signal'), ('anr', r'ANR in |am_anr'),
                                     ('avc', r'avc:.*denied'), ('font', r'(font|字体).*(error|failed|失败)')]:
                    if re.search(pattern, line, re.I):
                        matches[key] += 1
                        if len(errors) < 60:
                            errors.append(line[:1024])
    report = {'report': 'ziyu-diagnostic-capture-v1', 'conclusion': '记录中存在异常线索，请结合时间和复现过程定位。' if any(matches.values()) else '已完成有限时间记录；未检出关键字不代表问题不存在。',
              'session': state, 'keywordMatches': matches, 'keyLines': errors,
              'scope': ['系统/Android/ROM', 'Root/挂载模式与状态', '字体状态与角色', 'SELinux/AVC', 'Crash/ANR/字体错误线索', '采集过程'],
              'limits': '默认不读取原始 ANR/DropBox；关键字仅为线索，截断和权限错误见原始记录。',
              'privacy': '内容可能包含应用名称、路径和个人信息；请检查后自行分享，不自动上传。'}
    write(directory / 'report.json', report)
    labels = ['系统信息', 'Android 版本', 'ROM 识别', 'Root 管理器', '挂载模式', '字体槽位数量',
              '当前字体状态', '字体角色识别', 'SELinux 状态', 'AVC 拒绝记录', 'Crash 记录',
              'ANR 记录', '字体引擎错误', '采集过程信息', '最终诊断结论']
    details = ['见 start.txt/end.txt 的 module.prop 与 system', '见 android 段', '见 system 构建指纹',
               '见 root-manager 段', '见 mount-backend.conf、self-mount.conf 与 PID 1 mountinfo',
               '见 start-font-summary.json / end-font-summary.json；过长原始记录会注明截断',
               '见 font-live.conf 与字体加载验证结果', '见 device_font_roles.json', '见 selinux 段',
               f"检出 {matches['avc']} 条关键字线索", f"检出 {matches['crash']} 条关键字线索",
               f"检出 {matches['anr']} 条关键字线索；原始 ANR 采集={'开启' if state.get('sensitive') else '关闭'}",
               f"检出 {matches['font']} 条关键字线索", json.dumps(state, ensure_ascii=False), report['conclusion']]
    (directory/'report.txt').write_text('Ziyu Diagnostic Report\n\n结论：'+report['conclusion']+'\n\n'+
        '\n'.join(f'{i}. {label}：{detail}' for i,(label,detail) in enumerate(zip(labels,details),1))+
        '\n\n'+report['privacy']+'\n', encoding='utf-8')
    output = directory / 'capture.zip'
    budget = LIMIT - 65536
    with zipfile.ZipFile(output.with_suffix('.tmp'), 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.iterdir(), key=lambda p: (p.name != 'report.json', p.name)):
            if not path.is_file() or path.is_symlink() or path.name in ('capture.zip', 'capture.tmp', 'stop'):
                continue
            content = read_bounded(path, min(budget, LIMIT))[:budget]
            if content:
                archive.writestr(path.name, content)
                budget -= len(content)
    os.replace(output.with_suffix('.tmp'), output)
    return output


def worker(module, session):
    for _ in range(40):
        try:
            directory, state = state_for(session)
            if state.get('pid') == os.getpid():
                break
        except FileNotFoundError:
            pass
        time.sleep(.05)
    else:
        return 1
    child = None
    try:
        os.nice(10)
        deadline = time.monotonic() + state['seconds']
        # Toybox timeout remains a watchdog if the Python supervisor is killed.
        child = subprocess.Popen(['timeout', '-s', 'TERM', '120', 'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-b', 'events',
                                  '-v', 'threadtime', '-T', '1', '-f', str(directory/'logcat.log'), '-r', '1024', '-n', '7'],
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 start_new_session=True)
        snapshot(module, directory, 'start', state['sensitive'])
        while time.monotonic() < deadline and not (directory/'stop').exists() and child.poll() is None:
            time.sleep(.5)
        stop_child(child)
        write(directory / 'state.json', {**state, 'state': 'finalizing'})
        snapshot(module, directory, 'end', state['sensitive'])
        state.update(state='completed', stoppedAt=int(time.time()), logcatExit=child.returncode)
        if child.returncode not in (0, 124, -signal.SIGTERM, 128+signal.SIGTERM):
            state['error'] = f'logcat 提前结束（{child.returncode}），已有快照和原始记录已保留'
    except Exception as error:
        state.update(state='failed', error=str(error), stoppedAt=int(time.time()))
    finally:
        if child is not None:
            stop_child(child)
        write(directory / 'state.json', {**state, 'state': 'finalizing'})
        try:
            package(directory, state)
        except Exception as error:
            state.update(state='failed', error=f'打包失败，原始日志已保留：{error}')
        write(directory / 'state.json', state)
    return 0


def command(module, action, session, sensitive):
    from adb_storage import secure_directory
    secure_directory(ROOT)
    import fcntl
    with (ROOT / '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        directory, state = state_for(session or None)
        if action == 'start':
            if state and state['state'] in ('running', 'finalizing') and alive(state):
                raise ValueError('已有采集正在运行，请先停止')
            session = uuid.uuid4().hex
            directory = ROOT / session
            secure_directory(directory)
            child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), str(module), 'worker', session],
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     start_new_session=True, close_fds=True)
            started = identity(child.pid)
            if not started:
                stop_child(child)
                raise ValueError('无法核对采集进程身份，本次未开始记录')
            state = {'session': session, 'state': 'running', 'pid': child.pid, 'startTime': started,
                     'bootId': boot_id(), 'startedAt': int(time.time()), 'seconds': 120, 'sensitive': sensitive}
            write(directory/'state.json', state)
            write(ROOT/'current.json', {'session': session})
        elif state is None:
            return {'status': 'ok', 'state': 'idle'}
        elif action == 'stop':
            if state['state'] in ('running', 'finalizing') and alive(state):
                (directory/'stop').touch(mode=0o600)
            elif state['state'] in ('running', 'finalizing'):
                state.update(state='interrupted', error='采集进程已结束；原始记录已保留')
                write(directory/'state.json', state)
                package(directory, state)
        elif action == 'export':
            if state['state'] in ('running', 'finalizing') and alive(state):
                raise ValueError('请先停止采集，等待日志打包完成')
            output = directory/'capture.zip'
            if not output.is_file():
                package(directory, state)
            public = Path(os.environ.get('ZIYU_PUBLIC_DIR', '/sdcard/ziyu'))/'reports'
            public.mkdir(parents=True, exist_ok=True)
            target = public / f'Ziyu-capture-{session}.zip'
            shutil.copyfile(output, target)
            target.chmod(0o644)
            return {'status': 'ok', 'state': state['state'], 'path': str(target)}
        result = {**state, 'status': 'ok', 'archiveReady': (directory/'capture.zip').is_file()}
        if state['state'] in ('running', 'finalizing') and not alive(state):
            result['state'] = 'interrupted'
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('module', type=Path)
    parser.add_argument('action', choices=('start', 'stop', 'status', 'export', 'worker'))
    parser.add_argument('session', nargs='?', default='')
    parser.add_argument('--sensitive', action='store_true')
    args = parser.parse_args()
    if args.action == 'worker':
        return worker(args.module, args.session)
    try:
        print(json.dumps(command(args.module, args.action, args.session, args.sensitive), ensure_ascii=False))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
