#!/usr/bin/env python3
"""Build the bounded Android PID helper; never execute it on the host/device."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    source = ROOT / 'native/pid-wrap/pid_wrap.c'
    folder = ROOT / 'common/pid-wrap'
    receipt = folder / 'build.json'
    if args.verify:
        value = json.loads(receipt.read_text())
        if value['sourceSha256'] != digest(source) or value['builderSha256'] != digest(Path(__file__)):
            raise ValueError('Rebuild PID helper after source or recipe changes')
        for name, expected in value['binaries'].items():
            if digest(folder / name) != expected:
                raise ValueError('PID helper binary digest mismatch: ' + name)
        return
    if not args.ndk:
        parser.error('--ndk required')
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    compiler = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin' / ('clang.exe' if platform.system() == 'Windows' else 'clang')
    folder.mkdir(parents=True, exist_ok=True)
    records = {}
    for abi, target in [('arm64', 'aarch64-linux-android28'), ('arm', 'armv7a-linux-androideabi28'),
                        ('x86_64', 'x86_64-linux-android28'), ('x86', 'i686-linux-android28')]:
        output = folder / ('pid_wrap_' + abi)
        subprocess.run([str(compiler), '--target=' + target, '-std=c11', '-Oz', '-fPIE', '-pie',
                        '-Wall', '-Wextra', '-Werror', '-Wl,-z,max-page-size=16384', '-Wl,-s',
                        str(source), '-o', str(output)], check=True)
        records[output.name] = digest(output)
    receipt.write_text(json.dumps({'sourceSha256': digest(source), 'builderSha256': digest(Path(__file__)),
                                  'binaries': records}, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
