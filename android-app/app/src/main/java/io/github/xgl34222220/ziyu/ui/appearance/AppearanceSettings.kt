package io.github.xgl34222220.ziyu.ui.appearance

import androidx.compose.runtime.Immutable

enum class UiStyle(val label: String) {
    MATERIAL("Material"),
    MIUIX("Miuix"),
    COUI("COUI");

    companion object {
        fun fromStorage(value: String?): UiStyle = when (value?.trim()?.uppercase()) {
            "MATERIAL" -> MATERIAL
            "COUI" -> COUI
            "MIUIX" -> MIUIX
            else -> MIUIX
        }
    }
}

enum class ThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

enum class KolorStyle(val label: String, val paletteName: String) {
    SOFT("柔和", "TonalSpot"),
    VIBRANT("鲜艳", "Vibrant"),
    NEUTRAL("中性", "Neutral"),
    EXPRESSIVE("表现力", "Expressive"),
    RAINBOW("彩虹", "Rainbow"),
    FRUIT_SALAD("缤纷", "FruitSalad"),
    MONOCHROME("单色", "Monochrome"),
    FIDELITY("忠实", "Fidelity"),
    CONTENT("内容", "Content");

    val supportsSpec2025: Boolean
        get() = this in setOf(SOFT, VIBRANT, NEUTRAL, EXPRESSIVE)

    companion object {
        fun fromStorage(value: String?): KolorStyle = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.paletteName.equals(value, ignoreCase = true)
        } ?: SOFT
    }
}

enum class ColorStandard(val label: String) {
    SPEC_2025("新版 2025"), SPEC_2021("经典 2021");

    companion object {
        fun fromStorage(value: String?): ColorStandard = entries.firstOrNull { it.name == value } ?: SPEC_2025
    }
}

@Immutable
data class AccentOption(
    val id: String,
    val label: String,
    val argb: Int,
)

val AccentOptions = listOf(
    AccentOption("ziyu", "字域蓝", 0xFF2A62F6.toInt()),
    AccentOption("purple", "曜紫", 0xFF7857D8.toInt()),
    AccentOption("cyan", "青蓝", 0xFF0087A8.toInt()),
    AccentOption("green", "青玉", 0xFF16856B.toInt()),
    AccentOption("red", "朱红", 0xFFC43B43.toInt()),
    AccentOption("pink", "玫粉", 0xFFB83F78.toInt()),
    // SukiSU-Ultra's palette, with existing ziyu accents kept for saved preferences.
    AccentOption("sakura", "樱花", 0xFFFF9CA8.toInt()),
    AccentOption("suki_red", "赤红", 0xFFF44336.toInt()),
    AccentOption("suki_pink", "桃粉", 0xFFE91E63.toInt()),
    AccentOption("suki_purple", "紫晶", 0xFF9C27B0.toInt()),
    AccentOption("deep_purple", "深紫", 0xFF673AB7.toInt()),
    AccentOption("indigo", "靛蓝", 0xFF3F51B5.toInt()),
    AccentOption("blue", "晴蓝", 0xFF2196F3.toInt()),
    AccentOption("aqua", "水青", 0xFF00BCD4.toInt()),
    AccentOption("teal", "湖绿", 0xFF009688.toInt()),
    AccentOption("leaf", "叶绿", 0xFF4FAF50.toInt()),
    AccentOption("yellow", "明黄", 0xFFFFEB3B.toInt()),
    AccentOption("amber", "琥珀", 0xFFFFC107.toInt()),
    AccentOption("orange", "橘色", 0xFFFF9800.toInt()),
    AccentOption("brown", "暖棕", 0xFF795548.toInt()),
    AccentOption("blue_grey", "蓝灰", 0xFF607D8F.toInt()),
)

fun accentOptionFor(argb: Int): AccentOption =
    AccentOptions.firstOrNull { it.argb == argb } ?: AccentOptions.first()

@Immutable
data class AppearanceSettings(
    val uiStyle: UiStyle = UiStyle.MIUIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedArgb: Int = AccentOptions.first().argb,
    val kolorStyle: KolorStyle = KolorStyle.VIBRANT,
    val colorStandard: ColorStandard = ColorStandard.SPEC_2025,
    val monetEnabled: Boolean = true,
    val amoledBlack: Boolean = false,
    val blurEnabled: Boolean = true,
    val glassEnabled: Boolean = true,
    val floatingDock: Boolean = true,
    val highRefreshRate: Boolean = false,
    val predictiveBack: Boolean = true,
    val backgroundPath: String = "",
) {
    val accent: AccentOption
        get() = accentOptionFor(seedArgb)

    /** Repairs stale or manually edited preferences before they reach Compose. */
    fun normalized(): AppearanceSettings = copy(
        seedArgb = accentOptionFor(seedArgb).argb,
    )
}
