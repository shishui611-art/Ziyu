#!/usr/bin/env python3
"""Generate standard Magisk-compatible update metadata for one release."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from urllib.parse import quote


def artifact_version(version: str) -> str:
    value = version.strip()
    if not value:
        raise ValueError("version is empty")
    if not value.startswith("v"):
        value = f"v{value}"
    return re.sub(r"[^0-9A-Za-z._-]+", "-", value).strip("-")


def build_metadata(
    *,
    repository: str,
    version: str,
    version_code: int,
    tag: str,
    notes_file: str,
    artifact_name: str = "Ziyu",
) -> dict[str, object]:
    if "/" not in repository or repository.startswith("/") or repository.endswith("/"):
        raise ValueError("repository must be owner/name")
    if version_code <= 0:
        raise ValueError("versionCode must be positive")
    tag = tag.strip()
    if not tag:
        raise ValueError("tag is empty")
    artifact = artifact_version(version)
    release_root = f"https://github.com/{repository}/releases/download/{tag}"
    return {
        "version": version.strip(),
        "versionCode": version_code,
        "zipUrl": f"{release_root}/{artifact_name}-{artifact}.zip",
        "changelog": f"https://raw.githubusercontent.com/{repository}/{tag}/{quote(notes_file, safe='/._-')}",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes-file", required=True)
    parser.add_argument("--artifact-name", default="Ziyu")
    parser.add_argument("--output", required=True)
    parser.add_argument("--fallback-output")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    metadata = build_metadata(
        repository=args.repository,
        version=args.version,
        version_code=args.version_code,
        tag=args.tag,
        notes_file=args.notes_file,
        artifact_name=args.artifact_name,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.fallback_output:
        advance_fallback_channel(metadata, Path(args.fallback_output))
    return 0


def advance_fallback_channel(metadata: dict, output: Path) -> bool:
    """Let preview-channel users receive a newer stable without downgrading RCs."""
    if output.is_file():
        current = json.loads(output.read_text(encoding="utf-8"))
        ziyu = "/Ziyu/" in str(metadata.get("zipUrl", ""))
        def semver(value):
            match = re.match(r"^v?(\d+)\.(\d+)\.(\d+)", str(value))
            return tuple(map(int, match.groups())) if match else None
        next_version, old_version = semver(metadata.get("version")), semver(current.get("version"))
        if ziyu and next_version and old_version and old_version > next_version:
            return False
        if not (ziyu and next_version and old_version and next_version > old_version) and int(current["versionCode"]) > int(metadata["versionCode"]):
            return False
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


if __name__ == "__main__":
    raise SystemExit(main())
