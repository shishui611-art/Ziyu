#!/usr/bin/env python3
"""Check the staged upgrade once; share content reads across font aliases."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from font_digest_cache import FontDigestCache, file_identity


def verify(root, manifest, normalize, partitions):
    root, manifest = Path(root).resolve(strict=True), Path(manifest)
    original = manifest.read_bytes()
    digests, checksums, observed, normalized = FontDigestCache(), {}, [], []
    seen = set()
    for line in original.decode('utf-8').splitlines():
        if not line:
            continue
        fields = line.split('|')
        if len(fields) not in (2, 3):
            raise ValueError('invalid-payload-manifest-record')
        relative, expected = fields[:2]
        components = relative.split('/')
        if (not relative or '\\' in relative or
                any(part in ('', '.', '..') for part in components)):
            raise ValueError('unsafe-payload-manifest-path')
        if components[0] not in partitions:
            raise ValueError('unsupported-payload-manifest-partition')
        if relative in seen:
            raise ValueError('duplicate-payload-manifest-path')
        seen.add(relative)
        path = root / relative
        try:
            resolved = path.resolve(strict=True)
            resolved.relative_to(root)
        except FileNotFoundError:
            raise ValueError('payload-artifact-missing:' + relative) from None
        except (ValueError, RuntimeError):
            raise ValueError('unsafe-payload-artifact-link:' + relative) from None
        info = path.stat()
        if not info.st_size:
            raise ValueError('payload-artifact-missing:' + relative)
        identity = file_identity(info)
        if re.fullmatch(r'[0-9a-fA-F]{64}', expected):
            if len(fields) != 2:
                raise ValueError('invalid-payload-manifest-hash')
            actual = digests.sha256(path)
            if actual != expected.lower():
                raise ValueError('payload-artifact-hash-mismatch:' + relative)
        else:
            # POSIX cksum is not zlib CRC32. Retain the original old-version
            # contract and normalize only this replacement installation.
            if len(fields) != 3 or not all(re.fullmatch(r'[0-9]+', v) for v in fields[1:]):
                raise ValueError('invalid-payload-manifest-hash')
            checksum = checksums.get(identity)
            if checksum is None:
                output = subprocess.run(['cksum', str(path)], check=True,
                                        capture_output=True, text=True).stdout.split()
                checksum = tuple(output[:2])
                checksums[identity] = checksum
            if checksum != tuple(fields[1:]):
                raise ValueError('payload-artifact-checksum-mismatch:' + relative)
            actual = digests.sha256(path)
        if file_identity(path.stat()) != identity or path.resolve(strict=True) != resolved:
            raise ValueError('payload-artifact-changed:' + relative)
        observed.append((path, resolved, identity))
        normalized.append(relative + '|' + actual + '\n')
    if not normalized:
        raise ValueError('empty-payload-manifest')
    # Recheck names after the batch as well, without rereading font contents.
    for path, resolved, identity in observed:
        if file_identity(path.stat()) != identity or path.resolve(strict=True) != resolved:
            raise ValueError('payload-artifact-changed:' + str(path.relative_to(root)))
    if manifest.read_bytes() != original:
        raise ValueError('payload-manifest-changed')
    if normalize:
        temporary = manifest.with_name(manifest.name + f'.tmp.{os.getpid()}')
        try:
            with temporary.open('w', encoding='utf-8', newline='\n') as stream:
                stream.writelines(normalized)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, 0o644)
            os.replace(temporary, manifest)
        finally:
            temporary.unlink(missing_ok=True)
    unique = len({identity for _, _, identity in observed})
    return {'status': 'ok', 'records': len(normalized), 'uniqueFiles': unique,
            'reusedAliases': len(normalized) - unique, 'validation': 'staged-manifest'}


if __name__ == '__main__':
    try:
        result = verify(sys.argv[1], sys.argv[2], sys.argv[3] == '1', set(sys.argv[4].split()))
        print(json.dumps(result, ensure_ascii=False))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print('update-manifest-rejected:' + str(error).replace('\r', ' ').replace('\n', ' '))
        sys.exit(1)
