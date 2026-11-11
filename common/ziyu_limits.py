"""Shared configurable size limits for Ziyu font pipelines.

Reads config/font-import-limit.conf (set by the settings slider) so apply,
composite, clipping, preview and export paths follow the same limit as import.
"""
from __future__ import annotations

import os

DEFAULT_MB = 256
MIN_MB = 32
MAX_MB = 512


def _module_dir() -> str | None:
    return os.environ.get("MODDIR") or os.environ.get("MODULE_DIR")


def limit_mb() -> int:
    value = 0
    module = _module_dir()
    if module:
        try:
            limit_path = os.path.join(module, "config", "tools", "font-import-limit.conf")
            if not os.path.isfile(limit_path):
                limit_path = os.path.join(module, "config", "font-import-limit.conf")
            with open(limit_path, "r", encoding="utf-8") as handle:
                for line in handle:
                    if line.startswith("max_mb="):
                        value = int(line.split("=", 1)[1].strip() or 0)
                        break
        except (OSError, ValueError):
            value = 0
    if value <= 0:
        value = DEFAULT_MB
    return max(MIN_MB, min(MAX_MB, value))


def limit_bytes() -> int:
    return limit_mb() * 1024 * 1024


def limit_bytes_scaled(factor: float = 1.0, floor_bytes: int = 0) -> int:
    return max(floor_bytes, int(limit_bytes() * factor))
