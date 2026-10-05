package io.github.xgl34222220.ziyu.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZiyuVersionTest {
    @Test fun oldOffsetVersionIsNeverOfferedAsAnUpgrade() {
        assertFalse(isNewerZiyuVersion("v1.0.0", 80000, "1.1.0-debug", 11000))
    }
    @Test fun newerSemanticVersionAndSameVersionRevisionAreAccepted() {
        assertTrue(isNewerZiyuVersion("v1.1.1", 11001, "1.1.0-debug", 11000))
        assertTrue(isNewerZiyuVersion("v1.1.0", 11001, "1.1.0-debug", 11000))
        assertFalse(isNewerZiyuVersion("v1.1.0", 11000, "1.1.0-debug", 11000))
    }
    @Test fun invalidDisplayVersionDoesNotTriggerAnUpdate() {
        assertFalse(isNewerZiyuVersion("unknown", 99999, "1.1.0", 11000))
    }
}
