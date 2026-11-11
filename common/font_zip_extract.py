#!/usr/bin/env python3
"""Safely extract supported font files from a ZIP archive into a flat directory."""

from __future__ import annotations

from ziyu_limits import limit_bytes as _limit_bytes

import hashlib
from contextlib import contextmanager
import os
import re
import stat
import struct
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree


FONT_SUFFIXES = {".ttf", ".otf", ".ttc"}
CHUNK_SIZE = 1024 * 1024


def _safe_members(archive: zipfile.ZipFile):
    for info in archive.infolist():
        name = info.filename
        if not name or "\x00" in name or "\\" in name:
            raise ValueError("ZIP 条目路径无效")
        if any(ord(char) < 32 or ord(char) == 127 for char in name):
            raise ValueError("ZIP 条目路径包含控制字符")
        if name.startswith("/") or re.match(r"^[A-Za-z]:", name):
            raise ValueError("ZIP 条目不能使用绝对路径")

        path = PurePosixPath(name)
        if any(part in ("", ".", "..") for part in name.split("/")):
            # A trailing slash belongs to a directory entry, which is ignored.
            if info.is_dir() and name.endswith("/") and all(
                part not in (".", "..") for part in name.split("/")[:-1]
            ):
                continue
            raise ValueError("ZIP 条目包含不安全路径")
        if info.is_dir():
            continue
        mode = info.external_attr >> 16
        if stat.S_ISLNK(mode):
            raise ValueError("ZIP 字体包不允许包含符号链接")
        yield info, path


def _font_members(archive: zipfile.ZipFile):
    for info, path in _safe_members(archive):
        if Path(path.name).suffix.lower() in FONT_SUFFIXES:
            if len(path.name.encode("utf-8")) > 512:
                raise ValueError("字体文件名过长")
            yield info, path.name


def _unique_name(name: str, archive_path: str, destination: Path) -> str:
    if not (destination / name).exists():
        return name
    path = Path(name)
    tag = hashlib.sha256(archive_path.encode("utf-8")).hexdigest()[:8]
    candidate = f"{path.stem}-{tag}{path.suffix}"
    index = 2
    while (destination / candidate).exists():
        candidate = f"{path.stem}-{tag}-{index}{path.suffix}"
        index += 1
    return candidate


# MTZ is a ZIP container. Only font payloads are read; theme scripts and artwork
# never become import candidates. The directory shape is documented by the
# miui-font-sample author; no upstream code or font is bundled.
MTZ_FONT_MAGICS = {b"\x00\x01\x00\x00": ".ttf", b"true": ".ttf",
                   b"\x00\x02\x00\x00": ".ttf", b"OTTO": ".otf", b"ttcf": ".ttc"}
MTZ_MAX_ENTRIES = 4096
MTZ_MAX_DIRECTORY_BYTES = 4 * 1024 * 1024
MTZ_MAX_COMPONENT_BYTES = 256 * 1024 * 1024


@contextmanager
def _mtz_archive(stream):
    # Check EOCD before ZipFile allocates a potentially huge central directory.
    stream.seek(0, 2)
    end = stream.tell()
    stream.seek(max(0, end - 65557))
    tail = stream.read()
    at = tail.rfind(b"PK\x05\x06")
    while at >= 0:
        if len(tail) - at >= 22:
            disk, directory_disk, disk_count, count, size, offset, comment = struct.unpack_from("<4H2IH", tail, at + 4)
            if at + 22 + comment == len(tail):
                break
        at = tail.rfind(b"PK\x05\x06", 0, at)
    else:
        raise ValueError("MTZ 不是可读取的压缩包，或文件不完整")
    if (disk or directory_disk or disk_count != count or count > MTZ_MAX_ENTRIES
            or size > MTZ_MAX_DIRECTORY_BYTES or offset + size > end - len(tail) + at):
        raise ValueError("MTZ 文件目录无效或超过读取限制")
    stream.seek(0)
    with zipfile.ZipFile(stream) as archive:
        if len(archive.infolist()) != count:
            raise ValueError("MTZ 文件目录数量不一致")
        yield archive


def _mtz_title(data):
    if len(data) > 65536 or b"\0" in data or b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        return ""
    try:
        root = ElementTree.fromstring(data)
    except (ElementTree.ParseError, ValueError, LookupError):
        return ""
    localized = root.findall("./titles/title")
    preferred = [node for node in localized if node.get("locale") in ("zh_CN", "zh-Hans", "zh_TW")]
    for node in preferred + root.findall("./title") + localized:
        title = " ".join("".join(node.itertext()).split())
        title = "".join(char for char in title if ord(char) >= 32 and ord(char) != 127)[:160]
        if title and title.casefold() not in ("default", "null"):
            return title
    return ""


def _extract_mtz(archive_path, destination, max_files, max_bytes):
    created = []
    budget = {"bytes": 0, "entries": 0, "fonts": 0}
    title = ""

    def account(count):
        budget["bytes"] += count
        if budget["bytes"] > max_bytes:
            raise ValueError("MTZ 展开内容超过容量限制")

    def read_chunks(source, target, info, initial=b""):
        size = len(initial)
        target.write(initial)
        while True:
            block = source.read(CHUNK_SIZE)
            if not block:
                break
            size += len(block)
            account(len(block))
            if size > info.file_size:
                raise ValueError("MTZ 内容超过声明大小")
            target.write(block)
        if size != info.file_size:
            raise ValueError("MTZ 内容读取不完整")

    def visit(archive, prefix="", inside_fonts=False, depth=0):
        nonlocal title
        budget["entries"] += len(archive.infolist())
        if budget["entries"] > MTZ_MAX_ENTRIES:
            raise ValueError("MTZ 文件数量超过读取限制")
        for info, path in _safe_members(archive):
            lower = [part.casefold() for part in path.parts]
            if not depth and path.name.casefold() == "description.xml" and len(path.parts) <= 2 and info.file_size <= 65536:
                if not title and not info.flag_bits & 1:
                    with archive.open(info) as source:
                        data = source.read(65537)
                    account(len(data))
                    title = _mtz_title(data)
                continue
            in_fonts = inside_fonts or "fonts" in lower[:-1]
            suffix = path.suffix.lower()
            component = path.name.casefold() in ("fonts", "fonts.zip")
            candidate = ((in_fonts or len(path.parts) == 1) and suffix in FONT_SUFFIXES | {".mrc"})
            candidate = candidate or (in_fonts and suffix in ("", ".mrc"))
            if not component and not candidate:
                continue
            if info.flag_bits & 1:
                raise ValueError("MTZ 字体已加密，无法读取")
            if not 4 <= info.file_size <= min(max_bytes, max(MTZ_MAX_COMPONENT_BYTES, _limit_bytes())):
                raise ValueError("MTZ 字体或组件大小无效")
            with archive.open(info) as source:
                magic = source.read(4)
                account(len(magic))
                if component and magic == b"PK\x03\x04":
                    if depth >= 1:
                        raise ValueError("MTZ 字体组件嵌套层数过多")
                    # Anonymous disk staging stays inside this import's private
                    # directory; do not retain a second compressed font copy.
                    with tempfile.TemporaryFile(dir=destination) as nested:
                        read_chunks(source, nested, info, magic)
                        with _mtz_archive(nested) as child:
                            visit(child, prefix + info.filename + "/", True, depth + 1)
                    continue
                extension = MTZ_FONT_MAGICS.get(magic)
                if extension is None:
                    # Extensionless entries may be ordinary component metadata.
                    if suffix == "" and not component:
                        continue
                    raise ValueError("MTZ 字体格式不受支持，可能已加密或损坏")
                if budget["fonts"] >= max_files:
                    raise ValueError(f"字体包超过 {max_files} 个字体文件限制")
                base = path.stem if suffix else path.name
                name = base + extension
                if len(name.encode("utf-8")) > 512:
                    raise ValueError("字体文件名过长")
                output = destination / _unique_name(name, prefix + info.filename, destination)
                flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
                fd = os.open(output, flags, 0o600)
                created.append(output)
                with os.fdopen(fd, "wb") as target:
                    read_chunks(source, target, info, magic)
                budget["fonts"] += 1

    try:
        with archive_path.open("rb") as stream:
            identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
            before = identity(os.fstat(stream.fileno()))
            with _mtz_archive(stream) as archive:
                visit(archive)
            if before != identity(os.fstat(stream.fileno())):
                raise ValueError("MTZ 文件在读取期间发生变化，请重新导入")
            # A shared-storage path lookup can expose different metadata from
            # an open file. Compare fstat with fstat, including a fresh open of
            # the original path, so both in-place writes and replacements still
            # invalidate this extraction without another content read or copy.
            try:
                current_fd = os.open(archive_path, os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
                try:
                    if before != identity(os.fstat(current_fd)):
                        raise ValueError("MTZ 文件在读取期间发生变化，请重新导入")
                finally:
                    os.close(current_fd)
            except FileNotFoundError as error:
                raise ValueError("MTZ 文件在读取期间发生变化，请重新导入") from error
        if not budget["fonts"]:
            raise ValueError("这个 MTZ 包中没有可导入的字体")
        if title:
            metadata = destination / ".mtz-title"
            fd = os.open(metadata, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
            created.append(metadata)
            with os.fdopen(fd, "w", encoding="utf-8") as target:
                target.write(title + "\n")
        return budget["fonts"]
    except Exception:
        for path in created:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        raise


def extract(archive_path: Path, destination: Path, max_files: int, max_bytes: int) -> int:
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("字体临时目录必须为空")
    if archive_path.suffix.lower() == ".mtz":
        return _extract_mtz(archive_path, destination, max_files, max_bytes)

    created: list[Path] = []
    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            members = list(_font_members(archive))
            if not members:
                raise ValueError("ZIP 中没有找到 TTF / OTF / TTC 字体")
            if len(members) > max_files:
                raise ValueError(f"字体包超过 {max_files} 个字体文件限制")
            declared_bytes = sum(info.file_size for info, _ in members)
            if declared_bytes > max_bytes:
                raise ValueError(f"字体包解压后超过 {max_bytes} 字节限制")

            total_written = 0
            for info, base_name in members:
                output = destination / _unique_name(base_name, info.filename, destination)
                flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
                if hasattr(os, "O_NOFOLLOW"):
                    flags |= os.O_NOFOLLOW
                fd = os.open(output, flags, 0o600)
                created.append(output)
                actual_size = 0
                with os.fdopen(fd, "wb") as target, archive.open(info, "r") as source:
                    while True:
                        block = source.read(CHUNK_SIZE)
                        if not block:
                            break
                        actual_size += len(block)
                        total_written += len(block)
                        if total_written > max_bytes or actual_size > info.file_size:
                            raise ValueError("ZIP 解压数据超过声明大小或总容量限制")
                        target.write(block)
                if actual_size != info.file_size:
                    raise ValueError("ZIP 字体条目大小与声明不一致")
        return len(created)
    except Exception:
        for path in created:
            try:
                path.unlink()
            except OSError:
                pass
        raise


def main() -> int:
    if len(sys.argv) != 5:
        print("usage: font_zip_extract.py ARCHIVE DESTINATION MAX_FILES MAX_BYTES", file=sys.stderr)
        return 2
    archive = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    try:
        max_files = int(sys.argv[3])
        max_bytes = int(sys.argv[4])
        if max_files <= 0 or max_bytes <= 0:
            raise ValueError("容量限制必须大于零")
        count = extract(archive, destination, max_files, max_bytes)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(str(error) or "字体包读取失败", file=sys.stderr)
        return 1
    print(count)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
