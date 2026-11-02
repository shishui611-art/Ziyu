#!/usr/bin/env python3
"""Retired resident guard: later changes require a NEW explicit one-shot call.

Controlled Android fixtures run the production supervisor; no device rendering is
claimed. The old event/sleep observer contract was removed in release 2.0.0.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from host_task_scope_fixture import install_task_scope
ROOT=Path(__file__).resolve().parents[1]

class GuardResumeTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory(); self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name);self.module=self.root/'module'
        install_task_scope(self.module)
        (self.module/'config').mkdir()
        self.bin=self.root/'bin';self.bin.mkdir()
        self.active=self.module/'config/active_font.conf'; self.active.write_text('custom\n')
        shutil.copyfile(ROOT/'common/font_switch_lock.sh',self.module/'common/font_switch_lock.sh')
        p=self.bin/'getprop';p.write_text('#!/bin/sh\necho 1\n');p.chmod(0o755)
        (self.module/'common/google_font_provider_bridge.sh').write_text('''
case "$1" in
 fingerprint) echo fingerprint >> "$TEST_ROOT/probes";;
 apply) echo apply >> "$TEST_ROOT/applied";exit "${TEST_APPLY_RC:-0}";;
 restore) echo restore >> "$TEST_ROOT/restored";exit "${TEST_RESTORE_RC:-0}";;
esac
''')
        self.env={**os.environ,'MODDIR':str(self.module),'TEST_ROOT':str(self.root),
            'PATH':f'{self.bin}:{os.environ["PATH"]}',
            'ZIYU_GOOGLE_FONT_WATCH_CYCLES':'0','ZIYU_GOOGLE_FONT_WATCH_INTERVAL':'1'}
    def rows(self,name):
        p=self.root/name;return p.read_text().splitlines() if p.exists() else []
    def run_guard(self,**extra):
        return subprocess.run(['sh',str(ROOT/'common/google_font_provider_service.sh')],
            env={**self.env,**extra},capture_output=True,text=True,timeout=10)
    def test_default_then_custom_needs_explicit_second_call(self):
        self.active.write_text('default\n');r=self.run_guard();self.assertEqual(r.returncode,0,r.stderr)
        self.active.write_text('custom\n');self.assertEqual(self.rows('applied'),[])
        self.assertEqual(self.run_guard().returncode,0);self.assertEqual(self.rows('applied'),['apply'])
        self.assertEqual(self.rows('restored'),['restore'])
    def test_return_to_same_selection_is_one_new_call_not_permanent_guard(self):
        self.assertEqual(self.run_guard().returncode,0)
        self.active.write_text('default\n');self.assertEqual(self.run_guard().returncode,0)
        self.active.write_text('custom\n');self.assertEqual(self.run_guard().returncode,0)
        self.assertEqual(self.rows('applied'),['apply','apply']);self.assertEqual(self.rows('probes'),[])
    def test_missing_selection_does_not_wait_forever(self):
        self.active.unlink();self.assertEqual(self.run_guard().returncode,0)
        self.assertEqual(self.rows('applied'),[])
    def test_old_watcher_is_never_executed(self):
        (self.module/'common/google_font_watch_wait.py').write_text('raise RuntimeError("watcher must not run")')
        self.assertEqual(self.run_guard().returncode,0)
        self.assertEqual(self.rows('applied'),['apply']);self.assertEqual(self.rows('probes'),[])
        self.assertFalse((self.module/'.google-font-provider.lock').exists())
    def test_failure_does_not_become_idle_retry_loop(self):
        self.assertEqual(self.run_guard(TEST_APPLY_RC='1').returncode,1)
        self.assertEqual(self.rows('applied'),['apply'])
        self.assertFalse((self.module/'.google-font-provider.lock').exists())
    def test_disabled_before_call_restores_once(self):
        (self.module/'disable').touch();self.assertEqual(self.run_guard().returncode,0)
        self.assertEqual(self.rows('applied'),[]);self.assertEqual(self.rows('restored'),['restore'])
if __name__=='__main__':unittest.main(verbosity=2)
