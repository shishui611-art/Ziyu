#!/usr/bin/env python3
"""Read NoMount's JSON rule list without assuming a particular field schema."""
from __future__ import annotations

import json
import os
import sys
from typing import Any


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

    if isinstance(node, dict):
        scalar_values = {value for value in node.values() if isinstance(value, str)}
        scalar_keys = {key for key in node if isinstance(key, str)}
        record_strings = scalar_values | scalar_keys
        if target in record_strings:
            target_present = True
            if source is None or source in record_strings:
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
        candidate = unquote(value).strip()
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


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3, 4) or argv[1] not in {"target", "pair", "root"}:
        print("usage: nomount_rule_json.py target | pair | root", file=sys.stderr)
        return 2
    if argv[1] == "root" and len(argv) == 3:
        root = argv[2]
    elif argv[1] == "root" and len(argv) == 2:
        root = unquote(os.environ.get("LUOSHU_NOMOUNT_JSON_ROOT", ""))
    elif len(argv) == 2:
        target = unquote(os.environ.get("LUOSHU_NOMOUNT_JSON_TARGET", ""))
        source_value = os.environ.get("LUOSHU_NOMOUNT_JSON_SOURCE")
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
    if argv[1] == "root" and not root:
        return 2

    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        print("invalid", end="")
        return 0
    if not isinstance(payload, (dict, list)):
        print("invalid", end="")
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
