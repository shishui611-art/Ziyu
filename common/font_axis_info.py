#!/usr/bin/env python3
"""Read variable-axis capability for the LuoShu native App without modifying a font."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import tempfile
from pathlib import Path

CACHE_SCHEMA = 'ziyu-font-axis-cache-v1'
MAX_CACHE_BYTES = 128 * 1024


def file_identity(path: Path) -> tuple:
    value = path.stat()
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def valid_axis_payload(data) -> bool:
    if not isinstance(data, dict) or data.get('status') != 'ok':
        return False
    axes = data.get('axes')
    if not isinstance(axes, list) or len(axes) > 64:
        return False
    tags = set()
    try:
        for axis in axes:
            tag = axis['tag']
            values = [float(axis[key]) for key in ('min', 'default', 'max')]
            if (not isinstance(tag, str) or len(tag) != 4 or tag in tags
                    or any(not 32 <= ord(char) <= 126 for char in tag)
                    or not all(math.isfinite(value) for value in values)
                    or not values[0] <= values[1] <= values[2]):
                return False
            tags.add(tag)
    except (KeyError, TypeError, ValueError, OverflowError):
        return False
    return True


def cached_axis_info(path: Path, cache: Path | None) -> dict:
    before = file_identity(path)
    cache_file = None
    if cache is not None and not cache.is_symlink() and not cache.parent.is_symlink():
        key = hashlib.sha256(str(path.absolute()).encode('utf-8')).hexdigest()
        cache_file = cache / (key + '.json')
        try:
            if not cache_file.is_symlink() and cache_file.stat().st_size <= MAX_CACHE_BYTES:
                saved = json.loads(cache_file.read_text(encoding='utf-8'))
                if (saved.get('schema') == CACHE_SCHEMA and saved.get('identity') == list(before)
                        and saved.get('source') == str(path.absolute()) and valid_axis_payload(saved.get('data'))
                        and file_identity(path) == before):
                    return saved['data']
        except (OSError, ValueError, AttributeError):
            pass
    data = read_axis_info(path)
    if file_identity(path) != before:
        raise ValueError('读取期间源字体已变化，请重试')
    if cache_file is not None:
        temporary = None
        try:
            cache.mkdir(parents=True, exist_ok=True)
            payload = json.dumps({'schema': CACHE_SCHEMA, 'source': str(path.absolute()),
                                  'identity': before, 'data': data}, ensure_ascii=False).encode('utf-8')
            if len(payload) <= MAX_CACHE_BYTES and not cache_file.is_symlink():
                fd, name = tempfile.mkstemp(prefix='.axis-', dir=cache)
                temporary = Path(name)
                with os.fdopen(fd, 'wb') as stream:
                    stream.write(payload)
                if file_identity(path) != before:
                    raise ValueError('写入缓存前源字体已变化，请重试')
                os.replace(temporary, cache_file)
                # Per-source files replace their previous generation. Bound old
                # source paths as well; no directory scan occurs on a cache hit.
                entries = [p for p in cache.glob('*.json') if p.is_file() and not p.is_symlink()]
                if len(entries) > 128:
                    for old in sorted(entries, key=lambda p: p.stat().st_mtime_ns)[:len(entries) - 128]:
                        if old != cache_file:
                            old.unlink(missing_ok=True)
        except OSError:
            # A cache failure must not discard successfully read font metadata.
            pass
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass
    if file_identity(path) != before:
        raise ValueError('读取期间源字体已变化，请重试')
    return data


def read_axis_info(path: Path) -> dict[str, object]:
    from fontTools.ttLib import TTFont
    from font_metadata import axes as font_axes, is_collection
    if not path.is_file():
        raise FileNotFoundError(path)
    before = path.stat()
    kwargs: dict[str, object] = {"lazy": True, "recalcTimestamp": False}
    # Keep TTFont lazy: collection detection needs four bytes, not a full CJK
    # font allocation before the actual axis/name tables can be read.
    if is_collection(path):
        kwargs["fontNumber"] = 0
    font = TTFont(str(path), **kwargs)
    try:
        axes = font_axes(font)
        if len(axes) > 64:
            raise ValueError("字体轴数量异常")
        tags = set()
        for index, axis in enumerate(axes):
            tag = axis["tag"]
            minimum, default, maximum = (axis[key] for key in ("min", "default", "max"))
            if (len(tag) != 4 or any(not 32 <= ord(char) <= 126 for char in tag)
                    or tag in tags or not all(math.isfinite(value) for value in (minimum, default, maximum))
                    or not minimum <= default <= maximum):
                raise ValueError(f"字体轴 {tag} 的定义无效")
            tags.add(tag)
            axis["hidden"] = bool(font["fvar"].axes[index].flags & 1)
        weight = next((axis for axis in axes if axis["tag"] == "wght"), None)
        result = {
            "status": "ok",
            "variable": bool(axes),
            "hasWeight": weight is not None,
            "weight": weight,
            "axes": axes,
        }
        after = path.stat()
        identity = lambda value: (value.st_dev, value.st_ino, value.st_size,
                                  value.st_mtime_ns, value.st_ctime_ns)
        if identity(before) != identity(after):
            raise ValueError("读取期间源字体已变化，请重试")
        return result
    finally:
        font.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('font', type=Path)
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    result = cached_axis_info(args.font, args.cache)
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"status": "error", "message": str(error)}, ensure_ascii=False, separators=(",", ":")))
        raise SystemExit(1)
