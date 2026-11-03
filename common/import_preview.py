#!/usr/bin/env python3
"""Read metadata for a bounded App-private import preview, never import a font."""
from pathlib import Path
import json
import re
import sys
import zipfile
from font_metadata import inspect


def inspect_preview(path: Path) -> dict:
    path = path.resolve(strict=True)
    if not re.fullmatch(r"/data/(?:user/\d+|data)/io\.github\.shishui611_art\.ziyu/cache/native-import-preview-[a-zA-Z0-9-]+/[a-f0-9]{64}\.(?:ttf|otf|ttc|woff2?|zip)", path.as_posix()):
        raise ValueError("预览文件不在字域临时目录")
    if not 0 < path.stat().st_size <= 268435456:
        raise ValueError("预览文件为空或超过 256 MB")
    if path.suffix == '.zip':
        with path.open('rb') as stream:
            stream.seek(max(0, path.stat().st_size - 65557))
            tail = stream.read()
        end = tail.rfind(b'PK\x05\x06')
        if end < 0 or len(tail[end:]) < 22:
            raise ValueError('ZIP 目录无效，可继续使用原导入流程检查')
        count = int.from_bytes(tail[end+10:end+12], 'little')
        size = int.from_bytes(tail[end+12:end+16], 'little')
        if count > 4096 or size > 4194304:
            raise ValueError('ZIP 文件列表超过预览限制，可继续导入')
        with zipfile.ZipFile(path) as archive:
            names = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
            return {'status': 'ok', 'data': {'archiveEntries': [name[:256] for name in names[:128]], 'entryCount': len(names)}}
    # Bound collection fan-out before invoking the existing metadata reader.
    with path.open('rb') as stream:
        header = stream.read(12)
    if header[:4] == b'ttcf' and not 0 < int.from_bytes(header[8:12], 'big') <= 32:
        raise ValueError("集合字面超过预览上限，可继续导入")
    return inspect(path)


if __name__ == '__main__':
    try:
        print(json.dumps(inspect_preview(Path(sys.argv[1])), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
