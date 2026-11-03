package io.github.xgl34222220.ziyu

import org.junit.Assert.*
import org.junit.Test

class FontComparisonPolicyTest {
    private fun font(id: String) = FontItem(id, id, "TTF", "1 MB", "1", false, true, "", emptyList())
    private val old = font("mounted")
    private val next = font("selected")
    @Test fun pendingSelectionIsNotMistakenForEffectiveFont() {
        val snapshot = ModuleSnapshot(activeFont = next.id, effectiveFont = old.id, fontEffectState = "verified")
        assertEquals(old, comparisonSource(snapshot, listOf(old, next), next))
    }
    @Test fun noComparisonWithoutVerifiedCustomSource() {
        for ((effect, id) in listOf("unknown" to old.id, "pending-reboot" to old.id, "system" to "default", "failed" to old.id))
            assertNull(comparisonSource(ModuleSnapshot(effectiveFont=id, fontEffectState=effect), listOf(old,next), next))
    }
    @Test fun sameFontAndRollbackSkipComparison() {
        assertNull(comparisonSource(ModuleSnapshot(effectiveFont=next.id, fontEffectState="live"), listOf(old,next), next))
        assertNull(comparisonSource(ModuleSnapshot(effectiveFont=old.id, fontEffectState="verified", rollbackPending=true), listOf(old,next), next))
    }
    @Test fun deletedLibrarySourceDoesNotHideMountedComparison() {
        assertEquals(old.id, comparisonSource(ModuleSnapshot(effectiveFont=old.id, fontEffectState="verified"), emptyList(), next)?.id)
    }
}
