#!/usr/bin/env python3
"""Read NoMount's JSON rule list without assuming a particular field schema."""
from __future__ import annotations

import json
import os
import sys
from typing import Any
from urllib.parse import unquote


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


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3, 4) or argv[1] not in {"target", "pair"}:
        print("usage: nomount_rule_json.py target | pair", file=sys.stderr)
        return 2
    if len(argv) == 2:
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

    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        print("invalid", end="")
        return 0
    if not isinstance(payload, (dict, list)):
        print("invalid", end="")
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
