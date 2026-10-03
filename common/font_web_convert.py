#!/usr/bin/env python3
"""Convert WOFF/WOFF2 web-font containers to Android-usable SFNT.

WOFF uses FontTools directly. WOFF2 first tries FontTools' Brotli-backed decoder;
the pruned Android runtime then falls back to LuoShu's pinned ARM64
woff2_decompress helper. An extension rename is never treated as conversion.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from fontTools.ttLib import TTCollection, TTFont

MAGIC = {b"wOFF": "WOFF", b"wOF2": "WOFF2"}
SFNT_MAGIC = {
    b"\x00\x01\x00\x00": "TTF",
    b"true": "TTF",
    b"\x00\x02\x00\x00": "TTF",
    b"OTTO": "OTF",
    b"ttcf": "TTC",
}
DEFAULT_WOFF2_DECODER = (
    Path(__file__).resolve().parents[1] / ".luoshu-runtime/bin/woff2_decompress"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_stem(value: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "-" for ch in value)
    cleaned = cleaned.strip(".-_")
    return cleaned[:80] or "ConvertedFont"


def sfnt_format(path: Path) -> str:
    with path.open("rb") as stream:
        magic = stream.read(4)
    value = SFNT_MAGIC.get(magic)
    if value is None:
        raise ValueError("转换结果不是有效 SFNT/TTC 字体")
    return value


def extension_for_format(value: str) -> str:
    return {"TTF": "ttf", "OTF": "otf", "TTC": "ttc"}[value]


def validate_decoded(path: Path) -> tuple[str, int]:
    fmt = sfnt_format(path)
    if path.stat().st_size < 4096:
        raise ValueError("转换结果异常为空")
    if fmt == "TTC":
        collection = TTCollection(str(path), lazy=True)
        try:
            count = len(collection.fonts)
            if count < 1 or count > 128:
                raise ValueError("转换后的 TTC 字体面数量异常")
            for font in collection.fonts:
                if "cmap" not in font or "head" not in font:
                    raise ValueError("转换后的 TTC 字体面缺少 cmap/head")
            return fmt, count
        finally:
            collection.close()

    font = TTFont(str(path), lazy=True, recalcTimestamp=False)
    try:
        if "cmap" not in font or "head" not in font:
            raise ValueError("转换结果缺少 cmap/head")
    finally:
        font.close()
    return fmt, 1


def _fonttools_decode(source: Path, output: Path) -> None:
    font = TTFont(str(source), lazy=False, recalcTimestamp=False)
    try:
        font.flavor = None
        font.save(str(output), reorderTables=False)
    finally:
        font.close()


def _native_woff2_decode(source: Path, output_dir: Path, decoder: Path) -> tuple[Path, str]:
    if not decoder.is_file() or not os.access(decoder, os.X_OK):
        raise ValueError("WOFF2 解码器不可用；字域运行时缺少 ARM64 woff2_decompress")
    stage = Path(tempfile.mkdtemp(prefix=".luoshu-woff2-", dir=output_dir))
    try:
        staged_input = stage / (safe_stem(source.stem) + ".woff2")
        shutil.copyfile(source, staged_input)
        try:
            result = subprocess.run(
                [str(decoder), str(staged_input)],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=90,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise ValueError(f"WOFF2 原生解码器启动失败：{error}") from error
        staged_output = staged_input.with_suffix(".ttf")
        if result.returncode != 0 or not staged_output.is_file():
            detail = " ".join((result.stderr or result.stdout).split())[:220]
            raise ValueError(f"WOFF2 原生解码失败{('：' + detail) if detail else ''}")
        final_temp = output_dir / f".woff2-native-{os.getpid()}-{source.name}.sfnt"
        shutil.copyfile(staged_output, final_temp)
        return final_temp, "native-arm64"
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def convert(
    source: Path,
    output_dir: Path,
    woff2_decoder: Path | None = None,
) -> dict[str, Any]:
    if not source.is_file() or source.stat().st_size < 12:
        raise ValueError("网页字体文件不存在或过小")
    with source.open("rb") as stream:
        container = MAGIC.get(stream.read(4))
    if container is None:
        raise ValueError("仅支持 WOFF / WOFF2 转换")

    output_dir.mkdir(parents=True, exist_ok=True)
    source_hash = sha256(source)
    decoder = (
        woff2_decoder
        or (Path(os.environ["LUOSHU_WOFF2_DECODER"]) if os.environ.get("LUOSHU_WOFF2_DECODER") else None)
        or DEFAULT_WOFF2_DECODER
    )
    decode_method = "fonttools"
    raw_temp: Path | None = None

    with tempfile.NamedTemporaryFile(
        prefix=".luoshu-webfont-", suffix=".sfnt", dir=output_dir, delete=False
    ) as handle:
        temp = Path(handle.name)
    try:
        try:
            _fonttools_decode(source, temp)
        except Exception as error:
            temp.unlink(missing_ok=True)
            if container != "WOFF2":
                raise ValueError(f"WOFF 解码失败：{error}") from error
            raw_temp, decode_method = _native_woff2_decode(source, output_dir, decoder)
            temp = raw_temp

        output_format, face_count = validate_decoded(temp)
        ext = extension_for_format(output_format)
        target = output_dir / f"{safe_stem(source.stem)}-{source_hash[:10]}.{ext}"
        output_hash = sha256(temp)
        duplicate = target.is_file() and sha256(target) == output_hash
        if not duplicate:
            final_temp = target.with_name(target.name + f".tmp.{os.getpid()}")
            shutil.copyfile(temp, final_temp)
            os.chmod(final_temp, 0o644)
            os.replace(final_temp, target)
        return {
            "status": "ok",
            "sourceContainer": container,
            "sourceSha256": source_hash,
            "decodeMethod": decode_method,
            "outputFormat": output_format,
            "outputSha256": output_hash,
            "outputPath": str(target),
            "outputFileName": target.name,
            "faceCount": face_count,
            "duplicate": duplicate,
        }
    finally:
        temp.unlink(missing_ok=True)
        if raw_temp is not None and raw_temp != temp:
            raw_temp.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--woff2-decoder", type=Path)
    args = parser.parse_args()
    try:
        result = convert(args.input, args.output_dir, args.woff2_decoder)
    except Exception as error:
        print(json.dumps({"status": "error", "message": str(error)}, ensure_ascii=False, separators=(",", ":")))
        return 1
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
