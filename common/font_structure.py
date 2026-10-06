"""Validate SFNT tables and mapped outline glyphs without a font size heuristic."""
from pathlib import Path
import argparse
from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont, TTCollection


def validate_font(font, file_size=None, *, decode_all=True):
    required = {"head", "maxp", "cmap", "hhea", "hmtx"}
    if not required.issubset(font.keys()):
        raise ValueError("字体缺少必要的 SFNT 表")
    if file_size is not None:
        for entry in font.reader.tables.values():
            if entry.offset < 0 or entry.length < 0 or entry.offset + entry.length > file_size:
                raise ValueError("字体表目录越过文件边界")
    # Force decoding, including loca/glyf, to reject valid-looking directory text
    # containing truncated or malformed table data.
    if decode_all:
        font.ensureDecompiled()
    order = font.getGlyphOrder()
    if font["maxp"].numGlyphs != len(order) or not order:
        raise ValueError("字体字形数量无效")
    cmap = font.getBestCmap() or {}
    if not cmap:
        raise ValueError("字体没有 Unicode 字符映射")
    glyphs = font.getGlyphSet()
    for name in set(cmap.values()):
        if name == ".notdef" or name not in glyphs:
            raise ValueError("字符映射指向缺失字形")
        if decode_all:
            glyphs[name].draw(RecordingPen())
    return cmap


def validate(path):
    path = Path(path)
    with path.open("rb") as stream:
        collection = stream.read(4) == b"ttcf"
    owner = TTCollection(path, lazy=False) if collection else TTFont(path, lazy=False)
    try:
        if collection and not 1 <= len(owner.fonts) <= 128:
            raise ValueError("字体集合的字体面数量无效")
        for font in owner.fonts if collection else [owner]:
            validate_font(font, path.stat().st_size)
    finally:
        owner.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("font")
    args = parser.parse_args()
    try:
        validate(args.font)
    except Exception as error:
        print(str(error) or error.__class__.__name__)
        raise SystemExit(1)
