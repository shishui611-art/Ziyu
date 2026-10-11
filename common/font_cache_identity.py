#!/usr/bin/env python3
"""Identify inventory contents without making scan time a cache dependency."""
import hashlib
import json
from pathlib import Path
import sys


def inventory_identity(path):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(data, dict):
        raise ValueError('inventory must be an object')
    # Preserve every topology, source, slot, metric and schema field. Only the
    # scanner's wall-clock timestamp is irrelevant to the generated fonts.
    data.pop('generatedAt', None)
    canonical = json.dumps(data, sort_keys=True, separators=(',', ':'),
                           ensure_ascii=True).encode('utf-8')
    return 'inventory-content-v1:' + hashlib.sha256(canonical).hexdigest()


if __name__ == '__main__':
    try:
        print(inventory_identity(sys.argv[1]))
    except (OSError, ValueError, IndexError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
