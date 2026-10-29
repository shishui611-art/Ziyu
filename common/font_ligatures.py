"""Restyle Latin GSUB targets without changing the OEM layout tables or glyph IDs."""
from collections import ChainMap

from fontTools.pens.boundsPen import BoundsPen, ControlBoundsPen
from fontTools.pens.transformPen import TransformPen


PRESENTATION = dict(zip(('ff', 'fi', 'fl', 'ffi', 'ffl', 'st'),
                        (0xFB00, 0xFB01, 0xFB02, 0xFB03, 0xFB04, 0xFB06)))


def _rules(font):
    if 'GSUB' not in font:
        return []
    lookups = getattr(font['GSUB'].table, 'LookupList', None)
    result = []
    for lookup in getattr(lookups, 'Lookup', ()):
        for subtable in lookup.SubTable:
            kind = lookup.LookupType
            if kind == 7:
                kind = subtable.ExtensionLookupType
                subtable = subtable.ExtSubTable
            if kind == 4:
                for first, ligatures in subtable.ligatures.items():
                    for ligature in ligatures:
                        result.append(((first, *ligature.Component), ligature.LigGlyph))
    return result


def _sequences(font):
    sequences = {}
    presentation = {cp: sequence for sequence, cp in PRESENTATION.items()}
    presentation[0xFB05] = 'st'
    targets = set()
    for cp, name in (font.getBestCmap() or {}).items():
        if 65 <= cp <= 90 or 97 <= cp <= 122:
            sequences.setdefault(name, set()).add(chr(cp))
        elif cp in presentation:
            sequences.setdefault(name, set()).add(presentation[cp])
            targets.add(name)
    rules = _rules(font)
    # Nested ff + i -> ffi. Bound expansion for malformed/cyclic GSUB tables.
    for _ in range(8):
        changed = False
        for components, target in rules:
            parts = [sequences.get(name, set()) for name in components]
            if not parts or any(len(part) != 1 for part in parts):
                continue
            sequence = ''.join(next(iter(part)) for part in parts)
            if not 2 <= len(sequence) <= 8:
                continue
            values = sequences.setdefault(target, set())
            if sequence not in values:
                values.add(sequence)
                changed = True
        if not changed:
            break
    targets.update(target for _, target in rules)
    return {name: next(iter(values)) for name, values in sequences.items()
            if name in targets and len(values) == 1 and len(next(iter(values))) >= 2}


class _JoinedGlyph:
    def __init__(self, glyphs, names):
        self.glyphs, self.names = glyphs, names
        self.width = sum(glyphs[name].width for name in names)

    def draw(self, pen):
        offset = 0
        for name in self.names:
            glyph = self.glyphs[name]
            glyph.draw(TransformPen(pen, (1, 0, 0, 1, offset, 0)))
            offset += glyph.width


def plan_latin_ligatures(base, donor, replaced, glyphs=None, blocked=()):
    """Return extra target slots plus source outline/metric views (no font edits).

    Use the donor's real ligature when present; otherwise join its selected
    letters. Never import a target shared with another script or a UVS slot.
    """
    glyphs = donor.getGlyphSet() if glyphs is None else glyphs
    metrics = donor['hmtx'].metrics
    targets = _sequences(base)
    if not targets:
        return {}, glyphs, metrics
    base_cmap, donor_cmap = base.getBestCmap() or {}, donor.getBestCmap() or {}
    donor_targets = {}
    for name, sequence in _sequences(donor).items():
        donor_targets.setdefault(sequence, name)
    aliases = {}
    for table in base['cmap'].tables:
        if table.isUnicode() and table.format != 14:
            for cp, name in table.cmap.items():
                aliases.setdefault(name, set()).add(cp)
    blocked = set(blocked)
    for table in base['cmap'].tables:
        if table.format == 14:
            blocked.update(name for entries in getattr(table, 'uvsDict', {}).values()
                           for _, name in entries if name)
    extra, joined, joined_metrics = {}, {}, {}
    for target, sequence in targets.items():
        if target in blocked or target in replaced or target == '.notdef':
            continue
        allowed = {PRESENTATION.get(sequence)}
        if sequence == 'st':
            allowed.add(0xFB05)
        if aliases.get(target, set()) - allowed:
            continue
        names = tuple(donor_cmap.get(ord(char)) for char in sequence)
        if any(not name or replaced.get(base_cmap.get(ord(char))) != name
               for char, name in zip(sequence, names)):
            continue
        source = donor_targets.get(sequence) or donor_cmap.get(PRESENTATION.get(sequence))
        if source is None:
            source = '__ziyu_liga_' + sequence
            while source in glyphs:
                source += '_'
            glyph = _JoinedGlyph(glyphs, names)
            # TrueType xMin describes control points, not the tighter curve
            # extrema. Using curve bounds here gives joined glyphs a different
            # LSB from their emitted glyf outline, especially at small UPEM.
            pen = (ControlBoundsPen if 'glyf' in donor else BoundsPen)(glyphs)
            glyph.draw(pen)
            joined[source] = glyph
            joined_metrics[source] = (glyph.width, pen.bounds[0] if pen.bounds else 0)
        if source in glyphs or source in joined:
            extra[target] = source
    return extra, ChainMap(joined, glyphs), ChainMap(joined_metrics, metrics)
