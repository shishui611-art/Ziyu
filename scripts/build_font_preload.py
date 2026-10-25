#!/usr/bin/env python3
"""Build the small, separately loadable Zygisk helper with an installed NDK.

No SDK downloads, device execution or tests. The main module never has a root
zygisk/ directory, so disabling Zygisk cannot suppress its ordinary boot scripts.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HEADER_SHA256 = 'fc6523882c0f8659b4fcd1e04ffb07d3a948090e5a8c08817af6e2b506a078d0'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--verify', action='store_true', help='Check source/header/binary correspondence without building')
    args = parser.parse_args()
    source = ROOT / 'native/font-preload'
    if hashlib.sha256((source / 'zygisk.hpp').read_bytes()).hexdigest() != HEADER_SHA256:
        raise ValueError('Pinned upstream Zygisk API header digest mismatch')
    output = ROOT / 'common/font_preload_module'
    if args.verify:
        record = json.loads((output / 'build.json').read_text(encoding='utf-8'))
        if record['sourceSha256'] != hashlib.sha256((source / 'preload.cpp').read_bytes()).hexdigest():
            raise ValueError('Native font preloader must be rebuilt after its source changes')
        if record.get('builderSha256') != hashlib.sha256(Path(__file__).read_bytes()).hexdigest():
            raise ValueError('Native font preloader must be rebuilt after its build recipe changes')
        if record['apiHeaderSha256'] != HEADER_SHA256 or record['apiVersion'] != 4:
            raise ValueError('Unexpected preloader API provenance')
        for abi in ('arm64-v8a', 'armeabi-v7a'):
            data = (output / 'zygisk' / (abi + '.so')).read_bytes()
            if hashlib.sha256(data).hexdigest() != record['libraries'][abi]['sha256'] or len(data) != record['libraries'][abi]['bytes']:
                raise ValueError('Native font preloader binary digest mismatch: ' + abi)
        print('Native font preloader source/header/binaries match')
        return
    if not args.ndk:
        parser.error('--ndk is required for a native build')
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    toolchain = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    extension = '.exe' if platform.system() == 'Windows' else ''
    compiler = toolchain / ('clang++' + extension)
    readelf = toolchain / ('llvm-readelf' + extension)
    libs = output / 'zygisk'
    libs.mkdir(parents=True, exist_ok=True)
    records = {}
    for abi, target in [('arm64-v8a', 'aarch64-linux-android28'), ('armeabi-v7a', 'armv7a-linux-androideabi28')]:
        path = libs / (abi + '.so')
        command = [str(compiler), '--target=' + target, '-std=c++17', '-Oz', '-shared', '-fPIC',
                   '-fno-exceptions', '-fno-rtti', '-fno-threadsafe-statics', '-fvisibility=hidden',
                   '-nostdlib++', '-Wall', '-Wextra', '-Werror', '-Wl,--no-undefined',
                   '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-Wl,--build-id=sha1',
                   '-Wl,-s', '-Wl,--as-needed', '-DZIYU_PRELOAD_ABI="' + abi + '"', str(source / 'preload.cpp'),
                   '-llog', '-o', str(path)]
        subprocess.run(command, check=True)
        dynamic = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        if 'libc++' in dynamic:
            raise ValueError('Helper must not depend on a separate C++ runtime')
        exports = subprocess.check_output([str(readelf), '--dyn-syms', str(path)], text=True)
        if any(symbol not in exports for symbol in ('zygisk_module_entry', 'zygisk_companion_entry')):
            raise ValueError('Missing Zygisk entry point')
        records[abi] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size}
    properties = dict(line.split('=', 1) for line in (ROOT / 'module.prop').read_text(encoding='utf-8').splitlines() if '=' in line)
    (output / 'module.prop').write_text(
        'id=ziyu-font-preload\nname=ziyu 字体预加载（实验性）\n'
        f'version={properties["version"]}\nversionCode={properties["versionCode"]}\n'
        'author=字域\ndescription=配合字域已验证字体负载，在应用撤挂载前准备字体缓存；需要可执行回调的 Zygisk，不修改排除策略。\n', encoding='utf-8', newline='\n')
    (output / 'ziyu-owner.conf').write_text('schema=ziyu-font-preload-owner-v1\nowner=ziyu\n', encoding='utf-8', newline='\n')
    (output / 'skip_mount').write_text('', encoding='utf-8', newline='\n')
    (output / 'build.json').write_text(json.dumps({'schema': 'ziyu-font-preload-build-v1',
        'version': properties['version'], 'ndk': (args.ndk / 'source.properties').read_text(),
        'sourceSha256': hashlib.sha256((source / 'preload.cpp').read_bytes()).hexdigest(),
        'builderSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'apiHeaderSha256': HEADER_SHA256, 'apiVersion': 4, 'libraries': records}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(records, indent=2))


if __name__ == '__main__':
    main()
