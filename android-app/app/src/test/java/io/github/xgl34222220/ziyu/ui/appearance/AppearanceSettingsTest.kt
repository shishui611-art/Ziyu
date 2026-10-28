package io.github.xgl34222220.ziyu.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun newInstallFollowsSystemThemeByDefault() {
        assertEquals(ThemeMode.SYSTEM, AppearanceSettings().themeMode)
    }

    @Test
    fun uiStyleStorageKeepsExistingColorOsStyleSettings() {
        assertEquals(3, UiStyle.entries.size)
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStorage("material"))
        assertEquals("Miuix", UiStyle.MIUIX.label)
        assertEquals("COUI", UiStyle.COUI.label)
        assertEquals(UiStyle.MIUIX, UiStyle.fromStorage("MIUIX"))
        assertEquals(UiStyle.COUI, UiStyle.fromStorage("COUI"))
        assertEquals(UiStyle.MIUIX, UiStyle.fromStorage("unknown"))
        assertEquals(UiStyle.MIUIX, UiStyle.fromStorage(null))
    }

    @Test
    fun themeModeStorageSupportsAllThreeModes() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("invalid"))
    }

    @Test
    fun storedKolorNamesPreserveDistinctPalettes() {
        assertEquals(KolorStyle.SOFT, KolorStyle.fromStorage(null))
        for (style in KolorStyle.entries) {
            assertEquals(style, KolorStyle.fromStorage(style.name.lowercase()))
            assertEquals(style, KolorStyle.fromStorage(style.paletteName))
        }
    }

    @Test
    fun invalidAccentFallsBackToZiyuBlue() {
        val normalized = AppearanceSettings(seedArgb = 0x12345678).normalized()
        assertEquals(AccentOptions.first().argb, normalized.seedArgb)
        assertEquals("ziyu", normalized.accent.id)
        assertFalse(normalized.highRefreshRate)
    }

    @Test
    fun blurPreferenceIsPreservedWhenGlassIsDisabled() {
        val normalized = AppearanceSettings(
            glassEnabled = false,
            blurEnabled = true,
        ).normalized()

        assertFalse(normalized.glassEnabled)
        assertTrue(normalized.blurEnabled)
        assertTrue(normalized.copy(glassEnabled = true).normalized().blurEnabled)
    }

    @Test
    fun validMaterialSettingsArePreserved() {
        val source = AppearanceSettings(
            uiStyle = UiStyle.MATERIAL,
            themeMode = ThemeMode.DARK,
            seedArgb = AccentOptions.last().argb,
            kolorStyle = KolorStyle.NEUTRAL,
            monetEnabled = false,
            amoledBlack = true,
            glassEnabled = true,
            blurEnabled = true,
            floatingDock = false,
        )

        val normalized = source.normalized()
        assertEquals(source, normalized)
        assertTrue(normalized.blurEnabled)
    }
}
