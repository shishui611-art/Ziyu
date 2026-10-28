"""Exercise owned Zygisk-companion state and explicit enable/disable in a temp tree."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PreloadControlTest(unittest.TestCase):
    def test_status_and_markers_are_scoped_to_owned_companion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root/'main'; (module/'common').mkdir(parents=True); (module/'config').mkdir()
            shutil.copyfile(ROOT/'common/font_preload_helper.sh', module/'common/font_preload_helper.sh')
            target, pending = root/'modules/ziyu-font-preload', root/'modules_update/ziyu-font-preload'
            for folder in (target, pending):
                folder.mkdir(parents=True)
                (folder/'ziyu-owner.conf').write_text('schema=ziyu-font-preload-owner-v1\nowner=ziyu\n')
                (folder/'module.prop').write_text('id=ziyu-font-preload\n')
            script = (ROOT/'common/font_preload_control.sh').read_text(encoding='utf-8')
            script = script.replace('target=/data/adb/modules/ziyu-font-preload', f'target="{target.as_posix()}"')
            script = script.replace('pending=/data/adb/modules_update/ziyu-font-preload', f'pending="{pending.as_posix()}"')
            script = script.replace('boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)', 'boot=test-boot')
            runner = root/'control.sh'; runner.write_text(script, encoding='utf-8')
            shell = shutil.which('bash') or r'C:\Program Files\Git\bin\bash.exe'
            env = {**os.environ, 'MODDIR': module.as_posix()}
            def run(action):
                return subprocess.run([shell, runner.as_posix(), action], env=env, text=True,
                                      capture_output=True, encoding='utf-8', timeout=10)
            status = run('status'); self.assertEqual(status.returncode, 0, status.stderr)
            self.assertIn('state=enabled\ncallback=unobserved', status.stdout)
            (module/'config/font-preload-arm64-v8a.conf').write_text('boot_id=old-boot\nstate=callback-observed\n')
            self.assertIn('callback=unobserved', run('status').stdout)
            (module/'config/font-preload-arm64-v8a.conf').write_text('boot_id=test-boot\nstate=callback-observed\n')
            self.assertIn('callback=observed', run('status').stdout)
            self.assertEqual(run('disable').returncode, 0)
            self.assertTrue((target/'disable').is_file() and (pending/'disable').is_file())
            self.assertIn('state=disabled', run('status').stdout)
            (target/'disable').write_text('target prior choice')
            (pending/'disable').write_text('pending prior choice')
            failing = script.replace('. "$MODDIR/common/font_preload_helper.sh"',
                '. "$MODDIR/common/font_preload_helper.sh"\nrm() { if [ "$1" = -f ] && [ "$2" = "$FAIL_MARKER" ]; then return 1; fi; command rm "$@"; }')
            broken_runner = root/'control-failure.sh'; broken_runner.write_text(failing, encoding='utf-8')
            failed = subprocess.run([shell, broken_runner.as_posix(), 'enable'],
                                    env={**env, 'FAIL_MARKER': (target/'disable').as_posix()},
                                    text=True, capture_output=True, encoding='utf-8', timeout=10)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual((target/'disable').read_text(), 'target prior choice')
            self.assertEqual((pending/'disable').read_text(), 'pending prior choice')
            self.assertEqual(run('enable').returncode, 0)
            self.assertFalse((target/'disable').exists() or (pending/'disable').exists())
            (pending/'remove').touch()
            self.assertNotEqual(run('enable').returncode, 0)
            (pending/'module.prop').write_text('id=unrelated\n')
            self.assertNotEqual(run('disable').returncode, 0)
            self.assertFalse((target/'disable').exists())


if __name__ == '__main__':
    unittest.main()
