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
    def test_current_boot_protection_cannot_report_a_verified_custom_font(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            common = module / 'common'
            common.mkdir()
            config = module / 'config'
            config.mkdir()
            (module / 'module.prop').write_text('id=LuoShu\nversion=v1.4.2\nversionCode=14002\n', encoding='utf-8')
            (config / 'active_font.conf').write_text('Demo\n', encoding='utf-8')
            (config / 'device-font-load-verification.conf').write_text(
                'state=verified\nmode=mount-verified\nactiveFont=Demo\n', encoding='utf-8')
            boot_path = Path('/proc/sys/kernel/random/boot_id')
            boot_id = boot_path.read_text(encoding='utf-8').strip() if boot_path.is_file() else ''
            (config / 'boot-safety-current-boot.conf').write_text(
                f'schema=ziyu-boot-safety-current-v1\nstate=blocked\nboot_id={boot_id}\n', encoding='utf-8')
            shutil.copy2(ROOT / 'common/app_bridge.sh', common / 'app_bridge.sh')
            (common / 'boot_ota_guard.sh').write_text(
                '#!/bin/sh\nprintf "status=ok\\nsafe_mode=1\\ncurrent_boot_blocked=1\\nreason=boot-safety-safe-mode\\n"\n',
                encoding='utf-8', newline='\n')
            shell = shutil.which('sh') or r'C:\Program Files\Git\usr\bin\sh.exe'
            env = dict(os.environ, MODDIR=module.as_posix())
            env['PATH'] = str(Path(shell).parent) + os.pathsep + env.get('PATH', '')
            result = subprocess.run([shell, (common / 'app_bridge.sh').as_posix(), 'status'],
                                    env=env, capture_output=True, text=True, encoding='utf-8', timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(result.stdout)['data']
            self.assertEqual('Demo', data['active'])
            self.assertEqual('unknown', data['effectiveActive'])
            self.assertEqual('failed', data['fontEffectState'])
            self.assertEqual('boot-protection', data['verificationMode'])
            self.assertEqual('boot-safety-safe-mode', data['verificationReason'])
            (common / 'boot_ota_guard.sh').write_text(
                '#!/bin/sh\nprintf "status=ok\\nsafe_mode=0\\ncurrent_boot_blocked=1\\nreason=ota-fingerprint-change\\nnext_generation_ready=1\\n"\n',
                encoding='utf-8', newline='\n')
            result = subprocess.run([shell, (common / 'app_bridge.sh').as_posix(), 'status'],
                                    env=env, capture_output=True, text=True, encoding='utf-8', timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(result.stdout)['data']
            self.assertEqual('unknown', data['effectiveActive'])
            self.assertEqual('pending-reboot', data['fontEffectState'])
            self.assertEqual('ota-generation-prepared', data['verificationReason'])
            self.assertTrue(data['rebootRequired'])

            # A previous boot's protection must not override a verified current
            # font, and the bridge must not invoke its guard for that stale record.
            marker = config / 'guard-status-called'
            (config / 'boot-safety-current-boot.conf').write_text(
                'schema=ziyu-boot-safety-current-v1\nstate=blocked\nboot_id=previous-test-boot\n',
                encoding='utf-8')
            (common / 'boot_ota_guard.sh').write_text(
                '#!/bin/sh\ntouch "$2/config/guard-status-called"\n'
                'printf "status=ok\\ncurrent_boot_blocked=1\\nreason=boot-safety-safe-mode\\n"\n',
                encoding='utf-8', newline='\n')
            result = subprocess.run([shell, (common / 'app_bridge.sh').as_posix(), 'status'],
                                    env=env, capture_output=True, text=True, encoding='utf-8', timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(result.stdout)['data']
            self.assertEqual('Demo', data['effectiveActive'])
            self.assertEqual('verified', data['fontEffectState'])
            self.assertFalse(marker.exists())

    def test_boot_safety_forwards_only_supported_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            common = module / 'common'
            common.mkdir()
            shutil.copy2(ROOT / 'common/app_bridge.sh', common / 'app_bridge.sh')
            (common / 'boot_ota_guard.sh').write_text(
                '#!/bin/sh\nprintf "status=ok\\nsafe_mode=0\\naction=%s\\nmodule=%s\\n" "$1" "$2"\n',
                encoding='utf-8', newline='\n',
            )
            shell = shutil.which('sh') or r'C:\Program Files\Git\usr\bin\sh.exe'
            env = dict(os.environ, MODDIR=module.as_posix())
            env['PATH'] = str(Path(shell).parent) + os.pathsep + env.get('PATH', '')
            for action in ('status', 'recover', 'rescan'):
                result = subprocess.run([shell, (common / 'app_bridge.sh').as_posix(),
                                         'boot_safety', action], env=env, capture_output=True,
                                        text=True, encoding='utf-8', timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f'action={action}\n', result.stdout)
                self.assertIn(f'module={module.as_posix()}\n', result.stdout)
            invalid = subprocess.run([shell, (common / 'app_bridge.sh').as_posix(),
                                      'boot_safety', 'recover;echo unsafe'], env=env,
                                     capture_output=True, text=True, encoding='utf-8', timeout=10)
            self.assertEqual(2, invalid.returncode)
            self.assertNotIn('unsafe', invalid.stdout)

    def test_preference_and_action_json_are_forwarded(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            (module/'common').mkdir()
            (module/'config').mkdir()
            for name in ('app_bridge.sh', 'mount_backend_preferences.sh', 'mount_backend_state.sh', 'mount_backend_details.sh', 'root_manager_detection.sh', 'meta_mount_detection.sh', 'action_control.sh', 'action_control.py', 'log_review.sh'):
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

    def test_app_coverage_exports_module_directory_without_inherited_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            common = module / 'common'
            python_bin = common / 'python' / 'bin'
            python_bin.mkdir(parents=True)
            (module / 'module.prop').write_text('id=LuoShu\n', encoding='utf-8')
            shutil.copy2(ROOT / 'common' / 'app_bridge.sh', common / 'app_bridge.sh')
            (common / 'font_coverage_locator.py').write_text('# fixture\n', encoding='utf-8')
            stub = python_bin / 'luoshu-python'
            with stub.open('w', encoding='utf-8', newline='\n') as stream:
                stream.write(
                    '#!/bin/sh\n'
                    'printf "%s\\n%s\\n%s\\n%s\\n" "$MODDIR" "$MODULE_DIR" "$PYTHONHOME" "$2"\n'
                )
            stub.chmod(0o755)

            shell = shutil.which('sh') or r'C:\Program Files\Git\usr\bin\sh.exe'
            env = dict(os.environ)
            env.pop('MODDIR', None)
            env.pop('MODULE_DIR', None)
            env['PATH'] = str(Path(shell).parent) + os.pathsep + env.get('PATH', '')

            def shell_path(path):
                value = str(path.resolve())
                cygpath = Path(shell).with_name('cygpath.exe')
                if os.name == 'nt' and cygpath.is_file():
                    converted = subprocess.run([str(cygpath), '-u', value], capture_output=True, text=True)
                    if converted.returncode == 0:
                        return converted.stdout.strip()
                return value

            result = subprocess.run(
                [shell, shell_path(common / 'app_bridge.sh'), 'app_coverage', 'running'],
                env=env, capture_output=True, text=True, encoding='utf-8',
            )
            self.assertEqual(0, result.returncode, f'stdout={result.stdout!r} stderr={result.stderr!r}')
            values = result.stdout.splitlines()
            expected_module = shell_path(module)
            self.assertEqual(expected_module, values[0])
            self.assertEqual(expected_module, values[1])
            self.assertEqual(shell_path(common / 'python'), values[2])
            self.assertEqual('running', values[3])

if __name__ == '__main__':
    unittest.main()
