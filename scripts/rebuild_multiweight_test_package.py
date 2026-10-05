#!/usr/bin/env python3
"""Refresh a checked test ZIP with the repaired multiweight worker and native App."""
import argparse
import copy
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--base", type=Path, required=True)
parser.add_argument("--apk", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
if args.output.exists():
    raise SystemExit("Output already exists; refusing to overwrite")
apk = args.apk.read_bytes()
replacements = {
    name: (ROOT / name).read_text(encoding="utf-8").encode("utf-8")
    for name in (
        "common/legacy_v14_4/v143_auto_multiweight_mix.sh",
        "common/legacy_v14_4/v142_weighted_mix.sh",
        "common/legacy_v14_4/font_instance.py",
        "common/font_instance.py",
        "common/composite_font.py",
        "common/legacy_v14_4/composite_font.py",
        "common/app_installer.sh",
        "common/app_bridge.sh",
        "common/font_archive_export.sh",
        "common/native_import.sh",
        "system/bin/luoshu-backup",
        "module.prop",
        "README.md",
    )
}
replacements["bundled/Ziyu-App.apk"] = apk
with zipfile.ZipFile(args.base) as base:
    if base.testzip() is not None:
        raise SystemExit("Base package CRC check failed")
    prop = dict(line.split("=", 1) for line in base.read("bundled/app.prop").decode("utf-8").splitlines() if "=" in line)
    module = dict(line.split("=", 1) for line in (ROOT / "module.prop").read_text(encoding="utf-8").splitlines() if "=" in line)
    prop.update(package="io.github.xgl34222220.ziyu.debug", versionCode=module["versionCode"],
                versionName=module["version"].removeprefix("v") + "-debug", sha256=hashlib.sha256(apk).hexdigest())
    replacements["bundled/app.prop"] = ("\n".join(f"{key}={value}" for key, value in prop.items()) + "\n").encode("utf-8")
    missing = replacements.keys() - set(base.namelist())
    if missing:
        raise SystemExit(f"Expected entries missing: {missing}")
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for old in base.infolist():
            out.writestr(copy.copy(old), replacements.get(old.filename, base.read(old)))
with zipfile.ZipFile(args.output) as archive:
    assert archive.testzip() is None
    assert len(archive.namelist()) == len(set(archive.namelist()))
    for name, expected in replacements.items():
        assert archive.read(name) == expected, name
    assert b"build_composite_cached() (" in archive.read("common/legacy_v14_4/v143_auto_multiweight_mix.sh")
    assert all("__pycache__" not in name and not name.endswith(".pyc") for name in archive.namelist())
    for info in archive.infolist():
        if info.filename.endswith(".sh"):
            assert b"\r\n" not in archive.read(info), info.filename
digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
args.output.with_suffix(args.output.suffix + ".sha256").write_text(
    f"{digest}  {args.output.name}\n", encoding="utf-8")
print(f"CRC, entry uniqueness, embedded App SHA256 and shell line endings passed: {args.output}")
print(f"SHA256={digest}; bytes={args.output.stat().st_size}")
