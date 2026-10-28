"""Optional native variable-font instancing; FontTools remains the fallback.

The private helper includes HarfBuzz; no Android platform-private ABI is used.
The caller performs its usual role subsetting after a successful native pin.
"""
from io import BytesIO
import hashlib
import math
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import time

from fontTools.ttLib import TTFont, TTLibError

HELPER = Path(__file__).resolve().with_name('font-native-instance')
SUPPORTED = frozenset(('GlyphOrder', 'head', 'hhea', 'hmtx', 'maxp', 'OS/2', 'cmap', 'loca', 'glyf',
                       'name', 'post', 'fvar', 'gvar', 'avar', 'HVAR', 'VVAR', 'MVAR', 'STAT',
                       'vhea', 'vmtx', 'GDEF', 'GSUB', 'GPOS', 'BASE', 'kern',
                       'cvt ', 'cvar', 'fpgm', 'prep', 'gasp', 'DSIG', 'LTSH', 'hdmx', 'VDMX',
                       'SVG ', 'CFF2', 'VORG'))


def _sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def _cmap(font):
    mappings, variations = set(), set()
    for subtable in font['cmap'].tables:
        if subtable.format == 14:
            variations.update((selector, cp, name) for selector, values in subtable.uvsDict.items() for cp, name in values)
        elif subtable.isUnicode():
            mappings.update(subtable.cmap.items())
    return mappings, variations


def engine_identity():
    return {'wrapper': _sha(__file__), 'helper': _sha(HELPER) if HELPER.is_file() else 'absent',
            'enabled': os.name != 'nt' and platform.machine().lower() in ('aarch64', 'arm64')}


def _command(source, output, face, axes):
    if os.name == 'nt' or platform.machine().lower() not in ('aarch64', 'arm64') or not os.access(HELPER, os.X_OK):
        return None
    return [str(HELPER), str(source), str(output), str(max(face, 0)),
            *(f'{tag}={value:.9g}' for tag, value in sorted(axes.items()))]


def attempt(font, source, face, axes, role, cancel):
    """Return (validated TTFont, detail) or (None, fallback detail); cancellation propagates."""
    detail = {'engine': 'fonttools'}
    if role not in ('cjk', 'latin', 'digit') or not any(tag in font for tag in ('glyf', 'CFF2')) or not set(font.keys()) <= SUPPORTED:
        return None, {**detail, 'nativeFallback': 'unsupported-format-or-role'}
    if not axes or any(len(tag) != 4 or not math.isfinite(value) for tag, value in axes.items()):
        return None, {**detail, 'nativeFallback': 'unsupported-axis'}
    process = None
    candidate = None
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='ziyu-instance-') as directory:
        output = Path(directory) / 'pinned.ttf'
        command = _command(source, output, face, axes)
        if command is None:
            return None, {**detail, 'nativeFallback': 'helper-unavailable'}
        try:
            before = _sha(source)
            cancel()
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            while True:
                cancel()
                try:
                    process.communicate(timeout=0.25)
                    break
                except subprocess.TimeoutExpired:
                    if time.monotonic() - started > 120:
                        raise ValueError('native-timeout')
            if process.returncode != 0:
                raise ValueError(f'native-exit-{process.returncode}')
            if not output.is_file() or not 12 <= output.stat().st_size <= 128 * 1024 * 1024:
                raise ValueError('native-output-size')
            data = BytesIO(output.read_bytes())
            # FontTools' lazy-save path checks reader.file.name even for in-memory streams.
            data.name = '<ziyu-native-instance>'
            candidate = TTFont(data, lazy=True, recalcTimestamp=False, recalcBBoxes=False)
            if ('fvar' in candidate or 'gvar' in candidate
                    or not {'head', 'hmtx', 'maxp', 'cmap'} <= set(candidate.keys())
                    or candidate['head'].unitsPerEm != font['head'].unitsPerEm
                    or candidate.getGlyphOrder() != font.getGlyphOrder()
                    or _cmap(candidate) != _cmap(font)
                    or ('SVG ' in font and ('SVG ' not in candidate or font.reader['SVG '] != candidate.reader['SVG ']))):
                raise ValueError('native-output-contract')
            if 'glyf' in font:
                if not {'glyf', 'loca'} <= set(candidate.keys()):
                    raise ValueError('native-output-outline')
                # Validate every loca entry without parsing/recompiling glyf.
                offsets = candidate['loca'].locations
                if (len(offsets) != candidate['maxp'].numGlyphs + 1 or not offsets or offsets[0] != 0
                        or offsets[-1] > candidate.reader.tables['glyf'].length
                        or any(a > b for a, b in zip(offsets, offsets[1:]))):
                    raise ValueError('native-output-loca')
            elif not any(tag in candidate for tag in ('CFF ', 'CFF2')):
                raise ValueError('native-output-outline')
            if _sha(source) != before:
                raise RuntimeError('字体源文件在原生实例化期间发生变化，请重新应用')
            cancel()
            return candidate, {'engine': 'harfbuzz-15.0.0', 'nativeSeconds': round(time.monotonic() - started, 3)}
        except (OSError, ValueError, TTLibError, KeyError, AssertionError, subprocess.SubprocessError) as error:
            if candidate is not None:
                candidate.close()
            return None, {**detail, 'nativeFallback': str(error)[:200],
                          'nativeSeconds': round(time.monotonic() - started, 3)}
        except BaseException:
            if candidate is not None:
                candidate.close()
            raise
        finally:
            if process is not None and process.poll() is None:
                process.kill()
                process.communicate()


def subset_instance(font, source, face, points, axes, cancel):
    """Subset and optionally pin an unmodified disk source in one native pass."""
    if (not points or not set(font.keys()) <= SUPPORTED
            or not any(tag in font for tag in ('glyf', 'CFF2'))):
        return None, {'engine': 'fonttools', 'nativeFallback': 'unsupported-subset-source'}
    process, candidate = None, None
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='ziyu-subset-') as temporary:
        root = Path(temporary); output = root / 'subset.ttf'; chars = root / 'unicodes.txt'
        base = _command(source, output, face, axes)
        if base is None:
            return None, {'engine': 'fonttools', 'nativeFallback': 'helper-unavailable'}
        chars.write_text('\n'.join(str(cp) for cp in sorted(points)) + '\n', encoding='ascii')
        command = [base[0], '--subset', str(source), str(output), str(max(face, 0)), str(chars),
                   *(f'{tag}={value:.9g}' for tag, value in sorted(axes.items()))]
        before = _sha(source)
        try:
            cancel()
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            while True:
                cancel()
                try:
                    process.communicate(timeout=0.25); break
                except subprocess.TimeoutExpired:
                    if time.monotonic() - started > 120:
                        raise ValueError('native-subset-timeout')
            if process.returncode:
                raise ValueError(f'native-subset-exit-{process.returncode}')
            if not output.is_file() or not 12 <= output.stat().st_size <= 128 * 1024 * 1024:
                raise ValueError('native-subset-size')
            data = BytesIO(output.read_bytes()); data.name = '<ziyu-native-subset>'
            candidate = TTFont(data, lazy=True, recalcBBoxes=False, recalcTimestamp=False)
            cmap = candidate.getBestCmap() or {}; original = font.getBestCmap() or {}
            if (set(cmap) != points or candidate['head'].unitsPerEm != font['head'].unitsPerEm
                    or not 0 < candidate['maxp'].numGlyphs <= font['maxp'].numGlyphs
                    or any(candidate.getGlyphID(name) != font.getGlyphID(original[cp]) for cp, name in cmap.items())
                    or (axes and any(tag in candidate for tag in ('fvar', 'gvar')))
                    or (not axes and ('fvar' in font) != ('fvar' in candidate))):
                raise ValueError('native-subset-contract')
            if 'glyf' in font:
                if not {'glyf', 'loca'} <= set(candidate.keys()):
                    raise ValueError('native-subset-outline')
                offsets = candidate['loca'].locations
                if (len(offsets) != candidate['maxp'].numGlyphs + 1 or not offsets or offsets[0] != 0
                        or offsets[-1] > candidate.reader.tables['glyf'].length
                        or any(a > b for a, b in zip(offsets, offsets[1:]))):
                    raise ValueError('native-subset-loca')
            elif not any(tag in candidate for tag in ('CFF ', 'CFF2')):
                raise ValueError('native-subset-outline')
            if _sha(source) != before:
                raise RuntimeError('字体来源在原生裁剪期间发生变化')
            cancel()
            return candidate, {'engine': 'harfbuzz-subset-15.0.0', 'characters': len(points),
                               'nativeSeconds': round(time.monotonic() - started, 3)}
        except (OSError, ValueError, KeyError, AssertionError, TTLibError, subprocess.SubprocessError) as error:
            if candidate is not None:
                candidate.close()
            if _sha(source) != before:
                raise RuntimeError('字体来源在原生裁剪期间发生变化') from error
            return None, {'engine': 'fonttools', 'nativeFallback': str(error)[:200]}
        except BaseException:
            if candidate is not None:
                candidate.close()
            raise
        finally:
            if process is not None and process.poll() is None:
                process.kill(); process.communicate()
