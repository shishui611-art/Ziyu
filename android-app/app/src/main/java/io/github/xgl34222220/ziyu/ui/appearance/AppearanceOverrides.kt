/* Semantic overlay and light/dark merge adapted from SukiSU-Ultra
 * 075ac81f1dfe58b3f59c665a1b07fda8b975015f, GPL-3.0-or-later.
 * Ziyu adaptation: org.json boundary, immutable values and native theme bridges.
 * See THIRD_PARTY_NOTICES.md and licenses/SukiSU-Ultra-GPL-3.0.txt.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class AppearanceColorRole(val key: String, val label: String) {
    ACCENT("accent", "主题色"), ON_ACCENT("onAccent", "按钮文字"),
    TEXT("textPrimary", "主要文字"), SECONDARY("textSecondary", "辅助文字"),
    DISABLED("textDisabled", "停用文字"), SURFACE("surface", "页面背景"),
    CARD("card", "卡片背景"), OUTLINE("outline", "分隔线"), ERROR("error", "错误提示"),
}

enum class AppearanceColorMode(val key: String, val label: String) {
    SHARED("shared", "通用"), LIGHT("light", "浅色"), DARK("dark", "深色"),
}

@Immutable
data class BackgroundEffects(
    val sharpen: Float = 0f, val saturation: Float = 100f,
    val contrast: Float = 100f, val brightness: Float = 0f,
)

@Immutable
data class AppearanceOverrides(
    val shared: Map<String, Int> = emptyMap(),
    val light: Map<String, Int> = emptyMap(),
    val dark: Map<String, Int> = emptyMap(),
    val effects: BackgroundEffects = BackgroundEffects(),
) {
    fun colors(isDark: Boolean): Map<String, Int> = shared + if (isDark) dark else light
    fun section(mode: AppearanceColorMode): Map<String, Int> = when (mode) {
        AppearanceColorMode.SHARED -> shared; AppearanceColorMode.LIGHT -> light; AppearanceColorMode.DARK -> dark
    }
    fun withColor(mode: AppearanceColorMode, key: String, color: Int?): AppearanceOverrides {
        require(AppearanceColorRole.entries.any { it.key == key })
        val next = if (color == null) section(mode) - key else section(mode) + (key to color)
        return when (mode) {
            AppearanceColorMode.SHARED -> copy(shared = next)
            AppearanceColorMode.LIGHT -> copy(light = next)
            AppearanceColorMode.DARK -> copy(dark = next)
        }
    }
    fun toJson(): JSONObject {
        fun colors(values: Map<String, Int>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, colorHex(value)) }
        }
        return JSONObject().put("schema", 1).put("colors", JSONObject()
            .put("shared", colors(shared)).put("light", colors(light)).put("dark", colors(dark)))
            .put("image", JSONObject().put("background", JSONObject()
                .put("sharpen", effects.sharpen).put("saturation", effects.saturation)
                .put("contrast", effects.contrast).put("brightness", effects.brightness)))
    }
    companion object {
        const val MAX_BYTES = 16 * 1024
        fun parse(raw: String): AppearanceOverrides {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配色配置超过 16 KB" }
            return if (raw.isBlank()) AppearanceOverrides() else parse(JSONObject(raw))
        }
        fun parse(value: JSONObject?): AppearanceOverrides {
            if (value == null) return AppearanceOverrides()
            require(value.optInt("schema", 1) == 1) { "不支持此配色版本" }
            val colors = value.optJSONObject("colors")
            fun section(key: String): Map<String, Int> = buildMap {
                val obj = colors?.optJSONObject(key) ?: return@buildMap
                AppearanceColorRole.entries.forEach { role ->
                    if (obj.has(role.key) && !obj.isNull(role.key)) {
                        put(role.key, parseColorHex(obj.getString(role.key))
                            ?: error("${role.label}的颜色无效"))
                    }
                }
            }
            val image = value.optJSONObject("image")?.optJSONObject("background")
            fun number(key: String, default: Float, min: Float, max: Float): Float {
                if (image == null || !image.has(key) || image.isNull(key)) return default
                val n = image.getDouble(key).toFloat()
                require(n.isFinite() && n in min..max) { "背景效果数值超出范围：$key" }
                return n
            }
            return AppearanceOverrides(section("shared"), section("light"), section("dark"),
                BackgroundEffects(number("sharpen", 0f, 0f, 100f), number("saturation", 100f, 20f, 200f),
                    number("contrast", 100f, 20f, 200f), number("brightness", 0f, -100f, 100f)))
        }
    }
}

fun parseColorHex(raw: String): Int? {
    val text = raw.trim().removePrefix("#")
    if (text.length !in listOf(6, 8) || text.any { it.digitToIntOrNull(16) == null }) return null
    return (if (text.length == 6) "FF$text" else text).toLongOrNull(16)?.toInt()
}

fun colorHex(value: Int): String = String.format(java.util.Locale.ROOT, "#%08X", value)

@Immutable
data class AppearanceVariant(
    val seedArgb: Int, val monetEnabled: Boolean, val amoledBlack: Boolean,
    val kolorStyle: KolorStyle, val colorStandard: ColorStandard,
    val cardTransparency: Int, val backgroundPath: String,
    val backgroundBlur: Int = 0, val backgroundScrim: Int = 64,
    val homeCard: HomeCardDecoration = HomeCardDecoration(),
    val nativeMonetEnabled: Boolean = true,
    val uiStyle: UiStyle? = null,
    val backgroundScrimIsSigned: Boolean = false,
    val themeMode: ThemeMode? = null,
) {
    fun applyTo(settings: AppearanceSettings): AppearanceSettings = settings.copy(
        seedArgb = seedArgb, monetEnabled = monetEnabled, amoledBlack = amoledBlack,
        kolorStyle = kolorStyle, colorStandard = colorStandard, cardTransparency = cardTransparency,
        backgroundPath = backgroundPath, backgroundBlur = backgroundBlur, backgroundScrim = backgroundScrim,
        homeCard = homeCard, adaptive = null,
        nativeMonetEnabled = nativeMonetEnabled, uiStyle = uiStyle ?: settings.uiStyle,
        backgroundScrimIsSigned = backgroundScrimIsSigned,
        themeMode = if (settings.themeMode == ThemeMode.SYSTEM) themeMode ?: settings.themeMode else settings.themeMode,
    )
}

@Immutable
data class AdaptiveAppearance(val light: AppearanceVariant, val dark: AppearanceVariant)

fun AppearanceSettings.forDark(dark: Boolean): AppearanceSettings =
    adaptive?.let { (if (dark) it.dark else it.light).applyTo(this) } ?: this

fun AppearanceSettings.asVariant(): AppearanceVariant = AppearanceVariant(seedArgb, monetEnabled,
    amoledBlack, kolorStyle, colorStandard, cardTransparency, backgroundPath, backgroundBlur, backgroundScrim, homeCard,
    nativeMonetEnabled, uiStyle, backgroundScrimIsSigned, themeMode)
