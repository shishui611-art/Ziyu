package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.xgl34222220.ziyu.shouldRequireSetup
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val appearanceBackgroundMutex = kotlinx.coroutines.sync.Mutex()

class AppearanceRepository(private val context: Context) {
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
        val appIconId = stringPreferencesKey("app_icon_id")
        val appIconDynamicColor = booleanPreferencesKey("app_icon_dynamic_color")
        val bottomBarIcons = stringPreferencesKey("bottom_bar_icons")
        val dynamicWallpaperPath = stringPreferencesKey("dynamic_wallpaper_path")
        val dynamicWallpaperTransform = stringPreferencesKey("dynamic_wallpaper_transform")
        val dynamicHomeCardPath = stringPreferencesKey("dynamic_home_card_path")
        val dynamicHomeCardTransform = stringPreferencesKey("dynamic_home_card_transform")
        val dynamicWallpaperMuted = booleanPreferencesKey("dynamic_wallpaper_muted")
        val dynamicWallpaperFps = intPreferencesKey("dynamic_wallpaper_fps")
        val dynamicWallpaperSpeed = floatPreferencesKey("dynamic_wallpaper_speed")
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
        customTexts = runCatching { ThemeTexts.parse(JSONObject(preferences[Keys.customTexts] ?: "{}")) }.getOrDefault(emptyMap()),
        homeCard = runCatching { HomeCardDecoration.parse(JSONObject(preferences[Keys.homeCard] ?: "{}")) }.getOrDefault(HomeCardDecoration()),
        themeName = preferences[Keys.themeName].orEmpty(),
        themeAuthor = preferences[Keys.themeAuthor].orEmpty(),
        themeLicense = preferences[Keys.themeLicense].orEmpty(),
        appIconId = preferences[Keys.appIconId]?.let { BuiltinAppIcons.find(it).id } ?: BuiltinAppIcons.DEFAULT_ID,
        appIconDynamicColor = preferences[Keys.appIconDynamicColor] ?: false,
        bottomBarIcons = runCatching { parseBottomBarIconMap(JSONObject(preferences[Keys.bottomBarIcons] ?: "{}")) }.getOrDefault(emptyMap()),
        dynamicWallpaperPath = preferences[Keys.dynamicWallpaperPath].orEmpty(),
        dynamicWallpaperTransform = runCatching {
            DynamicWallpaperTransform.parse(preferences[Keys.dynamicWallpaperTransform]?.takeIf { it.isNotBlank() }?.let(::JSONObject))
        }.getOrDefault(DynamicWallpaperTransform()),
        dynamicHomeCardPath = preferences[Keys.dynamicHomeCardPath].orEmpty(),
        dynamicHomeCardTransform = runCatching {
            DynamicWallpaperTransform.parse(preferences[Keys.dynamicHomeCardTransform]?.takeIf { it.isNotBlank() }?.let(::JSONObject))
        }.getOrDefault(DynamicWallpaperTransform()),
        dynamicWallpaperMuted = preferences[Keys.dynamicWallpaperMuted] ?: true,
        dynamicWallpaperFps = preferences[Keys.dynamicWallpaperFps] ?: 0,
        dynamicWallpaperSpeed = preferences[Keys.dynamicWallpaperSpeed] ?: 1f,
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

    suspend fun currentSettings(): AppearanceSettings = readSettings(context.appearanceDataStore.data.first())

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
    suspend fun setBlurEnabled(enabled: Boolean) = edit(true) { it[Keys.blurEnabled] = enabled }
    suspend fun setGlassEnabled(enabled: Boolean) = edit(true) { it[Keys.glassEnabled] = enabled }
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

    suspend fun setAppIcon(iconId: String, dynamicColor: Boolean) {
        val icon = BuiltinAppIcons.find(iconId)
        edit(true) {
            it[Keys.appIconId] = icon.id
            it[Keys.appIconDynamicColor] = dynamicColor
        }
        BuiltinAppIcons.apply(context.applicationContext, icon, dynamicColor)
    }

    suspend fun setBottomBarIcons(icons: Map<String, String>) {
        backgroundMutex.lock()
        val created = mutableListOf<File>()
        var committed = false
        try {
            val owned = File(context.filesDir, "backgrounds").canonicalFile.apply { mkdirs() }
            val pending = File(context.cacheDir, "themes").canonicalFile
            val resolved = mutableMapOf<String, String>()
            withContext(Dispatchers.IO) {
                icons.forEach { (slotKey, path) ->
                    val slot = BottomBarIconSlot.fromKey(slotKey) ?: return@forEach
                    if (path.isBlank()) return@forEach
                    val file = File(path).canonicalFile
                    require(file.isFile && file.extension == "png" && file.length() in 1..(12L * 1024 * 1024)) { "底栏图标不可用，请重新选择" }
                    if (file.parentFile == owned) {
                        resolved[slot.key] = file.absolutePath
                    } else {
                        require(file.path.startsWith(pending.path + File.separator)) { "底栏图标位置无效" }
                        val target = File(owned, "bottom-bar-${slot.key}-${UUID.randomUUID()}.png")
                        created.add(target)
                        file.copyTo(target)
                        require(file.length() == target.length()) { "底栏图标保存失败" }
                        resolved[slot.key] = target.absolutePath
                    }
                }
            }
            withContext(NonCancellable) {
                edit(true) { it[Keys.bottomBarIcons] = bottomBarIconMapJson(resolved).toString() }
                committed = true
            }
            runCatching { cleanupBackgrounds() }
        } finally {
            if (!committed) created.forEach { it.delete() }
            backgroundMutex.unlock()
        }
    }

    suspend fun setDynamicWallpaper(
        path: String,
        transform: DynamicWallpaperTransform,
        muted: Boolean,
        fps: Int,
        speed: Float,
    ) {
        backgroundMutex.lock()
        val created = mutableListOf<File>()
        var committed = false
        try {
            val finalPath = promoteOwnedDynamic(path, "dynamic-wallpaper", created)
            withContext(NonCancellable) {
                edit(true) {
                    it[Keys.dynamicWallpaperPath] = finalPath
                    it[Keys.dynamicWallpaperTransform] = transform.sanitized().toJson().toString()
                    it[Keys.dynamicWallpaperMuted] = muted
                    it[Keys.dynamicWallpaperFps] = fps.coerceIn(0, 120)
                    it[Keys.dynamicWallpaperSpeed] = speed.takeIf { v -> v.isFinite() }?.coerceIn(0.25f, 2f) ?: 1f
                }
                committed = true
            }
            runCatching { cleanupBackgrounds() }
        } finally {
            if (!committed) created.forEach { it.delete() }
            backgroundMutex.unlock()
        }
    }

    suspend fun setDynamicHomeCard(path: String, transform: DynamicWallpaperTransform) {
        backgroundMutex.lock()
        val created = mutableListOf<File>()
        var committed = false
        try {
            val finalPath = promoteOwnedDynamic(path, "dynamic-home-card", created)
            withContext(NonCancellable) {
                edit(true) {
                    it[Keys.dynamicHomeCardPath] = finalPath
                    it[Keys.dynamicHomeCardTransform] = transform.sanitized().toJson().toString()
                }
                committed = true
            }
            runCatching { cleanupBackgrounds() }
        } finally {
            if (!committed) created.forEach { it.delete() }
            backgroundMutex.unlock()
        }
    }

    private suspend fun promoteOwnedDynamic(path: String, prefix: String, created: MutableList<File>): String {
        if (path.isBlank()) return ""
        return withContext(Dispatchers.IO) {
            val owned = File(context.filesDir, "backgrounds").canonicalFile.apply { mkdirs() }
            val staged = File(context.cacheDir, DYNAMIC_WALLPAPER_STAGE_DIR).canonicalFile
            val pending = File(context.cacheDir, "themes").canonicalFile
            val file = File(path).canonicalFile
            val kind = dynamicWallpaperKindOf(file) ?: error("动态壁纸文件格式不受支持")
            require(file.length() in 1..DYNAMIC_WALLPAPER_MAX_BYTES) { "动态壁纸文件大小无效" }
            if (file.parentFile == owned) {
                file.absolutePath
            } else {
                require(
                    file.path.startsWith(staged.path + File.separator) ||
                        file.path.startsWith(pending.path + File.separator),
                ) { "动态壁纸位置无效" }
                val target = File(owned, "$prefix-${UUID.randomUUID()}.${kind.extension}")
                created.add(target)
                file.copyTo(target)
                require(file.length() == target.length()) { "动态壁纸保存失败" }
                target.absolutePath
            }
        }
    }

    suspend fun setHomeCards(light: HomeCardDecoration, dark: HomeCardDecoration?) {
        backgroundMutex.lock()
        val created = mutableListOf<File>()
        var committed = false
        try {
            val cards = listOfNotNull(light, dark).map { HomeCardDecoration.parse(it.toJson()) }
            val owned = File(context.filesDir, "backgrounds").canonicalFile.apply { mkdirs() }
            val pending = File(context.cacheDir, "themes").canonicalFile
            val paths = mutableMapOf<String, String>()
            withContext(Dispatchers.IO) {
                cards.flatMap { it.imagePaths() }.filter { it.isNotBlank() }.distinct().forEach { path ->
                    val file = File(path).canonicalFile
                    require(file.isFile && file.extension == "png" && file.length() in 1..(32L * 1024 * 1024)) { "卡片图片不可用，请重新选择" }
                    if (file.parentFile == owned) paths[path] = file.absolutePath
                    else {
                        require(file.path.startsWith(pending.path + File.separator)) { "卡片图片位置无效" }
                        val target = File(owned, "background-${UUID.randomUUID()}.png")
                        created.add(target)
                        file.copyTo(target)
                        require(file.length() == target.length()) { "卡片图片复制失败" }
                        paths[path] = target.absolutePath
                    }
                }
            }
            withContext(NonCancellable) {
                edit(true) { p ->
                    val current = readSettings(p)
                    val adaptive = current.adaptive
                    require((adaptive != null) == (dark != null)) { "主题已变化，请重新打开卡片设置" }
                    if (adaptive == null) p[Keys.homeCard] = cards.first().remap(paths).toJson().toString()
                    else p[Keys.adaptive] = ThemeSettingsCodec.adaptiveToJson(
                        adaptive.copy(
                            light = adaptive.light.copy(homeCard = cards[0].remap(paths)),
                            dark = adaptive.dark.copy(homeCard = cards[1].remap(paths)),
                        ),
                    ).toString()
                }
                committed = true
            }
            runCatching { cleanupBackgrounds() }
        } finally {
            if (!committed) created.forEach { it.delete() }
            backgroundMutex.unlock()
        }
    }

    suspend fun applyTheme(value: AppearanceSettings, includeTexts: Boolean = true) =
        applyTheme(
            value = value,
            options = ThemeImportOptions(
                textGroups = if (includeTexts) ThemeImportOptions.ALL_TEXT_GROUPS else emptySet(),
                themeMode = value.themeMode,
            ),
        )

    internal suspend fun applyTheme(value: AppearanceSettings, options: ThemeImportOptions) {
        backgroundMutex.lock()
        val created = mutableListOf<File>()
        var committed = false
        var appliedIcon: Pair<String, Boolean>? = null
        try {
            val promoted = mutableMapOf<String, String>()
            val pendingRoot = File(context.cacheDir, "themes").canonicalFile
            val ownedDir = File(context.filesDir, "backgrounds").canonicalFile.apply { mkdirs() }

            suspend fun promoteImage(path: String, prefix: String): String {
                if (path.isBlank()) return ""
                return promoted[path] ?: withContext(Dispatchers.IO) {
                    val file = File(path).canonicalFile
                    require(file.isFile && file.path.startsWith(pendingRoot.path + File.separator)) { "主题图片文件不可用" }
                    require(file.extension == "png" && file.length() in 1..(32L * 1024 * 1024)) { "主题图片无效" }
                    val target = File(ownedDir, "$prefix-${UUID.randomUUID()}.png")
                    created.add(target)
                    file.copyTo(target)
                    require(file.length() == target.length()) { "主题图片复制失败" }
                    promoted[path] = target.absolutePath
                    target.absolutePath
                }
            }

            if (options.applyBackground) {
                listOf(value.backgroundPath, value.adaptive?.light?.backgroundPath.orEmpty(), value.adaptive?.dark?.backgroundPath.orEmpty())
                    .filter { it.isNotBlank() }.distinct().forEach { promoteImage(it, "background") }
            }
            if (options.applyHomeCard) {
                listOf(value.homeCard.backgroundPath, value.adaptive?.light?.homeCard?.backgroundPath.orEmpty(), value.adaptive?.dark?.homeCard?.backgroundPath.orEmpty())
                    .filter { it.isNotBlank() }.distinct().forEach { promoteImage(it, "background") }
            }
            if (options.applyStickers) {
                (value.homeCard.stickers.map { it.path } +
                    value.adaptive?.light?.homeCard?.stickers?.map { it.path }.orEmpty() +
                    value.adaptive?.dark?.homeCard?.stickers?.map { it.path }.orEmpty())
                    .filter { it.isNotBlank() }.distinct().forEach { promoteImage(it, "background") }
            }
            val promotedBottomBar = if (options.applyBottomBarIcons) {
                value.bottomBarIcons.mapNotNull { (slot, path) ->
                    val key = BottomBarIconSlot.fromKey(slot)?.key ?: return@mapNotNull null
                    if (path.isBlank()) null else key to promoteImage(path, "bottom-bar-$key")
                }.toMap()
            } else null

            val promotedDynamicWallpaper = if (options.applyDynamicWallpaper) {
                promoteOwnedDynamic(value.dynamicWallpaperPath, "dynamic-wallpaper", created)
            } else null
            val promotedDynamicCard = if (options.applyDynamicHomeCard) {
                promoteOwnedDynamic(value.dynamicHomeCardPath, "dynamic-home-card", created)
            } else null

            withContext(NonCancellable) {
                context.appearanceDataStore.edit { p ->
                    val current = readSettings(p)
                    fun mergeHomeCard(incoming: HomeCardDecoration, existing: HomeCardDecoration): HomeCardDecoration =
                        existing.copy(
                            backgroundPath = if (options.applyHomeCard) promoted[incoming.backgroundPath].orEmpty() else existing.backgroundPath,
                            scrim = if (options.applyHomeCard) incoming.scrim else existing.scrim,
                            blur = if (options.applyHomeCard) incoming.blur else existing.blur,
                            imageAlpha = if (options.applyHomeCard) incoming.imageAlpha else existing.imageAlpha,
                            zoom = if (options.applyHomeCard) incoming.zoom else existing.zoom,
                            offsetX = if (options.applyHomeCard) incoming.offsetX else existing.offsetX,
                            offsetY = if (options.applyHomeCard) incoming.offsetY else existing.offsetY,
                            hideSample = if (options.applyHomeCard) incoming.hideSample else existing.hideSample,
                            hideLabel = if (options.applyHomeCard) incoming.hideLabel else existing.hideLabel,
                            stickers = if (options.applyStickers) incoming.stickers.map { it.copy(path = promoted[it.path].orEmpty()) } else existing.stickers,
                        )

                    val mergedTexts = if (options.textGroups.isEmpty()) {
                        current.customTexts
                    } else {
                        val kept = current.customTexts.filterKeys { ThemeTextKey.forKey(it)?.group !in options.textGroups }
                        val incoming = value.customTexts.filterKeys { ThemeTextKey.forKey(it)?.group in options.textGroups }
                        kept + incoming
                    }

                    val mergedAdaptive = if (options.applyColorsAndStyle && value.adaptive != null) {
                        val curLight = current.forDark(false)
                        val curDark = current.forDark(true)
                        AdaptiveAppearance(
                            light = value.adaptive.light.copy(
                                backgroundPath = if (options.applyBackground) promoted[value.adaptive.light.backgroundPath].orEmpty() else curLight.backgroundPath,
                                homeCard = mergeHomeCard(value.adaptive.light.homeCard, curLight.homeCard),
                            ),
                            dark = value.adaptive.dark.copy(
                                backgroundPath = if (options.applyBackground) promoted[value.adaptive.dark.backgroundPath].orEmpty() else curDark.backgroundPath,
                                homeCard = mergeHomeCard(value.adaptive.dark.homeCard, curDark.homeCard),
                            ),
                        )
                    } else if (options.applyColorsAndStyle) {
                        null
                    } else {
                        current.adaptive
                    }

                    val nextIconId = if (options.applyAppIcon && BuiltinAppIcons.isKnown(value.appIconId)) {
                        value.appIconId
                    } else {
                        current.appIconId
                    }
                    val nextIconDynamic = if (options.applyAppIcon) value.appIconDynamicColor else current.appIconDynamicColor
                    if (nextIconId != current.appIconId || nextIconDynamic != current.appIconDynamicColor) {
                        appliedIcon = nextIconId to nextIconDynamic
                    }

                    val merged = (if (options.applyColorsAndStyle) value else current).copy(
                        themeMode = options.themeMode ?: if (options.applyColorsAndStyle) value.themeMode else current.themeMode,
                        highRefreshRate = current.highRefreshRate,
                        predictiveBack = current.predictiveBack,
                        backgroundPath = if (options.applyBackground) promoted[value.backgroundPath].orEmpty() else current.backgroundPath,
                        homeCard = mergeHomeCard(value.homeCard, current.homeCard),
                        adaptive = mergedAdaptive,
                        customTexts = mergedTexts,
                        bottomBarIcons = promotedBottomBar ?: current.bottomBarIcons,
                        appIconId = nextIconId,
                        appIconDynamicColor = nextIconDynamic,
                        dynamicWallpaperPath = promotedDynamicWallpaper ?: current.dynamicWallpaperPath,
                        dynamicWallpaperTransform = if (options.applyDynamicWallpaper) value.dynamicWallpaperTransform else current.dynamicWallpaperTransform,
                        dynamicHomeCardPath = promotedDynamicCard ?: current.dynamicHomeCardPath,
                        dynamicHomeCardTransform = if (options.applyDynamicHomeCard) value.dynamicHomeCardTransform else current.dynamicHomeCardTransform,
                        dynamicWallpaperMuted = current.dynamicWallpaperMuted,
                        dynamicWallpaperFps = current.dynamicWallpaperFps,
                        dynamicWallpaperSpeed = current.dynamicWallpaperSpeed,
                        themeName = value.themeName.ifBlank { current.themeName },
                        themeAuthor = value.themeAuthor.ifBlank { current.themeAuthor },
                        themeLicense = value.themeLicense.ifBlank { current.themeLicense },
                    ).normalized()
                    writeTheme(p, merged)
                }
                committed = true
            }
            appliedIcon?.let { (iconId, dynamicColor) ->
                BuiltinAppIcons.applyIfNeeded(context.applicationContext, BuiltinAppIcons.find(iconId), dynamicColor)
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
                writeTheme(
                    preferences,
                    AppearanceSettings(
                        uiStyle = current.uiStyle,
                        highRefreshRate = current.highRefreshRate,
                        predictiveBack = current.predictiveBack,
                        appIconId = current.appIconId,
                        appIconDynamicColor = current.appIconDynamicColor,
                    ),
                )
            }
            runCatching { cleanupBackgrounds() }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }
        } finally {
            backgroundMutex.unlock()
        }
    }

    private fun writeTheme(p: MutablePreferences, s: AppearanceSettings) {
        p[Keys.uiStyle] = s.uiStyle.name
        p[Keys.themeMode] = s.themeMode.storageValue
        p[Keys.seedArgb] = s.seedArgb
        p[Keys.kolorStyle] = s.kolorStyle.name
        p[Keys.colorStandard] = s.colorStandard.name
        p[Keys.monetEnabled] = s.monetEnabled
        p[Keys.nativeMonetEnabled] = s.nativeMonetEnabled
        p[Keys.amoledBlack] = s.amoledBlack
        p[Keys.blurEnabled] = s.blurEnabled
        p[Keys.glassEnabled] = s.glassEnabled
        p[Keys.floatingDock] = s.floatingDock
        p[Keys.backgroundPath] = s.backgroundPath
        p[Keys.cardTransparency] = s.cardTransparency
        p[Keys.backgroundBlur] = s.backgroundBlur
        p[Keys.backgroundScrim] = s.backgroundScrim
        p[Keys.backgroundScrimIsSigned] = s.backgroundScrimIsSigned
        p[Keys.overrides] = s.overrides.toJson().toString()
        p[Keys.customTexts] = ThemeTexts.json(s.customTexts).toString()
        p[Keys.homeCard] = s.homeCard.toJson().toString()
        p[Keys.adaptive] = s.adaptive?.let { ThemeSettingsCodec.adaptiveToJson(it).toString() }.orEmpty()
        p[Keys.themeName] = s.themeName
        p[Keys.themeAuthor] = s.themeAuthor
        p[Keys.themeLicense] = s.themeLicense
        p[Keys.appIconId] = s.appIconId
        p[Keys.appIconDynamicColor] = s.appIconDynamicColor
        p[Keys.bottomBarIcons] = bottomBarIconMapJson(s.bottomBarIcons).toString()
        p[Keys.dynamicWallpaperPath] = s.dynamicWallpaperPath
        p[Keys.dynamicWallpaperTransform] = s.dynamicWallpaperTransform.sanitized().toJson().toString()
        p[Keys.dynamicHomeCardPath] = s.dynamicHomeCardPath
        p[Keys.dynamicHomeCardTransform] = s.dynamicHomeCardTransform.sanitized().toJson().toString()
        p[Keys.dynamicWallpaperMuted] = s.dynamicWallpaperMuted
        p[Keys.dynamicWallpaperFps] = s.dynamicWallpaperFps
        p[Keys.dynamicWallpaperSpeed] = s.dynamicWallpaperSpeed
    }

    private suspend fun cleanupBackgrounds() = withContext(Dispatchers.IO) {
        val s = readSettings(context.appearanceDataStore.data.first())
        val keep = s.allImagePaths().toSet()
        File(context.filesDir, "backgrounds").listFiles()?.filter {
            (it.name.startsWith("background-") ||
                it.name.startsWith("bottom-bar-") ||
                it.name.startsWith("dynamic-wallpaper-") ||
                it.name.startsWith("dynamic-home-card-")) &&
                it.absolutePath !in keep &&
                System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L
        }?.forEach { it.delete() }
    }

    suspend fun setBackgroundPath(path: String) {
        backgroundMutex.lock()
        try {
            edit { it[Keys.backgroundPath] = path }
            cleanupBackgrounds()
        } finally {
            backgroundMutex.unlock()
        }
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
