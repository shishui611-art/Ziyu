"""Finite native Type 2 cache, shared read-only by collection fork workers."""
import math
import os
from pathlib import Path
import platform
import struct
import subprocess
import tempfile
import time

from font_native_instance import HELPER
from font_native_glyphs import _digest
from fontTools.misc.psCharStrings import encodeIntT2

LIMIT = 64 * 1024 * 1024


def _command(source, output, face, upem):
    if (os.name == 'nt' or platform.machine().lower() not in ('aarch64', 'arm64')
            or not os.access(HELPER, os.X_OK)):
        return None
    return [str(HELPER), '--cff-cache', str(source), str(output), str(face), str(upem)]


class CffCache:
    def __init__(self, data, font, upem):
        if len(data) < 20 or data[:8] != b'ZIYUCF01':
            raise ValueError('native-cff-header')
        count, original, target, size = struct.unpack_from('>IHHI', data, 8)
        header = 20 + count * 33 + (count + 1) * 4
        if (count != font['maxp'].numGlyphs or not 0 < count <= 65535
                or original != font['head'].unitsPerEm or target != upem
                or len(data) != header + size or len(data) > LIMIT):
            raise ValueError('native-cff-contract')
        self.status = data[20:20 + count]
        self._bounds = memoryview(data)[20 + count:20 + count * 33]
        self.offsets = struct.unpack_from('>' + 'I' * (count + 1), data, 20 + count * 33)
        if (self.offsets[0] != 0 or self.offsets[-1] != size
                or any(a > b for a, b in zip(self.offsets, self.offsets[1:]))
                or any(s not in (0, 1, 2) or (s == 0 and a != b)
                       for s, a, b in zip(self.status, self.offsets, self.offsets[1:]))):
            raise ValueError('native-cff-index')
        for gid, status in enumerate(self.status):
            bounds = struct.unpack_from('>4d', self._bounds, gid * 32)
            if (not all(math.isfinite(value) for value in bounds)
                    or status == 2 and (bounds[0] > bounds[2] or bounds[1] > bounds[3])):
                raise ValueError('native-cff-bounds')
        self.data = memoryview(data)[header:]
        self.ids = font.getReverseGlyphMap()
        self.byte_size = len(data)
        self.prepared = sum(value != 0 for value in self.status)

    def supports(self, name):
        gid = self.ids.get(name)
        return gid is not None and self.status[gid] != 0

    def bounds(self, name):
        gid = self.ids[name]
        return struct.unpack_from('>4d', self._bounds, gid * 32) if self.status[gid] == 2 else None

    def body(self, name, width, cff2):
        gid = self.ids[name]
        prefix = b'' if cff2 or width is None else encodeIntT2(round(width))
        return prefix + bytes(self.data[self.offsets[gid]:self.offsets[gid + 1]]) + (b'' if cff2 else b'\x0e')


def prepare(font, source, upem, *, face=0, budget=LIMIT):
    if 'fvar' in font or not any(tag in font for tag in ('glyf', 'CFF ', 'CFF2')):
        return None, {'engine': 'fonttools', 'nativeFallback': 'static-outline-required'}
    if 'glyf' not in font:
        tag = 'CFF ' if 'CFF ' in font else 'CFF2'
        top = font[tag].cff.topDictIndex[0]
        expected = [1 / font['head'].unitsPerEm, 0, 0, 1 / font['head'].unitsPerEm, 0, 0]
        matrix = getattr(top, 'FontMatrix', expected)
        if (len(matrix) != 6 or any(abs(a - b) > 1e-12 for a, b in zip(matrix, expected))
                or any('FontMatrix' in fd.rawDict for fd in getattr(top, 'FDArray', ()))):
            return None, {'engine': 'fonttools', 'nativeFallback': 'custom-cff-font-matrix'}
    if budget < 24 + font['maxp'].numGlyphs * 37:
        return None, {'engine': 'fonttools', 'nativeFallback': 'native-cff-cache-budget'}
    from font_instance import check_mix_cancellation
    started = time.monotonic()
    process = None
    with tempfile.TemporaryDirectory(prefix='ziyu-cff-') as temporary:
        output = Path(temporary) / 'bodies.bin'
        command = _command(source, output, face, upem)
        if command is None:
            return None, {'engine': 'fonttools', 'nativeFallback': 'helper-unavailable'}
        before = _digest(source)
        try:
            check_mix_cancellation()
            process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            while True:
                check_mix_cancellation()
                try:
                    process.wait(timeout=.25)
                    break
                except subprocess.TimeoutExpired:
                    if time.monotonic() - started > 90:
                        raise ValueError('native-cff-timeout')
            if process.returncode:
                raise ValueError('native-cff-exit-%s' % process.returncode)
            if not output.is_file() or not 20 <= output.stat().st_size <= min(budget, LIMIT):
                raise ValueError('native-cff-cache-budget-or-size')
            cache = CffCache(output.read_bytes(), font, upem)
            if _digest(source) != before:
                raise RuntimeError('字体来源在原生 CFF 处理期间发生变化')
            check_mix_cancellation()
            return cache, {'engine': 'native-cff-v1', 'preparedGlyphs': cache.prepared,
                           'seconds': round(time.monotonic() - started, 3)}
        except (OSError, ValueError, struct.error, subprocess.SubprocessError) as error:
            if _digest(source) != before:
                raise RuntimeError('字体来源在原生 CFF 处理期间发生变化') from error
            return None, {'engine': 'fonttools', 'nativeFallback': str(error)[:200]}
        finally:
            if process is not None and process.poll() is None:
                process.kill()
                process.wait()
