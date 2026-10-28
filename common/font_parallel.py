"""Bounded offline font workers; no affinity, resident daemon or multiprocessing dependency.

Each child owns a separate FontTools object graph and private scratch directory.
Callers keep publication/rollback in the parent, after every worker has succeeded.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import time

MIB = 1024 * 1024


def _available_memory():
    try:
        for line in Path('/proc/meminfo').read_text(encoding='ascii').splitlines():
            if line.startswith('MemAvailable:'):
                available = int(line.split()[1]) * 1024
                break
        else:
            return 0
        # Respect a cgroup memory limit when the device supplies one.
        for root, limit_name, used_name in (
                ('/sys/fs/cgroup', 'memory.max', 'memory.current'),
                ('/dev/memcg', 'memory.limit_in_bytes', 'memory.usage_in_bytes')):
            try:
                limit = int((Path(root) / limit_name).read_text(encoding='ascii').strip())
                used = int((Path(root) / used_name).read_text(encoding='ascii').strip())
                available = min(available, max(0, limit - used))
            except (OSError, ValueError):
                pass
        return available
    except (OSError, ValueError):
        return 0


def worker_budget(job_count, source_bytes=0):
    """Reserve foreground memory and at least half the allowed CPUs; unknown capacity is serial."""
    try:
        cpus = len(os.sched_getaffinity(0))
    except (AttributeError, OSError):
        cpus = (getattr(os, 'process_cpu_count', os.cpu_count)() or 1)
    available = _available_memory()
    # Include the bounded immutable outline-template cache used by transplants.
    per_worker = max(768 * MIB, max(0, source_bytes) * 24) + 64 * MIB
    memory_workers = max(1, (available - 1024 * MIB) // per_worker)
    workers = max(1, min(3, cpus // 2, job_count, memory_workers))
    if not hasattr(os, 'fork') or not hasattr(signal, 'pthread_sigmask'):
        workers = 1
    return {'workers': workers, 'allowedCpus': cpus,
            'availableMemoryBytes': available, 'estimatedWorkerBytes': per_worker}


def _stop_children(children):
    # Kill only direct workers still owned by this invocation; never match global Python PIDs.
    for pid in children:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    for pid in children:
        try:
            os.waitpid(pid, 0)
        except ChildProcessError:
            pass


def run_batches(items, workspace, source_bytes, run_batch, *, item_costs=None, progress_poll=None):
    """Return ordered child batches and the chosen resource budget.

    run_batch receives indexed items, a private directory, and whether it is in
    a child. Its JSON result must refer only to outputs retained in workspace.
    The caller removes workspace after validating and publishing the results.
    """
    budget = worker_budget(len(items), source_bytes)
    workspace = Path(workspace)
    workspace.mkdir(parents=True, exist_ok=True)
    if not items:
        return [], {**budget, 'workers': 0}
    indexed = list(enumerate(items))
    if item_costs is not None:
        if (len(item_costs) != len(items)
                or any(type(cost) is not int or cost < 1 for cost in item_costs)):
            raise ValueError('字体任务调度估算无效')
        partitions = [[] for _ in range(budget['workers'])]
        loads = [0] * budget['workers']
        # Longest estimated jobs first; preserve the original item indices.
        # Costs affect scheduling only, never validation or publication.
        for item in sorted(indexed, key=lambda pair: (-item_costs[pair[0]], pair[0])):
            worker = min(range(len(loads)), key=lambda index: (loads[index], index))
            partitions[worker].append(item)
            loads[worker] += item_costs[item[0]]
        budget['scheduling'] = 'estimated-cost-balanced'
        budget['estimatedBatchCosts'] = loads
        budget['batchItemCounts'] = [len(batch) for batch in partitions]
    else:
        partitions = [indexed[index::budget['workers']] for index in range(budget['workers'])]
    if budget['workers'] == 1:
        private = workspace / 'worker-0'
        private.mkdir()
        return [run_batch(partitions[0], private, False)], budget

    pending = {}
    completed = {}
    previous_handlers = {}

    def interrupted(signum, _frame):
        raise InterruptedError(f'font worker batch interrupted by signal {signum}')

    try:
        for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            previous_handlers[sig] = signal.signal(sig, interrupted)
        for index in range(budget['workers']):
            private = workspace / f'worker-{index}'
            private.mkdir()
            result_file = private / 'result.json'
            batch = partitions[index]
            # Register ownership before an interrupt can arrive between fork() and pending[pid].
            previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, set(previous_handlers))
            try:
                pid = os.fork()
            except BaseException:
                signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
                raise
            if pid == 0:
                # A child must never run the parent's finally block with sibling ownership.
                pending.clear()
                code = 1
                try:
                    for sig in previous_handlers:
                        signal.signal(sig, signal.SIG_DFL)
                    signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
                    try:
                        os.nice(5)  # Keep rendering ahead of offline font compilation.
                    except (AttributeError, OSError):
                        pass
                    try:
                        value = {'ok': True, 'value': run_batch(batch, private, True)}
                        code = 0
                    except BaseException as error:
                        value = {'ok': False, 'error': f'{type(error).__name__}: {error}'}
                    temporary = private / 'result.tmp'
                    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
                    temporary.replace(result_file)
                except BaseException:
                    code = 1
                finally:
                    os._exit(code)
            pending[pid] = (index, result_file)
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        while pending:
            if progress_poll is not None:
                progress_poll()
            progressed = False
            for pid in list(pending):
                found, status = os.waitpid(pid, os.WNOHANG)
                if not found:
                    continue
                index, result_file = pending.pop(pid)
                progressed = True
                if not result_file.is_file() or result_file.stat().st_size > 32 * MIB:
                    raise RuntimeError(f'字体处理进程未返回完整结果：worker {index}')
                value = json.loads(result_file.read_text(encoding='utf-8'))
                result_file.unlink()
                if not os.WIFEXITED(status) or os.WEXITSTATUS(status) != 0 or value.get('ok') is not True:
                    raise RuntimeError(value.get('error', f'字体处理进程异常结束：worker {index}'))
                completed[index] = value['value']
            if not progressed:
                time.sleep(0.05)
        return [completed[index] for index in sorted(completed)], budget
    finally:
        # Do not leave children publishing into scratch paths after a failure or cancellation.
        for sig in previous_handlers:
            signal.signal(sig, signal.SIG_IGN)
        try:
            _stop_children(pending)
        finally:
            for sig, handler in previous_handlers.items():
                signal.signal(sig, handler)
