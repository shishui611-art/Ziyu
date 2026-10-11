#!/usr/bin/env python3
"""Emit the existing physical manifest format without rehashing hardlinks."""
import os
from pathlib import Path
import sys

from font_digest_cache import FontDigestCache


def build(root, listing, output):
    root = Path(root)
    resolved_root = root.resolve(strict=True)
    hashes = FontDigestCache()
    fonts = 0
    with Path(listing).open(encoding='utf-8') as paths, Path(output).open('w', encoding='utf-8', newline='\n') as result:
        for raw in paths:
            path = Path(raw.rstrip('\n'))
            suffix = path.suffix
            if suffix in ('.ttf', '.otf', '.ttc', '.font', '.TTF', '.OTF', '.TTC'):
                fonts += 1
            elif suffix != '.xml':
                continue
            if not path.is_file() or path.stat().st_size <= 0:
                raise ValueError('missing or empty physical artifact')
            if resolved_root not in path.resolve(strict=True).parents:
                raise ValueError('physical artifact leaves frozen payload')
            relative = path.relative_to(root).as_posix()
            if any(token in relative for token in ('|', '\\')) or '..' in Path(relative).parts:
                raise ValueError('unsafe physical manifest path')
            result.write(relative + '|' + hashes.sha256(path) + '\n')
        if fonts == 0 or result.tell() == 0:
            raise ValueError('physical manifest has no fonts')
        result.flush()
        os.fsync(result.fileno())


if __name__ == '__main__':
    try:
        build(*sys.argv[1:])
    except (OSError, ValueError, TypeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
