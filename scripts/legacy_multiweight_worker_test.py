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
    def test_all_weights_keep_selected_families_and_reuse_composite(self):
        engine = ROOT / "common/legacy_v14_4/v143_auto_multiweight_mix.sh"
        definitions = engine.read_text(encoding="utf-8").split('\ncase "${1:-config}" in', 1)[0]
        with tempfile.TemporaryDirectory(prefix="ziyu-worker-") as temporary:
            root = Path(temporary)
            (root / "functions.sh").write_text(definitions, encoding="utf-8")
            module = root / "module"
            (module / "common").mkdir(parents=True)
            (module / "config").mkdir()
            (module / "logs").mkdir()
            fonts = root / "public/fonts"
            fonts.mkdir(parents=True)
            for family in ("CJK", "Latin", "Digit"):
                (fonts / f"{family}-Regular.ttf").write_bytes(b"font fixture: " + family.encode())
            task = module / "config/axes_task.conf"
            task.write_text(
                "task=regression\nstate=running\ncjk=CJK\nlatin=Latin\ndigit=Digit\n"
                "cjkAxes=wght=400\nlatinAxes=wght=400\ndigitAxes=wght=400\n"
                "cjkMode=fixed\nlatinMode=auto\ndigitMode=auto\n"
                f"root={root.as_posix()}/job\nstarted=1\n", encoding="utf-8")
            (module / "common/luoshu_composite.sh").write_text(
                '#!/bin/sh\nwhile [ "$#" -gt 0 ]; do\n'
                'case "$1" in --cjk) input="$2";; --output) output="$2";; esac\n'
                'shift 2\ndone\ncp "$input" "$output"\n'
                'echo compiled >> "$MODDIR/compiled.txt"\necho "{}"\n', encoding="utf-8")
            (module / "common/font_manager.sh").write_text(
                '#!/bin/sh\nfor font in "$LUOSHU_PUBLIC_DIR"/fonts/*; do\n'
                'basename "$font" >> "$MODDIR/outputs.txt"\ndone\n'
                "printf '{\"status\":\"ok\"}\\n'\n", encoding="utf-8")
            (root / "test.sh").write_text(
                'cd "$TEST_ROOT"\nMODDIR="$PWD/module"\n'
                'LUOSHU_PUBLIC_DIR="$PWD/public"\n. ./functions.sh\n'
                'detect_font_family() { printf "%s\\n" "${1%-*}"; }\n'
                'detect_font_weight() { echo regular; }\n'
                'is_variable_font() { return 1; }\n'
                'font_validate() { FONT_CHECK_VARIABLE=false; FONT_CHECK_FORMAT=TTF; test -s "$1"; }\n'
                'worker regression\n', encoding="utf-8")
            environment = os.environ.copy()
            environment["TEST_ROOT"] = root.as_posix()
            run = subprocess.run([BASH, str(root / "test.sh")], env=environment,
                                 capture_output=True, text=True, timeout=180)
            state = task.read_text(encoding="utf-8")
            self.assertEqual(run.returncode, 0, state + run.stderr)
            self.assertIn("state=success\n", state)
            config = (module / "config/font_mix.conf").read_text(encoding="utf-8")
            for field, family in (("cjk", "CJK"), ("latin", "Latin"), ("digit", "Digit")):
                self.assertIn(f"{field}={family}\n", config)
            names = (module / "outputs.txt").read_text().splitlines()
            self.assertEqual(len(names), 9)
            self.assertIn("LuoShuAutoMix-Regular.ttf", names)
            self.assertTrue(all(name.startswith("LuoShuAutoMix-") for name in names), names)
            self.assertEqual((module / "compiled.txt").read_text().splitlines(), ["compiled"])


if __name__ == "__main__":
    unittest.main()
