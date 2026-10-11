#!/usr/bin/env python3
"""Bounded, read-only App auxiliary queries sharing one bridge invocation."""
from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
from threading import Thread
from pathlib import Path


def read(module: Path, script: str, action: str, timeout: int) -> dict:
    env = dict(os.environ, MODDIR=str(module), MODULE_DIR=str(module))
    try:
        with subprocess.Popen(["sh", str(module / "common" / script), action],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=env, start_new_session=True) as process:
            try:
                stdout, stderr = process.communicate(timeout=timeout)
                code = process.returncode
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                stdout, stderr = process.communicate()
                code = 124
                stderr += b"\nauxiliary query timed out"
        return {"code": code, "stdout": stdout.decode("utf-8", errors="replace"),
                "stderr": stderr.decode("utf-8", errors="replace")}
    except OSError as error:
        return {"code": 1, "stdout": "", "stderr": str(error)}


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] not in {"settings", "logs"}:
        return 2
    module = Path(sys.argv[1])
    jobs = ([("action", "action_control.sh", "status", 10),
             ("preferences", "mount_backend_preferences.sh", "get", 15)]
            if sys.argv[2] == "settings" else
            [("review", "log_review.sh", "status", 8),
             ("action", "action_control.sh", "status", 10)])
    results = {}

    def query(key: str, script: str, action: str, timeout: int) -> None:
        results[key] = read(module, script, action, timeout)

    # Both pages ask independent read-only helpers. Keep each timeout and error
    # result independent, while avoiding the sum of their waiting times.
    workers = [Thread(target=query, args=job) for job in jobs]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    print(json.dumps({"schema": "ziyu-app-reads-v1", "reads": results}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
