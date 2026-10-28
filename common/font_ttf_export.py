#!/usr/bin/env python3
"""Export a user-library snapshot as a real single-face TrueType font (GPL-3.0-only)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from contextlib import ExitStack

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from font_digest_cache import file_identity

MAX_BYTES = 128 * 1024 * 1024
MAX_CHOICES = 512


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def workspace(value):
    path = Path(value)
    if not re.fullmatch(r'/data/(?:user/0|data)/io\.github\.shishui611_art\.ziyu(?:\.debug)?/cache/font_archive/ttf-[0-9a-f-]+', str(path)):
        raise ValueError('字体导出缓存路径无效')
    # /data/data may itself be a system symlink; no app-owned component may be one.
    for item in (path, path.parent, path.parent.parent):
        if item.is_symlink() or not item.is_dir():
            raise ValueError('字体导出缓存不是应用私有普通目录')
    if any((path / name).is_symlink() for name in ('choices.json', 'export.tmp', 'export.ttf')):
        raise ValueError('字体导出缓存文件不能是符号链接')
    return path


def app_access(root):
    owner = root.parent.stat()
    for path in (root, *root.iterdir()):
        if path.is_symlink():
            raise ValueError('字体导出缓存含有符号链接')
        os.chown(path, owner.st_uid, owner.st_gid)
        os.chmod(path, 0o700 if path.is_dir() else 0o600)
    restorecon = Path('/system/bin/restorecon')
    if restorecon.is_file():
        subprocess.run([str(restorecon), '-RF', str(root)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)


def list_choices(root):
    choices = []
    for source in sorted(root.iterdir()):
        if not re.fullmatch(r'\d{3}\.(?:ttf|otf|ttc)', source.name):
            continue
        if source.is_symlink() or not 0 < source.stat().st_size <= MAX_BYTES:
            raise ValueError('字体文件无效或超过 128 MB 导出限制')
        with source.open('rb') as stream:
            header = stream.read(12)
            collection = header[:4] == b'ttcf'
        if collection:
            count = int.from_bytes(header[8:12], 'big')
        else:
            count = 1
        if not 0 < count <= MAX_CHOICES:
            raise ValueError('字体集合面数量超出导出限制')
        sha = digest(source)
        for face in range(count):
            if len(choices) >= MAX_CHOICES:
                raise ValueError('字体面数量超过单次导出限制')
            with TTFont(source, fontNumber=face if collection else -1, lazy=True) as font:
                if 'glyf' not in font and 'CFF ' not in font and 'CFF2' not in font:
                    continue
                name = font['name'].getDebugName(17) or font['name'].getDebugName(2) or '常规'
                weight = int(font['OS/2'].usWeightClass) if 'OS/2' in font else 400
                variable = 'fvar' in font
                conversion = 'glyf' not in font
                choices.append(dict(token=f'{source.name}:{face}', file=source.name,
                                    face=face if collection else -1, sha256=sha,
                                    label=f'{name} · {weight}' + (f' · 第 {face + 1} 面' if collection else '')
                                    + (' · 可变' if variable else ''),
                                    conversion=conversion,
                                    note=('按默认轴转为静态 TTF，轮廓转换会去除原有 CFF 提示信息'
                                          if variable and conversion else '转换为 TrueType 轮廓，保留字符与排版表'
                                          if conversion else '保留可变轴，导出完整可变 TTF'
                                          if variable else '导出独立 TTF 字体文件')))
    if not choices:
        raise ValueError('没有可导出为 TTF 的字体面')
    (root / 'choices.json').write_text(json.dumps(choices, ensure_ascii=False), encoding='utf-8')
    app_access(root)
    return {'choices': choices}


def cmap_ids(font):
    return {str(cp): font.getGlyphID(name) for cp, name in (font.getBestCmap() or {}).items()}


def convert_cff(font):
    if 'fvar' in font:
        instantiateVariableFont(font, {axis.axisTag: axis.defaultValue for axis in font['fvar'].axes}, inplace=True)
    # Pinning CFF2 axes legitimately resolves layout FeatureVariations. Bind
    # output validation to that selected default instance, not the variable source.
    layout = {tag: font.getTableData(tag) for tag in ('GSUB', 'GPOS', 'GDEF') if tag in font}
    glyph_set = font.getGlyphSet()
    order = font.getGlyphOrder()
    glyphs = {}
    for name in order:
        pen = TTGlyphPen(glyph_set)
        glyph_set[name].draw(Cu2QuPen(pen, max_err=max(0.5, font['head'].unitsPerEm / 2000), reverse_direction=True))
        glyphs[name] = pen.glyph()
    for tag in ('CFF ', 'CFF2', 'DSIG', 'LTSH', 'hdmx', 'VDMX'):
        if tag in font:
            del font[tag]
    font.sfntVersion = '\x00\x01\x00\x00'
    builder = FontBuilder(font=font)
    builder.isTTF = True
    font['head'].glyphDataFormat = 0
    builder.setupMaxp()
    builder.setupGlyf(glyphs)
    post = font['post']
    builder.setupPost(italicAngle=post.italicAngle, underlinePosition=post.underlinePosition,
                      underlineThickness=post.underlineThickness, isFixedPitch=post.isFixedPitch)
    if 'VORG' in font and 'vmtx' in font:
        vorg = font['VORG']
        for name in order:
            advance, _ = font['vmtx'][name]
            font['vmtx'][name] = (advance, vorg[name] - getattr(font['glyf'][name], 'yMax', 0))
        del font['VORG']
    return layout


def export(root, token):
    choices = json.loads((root / 'choices.json').read_text(encoding='utf-8'))
    choice = next((value for value in choices if value['token'] == token), None)
    if choice is None or not re.fullmatch(r'\d{3}\.(?:ttf|otf|ttc)', choice['file']):
        raise ValueError('导出字体面选择无效')
    source = root / choice['file']
    if source.is_symlink() or not 0 < source.stat().st_size <= MAX_BYTES or digest(source) != choice['sha256']:
        raise ValueError('导出字体来源已变化，请重新选择')
    before = file_identity(source.stat())
    target, temporary = root / 'export.ttf', root / 'export.tmp'
    try:
        instancing = {'engine': 'static', 'required': False}
        with ExitStack() as stack:
            font = stack.enter_context(TTFont(source, fontNumber=choice['face'], lazy=True, recalcTimestamp=False))
            coverage, count = cmap_ids(font), len(font.getGlyphOrder())
            layout = {tag: font.getTableData(tag) for tag in ('GSUB', 'GPOS', 'GDEF') if tag in font}
            direct = choice['face'] == -1 and 'glyf' in font and font.sfntVersion == '\x00\x01\x00\x00'
            if direct:
                shutil.copyfile(source, temporary)
            else:
                if 'glyf' not in font:
                    if 'fvar' in font:
                        from font_native_instance import attempt
                        from font_instance import check_mix_cancellation
                        axes = {axis.axisTag: float(axis.defaultValue) for axis in font['fvar'].axes}
                        native, instancing = attempt(font, source, choice['face'], axes, 'cjk', check_mix_cancellation)
                        if native is not None:
                            font = stack.enter_context(native)
                    layout = convert_cff(font)
                font.sfntVersion = '\x00\x01\x00\x00'
                if 'DSIG' in font:
                    del font['DSIG']
                font.save(temporary, reorderTables=False)
        with TTFont(temporary, lazy=True) as check:
            if check.sfntVersion != '\x00\x01\x00\x00' or 'glyf' not in check or 'loca' not in check:
                raise ValueError('导出结果不是有效的 TrueType 字体')
            if cmap_ids(check) != coverage or len(check.getGlyphOrder()) != count:
                raise ValueError('导出过程中字符或字形索引发生变化')
            if any(check.getTableData(tag) != data for tag, data in layout.items()):
                raise ValueError('导出结果未保留原字体排版表')
        if file_identity(source.stat()) != before or digest(source) != choice['sha256']:
            raise ValueError('导出期间字体来源已变化')
        if not 0 < temporary.stat().st_size <= MAX_BYTES:
            raise ValueError('TTF 输出为空或超出 128 MB 限制')
        os.replace(temporary, target)
        app_access(root)
        return {'sha256': digest(target), 'bytes': target.stat().st_size, 'instancing': instancing}
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=('list', 'export'))
    parser.add_argument('root')
    parser.add_argument('token', nargs='?', default='')
    args = parser.parse_args()
    try:
        root = workspace(args.root)
        result = list_choices(root) if args.command == 'list' else export(root, args.token)
        print(json.dumps({'status': 'ok', 'data': result}, ensure_ascii=False))
        return 0
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
