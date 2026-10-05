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
            (root / "functions.sh").write_text(definitions, encoding="utf-8")
            module = root / "module"
            (module / "config").mkdir(parents=True)
            (module / "common").mkdir()
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

    def test_failure_diagnostics_survive_work_cache_cleanup(self):
        engine = ROOT / "common/legacy_v14_4/v143_auto_multiweight_mix.sh"
        definitions = engine.read_text(encoding="utf-8").split('\ncase "${1:-config}" in', 1)[0]
        with tempfile.TemporaryDirectory(prefix="ziyu-failure-") as temporary:
            root = Path(temporary)
            (root / "functions.sh").write_text(definitions, encoding="utf-8")
            module = root / "module"
            (module / "config").mkdir(parents=True)
            (module / "logs").mkdir()
            task = module / "config/axes_task.conf"
            task.write_text("task=failure-test\nstate=running\ncjk=中文字体\nlatin=Latin\ndigit=Digit\n"
                            "cjkAxes=wght=400\ncjkMode=fixed\n"
                            f"root={root.as_posix()}/job\n", encoding="utf-8")
            (root / "source.ttf").write_bytes(b"fixture variable font")
            (root / "helper.py").write_text("# fingerprint fixture\n", encoding="utf-8")
            (root / "fail.sh").write_text(
                '#!/bin/sh\nprintf \'{"errorCode":"FONT_VARIATION_ASSERTION","message":"处理失败",'
                '"traceback":"packed delta count"}\\n\' >&2\nexit 13\n', encoding="utf-8")
            (root / "fail.sh").chmod(0o755)
            (root / "test.sh").write_text(
                'cd "$TEST_ROOT"\nMODDIR="$PWD/module"\nLUOSHU_PUBLIC_DIR="$PWD/public"\n. ./functions.sh\n'
                'find_best_source() { echo "$PWD/source.ttf"; }\n'
                'font_validate() { FONT_CHECK_VARIABLE=true; FONT_CHECK_FORMAT=TTF; return 0; }\n'
                'PYBIN="$PWD/fail.sh"\nINSTANCE_PY="$PWD/helper.py"\nworker failure-test\n', encoding="utf-8")
            environment = os.environ.copy()
            environment["TEST_ROOT"] = root.as_posix()
            run = subprocess.run([BASH, str(root / "test.sh")], env=environment,
                                 capture_output=True, text=True, timeout=90)
            self.assertNotEqual(run.returncode, 0)
            self.assertIn("state=failed", task.read_text(encoding="utf-8"))
            self.assertFalse((root / "job").exists())
            saved = module / "logs/font-diagnostics/failure-test-cjk-100.json"
            self.assertIn("packed delta count", saved.read_text(encoding="utf-8"))
            log = (module / "logs/fontswitch.log").read_text(encoding="utf-8")
            for value in ("generated_weight=100", "effective_weight=400", "exit_code=13", "FONT_VARIATION_ASSERTION"):
                self.assertIn(value, log)

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
            (root / "helper.py").write_text("# fingerprint fixture\n", encoding="utf-8")
            (root / "instance.sh").write_text(
                '#!/bin/sh\nshift\nwhile [ "$#" -gt 0 ]; do\n'
                'case "$1" in --input) input="$2";; --output) output="$2";; esac\nshift 2\ndone\n'
                'cp "$input" "$output"\n'
                'printf \'{"variationFallbacks":[{"characters":"窒"}],"warning":"窒在 400 字重无法处理，已保留原始轮廓"}\\n\'\n',
                encoding="utf-8")
            (root / "instance.sh").chmod(0o755)
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
                'font_validate() { FONT_CHECK_VARIABLE=false; FONT_CHECK_FORMAT=TTF; '
                'case "$1" in */CJK-Regular.ttf) FONT_CHECK_VARIABLE=true;; esac; test -s "$1"; }\n'
                'PYBIN="$PWD/instance.sh"\nINSTANCE_PY="$PWD/helper.py"\n'
                'worker regression\n', encoding="utf-8")
            environment = os.environ.copy()
            environment["TEST_ROOT"] = root.as_posix()
            run = subprocess.run([BASH, str(root / "test.sh")], env=environment,
                                 capture_output=True, text=True, timeout=180)
            state = task.read_text(encoding="utf-8")
            self.assertEqual(run.returncode, 0, state + run.stderr)
            self.assertIn("state=success\n", state)
            self.assertIn("窒在 400 字重无法处理，已保留原始轮廓", state)
            self.assertIn("其余字形已按所选字重正常处理", state)
            self.assertTrue((module / "logs/font-diagnostics/regression-cjk-100.json").is_file())
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
