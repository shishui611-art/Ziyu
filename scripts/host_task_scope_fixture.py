"""Use the production task supervisor on host Python, not an ARM64 binary."""
from pathlib import Path
import shutil
import shlex
import sys
ROOT = Path(__file__).resolve().parents[1]
def install_task_scope(module: Path) -> None:
    common = module/'common'
    (common/'python/bin').mkdir(parents=True,exist_ok=True)
    for name in ('task_scope.py','task_scope.sh','background_task.sh'):
        shutil.copyfile(ROOT/'common'/name,common/name)
    interpreter=common/'python/bin/ziyu-python'
    interpreter.write_text('#!/bin/sh\nunset PYTHONHOME PYTHONPATH LD_LIBRARY_PATH\nexec '+shlex.quote(sys.executable)+' "$@"\n')
    interpreter.chmod(0o755)
