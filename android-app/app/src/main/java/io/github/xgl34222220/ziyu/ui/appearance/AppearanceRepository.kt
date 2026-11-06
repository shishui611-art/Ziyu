package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.xgl34222220.ziyu.shouldRequireSetup
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val appearanceBackgroundMutex = kotlinx.coroutines.sync.Mutex()

class AppearanceRepository(private val context: Context) {
    // Package timestamps survive normal app upgrades even when a user has never changed
    // appearance settings, so those existing installs must not be sent through first-run setup.
    private val backgroundMutex = appearanceBackgroundMutex
    private val isUpgradeInstall = context.isUpgradeInstall()

    private object Keys {
        val uiStyle = stringPreferencesKey("ui_style")
        val themeMode = stringPreferencesKey("theme_mode")
        val setupCompleted = booleanPreferencesKey("setup_completed")
        val seedArgb = intPreferencesKey("theme_seed_argb")
        val kolorStyle = stringPreferencesKey("theme_kolor_style")
        val colorStandard = stringPreferencesKey("theme_color_standard")
        val monetEnabled = booleanPreferencesKey("theme_monet_enabled")
        val nativeMonetEnabled = booleanPreferencesKey("theme_native_monet_enabled")
        val amoledBlack = booleanPreferencesKey("theme_amoled_black")
        val blurEnabled = booleanPreferencesKey("theme_blur_enabled")
        val glassEnabled = booleanPreferencesKey("theme_glass_enabled")
        val floatingDock = booleanPreferencesKey("theme_floating_dock")
        val backgroundPath = stringPreferencesKey("background_path")
        val cardTransparency = intPreferencesKey("card_transparency")
        val highRefreshRate = booleanPreferencesKey("display_high_refresh_rate")
        val predictiveBack = booleanPreferencesKey("predictive_back")
        val backgroundBlur = intPreferencesKey("background_blur")
        val backgroundScrim = intPreferencesKey("background_scrim")
        val backgroundScrimIsSigned = booleanPreferencesKey("background_scrim_signed")
        val overrides = stringPreferencesKey("appearance_overrides")
        val adaptive = stringPreferencesKey("adaptive_appearance")
        val customTexts = stringPreferencesKey("theme_custom_texts")
        val homeCard = stringPreferencesKey("home_card_decoration")
        val themeName = stringPreferencesKey("theme_name")
        val themeAuthor = stringPreferencesKey("theme_author")
        val themeLicense = stringPreferencesKey("theme_license")
    }

    val settings: Flow<AppearanceSettings> = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::readSettings)

    private fun readSettings(preferences: Preferences): AppearanceSettings = AppearanceSettings(
                uiStyle = UiStyle.fromStorage(preferences[Keys.uiStyle]),
                themeMode = ThemeMode.fromStorage(preferences[Keys.themeMode] ?: ThemeMode.SYSTEM.storageValue),
                seedArgb = preferences[Keys.seedArgb] ?: AccentOptions.first().argb,
                kolorStyle = KolorStyle.fromStorage(preferences[Keys.kolorStyle]),
                colorStandard = ColorStandard.fromStorage(preferences[Keys.colorStandard]),
                monetEnabled = preferences[Keys.monetEnabled] ?: true,
                nativeMonetEnabled = preferences[Keys.nativeMonetEnabled] ?: true,
                amoledBlack = preferences[Keys.amoledBlack] ?: false,
                blurEnabled = preferences[Keys.blurEnabled] ?: true,
                glassEnabled = preferences[Keys.glassEnabled] ?: true,
                floatingDock = preferences[Keys.floatingDock] ?: true,
                highRefreshRate = preferences[Keys.highRefreshRate] ?: false,
                predictiveBack = preferences[Keys.predictiveBack] ?: true,
                backgroundPath = preferences[Keys.backgroundPath].orEmpty(),
                cardTransparency = preferences[Keys.cardTransparency] ?: 0,
                backgroundBlur = preferences[Keys.backgroundBlur] ?: 0,
                backgroundScrim = preferences[Keys.backgroundScrim] ?: 64,
                backgroundScrimIsSigned = preferences[Keys.backgroundScrimIsSigned] ?: false,
                overrides = runCatching { AppearanceOverrides.parse(preferences[Keys.overrides].orEmpty()) }.getOrDefault(AppearanceOverrides()),
                adaptive = runCatching { ThemeSettingsCodec.adaptiveFromJson(preferences[Keys.adaptive].orEmpty()) }.getOrNull(),
                customTexts = runCatching { ThemeTexts.parse(org.json.JSONObject(preferences[Keys.customTexts] ?: "{}")) }.getOrDefault(emptyMap()),
                homeCard = runCatching { HomeCardDecoration.parse(org.json.JSONObject(preferences[Keys.homeCard] ?: "{}")) }.getOrDefault(HomeCardDecoration()),
                themeName = preferences[Keys.themeName].orEmpty(),
                themeAuthor = preferences[Keys.themeAuthor].orEmpty(),
                themeLicense = preferences[Keys.themeLicense].orEmpty(),
            ).normalized()

    val setupRequired: Flow<Boolean> = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val hadLegacyPreferences = preferences.asMap().keys.any { it != Keys.setupCompleted }
            shouldRequireSetup(
                storedSetupCompleted = preferences[Keys.setupCompleted],
                hasLegacyPreferences = hadLegacyPreferences,
                isUpgradeInstall = isUpgradeInstall,
            )
        }

    suspend fun setUiStyle(value: UiStyle) = edit(true) { it[Keys.uiStyle] = value.name }
    suspend fun setThemeMode(value: ThemeMode) = edit(true) { it[Keys.themeMode] = value.storageValue }
    suspend fun setSetupCompleted() = edit(true) { it[Keys.setupCompleted] = true }
    suspend fun setSeedArgb(value: Int) = edit {
        it[Keys.seedArgb] = accentOptionFor(value).argb
        it[Keys.monetEnabled] = false
        it[Keys.nativeMonetEnabled] = true
    }
    suspend fun setThemeDisplay(mode: ThemeMode, black: Boolean) = edit(true) {
        it[Keys.themeMode] = mode.storageValue
        it[Keys.amoledBlack] = black
        if (mode == ThemeMode.DARK) readSettings(it).adaptive?.let { adaptive ->
            it[Keys.adaptive] = ThemeSettingsCodec.adaptiveToJson(adaptive.copy(dark = adaptive.dark.copy(amoledBlack = black))).toString()
        }
    }
    suspend fun setPredictiveBack(enabled: Boolean) = edit(true) { it[Keys.predictiveBack] = enabled }
    suspend fun setCardTransparency(value: Int) = edit { it[Keys.cardTransparency] = value.coerceIn(0, 100) }
    suspend fun setKolorStyle(value: KolorStyle) = edit { it[Keys.kolorStyle] = value.name }
    suspend fun setColorStandard(value: ColorStandard) = edit { it[Keys.colorStandard] = value.name }
    suspend fun setMonetEnabled(enabled: Boolean) = edit {
        it[Keys.monetEnabled] = enabled
        it[Keys.nativeMonetEnabled] = true
    }
    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.amoledBlack] = enabled }
    suspend fun setBlurEnabled(enabled: Boolean) = edit(true) { preferences ->
        preferences[Keys.blurEnabled] = enabled
    }

    suspend fun setGlassEnabled(enabled: Boolean) {
        edit(true) { preferences ->
            preferences[Keys.glassEnabled] = enabled
        }
    }

    suspend fun setFloatingDock(enabled: Boolean) = edit(true) { it[Keys.floatingDock] = enabled }
    suspend fun setHighRefreshRate(enabled: Boolean) = edit(true) { it[Keys.highRefreshRate] = enabled }
    suspend fun setOverrides(value: AppearanceOverrides) = edit(true) { it[Keys.overrides] = value.toJson().toString() }
    suspend fun setBackgroundEffects(value: BackgroundEffects, blur: Int, scrim: Int) = edit {
        it[Keys.overrides] = readSettings(it).overrides.copy(effects = value).toJson().toString()
        it[Keys.backgroundBlur] = blur.coerceIn(0, 100)
        it[Keys.backgroundScrim] = scrim.coerceIn(if (readSettings(it).backgroundScrimIsSigned) -100 else 0, 100)
    }
    suspend fun setCustomTexts(value: Map<String, String>) = edit(true) {
        it[Keys.customTexts] = ThemeTexts.json(ThemeTexts.parse(ThemeTexts.json(value))).toString()
    }

    suspend fun setHomeCards(light: HomeCardDecoration, dark: HomeCardDecoration?) {
        backgroundMutex.lock()
        val created = mutableListOf<java.io.File>()
        var committed = false
        try {
            val cards = listOfNotNull(light, dark).map { HomeCardDecoration.parse(it.toJson()) }
            val owned = java.io.File(context.filesDir, "backgrounds").canonicalFile.apply { mkdirs() }
            val pending = java.io.File(context.cacheDir, "themes").canonicalFile
            val paths = mutableMapOf<String, String>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cards.flatMap { it.imagePaths() }.filter { it.isNotBlank() }.distinct().forEach { path ->
                    val file = java.io.File(path).canonicalFile
                    require(file.isFile && file.extension == "png" && file.length() in 1..(32L*1024*1024)) { "卡片图片不可用，请重新选择" }
                    if (file.parentFile == owned) paths[path] = file.absolutePath
                    else {
                        require(file.path.startsWith(pending.path + java.io.File.separator)) { "卡片图片位置无效" }
                        val target = java.io.File(owned, "background-${java.util.UUID.randomUUID()}.png")
                        created.add(target); file.copyTo(target)
                        require(file.length() == target.length()) { "卡片图片复制失败" }
                        paths[path] = target.absolutePath
                    }
                }
            }
            // Commit and ownership transfer must finish together, including on editor cancellation.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                edit(true) { p ->
                    val current = readSettings(p)
                    val adaptive = current.adaptive
                    require((adaptive != null) == (dark != null)) { "主题已变化，请重新打开卡片设置" }
                    if (adaptive == null) p[Keys.homeCard] = cards.first().remap(paths).toJson().toString()
                    else p[Keys.adaptive] = ThemeSettingsCodec.adaptiveToJson(adaptive.copy(
                        light = adaptive.light.copy(homeCard = cards[0].remap(paths)), dark = adaptive.dark.copy(homeCard = cards[1].remap(paths)))).toString()
                }
                committed = true
            }
            runCatching { cleanupBackgrounds() }
        } finally {
            if (!committed) created.forEach { it.delete() }
            backgroundMutex.unlock()
        }
    }

    suspend fun applyTheme(value: AppearanceSettings, includeTexts: Boolean = true) {
        backgroundMutex.lock()
        val created = mutableListOf<java.io.File>()
        var committed = false
        try {
            val promoted = mutableMapOf<String, String>()
            suspend fun promote(path: String): String {
                if (path.isBlank()) return ""
                return promoted[path] ?: run {
                    val file = java.io.File(path).canonicalFile
                    val pending = java.io.File(context.cacheDir, "themes").canonicalFile
                    require(file.isFile && file.path.startsWith(pending.path + java.io.File.separator)) { "主题背景文件不可用" }
                    require(file.extension == "png" && file.length() in 1..(32L * 1024 * 1024)) { "主题背景无效" }
                    val directory = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
                    val target = java.io.File(directory, "background-${java.util.UUID.randomUUID()}.png")
                    created.add(target)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        file.copyTo(target)
                        require(file.length() == target.length()) { "主题背景复制失败" }
                    }
                    promoted[path] = target.absolutePath; target.absolutePath
                }
            }
            value.allImagePaths().filter { it.isNotBlank() }.distinct().forEach { promote(it) }
            val target = value.copy(backgroundPath = promoted[value.backgroundPath].orEmpty(), homeCard = value.homeCard.remap(promoted),
                adaptive = value.adaptive?.let { AdaptiveAppearance(
                    it.light.copy(backgroundPath = promoted[it.light.backgroundPath].orEmpty(), homeCard = it.light.homeCard.remap(promoted)),
                    it.dark.copy(backgroundPath = promoted[it.dark.backgroundPath].orEmpty(), homeCard = it.dark.homeCard.remap(promoted))) })
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                context.appearanceDataStore.edit { p ->
                    val current = readSettings(p)
                    // Gestures and refresh rate remain the user's own device choices.
                    writeTheme(p, target.normalized().copy(highRefreshRate = current.highRefreshRate, predictiveBack = current.predictiveBack,
                        customTexts = if(includeTexts) target.customTexts else current.customTexts))
                }
                committed = true
            }
            runCatching { cleanupBackgrounds() }
        } finally {
            if (!committed) created.forEach { it.delete() }
            backgroundMutex.unlock()
        }
    }

    suspend fun resetTheme() {
        backgroundMutex.lock()
        try {
            context.appearanceDataStore.edit { preferences ->
                val current = readSettings(preferences)
                // Interface provider and device interaction preferences are independent of theme content.
                writeTheme(preferences, AppearanceSettings(
                    uiStyle = current.uiStyle,
                    highRefreshRate = current.highRefreshRate,
                    predictiveBack = current.predictiveBack,
                ))
            }
            // The preference transaction already committed; delayed image cleanup is best effort.
            runCatching { cleanupBackgrounds() }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }
        } finally {
            backgroundMutex.unlock()
        }
    }

    private fun writeTheme(p: MutablePreferences, s: AppearanceSettings) {
        p[Keys.uiStyle] = s.uiStyle.name; p[Keys.themeMode] = s.themeMode.storageValue
        p[Keys.seedArgb] = s.seedArgb; p[Keys.kolorStyle] = s.kolorStyle.name
        p[Keys.colorStandard] = s.colorStandard.name; p[Keys.monetEnabled] = s.monetEnabled
        p[Keys.nativeMonetEnabled] = s.nativeMonetEnabled
        p[Keys.amoledBlack] = s.amoledBlack; p[Keys.blurEnabled] = s.blurEnabled
        p[Keys.glassEnabled] = s.glassEnabled; p[Keys.floatingDock] = s.floatingDock
        p[Keys.backgroundPath] = s.backgroundPath; p[Keys.cardTransparency] = s.cardTransparency
        p[Keys.backgroundBlur] = s.backgroundBlur; p[Keys.backgroundScrim] = s.backgroundScrim
        p[Keys.backgroundScrimIsSigned] = s.backgroundScrimIsSigned
        p[Keys.overrides] = s.overrides.toJson().toString()
        p[Keys.customTexts] = ThemeTexts.json(s.customTexts).toString()
        p[Keys.homeCard] = s.homeCard.toJson().toString()
        p[Keys.adaptive] = s.adaptive?.let { ThemeSettingsCodec.adaptiveToJson(it).toString() }.orEmpty()
        p[Keys.themeName] = s.themeName; p[Keys.themeAuthor] = s.themeAuthor; p[Keys.themeLicense] = s.themeLicense
    }

    private suspend fun cleanupBackgrounds() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val s = readSettings(context.appearanceDataStore.data.first())
        val keep = s.allImagePaths().toSet()
        java.io.File(context.filesDir, "backgrounds").listFiles()?.filter {
            it.name.startsWith("background-") && it.absolutePath !in keep &&
                System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L
        }?.forEach { it.delete() }
    }

    suspend fun setBackgroundPath(path: String) {
        backgroundMutex.lock()
        try {
        edit { it[Keys.backgroundPath] = path }
        cleanupBackgrounds()
        } finally { backgroundMutex.unlock() }
    }

    private suspend inline fun edit(keepAdaptive: Boolean = false, crossinline block: (MutablePreferences) -> Unit) {
        context.appearanceDataStore.edit { preferences ->
            val current = readSettings(preferences)
            if (!keepAdaptive && current.adaptive != null) {
                val systemDark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val dark = when (current.themeMode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
                writeTheme(preferences, current.forDark(dark).copy(adaptive = null))
            }
            block(preferences)
        }
    }
}

@Suppress("DEPRECATION")
private fun Context.isUpgradeInstall(): Boolean = runCatching {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    packageInfo.lastUpdateTime > packageInfo.firstInstallTime
}.getOrDefault(false)
