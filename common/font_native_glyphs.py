"""Validated native static-glyf preparation for the existing OEM publisher."""
import hashlib
import os
from pathlib import Path
import platform
import struct
import subprocess
import tempfile
import time

from fontTools.ttLib.tables._g_l_y_f import Glyph
from font_native_instance import HELPER


def packed_glyph(data):
    """Keep a prepared simple glyph encoded while exposing its checked header.

    FontTools can publish these exact bytes with recalcBBoxes=False. Validate
    the endpoint/flag/coordinate stream without allocating point arrays; only
    callers that actually change coordinates need to expand the glyph.
    """
    glyph = Glyph(data)
    if not data:
        glyph.numberOfContours = 0
        glyph.xMin = glyph.yMin = glyph.xMax = glyph.yMax = 0
        glyph.preparedPointCount = 0
        return glyph
    if len(data) < 12:
        raise ValueError('prepared-glyph-header')
    contours, x0, y0, x1, y1 = struct.unpack_from('>5h', data)
    if contours < 0 or x0 > x1 or y0 > y1 or len(data) < 12 + contours * 2:
        raise ValueError('prepared-glyph-outline')
    previous = -1
    for at in range(10, 10 + contours * 2, 2):
        end = struct.unpack_from('>H', data, at)[0]
        if end <= previous:
            raise ValueError('prepared-glyph-endpoints')
        previous = end
    points = previous + 1
    at = 10 + contours * 2
    instructions = struct.unpack_from('>H', data, at)[0]
    at += 2 + instructions
    if at > len(data):
        raise ValueError('prepared-glyph-instructions')
    seen = coordinate_bytes = 0
    while seen < points:
        if at >= len(data):
            raise ValueError('prepared-glyph-flags')
        flag = data[at]
        at += 1
        repeat = 1
        if flag & 8:
            if at >= len(data):
                raise ValueError('prepared-glyph-repeat')
            repeat += data[at]
            at += 1
        if seen + repeat > points or flag & 0x80:
            raise ValueError('prepared-glyph-point-count')
        coordinate_bytes += repeat * ((1 if flag & 2 else 0 if flag & 16 else 2)
                                      + (1 if flag & 4 else 0 if flag & 32 else 2))
        seen += repeat
    tail = at + coordinate_bytes
    if tail > len(data) or len(data) - tail > 3 or any(data[tail:]):
        raise ValueError('prepared-glyph-coordinates')
    glyph.numberOfContours = contours
    glyph.xMin, glyph.yMin, glyph.xMax, glyph.yMax = x0, y0, x1, y1
    glyph.preparedPointCount = points
    return glyph


def _command(source, output, upem):
    if os.name == 'nt' or platform.machine().lower() not in ('aarch64', 'arm64') or not os.access(HELPER, os.X_OK):
        return None
    return [str(HELPER), '--glyph-cache', str(source), str(output), '0', str(upem)]


def _digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class GlyphCache:
    def __init__(self, data, font, upem):
        if len(data) < 20 or data[:8] != b'ZIYUGC01':
            raise ValueError('native-glyph-header')
        count, source_upem, target_upem, size = struct.unpack_from('>IHHI', data, 8)
        header = 20 + count + (count + 1) * 4
        if (count != font['maxp'].numGlyphs or count > 65535 or source_upem != font['head'].unitsPerEm
                or target_upem != upem or size > 64 * 1024 * 1024 or len(data) != header + size):
            raise ValueError('native-glyph-contract')
        self.status = data[20:20 + count]
        self.offsets = struct.unpack_from('>' + 'I' * (count + 1), data, 20 + count)
        if (self.offsets[0] != 0 or self.offsets[-1] != size
                or any(a > b for a, b in zip(self.offsets, self.offsets[1:]))
                or any(s not in (0, 1, 2) or (s < 2 and a != b) or (s == 2 and b - a < 12)
                       for s, a, b in zip(self.status, self.offsets, self.offsets[1:]))):
            raise ValueError('native-glyph-index')
        self.data = memoryview(data)[header:]
        self.byte_size = len(data)
        self.ids = font.getReverseGlyphMap()
        self.prepared = sum(value != 0 for value in self.status)

    def packed_glyph(self, name):
        gid = self.ids[name]
        if self.status[gid] == 0:
            return None
        return packed_glyph(bytes(self.data[self.offsets[gid]:self.offsets[gid + 1]]))

    def glyph(self, name, table):
        glyph = self.packed_glyph(name)
        if glyph is None:
            return None
        glyph.expand(table)
        if glyph.numberOfContours < 0:
            raise ValueError('native-glyph-composite-output')
        return glyph


def prepare(font, source, upem, budget=64 * 1024 * 1024):
    if 'glyf' not in font or 'fvar' in font:
        return None, {'engine': 'fonttools', 'nativeFallback': 'static-glyf-required'}
    # Even an empty cache needs its status and offset indexes. Do not spawn a
    # full-donor conversion when the worker's remaining budget cannot hold it.
    if budget < 24 + font['maxp'].numGlyphs * 5:
        return None, {'engine': 'fonttools', 'nativeFallback': 'native-glyph-cache-budget'}
    with Path(source).open('rb') as stream:
        if stream.read(4) == b'ttcf':
            return None, {'engine': 'fonttools', 'nativeFallback': 'collection-face-view-required'}
    from font_instance import check_mix_cancellation
    started = time.monotonic()
    process = None
    with tempfile.TemporaryDirectory(prefix='ziyu-glyphs-') as temporary:
        output = Path(temporary) / 'out.glyphs'
        command = _command(source, output, upem)
        if command is None:
            return None, {'engine': 'fonttools', 'nativeFallback': 'helper-unavailable'}
        before = _digest(source)
        try:
            check_mix_cancellation()
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            while True:
                check_mix_cancellation()
                try:
                    process.communicate(timeout=0.25)
                    break
                except subprocess.TimeoutExpired:
                    if time.monotonic() - started > 90:
                        raise ValueError('native-glyph-timeout')
            if process.returncode:
                raise ValueError(f'native-glyph-exit-{process.returncode}')
            if not output.is_file() or not 20 <= output.stat().st_size <= 65 * 1024 * 1024:
                raise ValueError('native-glyph-size')
            if output.stat().st_size > budget:
                raise ValueError('native-glyph-cache-budget')
            cache = GlyphCache(output.read_bytes(), font, upem)
            if _digest(source) != before:
                raise RuntimeError('字体来源在原生字形处理期间发生变化')
            check_mix_cancellation()
            return cache, {'engine': 'native-static-glyf-v1', 'preparedGlyphs': cache.prepared,
                           'seconds': round(time.monotonic() - started, 3)}
        except (OSError, ValueError, struct.error, subprocess.SubprocessError) as error:
            if _digest(source) != before:
                raise RuntimeError('字体来源在原生字形处理期间发生变化') from error
            return None, {'engine': 'fonttools', 'nativeFallback': str(error)[:200]}
        finally:
            if process is not None and process.poll() is None:
                process.kill()
                process.communicate()
