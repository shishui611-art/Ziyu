#!/usr/bin/env python3
"""Strictly read Hybrid Mount runtime status for scoped VFS cleanup."""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any


def module_active(payload: Any, module_id: str) -> bool:
    if not isinstance(payload, dict):
        raise ValueError("runtime status must be a JSON object")
    if isinstance(payload.get("data"), dict):
        payload = payload["data"]
    if payload.get("supported") is not True:
        raise ValueError("Hybrid Mount runtime does not report supported=true")
    modules = payload.get("modules")
    if not isinstance(modules, list):
        raise ValueError("runtime status has no modules array")
    matches = [item for item in modules if isinstance(item, dict) and item.get("id") == module_id]
    if len(matches) != 1:
        raise ValueError(f"expected one runtime record for {module_id}, found {len(matches)}")
    active = matches[0].get("active")
    if not isinstance(active, bool):
        raise ValueError(f"runtime active state for {module_id} is not boolean")
    return active


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module-id", required=True)
    args = parser.parse_args()
    try:
        active = module_active(json.load(sys.stdin), args.module_id)
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        print(f"hybrid-runtime-status-invalid: {exc}", file=sys.stderr)
        return 2
    print("active" if active else "inactive")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
