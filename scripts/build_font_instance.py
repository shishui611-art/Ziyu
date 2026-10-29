"""Build/verify the pinned ARM64 HarfBuzz helper. No device execution or downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '15.0.0'
ARCHIVE_SHA = 'fe4b8bad7afbbae75d61b2095c267c98f4ed04fe4a117680130ed253d2732efe'
SOURCE = ROOT / 'native/font-instance/instance.cpp'
GLYPH_SOURCE = ROOT / 'native/font-instance/glyph-cache.hh'
CFF_SOURCE = ROOT / 'native/font-instance/cff-cache.hh'
BINARY = ROOT / 'common/font-native-instance'
RECORD = ROOT / 'common/font_native_instance_build.json'
LICENSE = ROOT / 'licenses/HarfBuzz-COPYING.txt'
MS_LICENSE = ROOT / 'licenses/HarfBuzz-Microsoft-USE-MIT.txt'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--archive', type=Path, help='Official harfbuzz-15.0.0 tag tar.gz, verified before extraction')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if args.verify:
        record = json.loads(RECORD.read_text(encoding='utf-8'))
        expected = {'sourceSha256': sha(SOURCE), 'glyphSourceSha256': sha(GLYPH_SOURCE), 'cffSourceSha256': sha(CFF_SOURCE), 'builderSha256': sha(__file__),
                    'binarySha256': sha(BINARY), 'licenseSha256': sha(LICENSE), 'msLicenseSha256': sha(MS_LICENSE),
                    'upstreamArchiveSha256': ARCHIVE_SHA, 'harfbuzz': VERSION,
                    'abi': 'arm64-v8a', 'api': 28}
        if any(record.get(key) != value for key, value in expected.items()):
            raise ValueError('Native instance source/provenance/binary mismatch; rebuild required')
        print('Native instance source/license/binary match')
        return
    if not args.ndk or not args.archive:
        parser.error('--ndk and --archive are required')
    if sha(args.archive) != ARCHIVE_SHA:
        raise ValueError('Pinned HarfBuzz archive SHA-256 mismatch')
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    suffix = '.exe' if platform.system() == 'Windows' else ''
    tools = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    flags = ['--target=aarch64-linux-android28', '-std=c++17', '-Oz', '-DNDEBUG', '-DHB_NO_MT',
             '-ffp-contract=off', '-ffunction-sections', '-fdata-sections', '-fno-exceptions', '-fno-rtti', '-static-libstdc++',
             '-Wl,--gc-sections', '-Wl,-s', '-Wl,-z,max-page-size=16384']
    with tempfile.TemporaryDirectory(prefix='ziyu-hb-') as temporary:
        temp = Path(temporary)
        with tarfile.open(args.archive, 'r:gz') as archive:
            prefix = f'harfbuzz-{VERSION}/'
            for member in archive.getmembers():
                if member.isfile() and (member.name.startswith(prefix + 'src/') or member.name == prefix + 'COPYING'):
                    relative = Path(member.name)
                    if '..' in relative.parts or relative.is_absolute():
                        raise ValueError('Unsafe source member')
                    dest = temp / relative
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(archive.extractfile(member).read())
        source = temp / f'harfbuzz-{VERSION}'
        native = temp / 'font-native-instance'
        subprocess.run([str(tools / ('clang++' + suffix)), *flags, '-I', str(source / 'src'),
                        str(source / 'src/harfbuzz-subset.cc'), str(SOURCE), '-o', str(native)], check=True)
        dynamic = subprocess.check_output([str(tools / ('llvm-readelf' + suffix)), '-d', str(native)], text=True)
        if 'libc++' in dynamic or 'harfbuzz' in dynamic.lower():
            raise ValueError('Helper must not depend on external C++/HarfBuzz libraries')
        BINARY.write_bytes(native.read_bytes())
        BINARY.chmod(0o755)
        LICENSE.write_bytes((source / 'COPYING').read_bytes())
        MS_LICENSE.write_bytes((source / 'src/ms-use/COPYING').read_bytes())
    record = {'schema': 'ziyu-native-instance-build-v1', 'harfbuzz': VERSION,
              'upstream': f'https://github.com/harfbuzz/harfbuzz/tree/{VERSION}',
              'upstreamArchiveSha256': ARCHIVE_SHA, 'abi': 'arm64-v8a', 'api': 28, 'flags': flags,
              'ndk': (args.ndk / 'source.properties').read_text(),
              'sourceSha256': sha(SOURCE), 'glyphSourceSha256': sha(GLYPH_SOURCE), 'cffSourceSha256': sha(CFF_SOURCE),
              'builderSha256': sha(__file__), 'binarySha256': sha(BINARY),
              'licenseSha256': sha(LICENSE), 'msLicenseSha256': sha(MS_LICENSE), 'bytes': BINARY.stat().st_size}
    RECORD.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(record, ensure_ascii=False))


if __name__ == '__main__':
    main()
