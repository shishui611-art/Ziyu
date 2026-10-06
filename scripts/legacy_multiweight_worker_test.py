#!/usr/bin/env python3
"""Exercise the real legacy worker across all nine weights without mounting fonts."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"


class LegacyMultiweightWorkerTest(unittest.TestCase):
    def test_fixed_weight_worker_keeps_warning_in_completed_task(self):
        engine = ROOT / "common/legacy_v14_4/v142_weighted_mix.sh"
        definitions = engine.read_text(encoding="utf-8").split('\ncase "${1:-config}" in', 1)[0]
        with tempfile.TemporaryDirectory(prefix="ziyu-fixed-") as temporary:
            root = Path(temporary)
            # Production creates the task work root before launching parallel
            # role jobs; each child redirects its log there before it starts.
            (root / "job").mkdir()
            (root / "functions.sh").write_text(definitions, encoding="utf-8")
            module = root / "module"
            (module / "config").mkdir(parents=True)
            (module / "common").mkdir()
            router = module / "common/legacy_v14_4/mix_router.sh"
            router.parent.mkdir(parents=True)
            router.write_text('#!/bin/sh\n[ "${1:-}" = finalize ] || exit 2\nexit 0\n', encoding="utf-8")
            router.chmod(0o755)
            (module / "logs").mkdir()
            task = module / "config/axes_task.conf"
            task.write_text("task=fixed-test\nstate=running\ncjk=CJK\nlatin=Latin\ndigit=Digit\n"
                            "cjkAxes=wght=400\nlatinAxes=wght=400\ndigitAxes=wght=400\n"
                            f"root={root.as_posix()}/job\n", encoding="utf-8")
            (root / "source.ttf").write_bytes(b"fixture variable font")
            (root / "instance.sh").write_text(
                '#!/bin/sh\nshift\nwhile [ "$#" -gt 0 ]; do\ncase "$1" in '
                '--input) input="$2";; --output) output="$2";; esac\nshift 2\ndone\ncp "$input" "$output"\n'
                'printf \'{"variationFallbacks":[{"characters":"窒"}],"warning":"窒在 400 字重无法处理，已保留原始轮廓"}\\n\'\n', encoding="utf-8")
            (root / "instance.sh").chmod(0o755)
            (module / "common/font_mix.sh").write_text(
                '#!/bin/sh\nprintf "task=child\\nstate=success\\nmessage=字体已准备，重启后生效\\n" >"$MODDIR/config/mix_task.conf"\n'
                'printf \'{"status":"ok","data":{"task":"child"}}\\n\'\n', encoding="utf-8")
            (root / "test.sh").write_text(
                'cd "$TEST_ROOT"\nMODDIR="$PWD/module"\n. ./functions.sh\n'
                'find_best_source() { echo "$PWD/source.ttf"; }\n'
                'font_validate() { FONT_CHECK_VARIABLE=true; FONT_CHECK_FORMAT=TTF; return 0; }\n'
                'PYBIN="$PWD/instance.sh"\nworker fixed-test\n', encoding="utf-8")
            environment = os.environ.copy()
            environment["TEST_ROOT"] = root.as_posix()
            run = subprocess.run([BASH, str(root / "test.sh")], env=environment,
                                 capture_output=True, text=True, timeout=90)
            state = task.read_text(encoding="utf-8")
            self.assertEqual(run.returncode, 0, state + run.stderr)
            self.assertIn("state=success", state)
            self.assertIn("窒在 400 字重无法处理，已保留原始轮廓", state)
            self.assertTrue((module / "logs/font-diagnostics/fixed-test-cjk-400.json").exists())
            self.assertFalse((root / "job").exists())



if __name__ == "__main__":
    unittest.main()
