#!/usr/bin/env python3
"""Content hashes shared only within one immutable-payload verification pass."""
import errno
import hashlib
import os
import stat


def file_identity(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns,
            info.st_ctime_ns, info.st_mode)


class FontDigestCache:
    def __init__(self):
        self._hashes = {}

    def sha256(self, path):
        # Open every alias even on a hit: readability and the current pathname
        # remain part of the proof. Only repeated reads of its bytes are omitted.
        with open(path, 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode):
                raise OSError(errno.EINVAL, 'font artifact is not a regular file', str(path))
            identity = file_identity(info)
            value = self._hashes.get(identity)
            if value is None:
                value = hashlib.file_digest(stream, 'sha256').hexdigest()
            if file_identity(os.fstat(stream.fileno())) != identity:
                raise OSError(errno.ESTALE, 'font artifact changed while verifying', str(path))
            self._hashes[identity] = value
            return value
