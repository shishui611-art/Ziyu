"""Production status must follow this boot's committed backend, not stale caches."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class HomeBackendStatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.module = self.base / 'module'
        (self.module / 'common').mkdir(parents=True)
        (self.module / 'config').mkdir()
        for name in ('app_bridge.sh', 'root_manager_detection.sh', 'mount_backend_details.sh'):
            shutil.copy2(ROOT / 'common' / name, self.module / 'common' / name)
        (self.module / 'module.prop').write_text('id=LuoShu\nversion=test\nversionCode=1\n')
        for directory in ('ksu', 'magisk'):
            (self.base / 'data' / 'adb' / directory).mkdir(parents=True)
        self.shell = shutil.which('sh') or r'C:\Program Files\Git\usr\bin\sh.exe'
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(('KSU', 'SUKISU', 'MAGISK', 'APATCH'))}
        self.env.update(MODDIR=str(self.module), MODULE_DIR=str(self.module),
                        LUOSHU_ROOT_DETECT_ROOT=str(self.base))
        self.env['PATH'] = str(Path(self.shell).parent) + os.pathsep + self.env.get('PATH', '')
        # Native Windows MSYS lacks Linux boot_id. A read-only proc fixture
        # exercises the same boot check without introducing a production bypass.
        tools = self.base / 'tools'
        tools.mkdir()
        cat = tools / 'cat'
        cat.write_text('#!/bin/sh\nif [ "$1" = /proc/sys/kernel/random/boot_id ]; then printf "status-test-boot\\n"; else exec /usr/bin/cat "$@"; fi\n')
        cat.chmod(0o755)
        self.env['PATH'] = str(tools) + os.pathsep + self.env['PATH']
        self.boot = self.run_shell('cat /proc/sys/kernel/random/boot_id').strip()

    def run_shell(self, command):
        result = subprocess.run([self.shell, '-c', command], env=self.env,
                                capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def write(self, name, contents):
        (self.module / 'config' / name).write_text(contents, encoding='utf-8')

    def backend(self, active='none', verification='failed', error='font-route-verification-failed', boot=None):
        self.write('mount-backend.conf',
                   f'schema=ziyu-mount-backend-v1\nboot_id={self.boot if boot is None else boot}\n'
                   'root_manager=SukiSU Ultra\nroot_version=4.2.0\nroot_version_code=40900\n'
                   'root_detection_source=env\nselected_backend=self\n'
                   f'active_backend={active}\nverification={verification}\nlast_error={error}\n')

    def status(self):
        result = subprocess.run([self.shell, str(self.module / 'common' / 'app_bridge.sh'), 'status'],
                                env=self.env, capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)['data']

    def test_shared_boot_identity_wins_over_residual_directories_in_home_and_settings(self):
        self.backend()
        self.assertEqual('SukiSU Ultra', self.status()['rootManager'])
        report = subprocess.run([self.shell, str(ROOT / 'system' / 'bin' / 'luoshu-health'), 'report'],
                                env=self.env, capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(0, report.returncode, report.stderr)
        self.assertIn('rootManager=SukiSU Ultra\n', report.stdout)
        self.assertIn('alignmentState=failed\n', report.stdout)
        self.assertIn('selfMountState=failed\n', report.stdout)

    def test_explicit_new_manager_environment_wins_over_boot_identity(self):
        self.backend()
        self.env.update(APATCH='1', APATCH_VER_CODE='100')
        self.assertEqual('APatch', self.status()['rootManager'])

    def test_stale_boot_identity_and_conflicting_directories_are_not_magisk(self):
        self.backend(boot='previous-boot')
        self.assertEqual('Root', self.status()['rootManager'])

    def test_failed_backend_rejects_legacy_mounted_verified_cache(self):
        self.backend()
        self.write('active_font.conf', 'DemoFont\n')
        self.write('self-mount.conf', 'state=mounted\nbackend=self-overlay\n')
        self.write('device-font-load-verification.conf',
                   'state=verified\nmode=mount-confirmed\nactiveFont=DemoFont\n')
        data = self.status()
        self.assertEqual('failed', data['mountState'])
        self.assertEqual('failed', data['verificationState'])
        self.assertEqual('failed', data['fontEffectState'])
        self.assertEqual('default', data['effectiveActive'])
        self.assertEqual('自挂载失败 · 已回滚', data['mountEngine'])
        self.assertEqual('font-route-verification-failed', data['mountFailure'])

    def test_rollback_failure_does_not_claim_restored_stock(self):
        self.backend(error='font-route-verification-failed;rollback-failed')
        self.write('active_font.conf', 'DemoFont\n')
        data = self.status()
        self.assertEqual('unknown', data['effectiveActive'])
        self.assertEqual('自挂载失败 · 回滚待检查', data['mountEngine'])

    def test_success_names_actual_backend_and_pending_is_not_disabled(self):
        self.backend(active='self', verification='passed', error='none')
        self.assertEqual('字域自挂载 · 未观察到可识别的挂载方式', self.status()['mountEngine'])
        self.backend(active='none', verification='pending', error='none')
        self.assertEqual('字域自挂载 · 未观察到可识别的挂载方式 · 待验证', self.status()['mountEngine'])


if __name__ == '__main__':
    unittest.main()
