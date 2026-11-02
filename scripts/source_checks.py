#!/usr/bin/env python3
"""Run source checks once; reuse their receipt only for the identical source tree."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def fingerprint():
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT)
    digest = hashlib.sha256()
    for name in sorted(set(names.split(b'\0')) - {b''}):
        path = ROOT / name.decode('utf-8')
        digest.update(name + b'\0')
        digest.update(path.read_bytes() if path.is_file() else b'<missing>')
        digest.update(b'\0')
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipt', required=True, type=Path)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    current = fingerprint()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if args.verify:
        data = json.loads(args.receipt.read_text(encoding='utf-8'))
        if (data.get('schema') != 'ziyu-source-checks-v1' or data.get('status') != 'passed'
                or data.get('sourceSha256') != current or data.get('commit') != head):
            raise ValueError('Source checks receipt is stale; run source_checks.py again')
        print('Reusing passing source checks for the identical source tree')
        return
    args.receipt.unlink(missing_ok=True)
    started = time.monotonic()
    subprocess.run(['sh', '-x', str(ROOT / 'scripts/check.sh')], cwd=ROOT, check=True)
    if fingerprint() != current:
        raise ValueError('Source changed during checks; no passing receipt was issued')
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps({'schema': 'ziyu-source-checks-v1', 'status': 'passed',
                            'commit': head, 'sourceSha256': current,
                            'elapsedSeconds': round(time.monotonic() - started, 3)}, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
