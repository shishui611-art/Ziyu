package io.github.xgl34222220.ziyu.legacy

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val APP_BRIDGE = "/data/adb/modules/ziyu/common/app_bridge.sh"
private const val PREVIEW_CACHE_MAX_FILES = 32
private const val PREVIEW_CACHE_MAX_BYTES = 384L * 1024L * 1024L
private const val PREVIEW_MEMORY_MAX_ENTRIES = 24
private const val PREVIEW_EXPORT_CONCURRENCY = 2

internal data class VariableAxisInfo(
    val tag: String,
    val min: Float,
    val default: Float,
    val max: Float,
)

internal data class WeightAxisInfo(
    val loading: Boolean = true,
    val hasWeight: Boolean = false,
    val min: Int = 100,
    val default: Int = 400,
    val max: Int = 900,
    val axes: List<VariableAxisInfo> = emptyList(),
    val error: String = "",
)

private data class PreviewTypefaceState(
    val typeface: Typeface? = null,
    val file: File? = null,
    val error: String = "",
)

private data class PreviewMemoryEntry(
    val typeface: Typeface,
    val file: File,
)

private class PreviewTextView(context: Context) : TextView(context) {
    private var lastText: String? = null
    private var lastTypeface: Typeface? = null
    private var lastGravity: Int = Int.MIN_VALUE
    private var lastMaxLines: Int = Int.MIN_VALUE
    private var lastColor: Int = Int.MIN_VALUE
    private var lastSizeSp: Float = Float.NaN

    init {
        includeFontPadding = false
        setSingleLine(false)
    }

    fun render(
        value: String,
        font: Typeface,
        gravityValue: Int,
        maxLinesValue: Int,
        color: Int,
        sizeSp: Float,
    ) {
        if (lastText != value) {
            text = value
            lastText = value
        }
        if (lastTypeface !== font) {
            typeface = font
            lastTypeface = font
        }
        if (lastGravity != gravityValue) {
            gravity = gravityValue
            lastGravity = gravityValue
        }
        if (lastMaxLines != maxLinesValue) {
            maxLines = maxLinesValue
            lastMaxLines = maxLinesValue
        }
        if (lastColor != color) {
            setTextColor(color)
            lastColor = color
        }
        if (lastSizeSp != sizeSp) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            lastSizeSp = sizeSp
        }
    }
}

private val previewMemoryCache = object : LruCache<String, PreviewMemoryEntry>(PREVIEW_MEMORY_MAX_ENTRIES) {}
private val previewCoverageCache = object : LruCache<String, Int>(256) {}

private fun previewCoverageGet(key: String): Int? = synchronized(previewCoverageCache) {
    previewCoverageCache.get(key)
}

private fun previewCoveragePut(key: String, value: Int) = synchronized(previewCoverageCache) {
    previewCoverageCache.put(key, value)
}
private val previewLocks = ConcurrentHashMap<String, Mutex>()
private val previewExportSemaphore = Semaphore(PREVIEW_EXPORT_CONCURRENCY)
private val axisInfoCache = ConcurrentHashMap<String, WeightAxisInfo>()
private val axisInfoLocks = ConcurrentHashMap<String, Mutex>()

private fun previewMemoryGet(key: String): PreviewMemoryEntry? = synchronized(previewMemoryCache) {
    previewMemoryCache.get(key)
}

private fun previewMemoryPut(key: String, entry: PreviewMemoryEntry) = synchronized(previewMemoryCache) {
    previewMemoryCache.put(key, entry)
}

@Composable
internal fun rememberWeightAxisInfo(font: FontItem?): WeightAxisInfo {
    val cached = remember(font?.id) { font?.id?.let(axisInfoCache::get) }
    val info by produceState(
        initialValue = cached ?: WeightAxisInfo(loading = font?.variable == true),
        key1 = font?.id,
        key2 = font?.variable,
    ) {
        value = when {
            font == null -> WeightAxisInfo(loading = false, error = "未选择字体")
            !font.variable -> WeightAxisInfo(loading = false, hasWeight = false)
            cached != null -> cached
            else -> {
                val lock = axisInfoLocks.computeIfAbsent(font.id) { Mutex() }
                lock.withLock {
                    axisInfoCache[font.id] ?: loadWeightAxisInfo(font).also { loaded ->
                        axisInfoCache[font.id] = loaded
                    }
                }
            }
        }
    }
    return info
}

private suspend fun loadWeightAxisInfo(font: FontItem): WeightAxisInfo = try {
    val command = "sh ${RootShell.quote(APP_BRIDGE)} weight_axis ${RootShell.quote(font.id)}"
    val result = RootShell.exec(command, timeoutMs = 25_000L)
    if (result.code != 0) {
        error(result.stderr.ifBlank { bridgeError(result.stdout, "字体轴读取失败") })
    }
    val jsonLine = result.stdout.lineSequence()
        .firstOrNull { it.trimStart().startsWith("{") }
        ?: error("未收到字体轴数据")
    val root = JSONObject(jsonLine.trim())
    if (root.optString("status") != "ok") {
        error(root.optString("message", "字体轴读取失败"))
    }
    val rawAxes = root.optJSONArray("axes")
    val axes = buildList {
        if (rawAxes != null) {
            for (index in 0 until rawAxes.length()) {
                val axis = rawAxes.optJSONObject(index) ?: continue
                val tag = axis.optString("tag").trim()
                val minimum = axis.optDouble("min", Double.NaN).toFloat()
                val maximum = axis.optDouble("max", Double.NaN).toFloat()
                val defaultValue = axis.optDouble("default", Double.NaN).toFloat()
                if (
                    tag.length == 4 &&
                    minimum.isFinite() &&
                    maximum.isFinite() &&
                    defaultValue.isFinite() &&
                    maximum >= minimum
                ) {
                    add(
                        VariableAxisInfo(
                            tag = tag,
                            min = minimum,
                            default = defaultValue.coerceIn(minimum, maximum),
                            max = maximum,
                        ),
                    )
                }
            }
        }
    }
    val weight = axes.firstOrNull { it.tag == "wght" }
    if (weight == null) {
        WeightAxisInfo(loading = false, hasWeight = false, axes = axes)
    } else {
        WeightAxisInfo(
            loading = false,
            hasWeight = true,
            min = weight.min.roundToInt(),
            default = weight.default.roundToInt(),
            max = weight.max.roundToInt(),
            axes = axes,
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    WeightAxisInfo(
        loading = false,
        hasWeight = false,
        error = error.message ?: "字体轴读取失败",
    )
}

@Composable
internal fun NativeFontPreview(
    font: FontItem?,
    text: String,
    axes: Map<String, Float> = emptyMap(),
    modifier: Modifier = Modifier,
    textSizeSp: Float = 25f,
    gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
    maxLines: Int = 2,
) {
    val context = LocalContext.current.applicationContext
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    val cleanAxes = remember(axes) { normalizePreviewAxes(axes) }
    val axisKey = remember(cleanAxes) {
        cleanAxes.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${formatAxisValue(it.value)}" }
    }
    val requestedWeight = remember(cleanAxes, font?.id, font?.weights) {
        val fallback = font?.let {
            io.github.xgl34222220.ziyu.legacy.ui.font.fontPreviewWeight(it).toFloat()
        } ?: 400f
        (cleanAxes["wght"] ?: fallback).roundToInt().coerceIn(1, 1000)
    }
    val sourceRevision = remember(font, requestedWeight) {
        font?.let {
            val staticRevision = if (it.variable) "" else "|wght=$requestedWeight"
            "${it.id}|${it.size}|${it.date}|face=${it.faceIndex}$staticRevision"
        }
    }
    val extension = remember(font?.format) {
        font?.format?.lowercase()
            ?.takeIf { it in setOf("ttf", "otf", "ttc") }
            ?: "ttf"
    }
    val cacheDir = remember(context.cacheDir) {
        File(context.cacheDir, "native-font-preview").apply { mkdirs() }
    }
    val target = remember(sourceRevision, extension) {
        sourceRevision?.let { File(cacheDir, "${stableKey(it)}.$extension") }
    }
    val memoryEntry = remember(sourceRevision) { sourceRevision?.let(::previewMemoryGet) }
    val previewKey = remember(sourceRevision, font?.valid, font?.error) {
        "${sourceRevision.orEmpty()}|${font?.valid}|${font?.error.orEmpty()}"
    }

    val preview by produceState(
        initialValue = memoryEntry?.let { PreviewTypefaceState(it.typeface, it.file) }
            ?: PreviewTypefaceState(),
        key1 = previewKey,
    ) {
        value = withContext(Dispatchers.IO) {
            when {
                font == null -> PreviewTypefaceState(error = "未选择字体")
                !font.valid -> PreviewTypefaceState(error = font.error.ifBlank { "字体无效" })
                sourceRevision == null || target == null -> PreviewTypefaceState(error = "字体索引无效")
                else -> {
                    val lock = previewLocks.computeIfAbsent(sourceRevision) { Mutex() }
                    lock.withLock {
                        previewMemoryGet(sourceRevision)?.let { cachedEntry ->
                            return@withLock PreviewTypefaceState(cachedEntry.typeface, cachedEntry.file)
                        }
                        previewExportSemaphore.withPermit {
                            try {
                                var exported = false
                                var renderFile = target
                                if (!target.isFile || target.length() == 0L) {
                                    val command = "sh ${RootShell.quote(APP_BRIDGE)} preview_export " +
                                        "${RootShell.quote(font.id)} ${RootShell.quote(target.absolutePath)} $requestedWeight"
                                    val result = RootShell.exec(command, timeoutMs = 25_000L)
                                    val jsonLine = result.stdout.lineSequence()
                                        .firstOrNull { it.trimStart().startsWith("{") }
                                    val root = jsonLine?.let { JSONObject(it.trim()) }
                                    if (result.code != 0 || root?.optString("status") != "ok") {
                                        error(
                                            root?.optString("message").orEmpty().ifBlank {
                                                result.stderr.ifBlank { "预览字体导出失败" }
                                            },
                                        )
                                    }
                                    if (!target.isFile || target.length() == 0L) {
                                        error("预览字体文件为空")
                                    }
                                    // 同 sha256 的字体只保留一份缓存，避免不同 id/时间重复复制大字体。
                                    val sha = root?.optJSONObject("data")?.optString("sha256").orEmpty()
                                    if (sha.length >= 16) {
                                        val shaFile = File(cacheDir, "sha256-${sha}.$extension")
                                        if (shaFile.isFile && shaFile.length() > 0L) {
                                            target.delete()
                                            renderFile = shaFile
                                        } else if (target.renameTo(shaFile)) {
                                            renderFile = shaFile
                                        }
                                    }
                                    exported = true
                                }
                                val loaded = if (font.faceIndex > 0) {
                                    Typeface.Builder(renderFile).setTtcIndex(font.faceIndex).build()
                                } else {
                                    Typeface.createFromFile(renderFile)
                                }
                                renderFile.setLastModified(System.currentTimeMillis())
                                if (exported) trimPreviewCache(cacheDir, renderFile)
                                previewMemoryPut(
                                    sourceRevision,
                                    PreviewMemoryEntry(typeface = loaded, file = renderFile),
                                )
                                PreviewTypefaceState(typeface = loaded, file = renderFile)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                PreviewTypefaceState(error = error.message ?: "预览字体加载失败")
                            }
                        }
                    }
                }
            }
        }
    }

    val previewFile = preview.file
    val variationSettings = remember(axisKey) {
        cleanAxes.takeIf { it.isNotEmpty() }?.let(::toAndroidVariationSettings).orEmpty()
    }
    val variationResult = remember(previewFile?.absolutePath, variationSettings, font?.variable, preview.typeface) {
        if (font?.variable != true || previewFile == null || variationSettings.isEmpty()) {
            Result.success(preview.typeface)
        } else {
            runCatching {
                Typeface.Builder(previewFile)
                    .setFontVariationSettings(variationSettings)
                    .build()
            }
        }
    }
    val variationError = variationResult.exceptionOrNull()?.message.orEmpty()
    val renderedTypeface = variationResult.fold(
        onSuccess = { it },
        onFailure = { preview.typeface },
    ) ?: Typeface.DEFAULT
    val failure = preview.error.ifBlank { variationError }
    val failed = failure.isNotBlank()
    val renderedText = if (failed) "预览失败 · $failure" else text
    val renderedColor = if (failed) errorColor else textColor
    val renderedSize = if (failed) minOf(textSizeSp, 12f) else textSizeSp
    // 用 Android 原生字形查询判断字体是否真的缺字；缺字时系统会静默回退，这里显式标出来。
    // 覆盖结果按「字体来源+变化轴+文本」缓存，避免滚动/重组时反复逐字查询。
    val coverageKey = remember(sourceRevision, axisKey, renderedText, failed) {
        if (failed || renderedText.isBlank()) "" else "${sourceRevision.orEmpty()}|$axisKey|${stableKey(renderedText)}"
    }
    val missingGlyphs = remember(renderedTypeface, coverageKey) {
        if (coverageKey.isEmpty()) 0
        else previewCoverageGet(coverageKey)
            ?: missingGlyphCount(renderedTypeface, renderedText).also { previewCoveragePut(coverageKey, it) }
    }

    androidx.compose.foundation.layout.Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext -> PreviewTextView(viewContext) },
            update = { view ->
                view.render(
                    value = renderedText,
                    font = renderedTypeface,
                    gravityValue = gravity,
                    maxLinesValue = maxLines,
                    color = renderedColor,
                    sizeSp = renderedSize,
                )
            },
        )
        if (missingGlyphs > 0) {
            Text(
                "缺字 $missingGlyphs · 系统回退",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 9.sp,
            )
        }
    }
}

private fun missingGlyphCount(typeface: Typeface, text: String): Int {
    val paint = Paint().apply { this.typeface = typeface }
    var missing = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (!Character.isWhitespace(codePoint) && !paint.hasGlyph(String(Character.toChars(codePoint)))) missing++
        index += Character.charCount(codePoint)
    }
    return missing
}

private fun normalizePreviewAxes(axes: Map<String, Float>): Map<String, Float> =    axes.filter { (tag, value) -> tag.length == 4 && value.isFinite() }

private fun toAndroidVariationSettings(axes: Map<String, Float>): String = axes.entries
    .sortedBy { it.key }
    .joinToString(", ") { (tag, value) -> "'$tag' ${formatAxisValue(value)}" }

private fun formatAxisValue(value: Float): String = if (value % 1f == 0f) {
    value.roundToInt().toString()
} else {
    value.toString().trimEnd('0').trimEnd('.')
}

private fun bridgeError(raw: String, fallback: String): String {
    val line = raw.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
        ?: return fallback
    return runCatching { JSONObject(line.trim()).optString("message", fallback) }
        .getOrDefault(fallback)
}

private fun stableKey(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.take(12).joinToString("") { byte -> "%02x".format(byte) }
}

private fun trimPreviewCache(directory: File, keep: File) {
    val files = directory.listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
    var keptFiles = 0
    var keptBytes = 0L
    files.forEach { file ->
        val required = file.absolutePath == keep.absolutePath
        val fits = keptFiles < PREVIEW_CACHE_MAX_FILES &&
            keptBytes + file.length() <= PREVIEW_CACHE_MAX_BYTES
        if (required || fits) {
            keptFiles += 1
            keptBytes += file.length()
        } else {
            file.delete()
        }
    }
}
