"""Repair sparse, undecodable gvar records without recompiling healthy glyphs.

The source is never modified. A private single-face copy is used only as input
to the independently validated native instance path; failure uses FontTools.
"""
import hashlib
from pathlib import Path
import struct
import tempfile
import traceback

from fontTools.ttLib import TTFont, TTLibError
from fontTools.ttLib.tables.DefaultTable import DefaultTable
from font_native_instance import attempt


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def repair_and_instance(original, source, face, axes, role, cancel):
    if 'gvar' not in original or 'glyf' not in original:
        return None, None, []
    before = sha(source)
    kwargs = {'fontNumber': face} if face >= 0 else {}
    recovered = []
    try:
        with TTFont(str(source), lazy=True, recalcBBoxes=False, recalcTimestamp=False, **kwargs) as work:
            order = work.getGlyphOrder()
            variations = work['gvar'].variations
            default_weight = next((float(a.defaultValue) for a in work['fvar'].axes if a.axisTag == 'wght'), None)
            limit = min(8, max(1, len(order) // 100))
            bad = []
            for index, name in enumerate(order):
                if index % 128 == 0:
                    cancel()
                try:
                    variations[name]
                except AssertionError as error:
                    if len(recovered) >= limit:
                        return None, None, []
                    codes = sorted(cp for cp, glyph in (work.getBestCmap() or {}).items() if glyph == name)
                    coordinates, _, _ = work['glyf'][name].getCoordinates(work['glyf'])
                    if not coordinates and any(not chr(cp).isspace() for cp in codes):
                        return None, None, []
                    recovered.append({'glyph': name, 'characters': ''.join(map(chr, codes)),
                        'codepoints': [f'U+{cp:04X}' for cp in codes], 'fallback': 'default-outline',
                        'reason': 'gvar-delta-decode', 'exceptionType': type(error).__name__,
                        'originalWeight': default_weight, 'traceback': traceback.format_exc()[-2000:]})
                    bad.append(index)
                finally:
                    # LazyDict caches decoded tuples; a scan should not retain a
                    # second full variation graph while the native helper runs.
                    if name in variations:
                        del variations[name]
            if not bad:
                return None, None, []
            raw = work.reader['gvar']
            major, _minor, _axes, _shared, _tuple_offset, count, flags, base = struct.unpack_from('>HHHHIHHI', raw, 0)
            if major != 1 or count != len(order) or flags not in (0, 1):
                return None, None, []
            long_offsets = bool(flags & 1)
            form = '>' + ('I' if long_offsets else 'H') * (count + 1)
            if base < 20 + (count + 1) * (4 if long_offsets else 2) or base > len(raw):
                return None, None, []
            entries = struct.unpack_from(form, raw, 20)
            offsets = [v if long_offsets else v * 2 for v in entries]
            if (offsets[0] != 0 or base + offsets[-1] > len(raw)
                    or any(a > b for a, b in zip(offsets, offsets[1:]))):
                return None, None, []
            removed = 0
            bad_set = set(bad)
            updated = [0]
            for index in range(count):
                if index in bad_set:
                    removed += offsets[index + 1] - offsets[index]
                updated.append(offsets[index + 1] - removed)
            if not long_offsets and any(value % 2 for value in updated):
                return None, None, []
            header = bytearray(raw[:base])
            struct.pack_into(form, header, 20, *(updated if long_offsets else [v // 2 for v in updated]))
            chunks, cursor = [], 0
            for index in bad:
                chunks.append(raw[base + cursor:base + offsets[index]])
                cursor = offsets[index + 1]
            chunks.append(raw[base + cursor:])
            patched = bytes(header) + b''.join(chunks)
            work.tables.clear()
            table = DefaultTable('gvar'); table.data = patched; work.tables['gvar'] = table
            with tempfile.TemporaryDirectory(prefix='ziyu-gvar-repair-') as directory:
                repaired = Path(directory) / 'repaired.ttf'
                work.save(str(repaired), reorderTables=False)
                cancel()
                if sha(source) != before:
                    raise RuntimeError('字体源文件在可变数据修复期间发生变化，请重新应用')
                candidate, detail = attempt(original, repaired, -1, axes, role, cancel)
                if sha(source) != before:
                    if candidate is not None:
                        candidate.close()
                    raise RuntimeError('字体源文件在可变数据修复期间发生变化，请重新应用')
                if candidate is None:
                    return None, None, []
                detail['nativeRepairGlyphs'] = len(recovered)
                return candidate, detail, recovered
    except (OSError, ValueError, TypeError, OverflowError, IndexError, EOFError, TTLibError, KeyError, AssertionError, struct.error):
        return None, None, []
