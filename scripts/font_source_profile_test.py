#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", type=Path, required=True)
    args = parser.parse_args()
    assert args.font.is_file()

    root = Path(__file__).resolve().parents[1]
    analyzer = root / "common/font_source_profile.py"
    converter = root / "common/font_web_convert.py"

    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        regular = temp / "Demo-Regular.ttf"
        bold = temp / "Demo-Bold.ttf"
        ttc = temp / "Demo.ttc"
        woff = temp / "Demo.woff"
        profile = temp / "profile.json"

        shutil.copy2(args.font, regular)

        from fontTools.ttLib import TTCollection, TTFont

        font = TTFont(str(args.font), lazy=False, recalcTimestamp=False)
        try:
            font["OS/2"].usWeightClass = 700
            font.save(str(bold), reorderTables=False)
        finally:
            font.close()

        first = TTFont(str(regular), lazy=False, recalcTimestamp=False)
        second = TTFont(str(bold), lazy=False, recalcTimestamp=False)
        collection = TTCollection()
        collection.fonts = [first, second]
        try:
            collection.save(str(ttc))
        finally:
            first.close()
            second.close()

        web = TTFont(str(args.font), lazy=False, recalcTimestamp=False)
        try:
            web.flavor = "woff"
            web.save(str(woff), reorderTables=False)
        finally:
            web.close()

        result = run([
            sys.executable, str(analyzer),
            "--font", str(regular),
            "--font", str(bold),
            "--output", str(profile),
        ])
        assert result.returncode == 0, result.stderr or result.stdout
        summary = json.loads(result.stdout)
        payload = json.loads(profile.read_text(encoding="utf-8"))
        assert summary["status"] == "ok"
        assert payload["schema"] == "source-font-profile-v1"
        assert payload["profileRevision"] == 1
        assert payload["summary"]["fileCount"] == 2
        assert payload["summary"]["faceCount"] == 2
        assert 700 in payload["summary"]["weights"]
        assert payload["summary"]["capabilities"]["latinUi"] is True
        assert payload["summary"]["capabilities"]["numeric"] is True
        assert payload["summary"]["requiresSfntConversion"] is False
        face = payload["files"][0]["faces"][0]
        assert face["metrics"]["unitsPerEm"] > 0
        assert face["coverage"]["probes"]["latin"]["ratio"] >= 0.95
        assert face["coverage"]["probes"]["digits"]["ratio"] == 1.0
        assert isinstance(face["tables"]["all"], list)
        assert isinstance(face["variation"]["axes"], list)
        assert "globalUiCandidate" in face["capabilities"]

        validated = run([sys.executable, str(analyzer), "--validate", str(profile)])
        assert validated.returncode == 0, validated.stderr or validated.stdout

        ttc_result = run([sys.executable, str(analyzer), "--font", str(ttc)])
        assert ttc_result.returncode == 0, ttc_result.stderr or ttc_result.stdout
        ttc_summary = json.loads(ttc_result.stdout)
        assert ttc_summary["faceCount"] == 2

        woff_profile = temp / "woff-profile.json"
        woff_result = run([
            sys.executable, str(analyzer),
            "--font", str(woff),
            "--output", str(woff_profile),
        ])
        assert woff_result.returncode == 0, woff_result.stderr or woff_result.stdout
        woff_payload = json.loads(woff_profile.read_text(encoding="utf-8"))
        assert woff_payload["summary"]["requiresSfntConversion"] is True
        assert woff_payload["files"][0]["container"] == "WOFF"

        converted_dir = temp / "converted"
        converted = run([
            sys.executable, str(converter),
            "--input", str(woff),
            "--output-dir", str(converted_dir),
        ])
        assert converted.returncode == 0, converted.stderr or converted.stdout
        converted_json = json.loads(converted.stdout)
        output_path = Path(converted_json["outputPath"])
        assert output_path.is_file()
        assert converted_json["sourceContainer"] == "WOFF"
        assert converted_json["outputFormat"] in {"TTF", "OTF"}

        # Force the Android fallback path without depending on host Brotli.
        # FontTools must reject this synthetic WOFF2, then the test decoder emits
        # a known-good TTF beside the staged input, matching google/woff2 CLI semantics.
        fake_woff2 = temp / "fake-native.woff2"
        fake_woff2.write_bytes(b"wOF2" + b"\x00" * 32)
        fake_decoder = temp / "fake-woff2-decompress"
        fake_decoder.write_text(
            "#!/usr/bin/env python3\n"
            "import shutil, sys\n"
            "from pathlib import Path\n"
            "source = Path(sys.argv[1])\n"
            f"shutil.copyfile({str(regular)!r}, str(source.with_suffix('.ttf')))\n",
            encoding="utf-8",
        )
        fake_decoder.chmod(0o755)

        native_dir = temp / "native-converted"
        native_result = run([
            sys.executable, str(converter),
            "--input", str(fake_woff2),
            "--output-dir", str(native_dir),
            "--woff2-decoder", str(fake_decoder),
        ])
        assert native_result.returncode == 0, native_result.stderr or native_result.stdout
        native_json = json.loads(native_result.stdout)
        assert native_json["sourceContainer"] == "WOFF2"
        assert native_json["decodeMethod"] == "native-arm64"
        assert Path(native_json["outputPath"]).is_file()

        native_profile = temp / "native-profile.json"
        native_env = dict(os.environ)
        native_env["ZIYU_WOFF2_DECODER"] = str(fake_decoder)
        native_profile_result = subprocess.run(
            [
                sys.executable, str(analyzer),
                "--font", str(fake_woff2),
                "--output", str(native_profile),
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=native_env,
        )
        assert native_profile_result.returncode == 0, native_profile_result.stderr or native_profile_result.stdout
        native_profile_json = json.loads(native_profile.read_text(encoding="utf-8"))
        assert native_profile_json["files"][0]["container"] == "WOFF2"
        assert native_profile_json["files"][0]["analysisConversion"]["decodeMethod"] == "native-arm64"
        assert native_profile_json["summary"]["requiresSfntConversion"] is True

        converted_profile = temp / "converted-profile.json"
        post = run([
            sys.executable, str(analyzer),
            "--font", str(output_path),
            "--output", str(converted_profile),
        ])
        assert post.returncode == 0, post.stderr or post.stdout
        post_payload = json.loads(converted_profile.read_text(encoding="utf-8"))
        assert post_payload["summary"]["requiresSfntConversion"] is False
        assert post_payload["summary"]["capabilities"]["latinUi"] is True

    print("font_source_profile_test: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
