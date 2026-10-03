#!/usr/bin/env python3
"""Exercise the global system-weight setting backend with a fake Settings provider."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'common' / 'font_weight_runtime.sh'
GIT_SHELL = Path(r'C:\Program Files\Git\bin\sh.exe')
CYGPATH = Path(r'C:\Program Files\Git\usr\bin\cygpath.exe')
SHELL = shutil.which('sh') or (str(GIT_SHELL) if GIT_SHELL.is_file() else 'sh')


class FontWeightRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / 'module'
        self.config = self.module / 'config'
        self.common = self.module / 'common'
        self.bin = self.root / 'bin'
        self.config.mkdir(parents=True)
        self.common.mkdir()
        self.bin.mkdir()
        self.value = self.root / 'setting-value'
        self.value.write_text('null\n')
        self.calls = self.root / 'settings-calls'
        self.shell_calls = self.root / 'shell-calls'
        self.command('settings', '''
if [ "$1" = --user ] && [ "$2" = current ]; then shift 2; fi
case "$1" in
  get)
    printf 'get\\n' >> "$SETTINGS_CALLS"
    [ "$2" = secure ] && [ "$3" = font_weight_adjustment ] || exit 21
    cat "$SETTINGS_VALUE"
    ;;
  put)
    printf 'put:%s\\n' "$4" >> "$SETTINGS_CALLS"
    [ "$2" = secure ] && [ "$3" = font_weight_adjustment ] || exit 22
    [ "${SETTINGS_FAIL:-}" != put ] || exit 1
    printf '%s\\n' "$4" > "$SETTINGS_VALUE"
    ;;
  delete)
    printf 'delete\\n' >> "$SETTINGS_CALLS"
    [ "$2" = secure ] && [ "$3" = font_weight_adjustment ] || exit 23
    [ "${SETTINGS_FAIL:-}" != delete ] || exit 1
    printf 'null\\n' > "$SETTINGS_VALUE"
    ;;
  *) exit 24 ;;
esac
''')
        self.command('cmd', 'printf "cmd:%s\\n" "$*" >> "$SHELL_CALLS"\n')
        self.command('am', 'printf "am:%s\\n" "$*" >> "$SHELL_CALLS"\n')
        shell_module = self.shell_path(self.module)
        self.env = {
            **os.environ,
            'MODDIR': shell_module,
            'MODULE_DIR': shell_module,
            'PATH': f'{self.shell_path(self.bin)}:/usr/bin:/bin',
            'SETTINGS_VALUE': self.shell_path(self.value),
            'SETTINGS_CALLS': self.shell_path(self.calls),
            'SHELL_CALLS': self.shell_path(self.shell_calls),
        }

    @staticmethod
    def shell_path(path):
        if os.name == 'nt' and CYGPATH.is_file():
            return subprocess.check_output([str(CYGPATH), '-u', str(path)], text=True, encoding='utf-8').strip()
        return str(path)

    def command(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/sh\n' + body)
        path.chmod(0o755)

    def run_action(self, action, *args, **env):
        return subprocess.run(
            [SHELL, self.shell_path(RUNTIME), action, *args],
            env={**self.env, **env},
            capture_output=True,
            text=True,
            encoding='utf-8',
            errors='replace',
            timeout=8,
        )

    def data(self, result):
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        payload = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(payload.get('status'), 'ok', payload)
        return payload.get('data', {})

    def test_first_change_backs_up_an_unset_value_and_reset_restores_unset(self):
        changed = self.data(self.run_action('set', '550'))
        self.assertEqual(changed['weight'], 550)
        self.assertEqual(self.value.read_text(), '150\n')
        self.assertIn('present=false', (self.config / 'font_weight_original.conf').read_text())
        self.assertIn('adjustment=0', (self.config / 'font_weight_original.conf').read_text())

        status = self.data(self.run_action('status'))
        self.assertTrue(status['supported'])
        self.assertEqual(status['weight'], 550)
        self.assertEqual(status['adjustment'], 150)

        reset = self.data(self.run_action('reset'))
        self.assertTrue(reset['reset'])
        self.assertEqual(self.value.read_text(), 'null\n')
        self.assertFalse((self.config / 'font_weight.conf').exists())
        self.assertFalse((self.config / 'font_weight_original.conf').exists())

    def test_existing_system_adjustment_is_restored_after_reset(self):
        self.value.write_text('80\n')
        self.data(self.run_action('set', '560'))
        original = (self.config / 'font_weight_original.conf').read_text()
        self.assertIn('present=true', original)
        self.assertIn('adjustment=80', original)
        self.data(self.run_action('reset'))
        self.assertEqual(self.value.read_text(), '80\n')

    def test_boot_reapplies_owned_value_once_after_system_reset(self):
        self.data(self.run_action('set', '620'))
        self.value.write_text('0\n')
        before = self.calls.read_text().count('put:')
        result = self.run_action('boot')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(self.value.read_text(), '220\n')
        self.assertEqual(self.calls.read_text().count('put:'), before + 1)

        result = self.run_action('boot')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(self.calls.read_text().count('put:'), before + 1)

    def test_external_change_is_never_overwritten_or_reset(self):
        self.data(self.run_action('set', '570'))
        self.value.write_text('45\n')
        result = self.run_action('boot')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(self.value.read_text(), '45\n')
        self.assertFalse((self.config / 'font_weight.conf').exists())
        self.assertFalse((self.config / 'font_weight_original.conf').exists())

        self.data(self.run_action('set', '530'))
        self.value.write_text('25\n')
        reset = self.data(self.run_action('reset'))
        self.assertFalse(reset['reset'])
        self.assertEqual(self.value.read_text(), '25\n')

    def test_weight_must_be_inside_range_and_on_ten_point_step(self):
        for value in ('299', '701', '505', 'text'):
            result = self.run_action('set', value)
            self.assertNotEqual(result.returncode, 0, value)
        self.assertEqual(self.value.read_text(), 'null\n')
        self.assertFalse((self.config / 'font_weight.conf').exists())
        self.assertNotIn('put:', self.calls.read_text() if self.calls.exists() else '')

    def test_settings_read_is_read_only_and_does_not_create_public_font_storage(self):
        status = self.data(self.run_action('status'))
        self.assertTrue(status['supported'])
        self.assertFalse((self.root / 'public').exists())
        self.assertFalse((self.config / 'font_weight.conf').exists())
        self.assertEqual(self.calls.read_text().count('get'), 1)
        self.assertFalse(self.calls.read_text().count('put'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
