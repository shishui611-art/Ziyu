#!/usr/bin/env python3
"""Host regressions for the isolated 1.1.1 repair, NOT Android device validation."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'common'), str(ROOT/'scripts')]
import hyperos_cjk_routing_test as routing
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables.S_V_G_ import SVGDocument
import fontTools.subset.svg as svg_subset


class SvgCompatibility(unittest.TestCase):
    def check_svg(self, cff=False, variable=False):
        rig = routing.RoutingTest()
        rig.setUp()
        self.addCleanup(rig.doCleanups)
        rig.default_pair(cff=cff, variable=variable)
        source = rig.fonts/'400.ttf'
        with TTFont(source) as font:
            table = newTable('SVG ')
            a = font.getGlyphID(font.getBestCmap()[routing.LATIN])
            b = len(font.getGlyphOrder())-1
            table.docList = [SVGDocument(
                '<svg xmlns="http://www.w3.org/2000/svg"><defs><path id="ink" d="M0 0L1 1"/></defs>'
                f'<g id="glyph{a}"><use href="#ink"/></g><g id="glyph{b}"/></svg>', a, b)]
            font['SVG '] = table
            font.save(source)
        before = hashlib.sha256(source.read_bytes()).hexdigest()
        # Reproduce the phone's missing optional dependency, even on hosts
        # which happen to have lxml installed.
        with patch.object(svg_subset, 'etree', None):
            rig.build()
        self.assertEqual(before, hashlib.sha256(source.read_bytes()).hexdigest())
        with TTFont(source, lazy=True) as original, TTFont(rig.fonts/'Roboto-Regular.ttf', lazy=True) as output:
            self.assertEqual(original.getGlyphOrder(), output.getGlyphOrder())
            for tag in ('SVG ', 'glyf', 'loca', 'CFF ', 'gvar'):
                if tag in original:
                    self.assertEqual(original.reader[tag], output.reader[tag], tag)
            self.assertNotIn(routing.HAN, output.getBestCmap())
            self.assertIn(routing.LATIN, output.getBestCmap())
            self.assertIn(48, output.getBestCmap())
        self.assertTrue(rig.reports['/system/fonts/Roboto-Regular.ttf']['svgPreserved'])
        self.assertFalse(list((rig.fonts/'.ziyu-font-store').glob('hyperos-metrics-*')))

    def test_svg_ttf_without_lxml(self): self.check_svg()
    def test_svg_cff_without_lxml(self): self.check_svg(cff=True)
    def test_svg_variable_without_lxml(self): self.check_svg(variable=True)


class TaskOwnership(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pids = self.root/'pids'
        self.pids.mkdir()
        self.known = []
        self.addCleanup(self.cleanup_known)
        self.worker = self.root/'worker.py'
        self.worker.write_text('''import os, signal, subprocess, sys, time
from pathlib import Path
root=Path(sys.argv[1]); mode=sys.argv[2]
(root/str(os.getpid())).write_text(str(os.getpid()))
if mode == 'leaf':
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    while True: time.sleep(1)
p=subprocess.Popen([sys.executable, __file__, str(root), 'leaf'], start_new_session=True)
while not (root/str(p.pid)).exists(): time.sleep(.01)
if mode == 'success': sys.exit(0)
if mode == 'failure': sys.exit(7)
while True: time.sleep(1)
''')
        self.sentinel = subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])
        self.addCleanup(self.stop, self.sentinel)

    def stop(self, proc):
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=5)

    def cleanup_known(self):
        for f in self.pids.glob('*'):
            try: os.kill(int(f.name), signal.SIGKILL)
            except ProcessLookupError: pass

    def launch(self, mode, timeout=5):
        proc = subprocess.Popen([sys.executable,str(ROOT/'common/task_scope.py'),
             '--timeout',str(timeout),'--',sys.executable,str(self.worker),str(self.pids),mode],
             stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.addCleanup(self.stop,proc)
        return proc

    def check_finish(self, proc, code):
        out, err = proc.communicate(timeout=8)
        self.assertEqual(proc.returncode,code,(out,err))
        report = json.loads(next(line.removeprefix('[TASK-CLEANUP] ') for line in err.splitlines()
                                  if line.startswith('[TASK-CLEANUP] ')))
        self.assertEqual(report['leftoverPids'],[],report)
        for f in self.pids.glob('*'):
            self.assertFalse(Path('/proc',f.name).exists(),f'unreaped child {f.name}')
        self.assertIsNone(self.sentinel.poll(),'unrelated process was killed')

    def test_success_reaps_setsid_orphan(self): self.check_finish(self.launch('success'),0)
    def test_failure_reaps_setsid_orphan(self): self.check_finish(self.launch('failure'),7)
    def test_timeout_reaps_descendants(self): self.check_finish(self.launch('wait',.4),124)
    def check_signal(self,sig):
        proc = self.launch('wait')
        end=time.monotonic()+4
        while len(list(self.pids.glob('*'))) < 2 and time.monotonic() < end:
            time.sleep(.02)
        self.assertEqual(len(list(self.pids.glob('*'))),2)
        proc.send_signal(sig)
        self.check_finish(proc,128+sig)
    def test_term_reaps_descendants(self): self.check_signal(signal.SIGTERM)
    def test_int_reaps_descendants(self): self.check_signal(signal.SIGINT)
    def test_hup_reaps_descendants(self): self.check_signal(signal.SIGHUP)
    def test_failed_exec(self):
        proc=subprocess.Popen([sys.executable,str(ROOT/'common/task_scope.py'),'--','/missing/command'],
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.check_finish(proc,127)


class ShellIntegration(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.module=Path(self.temp.name)/'module'
        (self.module/'common/python/bin').mkdir(parents=True)
        (self.module/'config').mkdir()
        (self.module/'logs').mkdir()
        (self.module/'module.prop').write_text('id=Ziyu\n')
        for name in ('task_scope.sh','task_scope.py','background_task.sh','font_switch_lock.sh'):
            shutil.copyfile(ROOT/'common'/name,self.module/'common'/name)
        py=self.module/'common/python/bin/ziyu-python'
        py.write_text(f'#!/bin/sh\nunset PYTHONHOME PYTHONPATH LD_LIBRARY_PATH\nexec {sys.executable} "$@"\n')
        py.chmod(0o755)
        bindir=Path(self.temp.name)/'bin';bindir.mkdir()
        prop=bindir/'getprop';prop.write_text('#!/bin/sh\necho 1\n');prop.chmod(0o755)
        self.env={**os.environ,'MODDIR':str(self.module),'PATH':str(bindir)+':'+os.environ['PATH']}

    def test_launcher_fast_exit_cleans_sidecars_after_worker_finishes(self):
        pidfile=self.module/'config/test.pid'
        logfile=self.module/'logs/test.log'
        subprocess.run(['sh','-c','''. "$MODDIR/common/background_task.sh"
ziyu_start_detached "$1" stable111-task "$2" sh -c '
. "$MODDIR/common/background_task.sh"
ziyu_clear_task_pid "$ZIYU_TASK_SCOPE_PID_FILE" "$ZIYU_TASK_SCOPE_TASK"
[ -s "$ZIYU_TASK_SCOPE_PID_FILE" ] || exit 12
' ''' ,'sh',str(pidfile),str(logfile)],env=self.env,check=True,timeout=5)
        end=time.monotonic()+5
        proof=Path(str(pidfile)+'.cleanup.json')
        while not proof.exists() and time.monotonic()<end: time.sleep(.05)
        self.assertTrue(proof.exists(),logfile.read_text())
        report=json.loads(proof.read_text())
        self.assertEqual(report['result'],0)
        self.assertEqual(report['leftoverPids'],[])
        for suffix in ('','.task','.boot'): self.assertFalse(Path(str(pidfile)+suffix).exists())

    def test_provider_has_one_apply_even_when_old_watch_settings_request_infinite(self):
        shutil.copyfile(ROOT/'common/google_font_provider_service.sh',self.module/'common/google_font_provider_service.sh')
        (self.module/'config/active_font.conf').write_text('custom\n')
        bridge=self.module/'common/google_font_provider_bridge.sh'
        bridge.write_text('''case "$1" in
fingerprint) echo unchanged;;
apply) echo apply >> "$MODDIR/logs/applies";;
restore) echo restore >> "$MODDIR/logs/applies";;
esac
''')
        result=subprocess.run(['sh',str(self.module/'common/google_font_provider_service.sh'),'boot'],
            env={**self.env,'ZIYU_GOOGLE_FONT_RETRIES':'999','ZIYU_GOOGLE_FONT_WATCH_CYCLES':'-1'},
            capture_output=True,text=True,timeout=6)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual((self.module/'logs/applies').read_text(),'apply\n')
        self.assertIn('"leftoverPids": []',result.stderr)
        self.assertFalse((self.module/'.google-font-provider.lock').exists())

    def test_prewarm_endpoints_do_not_launch_workers(self):
        script=ROOT/'common/legacy_v14_4/font_switch_safe.sh'
        for action in ('prewarm','prewarm-start'):
            result=subprocess.run(['sh',str(script),'action',action,'example'],
                env={**self.env,'ZIYU_PUBLIC_DIR':str(Path(self.temp.name)/'public')},
                capture_output=True,text=True,timeout=5)
            self.assertEqual(result.returncode,0,result.stderr)
        self.assertFalse(list((self.module/'config').glob('font-prewarm-*.pid')))
        self.assertFalse((self.module/'.safe-switch-prewarm.lock').exists())

if __name__=='__main__': unittest.main(verbosity=2)
