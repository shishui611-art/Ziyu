package io.github.xgl34222220.ziyu

/** A pending selection is never used as the current effective font. */
internal fun comparisonSource(snapshot: ModuleSnapshot, fonts: List<FontItem>, target: FontItem): FontItem? {
    if (!target.valid || snapshot.rollbackPending || snapshot.fontEffectState !in setOf("verified", "partial", "live", "live-partial")) return null
    if (snapshot.effectiveFont in setOf("", "default", "unknown", target.id)) return null
    return fonts.firstOrNull { it.id == snapshot.effectiveFont && it.valid }
}
