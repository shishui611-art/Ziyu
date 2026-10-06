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

    def own_old_setting(self, original='0', present='true', adjustment='150'):
        (self.config / 'font_weight.conf').write_text(f'weight=550\nadjustment={adjustment}\n')
        (self.config / 'font_weight_original.conf').write_text(f'present={present}\nadjustment={original}\n')

    def test_retired_setter_cannot_change_system_weight(self):
        result = self.run_action('set', '550')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.value.read_text(), 'null\n')
        self.assertFalse(self.calls.exists())
        self.assertFalse(self.data(self.run_action('status'))['supported'])

    def test_boot_restores_owned_original_once(self):
        self.own_old_setting(original='20')
        self.value.write_text('150\n')
        self.data(self.run_action('boot'))
        self.assertEqual(self.value.read_text(), '20\n')
        writes = self.calls.read_text().count('put:')
        self.data(self.run_action('boot'))
        self.assertEqual(self.calls.read_text().count('put:'), writes)
        self.assertFalse((self.config / 'font_weight.conf').exists())

    def test_external_change_is_preserved(self):
        self.own_old_setting()
        self.value.write_text('45\n')
        self.data(self.run_action('boot'))
        self.assertEqual(self.value.read_text(), '45\n')
        self.assertNotIn('put:', self.calls.read_text())

    def test_unset_original_is_deleted_after_retirement(self):
        self.own_old_setting(present='false')
        self.value.write_text('150\n')
        self.data(self.run_action('boot'))
        self.assertEqual(self.value.read_text(), 'null\n')

    def test_failed_restore_keeps_backup_for_retry(self):
        self.own_old_setting(original='20')
        self.value.write_text('150\n')
        result = self.run_action('boot', SETTINGS_FAIL='put')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.config / 'font_weight_original.conf').exists())
        self.assertEqual(self.value.read_text(), '150\n')


if __name__ == '__main__':
    unittest.main(verbosity=2)
