#!/usr/bin/env python3
"""Read NoMount's JSON rule list without assuming a particular field schema."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import PurePosixPath
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
            if (source is None or source in record_strings) and node.get('uid', 0) == 0:
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


def owned_reload_plan(node: Any, root: str) -> list[tuple[str, str]]:
    """Plan only explicit NoMount file pairs owned by this module's published tree."""
    root = root.rstrip('/')
    if not root or not root.startswith('/') or '..' in PurePosixPath(root).parts:
        raise ValueError('invalid-module-root')
    rows: dict[str, str] = {}

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            target, source = value.get('virtual'), value.get('real')
            if isinstance(source, str) and source.startswith(root + '/'):
                if not isinstance(target, str) or not target.startswith('/'):
                    raise ValueError('missing-virtual-path')
                for path in (source, target):
                    if any(ord(c) < 32 or c in '|\\' for c in path) or str(PurePosixPath(path)) != path or '..' in PurePosixPath(path).parts:
                        raise ValueError('unsafe-rule-path')
                if target in rows and rows[target] != source:
                    raise ValueError('duplicate-rule-target')
                rows[target] = source
            for child in value.values():
                if isinstance(child, (dict, list)):
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(node)
    if len(rows) > 4096:
        raise ValueError('too-many-owned-rules')
    return sorted(rows.items())


def suite_rules(text: str) -> list[dict]:
    """Prism nm list has no JSON switch; require its complete, exact row grammar."""
    rows = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r'(/.+?)(?: -> (/.*?)| \((whiteout|virtual dir)\))( \(public\))?(?: \[UID: ([0-9]+)\])?', line)
        if not match:
            raise ValueError('unrecognized-suite-rule')
        target, source, kind, public, uid = match.groups()
        for path in (target, source):
            if path is not None and (str(PurePosixPath(path)) != path or '..' in PurePosixPath(path).parts
                                     or any(ord(c) < 32 or c in '|\\' for c in path)):
                raise ValueError('unsafe-suite-rule')
        rows.append({'virtual': target, 'real': source, 'public': bool(public),
                     'kind': kind or 'file', 'uid': int(uid or 0)})
        if len(rows) > 16384:
            raise ValueError('too-many-suite-rules')
    return rows


def reload_metadata(node: Any, target: str, source: str) -> str:
    records = []
    def visit(value):
        if isinstance(value, dict):
            if value.get('virtual') == target and value.get('real') == source:
                if value.get('uid', 0) != 0 or value.get('kind', 'file') != 'file':
                    raise ValueError('scoped-or-nonfile-rule')
                records.append('public' if value.get('public', False) else 'private')
            for child in value.values():
                if isinstance(child, (dict, list)):
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(node)
    if len(records) != 1:
        raise ValueError('ambiguous-rule-metadata')
    return records[0]


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == 'normalize-suite':
        try:
            print(json.dumps(suite_rules(sys.stdin.read()), ensure_ascii=False))
            return 0
        except ValueError:
            return 2
    if len(argv) not in (2, 3, 4) or argv[1] not in {"target", "pair", "root", "owned-plan", "owned-plan-v2"}:
        print("usage: nomount_rule_json.py target | pair | root | owned-plan", file=sys.stderr)
        return 2
    if argv[1] in {"root", "owned-plan", "owned-plan-v2"} and len(argv) == 3:
        root = argv[2]
    elif argv[1] in {"root", "owned-plan", "owned-plan-v2"} and len(argv) == 2:
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
    if argv[1] in {"root", "owned-plan", "owned-plan-v2"} and not root:
        return 2

    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        print("invalid", end="")
        return 0
    if not isinstance(payload, (dict, list)):
        print("invalid", end="")
        return 0

    if argv[1] in {'owned-plan', 'owned-plan-v2'}:
        try:
            rows = owned_reload_plan(payload, root)
        except ValueError:
            return 2
        # Shell transaction plans use literal UTF-8/LF on every host.
        try:
            output = ''.join(f'{target}|{source}' +
                             ('|' + reload_metadata(payload, target, source) if argv[1] == 'owned-plan-v2' else '') + '\n'
                             for target, source in rows)
        except ValueError:
            return 2
        sys.stdout.buffer.write(output.encode('utf-8'))
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
