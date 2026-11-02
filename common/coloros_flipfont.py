#!/usr/bin/env python3
"""Read-only ColorOS theme-font route hint; never treats a link as content proof."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import posixpath
import stat
import sys
import time
from typing import Any


SCHEMA = "ziyu-coloros-flipfont-v1"
UI_PREFIXES = ("oplusosui-", "colorosui-")
FONT_SUFFIXES = {".ttf", ".otf", ".ttc"}
SYSTEM_FONT_PREFIX = "/system/fonts/"
ENTRY_SCAN_LIMIT = 128


def _under_root(root: Path, *parts: str) -> Path:
    """Resolve a lexical path under a supplied namespace without following links."""
    current = root
    for part in parts:
        current = current / part
        try:
            mode = current.lstat().st_mode
        except FileNotFoundError:
            raise
        if not stat.S_ISDIR(mode):
            raise NotADirectoryError(str(current))
    return current


def inspect(module_root: Path, namespace_root: Path = Path("/")) -> dict[str, Any]:
    bridge = "/data/format_unclear/font"
    result: dict[str, Any] = {
        "schema": SCHEMA,
        "state": "unavailable",
        "namespace": "current-process",
        "bridgePath": bridge,
        "observedAt": int(time.time()),
        "verdict": "not-observed",
        "entries": [],
        "contentVerified": False,
        "limitation": "链接存在或链接文本均不能证明目标内容可信，也不能证明 PID 1 实际加载了该字体。",
    }
    try:
        directory = _under_root(namespace_root, "data", "format_unclear", "font")
        with os.scandir(directory) as iterator:
            candidates = []
            truncated = False
            for index, entry in enumerate(iterator):
                if index >= ENTRY_SCAN_LIMIT:
                    truncated = True
                    break
                candidates.append(entry)
    except (FileNotFoundError, NotADirectoryError, PermissionError, OSError) as error:
        result["reason"] = type(error).__name__
        return result

    entries: list[dict[str, Any]] = []
    external_candidates = 0
    system_candidates = 0
    for entry in candidates:
        name = entry.name
        lowered = name.lower()
        if not lowered.startswith(UI_PREFIXES) or Path(name).suffix.lower() not in FONT_SUFFIXES:
            continue
        try:
            metadata = entry.stat(follow_symlinks=False)
        except OSError:
            entries.append({"name": name, "kind": "unreadable-entry", "contentVerified": False})
            continue

        item: dict[str, Any] = {"name": name, "contentVerified": False}
        if stat.S_ISLNK(metadata.st_mode):
            item["kind"] = "symlink"
            item["resolution"] = "not-followed"
            try:
                raw_target = os.readlink(entry.path)
                item["targetText"] = raw_target
                lexical = (posixpath.normpath(raw_target) if raw_target.startswith("/") else
                           posixpath.normpath(posixpath.join(bridge, raw_target)))
                if lexical.startswith(SYSTEM_FONT_PREFIX):
                    item["routeTextClass"] = "system-font-path-candidate"
                    system_candidates += 1
                else:
                    item["routeTextClass"] = "external-path-candidate"
                    external_candidates += 1
            except OSError:
                item["routeTextClass"] = "link-text-unreadable"
        elif stat.S_ISREG(metadata.st_mode):
            item.update({"kind": "regular-file", "sizeBytes": int(metadata.st_size),
                         "routeTextClass": "external-file-candidate"})
            external_candidates += 1
        else:
            item["kind"] = "other-object"
        entries.append(item)

    result["state"] = "ready"
    result["entries"] = entries
    result["entryScanLimit"] = ENTRY_SCAN_LIMIT
    result["entryScanTruncated"] = truncated
    if external_candidates:
        result["verdict"] = "external-route-candidate"
    elif system_candidates:
        result["verdict"] = "system-path-text-only"
    elif entries:
        result["verdict"] = "entry-without-route-proof"
    else:
        result["verdict"] = "no-ui-entries"
    return result


def _write_report(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    try:
        temporary.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("status", "hint"))
    parser.add_argument("--module-root", type=Path, required=True)
    parser.add_argument("--namespace-root", type=Path, default=Path("/"),
                        help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    payload = inspect(args.module_root, args.namespace_root)
    if args.command == "status":
        print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
        return 0

    _write_report(args.module_root / "logs" / "coloros-flipfont.json", payload)
    if payload["verdict"] == "external-route-candidate":
        print("ColorOS 字体桥接目录出现外部路由候选；仅为目录/链接元数据提示，需看 PID 1 内容与加载路径校验。")
    elif payload["entries"]:
        print("检测到 ColorOS UI 字体桥接条目；链接路径本身不能证明目标内容或系统实际加载路径。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
