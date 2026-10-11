"""Write only changed SFNT tables, retaining untouched outline/layout bytes.

The cache belongs to one isolated metrics batch. It never survives activation.
"""
from collections import OrderedDict
from fontTools.ttLib.sfnt import SFNTWriter


class FontMetricsIO:
    def __init__(self, budget=32 * 1024 * 1024):
        self.budget = budget
        self.bytes = 0
        self.raw = OrderedDict()
        self.cmap_identity = None
        self.cmap = None
        self.glyph_order = None
        self.table_reads = 0
        self.table_hits = 0
        self.cmap_hits = 0
        self.direct_writes = 0

    def seed(self, font, identity):
        # ColorOS never edits cmap. Share only this read-only decoded table,
        # never the head/hhea/OS2 tables modified for a particular stock slot.
        if identity == self.cmap_identity and self.cmap is not None:
            self.cmap_hits += 1
            font.tables['cmap'] = self.cmap
            for table in self.cmap.tables:
                table.ttFont = font
            if self.glyph_order is not None:
                font.setGlyphOrder(self.glyph_order)

    def remember(self, font, identity):
        self.cmap_identity = identity
        self.cmap = font.tables.get('cmap')
        self.glyph_order = getattr(font, 'glyphOrder', None)
        if self.cmap is not None:
            # Lazy cmap subtables hold a back-reference to TTFont. Release that
            # closed reader/decoded outline graph; seed supplies the next reader.
            for table in self.cmap.tables:
                table.ttFont = None

    def raw_table(self, font, identity, tag):
        key = (identity, tag)
        if key in self.raw:
            self.table_hits += 1
            self.raw.move_to_end(key)
            return self.raw[key]
        data = font.reader[tag]
        self.table_reads += 1
        if len(data) <= self.budget:
            while self.bytes + len(data) > self.budget and self.raw:
                self.bytes -= len(self.raw.popitem(last=False)[1])
            self.raw[key] = data
            self.bytes += len(data)
        return data

    def save(self, font, output, identity, changed):
        if font.reader is None or font.flavor is not None:
            # Preserve FontTools' handling of non-SFNT containers.
            font.save(output, reorderTables=False)
            return
        # Compile first: OS/2 may load cmap to update first/last char indices.
        # That read does not mean unchanged cmap/outlines need serialization.
        compiled = {tag: font.getTableData(tag) for tag in changed if tag in font}
        tags = [tag for tag in font.reader.keys() if tag in font]
        tags.extend(tag for tag in font.keys() if tag != 'GlyphOrder' and tag not in tags)
        with open(output, 'wb') as stream:
            writer = SFNTWriter(stream, len(tags), font.sfntVersion)
            for tag in tags:
                writer[tag] = compiled[tag] if tag in compiled else self.raw_table(font, identity, tag)
            # SFNTWriter fixes table checksums, offsets and head checksum adjustment.
            writer.close()
        self.direct_writes += 1

    def stats(self):
        return {'rawTableReads': self.table_reads, 'rawTableCacheHits': self.table_hits,
                'cmapCacheHits': self.cmap_hits, 'directMetricsWrites': self.direct_writes,
                'rawTableCacheBudgetBytes': self.budget}
