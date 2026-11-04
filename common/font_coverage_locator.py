#!/usr/bin/env python3
"""Read-only locator for apps that may not be using 字域's active font payload."""
from __future__ import annotations

import json
import os
import re
import select
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from device_font_process_audit import PayloadIndex, inspect_process  # noqa: E402

PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+(?::[A-Za-z0-9_.$-]+)?$")
SAFE_NAME_RE = re.compile(r"[^A-Za-z0-9._+-]")
MAX_PID_ENTRIES = 1024
MAX_PID_DIRECTORY_ENTRIES = 16384
MAX_RUNNING_APPS = 96
MAX_SELECTED_PROCESSES = 3
MAX_SCAN_APPS = 24
MAX_SCAN_RESULTS = 16
MAX_FILES_PER_APP = 5
MAX_OUTPUT_BYTES = 24 * 1024
SCAN_BUDGET_SECONDS = 16.0
COMMAND_BUDGET_SECONDS = 22

GUIDES = {
    "not-running": ("没有找到运行进程", "先打开有问题的页面，再回字域检查。"),
    "no-payload": ("没有找到可检查的字域字体", "先确认字体已应用，再回来检查。"),
    "unreadable": ("暂时无法检查", "请检查 Root 授权，并保持目标应用运行。"),
    "stale-process": ("还在使用旧字体", "完全退出这个应用，再重新打开检查。"),
    "route-not-visible": ("应用没有读到字域字体", "先查看字域的挂载结果，再重新打开这个应用。"),
    "theme-source": ("可能受系统主题影响", "在系统设置中检查主题和字体。"),
    "app-source": ("发现其他字体来源", "应用自带或下载的字体可能不会随系统更换。"),
    "mixed": ("同时读到了多种字体", "不同文字可能使用不同字体，请确认异常是否仍在。"),
    "system-font": ("仍在读取其他系统字体", "确认字域字体已生效，再重新打开这个应用。"),
    "covered": ("已读到字域字体", "若部分文字仍未变化，应用可能还在使用其他字体。"),
    "inconclusive": ("暂时无法判断", "打开文字异常的页面后，再回字域检查。"),
}


class BudgetExpired(Exception):
    pass


class ProcessDiscoveryError(Exception):
    pass


def _alarm(_signum: int, _frame: Any) -> None:
    raise BudgetExpired


def _safe_name(value: str) -> str:
    return SAFE_NAME_RE.sub("_", value)[:96]


def running_processes(proc_root: Path = Path("/proc"), limit: int = MAX_RUNNING_APPS) -> tuple[dict[str, list[dict[str, str]]], bool]:
    """Read at most a bounded number of cmdlines and group package processes."""
    pids: list[int] = []
    truncated = False
    try:
        with os.scandir(proc_root) as entries:
            for entry in entries:
                if entry.name.isdigit():
                    pids.append(int(entry.name))
                    if len(pids) >= MAX_PID_DIRECTORY_ENTRIES:
                        truncated = True
                        break
    except OSError as error:
        raise ProcessDiscoveryError("无法读取应用进程，请检查 Root 授权") from error

    apps: dict[str, list[dict[str, str]]] = {}
    truncated = truncated or len(pids) > MAX_PID_ENTRIES
    readable = 0
    # /proc enumeration is usually PID-ordered. Prefer recent processes rather
    # than consuming the whole budget on old system daemons.
    for pid_number in sorted(pids, reverse=True)[:MAX_PID_ENTRIES]:
        try:
            with (proc_root / str(pid_number) / "cmdline").open("rb") as stream:
                raw = stream.read(257)
            readable += 1
        except OSError:
            continue
        process_bytes = raw.split(b"\0", 1)[0]
        if len(process_bytes) > 256:
            continue
        process = process_bytes.decode("utf-8", "replace")
        if not PACKAGE_RE.fullmatch(process):
            continue
        package = process.split(":", 1)[0]
        if package not in apps and len(apps) >= limit:
            truncated = True
            break
        rows = apps.setdefault(package, [])
        # A process name can occur more than once during an app restart. Preserve
        # only the first PID for that process name, then prefer the main process.
        if any(row["process"] == process for row in rows):
            continue
        rows.append({"pid": str(pid_number), "process": process})

    if pids and not readable:
        raise ProcessDiscoveryError("无法读取应用进程，请检查 Root 授权")
    for rows in apps.values():
        rows.sort(key=lambda row: (row["process"].partition(":")[0] != row["process"], int(row["pid"])))
    return apps, truncated


def _bounded_process_output(arguments: list[str], seconds: float, cap: int) -> tuple[bytes, int | None, bool]:
    """Capture owned pidof/ps children with a pipe cap and a local deadline."""
    child = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    assert child.stdout is not None
    output = bytearray()
    deadline = time.monotonic() + seconds
    try:
        descriptor = child.stdout.fileno()
        os.set_blocking(descriptor, False)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return bytes(output), None, True
            if not select.select([descriptor], [], [], remaining)[0]:
                return bytes(output), None, True
            try:
                block = os.read(descriptor, min(65536, cap + 1 - len(output)))
            except BlockingIOError:
                continue
            if not block:
                try:
                    code = child.wait(timeout=max(0.001, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    return bytes(output), None, True
                return bytes(output), code, False
            output.extend(block)
            if len(output) > cap:
                return bytes(output[:cap]), None, True
    finally:
        if child.poll() is None:
            child.kill()
        try:
            child.wait(timeout=0.25)
        except subprocess.TimeoutExpired:
            pass
        child.stdout.close()


def selected_processes(package: str, proc_root: Path = Path("/proc")) -> tuple[list[dict[str, str]], bool]:
    """Resolve the requested package before any global process-list cap."""
    candidates: dict[int, str] = {}
    ps_ok = False
    truncated = False
    if proc_root == Path("/proc"):
        try:
            output, _code, limited = _bounded_process_output(["/system/bin/pidof", package], 1.0, 4096)
            truncated = limited
            for token in output.split()[:MAX_SELECTED_PROCESSES]:
                if token.isdigit():
                    candidates[int(token)] = package
        except OSError:
            pass
        try:
            output, code, limited = _bounded_process_output(["/system/bin/ps", "-A", "-o", "PID,ARGS"], 2.0, 1024 * 1024)
            ps_ok = code == 0
            truncated = truncated or limited
            for line in output.decode("utf-8", "replace").splitlines():
                fields = line.split(None, 2)
                if len(fields) < 2 or not fields[0].isdigit():
                    continue
                process = fields[1]
                if PACKAGE_RE.fullmatch(process) and process.split(":", 1)[0] == package:
                    candidates[int(fields[0])] = process
        except OSError:
            pass
    if not candidates and not ps_ok:
        apps, fallback_truncated = running_processes(proc_root, MAX_PID_ENTRIES)
        return apps.get(package, []), truncated or fallback_truncated
    rows: list[dict[str, str]] = []
    for pid, observed in candidates.items():
        try:
            with (proc_root / str(pid) / "cmdline").open("rb") as stream:
                process = stream.read(257).split(b"\0", 1)[0].decode("utf-8", "replace")
            if not PACKAGE_RE.fullmatch(process) or process.split(":", 1)[0] != package:
                continue
        except OSError:
            # Keep the selected app visible as unreadable rather than pretend
            # it is closed. Identity and maps are still required by inspection.
            process = observed
        rows.append({"pid": str(pid), "process": process})
    rows.sort(key=lambda row: (row["process"] != package, int(row["pid"])))
    return rows, truncated


def _finding(category: str, files: list[str] | None = None, detail: str = "") -> dict[str, Any]:
    title, action = GUIDES[category]
    return {"category": category, "title": title, "action": action,
            "detail": _safe_name(detail) if detail else "",
            "files": [_safe_name(name) for name in (files or [])[:MAX_FILES_PER_APP]]}


def _process_identity(proc_root: Path, process: dict[str, str]) -> str | None:
    """Bind the selected package to the same process lifetime around maps reads."""
    try:
        directory = proc_root / process["pid"]
        with (directory / "cmdline").open("rb") as stream:
            command = stream.read(257).split(b"\0", 1)[0]
        if command != process["process"].encode("utf-8"):
            return None
        with (directory / "stat").open("rb") as stream:
            raw = stream.read(4097)
        if len(raw) > 4096:
            return None
        fields = raw.rsplit(b") ", 1)[-1].split()
        token = fields[19]
        return token.decode("ascii") if token.isdigit() else None
    except (OSError, IndexError, KeyError, UnicodeError):
        return None


def _inspect_package(package: str, processes: list[dict[str, str]], payload: PayloadIndex,
                     deadline: float, proc_root: Path = Path("/proc")) -> dict[str, Any]:
    if not len(payload):
        return {"package": package, "verdict": "no-payload", "audited": 0,
                "findings": [_finding("no-payload")], "files": []}
    if not processes:
        return {"package": package, "verdict": "not-running", "audited": 0,
                "findings": [_finding("not-running")], "files": []}

    gathered: dict[tuple[str, str, bool, bool], None] = {}
    unreadable = 0
    memfd = 0
    map_truncated = False
    audited = 0
    for process in processes[:MAX_SELECTED_PROCESSES]:
        if time.monotonic() >= deadline:
            raise BudgetExpired
        pid = process["pid"]
        before = _process_identity(proc_root, process)
        if before is None:
            unreadable += 1
            continue
        report = inspect_process(pid, proc_root / pid / "root", payload, proc_root)
        if report["state"] != "ok" or _process_identity(proc_root, process) != before:
            unreadable += 1
            continue
        audited += 1
        memfd += int(report.get("memfd", 0))
        map_truncated = map_truncated or bool(report.get("truncated"))
        for item in report["items"]:
            gathered[(item["kind"], item["name"], bool(item["staleCache"]),
                      bool(item["routeNotVisible"]))] = None

    kinds = {kind for kind, _name, _stale, _route_missing in gathered}
    stale = [name for _kind, name, is_stale, _route_missing in gathered if is_stale]
    route_missing = [name for _kind, name, _stale, is_missing in gathered if is_missing]
    files = [{"kind": kind, "name": name, "staleCache": is_stale,
              "routeNotVisible": route_missing_item}
             for kind, name, is_stale, route_missing_item in list(gathered)[:MAX_FILES_PER_APP]]

    if audited == 0:
        verdict = "unreadable"
        findings = [_finding("unreadable", detail=f"{unreadable} 个进程不可读")]
    elif not gathered:
        verdict = "inconclusive"
        findings = [_finding("inconclusive", detail=f"共享内存字体段 {memfd} 个")]
    elif stale:
        verdict = "stale-process"
        findings = [_finding("stale-process", stale)]
    elif route_missing:
        verdict = "route-not-visible"
        findings = [_finding("route-not-visible", route_missing)]
    elif "unknown" in kinds:
        verdict = "inconclusive"
        findings = [_finding("inconclusive", [name for kind, name, _, _ in gathered if kind == "unknown"])]
    elif "theme" in kinds:
        verdict = "theme-source"
        findings = [_finding("theme-source", [name for kind, name, _, _ in gathered if kind == "theme"])]
    elif "other" in kinds or "updatable" in kinds:
        verdict = "app-source"
        private_files = [name for kind, name, _, _ in gathered if kind in ("other", "updatable")]
        findings = [_finding("app-source", private_files)]
    elif "stock" in kinds and "payload" in kinds:
        verdict = "mixed"
        findings = [_finding("mixed", [name for kind, name, _, _ in gathered if kind == "stock"])]
    elif "stock" in kinds:
        verdict = "system-font"
        findings = [_finding("system-font", [name for kind, name, _, _ in gathered if kind == "stock"])]
    elif "payload" in kinds:
        verdict = "covered"
        findings = [_finding("covered", [name for kind, name, _, _ in gathered if kind == "payload"])]
    else:
        verdict = "inconclusive"
        findings = [_finding("inconclusive")]

    return {
        "package": package,
        "verdict": verdict,
        "audited": audited,
        "processCount": len(processes),
        "memfdFontSegments": memfd,
        "mapsTruncated": map_truncated,
        "findings": findings,
        "files": files,
    }


def _run(command: str, package_arg: str = "", proc_root: Path = Path("/proc")) -> dict[str, Any]:
    module_text = os.environ.get("MODDIR", "")
    if not module_text or not os.path.isabs(module_text):
        return {"status": "error", "message": "模块目录不可用"}
    module = Path(module_text)
    if command == "locate" and not PACKAGE_RE.fullmatch(package_arg):
        return {"status": "error", "message": "应用包名格式不正确"}

    if hasattr(signal, "SIGALRM"):
        signal.signal(signal.SIGALRM, _alarm)
        signal.alarm(COMMAND_BUDGET_SECONDS)
    try:
        if command == "locate":
            selected, process_truncated = selected_processes(package_arg.split(":", 1)[0], proc_root)
            apps = {package_arg.split(":", 1)[0]: selected}
        else:
            apps, process_truncated = running_processes(proc_root=proc_root)
        if command == "running":
            rows = [{"package": package, "processCount": len(processes)}
                    for package, processes in list(apps.items())[:MAX_RUNNING_APPS]]
            data = {"packages": rows, "truncated": process_truncated or len(apps) > MAX_RUNNING_APPS}
            return {"status": "ok", "data": data}

        payload = PayloadIndex(module)
        def checked(data: dict[str, Any]) -> dict[str, Any]:
            if len(payload) and not payload.current():
                return {"status": "error", "message": "扫描期间字体已切换，请等待应用完成后重试。"}
            return {"status": "ok", "data": data}
        if command == "locate":
            data = _inspect_package(package_arg.split(":", 1)[0], apps.get(package_arg.split(":", 1)[0], []),
                                    payload, time.monotonic() + SCAN_BUDGET_SECONDS, proc_root)
            data["truncated"] = process_truncated
            return checked(data)

        if command != "scan":
            return {"status": "error", "message": "未知诊断命令"}
        if not len(payload):
            return {"status": "ok", "data": {"verdict": "no-payload", "scanned": 0,
                    "covered": 0, "uncertain": 0, "items": [], "truncated": process_truncated}}
        packages = list(apps.items())
        truncated = process_truncated or len(packages) > MAX_SCAN_APPS
        deadline = time.monotonic() + SCAN_BUDGET_SECONDS
        items: list[dict[str, Any]] = []
        covered = 0
        uncertain = 0
        scanned = 0
        for package, processes in packages[:MAX_SCAN_APPS]:
            if time.monotonic() >= deadline:
                truncated = True
                break
            try:
                item = _inspect_package(package, processes, payload, deadline, proc_root)
            except BudgetExpired:
                truncated = True
                break
            scanned += 1
            if item["verdict"] == "covered":
                covered += 1
            elif item["verdict"] in {"inconclusive", "unreadable", "not-running"}:
                uncertain += 1
                if len(items) < MAX_SCAN_RESULTS:
                    items.append(item)
                else:
                    truncated = True
            else:
                if len(items) < MAX_SCAN_RESULTS:
                    items.append(item)
                else:
                    truncated = True
        data = {
            "verdict": "ok",
            "scanned": scanned,
            "covered": covered,
            "uncertain": uncertain,
            "items": items,
            "truncated": truncated or scanned < len(packages),
        }
        return checked(data)
    finally:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    command = args[0] if args else ""
    package = args[1] if len(args) > 1 else ""
    valid_arity = (command in ("running", "scan") and len(args) == 1) or (command == "locate" and len(args) == 2)
    if not valid_arity:
        result: dict[str, Any] = {"status": "error", "message": "诊断参数无效"}
    else:
        try:
            result = _run(command, package)
        except BudgetExpired:
            result = {"status": "error", "message": "扫描超过安全时限，请缩小范围后重试"}
        except ProcessDiscoveryError as error:
            result = {"status": "error", "message": str(error)}
        except Exception as error:
            result = {"status": "error", "message": f"诊断失败：{error.__class__.__name__}"}

    encoded = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    if len(encoded.encode("utf-8")) > MAX_OUTPUT_BYTES:
        encoded = json.dumps({"status": "error", "message": "诊断结果超过安全输出上限，请重试"},
                             ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write(encoded + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
