"""Exercise collection progress propagation through the real switch worker loop."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CollectionProgressTest(unittest.TestCase):
    def test_real_worker_reports_faces_merge_and_preserves_budget_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory) / 'module'
            (module / 'common').mkdir(parents=True)
            (module / 'config').mkdir()
            (module / 'logs').mkdir()
            manager = module / 'fake-manager.sh'
            manager.write_text('''#!/bin/sh
printf 'percent=88\\nmessage=正在生成原厂 CJK 字体集合\\n' > "$LUOSHU_SWITCH_PROGRESS_FILE"
printf '%s\\n' '{"stage":"collection-faces","message":"本机 CJK 集合已编译 1/3 面","percent":95}' > "$LUOSHU_COLLECTION_PROGRESS_FILE"
sleep 3
printf '%s\\n' '{"stage":"collection-merge","message":"正在合并本机 CJK 集合","percent":97}' > "$LUOSHU_COLLECTION_PROGRESS_FILE"
sleep 3
printf '%s\\n' '{"status":"ok"}'
''', encoding='utf-8')
            definitions = (ROOT/'common/font_switch_task.sh').read_text(encoding='utf-8').split('\ncase "${1:-status}" in', 1)[0]
            runner = module / 'test.sh'
            runner.write_text(definitions + '''
write_task() { printf '%s|%s\\n' "$4" "${13}" >> "$CAPTURE"; }
run_bounded font "$MODDIR/output.json" task-1 1 "$MODDIR/config/progress.conf"
''', encoding='utf-8')
            shell = shutil.which('bash') or r'C:\Program Files\Git\bin\bash.exe'
            capture = module/'capture.txt'
            env = {**os.environ, 'MODDIR': module.as_posix(), 'LUOSHU_FONT_MANAGER': manager.as_posix(),
                   'LUOSHU_SWITCH_HEARTBEAT_INTERVAL': '1', 'CAPTURE': capture.as_posix()}
            result = subprocess.run([shell, runner.as_posix()], env=env, capture_output=True,
                                    text=True, encoding='utf-8', timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            data = capture.read_text(encoding='utf-8')
            self.assertIn('本机 CJK 集合已编译 1/3 面|88', data)
            self.assertIn('正在合并本机 CJK 集合|89', data)
            self.assertIn('[SWITCH-BUDGET]', (module/'logs/fontswitch.log').read_text(encoding='utf-8'))
            self.assertFalse((module/'config/progress.conf.collection').exists())


if __name__ == '__main__':
    unittest.main()
