/* Theme text keys and sticker geometry follow SukiSU-Ultra 075ac81f.
 * GPL-3.0-or-later; Ziyu adaptation GPL-3.0-only. See THIRD_PARTY_NOTICES.md. */
package io.github.xgl34222220.ziyu.ui.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** Only cosmetic labels are editable. Live values, errors and confirmation text stay authoritative. */
enum class ThemeTextKey(val key: String, val label: String, val default: String, val group: String, val limit: Int = 32) {
    HOME_TITLE("home.title", "首页标题", "字域", "首页"),
    FONT_LABEL("home.current_font", "字体标题", "当前字体", "首页"),
    SAMPLE("home.sample", "卡片大标题", "字里见山河", "首页", 120),
    STATUS_WORKING("home.status_working", "卡片大标题", "字里见山河", "首页", 120),
    STAT_FONT("home.stats_superuser", "当前字体卡片标题", "当前字体", "首页"),
    STAT_LIBRARY("home.stats_module", "字体库卡片标题", "字体库", "首页"),
    SHORT_SAMPLE("home.short_sample", "简短预览", "字里见山河", "首页", 60),
    LATIN_SAMPLE("home.sample_secondary", "卡片副标题", "", "首页", 80),
    DIGITS("home.digits", "数字预览", "0123456789", "首页", 40),
    LOGO("home.logo", "字母装饰", "Aa", "首页", 8),
    HOME("page.home", "首页", "首页", "页面"),
    LIBRARY("page.library", "字体库", "字体库", "页面"),
    STUDIO("page.studio", "组合", "组合", "页面"),
    MIX("page.mix", "字体组合", "字体组合", "页面"),
    TASK_CENTER("page.install", "字体刷写", "字体刷写", "页面"),
    SETTINGS("page.settings", "设置", "设置", "页面"),
    APPEARANCE("page.appearance", "外观与主题", "外观与主题", "设置"),
    STORE("page.theme_store", "主题商店", "主题商店", "设置"),
    TEXTS("page.custom_texts", "自定义文字", "自定义文字", "设置"),
    UI_MODE("settings.ui_mode", "界面风格", "界面风格", "设置"),
    STATUS("settings.status", "字域状态", "字域状态", "设置"),
    NOTIFICATIONS("settings.notifications", "通知与实时活动", "通知与实时活动", "设置"),
    SAFETY("settings.safety", "安全与维护", "安全与维护", "设置"),
    RULES("settings.font_rules", "保留原厂字体", "保留原厂字体", "设置"),
    CLIPPING("settings.clipping", "字体防裁切", "字体防裁切", "设置"),
    BOOT("settings.boot", "开机保护", "开机保护", "设置"),
    CHECK("settings.app_coverage", "检查应用字体", "检查应用字体", "设置"),
    GOOGLE("settings.google", "Google 字体兼容", "Google 字体兼容", "设置"),
    STORAGE("settings.storage", "文件与配置位置", "文件与配置位置", "设置"),
    BACKUP("settings.backup", "备份与恢复", "备份与恢复", "设置"),
    UPDATE("settings.update", "模块更新", "模块更新", "设置");
    companion object {
        private val byDefault = entries.associateBy { it.default }
        private val byKey = entries.associateBy { it.key }
        fun forDefault(value: String) = byDefault[value]
        fun forKey(value: String) = byKey[value]
    }
}

/** JSON null and old packages' literal "null" mean that no text was supplied. */
internal fun nonBlankThemeText(value: Any?): String? = (value as? String)?.trim()
    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

internal fun themeTextOverride(texts: Map<String, String>, key: ThemeTextKey): String? =
    nonBlankThemeText(texts[key.key]) ?: when (key) {
        ThemeTextKey.STATUS_WORKING -> nonBlankThemeText(texts[ThemeTextKey.SAMPLE.key])
        ThemeTextKey.STAT_FONT -> nonBlankThemeText(texts[ThemeTextKey.FONT_LABEL.key])
        else -> null
    }

internal fun resolveThemeText(texts: Map<String, String>, key: ThemeTextKey): String =
    themeTextOverride(texts, key) ?: key.default
@Composable
internal fun themeText(default: String): String = ThemeTextKey.forDefault(default)?.let {
    resolveThemeText(LocalAppearanceSettings.current.customTexts, it)
} ?: default

internal object ThemeTexts {
    fun parse(o: JSONObject?): Map<String, String> = buildMap {
        if (o == null) return@buildMap
        require(o.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "文字配置超过 16 KB" }
        ThemeTextKey.entries.forEach { key ->
            val value = nonBlankThemeText(o.opt(key.key)) ?: when (key) {
                ThemeTextKey.STATUS_WORKING -> nonBlankThemeText(o.opt(ThemeTextKey.SAMPLE.key))
                ThemeTextKey.STAT_FONT -> nonBlankThemeText(o.opt(ThemeTextKey.FONT_LABEL.key))
                else -> null
            }
            if (value != null) {
                require(value.length <= key.limit && value.none { it.code < 32 && it != '\n' }) { "${key.label}过长或包含无效字符" }
                put(key.key, value)
            }
        }
    }
    fun json(texts: Map<String, String>) = JSONObject().apply { texts.forEach { (k,v) -> put(k,v) } }
}

@Immutable
data class CardSticker(val path: String, val offsetX: Float = .30f, val offsetY: Float = -.28f,
                       val rotation: Float = -8f, val size: Float = .20f) {
    fun transformJson(): JSONObject = JSONObject().put("offsetX", offsetX).put("offsetY", offsetY).put("rotation", rotation).put("size", size)
    companion object {
        const val MAX_COUNT = 5
        fun parse(path: String, o: JSONObject?): CardSticker {
            fun n(key: String, default: Float, min: Float, max: Float): Float = (o?.optDouble(key, default.toDouble())?.toFloat() ?: default).also {
                require(it.isFinite() && it in min..max) { "贴纸位置无效：$key" }
            }
            return CardSticker(path, n("offsetX", .30f, -.5f, .5f), n("offsetY", -.28f, -.5f, .5f),
                n("rotation", -8f, -180f, 180f), n("size", .20f, .12f, .35f))
        }
    }
}

@Immutable
data class HomeCardDecoration(val backgroundPath: String = "", val scrim: Int = 35, val blur: Int = 0,
    val imageAlpha: Int = 100, val zoom: Int = 100, val offsetX: Int = 0, val offsetY: Int = 0,
    val hideSample: Boolean = false, val hideLabel: Boolean = false, val stickers: List<CardSticker> = emptyList()) {
    fun imagePaths(): List<String> = listOf(backgroundPath) + stickers.map { it.path }
    fun remap(paths: Map<String, String>) = copy(backgroundPath = paths[backgroundPath].orEmpty(),
        stickers = stickers.map { it.copy(path = paths[it.path].orEmpty()) })
    fun toJson(paths: Boolean = true): JSONObject = JSONObject().put("scrim", scrim).put("blur", blur).put("imageAlpha", imageAlpha)
        .put("zoom", zoom).put("offsetX", offsetX).put("offsetY", offsetY).put("hideSample", hideSample).put("hideLabel", hideLabel)
        .apply { if (paths) put("backgroundPath", backgroundPath) }
        .put("stickers", JSONArray().apply { stickers.forEach { s -> put(s.transformJson().apply { if (paths) put("path", s.path) }) } })
    companion object {
        fun parse(o: JSONObject?, paths: Boolean = true): HomeCardDecoration {
            if (o == null) return HomeCardDecoration()
            fun n(k: String, d: Int, lo: Int = 0, hi: Int = 100) = o.optInt(k,d).also { require(it in lo..hi) { "卡片数值无效：$k" } }
            val list = o.optJSONArray("stickers") ?: JSONArray()
            require(list.length() <= CardSticker.MAX_COUNT) { "最多添加 5 张贴纸" }
            return HomeCardDecoration(if(paths)o.optString("backgroundPath") else "", n("scrim",35), n("blur",0), n("imageAlpha",100),
                n("zoom",100,100,300), n("offsetX",0,-100,100), n("offsetY",0,-100,100), o.optBoolean("hideSample"), o.optBoolean("hideLabel"),
                List(list.length()) { i -> val s=list.getJSONObject(i); CardSticker.parse(if(paths)s.optString("path") else "",s) })
        }
    }
}

internal fun AppearanceSettings.allImagePaths(): List<String> = listOf(backgroundPath) + homeCard.imagePaths() +
    adaptive?.let { listOf(it.light.backgroundPath, it.dark.backgroundPath) + it.light.homeCard.imagePaths() + it.dark.homeCard.imagePaths() }.orEmpty()
