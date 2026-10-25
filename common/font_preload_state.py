#!/usr/bin/env python3
"""Bind optional font preload to the generation just verified by the mount hook.

One ordered catalogue per successful hook. No font content reads or hashes.
"""
import os
import json
from pathlib import Path
import sys
import re
import stat
from pathlib import PurePosixPath
from font_route_verify import PARTITIONS, RouteError, namespace_file, physical_roles


def identity(path, follow_symlinks=False):
    st = path.stat(follow_symlinks=follow_symlinks)
    return ':'.join(str(value) for value in
                    (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns))



LIMIT = 192
SUFFIXES = {'.ttf', '.otf', '.ttc', '.font'}


def font_path(value):
    """Partition font paths only; include /system/product-style aliases."""
    if not isinstance(value, str) or any(c in value for c in ('\x00', '\n', '\r', '\\', '|', ':')):
        return None
    value = value.removeprefix('/')
    parts = value.split('/')
    if any(p in {'', '.', '..'} for p in parts) or len(('/' + value).encode()) >= 512:
        return None
    offset = 2 if len(parts) > 3 and parts[0] == 'system' and parts[1] in PARTITIONS else 1
    if parts[0] not in PARTITIONS or len(parts) <= offset + 1 or parts[offset] != 'fonts':
        return None
    return value if PurePosixPath(value).suffix.lower() in SUFFIXES else None


def ordered_fonts(paths, proof):
    """Interleave CJK, Latin and digits before spare aliases consume the budget."""
    applied = set(proof.get('appliedFonts', paths))
    evidence = proof.get('routeEvidence', {})
    evidence = evidence if isinstance(evidence, dict) else {}
    groups = [[], [], [], []]
    for path in sorted(paths):
        entry = evidence.get(path, {})
        roles = set(str(entry.get('roles', '')).split(',')) if isinstance(entry, dict) else set()
        roles |= physical_roles(PurePosixPath(path).name)
        group = 0 if 'cjk' in roles else 1 if 'latin' in roles else 2 if 'digit' in roles else 3
        groups[group].append(path)
    for group in groups:
        group.sort(key=lambda p: (p not in applied,
                                 not re.search(r'(regular|variable|vf|syssans|sysfont|oplusosui)', p, re.I), p))
    for index in range(max(map(len, groups), default=0)):
        for group in groups:
            if index < len(group):
                yield group[index]


def catalogue(module, proof, root):
    """One post-verification metadata pass; no font content reads or hashes."""
    payload = module / '.luoshu-payload'
    if proof.get('mode') == 'physical-safe':
        candidates = proof.get('mountedFonts', [])
        if not isinstance(candidates, list) or not candidates:
            raise ValueError('preload mounted font list unavailable')
    else:
        candidates = [line.split('|', 1)[0] for line in
                      (module / 'config/font-payload-manifest.conf').read_text(encoding='utf-8').splitlines()]
        candidates = [p for p in candidates if PurePosixPath(p).suffix.lower() in SUFFIXES]
    paths = {p for value in candidates if (p := font_path(value)) is not None}
    omitted = sum(font_path(value) is None for value in candidates)
    evidence = proof.get('routeEvidence', {})
    evidence = evidence if isinstance(evidence, dict) else {}
    records, aliases, seen = [], [], set()
    used = 0
    payload_real = payload.resolve(strict=True)

    def record(logical, source_rel, source_id, expected_visible):
        nonlocal used, omitted
        logical = font_path(logical)
        if logical is None or logical in seen:
            return
        try:
            visible, _ = namespace_file(root, logical)
            visible_id = identity(visible)
            if visible_id != expected_visible or not stat.S_ISREG(visible.stat().st_mode):
                return
        except (OSError, RouteError):
            return
        line = f'font=/{logical}|{source_rel}|{source_id}|{visible_id}\n'
        length = len(line.encode('utf-8'))
        if len(records) >= LIMIT or used + length > 120000:
            omitted += 1
            return
        seen.add(logical)
        records.append(line)
        used += length

    for rel in ordered_fonts(paths, proof):
        try:
            source = payload / rel
            resolved = source.resolve(strict=True)
            if not resolved.is_relative_to(payload_real) or not source.is_file():
                raise ValueError('font source outside immutable payload')
            source_id = identity(source, follow_symlinks=True)
            visible, resolved_rel = namespace_file(root, rel)
            visible_stat = visible.stat()
            if not stat.S_ISREG(visible_stat.st_mode) or visible_stat.st_size != source.stat().st_size:
                raise ValueError('verified font changed')
            visible_id = identity(visible)
            record(rel, rel, source_id, visible_id)
            entry = evidence.get(rel, {})
            if isinstance(entry, dict):
                declared = entry.get('declared', '')
                if isinstance(declared, str) and declared:
                    logical = declared if declared.startswith('/') else str(PurePosixPath(rel).parent / declared)
                    aliases.append((logical, rel, source_id, visible_id))
            # Font caches use path keys. Cover observed aliases without treating
            # /data/fonts or an unrelated same-sized file as a safe font source.
            if resolved_rel != rel:
                aliases.append((resolved_rel, rel, source_id, visible_id))
            part, suffix = rel.split('/', 1)
            if part != 'system':
                aliases.append((f'system/{part}/{suffix}', rel, source_id, visible_id))
        except (OSError, ValueError, RouteError):
            omitted += 1
    for alias in aliases:
        record(*alias)
    return ''.join(records), len(records), omitted


def prepare(module):
    module = Path(module)
    config = module / 'config'
    state = config / 'mount-backend.conf'
    payload = module / '.luoshu-payload'
    proof_file = config / 'mount-backend-verification.json'
    target = config / 'font-preload-generation.conf'
    temporary = target.with_name(target.name + f'.tmp.{os.getpid()}')
    if module.is_symlink() or config.is_symlink() or state.is_symlink() or payload.is_symlink() or not payload.is_dir():
        raise ValueError('preload generation paths are not regular module paths')
    if proof_file.is_symlink() or proof_file.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('preload mount proof is not a bounded regular file')
    state_before, payload_before, proof_before = identity(state), identity(payload), identity(proof_file)
    fields = dict(line.split('=', 1) for line in state.read_text(encoding='utf-8').splitlines() if '=' in line)
    boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    if (fields.get('schema') != 'ziyu-mount-backend-v3' or not boot or fields.get('boot_id') != boot
            or fields.get('active_backend') not in {'self', 'external'}
            or fields.get('verification') not in {'passed', 'partial'}
            or fields.get('payload_font', 'default') in {'', 'default'}
            or any(os.path.lexists(module / name) for name in ('disable', 'remove'))):
        raise ValueError('preload requires this boot verified custom-font generation')
    proof = json.loads(proof_file.read_text(encoding='utf-8'))
    if (proof.get('bootId') != boot or proof.get('activeFont') != fields['payload_font']
            or proof.get('state') not in {'verified', 'partial'}
            or proof.get('payloadManifestSha256', '') != fields.get('payload_manifest_sha256', '')):
        raise ValueError('preload mount proof belongs to another generation')
    allowed, count, omitted = catalogue(module, proof, Path('/proc/1/root'))
    if not count:
        raise ValueError('preload has no readable verified fonts')
    allowed = f'expected_fonts={count}\nomitted_fonts={omitted}\n' + allowed
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as output:
            output.write('schema=ziyu-font-preload-generation-v2\n' +
                         f'boot_id={boot}\nstate_identity={state_before}\npayload_identity={payload_before}\n'
                         f'proof_identity={proof_before}\n' + allowed)
            output.flush()
            os.fsync(output.fileno())
        if identity(state) != state_before or identity(payload) != payload_before or identity(proof_file) != proof_before:
            raise ValueError('mount generation changed while preparing optional preload')
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        prepare(sys.argv[1])
    except (OSError, ValueError, IndexError, RouteError) as error:
        print(f'optional preload generation unavailable: {error}', file=sys.stderr)
        raise SystemExit(1)
