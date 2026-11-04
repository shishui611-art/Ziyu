#!/usr/bin/env python3
"""User restrictions on existing font candidates. Never grants replacement rights."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re

SCHEMA = "ziyu-font-slot-overrides-v1"
KEYS = ("includeOnly", "excludePaths", "excludePathPrefixes")
VENDOR_CLOCK = re.compile(r"^(?:RedMagicDigit|ZDigit)(?:VF)?(?:[-_.][A-Za-z0-9_.-]+)?\.(?:ttf|otf|ttc|otc)$", re.I)


def logical_path(value: str, *, prefix: bool = False) -> str:
    if not isinstance(value, str) or len(value) > 1024 or any(ord(c) < 32 for c in value) or "\\" in value:
        raise ValueError("字体槽位路径无效")
    if not value.startswith("/") or ".." in value.split("/"):
        raise ValueError("请填写完整字体槽位路径，不支持相对路径或 ..")
    path = posixpath.normpath(value)
    for part in ("product", "system_ext", "vendor", "odm"):
        if path.startswith(f"/system/{part}/"):
            path = path[len("/system"):]
    if not re.fullmatch(r"/[A-Za-z][A-Za-z0-9_]*/fonts(?:/[^\x00-\x1f]*)?", path):
        raise ValueError("规则只支持系统分区下的 fonts 目录")
    if not prefix and not path.lower().endswith((".ttf", ".otf", ".ttc", ".otc")):
        raise ValueError("槽位应指向 TTF、OTF 或 TTC 字体文件")
    return path.rstrip("/") + "/" if prefix else path


def validate(value: object) -> dict:
    if (not isinstance(value, dict) or value.get("schema") != SCHEMA
            or type(value.get("revision")) is not int or value.get("revision") != 1):
        raise ValueError("字体槽位规则格式或版本无效")
    if set(value) - {"schema", "revision", *KEYS}:
        raise ValueError("字体槽位规则包含未知字段")
    result = {"schema": SCHEMA, "revision": 1}
    for key in KEYS:
        rows = value.get(key, [])
        if not isinstance(rows, list) or len(rows) > 512:
            raise ValueError("每组最多保存 512 条字体槽位规则")
        result[key] = sorted({logical_path(row, prefix=key == "excludePathPrefixes") for row in rows})
    return result


def load(path: Path) -> dict:
    # exists() follows links: a dangling link must not silently remove exclusions.
    if path.is_symlink():
        raise ValueError("字体槽位规则文件无效，请在设置中重新保存")
    if not path.exists():
        return validate({"schema": SCHEMA, "revision": 1})
    if not path.is_file() or path.stat().st_size > 65536:
        raise ValueError("字体槽位规则文件无效，请在设置中重新保存")
    return validate(json.loads(path.read_text(encoding="utf-8")))


def digest(rules: dict) -> str:
    return hashlib.sha256(json.dumps(validate(rules), sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def exclusion(path: str, rules: dict) -> str:
    if not restricted(rules):
        return ""
    try:
        path = logical_path(path)
    except ValueError:
        return "unsupported-logical-path"
    if path in rules["excludePaths"] or any(path.startswith(p) for p in rules["excludePathPrefixes"]):
        return "user-slot-excluded"
    if rules["includeOnly"] and path not in rules["includeOnly"]:
        return "outside-user-slot-allowlist"
    return ""


def restricted(rules: dict) -> bool:
    return any(rules[key] for key in KEYS)


def write(path: Path, value: dict) -> None:
    temp = path.with_name(path.name + f".tmp.{os.getpid()}")
    try:
        temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temp.chmod(0o600)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def selectable_fonts(module: Path, rules: dict) -> dict:
    """Use cached topology and the existing policy; do not read font binaries."""
    from font_role_shadow import build, _coloros_groups
    config = module / "config"
    topology = json.loads((config / "device_font_topology.json").read_text(encoding="utf-8"))
    groups = _coloros_groups(config / "coloros_slot_groups.conf")
    _, baseline = build(topology, groups)
    options: dict[str, dict] = {}
    for raw_path, slot in baseline["slots"].items():
        if slot["action"] not in {"replace", "conditional", "specialized"}:
            continue
        try:
            path = logical_path(raw_path)
        except ValueError:
            continue
        options[path] = {"path": path, "name": posixpath.basename(path), "role": slot["role"]}
    return {"status": "ok", "rules": rules, "fonts": sorted(options.values(), key=lambda item: (item["role"], item["name"].lower(), item["path"]))}


def command(module: Path, action: str, encoded: str = "", expected: str = "") -> dict:
    config = module / "config"
    path = config / "font-slot-overrides.json"
    if action == "validate":
        return {"status": "ok", "digest": digest(load(path))}
    if action == "identity":
        return digest(load(path))
    if action == "get":
        try:
            return {"status": "ok", "rules": load(path)}
        except (OSError, ValueError) as error:
            return {"status": "error", "message": str(error), "rules": validate({"schema": SCHEMA, "revision": 1})}
    if action == "list":
        return selectable_fonts(module, load(path))
    if len(encoded) > 90000:
        raise ValueError("规则内容过长")
    rules = validate(json.loads(base64.b64decode(encoded, validate=True)))
    # Reuse the established classifier; no font scan, copying or mounting occurs.
    from font_role_shadow import build, _coloros_groups
    topology = json.loads((config / "device_font_topology.json").read_text(encoding="utf-8"))
    groups = _coloros_groups(config / "coloros_slot_groups.conf")
    roles, plan = build(topology, groups, rules)
    try:
        current = load(path)
    except (OSError, ValueError):
        current = None
    previous_count = build(topology, groups, current)[1]["summary"]["candidateCount"] if current is not None else None
    current_digest = digest(current) if current is not None else "invalid-current-rules"
    token = hashlib.sha256((digest(rules) + current_digest + json.dumps(topology, sort_keys=True) + json.dumps(sorted(groups))).encode()).hexdigest()
    if action == "save":
        if expected != token:
            raise ValueError("系统字体信息或规则已变化，请重新预览后保存")
        for name in ("switch_task.conf", "mix_task.conf", "axes_task.conf"):
            task = config / name
            if task.is_file() and re.search(r"^state=(queued|running)$", task.read_text(), re.M):
                raise ValueError("字体任务正在运行，请完成后再保存规则")
        # Preserve last valid config before replacing. Corrupt current bytes never
        # overwrite the valid backup. Only explicit Save repairs corrupt config.
        try:
            previous = load(path)
        except (OSError, ValueError):
            previous = None
        if previous is not None:
            write(config / "font-slot-overrides.previous.json", previous)
        write(path, rules)
        write(config / "device_font_roles.json", roles)
        write(config / "device_font_shadow_plan.json", plan)
    elif action != "preview":
        raise ValueError("未知槽位规则操作")
    retained = set()
    for raw_path, slot in roles["slots"].items():
        if not {"user-slot-excluded", "outside-user-slot-allowlist"}.intersection(slot["reasons"]):
            continue
        try:
            retained.add(logical_path(raw_path))
        except ValueError:
            continue
    return {"status": "ok", "rules": rules, "token": token,
            "before": previous_count, "after": plan["summary"]["candidateCount"],
            "retained": len(retained),
            "message": "规则已保存。当前字体保持原状，下次确认应用字体时使用新规则。" if action == "save" else "仅预览规则，不改变当前字体"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", type=Path)
    parser.add_argument("action", choices=("get", "list", "validate", "identity", "preview", "save"))
    parser.add_argument("encoded", nargs="?", default="")
    parser.add_argument("expected", nargs="?", default="")
    args = parser.parse_args()
    try:
        if args.action == "save":
            import fcntl
            with (args.module / "config/.font-slot-overrides.lock").open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                result = command(args.module, args.action, args.encoded, args.expected)
        else:
            result = command(args.module, args.action, args.encoded, args.expected)
        print(result if args.action == "identity" else json.dumps(result, ensure_ascii=False))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({"status": "error", "message": str(error)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
