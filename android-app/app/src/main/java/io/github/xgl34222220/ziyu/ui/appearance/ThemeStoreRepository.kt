package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ThemeSource(
    val url: String,
    val accessCode: String = "",
    val id: String = "default",
    val name: String = "",
    val isDefault: Boolean = true,
) {
    fun displayName(): String = when {
        isDefault -> "默认来源"
        name.isNotBlank() -> name
        else -> url.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}

internal data class ThemeStoreSettings(
    val defaultAuto: Boolean = true,
    val defaultUrl: String = "",
    val defaultAccessCode: String = "",
    val extraSources: List<ThemeSource> = emptyList(),
    val consent: Boolean = false,
    val showR18: Boolean = false,
)

internal data class StoreTheme(
    val id: String,
    val version: Int = 1,
    val name: String,
    val author: String,
    val authorHash: String = "",
    val description: String,
    val license: String,
    val tags: List<String>,
    val path: String,
    val sha256: String,
    val size: Long,
    val format: Int,
    val preview: String,
    val avatar: String = "",
    val screenshots: List<String>,
    val downloads: Long,
    val updated: String,
    val changelog: String = "",
    val adaptive: Boolean,
    val dynamic: Boolean = false,
    val r18: Boolean = false,
    val heat: Long? = null,
    val source: ThemeSource = ThemeSource(ThemeStoreDefaults.BASE_URL),
) {
    val key: String get() = "${source.id}:$id"
    val searchBody: String = buildString {
        append(id).append(' ').append(name).append(' ').append(author).append(' ')
        append(description).append(' ').append(tags.joinToString(" ")).append(' ').append(source.name)
    }.lowercase(Locale.ROOT)
    val searchCompact: String = themeSearchKey(searchBody)
}

internal const val ADAPTIVE_THEME_TAG = "adaptive"
internal const val DYNAMIC_THEME_TAG = "dynamic"
internal const val R18_THEME_TAG = "r18"

private val ADAPTIVE_ALIASES = setOf("adaptive", "auto", "automatic", "lightdark", "light/dark", "自适应", "自适应主题", "自适应主题包")
private val DYNAMIC_ALIASES = setOf("dynamic", "live", "livewallpaper", "live-wallpaper", "animated", "动态", "动态壁纸", "动态背景")
private val R18_ALIASES = setOf("r18", "r-18", "18+", "nsfw", "adult", "成人", "成人向", "限制级")

internal fun canonicalThemeTag(tag: String): String = when (val key = tag.trim().lowercase(Locale.ROOT)) {
    in ADAPTIVE_ALIASES -> ADAPTIVE_THEME_TAG
    in DYNAMIC_ALIASES -> DYNAMIC_THEME_TAG
    in R18_ALIASES -> R18_THEME_TAG
    else -> key
}

internal fun themeTagDisplayLabel(tag: String): String = when (canonicalThemeTag(tag)) {
    ADAPTIVE_THEME_TAG -> "自适应主题"
    DYNAMIC_THEME_TAG -> "动态壁纸"
    R18_THEME_TAG -> "R18"
    else -> tag
}

internal fun StoreTheme.isAdaptiveTheme(): Boolean = adaptive || tags.any { canonicalThemeTag(it) == ADAPTIVE_THEME_TAG }
internal fun StoreTheme.isDynamicTheme(): Boolean = dynamic || format >= 6 || tags.any { canonicalThemeTag(it) == DYNAMIC_THEME_TAG }
internal fun StoreTheme.isR18Theme(): Boolean = r18 || tags.any { canonicalThemeTag(it) == R18_THEME_TAG }

internal fun themeSearchKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        .filter { Character.getType(it) !in setOf(Character.FORMAT.toInt(), Character.NON_SPACING_MARK.toInt()) }

internal data class ThemeTransfer(
    val done: Long,
    val total: Long,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
)

internal data class SourceHealthResult(
    val ok: Boolean,
    val status: String,
    val latencyMs: Long,
    val databaseLatencyMs: Long? = null,
    val themes: Int? = null,
    val pending: Int? = null,
    val version: String = "",
)

internal data class CatalogBatchResult(
    val themes: List<StoreTheme>,
    val activeSources: List<ThemeSource>,
    val fromCache: Boolean,
    val maintenance: Boolean,
    val failedSources: Int,
)

private class TransferSpeedMeter(
    private val totalBytes: Long,
    private val windowNanos: Long = 5_000_000_000L,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun update(transferred: Long, nowNanos: Long = System.nanoTime()): Pair<Long, Long?> {
        val clamped = transferred.coerceIn(0L, totalBytes.coerceAtLeast(0L))
        samples.addLast(nowNanos to clamped)
        while (samples.size > 2 && nowNanos - samples.first().first > windowNanos) samples.removeFirst()
        if (samples.size < 2) return 0L to null
        val first = samples.first()
        val last = samples.last()
        val elapsed = (last.first - first.first).coerceAtLeast(1L)
        val speed = ((last.second - first.second).coerceAtLeast(0L) * 1_000_000_000L) / elapsed
        val remaining = (totalBytes - last.second).coerceAtLeast(0L)
        return speed to (if (speed > 0L) remaining / speed else null)
    }
}

/** Catalog, mirror failover, CNB direct, SKM1 media and range download protocol aligned with
 * shirkneko/SukiSU 97d2442d. Fresh Ziyu implementation over HttpURLConnection + org.json:
 * strict HTTPS, cross-origin credential stripping, bounded streams, four range workers,
 * atomic chunk ledger, verified receipt and SHA-256 verification. No account or upload endpoints.
 */
internal class ThemeStoreRepository(private val context: Context) {
    private val access = ThemeStoreAccessRepository(context)
    private val config = File(context.noBackupFilesDir, "theme-store-source.json")
    private val cache = File(context.cacheDir, "theme-store").apply { mkdirs() }
    private val previewSlots = Semaphore(4)
    private val previewLocks = Array(16) { Mutex() }
    private val rangePattern = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")

    fun settings(): ThemeStoreSettings = runCatching {
        val o = JSONObject(config.readText())
        val consent = o.optInt("consent") == 1
        val showR18 = o.optBoolean("showR18", false)
        if (o.has("schema") && o.optInt("schema") >= 2) {
            val defaultAuto = o.optBoolean("defaultAuto", true)
            val rawDefaultUrl = o.optString("defaultUrl", "")
            val defaultCode = o.optString("defaultAccessCode", "")
            val extras = buildList {
                val arr = o.optJSONArray("extraSources") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val url = runCatching { normalize(item.optString("url")) }.getOrNull() ?: continue
                    val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val migrated = ThemeStoreDefaults.migrate(
                        ThemeSource(url, item.optString("accessCode"), id, item.optString("name"), isDefault = false),
                    )
                    add(migrated)
                }
            }.distinctBy { it.url }
            val migratedDefault = if (rawDefaultUrl.isNotBlank()) {
                ThemeStoreDefaults.migrate(ThemeSource(normalize(rawDefaultUrl), defaultCode))
            } else {
                ThemeSource("", defaultCode)
            }
            ThemeStoreSettings(defaultAuto, migratedDefault.url, migratedDefault.accessCode, extras, consent, showR18)
        } else {
            // Migrate legacy v1 single-source config cleanly.
            val rawUrl = o.optString("url", ThemeStoreDefaults.BASE_URL)
            val migrated = ThemeStoreDefaults.migrate(ThemeSource(normalize(rawUrl), o.optString("accessCode")))
            val isDefaultMirror = ThemeStoreDefaults.isBuiltInBaseUrl(migrated.url)
            ThemeStoreSettings(
                defaultAuto = isDefaultMirror,
                defaultUrl = if (isDefaultMirror) "" else migrated.url,
                defaultAccessCode = migrated.accessCode,
                extraSources = emptyList(),
                consent = consent,
                showR18 = showR18,
            )
        }
    }.getOrElse { ThemeStoreSettings() }

    fun activeSources(): List<ThemeSource> {
        val s = settings()
        val defaultUrl = if (s.defaultAuto || s.defaultUrl.isBlank()) {
            ThemeStoreDefaults.preferredBaseUrl(context)
        } else {
            runCatching { normalize(s.defaultUrl) }.getOrElse { ThemeStoreDefaults.preferredBaseUrl(context) }
        }
        val primary = ThemeStoreDefaults.migrate(
            ThemeSource(url = defaultUrl, accessCode = s.defaultAccessCode, id = "default", name = "默认来源", isDefault = true),
        )
        return listOf(primary) + s.extraSources.filter { it.url != primary.url }
    }

    fun source(): ThemeSource = activeSources().first()
    fun accepted(): Boolean = settings().consent
    fun showR18(): Boolean = settings().showR18

    fun accept() = saveSettings(settings().copy(consent = true))
    fun revokeConsent() = saveSettings(settings().copy(consent = false, showR18 = false))
    fun setShowR18(enabled: Boolean) {
        val current = settings()
        if (current.consent) saveSettings(current.copy(showR18 = enabled))
    }

    fun saveSettings(value: ThemeStoreSettings) {
        validateAccessCode(value.defaultAccessCode)
        val cleanDefaultUrl = if (value.defaultAuto || value.defaultUrl.isBlank()) "" else normalize(value.defaultUrl)
        val cleanExtras = value.extraSources.filter { it.url.isNotBlank() }.map { extra ->
            validateAccessCode(extra.accessCode)
            ThemeStoreDefaults.migrate(
                extra.copy(
                    id = extra.id.ifBlank { UUID.randomUUID().toString() },
                    name = extra.name.trim().take(60),
                    url = normalize(extra.url),
                    accessCode = extra.accessCode.trim(),
                    isDefault = false,
                ),
            )
        }.distinctBy { it.url }
        val arr = JSONArray()
        cleanExtras.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("url", e.url).put("accessCode", e.accessCode))
        }
        val root = JSONObject()
            .put("schema", 2)
            .put("defaultAuto", value.defaultAuto)
            .put("defaultUrl", cleanDefaultUrl)
            .put("defaultAccessCode", value.defaultAccessCode.trim())
            .put("extraSources", arr)
            .put("consent", if (value.consent) 1 else 0)
            .put("showR18", value.showR18)
        atomic(config, root.toString())
    }

    private fun validateAccessCode(raw: String) {
        val code = raw.trim()
        require(
            code.length <= 512 && code.none { it == '\r' || it == '\n' } &&
                !code.startsWith("sr_") && !code.startsWith("{"),
        ) { "请填写访问码，不能填写刷新密钥或凭证文件" }
    }

    suspend fun loadAllCatalogs(): CatalogBatchResult = withContext(Dispatchers.IO) {
        check(accepted()) { "请先同意连接主题来源" }
        trimCache()
        val configured = activeSources()
        val results = coroutineScope {
            configured.map { src -> async { loadSourceWithFailover(src) } }.awaitAll()
        }
        val successes = results.mapNotNull { (src, res) -> res.getOrNull()?.let { src to it } }
        if (successes.isEmpty()) {
            throw results.firstNotNullOfOrNull { it.second.exceptionOrNull() } ?: IOException("无法连接主题来源")
        }
        val live = successes.filterNot { it.second.fromCache }
        val maintenanceSet = live.ifEmpty { successes }
        val maintenance = maintenanceSet.isNotEmpty() && maintenanceSet.all { it.second.maintenance }
        val themes = successes
            .filterNot { it.second.maintenance }
            .flatMap { (_, load) -> load.themes }
            .distinctBy { it.key }
        CatalogBatchResult(
            themes = themes,
            activeSources = results.map { it.first },
            fromCache = successes.any { it.second.fromCache },
            maintenance = maintenance,
            failedSources = results.size - successes.size,
        )
    }

    private data class SingleCatalogLoad(
        val themes: List<StoreTheme>,
        val fromCache: Boolean,
        val notModified: Boolean = false,
        val maintenance: Boolean = false,
    )

    private suspend fun loadSourceWithFailover(source: ThemeSource): Pair<ThemeSource, Result<SingleCatalogLoad>> {
        val first = runCatching { fetchSingleCatalog(source) }
        if (first.getOrNull()?.fromCache == false) {
            ThemeStoreDefaults.reportReachable(source.url)
            return source to first
        }
        val firstErr = first.exceptionOrNull()
        if (firstErr is IllegalStateException || firstErr?.message?.contains("401") == true || firstErr?.message?.contains("403") == true) {
            return source to first
        }
        if (!source.isDefault || !ThemeStoreDefaults.isBuiltInBaseUrl(source.url)) {
            return source to first
        }
        var previousUrl = source.url
        var nextUrl = ThemeStoreDefaults.fallbackBaseUrl(context, previousUrl)
        var bestSource = source
        var bestResult = first
        while (nextUrl != null) {
            ThemeStoreDefaults.reportUnreachable(context, previousUrl)
            val candidate = source.copy(url = nextUrl)
            val attempt = runCatching { fetchSingleCatalog(candidate) }
            if (attempt.getOrNull()?.fromCache == false) {
                ThemeStoreDefaults.reportReachable(nextUrl)
                return candidate to attempt
            }
            if (bestResult.isFailure && attempt.isSuccess) {
                bestSource = candidate
                bestResult = attempt
            }
            previousUrl = nextUrl
            nextUrl = ThemeStoreDefaults.fallbackBaseUrl(context, nextUrl)
        }
        return bestSource to bestResult
    }

    suspend fun testCatalog(source: ThemeSource): Triple<Int, Boolean, Boolean> = withContext(Dispatchers.IO) {
        val load = fetchSingleCatalog(source, allowUnaccepted = true)
        Triple(load.themes.size, load.fromCache, load.notModified)
    }

    private suspend fun fetchSingleCatalog(source: ThemeSource, allowUnaccepted: Boolean = false): SingleCatalogLoad {
        if (!allowUnaccepted) check(accepted()) { "请先同意连接主题来源" }
        val key = digest(source.url.toByteArray()).take(24)
        val catalogFile = File(cache, "catalog-$key.json")
        val etagFile = File(cache, "catalog-$key.etag")
        val cachedEtag = etagFile.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }?.takeIf { it.isNotEmpty() }
        val cachedRaw = if (catalogFile.isFile) runCatching { catalogFile.readText() }.getOrNull() else null
        val extraHeaders = if (cachedRaw != null && cachedEtag != null) mapOf("If-None-Match" to cachedEtag) else emptyMap()
        return try {
            connection(source, "catalog.json", extraHeaders = extraHeaders, allowUnaccepted = allowUnaccepted).useConnection { conn ->
                val code = conn.responseCode
                if (code == 304 && cachedRaw != null) {
                    val parsed = parseCatalog(cachedRaw, source)
                    return@useConnection SingleCatalogLoad(parsed.first, fromCache = false, notModified = true, maintenance = parsed.second)
                }
                require(code == 200) { httpError(code) }
                val raw = conn.inputStream.use { readLimited(it, 8L * 1024 * 1024).toString(Charsets.UTF_8) }
                val parsed = parseCatalog(raw, source)
                atomic(catalogFile, raw)
                conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }?.let { atomic(etagFile, it) }
                SingleCatalogLoad(parsed.first, fromCache = false, notModified = false, maintenance = parsed.second)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val isAuthError = error is IllegalStateException ||
                error.message?.startsWith("HTTP 401") == true || error.message?.startsWith("HTTP 403") == true
            val usableCache = if (isAuthError || cachedRaw == null ||
                System.currentTimeMillis() - catalogFile.lastModified() >= 7L * 24 * 60 * 60 * 1000L
            ) null else cachedRaw
            if (usableCache != null) {
                val parsed = parseCatalog(usableCache, source)
                SingleCatalogLoad(parsed.first, fromCache = true, maintenance = parsed.second)
            } else {
                throw error
            }
        }
    }

    suspend fun checkHealth(source: ThemeSource): SourceHealthResult = withContext(Dispatchers.IO) {
        val base = URI(normalize(source.url))
        val target = base.resolve("health")
        val started = System.nanoTime()
        val conn = target.toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Ziyu/theme-compatible")
            conn.setRequestProperty("Cache-Control", "no-cache")
            val token = runCatching { access.resolve(source) }.getOrDefault(source.accessCode)
            if (token.isNotBlank()) conn.setRequestProperty(TOKEN_HEADER, token)
            val code = conn.responseCode
            val latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { readLimited(it, 64 * 1024).toString(Charsets.UTF_8) }.orEmpty().trim()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && (obj.has("ok") || obj.has("status"))) {
                val checks = obj.optJSONObject("checks")
                val db = checks?.optJSONObject("database")
                val cat = checks?.optJSONObject("catalog")
                val pending = checks?.optJSONObject("pending")
                SourceHealthResult(
                    ok = obj.optBoolean("ok", code in 200..299),
                    status = obj.optString("status").ifBlank { if (obj.optBoolean("ok")) "ok" else "down" },
                    latencyMs = latencyMs,
                    databaseLatencyMs = db?.takeIf { it.has("latencyMs") }?.optLong("latencyMs"),
                    themes = cat?.takeIf { it.has("themes") }?.optInt("themes"),
                    pending = pending?.takeIf { it.has("count") }?.optInt("count"),
                    version = obj.optString("version"),
                )
            } else {
                throw IOException("健康检查失败（HTTP $code）")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCatalog(raw: String, source: ThemeSource): Pair<List<StoreTheme>, Boolean> {
        val o = JSONObject(raw)
        require(o.optInt("schema", 1) == 1) { "不支持此主题来源版本" }
        val maintenance = o.optBoolean("maintenance", false)
        if (maintenance) return emptyList<StoreTheme>() to true
        val entries = o.optJSONArray("themes") ?: error("主题目录缺失")
        require(entries.length() <= 10000) { "主题目录过大" }
        val list = buildList {
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val id = e.optString("id")
                if (!idPattern.matches(id)) continue
                val tags = strings(e.optJSONArray("tags"), 20, 40)
                add(
                    StoreTheme(
                        id = id,
                        version = e.optInt("version", 1).coerceAtLeast(1),
                        name = (nonBlankThemeText(e.opt("name")) ?: "未命名主题").take(120),
                        author = nonBlankThemeText(e.opt("author")).orEmpty().take(120),
                        authorHash = nonBlankThemeText(e.opt("authorHash")).orEmpty().take(128),
                        description = nonBlankThemeText(e.opt("description")).orEmpty().take(2000),
                        license = nonBlankThemeText(e.opt("license")).orEmpty().take(200),
                        tags = tags,
                        path = e.optString("path"),
                        sha256 = e.optString("sha256").lowercase(Locale.ROOT),
                        size = e.optLong("size"),
                        format = e.optInt("formatVersion", 1),
                        preview = e.optString("previewPath"),
                        avatar = e.optString("avatarPath"),
                        screenshots = strings(e.optJSONArray("screenshotPaths"), 12, 512),
                        downloads = e.optLong("downloads").coerceAtLeast(0),
                        updated = nonBlankThemeText(e.opt("updatedAt")).orEmpty().take(40),
                        changelog = nonBlankThemeText(e.opt("changelog")).orEmpty().take(2000),
                        adaptive = e.optBoolean("adaptive"),
                        dynamic = e.optBoolean("dynamic"),
                        r18 = e.optBoolean("r18"),
                        heat = if (e.has("heat") && !e.isNull("heat")) e.optLong("heat", -1).takeIf { it >= 0 } else null,
                        source = source,
                    ),
                )
            }
        }.distinctBy { it.key }
        return list to false
    }

    suspend fun preview(source: ThemeSource, path: String): File? = previewSlots.withPermit {
        withContext(Dispatchers.IO) {
            if (path.isBlank() || !accepted()) return@withContext null
            previewLocks[((source.url + path).hashCode().ushr(1)) % previewLocks.size].withLock {
                val target = File(cache, "preview-${digest((source.url + path).toByteArray()).take(32)}.image")
                if (target.isFile && target.length() in 1..(8L * 1024 * 1024)) return@withContext target
                val part = File(cache, "${target.name}.part")
                try {
                    val bytes = fetchPreviewBytes(source, path, direct = true)
                        ?: fetchPreviewBytes(source, path, direct = false)
                        ?: return@withContext null
                    part.writeBytes(bytes)
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    target
                } finally {
                    part.delete()
                }
            }
        }
    }

    private suspend fun fetchPreviewBytes(source: ThemeSource, path: String, direct: Boolean): ByteArray? = runCatching {
        val headers = if (direct) mapOf(MEDIA_DIRECT_HEADER to MEDIA_DIRECT_VALUE) else emptyMap()
        val raw = connection(source, path, extraHeaders = headers, allowCrossOriginHttpsRedirect = true).useConnection { c ->
            require(c.responseCode == 200) { httpError(c.responseCode) }
            c.inputStream.use { readLimited(it, 8L * 1024 * 1024) }
        }
        if (ThemeMediaCrypto.isSealed(raw)) {
            runCatching { ThemeMediaCrypto.decrypt(raw) }.getOrNull()
        } else {
            raw
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() && ThemeDownloadStore.previewExtension(it) != null }

    suspend fun download(theme: StoreTheme, progress: (ThemeTransfer) -> Unit): File = withContext(Dispatchers.IO) {
        check(accepted()) { "请先同意连接主题来源" }
        val source = theme.source
        require(theme.format in ThemePackages.MIN_SUPPORTED_FORMAT..ThemePackages.MAX_SUPPORTED_FORMAT) {
            "暂不支持此主题版本（v${theme.format}）"
        }
        require(theme.size in 1..ThemePackages.MAX_PACKAGE_BYTES && Regex("[0-9a-f]{64}").matches(theme.sha256)) {
            "主题大小或校验信息无效"
        }
        val target = File(cache, "${theme.id}-v${theme.version}-${theme.sha256.take(16)}.sukisu")
        if (isVerifiedReceipt(target, theme.size, theme.sha256) ||
            (target.isFile && target.length() == theme.size && hashFile(target) == theme.sha256)
        ) {
            writeVerifiedReceipt(target, theme.size, theme.sha256)
            progress(ThemeTransfer(theme.size, theme.size))
            return@withContext target
        }
        target.delete()
        verifiedReceiptFile(target).delete()

        val part = File(cache, "${target.name}.part")
        val meta = File(cache, "${target.name}.part.meta")
        val meter = TransferSpeedMeter(theme.size)
        val transferred = AtomicLong(0L)
        var lastProgressAt = 0L

        fun report(delta: Long, base: Long = 0L, force: Boolean = false) {
            val moved = transferred.addAndGet(delta)
            synchronized(transferred) {
                val now = System.nanoTime()
                if (force || now - lastProgressAt >= 120_000_000L) {
                    lastProgressAt = now
                    val totalMoved = (base + moved).coerceIn(0L, theme.size)
                    val (speed, eta) = meter.update(totalMoved, now)
                    progress(ThemeTransfer(totalMoved, theme.size, speed, eta))
                }
            }
        }

        val useDirectCnb = !meta.isFile && (!part.isFile || part.length() == 0L) && probeDirectCnb(source, theme.path)
        if (useDirectCnb) {
            downloadSequential(source, theme, part) { n -> report(n) }
        } else {
            val probe = connection(source, theme.path, range = "bytes=0-0", allowCrossOriginHttpsRedirect = true)
            val ranges: Boolean
            val etag: String?
            try {
                val code = probe.responseCode
                ranges = code == 206 && probe.getHeaderField("Content-Range") == "bytes 0-0/${theme.size}"
                require(code == 200 || ranges) { httpError(code) }
                etag = probe.getHeaderField("ETag")?.takeIf { !it.startsWith("W/") }
            } finally {
                probe.disconnect()
            }
            if (ranges && theme.size >= 1024 * 1024) {
                val chunk = 2L * 1024 * 1024
                val count = ((theme.size + chunk - 1) / chunk).toInt()
                val fingerprint = digest((source.url + theme.path + theme.sha256 + theme.size).toByteArray())
                val old = runCatching { JSONObject(meta.readText()) }.getOrNull()
                val completed = mutableSetOf<Int>()
                if (part.isFile && part.length() == theme.size && old?.optString("fingerprint") == fingerprint) {
                    old.optJSONArray("completed")?.let { a ->
                        for (i in 0 until a.length()) a.optInt(i, -1).takeIf { it in 0 until count }?.let(completed::add)
                    }
                } else {
                    part.delete(); meta.delete()
                }
                RandomAccessFile(part, "rw").use { it.setLength(theme.size) }
                val base = completed.sumOf { minOf(chunk, theme.size - it * chunk) }
                if (base > 0L) report(0L, base, force = true)
                val slots = Semaphore(4)
                coroutineScope {
                    (0 until count).filter { it !in completed }.map { index ->
                        async {
                            slots.withPermit {
                                val from = index * chunk
                                val end = minOf(theme.size, from + chunk) - 1
                                var attempt = 0
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    var moved = 0L
                                    try {
                                        connection(source, theme.path, range = "bytes=$from-$end", etag = etag, allowCrossOriginHttpsRedirect = true).useConnection { c ->
                                            require(c.responseCode == 206) { "主题来源未按范围返回文件，请重试" }
                                            val match = rangePattern.matchEntire(c.getHeaderField("Content-Range").orEmpty())
                                            require(match?.groupValues?.drop(1)?.map { it.toLong() } == listOf(from, end, theme.size)) {
                                                "主题下载范围不一致"
                                            }
                                            c.inputStream.use { input ->
                                                RandomAccessFile(part, "rw").use { output ->
                                                    output.seek(from)
                                                    val buffer = ByteArray(64 * 1024)
                                                    while (moved < end - from + 1) {
                                                        currentCoroutineContext().ensureActive()
                                                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), end - from + 1 - moved).toInt())
                                                        require(n > 0) { "主题下载中断" }
                                                        output.write(buffer, 0, n)
                                                        moved += n
                                                        report(n.toLong(), base)
                                                    }
                                                    require(input.read() == -1) { "主题下载长度不一致" }
                                                    output.fd.sync()
                                                }
                                            }
                                        }
                                        synchronized(completed) {
                                            completed.add(index)
                                            atomic(meta, JSONObject().put("fingerprint", fingerprint).put("completed", JSONArray(completed.sorted())).toString())
                                        }
                                        break
                                    } catch (c: CancellationException) {
                                        throw c
                                    } catch (error: Exception) {
                                        report(-moved, base)
                                        if (++attempt >= 3) throw error
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            } else {
                meta.delete()
                downloadSequential(source, theme, part) { n -> report(n) }
            }
        }
        currentCoroutineContext().ensureActive()
        if (part.length() != theme.size || hashFile(part) != theme.sha256) {
            part.delete(); meta.delete(); error("主题文件校验失败，请重新下载")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        meta.delete()
        writeVerifiedReceipt(target, theme.size, theme.sha256)
        progress(ThemeTransfer(theme.size, theme.size))
        target
    }

    private suspend fun probeDirectCnb(source: ThemeSource, path: String): Boolean = runCatching {
        connection(source, path, method = "HEAD", allowCrossOriginHttpsRedirect = true).useConnection { c ->
            val host = c.url.host.orEmpty().lowercase(Locale.ROOT)
            c.responseCode in 200..299 && (host == "cnb.cool" || host.endsWith(".cnb.cool"))
        }
    }.getOrDefault(false)

    private suspend fun downloadSequential(source: ThemeSource, theme: StoreTheme, part: File, onBytes: (Long) -> Unit) {
        connection(source, theme.path, allowCrossOriginHttpsRedirect = true).useConnection { c ->
            require(c.responseCode == 200) { httpError(c.responseCode) }
            c.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= theme.size) { "主题下载长度不一致" }
                        output.write(buffer, 0, n); onBytes(n.toLong())
                    }
                    require(count == theme.size) { "主题下载不完整" }
                }
            }
        }
    }

    suspend fun reconnect(source: ThemeSource) = withContext(Dispatchers.IO) {
        access.reconnect(source)
    }

    private suspend fun connection(
        source: ThemeSource,
        path: String,
        range: String? = null,
        etag: String? = null,
        method: String = "GET",
        extraHeaders: Map<String, String> = emptyMap(),
        allowCrossOriginHttpsRedirect: Boolean = false,
        allowUnaccepted: Boolean = false,
    ): HttpURLConnection {
        if (!allowUnaccepted) check(accepted()) { "请先同意连接主题来源" }
        var token = access.resolve(source)
        var conn = openConnection(source, token, path, range, etag, method, extraHeaders, allowCrossOriginHttpsRedirect)
        if (conn.responseCode == 401 && access.automatic(source)) {
            conn.disconnect()
            token = access.refresh(source, token)
            conn = openConnection(source, token, path, range, etag, method, extraHeaders, allowCrossOriginHttpsRedirect)
        }
        try {
            if (conn.responseCode in setOf(401, 403) && access.automatic(source)) {
                access.reject(source, token)
                error("主题访问授权已失效，请在主题来源中重新授权")
            }
            return conn
        } catch (error: Throwable) {
            conn.disconnect(); throw error
        }
    }

    private fun openConnection(
        source: ThemeSource,
        token: String,
        path: String,
        range: String?,
        etag: String?,
        method: String,
        extraHeaders: Map<String, String>,
        allowCrossOriginHttpsRedirect: Boolean,
    ): HttpURLConnection {
        val base = URI(normalize(source.url))
        val relative = URI(path)
        require(
            !relative.isAbsolute && relative.rawAuthority == null && relative.rawQuery == null && relative.rawFragment == null &&
                !path.startsWith('/') && '\\' !in path && relative.path.split('/').none { it == ".." || it == "." },
        ) { "主题文件地址无效" }
        var url = base.resolve(relative)
        repeat(6) { redirects ->
            require(url.scheme == "https" && !url.host.isNullOrBlank()) { "主题来源的跳转地址无效" }
            val sameOrigin = url.scheme == base.scheme && url.host == base.host && effectivePort(url) == effectivePort(base)
            if (!allowCrossOriginHttpsRedirect) {
                require(sameOrigin && url.path.startsWith(base.path)) { "主题来源的跳转地址无效" }
            }
            val c = url.toURL().openConnection() as HttpURLConnection
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Ziyu/theme-compatible")
            c.setRequestProperty("Accept-Encoding", "identity")
            // Strictly strip custom store credentials and media-direct headers on cross-origin redirects (e.g. CNB CDN).
            if (sameOrigin && token.isNotEmpty()) c.setRequestProperty(TOKEN_HEADER, token)
            extraHeaders.forEach { (k, v) ->
                if (sameOrigin || (k != TOKEN_HEADER && k != MEDIA_DIRECT_HEADER)) {
                    c.setRequestProperty(k, v)
                }
            }
            if (range != null) c.setRequestProperty("Range", range)
            if (etag != null && sameOrigin) c.setRequestProperty("If-Range", etag)
            try {
                if (c.responseCode !in setOf(301, 302, 303, 307, 308)) return c
                require(redirects < 5) { "主题来源跳转过多" }
                url = url.resolve(c.getHeaderField("Location") ?: error("主题来源跳转无效"))
            } catch (e: Exception) {
                c.disconnect(); throw e
            }
            c.disconnect()
        }
        error("主题来源跳转无效")
    }

    private fun effectivePort(uri: URI): Int = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80

    private fun verifiedReceiptFile(file: File): File = File(file.parentFile, "${file.name}.verified")
    private fun writeVerifiedReceipt(file: File, size: Long, sha: String) {
        runCatching { verifiedReceiptFile(file).writeText("$size ${sha.lowercase(Locale.ROOT)}") }
    }
    private fun isVerifiedReceipt(file: File, size: Long, sha: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val text = runCatching { verifiedReceiptFile(file).readText().trim() }.getOrNull() ?: return false
        return text == "$size ${sha.lowercase(Locale.ROOT)}"
    }

    private suspend fun readLimited(input: InputStream, max: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer); if (n < 0) break
            count += n; require(count <= max) { "主题来源返回的文件过大" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private suspend fun hashFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use {
            val b = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(b); if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trimCache() {
        val files = cache.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        files.forEach {
            if (size > 256L * 1024 * 1024 && System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L) {
                val bytes = it.length()
                if (it.delete()) size -= bytes
            }
        }
    }

    private fun strings(a: JSONArray?, max: Int, length: Int): List<String> = if (a == null) emptyList() else
        (0 until minOf(a.length(), max)).map { a.optString(it).take(length) }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect() }

    companion object {
        const val TOKEN_HEADER = "X-Sukisu-Theme-Token"
        const val MEDIA_DIRECT_HEADER = "X-Sukisu-Media-Direct"
        const val MEDIA_DIRECT_VALUE = "skm1"

        fun normalize(raw: String): String {
            val trimmed = raw.trim()
            val withScheme = if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) trimmed else "https://$trimmed"
            val uri = URI(withScheme)
            require(
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null && uri.normalize() == uri,
            ) { "请输入有效的 HTTPS 主题来源地址" }
            return uri.toASCIIString().trimEnd('/') + "/"
        }

        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun httpError(code: Int) = when (code) {
            401, 403 -> "HTTP $code：主题来源拒绝访问，请检查访问码或重新授权"
            else -> "HTTP $code：主题来源暂时不可用"
        }
        private fun atomic(file: File, text: String) {
            file.parentFile?.mkdirs()
            val a = AtomicFile(file)
            val output = a.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); a.finishWrite(output) }
            catch (e: Throwable) { a.failWrite(output); throw e }
        }
    }
}
