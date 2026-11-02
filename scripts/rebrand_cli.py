#!/usr/bin/env python3
"""Reproduce the six equal-length help-text changes in the inherited C CLI.

The original GPL binary and attribution predate this rename. Its input digest
pins every byte; only the six UTF-8 display strings change, with no ELF offsets,
instructions, font handling, or command behavior changed.
"""
import argparse
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_SHA256 = 'eec2861c7b014afd880aebac2038ba12fef01c1444e7a3137ce4fd8b31632d46'
CURRENT_SHA256 = '44ce5305bea6a743a278bd6c955bccd5e2a752a22a38a2cee2084ab5c249e27c'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--binary', type=Path, default=ROOT / 'system/bin/ziyud')
    args = parser.parse_args()
    original = args.binary.read_bytes()
    actual = hashlib.sha256(original).hexdigest()
    if actual == CURRENT_SHA256:
        print('CLI branding digest verified')
        return
    if args.verify or actual != ORIGINAL_SHA256:
        raise SystemExit('CLI digest differs from the reviewed input/output; refusing to patch')
    old, new = '洛书'.encode('utf-8'), '字域'.encode('utf-8')
    if len(old) != len(new) or original.count(old) != 6:
        raise SystemExit('CLI display-string layout differs from the pinned original')
    result = original.replace(old, new)
    if len(result) != len(original) or hashlib.sha256(result).hexdigest() != CURRENT_SHA256:
        raise SystemExit('CLI branding output failed verification')
    temporary = args.binary.with_name(args.binary.name + '.branding.tmp')
    try:
        temporary.write_bytes(result)
        temporary.chmod(args.binary.stat().st_mode & 0o777)
        temporary.replace(args.binary)
    finally:
        temporary.unlink(missing_ok=True)
    print('CLI branding updated; six equal-length display strings changed')


if __name__ == '__main__':
    main()
