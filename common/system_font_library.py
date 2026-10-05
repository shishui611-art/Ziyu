#!/usr/bin/env python3
"""Copy verified ROM font sources into the public library for three-role mixing."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import shutil
import tempfile

from fontTools.ttLib import TTCollection, TTFont, TTLibError
import font_inventory as inventory
import stock_inventory_scan as stock

CJK = set(map(ord, "中文字体系统默认洛书汉字国一的。"))
LATIN = set(map(ord, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"))
DIGITS = set(map(ord, "0123456789"))
ROLES = {"cjk": ("SystemDefaultCjk", "系统默认 · 中文"),
         "latin": ("SystemDefaultLatin", "系统默认 · 英文"),
         "digit": ("SystemDefaultDigit", "系统默认 · 数字")}
WEIGHTS = {100: "Thin", 200: "ExtraLight", 300: "Light", 400: "Regular", 500: "Medium",
           600: "SemiBold", 700: "Bold", 800: "ExtraBold", 900: "Black"}


@dataclass(frozen=True)
class Face:
    path: Path
    logical: str
    index: int | None
    family: str
    name: str
    weight: int
    variable: bool
    cjk: bool
    latin: bool
    digit: bool


def read_faces(path: Path, logical: str) -> list[Face]:
    with path.open("rb") as stream:
        signature = stream.read(4)
        collection = signature == b"ttcf"
        if signature not in (b"ttcf", b"\x00\x01\x00\x00", b"OTTO", b"true"):
            raise TTLibError("Unsupported font signature")
    indices: list[int | None] = [None]
    if collection:
        fonts = TTCollection(path, lazy=True)
        try:
            indices = list(range(len(fonts.fonts)))
        finally:
            fonts.close()
    result = []
    for index in indices:
        with TTFont(path, fontNumber=index if index is not None else -1, lazy=True) as font:
            cmap = set((font.getBestCmap() or {}).keys())
            name = font["name"].getBestFullName() or path.stem
            family = font["name"].getBestFamilyName() or name
            if font["head"].macStyle & 2 or ("OS/2" in font and font["OS/2"].fsSelection & 1):
                continue
            result.append(Face(path, logical, index, family, name,
                               int(font["OS/2"].usWeightClass) if "OS/2" in font else 400,
                               "fvar" in font, CJK <= cmap, LATIN <= cmap, DIGITS <= cmap))
    return result


def stock_faces(module: Path) -> list[Face]:
    risk = stock._private_overlay_risk(module)
    roots = []
    for partition, logical in stock._logical_font_roots(module):
        try:
            actual = stock._safe_pick_actual_root(logical, None, risk)
        except inventory.InventoryError:
            continue
        if actual.is_dir():
            roots.append(inventory.FontRoot(partition, logical, actual))
    faces = []
    seen = set()
    for root in roots:
        for path in sorted(root.actual.iterdir()):
            if path.suffix.lower() not in inventory.FONT_EXTENSIONS:
                continue
            if any(word in path.name.lower() for word in ("emoji", "symbol", "icon", "math", "music")):
                continue
            try:
                trusted = inventory._stock_font_path(root, path, roots)
                if str(trusted) in seen:
                    continue
                seen.add(str(trusted))
                faces.extend(read_faces(trusted, str(root.logical / path.name)))
            except (OSError, ValueError, inventory.InventoryError, KeyError, TTLibError):
                continue
    return faces


def eligible(face: Face, role: str) -> bool:
    if role == "cjk":
        return face.cjk and face.latin and face.digit
    return face.latin if role == "latin" else face.digit


def rank(face: Face, role: str, main: str) -> tuple:
    name = (face.name + " " + face.path.name).lower().replace(" ", "")
    specialized_digits = role == "digit" and "solid-digits" in name
    simplified = role == "cjk" and any(word in name for word in ("cjksc", "sanssc", "simplified", "简体"))
    serif_or_mono = any(word in name for word in ("serif", "mono"))
    return (not specialized_digits, face.logical != main, not simplified,
            serif_or_mono, role != "cjk" and face.cjk,
            abs(face.weight - 400), not face.variable, face.name, str(face.path), face.index or 0)


def import_system_fonts(module: Path, destination: Path) -> dict:
    try:
        data = json.loads((module / "config/device_font_inventory.json").read_text())
        main = str(data.get("mainSlotPath", ""))
    except (OSError, ValueError):
        main = ""
    try:
        faces = stock_faces(module)
        choices = {}
        for role in ROLES:
            candidates = [face for face in faces if eligible(face, role)]
            if not candidates:
                raise ValueError(f"无法读取原厂{ROLES[role][1].split(' · ')[1]}字体，请恢复系统字体并重启后重试")
            chosen = min(candidates, key=lambda face: rank(face, role, main))
            if chosen.variable:
                choices[role] = [chosen]
            else:
                siblings = [face for face in candidates if face.family == chosen.family and not face.variable]
                by_weight = {}
                for face in sorted(siblings, key=lambda item: rank(item, role, main)):
                    weight = min(WEIGHTS, key=lambda value: abs(value - face.weight))
                    by_weight.setdefault(weight, face)
                choices[role] = list(by_weight.values())
        destination.mkdir(parents=True, exist_ok=True)
        reports = []
        with tempfile.TemporaryDirectory(prefix=".system-font-import-", dir=destination) as temp:
            stage = Path(temp)
            for role, selected in choices.items():
                family_id, display = ROLES[role]
                paths = []
                for face in selected:
                    with TTFont(face.path, fontNumber=face.index if face.index is not None else -1) as font:
                        extension = "otf" if font.sfntVersion == "OTTO" else "ttf"
                        weight_name = "Variable" if face.variable else WEIGHTS[min(WEIGHTS, key=lambda value: abs(value - face.weight))]
                        output = stage / f"{family_id}-{weight_name}.{extension}"
                        if face.index is None:
                            shutil.copyfile(face.path, output)
                        else:
                            font.save(output, reorderTables=False)
                    # Imported sources keep their real axes, family and glyphs.
                    with TTFont(output, lazy=True) as check:
                        if not check.getBestCmap():
                            raise ValueError("原厂字体导出后没有有效字符映射")
                    os.chmod(output, 0o644)
                    paths.append(output.name)
                (stage / f"{family_id}.conf").write_text(
                    f"name={display}\nsupports_cjk={'true' if selected[0].cjk else 'false'}\nsource=system-default\n", encoding="utf-8")
                reports.append({"id": family_id, "name": display, "files": paths})
            staged = {file.name for file in stage.iterdir()}
            for file in stage.iterdir():
                os.replace(file, destination / file.name)
            for family_id, _display in ROLES.values():
                for old in destination.glob(f"{family_id}-*"):
                    if old.suffix.lower() in inventory.FONT_EXTENSIONS and old.name not in staged:
                        old.unlink()
        return {"status": "ok", "data": {"fonts": reports}}
    finally:
        stock._cleanup_install_snapshots()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(import_system_fonts(args.module, args.destination), ensure_ascii=False, separators=(",", ":")))
        return 0
    except Exception as error:
        print(json.dumps({"status": "error", "message": str(error) or type(error).__name__}, ensure_ascii=False, separators=(",", ":")))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
