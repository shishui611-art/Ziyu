package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ThemeImportOptions(
    val applyColorsAndStyle: Boolean = true,
    val applyBackground: Boolean = true,
    val applyDynamicWallpaper: Boolean = true,
    val applyHomeCard: Boolean = true,
    val applyDynamicHomeCard: Boolean = true,
    val applyStickers: Boolean = true,
    val applyBottomBarIcons: Boolean = true,
    val applyAppIcon: Boolean = true,
    val textGroups: Set<String> = ALL_TEXT_GROUPS,
    val themeMode: ThemeMode? = null,
) {
    companion object {
        val ALL_TEXT_GROUPS = setOf("首页", "页面", "设置")
        fun allFor(prepared: PreparedTheme): ThemeImportOptions = ThemeImportOptions(
            applyColorsAndStyle = true,
            applyBackground = prepared.hasBackground,
            applyDynamicWallpaper = prepared.hasDynamicWallpaper,
            applyHomeCard = prepared.hasCardBackground,
            applyDynamicHomeCard = prepared.hasDynamicHomeCard,
            applyStickers = prepared.hasStickers,
            applyBottomBarIcons = prepared.hasBottomBarIcons,
            applyAppIcon = prepared.hasAppIcon,
            textGroups = prepared.textGroups,
            themeMode = prepared.settings.themeMode,
        )
    }
}

internal data class PreparedTheme(
    val settings: AppearanceSettings,
    val directory: File,
    val warnings: List<String> = emptyList(),
    val formatVersion: Int = 1,
    val isAdaptive: Boolean = false,
    val hasBackground: Boolean = false,
    val hasCardBackground: Boolean = false,
    val hasStickers: Boolean = false,
    val hasCustomColors: Boolean = false,
    val hasImageEffects: Boolean = false,
    val hasBottomBarIcons: Boolean = false,
    val hasAppIcon: Boolean = false,
    val hasDynamicWallpaper: Boolean = false,
    val hasDynamicHomeCard: Boolean = false,
    val textGroups: Set<String> = emptySet(),
    val explicitClearDynamicWallpaper: Boolean = false,
    val explicitClearDynamicHomeCard: Boolean = false,
) {
    fun discard() {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }
}

/** Data-only v1-v6 SukiSU reader plus Ziyu's lossless appearance extension.
 * Format reference: shirkneko/SukiSU 97d2442d (v1-v6).
 * Artwork is not bundled or relicensed; author and package license are retained.
 */
internal object ThemePackages {
    const val MIN_SUPPORTED_FORMAT = 1
    const val MAX_SUPPORTED_FORMAT = 6
    const val MAX_PACKAGE_BYTES = 128L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 320L * 1024 * 1024
    private const val MAX_JSON_BYTES = 64 * 1024
    private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    private const val MAX_DYNAMIC_BYTES = MAX_PACKAGE_BYTES
    private const val PACKAGE_SIZE_RESERVE = 2L * 1024 * 1024
    private const val MAX_IMPORTED_CARD_TRANSPARENCY = 50

    suspend fun prepare(
        context: Context,
        uri: Uri,
        style: UiStyle,
        name: String = "导入的主题",
        author: String = "",
        license: String = "",
    ): PreparedTheme = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "themes/pending-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val imageFiles = mutableMapOf<String, File>()
            val bottomBarFiles = mutableMapOf<String, File>()
            val dynamicFiles = mutableMapOf<String, File>()
            var manifest: JSONObject? = null
            var expanded = 0L
            context.contentResolver.openInputStream(uri)?.use { original ->
                val input = original.buffered(64 * 1024)
                input.mark(8)
                val header = ByteArray(4)
                var received = 0
                while (received < 4) {
                    val n = input.read(header, received, 4 - received)
                    if (n < 0) break
                    received += n
                }
                input.reset()
                val encrypted = received == 4 && header.contentEquals(byteArrayOf(83, 75, 84, 72))
                val limited = LimitedThemeInput(input, MAX_PACKAGE_BYTES)
                val plain = if (encrypted) ThemePackageCrypto.decryptingStream(limited) else limited
                val bounded = LimitedThemeInput(plain, MAX_EXPANDED_BYTES)
                ZipInputStream(bounded).use { zip ->
                    var entries = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 160) { "主题包文件过多" }
                        val path = entry.name
                        require(!path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." }) { "主题包路径无效" }
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val baseName = if ('/' !in path) path.substringBeforeLast('.', path) else ""
                        val imageRole = baseName.takeIf {
                            it in setOf(
                                "background", "background_light", "background_dark",
                                "card_background", "card_background_light", "card_background_dark",
                            ) || Regex("card_sticker_(?:(?:light|dark)_)?[0-4]").matches(it)
                        }
                        val bottomBarSlot = if ('/' !in path && baseName.startsWith("bottom_bar_icon_")) {
                            BottomBarIconSlot.fromKey(baseName.removePrefix("bottom_bar_icon_"))?.key
                        } else null
                        val dynamicRole = baseName.takeIf { it == "dynamic_wallpaper" || it == "dynamic_home_card" }
                        val target = when {
                            imageRole != null -> {
                                require(imageRole !in imageFiles) { "主题包包含重复背景" }
                                File(dir, "$imageRole.image")
                            }
                            bottomBarSlot != null -> {
                                require(bottomBarSlot !in bottomBarFiles) { "主题包包含重复底栏图标" }
                                File(dir, "bottom_bar_$bottomBarSlot.image")
                            }
                            dynamicRole != null -> {
                                require(dynamicRole !in dynamicFiles) { "主题包包含重复动态壁纸" }
                                File(dir, "$dynamicRole.raw")
                            }
                            else -> null
                        }
                        require(path != "theme.json" || manifest == null) { "主题包包含重复配置" }
                        val jsonBytes = if (path == "theme.json") java.io.ByteArrayOutputStream() else null
                        var size = 0L
                        val sink = target?.outputStream()?.buffered(64 * 1024)
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buffer); if (n < 0) break
                                size += n; expanded += n
                                require(expanded <= MAX_EXPANDED_BYTES) { "主题解压后过大" }
                                if (imageRole != null || bottomBarSlot != null) {
                                    require(size <= MAX_IMAGE_BYTES) { "背景图片超过 32 MB" }
                                }
                                if (dynamicRole != null) {
                                    require(size <= MAX_DYNAMIC_BYTES) { "动态壁纸超过 128 MB" }
                                }
                                if (jsonBytes != null) {
                                    require(size <= MAX_JSON_BYTES) { "主题配置超过 64 KB" }
                                    jsonBytes.write(buffer, 0, n)
                                }
                                sink?.write(buffer, 0, n)
                            }
                        } finally { sink?.close() }
                        if (jsonBytes != null) manifest = JSONObject(jsonBytes.toString("UTF-8"))
                        if (imageRole != null && target != null) imageFiles[imageRole] = target
                        if (bottomBarSlot != null && target != null) bottomBarFiles[bottomBarSlot] = target
                        if (dynamicRole != null && target != null) dynamicFiles[dynamicRole] = target
                        zip.closeEntry()
                    }
                    // Authentication must reach ciphertext EOF before any candidate can be applied.
                    val tail = ByteArray(64 * 1024)
                    while (bounded.read(tail) >= 0) currentCoroutineContext().ensureActive()
                }
            } ?: error("无法读取主题包")
            val root = manifest ?: error("主题包缺少 theme.json")
            val formatVersion = root.optInt("version", 1)
            require(formatVersion in MIN_SUPPORTED_FORMAT..MAX_SUPPORTED_FORMAT) { "暂不支持此主题包版本（v$formatVersion）" }

            imageFiles.keys.toList().forEach { role ->
                currentCoroutineContext().ensureActive()
                val raw = imageFiles.getValue(role)
                val normalized = File(dir, "$role.png")
                normalizeBackgroundFile(raw, normalized)
                raw.delete()
                imageFiles[role] = normalized
            }
            val normalizedBottomBarIcons = buildMap<String, String> {
                bottomBarFiles.forEach { (slot, raw) ->
                    currentCoroutineContext().ensureActive()
                    val normalized = File(dir, "bottom-bar-$slot.png")
                    normalizeBottomBarIconFile(raw, normalized)
                    raw.delete()
                    put(slot, normalized.absolutePath)
                }
            }
            val validatedDynamic = buildMap<String, File> {
                dynamicFiles.forEach { (role, raw) ->
                    currentCoroutineContext().ensureActive()
                    val kind = dynamicWallpaperKindOf(raw) ?: error("主题包中的动态壁纸格式不受支持")
                    val target = File(dir, "$role.${kind.extension}")
                    if (!raw.renameTo(target)) {
                        raw.copyTo(target, overwrite = true)
                        raw.delete()
                    }
                    put(role, target)
                }
            }

            val appearance = AppearanceOverrides.parse(root.optJSONObject("appearance"))
            val own = root.optJSONObject("ziyu")
            val defaults = AppearanceSettings(uiStyle = style)
            var settings = if (own != null) ThemeSettingsCodec.decode(own, defaults)
                else fromSuki(root, defaults).copy(overrides = appearance)

            fun background(data: JSONObject, role: String): String {
                val needed = data.optBoolean("hasBackground", imageFiles[role] != null)
                return if (!needed) "" else imageFiles[role]?.absolutePath ?: error("主题包缺少${if (role.endsWith("dark")) "深色" else ""}背景")
            }

            fun card(data: JSONObject, suffix: String): HomeCardDecoration {
                val ownCard = data.optJSONObject("ziyu")?.optJSONObject("homeCard")
                val base = if (ownCard == null) HomeCardDecoration(scrim = 0) else HomeCardDecoration.parse(ownCard, paths = false)
                val image = imageFiles["card_background$suffix"]
                val needed = data.optBoolean("hasCardBackground", image != null)
                require(!needed || image != null) { "主题包缺少首页卡片背景" }
                val transforms = ownCard?.optJSONArray("stickers") ?: data.optJSONArray("homeCardStickerTransforms") ?: JSONArray()
                require(transforms.length() <= CardSticker.MAX_COUNT) { "主题贴纸超过 5 张" }
                val prefix = "card_sticker${suffix}_"
                val indices = (0 until CardSticker.MAX_COUNT).filter { imageFiles.containsKey(prefix + it) }
                if (transforms.length() > 0) require(indices == (0 until transforms.length()).toList()) { "主题贴纸文件不完整" }
                return base.copy(
                    backgroundPath = if (needed) image!!.absolutePath else "",
                    hideSample = ownCard?.optBoolean("hideSample") ?: data.optBoolean("hideHomeCardDecoration"),
                    hideLabel = ownCard?.optBoolean("hideLabel") ?: data.optBoolean("hideHomeCardStatusText"),
                    stickers = indices.map { i -> CardSticker.parse(imageFiles.getValue(prefix + i).absolutePath, transforms.optJSONObject(i)) },
                )
            }

            val textJson = own?.optJSONObject("customTexts") ?: root.optJSONObject("customTexts") ?: JSONObject()
            if (nonBlankThemeText(textJson.opt("home.title")) == null) {
                nonBlankThemeText(root.opt("customHomeTitle"))?.let { textJson.put("home.title", it) }
            }
            val parsedTexts = ThemeTexts.parse(textJson)
            val isAdaptive = root.optBoolean("adaptive", false)

            val dynamicObj = root.optJSONObject("dynamic")
            val dynamicWallpaperFile = validatedDynamic["dynamic_wallpaper"]
            val dynamicHomeCardFile = validatedDynamic["dynamic_home_card"]
            val hasDynamicWallpaperFlag = dynamicObj?.takeIf { it.has("hasWallpaper") && !it.isNull("hasWallpaper") }?.optBoolean("hasWallpaper")
            val hasDynamicHomeCardFlag = dynamicObj?.takeIf { it.has("hasHomeCard") && !it.isNull("hasHomeCard") }?.optBoolean("hasHomeCard")
            if (hasDynamicWallpaperFlag == true) require(dynamicWallpaperFile != null) { "主题包缺少动态壁纸文件" }
            if (hasDynamicHomeCardFlag == true) require(dynamicHomeCardFile != null) { "主题包缺少动态卡片文件" }

            val rawAppIconId = nonBlankThemeText(own?.opt("appIconId")) ?: nonBlankThemeText(root.opt("appIconId"))
            val knownAppIconId = rawAppIconId?.takeIf(BuiltinAppIcons::isKnown)

            settings = settings.copy(
                customTexts = parsedTexts,
                homeCard = if (isAdaptive) HomeCardDecoration() else card(root, ""),
                backgroundPath = if (isAdaptive) "" else background(root, "background"),
                bottomBarIcons = normalizedBottomBarIcons,
                appIconId = knownAppIconId ?: settings.appIconId,
                dynamicWallpaperPath = if (hasDynamicWallpaperFlag == false) "" else dynamicWallpaperFile?.absolutePath.orEmpty(),
                dynamicWallpaperTransform = dynamicObj?.optJSONObject("wallpaperTransform")?.let(DynamicWallpaperTransform::parse)
                    ?: settings.dynamicWallpaperTransform,
                dynamicHomeCardPath = if (hasDynamicHomeCardFlag == false) "" else dynamicHomeCardFile?.absolutePath.orEmpty(),
                dynamicHomeCardTransform = dynamicObj?.optJSONObject("homeCardTransform")?.let(DynamicWallpaperTransform::parse)
                    ?: settings.dynamicHomeCardTransform,
            )

            if (isAdaptive) {
                require(formatVersion >= 3) { "双模式主题的版本无效" }
                val lightJson = root.getJSONObject("light")
                val darkJson = root.getJSONObject("dark")
                fun half(o: JSONObject, key: String): AppearanceVariant {
                    val z = o.optJSONObject("ziyu")
                    val variant = if (z != null) ThemeSettingsCodec.decodeVariant(z, settings) else fromSuki(o, settings).asVariant()
                    return variant.copy(
                        backgroundPath = background(o, key),
                        homeCard = card(o, if (key.endsWith("dark")) "_dark" else "_light"),
                    )
                }
                settings = settings.copy(
                    themeMode = ThemeMode.SYSTEM,
                    adaptive = AdaptiveAppearance(
                        half(lightJson, "background_light"),
                        half(darkJson, "background_dark"),
                    ),
                )
            }

            val unsupportedTexts = textJson.keys().asSequence().count { ThemeTextKey.forKey(it) == null }
            val transparencyWasLimited = settings.cardTransparency > MAX_IMPORTED_CARD_TRANSPARENCY ||
                settings.adaptive?.let {
                    it.light.cardTransparency > MAX_IMPORTED_CARD_TRANSPARENCY ||
                        it.dark.cardTransparency > MAX_IMPORTED_CARD_TRANSPARENCY
                } == true
            settings = settings.copy(
                cardTransparency = settings.cardTransparency.coerceAtMost(MAX_IMPORTED_CARD_TRANSPARENCY),
                adaptive = settings.adaptive?.let { adaptive ->
                    adaptive.copy(
                        light = adaptive.light.copy(cardTransparency = adaptive.light.cardTransparency.coerceAtMost(MAX_IMPORTED_CARD_TRANSPARENCY)),
                        dark = adaptive.dark.copy(cardTransparency = adaptive.dark.cardTransparency.coerceAtMost(MAX_IMPORTED_CARD_TRANSPARENCY)),
                    )
                },
            )
            val warnings = buildList {
                if (transparencyWasLimited) add("为保证文字清晰，主题卡片透明度已限制在 50%。")
                if (unsupportedTexts > 0) add("已导入可用文字；$unsupportedTexts 项专属文字未应用。")
                if (rawAppIconId != null && knownAppIconId == null) add("主题内置的外部应用图标标识未匹配字域图标，已保持当前图标。")
            }
            val hasBg = settings.backgroundPath.isNotBlank() ||
                settings.adaptive?.let { it.light.backgroundPath.isNotBlank() || it.dark.backgroundPath.isNotBlank() } == true
            val hasCard = settings.homeCard.backgroundPath.isNotBlank() ||
                settings.adaptive?.let { it.light.homeCard.backgroundPath.isNotBlank() || it.dark.homeCard.backgroundPath.isNotBlank() } == true
            val hasStickers = settings.homeCard.stickers.isNotEmpty() ||
                settings.adaptive?.let { it.light.homeCard.stickers.isNotEmpty() || it.dark.homeCard.stickers.isNotEmpty() } == true
            val hasColors = settings.overrides.shared.isNotEmpty() || settings.overrides.light.isNotEmpty() || settings.overrides.dark.isNotEmpty()
            val hasEffects = settings.overrides.effects != BackgroundEffects()
            val textGroups = parsedTexts.keys.mapNotNull { ThemeTextKey.forKey(it)?.group }.toSet()

            PreparedTheme(
                settings = settings.copy(
                    themeName = (nonBlankThemeText(root.opt("name")) ?: nonBlankThemeText(name) ?: "导入的主题").take(120),
                    themeAuthor = (nonBlankThemeText(root.opt("author")) ?: nonBlankThemeText(author)).orEmpty().take(120),
                    themeLicense = (nonBlankThemeText(root.opt("license")) ?: nonBlankThemeText(license)).orEmpty().take(200),
                ),
                directory = dir,
                warnings = warnings,
                formatVersion = formatVersion,
                isAdaptive = isAdaptive,
                hasBackground = hasBg,
                hasCardBackground = hasCard,
                hasStickers = hasStickers,
                hasCustomColors = hasColors,
                hasImageEffects = hasEffects,
                hasBottomBarIcons = normalizedBottomBarIcons.isNotEmpty() || root.has("bottomBarIcons"),
                hasAppIcon = knownAppIconId != null,
                hasDynamicWallpaper = dynamicWallpaperFile != null || hasDynamicWallpaperFlag == false,
                hasDynamicHomeCard = dynamicHomeCardFile != null || hasDynamicHomeCardFlag == false,
                textGroups = textGroups,
                explicitClearDynamicWallpaper = hasDynamicWallpaperFlag == false,
                explicitClearDynamicHomeCard = hasDynamicHomeCardFlag == false,
            )
        } catch (error: Throwable) {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
            throw error
        }
    }

    private fun fromSuki(o: JSONObject, base: AppearanceSettings): AppearanceSettings {
        val style = nonBlankThemeText(o.opt("uiMode"))?.let { raw ->
            UiStyle.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: base.uiStyle
        val suppliedMode = o.optInt("themeMode", 0)
        val nativeMonet = o.optBoolean("miuixMonet", suppliedMode >= 3)
        val mode = if (style == UiStyle.MIUIX) when {
            nativeMonet && suppliedMode in 0..2 -> suppliedMode + 3
            !nativeMonet && suppliedMode in 3..5 -> suppliedMode - 3
            !nativeMonet && suppliedMode == 6 -> 2
            else -> suppliedMode
        } else suppliedMode
        val seed = if (o.has("keyColor") && !o.isNull("keyColor")) o.optLong("keyColor").toInt() else null
        return base.copy(
            uiStyle = style,
            themeMode = when (mode) { 1, 4 -> ThemeMode.LIGHT; 2, 5, 6 -> ThemeMode.DARK; else -> ThemeMode.SYSTEM },
            seedArgb = seed?.takeIf { it != 0 } ?: base.seedArgb,
            monetEnabled = seed?.let { it == 0 } ?: base.monetEnabled,
            nativeMonetEnabled = mode >= 3,
            amoledBlack = mode == 6,
            kolorStyle = KolorStyle.fromStorage(o.optString("colorStyle", base.kolorStyle.paletteName)),
            colorStandard = ColorStandard.fromStorage(o.optString("colorSpec", base.colorStandard.name)),
            cardTransparency = (100 - o.optInt("cardBackgroundAlphaPercent", 100)).coerceIn(0, 100),
            backgroundBlur = o.optInt("customBackgroundBlurPercent", 0).coerceIn(0, 100),
            backgroundScrim = o.optInt("customBackgroundScrimPercent", 0).coerceIn(-100, 100),
            backgroundScrimIsSigned = true,
            floatingDock = o.optBoolean("enableFloatingBottomBar", base.floatingDock),
            glassEnabled = o.optBoolean("enableFloatingBottomBarBlur", base.glassEnabled),
            blurEnabled = o.optBoolean("enableBlur", base.blurEnabled),
        ).normalized()
    }

    suspend fun export(context: Context, settings: AppearanceSettings, uri: Uri): String = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "themes/export-${UUID.randomUUID()}.ziyu-theme")
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        try {
            val ownedDir = File(context.filesDir, "backgrounds").canonicalFile
            fun ownedFile(path: String, maxBytes: Long): File? {
                if (path.isBlank()) return null
                val candidate = File(path).canonicalFile
                require(candidate.parentFile == ownedDir && candidate.isFile && candidate.length() in 1..maxBytes) {
                    "主题媒体文件不可用，请重新选择"
                }
                return candidate
            }

            val bottomBarExport = settings.bottomBarIcons.mapNotNull { (slot, path) ->
                val canonicalSlot = BottomBarIconSlot.fromKey(slot)?.key ?: return@mapNotNull null
                ownedFile(path, MAX_IMAGE_BYTES)?.let { canonicalSlot to it }
            }.toMap()

            var dynamicWallpaperFile = settings.dynamicWallpaperPath.takeIf { it.isNotBlank() }?.let {
                ownedFile(it, MAX_DYNAMIC_BYTES)
            }
            var dynamicHomeCardFile = settings.dynamicHomeCardPath.takeIf { it.isNotBlank() }?.let {
                ownedFile(it, MAX_DYNAMIC_BYTES)
            }

            // Respect the 128 MiB package ceiling by dropping oversized dynamic media gracefully if needed.
            val staticEstimate = settings.allImagePaths().filter {
                it.isNotBlank() && it != settings.dynamicWallpaperPath && it != settings.dynamicHomeCardPath
            }.distinct().sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
            val limit = MAX_PACKAGE_BYTES - PACKAGE_SIZE_RESERVE
            var skippedDynamic = false
            while (staticEstimate + (dynamicWallpaperFile?.length() ?: 0L) + (dynamicHomeCardFile?.length() ?: 0L) > limit &&
                (dynamicWallpaperFile != null || dynamicHomeCardFile != null)
            ) {
                if (dynamicWallpaperFile != null && (dynamicHomeCardFile == null || dynamicWallpaperFile.length() >= dynamicHomeCardFile.length())) {
                    dynamicWallpaperFile = null
                } else {
                    dynamicHomeCardFile = null
                }
                skippedDynamic = true
            }

            val hasDynamic = dynamicWallpaperFile != null || dynamicHomeCardFile != null
            val version = when {
                hasDynamic -> 6
                bottomBarExport.isNotEmpty() -> 5
                else -> 4
            }

            val json = sukisuValues(settings)
                .put("version", version)
                .put("name", settings.themeName.ifBlank { "我的主题" })
                .put("author", settings.themeAuthor)
                .put("license", settings.themeLicense)
                .put("appIconId", settings.appIconId)
                .put("appearance", settings.overrides.toJson())
                .put("ziyu", ThemeSettingsCodec.encode(settings))
                .put("customHomeTitle", settings.customTexts["home.title"].orEmpty())
                .put("customTexts", ThemeTexts.json(settings.customTexts))
            if (bottomBarExport.isNotEmpty()) {
                json.put("bottomBarIcons", JSONArray(bottomBarExport.keys.sorted()))
            }
            if (hasDynamic) {
                val dyn = JSONObject()
                    .put("hasWallpaper", dynamicWallpaperFile != null)
                    .put("hasHomeCard", dynamicHomeCardFile != null)
                if (dynamicWallpaperFile != null) dyn.put("wallpaperTransform", settings.dynamicWallpaperTransform.sanitized().toJson())
                if (dynamicHomeCardFile != null) dyn.put("homeCardTransform", settings.dynamicHomeCardTransform.sanitized().toJson())
                json.put("dynamic", dyn)
            }
            settings.adaptive?.let { adaptive ->
                json.put("adaptive", true)
                json.put("hasBackground", false).put("hasCardBackground", false).put("homeCardStickerTransforms", JSONArray())
                json.put("light", sukisuValues(adaptive.light.applyTo(settings)).put("ziyu", ThemeSettingsCodec.encodeVariant(adaptive.light)))
                json.put("dark", sukisuValues(adaptive.dark.applyTo(settings)).put("ziyu", ThemeSettingsCodec.encodeVariant(adaptive.dark)))
            }

            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("theme.json"))
                zip.write(json.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                suspend fun addFile(source: File, entryName: String) {
                    zip.putNextEntry(ZipEntry(entryName))
                    source.inputStream().buffered(64 * 1024).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            zip.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }

                suspend fun addImage(path: String, name: String) {
                    val source = ownedFile(path, MAX_IMAGE_BYTES) ?: return
                    addFile(source, "$name.png")
                }

                suspend fun artwork(card: HomeCardDecoration, suffix: String) {
                    addImage(card.backgroundPath, "card_background$suffix")
                    card.stickers.forEachIndexed { i, sticker -> addImage(sticker.path, "card_sticker${suffix}_$i") }
                }

                if (settings.adaptive == null) {
                    addImage(settings.backgroundPath, "background")
                    artwork(settings.homeCard, "")
                } else {
                    addImage(settings.adaptive.light.backgroundPath, "background_light")
                    artwork(settings.adaptive.light.homeCard, "_light")
                    addImage(settings.adaptive.dark.backgroundPath, "background_dark")
                    artwork(settings.adaptive.dark.homeCard, "_dark")
                }
                bottomBarExport.forEach { (slot, iconFile) ->
                    addFile(iconFile, "bottom_bar_icon_$slot")
                }
                dynamicWallpaperFile?.let { addFile(it, "dynamic_wallpaper") }
                dynamicHomeCardFile?.let { addFile(it, "dynamic_home_card") }
            }
            require(file.length() <= MAX_PACKAGE_BYTES) { "主题包超过 128 MB" }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().buffered(64 * 1024).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("无法保存主题包")
            if (skippedDynamic) "已导出（部分超出 128 MB 限制的动态媒体未打包）" else "已导出"
        } finally {
            file.delete()
        }
    }

    private fun sukisuValues(s: AppearanceSettings): JSONObject = JSONObject()
        .put(
            "themeMode",
            when (s.themeMode) {
                ThemeMode.SYSTEM -> if (s.nativeMonetEnabled) 3 else 0
                ThemeMode.LIGHT -> if (s.nativeMonetEnabled) 4 else 1
                ThemeMode.DARK -> if (s.amoledBlack) 6 else if (s.nativeMonetEnabled) 5 else 2
            },
        )
        .put("miuixMonet", s.nativeMonetEnabled)
        .put("keyColor", if (s.monetEnabled) 0 else s.seedArgb)
        .put("colorStyle", s.kolorStyle.paletteName)
        .put("colorSpec", s.colorStandard.name)
        .put("cardBackgroundAlphaPercent", 100 - s.cardTransparency)
        .put("customBackgroundBlurPercent", s.backgroundBlur)
        .put("customBackgroundScrimPercent", s.backgroundScrim)
        .put("hasCardBackground", s.homeCard.backgroundPath.isNotBlank())
        .put("hideHomeCardStatusText", s.homeCard.hideLabel)
        .put("hideHomeCardDecoration", s.homeCard.hideSample)
        .put("homeCardStickerTransforms", JSONArray().apply { s.homeCard.stickers.forEach { put(it.transformJson()) } })
        .put("hasBackground", s.backgroundPath.isNotBlank())
        .put("uiMode", if (s.uiStyle == UiStyle.MATERIAL) "material" else "miuix")
        .put("enableFloatingBottomBar", s.floatingDock)
        .put("enableFloatingBottomBarBlur", s.glassEnabled)
        .put("enableBlur", s.blurEnabled)

    private class LimitedThemeInput(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L
        private fun add(n: Int): Int {
            if (n > 0) {
                count += n
                require(count <= max) { "主题包超过大小限制" }
            }
            return n
        }
        override fun read(): Int { val n = `in`.read(); if (n >= 0) add(1); return n }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(`in`.read(b, off, len))
        override fun skip(n: Long): Long {
            val buffer = ByteArray(minOf(n.coerceAtLeast(0), 8192).toInt())
            var total = 0L
            while (total < n && buffer.isNotEmpty()) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), n - total).toInt())
                if (read < 0) break
                total += read
            }
            return total
        }
    }
}
