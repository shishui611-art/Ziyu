#!/usr/bin/env python3
"""Refresh a checked test ZIP from the payload manifest, preserving bundled binaries."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--base", type=Path, required=True)
parser.add_argument("--apk", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--apk-metadata", type=Path,
                    default=ROOT / "android-app/app/build/outputs/apk/debug/output-metadata.json")
args = parser.parse_args()
if args.output.exists():
    raise SystemExit("Output already exists; refusing to overwrite")
apk = args.apk.read_bytes()
module = dict(line.split("=", 1) for line in (ROOT / "module.prop").read_text(encoding="utf-8").splitlines() if "=" in line)
metadata = json.loads(args.apk_metadata.read_text(encoding="utf-8"))
elements = metadata.get("elements", [])
expected_name = module["version"].removeprefix("v") + "-debug"
if (metadata.get("applicationId") != "io.github.shishui611_art.ziyu.debug" or len(elements) != 1 or
        str(elements[0].get("versionCode")) != module["versionCode"] or
        elements[0].get("versionName") != expected_name):
    raise SystemExit("APK 版本或包名与 module.prop 不一致，请先重新编译 Debug App")
if (args.apk_metadata.parent / elements[0]["outputFile"]).read_bytes() != apk:
    raise SystemExit("APK 与构建元数据对应的文件不一致，未生成模块包")
replacements = {}
for entry in (ROOT / "scripts/module_payload_manifest.txt").read_text(encoding="utf-8").splitlines():
    if not entry or entry.startswith("#") or entry == "common/python":
        continue
    source = ROOT / entry
    files = source.rglob("*") if source.is_dir() else [source]
    for path in files:
        if path.is_file() and "__pycache__" not in path.parts and path.suffix not in (".pyc", ".log"):
            replacements[path.relative_to(ROOT).as_posix()] = path.read_bytes()
replacements["bundled/Ziyu-App.apk"] = apk
with zipfile.ZipFile(args.base) as base:
    if base.testzip() is not None:
        raise SystemExit("Base package CRC check failed")
    prop = dict(line.split("=", 1) for line in base.read("bundled/app.prop").decode("utf-8").splitlines() if "=" in line)
    prop.update(package="io.github.shishui611_art.ziyu.debug", versionCode=module["versionCode"],
                versionName=module["version"].removeprefix("v") + "-debug", sha256=hashlib.sha256(apk).hexdigest())
    replacements["bundled/app.prop"] = ("\n".join(f"{key}={value}" for key, value in prop.items()) + "\n").encode("utf-8")
    missing = replacements.keys() - set(base.namelist())
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for old in base.infolist():
            out.writestr(copy.copy(old), replacements.get(old.filename, base.read(old)))
        for name in sorted(missing):
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = (0o100755 if name.startswith("common/") or name.endswith(".sh") else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, replacements[name])
with zipfile.ZipFile(args.output) as archive:
    assert archive.testzip() is None
    assert len(archive.namelist()) == len(set(archive.namelist()))
    for name, expected in replacements.items():
        assert archive.read(name) == expected, name
    assert b"exec sh" in archive.read("common/legacy_v14_4/v143_auto_multiweight_mix.sh")
    assert "common/font_prepare_cache.py" in archive.namelist()
    assert "common/mix_library.py" in archive.namelist()
    assert all("__pycache__" not in name and not name.endswith(".pyc") for name in archive.namelist())
    for info in archive.infolist():
        if info.filename.endswith(".sh"):
            assert b"\r\n" not in archive.read(info), info.filename
digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
args.output.with_suffix(args.output.suffix + ".sha256").write_text(
    f"{digest}  {args.output.name}\n", encoding="utf-8")
print(f"CRC, entry uniqueness, embedded App SHA256 and shell line endings passed: {args.output}")
print(f"SHA256={digest}; bytes={args.output.stat().st_size}")
