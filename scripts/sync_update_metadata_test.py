#!/usr/bin/env python3
import importlib.util
import json
import pathlib
import re
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("sync_update_metadata", ROOT / "scripts" / "sync_update_metadata.py")
mod = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(mod)

meta = mod.build_metadata(
    repository="xgl34222220-ops/LuoShu",
    version="v4.0.0",
    version_code=40000,
    tag="v4.0.0",
    notes_file="RELEASE_NOTES_v4.0.0.md",
    artifact_name="LuoShu",
)
assert meta == {
    "version": "v4.0.0",
    "versionCode": 40000,
    "zipUrl": "https://github.com/xgl34222220-ops/LuoShu/releases/download/v4.0.0/LuoShu-v4.0.0.zip",
    "changelog": "https://raw.githubusercontent.com/xgl34222220-ops/LuoShu/v4.0.0/RELEASE_NOTES_v4.0.0.md",
}

ziyu_meta = mod.build_metadata(
    repository="shishui611-art/Ziyu",
    version="v1.1.0",
    version_code=11000,
    tag="ziyu-v1.1.0",
    notes_file="更新日志/RELEASE_NOTES_ziyu-v1.1.0.md",
    artifact_name="Ziyu",
)
assert ziyu_meta["zipUrl"] == "https://github.com/shishui611-art/Ziyu/releases/download/ziyu-v1.1.0/Ziyu-v1.1.0.zip"

# The fork's update feeds must stay on its own release and never point upstream.
module_props = dict(
    line.split("=", 1)
    for line in (ROOT / "module.prop").read_text(encoding="utf-8").splitlines()
    if "=" in line
)
assert module_props.get("updateJson") == "https://raw.githubusercontent.com/shishui611-art/Ziyu/main/update.json"
app_update_source = (
    ROOT / "android-app" / "app" / "src" / "main" / "java" / "io" / "github"
    / "xgl34222220" / "ziyu" / "ui" / "settings" / "ModuleReleaseUpdater.kt"
).read_text(encoding="utf-8")
assert 'https://api.github.com/repos/shishui611-art/Ziyu/releases/latest' in app_update_source
assert 'https://raw.githubusercontent.com/shishui611-art/Ziyu/$tag/module.prop' in app_update_source
assert 'https://github.com/shishui611-art/Ziyu' in app_update_source
assert 'xgl34222220-ops/LuoShu' not in app_update_source
for metadata_file in ("update.json", "update-prerelease.json"):
    actual = json.loads((ROOT / metadata_file).read_text(encoding="utf-8"))
    # Feeds describe published assets. A candidate must not advertise itself
    # before release.yml succeeds and sync-update-metadata.yml verifies assets.
    version = actual["version"]
    match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z0-9.-]+)?", version)
    assert match, (metadata_file, actual)
    major, minor, patch = map(int, match.groups())
    expected_code = major * 10000 + minor * 1000 + patch
    if version == "v1.0.0" and actual["versionCode"] == 80000:
        expected_code = 80000  # Published 1.0 precedes the 1.1 numbering reset.
    assert actual["versionCode"] == expected_code, (metadata_file, actual)
    expected = mod.build_metadata(
        repository="shishui611-art/Ziyu", version=version,
        version_code=expected_code, tag="ziyu-" + version,
        notes_file="RELEASE_NOTES_ziyu-" + version + ".md", artifact_name="Ziyu",
    )
    assert actual == expected, (metadata_file, actual)

for kwargs in (
    dict(repository="bad", version="v1", version_code=1, tag="v1", notes_file="n"),
    dict(repository="a/b", version="v1", version_code=0, tag="v1", notes_file="n"),
):
    try:
        mod.build_metadata(**kwargs)
    except ValueError:
        pass
    else:
        raise AssertionError(f"expected ValueError: {kwargs}")

with tempfile.TemporaryDirectory() as directory:
    preview = pathlib.Path(directory) / 'preview.json'
    assert mod.advance_fallback_channel(meta, preview)
    assert json.loads(preview.read_text()) == meta
    newer = {**meta, 'versionCode': 40300, 'version': 'v4.3.0'}
    assert mod.advance_fallback_channel(newer, preview)
    assert json.loads(preview.read_text()) == newer
    preview.write_text(json.dumps({**meta, 'versionCode': 40400, 'version': 'v4.4.0-RC1'}))
    before = preview.read_bytes()
    assert not mod.advance_fallback_channel(newer, preview)
    assert preview.read_bytes() == before

print("update metadata tests passed")
