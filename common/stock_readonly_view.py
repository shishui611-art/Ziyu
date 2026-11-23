"""Owned, read-only block views on distinct devices, without provider mutation.

NoMount can replace inode operations, and overlay providers (e.g. Mountify,
meta-overlayfs, Magic Mount or self-mount) stack an overlay/tmpfs mount on top
of /system/fonts. A read-only loop over the underlying *mounted* ROM block
device creates separate inodes from the untouched partition image. Only
font/config directory readers use this view.
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

_STOCK_FS_TYPES = frozenset({'erofs', 'ext4'})
_LOOP_DEVICE_RE = re.compile(r'/dev/(?:block/)?loop[0-9]+')
_DEV_ID_RE = re.compile(r'[0-9]+:[0-9]+')
_DEVNAME_RE = re.compile(r'[A-Za-z0-9._-]+')


class StockViewIntegrityError(RuntimeError):
    """An owned view or its cleanup could not be proven safe."""


class StockReadonlyView:
    def __init__(self):
        self.libc = None
        self.base = None
        self.views = {}
        self.owned = []

    @staticmethod
    def _unescape(value):
        return re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), value)

    @classmethod
    def _resolve_block_device(cls, source: str, dev_id: str) -> tuple[Path, os.stat_result] | None:
        candidates: list[Path] = []
        if source.startswith('/'):
            candidates.append(Path(source))
        if _DEV_ID_RE.fullmatch(dev_id):
            uevent = Path('/sys/dev/block') / dev_id / 'uevent'
            try:
                for line in uevent.read_text(encoding='utf-8', errors='ignore').splitlines():
                    if line.startswith('DEVNAME='):
                        devname = line.split('=', 1)[1].strip()
                        if _DEVNAME_RE.fullmatch(devname):
                            candidates.append(Path('/dev/block') / devname)
                        break
            except OSError:
                pass
        for candidate in candidates:
            try:
                device = candidate.resolve(strict=True)
                if not str(device).startswith('/dev/block/') or _LOOP_DEVICE_RE.fullmatch(str(device)):
                    continue
                info = device.stat()
            except OSError:
                continue
            if stat.S_ISBLK(info.st_mode) and dev_id == f'{os.major(info.st_rdev)}:{os.minor(info.st_rdev)}':
                return device, info
        return None

    @staticmethod
    def _probe_stock_block(device: Path, fstype: str) -> int | None:
        backing_block_size = 0
        try:
            with device.open('rb', buffering=0) as stream:
                if fstype == 'erofs':
                    stream.seek(1024)
                    if stream.read(4) != struct.pack('<I', 0xE0F5E1E2):
                        return None
                elif fstype == 'ext4':
                    stream.seek(1080)
                    if stream.read(2) != struct.pack('<H', 0xEF53):
                        return None
                else:
                    return None
                try:
                    bsz_buf = bytearray(4)
                    fcntl.ioctl(stream.fileno(), 0x1268, bsz_buf, True)  # BLKSSZGET
                    backing_block_size = struct.unpack('<I', bsz_buf)[0]
                except OSError:
                    backing_block_size = 0
        except OSError:
            return None
        return backing_block_size

    @classmethod
    def _candidate_stock_mounts(cls, logical: Path, mountinfo_text: str):
        """Return underlying read-only ROM partition mounts for logical, deepest first.

        Overlay/tmpfs/per-directory mounts stacked on top of /system/fonts (such
        as Mountify, meta-overlayfs, Magic Mount or self-mount) are skipped so
        the untouched parent block partition (/ or /<partition>) is selected.
        """
        choices = []
        for index, line in enumerate(mountinfo_text.splitlines()):
            left, separator, right = line.partition(' - ')
            a, b = left.split(), right.split()
            if not separator or len(a) < 6 or len(b) < 3:
                continue
            fstype = b[0]
            if fstype not in _STOCK_FS_TYPES:
                continue
            if 'ro' not in a[5].split(',') or 'ro' not in b[2].split(','):
                continue
            mountpoint = Path(cls._unescape(a[4]))
            if not logical.is_relative_to(mountpoint) or mountpoint == logical:
                continue
            if len(mountpoint.parts) > 2 and mountpoint != logical.parent:
                continue
            choices.append((len(mountpoint.parts), -index, a, b, mountpoint))
        choices.sort(key=lambda item: (item[0], item[1]), reverse=True)
        return choices

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
            raise StockViewIntegrityError('stock scan namespace did not change')
        if libc.mount(None, b'/', None, 16384 | (1 << 18), None) != 0:
            raise OSError(ctypes.get_errno(), 'cannot isolate stock scan mount propagation')
        parent = Path('/data/adb/ziyu/stock-readonly')
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if parent.is_symlink():
            raise StockViewIntegrityError('stock view parent must not be a link')
        self.base = Path(tempfile.mkdtemp(prefix=f'{os.getpid()}-', dir=parent))
        self.libc = libc

    def pick(self, logical: Path) -> Path | None:
        """Accept only the current PID 1 read-only ROM block source."""
        logical = logical.resolve()
        candidates = self._candidate_stock_mounts(logical, Path('/proc/1/mountinfo').read_text())
        selected_mount = None
        for _, _, a, b, mountpoint in candidates:
            fstype = b[0]
            resolved = self._resolve_block_device(self._unescape(b[1]), a[2])
            if resolved is None:
                continue
            device, info = resolved
            backing_block_size = self._probe_stock_block(device, fstype)
            if backing_block_size is None:
                continue
            relative = Path(self._unescape(a[3]).lstrip('/')) / logical.relative_to(mountpoint)
            selected_mount = (fstype, device, info, backing_block_size, relative)
            break
        if selected_mount is None:
            return None
        fstype, device, info, backing_block_size, relative = selected_mount
        key = (str(device), fstype)
        if key not in self.views:
            self._isolate()
            target = self.base / f'view-{len(self.owned)}'
            result = subprocess.run(['losetup', '-f', '-r', '-s', str(device)],
                                    capture_output=True, text=True, timeout=8)
            loop = result.stdout.strip()
            if result.returncode:
                raise OSError('cannot create an owned read-only loop view: ' + result.stderr.strip())
            if not re.fullmatch(r'/dev/(?:block/)?loop[0-9]+', loop):
                raise StockViewIntegrityError('cannot identify the created read-only loop view')
            loop_fd = os.open(loop, os.O_RDONLY | os.O_CLOEXEC)
            record = {'target': target, 'fd': loop_fd, 'mounted': False}
            self.owned.append(record)
            status = bytearray(232)
            fcntl.ioctl(loop_fd, 0x4C05, status, True)  # LOOP_GET_STATUS64
            flags = struct.unpack_from('<I', status, 52)[0]
            if not flags & 1 or struct.unpack_from('<Q', status, 16)[0] != info.st_rdev:
                raise StockViewIntegrityError('loop backing device or read-only flag did not match')
            # Automatic cleanup also covers supervisor termination / hard crashes.
            struct.pack_into('<I', status, 52, flags | 4)
            fcntl.ioctl(loop_fd, 0x4C04, status)  # LOOP_SET_STATUS64
            if backing_block_size > 512:
                try:
                    fcntl.ioctl(loop_fd, 0x4C09, backing_block_size)  # LOOP_SET_BLOCK_SIZE
                except OSError:
                    pass
            # Create the mountpoint only after the owned loop is registered and
            # AUTOCLEAR is set. A failed losetup leaves no untracked directory.
            target.mkdir(mode=0o700)
            mount_data = b'noload' if fstype == 'ext4' else None
            if self.libc.mount(os.fsencode(loop), os.fsencode(target), fstype.encode('ascii'), 1, mount_data) != 0:
                raise OSError(ctypes.get_errno(), 'cannot mount read-only stock view')
            record['mounted'] = True
            if target.stat().st_dev == info.st_rdev:
                raise StockViewIntegrityError('stock view reused the redirected device')
            self.views[key] = target
        view_root = self.views[key]
        selected = view_root / relative
        # Parent links must not escape into the active redirected namespace.
        if not selected.resolve().is_relative_to(view_root.resolve()):
            raise StockViewIntegrityError('stock view directory escapes its verified filesystem')
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
            raise StockViewIntegrityError('; '.join(failures))
