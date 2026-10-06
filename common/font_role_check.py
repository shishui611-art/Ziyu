#!/usr/bin/env python3
"""Fast role coverage gate used before a LuoShu composite task is queued."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from fontTools.ttLib import TTCollection, TTFont
from font_structure import validate_font
from fontTools.pens.recordingPen import RecordingPen

CJK = tuple(map(ord, "中文字体系统默认洛书汉字"))
LATIN = tuple(map(ord, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"))
DIGITS = tuple(map(ord, "0123456789"))


class RoleCheckError(RuntimeError):
    pass


def is_collection(path: Path) -> bool:
    with path.open("rb") as stream:
        return stream.read(4) == b"ttcf"


def faces(path: Path) -> range:
    if not is_collection(path):
        return range(1)
    collection = TTCollection(str(path), lazy=True)
    try:
        return range(len(collection.fonts))
    finally:
        collection.close()


def required(role: str) -> tuple[int, ...]:
    # The CJK source is the complete cmap base. It must provide the slots that
    # are mandatory for replacement, but punctuation replacement is optional
    # in composite_font.py and must not reject otherwise usable fonts.
    if role == "cjk":
        return CJK + LATIN + DIGITS
    if role == "latin":
        return LATIN
    return DIGITS


def inspect_face(path: Path, index: int, role: str) -> dict[str, object]:
    kwargs: dict[str, object] = {"lazy": True, "recalcTimestamp": False}
    if is_collection(path):
        kwargs["fontNumber"] = index
    font = TTFont(str(path), **kwargs)
    try:
        cmap = validate_font(font, path.stat().st_size, decode_all=False)
        probes = required(role)
        glyphs = font.getGlyphSet()
        missing = []
        for codepoint in probes:
            name = cmap.get(codepoint)
            pen = RecordingPen()
            if name and name != '.notdef' and name in glyphs:
                glyphs[name].draw(pen)
            if not pen.value:
                missing.append(codepoint)
        return {
            "face": index if is_collection(path) else -1,
            "required": len(probes),
            "present": len(probes) - len(missing),
            "missing": [f"U+{codepoint:04X}" for codepoint in missing[:16]],
            "valid": not missing,
        }
    finally:
        font.close()


def check(path: Path, role: str) -> dict[str, object]:
    if not path.is_file():
        raise RoleCheckError("字体文件不存在")
    results = [inspect_face(path, index, role) for index in faces(path)]
    best = max(results, key=lambda item: int(item["present"]), default=None)
    if best is None:
        raise RoleCheckError("字体中没有可读取的字体面")
    return {
        "status": "ok" if best["valid"] else "error",
        "role": role,
        "path": str(path),
        **best,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("font")
    parser.add_argument("role", choices=("cjk", "latin", "digit"))
    args = parser.parse_args()
    try:
        result = check(Path(args.font), args.role)
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 0 if result["valid"] else 2
    except Exception as error:
        print(
            json.dumps(
                {"status": "error", "role": args.role, "message": str(error) or error.__class__.__name__},
                ensure_ascii=False,
                separators=(",", ":"),
            )
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
