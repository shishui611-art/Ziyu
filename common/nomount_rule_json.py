#!/usr/bin/env python3
"""Read NoMount's JSON rule list without assuming a particular field schema."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import PurePosixPath
from typing import Any

DELETED_SUFFIX = " (deleted)"


def strip_deleted_suffix(value: str) -> tuple[str, bool]:
    """Strip Linux d_path's trailing ' (deleted)' marker after module replacement."""
    if value.endswith(DELETED_SUFFIX):
        return value[: -len(DELETED_SUFFIX)], True
    return value, False


def unquote(value: str) -> str:
    """Decode percent-encoded paths without the pruned urllib runtime."""
    encoded = value.encode("utf-8")
    decoded = bytearray()
    index = 0
    hex_digits = b"0123456789abcdefABCDEF"
    while index < len(encoded):
        if (
            encoded[index] == ord("%")
            and index + 2 < len(encoded)
            and encoded[index + 1] in hex_digits
            and encoded[index + 2] in hex_digits
        ):
            decoded.append(int(encoded[index + 1 : index + 3], 16))
            index += 3
        else:
            decoded.append(encoded[index])
            index += 1
    return decoded.decode("utf-8", errors="replace")


def inspect_rules(node: Any, target: str, source: str | None) -> tuple[bool, bool]:
    """Return (target_present, exact_pair_present) from nested JSON records."""
    target_present = False
    exact_pair_present = False
    clean_expected_source = strip_deleted_suffix(source)[0] if source is not None else None

    if isinstance(node, dict):
        scalar_values = set()
        for value in node.values():
            if isinstance(value, str):
                scalar_values.add(value)
                scalar_values.add(strip_deleted_suffix(value)[0])
        scalar_keys = {key for key in node if isinstance(key, str)}
        record_strings = scalar_values | scalar_keys
        if target in record_strings:
            target_present = True
            if (
                clean_expected_source is None
                or source in record_strings
                or clean_expected_source in record_strings
            ) and node.get("uid", 0) == 0:
                exact_pair_present = True
        for value in node.values():
            nested_target, nested_pair = inspect_rules(value, target, source)
            target_present |= nested_target
            exact_pair_present |= nested_pair
    elif isinstance(node, list):
        for value in node:
            nested_target, nested_pair = inspect_rules(value, target, source)
            target_present |= nested_target
            exact_pair_present |= nested_pair
    elif isinstance(node, str) and node == target:
        target_present = True
        exact_pair_present = source is None

    return target_present, exact_pair_present


def contains_path_under_root(node: Any, root: str) -> bool:
    """Return whether any rule path equals root or is inside that directory."""
    normalized_root = root.rstrip("/") or "/"

    def matches(value: str) -> bool:
        candidate, _ = strip_deleted_suffix(unquote(value).strip())
        if normalized_root == "/":
            return candidate.startswith("/")
        return candidate == normalized_root or candidate.startswith(normalized_root + "/")

    if isinstance(node, dict):
        return any(
            (isinstance(key, str) and matches(key))
            or (isinstance(value, str) and matches(value))
            or contains_path_under_root(value, normalized_root)
            for key, value in node.items()
        )
    if isinstance(node, list):
        return any(contains_path_under_root(value, normalized_root) for value in node)
    return isinstance(node, str) and matches(node)


def _validate_rule_path(path: str) -> None:
    if (
        not path.startswith("/")
        or any(ord(c) < 32 or c in "|\\" for c in path)
        or str(PurePosixPath(path)) != path
        or ".." in PurePosixPath(path).parts
    ):
        raise ValueError("unsafe-rule-path")


def owned_reload_entries(node: Any, root: str) -> list[tuple[str, str, str, bool]]:
    """Plan explicit NoMount file pairs owned by this module's published tree.

    KernelSU replaces the module directory before post-fs-data on updates,
    so the kernel driver reports unlinked real paths with a trailing ' (deleted)'
    marker. Strip and record that marker while preserving path validation.
    """
    root = root.rstrip("/")
    if not root or not root.startswith("/") or ".." in PurePosixPath(root).parts:
        raise ValueError("invalid-module-root")
    rows: dict[str, tuple[str, str, bool]] = {}

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            target, raw_source = value.get("virtual"), value.get("real")
            if isinstance(raw_source, str):
                source, suffix_deleted = strip_deleted_suffix(raw_source)
                is_deleted = bool(value.get("deleted", False)) or suffix_deleted
                if source.startswith(root + "/"):
                    if not isinstance(target, str) or not target.startswith("/"):
                        raise ValueError("missing-virtual-path")
                    _validate_rule_path(target)
                    _validate_rule_path(source)
                    if value.get("uid", 0) != 0 or value.get("kind", "file") != "file":
                        raise ValueError("scoped-or-nonfile-rule")
                    scope = "public" if value.get("public", False) else "private"
                    existing = rows.get(target)
                    if existing is not None and (existing[0] != source or existing[1] != scope):
                        raise ValueError("duplicate-rule-target")
                    rows[target] = (source, scope, is_deleted)
            for child in value.values():
                if isinstance(child, (dict, list)):
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(node)
    if len(rows) > 4096:
        raise ValueError("too-many-owned-rules")
    return [(target, src, scope, deleted) for target, (src, scope, deleted) in sorted(rows.items())]


def owned_reload_plan(node: Any, root: str) -> list[tuple[str, str]]:
    return [(target, source) for target, source, _scope, _deleted in owned_reload_entries(node, root)]


def suite_rules(text: str) -> list[dict]:
    """Prism nm list has no JSON switch; require its complete, exact row grammar."""
    rows = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(
            r"(/.+?)(?: -> (/.*?)| \((whiteout|virtual dir)\))( \(public\))?(?: \[UID: ([0-9]+)\])?",
            line,
        )
        if not match:
            raise ValueError("unrecognized-suite-rule")
        target, raw_source, kind, public, uid = match.groups()
        source = None
        deleted = False
        if raw_source is not None:
            source, deleted = strip_deleted_suffix(raw_source)
        for path in (target, source):
            if path is not None and (
                str(PurePosixPath(path)) != path
                or ".." in PurePosixPath(path).parts
                or any(ord(c) < 32 or c in "|\\" for c in path)
            ):
                raise ValueError("unsafe-suite-rule")
        rows.append(
            {
                "virtual": target,
                "real": source,
                "deleted": deleted,
                "public": bool(public),
                "kind": kind or "file",
                "uid": int(uid or 0),
            }
        )
        if len(rows) > 16384:
            raise ValueError("too-many-suite-rules")
    return rows


def collect_targets(node: Any) -> set[str]:
    targets: set[str] = set()

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            virtual = value.get("virtual")
            if isinstance(virtual, str):
                targets.add(virtual)
            for child in value.values():
                if isinstance(child, (dict, list)):
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(node)
    return targets


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "normalize-suite":
        try:
            print(json.dumps(suite_rules(sys.stdin.read()), ensure_ascii=False))
            return 0
        except ValueError:
            return 2
    if argv[1] == "verify-targets-absent" and len(argv) in (2, 4):
        if len(argv) == 4:
            root = unquote(argv[2]) if "%" in argv[2] else argv[2]
            plan_path = argv[3]
        else:
            root = unquote(os.environ.get("ZIYU_NOMOUNT_JSON_ROOT", ""))
            plan_path = unquote(os.environ.get("ZIYU_NOMOUNT_JSON_SOURCE", ""))
        if not root or not plan_path:
            return 2
        try:
            payload = json.load(sys.stdin)
        except (json.JSONDecodeError, UnicodeDecodeError):
            print("invalid", end="")
            return 0
        if not isinstance(payload, (dict, list)):
            print("invalid", end="")
            return 0
        try:
            plan_lines = open(plan_path, "r", encoding="utf-8").read().splitlines()
        except OSError:
            print("invalid", end="")
            return 0
        active_targets = collect_targets(payload)
        for line in plan_lines:
            if not line.strip():
                continue
            target = line.split("|", 1)[0]
            if target in active_targets:
                print("present", end="")
                return 0
        if contains_path_under_root(payload, root):
            print("present", end="")
            return 0
        print("absent", end="")
        return 0

    if len(argv) not in (2, 3, 4) or argv[1] not in {
        "target",
        "pair",
        "root",
        "owned-plan",
        "owned-plan-v2",
        "owned-plan-v3",
    }:
        print("usage: nomount_rule_json.py target | pair | root | owned-plan", file=sys.stderr)
        return 2
    if argv[1] in {"root", "owned-plan", "owned-plan-v2", "owned-plan-v3"} and len(argv) == 3:
        root = argv[2]
    elif argv[1] in {"root", "owned-plan", "owned-plan-v2", "owned-plan-v3"} and len(argv) == 2:
        root = unquote(os.environ.get("ZIYU_NOMOUNT_JSON_ROOT", ""))
    elif len(argv) == 2:
        target = unquote(os.environ.get("ZIYU_NOMOUNT_JSON_TARGET", ""))
        source_value = os.environ.get("ZIYU_NOMOUNT_JSON_SOURCE")
        source = unquote(source_value) if source_value is not None and argv[1] == "pair" else None
        if not target or (argv[1] == "pair" and source is None):
            return 2
    elif argv[1] == "target" and len(argv) == 3:
        target = argv[2]
        source = None
    elif argv[1] == "pair" and len(argv) == 4:
        target = argv[2]
        source = argv[3]
    else:
        return 2
    if argv[1] in {"root", "owned-plan", "owned-plan-v2", "owned-plan-v3"} and not root:
        return 2

    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        print("invalid", end="")
        return 0
    if not isinstance(payload, (dict, list)):
        print("invalid", end="")
        return 0

    if argv[1] in {"owned-plan", "owned-plan-v2", "owned-plan-v3"}:
        try:
            entries = owned_reload_entries(payload, root)
        except ValueError:
            return 2
        if argv[1] == "owned-plan":
            output = "".join(f"{t}|{s}\n" for t, s, _sc, _d in entries)
        elif argv[1] == "owned-plan-v2":
            output = "".join(f"{t}|{s}|{sc}\n" for t, s, sc, _d in entries)
        else:
            output = "".join(f"{t}|{s}|{sc}|{1 if d else 0}\n" for t, s, sc, d in entries)
        sys.stdout.buffer.write(output.encode("utf-8"))
        return 0

    if argv[1] == "root":
        print("present" if contains_path_under_root(payload, root) else "absent", end="")
        return 0

    present, exact = inspect_rules(payload, target, source)
    if argv[1] == "target":
        state = "present" if present else "absent"
    elif exact:
        state = "exact"
    elif present:
        state = "conflict"
    else:
        state = "absent"
    print(state, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
