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
import org.json.JSONObject
import org.json.JSONArray

internal data class PreparedTheme(
    val settings: AppearanceSettings, val directory: File,
    val warnings: List<String> = emptyList(),
) {
    fun discard() { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
}

/** Data-only v1-v4 SukiSU reader plus Ziyu's lossless appearance extension.
 * Format reference: SukiSU-Ultra 075ac81f1dfe58b3f59c665a1b07fda8b975015f.
 * Artwork is not bundled or relicensed; author and package license are retained.
 */
internal object ThemePackages {
    const val MAX_PACKAGE_BYTES = 128L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val MAX_JSON_BYTES = 64 * 1024
    private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    private const val MAX_IMPORTED_CARD_TRANSPARENCY = 50

    suspend fun prepare(context: Context, uri: Uri, style: UiStyle, name: String = "导入的主题",
                        author: String = "", license: String = ""): PreparedTheme = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "themes/pending-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val files = mutableMapOf<String, File>()
            var manifest: JSONObject? = null
            var expanded = 0L
            context.contentResolver.openInputStream(uri)?.use { original ->
                val input = original.buffered(64 * 1024)
                input.mark(8)
                val header = ByteArray(4)
                var received = 0
                while (received < 4) { val n = input.read(header, received, 4-received); if (n < 0) break; received += n }
                input.reset()
                val encrypted = received == 4 && header.contentEquals(byteArrayOf(83,75,84,72))
                val limited = LimitedThemeInput(input, MAX_PACKAGE_BYTES)
                val plain = if (encrypted) ThemePackageCrypto.decryptingStream(limited) else limited
                val bounded = LimitedThemeInput(plain, MAX_EXPANDED_BYTES)
                ZipInputStream(bounded).use { zip ->
                    var entries = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 128) { "主题包文件过多" }
                        val path = entry.name
                        require(!path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." }) { "主题包路径无效" }
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val imageRole = path.substringBeforeLast('.', path).takeIf {
                            '/' !in path && (it in setOf("background", "background_light", "background_dark", "card_background", "card_background_light", "card_background_dark") ||
                                Regex("card_sticker_(?:(?:light|dark)_)?[0-4]").matches(it))
                        }
                        val target = if (imageRole != null) File(dir, "$imageRole.image") else null
                        require(imageRole == null || imageRole !in files) { "主题包包含重复背景" }
                        require(path != "theme.json" || manifest == null) { "主题包包含重复配置" }
                        val jsonBytes = if (path == "theme.json") java.io.ByteArrayOutputStream() else null
                        var size = 0L
                        val sink = target?.outputStream()
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buffer); if (n < 0) break
                                size += n; expanded += n
                                require(expanded <= MAX_EXPANDED_BYTES) { "主题解压后过大" }
                                if (target != null) require(size <= MAX_IMAGE_BYTES) { "背景图片超过 32 MB" }
                                if (jsonBytes != null) {
                                    require(size <= MAX_JSON_BYTES) { "主题配置超过 64 KB" }
                                    jsonBytes.write(buffer, 0, n)
                                }
                                sink?.write(buffer, 0, n)
                            }
                        } finally { sink?.close() }
                        if (jsonBytes != null) manifest = JSONObject(jsonBytes.toString("UTF-8"))
                        if (imageRole != null && target != null) files[imageRole] = target
                        zip.closeEntry()
                    }
                    // ZipInputStream may stop at the central directory. Authentication must
                    // reach ciphertext EOF before any candidate can be applied.
                    val tail = ByteArray(64 * 1024)
                    while (bounded.read(tail) >= 0) currentCoroutineContext().ensureActive()
                }
            } ?: error("无法读取主题包")
            val root = manifest ?: error("主题包缺少 theme.json")
            require(root.optInt("version", 1) in 1..4) { "不支持此主题包版本" }
            // Decode once into bounded, owned candidate images before showing an apply button.
            // Applying only promotes these PNGs; no second image decode is necessary.
            files.keys.toList().forEach { role ->
                currentCoroutineContext().ensureActive()
                val raw = files.getValue(role)
                val normalized = File(dir, "$role.png")
                normalizeBackgroundFile(raw, normalized)
                raw.delete(); files[role] = normalized
            }
            val appearance = AppearanceOverrides.parse(root.optJSONObject("appearance"))
            val own = root.optJSONObject("ziyu")
            val defaults = AppearanceSettings(uiStyle = style)
            var settings = if (own != null) ThemeSettingsCodec.decode(own, defaults)
                else fromSuki(root, defaults).copy(overrides = appearance)
            fun background(data: JSONObject, role: String): String {
                val needed = data.optBoolean("hasBackground", files[role] != null)
                return if (!needed) "" else files[role]?.absolutePath ?: error("主题包缺少${if (role.endsWith("dark")) "深色" else ""}背景")
            }
            fun card(data: JSONObject, suffix: String): HomeCardDecoration {
                val ownCard = data.optJSONObject("ziyu")?.optJSONObject("homeCard")
                val base = if(ownCard == null) HomeCardDecoration(scrim = 0) else HomeCardDecoration.parse(ownCard, paths = false)
                val image = files["card_background$suffix"]
                val needed = data.optBoolean("hasCardBackground", image != null)
                require(!needed || image != null) { "主题包缺少首页卡片背景" }
                val transforms = ownCard?.optJSONArray("stickers") ?: data.optJSONArray("homeCardStickerTransforms") ?: JSONArray()
                require(transforms.length() <= CardSticker.MAX_COUNT) { "主题贴纸超过 5 张" }
                val prefix = "card_sticker" + suffix + "_"
                val indices = (0 until CardSticker.MAX_COUNT).filter { files.containsKey(prefix + it) }
                if (transforms.length() > 0) require(indices == (0 until transforms.length()).toList()) { "主题贴纸文件不完整" }
                return base.copy(backgroundPath = if(needed)image!!.absolutePath else "",
                    hideSample = ownCard?.optBoolean("hideSample") ?: data.optBoolean("hideHomeCardDecoration"),
                    hideLabel = ownCard?.optBoolean("hideLabel") ?: data.optBoolean("hideHomeCardStatusText"),
                    stickers = indices.map { i -> CardSticker.parse(files.getValue(prefix+i).absolutePath, transforms.optJSONObject(i)) })
            }
            val textJson = own?.optJSONObject("customTexts") ?: root.optJSONObject("customTexts") ?: JSONObject()
            if (nonBlankThemeText(textJson.opt("home.title")) == null) {
                nonBlankThemeText(root.opt("customHomeTitle"))?.let { textJson.put("home.title", it) }
            }
            settings = settings.copy(customTexts = ThemeTexts.parse(textJson),
                homeCard = if(root.optBoolean("adaptive", false)) HomeCardDecoration() else card(root, ""),
                backgroundPath = if(root.optBoolean("adaptive", false)) "" else background(root, "background"))
            if (root.optBoolean("adaptive", false)) {
                require(root.optInt("version", 1) >= 3) { "双模式主题的版本无效" }
                val lightJson = root.getJSONObject("light"); val darkJson = root.getJSONObject("dark")
                fun half(o: JSONObject, key: String): AppearanceVariant {
                    val z = o.optJSONObject("ziyu")
                    val variant = if (z != null) ThemeSettingsCodec.decodeVariant(z, settings) else fromSuki(o, settings).asVariant()
                    return variant.copy(backgroundPath = background(o, key), homeCard = card(o, if(key.endsWith("dark")) "_dark" else "_light"))
                }
                settings = settings.copy(themeMode = ThemeMode.SYSTEM, adaptive = AdaptiveAppearance(
                    half(lightJson, "background_light"), half(darkJson, "background_dark")))
            }
            val unsupportedTexts = textJson.keys().asSequence().count { ThemeTextKey.forKey(it) == null }
            val transparencyWasLimited = settings.cardTransparency > MAX_IMPORTED_CARD_TRANSPARENCY ||
                settings.adaptive?.let { it.light.cardTransparency > MAX_IMPORTED_CARD_TRANSPARENCY ||
                    it.dark.cardTransparency > MAX_IMPORTED_CARD_TRANSPARENCY } == true
            settings = settings.copy(
                cardTransparency = settings.cardTransparency.coerceAtMost(MAX_IMPORTED_CARD_TRANSPARENCY),
                adaptive = settings.adaptive?.let { adaptive -> adaptive.copy(
                    light = adaptive.light.copy(cardTransparency = adaptive.light.cardTransparency.coerceAtMost(MAX_IMPORTED_CARD_TRANSPARENCY)),
                    dark = adaptive.dark.copy(cardTransparency = adaptive.dark.cardTransparency.coerceAtMost(MAX_IMPORTED_CARD_TRANSPARENCY)),
                ) },
            )
            val warnings = buildList {
                if (transparencyWasLimited) add("为保证文字清晰，主题卡片透明度已限制在 50%。")
                if (unsupportedTexts > 0) add("已导入可用文字；$unsupportedTexts 项专属文字未应用。")
            }
            PreparedTheme(settings.copy(themeName = (nonBlankThemeText(root.opt("name")) ?: nonBlankThemeText(name) ?: "导入的主题").take(120),
                themeAuthor = (nonBlankThemeText(root.opt("author")) ?: nonBlankThemeText(author)).orEmpty().take(120),
                themeLicense = (nonBlankThemeText(root.opt("license")) ?: nonBlankThemeText(license)).orEmpty().take(200)), dir, warnings)
        } catch (error: Throwable) {
            dir.listFiles()?.forEach { it.delete() }; dir.delete(); throw error
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
        val seed = if(o.has("keyColor") && !o.isNull("keyColor")) o.optLong("keyColor").toInt() else null
        return base.copy(
            uiStyle = style,
            themeMode = when (mode) { 1,4 -> ThemeMode.LIGHT; 2,5,6 -> ThemeMode.DARK; else -> ThemeMode.SYSTEM },
            seedArgb = seed?.takeIf { it != 0 } ?: base.seedArgb,
            monetEnabled = seed?.let { it == 0 } ?: base.monetEnabled,
            nativeMonetEnabled = mode >= 3, amoledBlack = mode == 6,
            kolorStyle = KolorStyle.fromStorage(o.optString("colorStyle", base.kolorStyle.paletteName)),
            colorStandard = ColorStandard.fromStorage(o.optString("colorSpec", base.colorStandard.name)),
            cardTransparency = (100-o.optInt("cardBackgroundAlphaPercent", 100)).coerceIn(0,100),
            backgroundBlur = o.optInt("customBackgroundBlurPercent", 0).coerceIn(0,100),
            backgroundScrim = o.optInt("customBackgroundScrimPercent", 0).coerceIn(-100,100),
            backgroundScrimIsSigned = true,
            floatingDock = o.optBoolean("enableFloatingBottomBar", base.floatingDock),
            glassEnabled = o.optBoolean("enableFloatingBottomBarBlur", base.glassEnabled),
            blurEnabled = o.optBoolean("enableBlur", base.blurEnabled),
        ).normalized()
    }

    suspend fun export(context: Context, settings: AppearanceSettings, uri: Uri) = withContext(Dispatchers.IO) {
        // Prepare a complete archive before opening a potentially remote SAF destination.
        val file = File(context.cacheDir, "themes/export-${UUID.randomUUID()}.ziyu-theme")
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        try {
            val json = sukisuValues(settings).put("version", 4).put("name", settings.themeName.ifBlank { "我的主题" })
                .put("author", settings.themeAuthor).put("license", settings.themeLicense)
                .put("appearance", settings.overrides.toJson()).put("ziyu", ThemeSettingsCodec.encode(settings))
                .put("customHomeTitle", settings.customTexts["home.title"].orEmpty()).put("customTexts", ThemeTexts.json(settings.customTexts))
            settings.adaptive?.let { adaptive ->
                json.put("adaptive", true)
                json.put("hasBackground", false).put("hasCardBackground", false).put("homeCardStickerTransforms", JSONArray())
                json.put("light", sukisuValues(adaptive.light.applyTo(settings)).put("ziyu", ThemeSettingsCodec.encodeVariant(adaptive.light)))
                json.put("dark", sukisuValues(adaptive.dark.applyTo(settings)).put("ziyu", ThemeSettingsCodec.encodeVariant(adaptive.dark)))
            }
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("theme.json")); zip.write(json.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                suspend fun add(path: String, name: String) {
                    if (path.isBlank()) return
                    val background = File(path).canonicalFile
                    val owned = File(context.filesDir, "backgrounds").canonicalFile
                    require(background.parentFile == owned && background.isFile && background.length() <= MAX_IMAGE_BYTES) { "背景图片不可用，请重新选择" }
                    zip.putNextEntry(ZipEntry("$name.png"))
                    background.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0)break; zip.write(buffer,0,n) }
                    }
                    zip.closeEntry()
                }
                suspend fun artwork(card: HomeCardDecoration, suffix: String) {
                    add(card.backgroundPath, "card_background$suffix")
                    card.stickers.forEachIndexed { i, sticker -> add(sticker.path, "card_sticker" + suffix + "_" + i) }
                }
                if(settings.adaptive == null) { add(settings.backgroundPath, "background"); artwork(settings.homeCard, "") }
                settings.adaptive?.let {
                    add(it.light.backgroundPath, "background_light"); artwork(it.light.homeCard, "_light")
                    add(it.dark.backgroundPath, "background_dark"); artwork(it.dark.homeCard, "_dark")
                }
            }
            require(file.length() <= MAX_PACKAGE_BYTES) { "主题包超过 128 MB" }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0)break; output.write(buffer,0,n) }
                }
            } ?: error("无法保存主题包")
        } finally { file.delete() }
    }

    private fun sukisuValues(s: AppearanceSettings): JSONObject = JSONObject()
        .put("themeMode", when (s.themeMode) { ThemeMode.SYSTEM -> if(s.nativeMonetEnabled)3 else 0; ThemeMode.LIGHT -> if(s.nativeMonetEnabled)4 else 1; ThemeMode.DARK -> if(s.amoledBlack)6 else if(s.nativeMonetEnabled)5 else 2 })
        .put("miuixMonet", s.nativeMonetEnabled).put("keyColor", if(s.monetEnabled)0 else s.seedArgb).put("colorStyle", s.kolorStyle.paletteName)
        .put("colorSpec", s.colorStandard.name).put("cardBackgroundAlphaPercent", 100-s.cardTransparency)
        .put("customBackgroundBlurPercent", s.backgroundBlur).put("customBackgroundScrimPercent", s.backgroundScrim)
        .put("hasCardBackground", s.homeCard.backgroundPath.isNotBlank())
        .put("hideHomeCardStatusText", s.homeCard.hideLabel).put("hideHomeCardDecoration", s.homeCard.hideSample)
        .put("homeCardStickerTransforms", JSONArray().apply { s.homeCard.stickers.forEach { put(it.transformJson()) } })
        .put("hasBackground", s.backgroundPath.isNotBlank()).put("uiMode", if(s.uiStyle==UiStyle.MATERIAL)"material" else "miuix")
        .put("enableFloatingBottomBar", s.floatingDock).put("enableFloatingBottomBarBlur", s.glassEnabled).put("enableBlur", s.blurEnabled)

    private class LimitedThemeInput(input: InputStream, private val max: Long): FilterInputStream(input) {
        private var count = 0L
        private fun add(n: Int): Int { if(n>0) { count+=n; require(count<=max) { "主题包超过大小限制" } }; return n }
        override fun read(): Int { val n=`in`.read(); if(n>=0)add(1); return n }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(`in`.read(b,off,len))
        override fun skip(n: Long): Long {
            val buffer=ByteArray(minOf(n.coerceAtLeast(0),8192).toInt()); var total=0L
            while(total<n && buffer.isNotEmpty()) { val read=read(buffer,0,minOf(buffer.size.toLong(),n-total).toInt()); if(read<0)break; total+=read }
            return total
        }
    }
}
