import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ActionControlTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('action_control', ROOT/'common/action_control.py')
        self.control = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.control)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.mod = Path(self.temp.name)
        (self.mod/'config').mkdir()

    def test_discard_pending_preserves_live_and_previous_selection(self):
        (self.mod/'.luoshu-payload').mkdir()
        (self.mod/'.luoshu-payload/font').write_text('old')
        (self.mod/'.luoshu-payload-next').mkdir()
        (self.mod/'config/font-payload-next.conf').write_text('font=new\npreviousFont=old\n')
        (self.mod/'config/active_font.conf').write_text('new\n')
        result = self.control.undo(self.mod)
        self.assertFalse(result['rebootRequired'])
        self.assertEqual('old', (self.mod/'.luoshu-payload/font').read_text())
        self.assertEqual('old\n', (self.mod/'config/active_font.conf').read_text())
        self.assertFalse((self.mod/'.luoshu-payload-next').exists())

    def test_undo_after_boot_stages_previous_payload_without_touching_live(self):
        retired = self.mod/'.luoshu-retired/payload-test'
        retired.mkdir(parents=True)
        (retired/'font').write_text('old')
        (self.mod/'.luoshu-payload').mkdir()
        (self.mod/'.luoshu-payload/font').write_text('new')
        (self.mod/'config/font-undo.conf').write_text(
            f'previousFont=old\npreviousMode=legacy\nretired={retired}\nfont=new\n')
        result = self.control.undo(self.mod)
        self.assertTrue(result['rebootRequired'])
        self.assertEqual('old', (self.mod/'.luoshu-payload-next/font').read_text())
        self.assertEqual('new', (self.mod/'.luoshu-payload/font').read_text())
        self.assertTrue(retired.exists())

    def test_undo_rejects_retired_path_outside_module(self):
        (self.mod/'config/font-undo.conf').write_text(
            f'previousFont=old\npreviousMode=legacy\nretired={self.mod.parent}\n')
        with self.assertRaises(ValueError):
            self.control.undo(self.mod)

    def test_viewed_does_not_clear_and_clear_preserves_task_and_rollback(self):
        (self.mod/'logs').mkdir()
        log = self.mod/'logs/fontswitch.log'
        log.write_text('ERROR diagnosis\n')
        (self.mod/'logs/unknown.bin').write_text('keep')
        task = self.mod/'config/switch_task.conf'
        task.write_text('state=running\n')
        undo = self.mod/'config/font-undo.conf'
        undo.write_text('previousFont=old\n')
        self.control.review_logs(self.mod, 'mark')
        self.assertEqual('ERROR diagnosis\n', log.read_text())
        self.control.review_logs(self.mod, 'clear')
        self.assertEqual('', log.read_text())
        self.assertEqual('state=running\n', task.read_text())
        self.assertTrue(undo.exists())
        self.assertEqual('keep', (self.mod/'logs/unknown.bin').read_text())

    def test_status_reports_pending_vs_boot_undo_truthfully(self):
        self.assertFalse(self.control.status(self.mod)['undoAvailable'])
        (self.mod/'config/font-payload-next.conf').write_text('font=new\npreviousFont=old\n')
        result = self.control.status(self.mod)
        self.assertTrue(result['undoAvailable'])
        self.assertFalse(result['undoRebootRequired'])
        self.assertEqual('old', result['targetFont'])

    def test_owned_pending_cancellation_never_cancels_another_task(self):
        (self.mod/'.luoshu-payload-next').mkdir()
        state = self.mod/'config/font-payload-next.conf'
        state.write_text('taskId=other\nfont=new\npreviousFont=old\n')
        self.control.discard_pending(self.mod, 'wanted')
        self.assertTrue(state.exists())
        state.write_text('taskId=wanted\nfont=new\npreviousFont=old\n')
        self.control.discard_pending(self.mod, 'wanted')
        self.assertFalse(state.exists())

    def test_supervisor_identity_rejects_recycled_or_non_supervisor_pid(self):
        proc = self.mod/'proc'
        (proc/'sys/kernel/random').mkdir(parents=True)
        (proc/'sys/kernel/random/boot_id').write_text('boot')
        (proc/'42').mkdir()
        pid = self.mod/'worker.pid'
        pid.write_text('42')
        Path(str(pid)+'.task').write_text('wanted')
        Path(str(pid)+'.boot').write_text('boot')
        (proc/'42/stat').write_text('42 (python) S 1 '+ '0 '*17 +'123 0')
        (proc/'42/cmdline').write_bytes(b'python\0task_scope.py\0--task\0wanted\0')
        self.assertEqual((42, 123), self.control.supervisor_identity(pid, 'wanted', proc))
        (proc/'42/cmdline').write_bytes(b'python\0other.py\0wanted\0')
        self.assertIsNone(self.control.supervisor_identity(pid, 'wanted', proc))
        Path(str(pid)+'.boot').write_text('old-boot')
        self.assertIsNone(self.control.supervisor_identity(pid, 'wanted', proc))

    def test_verified_prune_keeps_undo_and_recovery_but_removes_historical_owned_snapshots(self):
        retired = self.mod/'.luoshu-retired'
        for name in ('payload-undo', 'universal-recovery', 'payload-old'):
            (retired/name).mkdir(parents=True)
            (retired/name/'font').write_text(name)
        (self.mod/'config/font-undo.conf').write_text(f'retired={retired/"payload-undo"}\n')
        (self.mod/'config/universal-font-activated.conf').write_text(f'retired={retired/"universal-recovery"}\n')
        # No verification => no prune.
        self.assertEqual(0, self.control.prune_retired(self.mod)['removed'])
        (self.mod/'config/device-font-load-verification.conf').write_text('state=verified\n')
        (self.mod/'config/axes_task.conf').write_text('state=running\n')
        self.assertEqual(0, self.control.prune_retired(self.mod)['removed'])
        (self.mod/'config/axes_task.conf').write_text('state=cancelled\n')
        self.assertEqual(1, self.control.prune_retired(self.mod)['removed'])
        self.assertTrue((retired/'payload-undo/font').exists())
        self.assertTrue((retired/'universal-recovery/font').exists())
        self.assertFalse((retired/'payload-old').exists())


if __name__ == '__main__':
    unittest.main()
