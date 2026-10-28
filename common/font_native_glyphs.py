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

    def glyph(self, name, table):
        gid = self.ids[name]
        if self.status[gid] == 0:
            return None
        glyph = Glyph(bytes(self.data[self.offsets[gid]:self.offsets[gid + 1]]))
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
