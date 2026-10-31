#!/usr/bin/env python3
"""Per-family opt-in fitting for checked OEM outline transplants.

This does not change font XML, OEM line metrics, source files or live mounts.
One affine transform is used for every replaced glyph of a target face.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import sys
import tempfile

SCHEMA = 'ziyu-font-clipping-v1'


def enabled():
    return os.environ.get('ZIYU_FONT_CLIP_ENABLED') == '1'


def preference_path(module, family):
    if (not family or len(family) > 240 or family in ('.', '..', 'default')
            or any(char in family for char in ('/', '\\', '\n', '\r', '\0'))):
        raise ValueError('字体 ID 无效，系统默认字体不需要防裁切')
    folder = Path(module) / 'config/font-clipping-preferences'
    if folder.is_symlink() or folder.parent.is_symlink():
        raise ValueError('字体防裁切配置目录不安全')
    return folder / (hashlib.sha256(family.encode('utf-8')).hexdigest() + '.json')


def preference(module, family):
    path = preference_path(module, family)
    if not path.exists():
        return False
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4096:
        raise ValueError('字体防裁切配置无效')
    data = json.loads(path.read_text(encoding='utf-8'))
    if data.get('schema') != SCHEMA or data.get('font') != family or type(data.get('enabled')) is not bool:
        raise ValueError('字体防裁切配置不匹配')
    return data['enabled']


def save_preference(module, family, value):
    path = preference_path(module, family)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = {'schema': SCHEMA, 'font': family, 'enabled': value}
    descriptor, temporary = tempfile.mkstemp(prefix='.clipping-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False)
            stream.write('\n')
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)
    if preference(module, family) != value:
        raise ValueError('防裁切配置未通过回读')


def _ui_character(cp):
    return (0x20 <= cp <= 0x024f or 0x3000 <= cp <= 0x30ff
            or 0x3400 <= cp <= 0x9fff or 0xf900 <= cp <= 0xfaff
            or 0xfb00 <= cp <= 0xfb06 or 0xff00 <= cp <= 0xffef or 0x20000 <= cp <= 0x3347f)


def plan(target, donor, cache=None, recordings=None):
    result = {'schema': SCHEMA, 'enabled': enabled(), 'scale': 1.0, 'baseline': 0.0,
              'transformed': False, 'state': 'disabled'}
    if not result['enabled']:
        return result
    if 'fvar' in donor or any(tag in donor for tag in ('COLR', 'CBDT', 'sbix', 'SVG ')):
        raise ValueError('自动防裁切需要普通或已选定字重的静态字体；请先在工作室保存选轴结果，或关闭该字体的防裁切')
    top = int(target['hhea'].ascent)
    bottom = int(target['hhea'].descent)
    os2 = target['OS/2'] if 'OS/2' in target else None
    if os2 is not None:
        if os2.sTypoAscender > 0:
            top = min(top, int(os2.sTypoAscender))
        if os2.sTypoDescender < 0:
            bottom = max(bottom, int(os2.sTypoDescender))
    output_upem = target['head'].unitsPerEm
    donor_upem = donor['head'].unitsPerEm
    if not (16 <= donor_upem <= 16384 and 16 <= output_upem <= 16384
            and 0 < top <= 2 * output_upem and -output_upem <= bottom < 0 and top - bottom > 8):
        raise ValueError('原厂行框无效，无法安全计算防裁切比例')
    cache = cache if cache is not None else {}
    envelope = cache.get('clippingSourceEnvelope')
    if envelope is None:
        from fontTools.pens.boundsPen import BoundsPen
        from fontTools.pens.recordingPen import DecomposingRecordingPen
        from font_ligatures import _sequences
        from font_instance import check_mix_cancellation
        glyphs = donor.getGlyphSet()
        names = {name for cp, name in (donor.getBestCmap() or {}).items() if _ui_character(cp)}
        # Real GSUB ligatures can be unencoded. Joined-letter fallbacks cannot
        # grow vertically beyond these same measured letters.
        names = sorted(names | set(_sequences(donor)))
        low, high, count = math.inf, -math.inf, 0
        glyf = donor['glyf'] if 'glyf' in donor else None
        for index, name in enumerate(names):
            if index % 256 == 0:
                check_mix_cancellation()
            if glyf is not None:
                glyph = glyf.glyphs[name]
                data = getattr(glyph, 'data', None)
                if data is not None:
                    if not data:
                        continue
                    if len(data) < 10:
                        raise ValueError('来源 TrueType 字形边界头不完整')
                    contours, _x0, y0, _x1, y1 = struct.unpack_from('>5h', data)
                    if contours < -1:
                        raise ValueError('来源 TrueType 轮廓数量无效')
                    if not contours:
                        continue
                else:
                    if not glyph.numberOfContours:
                        continue
                    glyph.recalcBounds(glyf)
                    y0, y1 = glyph.yMin, glyph.yMax
                # Do not decode every source outline only to measure it. The
                # actual generated glyph header is checked again after native
                # decoding or pen conversion, before the existing publication.
            else:
                recording = recordings.get(name) if recordings is not None else None
                if recording is None:
                    recording = DecomposingRecordingPen(glyphs)
                    glyphs[name].draw(recording)
                    if recordings is not None:
                        recordings[name] = recording
                pen = BoundsPen(None)
                recording.replay(pen)
                if pen.bounds is None:
                    continue
                y0, y1 = pen.bounds[1], pen.bounds[3]
            if not all(math.isfinite(value) for value in (y0, y1)) or y0 > y1:
                raise ValueError('来源字形边界无效，无法计算防裁切比例')
            low, high, count = min(low, y0), max(high, y1), count + 1
        if count == 0 or high <= low:
            raise ValueError('没有可测量的文字轮廓，无法计算防裁切比例')
        envelope = (low, high, count)
        cache['clippingSourceEnvelope'] = envelope
    low, high, count = envelope
    unit_scale = output_upem / donor_upem
    low, high = low * unit_scale, high * unit_scale
    result.update(sourceBottom=low, sourceTop=high, targetBottom=bottom, targetTop=top,
                  measuredGlyphs=count, state='already-fits',
                  measurement='glyf-headers-checked-again-on-output' if 'glyf' in donor else 'decomposed-curve-bounds')
    if low >= bottom and high <= top:
        return result
    # Two design units cover coordinate rounding. There is no hard-coded font
    # shrink percentage, per-character squeeze or line-height enlargement.
    safe_low, safe_high = bottom + 2, top - 2
    factor = min(1.0, (safe_high - safe_low) / (high - low))
    if not 0 < factor <= 1:
        raise ValueError('防裁切比例无效')
    # Move the baseline only as far as necessary, keeping zero when possible.
    lower_shift, upper_shift = safe_low - low * factor, safe_high - high * factor
    shift = min(max(0.0, lower_shift), upper_shift)
    if low * factor + shift < safe_low - 1e-6 or high * factor + shift > safe_high + 1e-6:
        raise ValueError('防裁切变换不能完整放入原厂行框')
    result.update(scale=factor, baseline=shift, transformed=True, state='fitted')
    return result


def verify_bounds(bounds, fit):
    if fit.get('enabled') and bounds is not None:
        if bounds[1] < fit['targetBottom'] or bounds[3] > fit['targetTop']:
            raise ValueError('防裁切输出仍超出原厂行框，保留原负载；请导出诊断')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('status', 'set', 'mode'))
    parser.add_argument('font')
    parser.add_argument('value', nargs='?', choices=('on', 'off'))
    parser.add_argument('--module', type=Path, default=Path(os.environ.get('MODDIR', '/data/adb/modules/ziyu')))
    args = parser.parse_args()
    if args.action == 'set':
        if args.value is None:
            parser.error('set requires on or off')
        save_preference(args.module, args.font, args.value == 'on')
    value = preference(args.module, args.font)
    if args.action == 'mode':
        print('1' if value else '0')
    else:
        print(json.dumps({'status': 'ok', 'enabled': value,
                          'message': '已保存；重新应用这款字体后生效。' if args.action == 'set' else ''}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        sys.exit(1)
