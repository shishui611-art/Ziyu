#!/usr/bin/env python3
"""Settings retirement ownership + static installer phases; real supervisor."""
import os, shutil, subprocess, tempfile, unittest
from pathlib import Path
from host_task_scope_fixture import install_task_scope
ROOT=Path(__file__).resolve().parents[1]
class RetirementTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup)
        self.root=Path(t.name);self.old=self.root/'old';self.new=self.root/'new'
        for p in (self.old,self.new):(p/'config').mkdir(parents=True)
        install_task_scope(self.new)
        self.bin=self.root/'bin';self.bin.mkdir()
        self.value=self.root/'value';self.value.write_text('120\n')
        self.calls=self.root/'calls'
        p=self.bin/'settings';p.write_text('''#!/bin/sh
[ "$1" = --user ] && [ "$2" = current ] || exit 23
shift 2
printf '%s\n' "$*" >> "$CALLS"
[ "${SETTINGS_FAIL:-0}" = 0 ] || exit 1
case "$1" in get) cat "$VALUE";; put) printf '%s\n' "$4" > "$VALUE";; delete) printf 'null\n' > "$VALUE";; *) exit 9;; esac
''');p.chmod(0o755)
        self.env={**os.environ,'PATH':str(self.bin)+':'+os.environ['PATH'],'VALUE':str(self.value),'CALLS':str(self.calls)}
        self.backup('120','20')
    def backup(self,last,original):
        (self.old/'config/font_weight.conf').write_text('adjustment='+last+'\n')
        (self.old/'config/font_weight_original.conf').write_text('adjustment='+original+'\n')
    def test_unset_original_is_deleted_when_uninstalling_owned_weight(self):
        (self.old/'config/font_weight.conf').write_text('weight=550\nadjustment=150\n')
        (self.old/'config/font_weight_original.conf').write_text('present=false\nadjustment=0\n')
        self.value.write_text('150\n')
        r=self.run_job();self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(self.value.read_text(),'null\n')
        self.assertEqual(self.state(),'state=restored')
    def run_job(self,mode='flash',**env):
        old=self.new if mode=='boot' else self.old
        return subprocess.run(['sh',str(ROOT/'common/font_weight_retire.sh'),str(old),str(self.new),mode],
            env={**self.env,**env},capture_output=True,text=True,timeout=20)
    def state(self):return (self.new/'config/font-weight-retired-v2.conf').read_text().strip()
    def test_owned_setting_restored_once(self):
        r=self.run_job();self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(self.value.read_text(),'20\n')
        self.assertEqual(self.state(),'state=restored');old=self.calls.read_text()
        self.assertEqual(self.run_job('boot').returncode,0);self.assertEqual(self.calls.read_text(),old)
    def test_external_value_preserved(self):
        self.value.write_text('80\n');self.assertEqual(self.run_job().returncode,0)
        self.assertEqual(self.value.read_text(),'80\n');self.assertEqual(self.state(),'state=externally-changed')
        self.assertNotIn('put',self.calls.read_text())
    def test_unset_external_value_preserved(self):
        self.value.write_text('null\n');self.assertEqual(self.run_job().returncode,0)
        self.assertNotIn('put',self.calls.read_text())
    def test_missing_ownership_does_not_touch_settings(self):
        (self.old/'config/font_weight_original.conf').unlink();self.assertEqual(self.run_job().returncode,0)
        self.assertFalse(self.calls.exists());self.assertEqual(self.state(),'state=not-owned')
    def test_invalid_backup_is_not_evaluated(self):
        self.backup('$(touch '+str(self.root/'hacked')+')','20');self.assertEqual(self.run_job().returncode,0)
        self.assertFalse((self.root/'hacked').exists());self.assertFalse(self.calls.exists())
    def test_failed_flash_carries_backup_for_one_boot_retry(self):
        self.assertNotEqual(self.run_job(SETTINGS_FAIL='1').returncode,0);self.assertEqual(self.state(),'state=pending')
        self.assertEqual(self.run_job('boot').returncode,0);self.assertEqual(self.state(),'state=restored')
    def test_failed_boot_is_not_retried_forever(self):
        self.run_job(SETTINGS_FAIL='1');self.assertNotEqual(self.run_job('boot',SETTINGS_FAIL='1').returncode,0)
        self.assertEqual(self.state(),'state=failed');old=self.calls.read_text()
        self.assertEqual(self.run_job('boot').returncode,0);self.assertEqual(old,self.calls.read_text())
    def test_global_weight_is_scoped_and_legacy_migration_remains_bounded(self):
        manager=(ROOT/'common/font_manager_v4.sh').read_text()
        self.assertNotRegex(manager,r'(?m)^\s*settings\s')
        self.assertNotRegex((ROOT/'.luoshu-runtime/core/service.sh').read_text(),r'(?m)^\s*settings\s')
        # Uninstall must not default to writing zero for users who never opted in.
        self.assertNotRegex((ROOT/'.luoshu-runtime/compat/v227/uninstall.sh').read_text(),r'(?m)^\s*settings\s')
        runtime=(ROOT/'common/font_weight_runtime.sh').read_text()
        self.assertNotIn('fw_set()',runtime)
        self.assertIn('fw_boot()',runtime)
        self.assertIn('fw_matches_original',runtime)
        self.assertIn('font_weight_runtime.sh" service', (ROOT/'service.sh').read_text())
    def test_global_weight_controls_have_been_removed(self):
        base=ROOT/'android-app/app/src/main/java/io/github/xgl34222220/ziyu'
        self.assertNotIn('HomeGlobalWeightCard', (base/'ui/home/HomeScreenCompact.kt').read_text())
        self.assertNotIn('refreshSystemWeight', (base/'ZiyuAppShell.kt').read_text())
        self.assertNotIn('previewSystemWeight', (base/'Alpha15FeatureViewModel.kt').read_text())

    def test_installer_static_phases_no_fake_percent_or_background_jobs(self):
        s=(ROOT/'common/install_ui.sh').read_text();self.assertNotIn('sleep ',s)
        r=subprocess.run(['sh','-c','ui_print() { printf "%s\\n" "$*"; }; . "$1"; luoshu_install_header v2.0.0; luoshu_install_step 1 环境; luoshu_install_step 2 扫描; luoshu_install_step 3 App; luoshu_install_step 4 挂载; luoshu_install_complete','sh',str(ROOT/'common/install_ui.sh')],capture_output=True,text=True,check=True)
        self.assertIn('2.0.0',r.stdout);self.assertNotIn('%',r.stdout)
        for n in range(1,5):self.assertEqual(r.stdout.count(f'[{n}/4]'),1)
        self.assertIn('请完整重启；实际挂载结果见字域 App「挂载详情」',r.stdout)
if __name__=='__main__':unittest.main(verbosity=2)
