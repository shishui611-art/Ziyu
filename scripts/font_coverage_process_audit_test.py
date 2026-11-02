"""Fixture tests for PID-namespace font attribution and bounded scans."""
from pathlib import Path
import json
import os
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'common'))
import device_font_process_audit as audit
import font_coverage_locator as locator


class FontCoverageProcessAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ziyu-font-coverage-')
        self.root = Path(self.temp.name)
        self.module = self.root / 'module'
        self.payload_file = self.module / '.ziyu-payload' / 'system' / 'fonts' / 'Roboto-Regular.ttf'
        self.payload_file.parent.mkdir(parents=True)
        self.payload_file.write_bytes(b'active-payload-font')
        self.proc_root = self.root / 'proc'
        self.proc_root.mkdir()
        self.payload = audit.PayloadIndex(self.module)
        self.assertEqual(1, len(self.payload))

        self._original_device_helpers = {}
        if not all(hasattr(os, name) for name in ('major', 'minor', 'makedev')):
            for name in ('major', 'minor', 'makedev'):
                self._original_device_helpers[name] = getattr(os, name, None)
            device = self.payload_file.stat().st_dev
            os.major = lambda _device: 0
            os.minor = lambda _device: 1
            os.makedev = lambda major, minor: device if (major, minor) == (0, 1) else -1
            self.device = '00:01'
        else:
            device = self.payload_file.stat().st_dev
            self.device = f'{os.major(device):02x}:{os.minor(device):02x}'

    def tearDown(self):
        for name, original in self._original_device_helpers.items():
            if original is None:
                delattr(os, name)
            else:
                setattr(os, name, original)
        self.temp.cleanup()

    def add_process(self, pid, package, namespace, mapping):
        proc = self.proc_root / str(pid)
        font_dir = proc / 'root' / 'system' / 'fonts'
        font_dir.mkdir(parents=True)
        (proc / 'cmdline').write_bytes(package.encode() + b'\0')
        (proc / 'stat').write_text(f'{pid} (fixture) S ' + ' '.join(['0'] * 18 + ['123']), encoding='ascii')
        target = font_dir / 'Roboto-Regular.ttf'

        if namespace == 'payload':
            os.link(self.payload_file, target)
        elif namespace == 'other':
            other = self.root / f'other-{pid}.ttf'
            other.write_bytes(f'other-{pid}'.encode())
            os.link(other, target)
        elif namespace != 'missing':
            raise AssertionError(namespace)

        if mapping == 'target':
            mapped_file = target
            path = '/system/fonts/Roboto-Regular.ttf'
        elif mapping == 'old':
            mapped_file = self.root / f'old-{pid}.ttf'
            mapped_file.write_bytes(f'old-{pid}'.encode())
            path = '/system/fonts/Roboto-Regular.ttf'
        elif mapping == 'none':
            (proc / 'maps').write_text(
                f'7f000000-7f001000 r--p 00000000 {self.device} 987 /system/framework/example.jar\n',
                encoding='utf-8',
            )
            return {'pid': str(pid), 'process': package}
        elif mapping == 'memfd':
            (proc / 'maps').write_text(
                f'7f000000-7f001000 r--p 00000000 {self.device} 987 /memfd:font-cache (deleted)\n',
                encoding='utf-8',
            )
            return {'pid': str(pid), 'process': package}
        else:
            raise AssertionError(mapping)

        mapped = mapped_file.stat()
        line = (
            f'7f000000-7f001000 r--p 00000000 {self.device} '
            f'{mapped.st_ino} {path}\n'
        )
        (proc / 'maps').write_text(line, encoding='utf-8')
        return {'pid': str(pid), 'process': package}

    def inspect(self, pid, package, namespace, mapping):
        process = self.add_process(pid, package, namespace, mapping)
        return locator._inspect_package(
            package, [process], self.payload, time.monotonic() + 5, self.proc_root,
        )

    def test_exact_payload_inode_is_covered(self):
        result = self.inspect(101, 'com.example.covered', 'payload', 'target')
        self.assertEqual('covered', result['verdict'], result)

    def test_pid_reuse_during_maps_read_does_not_certify_the_selected_app(self):
        process = self.add_process(102, 'com.example.reused', 'payload', 'target')
        with patch.object(locator, '_process_identity', side_effect=['123', '456']):
            result = locator._inspect_package('com.example.reused', [process], self.payload,
                                              time.monotonic() + 5, self.proc_root)
        self.assertEqual('unreadable', result['verdict'], result)
        self.assertEqual(0, result['audited'])

    def test_payload_change_during_scan_rejects_the_attribution(self):
        self.add_process(103, 'com.example.changed', 'payload', 'target')
        with patch.dict(os.environ, {'MODDIR': str(self.module)}):
            with patch.object(audit.PayloadIndex, 'current', return_value=False):
                result = locator._run('locate', 'com.example.changed', proc_root=self.proc_root)
        self.assertEqual('error', result['status'], result)
        self.assertNotIn('data', result)

    def test_replacing_the_active_payload_directory_invalidates_the_index(self):
        self.assertTrue(self.payload.current())
        self.payload.root.rename(self.module / '.previous-payload')
        self.payload.root.mkdir()
        self.assertFalse(self.payload.current())

    def test_old_mapping_with_current_payload_path_is_stale_process(self):
        result = self.inspect(102, 'com.example.stale', 'payload', 'old')
        self.assertEqual('stale-process', result['verdict'], result)
        self.assertTrue(result['files'][0]['staleCache'], result)

    def test_mapping_visible_in_pid_namespace_but_not_payload_is_route_error(self):
        result = self.inspect(103, 'com.example.route', 'other', 'target')
        self.assertEqual('route-not-visible', result['verdict'], result)
        self.assertTrue(result['files'][0]['routeNotVisible'], result)

    def test_no_font_maps_is_inconclusive(self):
        result = self.inspect(104, 'com.example.nomaps', 'missing', 'none')
        self.assertEqual('inconclusive', result['verdict'], result)
        self.assertEqual([], result['files'])

    def test_font_memfd_is_inconclusive(self):
        result = self.inspect(105, 'com.example.memfd', 'missing', 'memfd')
        self.assertEqual('inconclusive', result['verdict'], result)
        self.assertEqual(1, result['memfdFontSegments'], result)

    def test_scan_keeps_uncertain_apps_separate_from_covered_count(self):
        self.add_process(201, 'com.example.covered', 'payload', 'target')
        self.add_process(202, 'com.example.stale', 'payload', 'old')
        self.add_process(203, 'com.example.route', 'other', 'target')
        self.add_process(204, 'com.example.nomaps', 'missing', 'none')
        self.add_process(205, 'com.example.memfd', 'missing', 'memfd')
        previous_moddir = os.environ.get('MODDIR')
        os.environ['MODDIR'] = str(self.module)
        try:
            result = locator._run('scan', proc_root=self.proc_root)
        finally:
            if previous_moddir is None:
                os.environ.pop('MODDIR', None)
            else:
                os.environ['MODDIR'] = previous_moddir

        self.assertEqual('ok', result['status'], result)
        data = result['data']
        self.assertEqual(1, data['covered'], data)
        self.assertEqual(2, data['uncertain'], data)
        verdicts = {item['package']: item['verdict'] for item in data['items']}
        self.assertEqual('stale-process', verdicts['com.example.stale'], verdicts)
        self.assertEqual('route-not-visible', verdicts['com.example.route'], verdicts)
        self.assertEqual('inconclusive', verdicts['com.example.nomaps'], verdicts)
        self.assertEqual('inconclusive', verdicts['com.example.memfd'], verdicts)
        self.assertLessEqual(len(json.dumps(result, ensure_ascii=False).encode('utf-8')),
                             locator.MAX_OUTPUT_BYTES)


if __name__ == '__main__':
    unittest.main()
