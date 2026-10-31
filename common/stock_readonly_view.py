"""Owned, read-only EROFS views on distinct devices, without provider mutation.

NoMount can replace inode operations, so another bind of the same filesystem
does not recover stock bytes. A read-only loop over the *mounted* block device
creates separate inodes. Only font/config directory readers use this view.
"""
from __future__ import annotations

import ctypes
import errno
import fcntl
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import tempfile


class StockReadonlyView:
    def __init__(self):
        self.libc = None
        self.base = None
        self.views = {}
        self.owned = []

    @staticmethod
    def _unescape(value):
        return re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), value)

    def _isolate(self):
        if self.libc is not None:
            return
        libc = ctypes.CDLL(None, use_errno=True)
        libc.mount.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                               ctypes.c_ulong, ctypes.c_void_p]
        libc.umount2.argtypes = [ctypes.c_char_p, ctypes.c_int]
        before = os.readlink('/proc/self/ns/mnt')
        if libc.unshare(0x00020000) != 0:
            raise OSError(ctypes.get_errno(), 'stock scan requires a private mount namespace')
        if before == os.readlink('/proc/self/ns/mnt'):
            raise OSError('stock scan namespace did not change')
        if libc.mount(None, b'/', None, 16384 | (1 << 18), None) != 0:
            raise OSError(ctypes.get_errno(), 'cannot isolate stock scan mount propagation')
        self.libc = libc
        parent = Path('/data/adb/luoshu/stock-readonly')
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if parent.is_symlink():
            raise OSError('stock view parent must not be a link')
        self.base = Path(tempfile.mkdtemp(prefix=f'{os.getpid()}-', dir=parent))

    def pick(self, logical: Path) -> Path | None:
        """Accept only the current PID 1 read-only EROFS block source."""
        logical = logical.resolve()
        choices = []
        for line in Path('/proc/1/mountinfo').read_text().splitlines():
            left, separator, right = line.partition(' - ')
            a, b = left.split(), right.split()
            if not separator or len(a) < 6 or len(b) < 3:
                continue
            mountpoint = Path(self._unescape(a[4]))
            if not logical.is_relative_to(mountpoint):
                continue
            choices.append((len(mountpoint.parts), a, b, mountpoint))
        if not choices:
            return None
        _, a, b, mountpoint = max(choices, key=lambda item: item[0])
        if b[0] != 'erofs' or 'ro' not in a[5].split(',') or 'ro' not in b[2].split(','):
            return None
        device = Path(self._unescape(b[1])).resolve(strict=True)
        if not str(device).startswith('/dev/block/'):
            return None
        info = device.stat()
        if not stat.S_ISBLK(info.st_mode) or a[2] != f'{os.major(info.st_rdev)}:{os.minor(info.st_rdev)}':
            return None
        with device.open('rb', buffering=0) as stream:
            stream.seek(1024)
            if stream.read(4) != struct.pack('<I', 0xE0F5E1E2):
                return None
        relative = Path(self._unescape(a[3]).lstrip('/')) / logical.relative_to(mountpoint)
        key = str(device)
        if key not in self.views:
            self._isolate()
            target = self.base / f'view-{len(self.owned)}'
            target.mkdir(mode=0o700)
            result = subprocess.run(['losetup', '-f', '-r', '-s', str(device)],
                                    capture_output=True, text=True, timeout=8)
            loop = result.stdout.strip()
            if result.returncode or not re.fullmatch(r'/dev/(?:block/)?loop[0-9]+', loop):
                raise OSError('cannot create an owned read-only loop view')
            loop_fd = os.open(loop, os.O_RDONLY | os.O_CLOEXEC)
            record = {'target': target, 'fd': loop_fd, 'mounted': False}
            self.owned.append(record)
            status = bytearray(232)
            fcntl.ioctl(loop_fd, 0x4C05, status, True)  # LOOP_GET_STATUS64
            flags = struct.unpack_from('<I', status, 52)[0]
            if not flags & 1 or struct.unpack_from('<Q', status, 16)[0] != info.st_rdev:
                raise OSError('loop backing device or read-only flag did not match')
            # Automatic cleanup also covers supervisor termination / hard crashes.
            struct.pack_into('<I', status, 52, flags | 4)
            fcntl.ioctl(loop_fd, 0x4C04, status)  # LOOP_SET_STATUS64
            if self.libc.mount(os.fsencode(loop), os.fsencode(target), b'erofs', 1, None) != 0:
                raise OSError(ctypes.get_errno(), 'cannot mount read-only stock view')
            record['mounted'] = True
            if target.stat().st_dev == info.st_rdev:
                raise OSError('stock view reused the redirected device')
            self.views[key] = target
        selected = self.views[key] / relative
        # Parent links must not escape into the active redirected namespace.
        if not selected.resolve().is_relative_to(self.views[key]):
            raise OSError('stock view directory escapes its verified filesystem')
        return selected if selected.is_dir() else None

    def close(self):
        failures = []
        for record in reversed(self.owned):
            target = record['target']
            if record['mounted'] and self.libc.umount2(os.fsencode(target), 0) != 0:
                failures.append(f'cannot release owned stock mount: {target}')
            os.close(record['fd'])  # AUTOCLEAR releases only this owned loop.
            try:
                target.rmdir()
            except OSError as error:
                if error.errno not in (errno.ENOENT,):
                    failures.append(str(error))
        if self.base is not None:
            try:
                self.base.rmdir()
            except OSError as error:
                failures.append(str(error))
        if failures:
            raise OSError('; '.join(failures))
