#!/usr/bin/env python3
"""Materialize a font source at concrete variation-axis values for LuoShu v2.0.0.

Variable fonts are pinned to every requested fvar axis. TTC/OTC collections are
reduced to the face whose coverage and weight best match the requested role.
Plain static TTF/OTF files may be copied by the shell wrapper without invoking
this helper.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
import sys
import traceback
import hashlib
import time
from datetime import datetime, timezone
from pathlib import Path

from fontTools.ttLib import TTCollection, TTFont
from fontTools import subset
from fontTools import __version__ as FONTTOOLS_VERSION
from fontTools.misc.lazyTools import LazyDict
from fontTools.varLib.instancer import instantiateVariableFont

from font_metrics_normalize import normalize_font_metrics

CJK_PROBES = tuple(map(ord, "中文字体系统默认洛书汉字国一的。"))
LATIN_PROBES = tuple(map(ord, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"))
DIGIT_PROBES = tuple(map(ord, "0123456789"))
AXIS_TAG_RE = re.compile(r"^[ -~]{1,4}$")


class InstanceError(RuntimeError):
    pass


def guard_gvar(font: TTFont) -> list[dict[str, object]]:
    """Recover only sparse per-glyph delta decoding errors; never drop characters."""
    recovered = []
    if "gvar" not in font or "glyf" not in font:
        return recovered
    original = font["gvar"].variations
    default_weight = next((float(axis.defaultValue) for axis in font["fvar"].axes if axis.axisTag == "wght"), None)
    limit = min(8, max(1, len(font.getGlyphOrder()) // 100))
    def read(name):
        try:
            return original[name]
        except AssertionError as error:
            if len(recovered) >= limit:
                raise InstanceError("多个字形的可变数据损坏，已停止兼容处理，请更换字体源文件")
            codes = sorted(cp for cp, glyph in (font.getBestCmap() or {}).items() if glyph == name)
            coordinates, _, _ = font["glyf"][name].getCoordinates(font["glyf"])
            if not coordinates and any(not chr(cp).isspace() for cp in codes):
                raise InstanceError(f'字符 {"".join(map(chr, codes))} 的原始轮廓也不可用，已停止应用')
            recovered.append({"glyph": name, "characters": "".join(map(chr, codes)),
                              "codepoints": [f"U+{cp:04X}" for cp in codes], "fallback": "default-outline",
                              "reason": "gvar-delta-decode", "exceptionType": type(error).__name__,
                              "originalWeight": default_weight,
                              "traceback": traceback.format_exc()[-2000:]})
            return []
    font["gvar"].variations = LazyDict({name: read for name in original})
    return recovered


def source_fingerprint(path) -> dict:
    fingerprint = {"path": str(path)} if path else {}
    if path:
        try:
            source = Path(path)
            fingerprint = {"path": str(source), "bytes": source.stat().st_size}
            with source.open("rb") as stream:
                fingerprint["sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
        except (OSError, MemoryError):
            pass
    return fingerprint


def report_error(error: Exception, message: str, code: str, exit_code: int, args, stage="font-instance") -> int:
    fingerprint = source_fingerprint(getattr(args, "input", None))
    inputs = {role: source_fingerprint(getattr(args, role)) for role in ("cjk", "latin", "digit")
              if getattr(args, role, None)}
    try:
        script_digest = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    except (OSError, MemoryError):
        script_digest = "unavailable"
    record = {"schema": "ziyu-font-error-v1", "status": "error", "message": message,
              "errorCode": code, "exitCode": exit_code, "exceptionType": type(error).__name__,
              "stage": stage, "taskId": os.environ.get("LUOSHU_TASK_ID", "unknown"),
              "time": datetime.now(timezone.utc).isoformat(), "monotonicSeconds": time.monotonic(),
              "source": fingerprint, "inputs": inputs, "output": getattr(args, "output", None),
              "role": getattr(args, "role", None),
              "effectiveWeight": getattr(args, "weight", None), "axes": getattr(args, "axes", None),
              "requestedFamily": os.environ.get("LUOSHU_FONT_FAMILY", ""),
              "generatedWeight": os.environ.get("LUOSHU_GENERATED_WEIGHT", ""),
              "weightMode": os.environ.get("LUOSHU_WEIGHT_MODE", ""),
              "pythonVersion": sys.version, "fontToolsVersion": FONTTOOLS_VERSION,
              "scriptSha256": script_digest,
              "traceback": traceback.format_exc()[-20000:]}
    print(json.dumps(record, ensure_ascii=False, separators=(",", ":")), file=sys.stderr)
    return exit_code


def is_collection(path: Path) -> bool:
    with path.open("rb") as stream:
        return stream.read(4) == b"ttcf"


def font_weight(font: TTFont) -> int:
    try:
        return int(font["OS/2"].usWeightClass)
    except Exception:
        return 400


def face_score(font: TTFont, role: str, weight: int) -> tuple[int, int]:
    cmap = font.getBestCmap() or {}
    probes = CJK_PROBES if role == "cjk" else LATIN_PROBES if role == "latin" else DIGIT_PROBES
    hits = sum(1 for codepoint in probes if codepoint in cmap)
    return hits, -abs(font_weight(font) - weight)


def pick_face(path: Path, role: str, weight: int) -> int:
    if not is_collection(path):
        return -1
    collection = TTCollection(str(path), lazy=True)
    try:
        count = len(collection.fonts)
    finally:
        collection.close()
    best: tuple[tuple[int, int], int] | None = None
    for index in range(count):
        font = TTFont(str(path), fontNumber=index, lazy=True, recalcTimestamp=False)
        try:
            score = face_score(font, role, weight)
        finally:
            font.close()
        if best is None or score > best[0]:
            best = score, index
    if best is None or best[0][0] == 0:
        raise InstanceError(f"无法从 {path.name} 中找到适合的{role}字体面")
    return best[1]


def clamp_weight(value: float | int) -> int:
    return max(1, min(1000, int(round(float(value)))))


def parse_axis_spec(spec: str) -> dict[str, float]:
    result: dict[str, float] = {}
    for raw_item in str(spec or "").split(","):
        item = raw_item.strip()
        if not item:
            continue
        if "=" not in item:
            raise InstanceError(f"无效轴参数：{item}")
        tag, raw_value = item.split("=", 1)
        tag = tag.strip()
        if not AXIS_TAG_RE.fullmatch(tag):
            raise InstanceError(f"无效轴标签：{tag}")
        try:
            result[tag] = float(raw_value.strip())
        except ValueError as exc:
            raise InstanceError(f"轴 {tag} 的数值无效") from exc
    return result


def materialize(
    source: Path,
    output: Path,
    role: str,
    requested_weight: int,
    requested_axes: dict[str, float],
    *,
    preserve_metrics: bool = False,
) -> dict[str, object]:
    if not source.is_file() or source.stat().st_size < 12:
        raise InstanceError(f"字体源文件不可用：{source}")
    requested_weight = clamp_weight(requested_axes.get("wght", requested_weight))
    face = pick_face(source, role, requested_weight)
    kwargs: dict[str, object] = {
        "lazy": True,
        "recalcTimestamp": False,
        "recalcBBoxes": True,
    }
    if face >= 0:
        kwargs["fontNumber"] = face
    font = TTFont(str(source), **kwargs)
    variable = "fvar" in font
    location: dict[str, float] = {}
    ignored_axes: list[str] = []
    variation_fallbacks = []
    try:
        if variable:
            variation_fallbacks = guard_gvar(font)
            # A composite uses only the assigned role's codepoints from its
            # Latin/digit inputs. Subset first: large imported variable fonts
            # can contain malformed gvar data in unrelated CJK glyphs, and
            # instancing the entire file needlessly parses all of those glyphs.
            if role in ("latin", "digit"):
                latin = (set(range(0x20, 0x30)) | set(range(0x3A, 0x7F))
                         | set(range(0xA0, 0x250)) | set(range(0x300, 0x370))
                         | set(range(0x1E00, 0x1F00)) | set(range(0x2000, 0x2070))
                         | set(range(0x20A0, 0x20D0)) | set(range(0x2100, 0x2150)))
                digits = (set(range(0x30, 0x3A)) | set(range(0xFF10, 0xFF1A))
                          | {0xB2, 0xB3, 0xB9} | set(range(0x2070, 0x207A))
                          | set(range(0x2080, 0x208A)))
                role_codepoints = (latin | digits) if role == "latin" else digits
                # Donor slots contribute glyf/CFF outlines to the composite,
                # not SVG colour documents indexed by the source glyph IDs.
                # Drop that optional table before subsetting: the offline
                # Android payload deliberately has no native lxml extension.
                # FontTools compares stripped table tags in drop_tables.
                options = subset.Options()
                options.drop_tables.append("SVG")
                role_subset = subset.Subsetter(options=options)
                role_subset.populate(unicodes=role_codepoints)
                role_subset.subset(font)
            known_axes = {str(axis.axisTag): axis for axis in font["fvar"].axes}
            ignored_axes = sorted(tag for tag in requested_axes if tag not in known_axes)
            for tag, axis in known_axes.items():
                requested = requested_axes.get(tag, float(axis.defaultValue))
                location[tag] = float(max(axis.minValue, min(axis.maxValue, requested)))
            font = instantiateVariableFont(font, location, inplace=True, optimize=True)
        elif requested_axes:
            ignored_axes = sorted(tag for tag in requested_axes if tag != "wght")

        final_weight = clamp_weight(location.get("wght", requested_weight))
        for fallback in variation_fallbacks:
            fallback["weight"] = final_weight
            fallback["message"] = f'{fallback["characters"] or fallback["glyph"]}在 {final_weight} 字重无法处理，已保留原始轮廓'
        warning = "；".join(str(item["message"]) for item in variation_fallbacks)
        if "OS/2" in font:
            font["OS/2"].usWeightClass = final_weight
        for tag in ("DSIG", "LTSH", "hdmx", "VDMX"):
            if tag in font:
                del font[tag]
        metrics = {"mode": "preserved"} if preserve_metrics else normalize_font_metrics(font)

        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix=output.name + ".", suffix=".tmp", dir=output.parent, delete=False) as handle:
            temp_path = Path(handle.name)
        try:
            font.save(str(temp_path), reorderTables=False)
            if temp_path.stat().st_size < 12:
                raise InstanceError("可变轴实例化输出异常为空")
            os.chmod(temp_path, 0o644)
            os.replace(temp_path, output)
        finally:
            temp_path.unlink(missing_ok=True)
        return {
            "status": "ok",
            "source": str(source),
            "output": str(output),
            "role": role,
            "weight": final_weight,
            "face": face,
            "variable": variable,
            "location": location,
            "ignoredAxes": ignored_axes,
            "variationFallbacks": variation_fallbacks,
            "warning": warning,
            "metrics": metrics,
            "size": output.stat().st_size,
        }
    finally:
        font.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--role", choices=("cjk", "latin", "digit"), required=True)
    parser.add_argument("--weight", type=int, default=400)
    parser.add_argument("--axes", default="")
    parser.add_argument("--preserve-metrics", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = None
    try:
        args = parse_args()
        result = materialize(
            Path(args.input),
            Path(args.output),
            args.role,
            args.weight,
            parse_axis_spec(args.axes),
            preserve_metrics=args.preserve_metrics,
        )
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 0
    except MemoryError as error:
        return report_error(error, "字体可变轴实例化时内存不足", "FONT_INSTANCE_MEMORY", 12, args)
    except AssertionError as error:
        return report_error(error, "字体可变数据处理失败，请提交完整诊断报告", "FONT_VARIATION_ASSERTION", 13, args)
    except Exception as error:
        return report_error(error, str(error) or error.__class__.__name__, "FONT_INSTANCE_ERROR", 1, args)


if __name__ == "__main__":
    import sys
    raise SystemExit(main())
