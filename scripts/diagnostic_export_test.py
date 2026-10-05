#!/usr/bin/env python3
"""Execute the App's actual export command against an isolated module fixture."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"


class DiagnosticExportTest(unittest.TestCase):
    def test_export_contains_original_failure_after_work_cache_is_gone(self):
        with tempfile.TemporaryDirectory(prefix="ziyu-diagnostic-") as temporary:
            root = Path(temporary)
            module = root / "module"
            (module / "config").mkdir(parents=True)
            (module / "logs/font-diagnostics").mkdir(parents=True)
            (module / "module.prop").write_text("version=v1.0.0\nversionCode=100\n", encoding="utf-8")
            (module / "config/axes_task.conf").write_text(
                "task=mix-repro-123\nstate=failed\ncjk=测试字体\ncjkAxes=wght=400\ncjkMode=fixed\n", encoding="utf-8")
            failure = {"errorCode": "FONT_VARIATION_ASSERTION", "taskId": "mix-repro-123",
                       "effectiveWeight": 400, "generatedWeight": "100", "traceback": "materialize\nAssertionError: packed delta count"}
            (module / "logs/font-diagnostics/mix-repro-123-cjk-100.json").write_text(json.dumps(failure), encoding="utf-8")
            (module / "logs/fontswitch.log").write_text("[MIX_PROCESS] task=mix-repro-123 exit_code=13\n", encoding="utf-8")
            props = root / "getprop"
            props.write_text('#!/bin/sh\ncase "$1" in ro.product.model) echo PLK110;; ro.build.version.sdk) echo 37;; esac\n', encoding="utf-8")
            props.chmod(0o755)
            kotlin = (ROOT / "android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/logs/DiagnosticExportUi.kt").read_text(encoding="utf-8")
            command = textwrap.dedent(kotlin.split('val command = """', 1)[1].split('""".trimIndent()', 1)[0]).replace("${'$'}", "$")
            command = command.replace("MOD=/data/adb/modules/LuoShu", 'MOD="$TEST_ROOT/module"')
            command = command.replace("OUT_DIR=/sdcard/LuoShu/reports", 'OUT_DIR="$TEST_ROOT/reports"')
            script = root / "export.sh"
            script.write_text('cd "$TEST_ROOT"\nPATH="$PWD:$PATH"\n' + command, encoding="utf-8")
            environment = os.environ.copy()
            environment["TEST_ROOT"] = root.as_posix()
            run = subprocess.run([BASH, str(script)], env=environment, capture_output=True, text=True, timeout=90)
            self.assertEqual(run.returncode, 0, run.stderr)
            reports = list((root / "reports").glob("Ziyu-diagnostic-*.txt"))
            self.assertEqual(len(reports), 1)
            report = reports[0].read_text(encoding="utf-8")
            for value in ("report=ziyu-diagnostic-v2", "deviceModel=PLK110", "androidSdk=37", "cjk=测试字体",
                          "cjkAxes=wght=400", "FONT_VARIATION_ASSERTION", "packed delta count", "exit_code=13", "mix-repro-123"):
                self.assertIn(value, report)
            self.assertIn("[config:switch_task.conf]\nunavailable", report)
            self.assertNotIn("getprop ro.serialno", command)
            self.assertNotIn("cat /proc", command)
            self.assertIn("font names, paths", report)


if __name__ == "__main__":
    unittest.main()
