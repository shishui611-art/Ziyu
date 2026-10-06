#!/usr/bin/env python3
"""Contract tests for strict Hybrid Mount per-module status parsing."""

from __future__ import annotations

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "common"))
from hybrid_mount_runtime import module_active  # noqa: E402


class HybridRuntimeStatusTests(unittest.TestCase):
    def test_active_luoshu_record(self) -> None:
        self.assertTrue(module_active({"supported": True, "modules": [{"id": "LuoShu", "active": True, "eligible": True}]}, "LuoShu"))

    def test_inactive_luoshu_record(self) -> None:
        self.assertFalse(module_active({"supported": True, "modules": [{"id": "LuoShu", "active": False, "eligible": True}]}, "LuoShu"))

    def test_wrapped_status_payload(self) -> None:
        payload = {"status": "ok", "data": {"supported": True, "modules": [{"id": "LuoShu", "active": False}]}}
        self.assertFalse(module_active(payload, "LuoShu"))

    def test_unsupported_runtime_is_not_safe_to_unload(self) -> None:
        with self.assertRaisesRegex(ValueError, "supported=true"):
            module_active({"supported": False, "modules": [{"id": "LuoShu", "active": False}]}, "LuoShu")

    def test_missing_or_duplicate_module_record_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "found 0"):
            module_active({"supported": True, "modules": []}, "LuoShu")
        with self.assertRaisesRegex(ValueError, "found 2"):
            module_active({"supported": True, "modules": [{"id": "LuoShu", "active": False}, {"id": "LuoShu", "active": False}]}, "LuoShu")

    def test_ambiguous_active_value_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "not boolean"):
            module_active({"supported": True, "modules": [{"id": "LuoShu", "active": "false"}]}, "LuoShu")


if __name__ == "__main__":
    unittest.main(verbosity=2)
