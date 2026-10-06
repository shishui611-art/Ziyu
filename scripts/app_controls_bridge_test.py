"""Exercise real App bridge JSON forwarding without Android mounts."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class AppControlsBridgeTest(unittest.TestCase):
    def test_preference_and_action_json_are_forwarded(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            (module/'common').mkdir()
            (module/'config').mkdir()
            for name in ('app_bridge.sh', 'mount_backend_preferences.sh', 'mount_backend_details.sh', 'root_manager_detection.sh', 'meta_mount_detection.sh', 'action_control.sh', 'action_control.py', 'log_review.sh'):
                shutil.copy2(ROOT/'common'/name, module/'common'/name)
            (module/'module.prop').write_text('id=LuoShu\n')
            env = dict(os.environ, MODDIR=str(module), LUOSHU_PYTHON=sys.executable)
            shell = shutil.which('sh') or r'C:\Program Files\Git\usr\bin\sh.exe'
            env['PATH'] = str(Path(shell).parent) + os.pathsep + env.get('PATH', '')
            def request(*arguments):
                result = subprocess.run([shell, str(module/'common/app_bridge.sh'), *arguments], env=env, capture_output=True, text=True, encoding='utf-8')
                self.assertEqual(0, result.returncode, result.stderr)
                return json.loads(result.stdout)
            preference = request('mount_preferences', 'set', 'self')
            self.assertTrue(preference['ok'])
            self.assertEqual('auto', preference['preferredBackend'])
            self.assertEqual('automatic-provider-first', preference['policy'])
            self.assertIn('providerName', preference)
            self.assertIn('mountMethod', preference)
            self.assertFalse(request('action_status')['data']['undoAvailable'])
            self.assertTrue(request('log_review', 'mark')['data']['viewed'])
            self.assertTrue(request('log_review', 'clear')['data']['cleared'])

if __name__ == '__main__':
    unittest.main()
