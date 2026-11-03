package io.github.xgl34222220.ziyu

/** A pending selection is never used as the current effective font. */
internal fun comparisonSource(snapshot: ModuleSnapshot, fonts: List<FontItem>, target: FontItem): FontItem? {
    if (!target.valid || snapshot.rollbackPending || snapshot.fontEffectState !in setOf("verified", "partial", "live", "live-partial")) return null
    if (snapshot.effectiveFont in setOf("", "default", "unknown", target.id)) return null
    // Library rows supply a label only. The current side is exported from the
    // verified system slot, including when the original source was deleted.
    return fonts.firstOrNull { it.id == snapshot.effectiveFont }
        ?: FontItem(snapshot.effectiveFont, snapshot.effectiveFont, "TTF", "", "", false, true, "", emptyList())
}
