#!/usr/bin/env python3
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'common'))
import diagnostic_capture as capture


class CaptureTest(unittest.TestCase):
    def test_pid_reuse_and_new_boot_are_not_owned(self):
        state = {'bootId':'boot1', 'pid':42, 'startTime':'123'}
        with patch.object(capture, 'boot_id', return_value='boot1'), patch.object(capture, 'identity', return_value='123'):
            self.assertTrue(capture.alive(state))
        with patch.object(capture, 'boot_id', return_value='boot1'), patch.object(capture, 'identity', return_value='124'):
            self.assertFalse(capture.alive(state))
        with patch.object(capture, 'boot_id', return_value='boot2'), patch.object(capture, 'identity', return_value='123'):
            self.assertFalse(capture.alive(state))

    def test_archive_is_bounded_and_raw_logs_survive(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            raw = b'x'*200000
            (directory/'logcat.log.1').write_bytes(raw)
            (directory/'start.txt').write_text('FATAL EXCEPTION test\navc: denied\n')
            with patch.object(capture, 'LIMIT', 100000):
                output = capture.package(directory, {'session':'test', 'sensitive':False})
            self.assertEqual((directory/'logcat.log.1').read_bytes(), raw)
            with zipfile.ZipFile(output) as archive:
                self.assertIsNone(archive.testzip())
                self.assertLessEqual(sum(row.file_size for row in archive.infolist()), 100000-65536)
                report = json.loads(archive.read('report.json'))
                self.assertEqual(report['keywordMatches']['crash'], 1)
                self.assertEqual(report['keywordMatches']['avc'], 1)

    def test_child_stop_uses_only_supplied_popen(self):
        child = Mock(pid=222)
        child.poll.return_value = None
        with patch.object(capture.os, 'killpg', create=True) as kill:
            capture.stop_child(child)
        kill.assert_called_once_with(222, capture.signal.SIGTERM)
        child.wait.assert_called_once_with(timeout=2)

    def test_completed_child_is_not_signalled(self):
        child = Mock(pid=222); child.poll.return_value = 0
        with patch.object(capture.os, 'killpg', create=True) as kill:
            capture.stop_child(child)
        kill.assert_not_called()

    def test_session_traversal_rejected(self):
        with self.assertRaises(ValueError): capture.state_for('../other-process')

    def test_start_without_process_identity_stops_only_new_child(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            child = Mock(pid=333)
            def secure(path): path.mkdir(parents=True, exist_ok=True)
            with patch.object(capture, 'ROOT', root), patch.dict(sys.modules, {'fcntl': Mock(), 'adb_storage': Mock(secure_directory=secure)}), \
                 patch.object(capture, 'state_for', return_value=(None,None)), patch.object(capture, 'identity', return_value=''), \
                 patch.object(capture.subprocess, 'Popen', return_value=child), patch.object(capture, 'stop_child') as stop:
                with self.assertRaisesRegex(ValueError, '进程身份'):
                    capture.command(root, 'start', '', False)
            stop.assert_called_once_with(child)
            self.assertFalse((root/'current.json').exists())


if __name__ == '__main__': unittest.main()
