#!/usr/bin/env python3
"""Reuse a successful full source check only while its exact inputs match.

This is an intra-workflow receipt, not a replacement for source checks. The
release job receives it from the required source-check job in the same run.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import time

SCHEMA = "ziyu-source-check-v1"
ROOT = Path(__file__).resolve().parents[1]


class ReceiptError(RuntimeError):
    pass


def _git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def snapshot(root):
    """Hash tracked worktree bytes/modes plus every actual module payload input.

    Payload directories include the downloaded Python runtime. Only generated
    bytecode is excluded there, as the packager removes it from the module.
    Build outputs and private local files outside the manifest are not inputs.
    """
    root = Path(root).resolve()
    head = _git(root, "rev-parse", "HEAD").decode("ascii").strip()
    paths = {}
    for entry in _git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        mode, _, stage = metadata.decode("ascii").split()
        if stage != "0":
            raise ReceiptError("Unmerged source entries cannot be validated")
        paths[os.fsdecode(name)] = mode

    def add_payload(path):
        if path.is_symlink() or path.is_file():
            paths.setdefault(path.relative_to(root).as_posix(), "payload")
        elif path.is_dir():
            for child in sorted(path.iterdir()):
                if child.name == "__pycache__" or child.suffix == ".pyc":
                    continue
                add_payload(child)
        else:
            raise ReceiptError("Missing source-check payload: " + str(path.relative_to(root)))

    manifest = root / "scripts/module_payload_manifest.txt"
    if not manifest.is_file():
        raise ReceiptError("Module payload manifest is missing")
    for entry in manifest.read_text(encoding="utf-8").splitlines():
        entry = entry.strip()
        if not entry or entry.startswith("#"):
            continue
        relative = Path(entry)
        if relative.is_absolute() or ".." in relative.parts:
            raise ReceiptError("Invalid module payload path")
        add_payload(root / relative)

    digest = hashlib.sha256()
    for name, mode in sorted(paths.items()):
        path = root / name
        info = path.lstat()
        item = hashlib.sha256()
        if stat.S_ISLNK(info.st_mode):
            kind = "symlink"
            item.update(os.fsencode(os.readlink(path)))
            if path.is_file():
                # Runtime library aliases must also notice changed target bytes.
                item.update(b"\0")
                with path.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        item.update(block)
        elif stat.S_ISREG(info.st_mode):
            kind = "file"
            with path.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    item.update(block)
        else:
            raise ReceiptError("Unsupported source input: " + name)
        record = [name, mode, kind, info.st_mode & 0o111, item.hexdigest()]
        digest.update(json.dumps(record, ensure_ascii=True, separators=(",", ":")).encode("ascii"))
        digest.update(b"\n")
    return {"sourceSha": head, "inputSha256": digest.hexdigest(), "inputCount": len(paths)}


def run_checks(root, receipt):
    receipt = Path(receipt)
    receipt.unlink(missing_ok=True)
    before = snapshot(root)
    started = time.monotonic()
    result = subprocess.run(["sh", "-x", str(Path(root) / "scripts/check.sh")], cwd=root)
    if result.returncode:
        return result.returncode
    after = snapshot(root)
    if before != after:
        raise ReceiptError("Source-check inputs changed while the full checks were running")
    report = {"schema": SCHEMA, "state": "passed", **after,
              "checkSeconds": round(time.monotonic() - started, 3)}
    receipt.parent.mkdir(parents=True, exist_ok=True)
    temporary = receipt.with_name(receipt.name + ".tmp." + str(os.getpid()))
    try:
        temporary.write_text(json.dumps(report, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temporary, receipt)
    finally:
        temporary.unlink(missing_ok=True)
    print("Full source checks passed; receipt written for " + after["sourceSha"])
    return 0


def verify_receipt(root, receipt):
    report = json.loads(Path(receipt).read_text(encoding="utf-8"))
    if not isinstance(report, dict) or report.get("schema") != SCHEMA or report.get("state") != "passed":
        raise ReceiptError("Source-check receipt does not record a completed successful check")
    current = snapshot(root)
    for key, value in current.items():
        if report.get(key) != value:
            raise ReceiptError("Source-check receipt no longer matches " + key)
    print("Verified full source checks for unchanged inputs: " + current["sourceSha"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "verify"))
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "run":
            return run_checks(ROOT, args.receipt)
        verify_receipt(ROOT, args.receipt)
        return 0
    except (OSError, ValueError, ReceiptError, subprocess.CalledProcessError) as error:
        print("Source-check receipt rejected: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
