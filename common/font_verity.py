#!/usr/bin/env python3
"""Seal only module-owned replacement inodes; never alter original update files.

Linux fsverity UAPI: FS_IOC_ENABLE_VERITY / FS_IOC_MEASURE_VERITY.
No keys, signatures, global sysctls or framework checks are changed.
"""
import errno
import fcntl
import os
from pathlib import Path
import struct
import sys

ENABLE = 0x40806685
MEASURE = 0xC0046686


def measured(path):
    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        value = bytearray(struct.pack('=HH', 0, 64) + bytes(64))
        fcntl.ioctl(fd, MEASURE, value, True)
        algorithm, size = struct.unpack_from('=HH', value)
        if algorithm not in (1, 2) or size not in (32, 64):
            raise ValueError('unexpected fs-verity digest')
        return bytes(value[:4 + size]).hex()
    finally:
        os.close(fd)


def seal(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('fs-verity source is not a regular file')
    try:
        return measured(path)
    except OSError as error:
        if error.errno != errno.ENODATA:
            raise
    # A readonly file descriptor is required. All copy/write descriptors have
    # already closed; the filesystem supplies the Merkle tree and immutability.
    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        argument = struct.pack('=IIIIQIIQ11Q', 1, 1, 0, 0, 0, 0, 0, 0, *([0] * 11))
        fcntl.ioctl(fd, ENABLE, argument)
    except OSError as error:
        if error.errno != errno.EEXIST:
            raise
    finally:
        os.close(fd)
    return measured(path)


def main():
    command, raw = sys.argv[1:3]
    path = Path(raw)
    if command == 'seal':
        # The CLI is also called from isolated consumers via /proc/1/root.
        resolved = path.resolve()
        text = resolved.as_posix()
        owned = ('/data/adb/modules/ziyu/', '/data/adb/modules_update/ziyu/')
        if not (any(text.startswith(root) for root in owned) or
                (resolved.parent == Path('/data/local/tmp') and resolved.name.startswith('.luoshu-provider-'))):
            raise ValueError('refusing to seal an original or unrelated file')
        print(seal(path))
    elif command == 'check':
        print(measured(path))
    else:
        raise ValueError('unknown fs-verity action')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'font fs-verity check failed: {error}', file=sys.stderr)
        raise SystemExit(1)
