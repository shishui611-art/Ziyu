package io.github.xgl34222220.ziyu

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportPreviewWeightTest {
    @Test fun lateMetadataReplacesPlaceholderWithFontDefault() {
        assertEquals(400f, importPreviewDefaultWeight(null, null))
        assertEquals(450f, importPreviewDefaultWeight(450f, 100f..900f))
        assertEquals(600f, importPreviewDefaultWeight(600f, 500f..800f))
    }
    @Test fun invalidAndOutOfRangeDefaultsAreBounded() {
        assertEquals(400f, importPreviewDefaultWeight(Float.NaN, 100f..900f))
        assertEquals(900f, importPreviewDefaultWeight(1200f, 100f..900f))
        assertEquals(100f, importPreviewDefaultWeight(-10f, 100f..900f))
    }
}
