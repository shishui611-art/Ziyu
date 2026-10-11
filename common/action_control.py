#!/usr/bin/env python3
"""Explicit, module-owned cancellation, next-boot undo and log review."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import signal
import time

UNDO_CONFIGS = ('font-config-overlay.conf', 'font_runtime_legacy_v14_4.conf',
                'font-payload-schema.conf', 'universal-font-runtime.conf',
                'font-runtime-targets.conf', 'font-target-aliases.conf',
                'font-target-coverage.conf', 'device-font-engine.conf')
OWNED_LOGS = ('fontswitch.log', 'service.log', 'mount.log', 'universal-runtime.log',
              'font-route-verify.log', 'font-manager.log', 'device-font-load-verify.log',
              'device-font-payload.log', 'runtime-report.log', 'device-font-cache.log',
              'device-font-template.log', 'device-font-slot-plan.log', 'font-topology.log',
              'font-role-shadow.log', 'provider_cache.log', 'google-font-provider.log',
              'font-weight-retire.log', 'mount-backend.log', 'self-mount.log',
              'mount_compat.log', 'universal-runtime-verify.log', 'universal-mount.log',
              'app-install.log', 'font-live.log')

def live_record(module: Path) -> dict:
    from font_live_payload import current
    return current(module)


def values(path: Path) -> dict[str, str]:
    try:
        return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines()
                    if '=' in line)
    except OSError:
        return {}


def atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f'.tmp.{os.getpid()}')
    temporary.write_text(content, encoding='utf-8')
    temporary.chmod(0o600)
    os.replace(temporary, path)


def conf(path: Path, data: dict) -> None:
    atomic(path, ''.join(f'{key}={value}\n' for key, value in data.items()))


def owned_path(module: Path, path: Path) -> Path:
    root = module.resolve()
    resolved = path.resolve()
    if not resolved.is_relative_to(root) or resolved == root:
        raise ValueError('路径不属于当前模块')
    return resolved


def remove_owned(module: Path, path: Path) -> None:
    owned_path(module, path)
    if path.is_symlink():
        raise ValueError('拒绝删除符号链接目标')
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink(missing_ok=True)


def supervisor_identity(pid_file: Path, task: str, proc_root: Path = Path('/proc')):
    """Require exact supervisor command, task, boot and a current start-time."""
    try:
        pid = int(pid_file.read_text().strip())
        if pid <= 1 or Path(str(pid_file)+'.task').read_text().strip() != task:
            return None
        if Path(str(pid_file)+'.boot').read_text().strip() != (
                proc_root/'sys/kernel/random/boot_id').read_text().strip():
            return None
        arguments = (proc_root/str(pid)/'cmdline').read_bytes().split(b'\0')
        decoded = [part.decode(errors='replace') for part in arguments if part]
        if not any(Path(part).name == 'task_scope.py' for part in decoded):
            return None
        task_index = decoded.index('--task')
        if decoded[task_index+1] != task:
            return None
        tail = (proc_root/str(pid)/'stat').read_text().rsplit(') ', 1)[1].split()
        if tail[0] == 'Z':
            return None
        return pid, int(tail[19])
    except (OSError, ValueError, IndexError):
        return None


def cancel(module: Path, kind: str, task: str) -> dict:
    if not task or any(char in task for char in '\n\r/='):
        raise ValueError('任务编号无效')
    cfg = module/'config'
    filename = 'switch_task.conf' if kind == 'switch' else 'axes_task.conf'
    record = values(cfg/filename)
    if record.get('task') != task:
        raise ValueError('任务已结束或已被新任务替换，请刷新')
    if record.get('state') not in ('queued', 'running', 'cancelling'):
        return {'state': record.get('state', 'idle'), 'task': task,
                'message': '任务已经结束', 'rebootRequired': False}
    marker = cfg/('switch_task.cancel' if kind == 'switch' else 'mix_task.cancel')
    conf(marker, {'task': task, 'time': int(time.time())})
    if kind == 'mix':
        # The mix controller owns the inner engine and drains its children before
        # publishing. Its cooperative checkpoints consume this exact task marker.
        return {'state': 'cancelling', 'task': task, 'message': '正在停止组合并回收当前任务进程',
                'rebootRequired': False}
    pid_file = cfg/'switch_task_worker.pid'
    identity = supervisor_identity(pid_file, task)
    if identity is None:
        return {'state': 'cancelling', 'task': task, 'message': '已请求停止，正在等待任务检查并回收进程',
                'rebootRequired': False}
    # Recheck immediately before TERM. Only the one-shot supervisor receives the
    # signal; it terminates/reaps its current descendants using start-time checks.
    if supervisor_identity(pid_file, task) != identity:
        raise ValueError('任务进程已经变化，请刷新')
    try:
        os.kill(identity[0], signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if supervisor_identity(pid_file, task) != identity:
            try:
                proof = json.loads(Path(str(pid_file)+'.cleanup.json').read_text())
            except (OSError, ValueError):
                proof = {}
            if proof.get('task') != task or proof.get('leftoverPids') != []:
                return {'state': 'cancelling', 'task': task, 'message': '停止请求已发出，进程回收尚未确认，请刷新',
                        'rebootRequired': False}
            record = values(cfg/filename)
            outcome = discard_pending(module, task)
            if outcome.get('state') == 'applied':
                record.update(state='success', message=outcome['message'], finished=str(int(time.time())), pid='', percent='100')
                conf(cfg/filename, record)
                return outcome
            if record.get('task') == task and record.get('state') in ('running', 'queued', 'failed'):
                record.update(state='cancelled', message='字体应用已取消，当前生效字体保持原样',
                              finished=str(int(time.time())), pid='', percent='100')
                conf(cfg/filename, record)
            return {'state': 'cancelled', 'task': task,
                    'message': '字体应用已取消，当前生效字体保持原样', 'rebootRequired': False}
        time.sleep(.05)
    return {'state': 'cancelling', 'task': task, 'message': '正在回收任务进程，请刷新查看结果',
            'rebootRequired': False}


def assert_idle(module: Path) -> None:
    for name in ('switch_task.conf', 'axes_task.conf'):
        if values(module/'config'/name).get('state') in ('queued', 'running', 'cancelling'):
            raise ValueError('请先停止当前任务，再撤销字体应用')


def status(module: Path) -> dict:
    cfg = module/'config'
    pending = values(cfg/'font-payload-next.conf') or values(cfg/'universal-font-next.conf')
    if pending:
        live = live_record(module)
        if live and live.get('request_id') == pending.get('requestId') and live.get('font') == pending.get('font'):
            return {'undoAvailable': True, 'targetFont': pending.get('previousFont', 'default'),
                    'rebootRequired': False, 'undoRebootRequired': True, 'state': 'live-mounted'}
        return {'undoAvailable': True, 'targetFont': pending.get('previousFont', 'default'),
                'rebootRequired': True, 'undoRebootRequired': False, 'state': 'pending-reboot'}
    undone = values(cfg/'font-undo-result.conf')
    if undone.get('state') == 'applied':
        return {'undoAvailable': False, 'targetFont': undone.get('targetFont', ''),
                'rebootRequired': False, 'undoRebootRequired': False, 'state': 'undone'}
    previous = (values(cfg/'font-undo.conf') or values(cfg/'universal-font-activated.conf')
                or values(cfg/'font-payload-activated.conf'))
    target = previous.get('previousFont', '')
    retired = Path(previous.get('retired', ''))
    available = bool(previous) and target == 'default'
    if previous and target != 'default':
        try:
            retired = owned_path(module, retired)
            available = retired.is_relative_to((module/'.luoshu-retired').resolve()) and retired.is_dir()
        except ValueError:
            available = False
    return {'undoAvailable': available, 'targetFont': target, 'rebootRequired': False,
            'undoRebootRequired': available, 'state': 'applied' if previous else 'none'}


def discard_pending(module: Path, task: str) -> dict:
    cfg = module/'config'
    if (cfg/'font-live-transaction.conf').exists():
        raise ValueError('热切换回滚尚未确认，保留字体队列，请导出日志')
    for name in ('font-payload-next.conf', 'universal-font-next.conf'):
        pending = values(cfg/name)
        if pending.get('taskId') != task:
            continue
        live = live_record(module)
        if live and live.get('request_id') == pending.get('requestId'):
            return {'state': 'applied', 'targetFont': live['font'], 'rebootRequired': False,
                    'message': '字体已完成热挂载，停止请求晚于提交；如需恢复请使用恢复上一套字体'}
        previous = pending.get('previousFont', 'default')
        if live:
            previous = live['font']
        remove_owned(module, module/'.luoshu-payload-next')
        (cfg/name).unlink(missing_ok=True)
        (cfg/'text_reboot_required.conf').unlink(missing_ok=True)
        atomic(cfg/'active_font.conf', previous+'\n')
        return {'state': 'cancelled', 'targetFont': previous, 'rebootRequired': False}
    return {'state': 'cancelled', 'rebootRequired': False}


def prune_retired(module: Path) -> dict:
    cfg = module/'config'
    # New snapshots are never pruned during an unverified boot or a live task.
    universal_mode = values(cfg/'universal-font-runtime.conf')
    grade = values(cfg/'universal-font-runtime-verification.conf').get('grade')
    state = values(cfg/'device-font-load-verification.conf').get('state')
    verified = grade == 'PASS' if universal_mode else state in ('verified', 'not-applicable')
    if not verified:
        return {'removed': 0, 'reason': 'boot-not-verified'}
    for name in ('switch_task.conf', 'axes_task.conf', 'mix_task.conf'):
        if values(cfg/name).get('state') in ('queued', 'running', 'cancelling'):
            return {'removed': 0, 'reason': 'task-active'}
    retired_root = module/'.luoshu-retired'
    if not retired_root.is_dir() or retired_root.is_symlink():
        return {'removed': 0, 'reason': 'no-owned-snapshots'}
    owned_path(module, retired_root)
    keep = set()
    for name in ('font-undo.conf', 'font-payload-activated.conf', 'universal-font-activated.conf', 'font-live-boot-previous.conf'):
        retired = values(cfg/name).get('retired', '')
        if name == 'font-live-boot-previous.conf':
            retired = values(cfg/name).get('source', '')
        if retired:
            try:
                keep.add(owned_path(module, Path(retired)))
            except ValueError:
                pass
    removed = 0
    for snapshot in retired_root.iterdir():
        if snapshot.is_symlink() or not snapshot.is_dir():
            continue
        if not snapshot.name.startswith(('payload-', 'universal-')) or snapshot.resolve() in keep:
            continue
        remove_owned(module, snapshot)
        removed += 1
    return {'removed': removed, 'reason': 'verified-prune'}


def undo(module: Path) -> dict:
    assert_idle(module)
    cfg = module/'config'
    if (cfg/'font-live-transaction.conf').exists():
        raise ValueError('热切换事务尚未恢复，请导出日志')
    live = live_record(module)
    if live:
        pending = values(cfg/'font-payload-next.conf')
        if live.get('request_id') == pending.get('requestId'):
            previous = values(cfg/'font-live-previous.conf')
            if previous.get('schema') != 'ziyu-live-previous-v1' or previous.get('boot_id') != live['boot_id']:
                raise ValueError('上一套热切换字体记录不可用')
            source = Path(previous.get('source', ''))
            if source != module/'.luoshu-payload' and (source.parent != module/'.luoshu-state/cache/live'/live['boot_id'] or not re.fullmatch(r'generation-[0-9a-f]{64}', source.name)):
                raise ValueError('上一套字体来源不在模块目录')
            if not source.is_dir() or source.is_symlink():
                raise ValueError('上一套字体来源不可读取')
            temporary = module/'.luoshu-state/tmp'/f'live-undo-{os.getpid()}'
            shutil.copytree(source, temporary, symlinks=True)
            remove_owned(module, module/'.luoshu-payload-next')
            os.replace(temporary, module/'.luoshu-payload-next')
            font = previous['font']
            conf(cfg/'font-payload-next.conf', dict(state='prepared', font=font, previousFont=live['font'],
                 previousLegacy='true', targetMode='legacy', undo='true', requestId=f'live-undo-{time.time_ns()}'))
            atomic(cfg/'active_font.conf', font+'\n')
            conf(cfg/'text_reboot_required.conf', dict(font=font, reason='explicit-live-undo-next-boot', bootId=live['boot_id']))
            return {'state': 'staged', 'targetFont': font, 'rebootRequired': True,
                    'message': '已准备恢复热切换前的字体，需确认重启后生效'}
    for name in ('font-payload-next.conf', 'universal-font-next.conf'):
        pending = values(cfg/name)
        if pending:
            previous = pending.get('previousFont', 'default')
            # Staged selection is only an App label. Cancel the stage and restore
            # that label; leave runtime mode and mounted payload completely alone.
            remove_owned(module, module/'.luoshu-payload-next')
            for marker in ('font-payload-next.conf', 'universal-font-next.conf',
                           'text_reboot_required.conf'):
                (cfg/marker).unlink(missing_ok=True)
            atomic(cfg/'active_font.conf', previous+'\n')
            conf(cfg/'font-undo-result.conf', {'state': 'cancelled-pending', 'targetFont': previous})
            return {'state': 'cancelled-pending', 'targetFont': previous, 'rebootRequired': False,
                    'message': '已撤销待重启的字体应用，当前生效字体保持原样'}
    previous = values(cfg/'font-undo.conf')
    if not previous:
        # Compatibility with a boot that occurred before this feature was added.
        previous = values(cfg/'universal-font-activated.conf') or values(cfg/'font-payload-activated.conf')
    if not previous:
        raise ValueError('没有可撤销的上一套字体记录')
    font = previous.get('previousFont', 'default')
    mode = previous.get('previousMode') or ('legacy' if previous.get('previousLegacy') == 'true'
                                          else 'default' if font == 'default' else 'classic')
    retired = Path(previous.get('retired', ''))
    if mode != 'default':
        retired = owned_path(module, retired)
        retired_root = (module/'.luoshu-retired').resolve()
        if not retired.is_relative_to(retired_root) or not retired.is_dir():
            raise ValueError('上一套字体负载缺失，无法安全撤销')
    next_payload = module/'.luoshu-payload-next'
    if next_payload.exists():
        raise ValueError('已有未提交的字体负载，请刷新后重试')
    temporary = module/f'.luoshu-undo-stage.{os.getpid()}'
    try:
        if mode == 'default':
            temporary.mkdir()
        else:
            # Preserve module payload symlinks; never traverse them into /system.
            shutil.copytree(retired, temporary, symlinks=True)
        os.replace(temporary, next_payload)
        current = previous.get('font', 'default')
        data = {'state': 'prepared', 'font': font, 'previousFont': current,
                'previousLegacy': 'false', 'recovery': 'true', 'undo': 'true',
                'time': int(time.time())}
        if mode == 'universal':
            manifest = json.loads((next_payload/'.luoshu-runtime/deployment/deployment.json').read_text())
            data.update(deploymentId=manifest['deploymentId'], payloadDigest=manifest['payloadDigest'],
                        previousMode='universal')
            conf(cfg/'universal-font-next.conf', data)
        else:
            data['targetMode'] = mode
            conf(cfg/'font-payload-next.conf', data)
        conf(cfg/'text_reboot_required.conf', {'font': font, 'reason': 'explicit-undo-next-boot'})
        conf(cfg/'font-undo-result.conf', {'state': 'staged', 'targetFont': font, 'targetMode': mode})
        return {'state': 'staged', 'targetFont': font, 'targetMode': mode, 'rebootRequired': True,
                'message': '已准备恢复上一套字体，需确认完整重启后生效'}
    except Exception:
        if temporary.exists():
            remove_owned(module, temporary)
        if next_payload.exists():
            remove_owned(module, next_payload)
        raise


def log_fingerprint(module: Path) -> str:
    digest = hashlib.sha256()
    for name in OWNED_LOGS:
        path = module/'logs'/name
        if path.is_file() and not path.is_symlink():
            stat = path.stat()
            digest.update(f'{name}:{stat.st_size}:{stat.st_mtime_ns}\n'.encode())
    return digest.hexdigest()


def recent_log_records(module: Path) -> list[tuple[str, str]]:
    path = module/'logs/fontswitch.log'
    if not path.is_file() or path.is_symlink():
        return []
    # Read the same last 500 records used by health reporting, without loading
    # an unbounded font-switch history into memory.
    with path.open('rb') as stream:
        stat = os.fstat(stream.fileno())
        stream.seek(0, os.SEEK_END)
        offset = stream.tell()
        chunks = []
        lines = 0
        while offset > 0 and lines <= 500:
            size = min(offset, 65536)
            offset -= size
            stream.seek(offset)
            chunk = stream.read(size)
            chunks.append(chunk)
            lines += chunk.count(b'\n')
    records = []
    for raw in b''.join(reversed(chunks)).splitlines(keepends=True):
        records.append((raw.decode('utf-8', errors='replace').rstrip('\r\n'),
                        f'{stat.st_dev}:{stat.st_ino}:{offset}'))
        offset += len(raw)
    return records[-500:]


def log_severity(line: str) -> str:
    if re.search(r'\[(?:ERROR|FATAL)\]|^(?:\[[^]]+\]\s*)*(?:ERROR|FATAL)\b', line, re.I):
        return 'error'
    if re.search(r'\[WARN(?:ING)?\]|^(?:\[[^]]+\]\s*)*WARN(?:ING)?\b', line, re.I):
        return 'warning'
    start = line.find('{')
    if start >= 0 and line.rstrip().endswith('}'):
        try:
            status = str(json.loads(line[start:]).get('status', '')).lower()
            if status in ('ok', 'success'):
                return 'info'
            if status in ('error', 'failed'):
                return 'error'
            if status in ('warn', 'warning'):
                return 'warning'
        except (ValueError, AttributeError):
            pass
    if re.search(r'\bwarn(?:ing)?\b|警告', line, re.I):
        return 'warning'
    if re.search(r'\b(?:error|failed|failure|fatal)\b|失败|错误', line, re.I):
        return 'error'
    return 'info'


def warning_review(module: Path, ignore: bool = False) -> dict:
    records = recent_log_records(module)
    warnings: dict[str, int] = {}
    errors = 0
    for line, position in records:
        severity = log_severity(line)
        if severity == 'error':
            errors += 1
        elif severity == 'warning':
            # Position distinguishes a newly appended identical warning from
            # one the user already ignored, even after the 500-line window moves.
            key = hashlib.sha256(f'{position}:{line}'.encode('utf-8')).hexdigest()
            warnings[key] = warnings.get(key, 0) + 1
    marker = module/'config/log-warning-review.json'
    if ignore:
        atomic(marker, json.dumps({'warnings': warnings, 'ignoredAt': int(time.time())}) + '\n')
    try:
        ignored = json.loads(marker.read_text(encoding='utf-8')).get('warnings', {})
        if not isinstance(ignored, dict):
            ignored = {}
    except (OSError, ValueError, AttributeError):
        ignored = {}
    ignored_count = sum(min(count, max(0, ignored.get(key, 0)))
                        for key, count in warnings.items()
                        if isinstance(ignored.get(key, 0), int))
    total = sum(warnings.values())
    return {'recentWarnings': total - ignored_count, 'recentErrors': errors,
            'ignoredWarnings': ignored_count,
            'warningsIgnored': total > 0 and total == ignored_count}


def review_logs(module: Path, action: str) -> dict:
    marker = module/'config/log-review.conf'
    if action == 'clear':
        (module/'config/log-warning-review.json').unlink(missing_ok=True)
        for name in OWNED_LOGS:
            path = module/'logs'/name
            if path.is_file() and not path.is_symlink():
                owned_path(module, path)
                # Truncation keeps live writers attached to the same inode.
                with path.open('w', encoding='utf-8'):
                    pass
    if action in ('mark', 'clear'):
        conf(marker, {'fingerprint': log_fingerprint(module), 'viewedAt': int(time.time())})
    record = values(marker)
    return {**warning_review(module, ignore=action == 'ignore-warnings'),
            'viewed': record.get('fingerprint') == log_fingerprint(module),
            'viewedAt': int(record.get('viewedAt', '0')), 'cleared': action == 'clear',
            'message': ('日志已清空' if action == 'clear' else '日志已标记为已查看' if action == 'mark'
                        else '当前警告已忽略；新警告和错误仍会提示' if action == 'ignore-warnings' else '')}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', required=True)
    parser.add_argument('action', choices=('cancel', 'undo', 'status', 'discard-pending', 'prune-retired', 'logs', 'log-health'))
    parser.add_argument('arguments', nargs='*')
    args = parser.parse_args()
    module = Path(args.module).resolve()
    try:
        if args.action == 'cancel':
            kind, task = args.arguments
            if kind not in ('switch', 'mix'):
                raise ValueError('未知任务类型')
            result = cancel(module, kind, task)
        elif args.action == 'undo':
            result = undo(module)
        elif args.action == 'status':
            result = status(module)
        elif args.action == 'discard-pending':
            result = discard_pending(module, args.arguments[0])
        elif args.action == 'prune-retired':
            result = prune_retired(module)
        elif args.action == 'log-health':
            for key, value in warning_review(module).items():
                print(f'{key}={str(value).lower() if isinstance(value, bool) else value}')
            return 0
        else:
            action = args.arguments[0] if args.arguments else 'status'
            if action not in ('status', 'mark', 'clear', 'ignore-warnings'):
                raise ValueError('未知日志操作')
            result = review_logs(module, action)
        print(json.dumps({'status': 'ok', 'data': result}, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
