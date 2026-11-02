#!/usr/bin/env python3
"""Inspect real APK metadata/signatures and verify a module against its source manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import zipfile

from release_version_policy import read_properties, version_info

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.shishui611_art.ziyu'
RELEASE_CERT = 'ebfd6167fe727ab3ada7dd5cf44dc3d3c345812fab4f6d5699f69ea572688468'


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def metadata():
    props = read_properties(ROOT / 'module.prop')
    info = version_info(props['version'], props.get('versionSeries', ''))
    if int(props['versionCode']) != info['versionCode']:
        raise ValueError('module.prop versionCode does not match the version policy')
    if read_properties(ROOT / 'config/version_notes.conf')['version'] != info['version']:
        raise ValueError('config/version_notes.conf is out of date')
    if not (ROOT / info['notesFile']).is_file():
        raise ValueError('Missing release notes: ' + info['notesFile'])
    changelog = (ROOT / '更新日志/CHANGELOG.md').read_text(encoding='utf-8')
    if not re.search(r'^## ' + re.escape(info['version']) + r'(?:\s|$)', changelog, re.M):
        raise ValueError('Missing version in CHANGELOG.md')
    return info


def sdk_tool(name):
    suffix = '.bat' if name == 'apksigner' and os.name == 'nt' else '.exe' if os.name == 'nt' else ''
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        sdk = os.environ.get(key)
        if sdk:
            folders = sorted((Path(sdk) / 'build-tools').glob('*'),
                             key=lambda path: tuple(int(n) for n in re.findall(r'\d+', path.name)), reverse=True)
            for folder in folders:
                tool = folder / (name + suffix)
                if tool.is_file():
                    return str(tool)
    tool = shutil.which(name)
    if tool:
        return tool
    raise ValueError(f'Cannot find {name}; set ANDROID_HOME to an installed Android SDK')


def command(args):
    result = subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding='utf-8', errors='replace')
    return result.stdout


def inspect_apk(path, allow_debug):
    info = metadata()
    badging = command([sdk_tool('aapt2'), 'dump', 'badging', str(path.resolve())])
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match:
        raise ValueError('Cannot read APK package/version metadata')
    package, code, name = match.groups()
    expected = info['version'].removeprefix('v').lower()
    debug = name == expected + '-debug'
    if package not in (PACKAGE, PACKAGE + '.debug') or int(code) != info['appVersionCode']:
        raise ValueError(f'APK identity mismatch: {package} {name} ({code})')
    if name != expected and not debug:
        raise ValueError(f'APK versionName mismatch: expected {expected}, got {name}')
    if (debug or package.endswith('.debug')) and not allow_debug:
        raise ValueError('Debug APK requires ZIYU_ALLOW_DEBUG_APP=1')
    if package.endswith('.debug') and not debug:
        raise ValueError('Legacy .debug package must also have a Debug version label')
    signature = command([sdk_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(path.resolve())])
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', signature, re.M)
    if len(certs) != 1 or 'Number of signers: 1' not in signature:
        raise ValueError('APK must have exactly one verified signing certificate')
    if not re.search(r'^Verified using v(?:2|3|3\.1|3\.2) scheme.*: true$', signature, re.M):
        raise ValueError('APK requires a verified v2/v3 signature')
    cert = certs[0].lower()
    expected_cert = os.environ.get('ZIYU_EXPECTED_CERT_SHA256', RELEASE_CERT if not debug else '')
    if expected_cert and cert != expected_cert.lower().replace(':', ''):
        raise ValueError('APK certificate does not match the existing release identity')
    return {'package': package, 'versionCode': int(code), 'versionName': name,
            'variant': 'debug' if debug else 'release', 'sha256': sha(path),
            'certificateSha256': cert, 'releaseCertificate': cert == RELEASE_CERT,
            'signatureVerified': True}


def properties_bytes(data):
    return dict(line.split('=', 1) for line in data.decode('utf-8').splitlines()
                if line and not line.startswith('#') and '=' in line)


def verify_module(path, apk, apk_report):
    info = metadata()
    report = json.loads(apk_report.read_text(encoding='utf-8'))
    if report != inspect_apk(apk, os.environ.get('ZIYU_ALLOW_DEBUG_APP') == '1'):
        raise ValueError('APK changed after pre-packaging verification')
    explicit = []
    for line in (ROOT / 'scripts/module_payload_manifest.txt').read_text(encoding='utf-8').splitlines():
        entry = line.strip()
        if not entry or entry.startswith('#'):
            continue
        relative = PurePosixPath(entry)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Unsafe payload manifest entry: ' + entry)
        explicit.append(entry)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entries')
        for name in names:
            relative = PurePosixPath(name)
            if 'luoshu' in name.lower():
                raise ValueError('Legacy project name in packaged path: ' + name)
            if relative.is_absolute() or '..' in relative.parts or '\\' in name:
                raise ValueError('Unsafe ZIP member: ' + name)
            if (set(relative.parts) & {'webroot', '__pycache__'} or name.endswith('.pyc')
                    or name.startswith(('docs/', '更新日志/')) or name == 'README.md'):
                raise ValueError('Forbidden ZIP member: ' + name)
        if archive.testzip() is not None:
            raise ValueError('ZIP CRC check failed')
        for entry in explicit:
            source = ROOT / entry
            if source.is_file():
                if entry not in names or archive.read(entry) != source.read_bytes():
                    raise ValueError('Missing or changed payload file: ' + entry)
            elif not source.is_dir() or not any(n.startswith(entry + '/') for n in names):
                raise ValueError('Missing payload directory: ' + entry)
        module = properties_bytes(archive.read('module.prop'))
        bundled = properties_bytes(archive.read('bundled/app.prop'))
        if module['version'] != info['version'] or int(module['versionCode']) != info['versionCode']:
            raise ValueError('Packaged module version mismatch')
        for key in ('package', 'versionName', 'versionCode', 'sha256'):
            if bundled.get(key) != str(report[key]):
                raise ValueError('bundled/app.prop mismatch: ' + key)
        with archive.open('bundled/Ziyu-App.apk') as stream:
            embedded_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
        if embedded_sha != report['sha256']:
            raise ValueError('Embedded APK differs from the standalone APK')
        if [name for name in names if name.endswith('module.prop')] != ['module.prop']:
            raise ValueError('Expected one module identity')
        if any(name.startswith(('common/font_preload_module/', 'zygisk/')) for name in names):
            raise ValueError('Retired experimental module must not be bundled')
        for name in ('common/font-native-instance', 'common/python/bin/ziyu-python', 'system/bin/ziyud'):
            if not archive.getinfo(name).external_attr >> 16 & 0o111:
                raise ValueError('Runtime executable mode missing: ' + name)
        count = len(names)
    return {'schema': 'ziyu-delivery-v1', 'status': 'verified', 'version': info['version'],
            'versionCode': info['versionCode'], 'apk': {'file': apk.name, **report},
            'module': {'file': path.name, 'sha256': sha(path), 'bytes': path.stat().st_size,
                       'entries': count, 'manifestEntriesChecked': len(explicit)},
            'validation': {'apkSignature': 'passed', 'zipCrc': 'passed', 'payloadManifest': 'passed',
                           'embeddedApkMatches': True, 'deviceAcceptance': 'not-performed'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('apk', 'module'):
        cmd = sub.add_parser(action)
        cmd.add_argument('--apk', type=Path, required=True)
        cmd.add_argument('--output', type=Path, required=True)
        if action == 'apk':
            cmd.add_argument('--properties', type=Path, required=True)
        else:
            cmd.add_argument('--zip', type=Path, required=True)
            cmd.add_argument('--apk-report', type=Path, required=True)
    args = parser.parse_args()
    # A failed retry must not leave an old successful report behind.
    args.output.unlink(missing_ok=True)
    if args.action == 'apk':
        args.properties.unlink(missing_ok=True)
        result = inspect_apk(args.apk, os.environ.get('ZIYU_ALLOW_DEBUG_APP') == '1')
        # All values have been constrained above; safe to source as POSIX assignments.
        args.properties.write_text(''.join(f'APP_{key}={result[field]}\n' for key, field in
                                   (('PACKAGE', 'package'), ('VERSION_CODE', 'versionCode'),
                                    ('VERSION_NAME', 'versionName'))), encoding='utf-8', newline='\n')
    else:
        result = verify_module(args.zip, args.apk, args.apk_report)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Verified {args.action}: {args.output}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
