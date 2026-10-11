#!/usr/bin/env python3
"""Read-only evidence of SystemUI font mappings that predate a live mount."""
import json
import os
from pathlib import Path, PurePosixPath
import sys
from font_route_verify import RouteError, namespace_file


def identity(path):
    value = os.stat(path)
    return (value.st_dev, value.st_ino, value.st_size)


def process_start(proc):
    # comm may contain spaces/parentheses; starttime is field 22.
    return (proc / 'stat').read_text().rsplit(')', 1)[1].split()[19]


def inspect(module):
    boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    report = dict(schema='ziyu-font-ui-cache-v1', bootId=boot, state='unknown',
                  comparedMappings=0, staleMappings=[], processes=[], readErrors=0)
    pointer = dict(line.split('=', 1) for line in
                   (module / 'config/font-live.conf').read_text().splitlines() if '=' in line)
    report.update(font=pointer.get('font', ''), requestId=pointer.get('request_id', ''))
    if pointer.get('boot_id') != boot or pointer.get('state') != 'mounted':
        return report
    result = json.loads((module / 'config/mount-backend-verification.json').read_text())
    targets = {}
    for raw in result.get('mountedFonts', result.get('appliedFonts', [])):
        path = PurePosixPath(raw)
        if path.is_absolute() or '..' in path.parts or 'fonts' not in path.parts:
            continue
        targets.setdefault(path.name, []).append(str(path))
    ui_compared = 0
    ui_stale = False
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            with (proc / 'cmdline').open('rb') as stream:
                command = stream.read(128).split(b'\0', 1)[0].decode(errors='replace')
            if command not in ('com.android.systemui', 'zygote', 'zygote64', 'system_server'):
                continue
            start = process_start(proc)
            records = []
            visible_targets = {}
            with (proc / 'maps').open() as stream:
                for line in stream:
                    pieces = line.split(None, 5)
                    if len(pieces) != 6:
                        continue
                    mapped_name = pieces[5].strip().removesuffix(' (deleted)').rsplit('/', 1)[-1]
                    matches = targets.get(mapped_name, [])
                    # A basename shared by partitions cannot identify a route.
                    if len(matches) != 1:
                        continue
                    try:
                        mapped = identity(proc / 'map_files' / pieces[0])
                        logical = matches[0]
                        if logical not in visible_targets:
                            # Absolute OEM symlinks must stay in this process's
                            # namespace; do not follow them into the caller's view.
                            target, _ = namespace_file(proc / 'root', logical)
                            visible_targets[logical] = identity(target)
                        visible = visible_targets[logical]
                        records.append(dict(font=matches[0], mappedIdentity=list(mapped),
                                            visibleIdentity=list(visible), stale=mapped != visible))
                    except (OSError, RouteError):
                        report['readErrors'] += 1
            if process_start(proc) != start:
                report['readErrors'] += 1
                continue
            report['processes'].append(dict(pid=int(proc.name), name=command,
                                           startTime=start, comparedMappings=len(records)))
            report['comparedMappings'] += len(records)
            for record in records:
                if record['stale']:
                    report['staleMappings'].append(dict(pid=int(proc.name), process=command, **record))
            if command == 'com.android.systemui':
                ui_compared += len(records)
                ui_stale |= any(record['stale'] for record in records)
        except (OSError, IndexError):
            continue
    report['state'] = 'stale' if ui_stale else ('current-mappings' if ui_compared else 'unknown')
    # This checks mapped files only, not every Typeface object or rendered glyph.
    report['scope'] = 'SystemUI observed font mappings; no visual acceptance'
    return report


if __name__ == '__main__':
    module = Path(sys.argv[1]).resolve()
    output = module / 'config/font-ui-cache.json'
    try:
        report = inspect(module)
        temp = output.with_name(output.name + '.tmp.' + str(os.getpid()))
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        os.replace(temp, output)
        print(report['state'])
    except (OSError, ValueError, TypeError) as error:
        print('unknown')
        print('SystemUI cache inspection unavailable: ' + str(error), file=sys.stderr)
