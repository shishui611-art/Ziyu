"""Selected-slot combinations, library publication and preparation reuse."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock
import os
import shutil
import subprocess
import re
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "common"))
sys.path.insert(0, str(ROOT / "scripts"))
from font_instance_svg_test import fixture, bounds
from fontTools.ttLib import TTFont


class MixWorkflowTest(unittest.TestCase):
    def test_engine_prepare_only_builds_real_artifact_before_any_payload_transaction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "module"
            (module / "common").mkdir(parents=True)
            (module / "config").mkdir()
            (module / "logs").mkdir()
            stage = module / ".luoshu-mix-stage"
            stage.mkdir()
            source = root / "source.ttf"
            fixture(source, 220, svg=False)
            for name in ("active_font.conf", "text_reboot_required.conf", "font_mix.conf"):
                (module / "config" / name).write_text("preserved:" + name)
            definitions = (ROOT / "common/legacy_v14_4/font_mix_engine.sh").read_text(encoding="utf-8").split('\ncase "${1:-status}" in', 1)[0]
            runner = root / "prepare-test.sh"
            # Execute the real composite builder; replacing the runtime invocation
            # lets the desktop test use host Python without ARM64 binaries.
            runner.write_text(definitions + '''
find_family_file() { printf '%s\\n' "$SOURCE"; }
build_composite_file() {
    "$HOST_PYTHON" "$ENGINE_DIR/composite_font.py" --cjk "$1" --latin "$2" --digit "$3" --output "$MODDIR/cache/actual.ttf" >"$MODDIR/cache/build.json" || return 1
    COMPOSITE_RESULT="$MODDIR/cache/actual.ttf"
    COMPOSITE_OUTPUT_HASH=$(composite_hash_file "$COMPOSITE_RESULT")
}
payload_stage_begin() { echo 'unexpected payload transaction' >&2; return 77; }
prepare_mix_config() { echo 'unexpected active config transaction' >&2; return 78; }
mkdir -p "$MODDIR/cache"
apply_mix SelectedCjk SelectedLatin SelectedDigits
''', encoding="utf-8")
            shell = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"
            env = {**os.environ, "MODDIR": module.as_posix(), "SOURCE": source.as_posix(),
                   "HOST_PYTHON": Path(sys.executable).as_posix(),
                   "ENGINE_DIR": (ROOT / "common/legacy_v14_4").as_posix(),
                   "LUOSHU_MIX_PREPARE_ONLY": "true", "LUOSHU_MIX_REQUEST_ID": "prepare-real",
                   "LUOSHU_MIX_MANIFEST": (stage / ".luoshu-mix-generation.conf").as_posix()}
            result = subprocess.run([shell, runner.as_posix()], env=env, capture_output=True,
                                    text=True, encoding="utf-8", timeout=25)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            with TTFont(stage / "mix-composite.ttf") as font:
                self.assertIn(ord("中"), font.getBestCmap())
                self.assertIn(ord("A"), font.getBestCmap())
                self.assertIn(ord("1"), font.getBestCmap())
            for name in ("active_font.conf", "text_reboot_required.conf", "font_mix.conf"):
                self.assertEqual((module / "config" / name).read_text(), "preserved:" + name)
            self.assertFalse((module / ".luoshu-payload-next").exists())
            self.assertFalse((module / ".font_switch.lock").exists())

    def test_cache_import_works_through_the_packaged_runtime_symlink(self):
        import fontTools
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / "module/common"
            legacy = common / "legacy_v14_4"
            runtime = root / "module/.legacy-v14-runtime/common"
            legacy.mkdir(parents=True)
            runtime.mkdir(parents=True)
            shutil.copyfile(ROOT / "common/legacy_v14_4/font_instance.py", legacy / "font_instance.py")
            shutil.copyfile(ROOT / "common/font_prepare_cache.py", common / "font_prepare_cache.py")
            (runtime / "font_instance.py").symlink_to(legacy / "font_instance.py")
            (runtime / "font_prepare_cache.py").symlink_to(common / "font_prepare_cache.py")
            source, output = root / "source.ttf", root / "selected.ttf"
            fixture(source, 220, svg=False)
            python_root = common / "python"
            site = python_root / "lib/python3.14/site-packages"
            site.mkdir(parents=True)
            (site / "fontTools").symlink_to(Path(fontTools.__file__).resolve().parent, target_is_directory=True)
            # Use the production worker's declared import paths. POSIX resolves
            # a script symlink when choosing sys.path[0]; Windows may retain it,
            # so exercise both the canonical target and the symlink entry.
            worker = (ROOT / "common/legacy_v14_4/v142_weighted_mix.sh").read_text(encoding="utf-8")
            template = re.search(r'export PYTHONPATH="([^"]+)"', worker).group(1)
            search_paths = [part.replace("$MODDIR", str(runtime.parent)).replace("$PYROOT", str(python_root))
                            for part in template.split(":")]
            env = {**os.environ, "PYTHONPATH": os.pathsep.join(search_paths), "PYTHONUTF8": "1"}
            env.pop("PYTHONHOME", None)
            for index, entry in enumerate(((runtime / "font_instance.py").resolve(), runtime / "font_instance.py")):
                env["LUOSHU_PREPARE_CACHE"] = str(root / f"cache-{index}")
                for expected_hit in (False, True):
                    result = subprocess.run([sys.executable, str(entry),
                                             "--input", str(source), "--output", str(output),
                                             "--role", "cjk", "--weight", "900", "--axes", "wght=900"],
                                            env=env, capture_output=True, text=True, encoding="utf-8", timeout=25)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    report = json.loads(result.stdout)
                    self.assertEqual(report["cacheHit"], expected_hit)
                    self.assertEqual(report["weight"], 900)

    def test_selected_variable_and_static_roles_produce_one_composite(self):
        instance_spec = importlib.util.spec_from_file_location("selected_instance", ROOT / "common/legacy_v14_4/font_instance.py")
        instance = importlib.util.module_from_spec(instance_spec)
        instance_spec.loader.exec_module(instance)
        from composite_font import build
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            originals, prepared = {}, {}
            selections = (("cjk", 700, 350), ("latin", 500, 400), ("digit", 300, 650))
            for role, width, weight in selections:
                source, output = root / f"{role}.ttf", root / f"{role}-selected.ttf"
                fixture(source, width, svg=False)
                if role == "latin":
                    with TTFont(source) as static:
                        del static["fvar"]
                        del static["gvar"]
                        static["OS/2"].usWeightClass = 400
                        static.save(source)
                report = instance.materialize(source, output, role, weight, {"wght": weight})
                self.assertEqual(report["weight"], weight)
                originals[role], prepared[role] = source, output
            # Requesting another weight of a static donor must not relabel it.
            report = instance.materialize(originals["latin"], prepared["latin"], "latin", 700, {"wght": 700})
            self.assertEqual(report["weight"], 400)
            output = root / "combined.ttf"
            result = build(SimpleNamespace(**prepared, output=output, weight=350,
                                          cjk_face=None, latin_face=None, digit_face=None, progress=None))
            self.assertEqual(result["status"], "ok")
            self.assertEqual(len(list(root.glob("combined*.ttf"))), 1)
            with TTFont(output) as font:
                self.assertNotIn("fvar", font)
                for role, char in (("cjk", "中"), ("latin", "A"), ("digit", "1")):
                    with TTFont(prepared[role]) as selected:
                        self.assertEqual(bounds(font, char), bounds(selected, char))

    def test_router_only_reports_success_after_named_library_is_saved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module, library = root / "module", root / "library"
            config = module / "config"
            config.mkdir(parents=True)
            (module / "common").mkdir()
            (module / "logs").mkdir()
            for name in ("mix_library.py", "font_structure.py"):
                shutil.copyfile(ROOT / "common" / name, module / "common" / name)
            store = module / ".luoshu-mix-stage"
            store.mkdir(parents=True)
            fixture(store / "mix-composite.ttf", 220, svg=False)
            (config / "mix-stage-next.conf").write_text(
                f"font=mix\nmixName=我的组合\nrequestId=request-library\npublicRoot={root.as_posix()}\n", encoding="utf-8")
            (store / ".luoshu-mix-generation.conf").write_text("requestId=request-library\ncompositeHash=test-hash\n")
            protected = ("active_font.conf", "text_reboot_required.conf", "font-payload-next.conf", "font_runtime_legacy_v14_4.conf")
            for name in protected:
                (config / name).write_text("unchanged:" + name)
            live = module / ".luoshu-payload"
            live.mkdir()
            (live / "font.ttf").write_bytes(b"existing-live-payload")
            (config / "axes_task.conf").write_text("task=axes-1\nstate=success\npercent=100\n", encoding="utf-8")
            for name in ("native_font_index.json", "native_font_index.key"):
                (config / name).write_text("old cache")
            definitions = (ROOT / "common/legacy_v14_4/mix_router.sh").read_text(encoding="utf-8").split('\n_cmd="${1:-config}"', 1)[0]
            runner = root / "router-test.sh"
            runner.write_text(definitions + '\nmix_reconcile_fast() { :; }\n'
                              'mix_python() { "$HOST_PYTHON" "$@"; }\n'
                              'case "$1" in status) mix_status_json_fast axes-1;; finalize) finalize_mix_stage;; esac\n', encoding="utf-8")
            shell = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"
            env = {**os.environ, "MODDIR": module.as_posix(), "HOST_PYTHON": Path(sys.executable).as_posix()}
            def run(action):
                result = subprocess.run([shell, str(runner), action], env=env,
                                        capture_output=True, text=True, encoding="utf-8", timeout=25)
                return result, json.loads(result.stdout)
            result, status = run("status")
            self.assertEqual(status["data"]["state"], "running")
            # Independent finalizers can race with a retry. They must publish
            # exactly one family and agree on its durable preparation result.
            processes = [subprocess.Popen([shell, str(runner), "finalize"], env=env,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                          text=True, encoding="utf-8") for _ in range(3)]
            for process in processes:
                stdout, stderr = process.communicate(timeout=45)
                self.assertEqual(process.returncode, 0, stderr + stdout)
                self.assertEqual(json.loads(stdout)["status"], "ok")
            result, status = run("status")
            self.assertEqual(status["data"]["state"], "success")
            self.assertEqual(status["data"]["result"], "prepared")
            self.assertTrue(status["data"]["generatedFontId"].startswith("ZiyuMix"))
            self.assertEqual(status["data"]["generatedFontName"], "我的组合")
            self.assertEqual(Path(status["data"]["previewSource"]).read_bytes(), (store / "mix-composite.ttf").read_bytes())
            self.assertFalse(status["data"]["rebootRequired"])
            for name in protected:
                self.assertEqual((config / name).read_text(), "unchanged:" + name)
            self.assertEqual((live / "font.ttf").read_bytes(), b"existing-live-payload")
            self.assertFalse((module / ".luoshu-payload-next").exists())
            bridge_definitions = (ROOT / "common/app_bridge.sh").read_text(encoding="utf-8").split('\ncase "${1:-status}" in', 1)[0]
            preview_runner = root / "preview-lookup.sh"
            preview_runner.write_text(bridge_definitions + '\npreview_source_json "$1" 400\n', encoding="utf-8")
            preview_env = {**env, "LUOSHU_PUBLIC_DIR": root.as_posix()}
            preview = subprocess.run([shell, preview_runner.as_posix(), status["data"]["generatedFontId"]],
                                     env=preview_env, capture_output=True, text=True, encoding="utf-8", timeout=20)
            self.assertEqual(preview.returncode, 0, preview.stdout + preview.stderr)
            self.assertEqual(json.loads(preview.stdout)["data"]["bytes"], Path(status["data"]["previewSource"]).stat().st_size)
            self.assertEqual(len(list((root / "fonts").glob("*.ttf"))), 1)
            self.assertFalse((config / "native_font_index.key").exists())
            result, status = run("finalize")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(len(list((root / "fonts").glob("*.ttf"))), 1)
            # Cancellation before finalization cannot publish another family.
            (config / "mix_task.cancel").write_text("task=axes-1\n")
            result, _ = run("finalize")
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(len(list((root / "fonts").glob("*.ttf"))), 1)
            (config / "mix_task.cancel").unlink()
            (store / "mix-composite.ttf").unlink()
            result, status = run("finalize")
            self.assertNotEqual(result.returncode, 0)
            result, status = run("status")
            self.assertEqual(status["data"]["state"], "success")
            self.assertEqual(status["data"]["result"], "prepared")

    def test_cancelled_status_is_terminal_and_has_no_prepared_font(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            (root / "config/axes_task.conf").write_text("task=axes-cancelled\nstate=cancelled\npercent=100\nmessage=组合任务已取消\n")
            shell = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"
            result = subprocess.run([shell, (ROOT / "common/legacy_v14_4/mix_router.sh").as_posix(),
                                     "status", "axes-cancelled"], env={**os.environ, "MODDIR": root.as_posix()},
                                    capture_output=True, text=True, encoding="utf-8", timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            data = json.loads(result.stdout)["data"]
            self.assertEqual(data["state"], "cancelled")
            for field in ("result", "generatedFontId", "generatedFontName", "previewSource"):
                self.assertEqual(data[field], "")

    def test_combo_router_honors_selected_weights_without_nine_weight_expansion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / "common"
            common.mkdir()
            (common / "v142_weighted_mix.sh").write_text('printf "fixed:%s\\n" "$*"\n')
            (common / "v143_auto_multiweight_mix.sh").write_text('echo nine-weight-route\n')
            (common / "font_role_check.sh").write_text('exit 0\n')
            (common / "mix_weight_mode.sh").write_text('infer_mix_weight_mode() { echo auto; }\n')
            script = ROOT / "common/legacy_v14_4/v14_mix.sh"
            shell = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"
            result = subprocess.run([shell, str(script), "start", "Chinese", "Latin", "Digits",
                                     "wght=400", "wght=550", "wght=700"],
                                    env={**os.environ, "MODDIR": root.as_posix()},
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("fixed:start Chinese Latin Digits wght=400 wght=550 wght=700", result.stdout)
            self.assertNotIn("nine-weight-route", result.stdout)

    def load(self, name):
        path = ROOT / "common" / f"{name}.py"
        self.assertTrue(path.exists(), f"missing implementation: {name}")
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_named_library_publish_is_idempotent_and_keeps_font_bytes(self):
        engine = self.load("mix_library")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            fixture(source / "Example-Regular.ttf", 220, svg=False)
            library = root / "library"
            first = engine.publish(source, library, "我的日常组合", "request-1")
            second = engine.publish(source, library, "我的日常组合", "request-1")
            self.assertEqual(first["id"], second["id"])
            self.assertEqual(len(list(library.glob("*.ttf"))), 1)
            self.assertEqual(next(library.glob("*.ttf")).read_bytes(),
                             (source / "Example-Regular.ttf").read_bytes())
            self.assertIn("name=我的日常组合", (library / (first["id"] + ".conf")).read_text())
            with self.assertRaises(ValueError):
                engine.publish(source, library, "../错误\nname=x", "request-2")

    def test_prepare_cache_reuses_and_invalidates_corrupt_or_changed_input(self):
        cache = self.load("font_prepare_cache")
        from font_instance import materialize
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source.ttf", root / "output.ttf"
            fixture(source, 220, svg=False)
            wrapped = Mock(wraps=materialize)
            kwargs = dict(source=source, output=output, role="cjk", weight=400,
                          axes={"wght": 400}, cache_dir=root / "cache",
                          materialize=wrapped, engine_path=ROOT / "common/font_instance.py")
            cache.prepare(**kwargs)
            cache.prepare(**kwargs)
            self.assertEqual(wrapped.call_count, 1)
            next((root / "cache").glob("*.ttf")).write_bytes(b"corrupt")
            cache.prepare(**kwargs)
            self.assertEqual(wrapped.call_count, 2)
            kwargs["weight"] = 700
            kwargs["axes"] = {"wght": 700}
            cache.prepare(**kwargs)
            self.assertEqual(wrapped.call_count, 3)
            fixture(source, 330, svg=False)
            cache.prepare(**kwargs)
            self.assertEqual(wrapped.call_count, 4)


if __name__ == "__main__":
    unittest.main()
