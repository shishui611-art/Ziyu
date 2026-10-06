package io.github.xgl34222220.ziyu

import org.junit.Assert.*
import org.junit.Test

class CombinationCompletionTest {
    @Test fun generationIsNotApplication() {
        assertFalse(CombinationCompletion.shouldRestart("prepared", true))
        assertFalse(CombinationCompletion.shouldRestart("cancelled", true))
        assertFalse(CombinationCompletion.shouldRestart("failed", true))
    }
    @Test fun onlyConfirmedSuccessfulApplyCanRestart() {
        assertFalse(CombinationCompletion.shouldRestart("success", false))
        assertTrue(CombinationCompletion.shouldRestart("success", true))
    }
    @Test fun promptNeedsARealPublishedLibraryResult() {
        assertFalse(CombinationCompletion.shouldPreview("success", "", "", "a"))
        assertFalse(CombinationCompletion.shouldPreview("success", "prepared", "font", "a", "a"))
        assertTrue(CombinationCompletion.shouldPreview("success", "prepared", "font", "a"))
    }
}
