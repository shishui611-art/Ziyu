/* WebGL dynamic wallpaper renderer and container validation adapted from
 * shirkneko/SukiSU 97d2442d (GPL-3.0-or-later); Ziyu adaptation GPL-3.0-only.
 * See THIRD_PARTY_NOTICES.md.
 */
@file:Suppress("DEPRECATION")

package io.github.xgl34222220.ziyu.ui.appearance

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DW_TAG = "ZiyuDynamicWallpaper"
private const val DW_BRIDGE_NAME = "DWBridge"
private const val DW_SNIFF_BYTES = 64
private const val DW_COPY_BUFFER = 256 * 1024
internal const val DYNAMIC_WALLPAPER_MAX_BYTES = 308L * 1024L * 1024L
internal const val DYNAMIC_WALLPAPER_STAGE_DIR = "dynamic_wallpaper_preview"

enum class DynamicWallpaperFit(val value: Int, val label: String, val jsName: String) {
    FIT(0, "完整适应", "fit"),
    FILL(1, "铺满画面", "fill"),
    CUSTOM(2, "自定义", "custom");

    companion object {
        fun fromValue(value: Int): DynamicWallpaperFit =
            entries.firstOrNull { it.value == value } ?: FILL
    }
}

@Immutable
data class DynamicWallpaperTransform(
    val fit: DynamicWallpaperFit = DynamicWallpaperFit.FILL,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun sanitized(): DynamicWallpaperTransform = if (fit == DynamicWallpaperFit.CUSTOM) {
        copy(
            zoom = zoom.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 1f,
            offsetX = offsetX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
            offsetY = offsetY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
        )
    } else {
        copy(zoom = 1f, offsetX = 0f, offsetY = 0f)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("fit", fit.value)
        .put("zoom", zoom.toDouble())
        .put("offsetX", offsetX.toDouble())
        .put("offsetY", offsetY.toDouble())

    companion object {
        fun parse(o: JSONObject?): DynamicWallpaperTransform {
            if (o == null) return DynamicWallpaperTransform()
            return DynamicWallpaperTransform(
                fit = DynamicWallpaperFit.fromValue(o.optInt("fit", DynamicWallpaperFit.FILL.value)),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                offsetX = o.optDouble("offsetX", 0.0).toFloat(),
                offsetY = o.optDouble("offsetY", 0.0).toFloat(),
            ).sanitized()
        }
    }
}

enum class DynamicWallpaperKind(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    WEBP("webp", "image/webp");

    companion object {
        fun fromFileName(name: String?): DynamicWallpaperKind? =
            when (name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
                MP4.extension -> MP4
                WEBP.extension -> WEBP
                else -> null
            }
    }
}

internal fun sniffDynamicWallpaperKind(header: ByteArray, length: Int): DynamicWallpaperKind? {
    fun matches(offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > length) return false
        for (i in text.indices) {
            if (header[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }
    if (matches(0, "RIFF") && matches(8, "WEBP")) return DynamicWallpaperKind.WEBP
    val lastOffset = (length - 4).coerceAtLeast(0)
    for (offset in 0..lastOffset) {
        if (matches(offset, "ftyp")) return DynamicWallpaperKind.MP4
    }
    return null
}

internal fun dynamicWallpaperKindOf(file: File): DynamicWallpaperKind? {
    if (!file.isFile || file.length() <= 0L) return null
    return runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(DW_SNIFF_BYTES)
            var length = 0
            while (length < header.size) {
                val read = input.read(header, length, header.size - length)
                if (read < 0) break
                length += read
            }
            if (length <= 0) null else sniffDynamicWallpaperKind(header, length)
        }
    }.getOrNull()
}

internal suspend fun importDynamicWallpaper(
    context: Context,
    uri: Uri,
    prefix: String,
    directory: File = File(context.filesDir, "backgrounds"),
): String = withContext(Dispatchers.IO) {
    val dir = directory.apply { check(isDirectory || mkdirs()) { "无法创建背景目录" } }
    val temp = File(dir, ".dynamic-${UUID.randomUUID()}.tmp")
    try {
        val header = ByteArray(DW_SNIFF_BYTES)
        var headerLen = 0
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().buffered(DW_COPY_BUFFER).use { output ->
                val buffer = ByteArray(DW_COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= DYNAMIC_WALLPAPER_MAX_BYTES) { "动态壁纸超过 308 MB 限制" }
                    if (headerLen < header.size) {
                        val take = minOf(header.size - headerLen, read)
                        System.arraycopy(buffer, 0, header, headerLen, take)
                        headerLen += take
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("无法读取所选文件")
        require(total > 0L) { "所选文件为空" }
        val kind = sniffDynamicWallpaperKind(header, headerLen)
            ?: error("仅支持 MP4 视频或动图 WebP 文件")
        val target = File(dir, "$prefix-${UUID.randomUUID()}.${kind.extension}")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.absolutePath
    } catch (error: Throwable) {
        temp.delete()
        if (error is CancellationException) throw error
        throw error
    }
}

internal suspend fun stageDynamicWallpaperPreview(context: Context, uri: Uri): String {
    val stageDir = File(context.cacheDir, DYNAMIC_WALLPAPER_STAGE_DIR).apply { mkdirs() }
    stageDir.listFiles()?.forEach { it.delete() }
    return importDynamicWallpaper(context, uri, "preview", stageDir)
}

internal fun resolveDynamicWallpaperRoute(context: Context, path: String): String? {
    if (path.isBlank()) return null
    val file = File(path).canonicalFile
    val backgrounds = File(context.filesDir, "backgrounds").canonicalFile
    val staged = File(context.cacheDir, DYNAMIC_WALLPAPER_STAGE_DIR).canonicalFile
    return when (file.parentFile) {
        backgrounds -> "applied/${file.name}"
        staged -> "preview/${file.name}"
        else -> null
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun DynamicWallpaperBackground(
    path: String,
    muted: Boolean,
    transform: DynamicWallpaperTransform = DynamicWallpaperTransform(),
    fps: Int = 0,
    speed: Float = 1f,
    blurPercent: Int = 0,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    onFrameSnapshot: ((Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val kind = remember(path) { DynamicWallpaperKind.fromFileName(File(path).name) } ?: return
    val mediaRelative = remember(context, path) { resolveDynamicWallpaperRoute(context, path) } ?: return
    val server = remember(path, kind) {
        runCatching {
            DynamicWallpaperServer(
                backgroundsDir = File(context.filesDir, "backgrounds"),
                previewDir = File(context.cacheDir, DYNAMIC_WALLPAPER_STAGE_DIR),
            )
        }.getOrNull()
    }
    if (server == null) {
        LaunchedEffect(path) { onError("server-start") }
        return
    }
    DisposableEffect(server) { onDispose { server.close() } }
    val mediaUrl = server.origin + mediaRelative
    val htmlTemplate = remember(context) {
        context.assets.open("dynamic_wallpaper.html").bufferedReader().use { it.readText() }
    }
    val mutedState = rememberUpdatedState(muted)
    val blurState = rememberUpdatedState(blurPercent.coerceIn(0, 100) / 100f)
    val transformState = rememberUpdatedState(transform.sanitized())
    val fpsState = rememberUpdatedState(fps)
    val speedState = rememberUpdatedState(speed)
    val onErrorState = rememberUpdatedState(onError)
    val onFrameSnapshotState = rememberUpdatedState(onFrameSnapshot)
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val snapshotBusy = remember { AtomicBoolean(false) }
    val snapshotExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ziyu-dw-snapshot").apply { isDaemon = true }
        }
    }
    DisposableEffect(snapshotExecutor) { onDispose { snapshotExecutor.shutdownNow() } }
    val isVideo = kind == DynamicWallpaperKind.MP4

    val bridge = remember {
        DynamicWallpaperBridge(
            onReadyCallback = {
                mainHandler.post {
                    val view = webViewRef.value ?: return@post
                    runCatching {
                        view.evaluateJavascript(
                            dwStateScript(
                                mutedState.value,
                                blurState.value,
                                transformState.value,
                                fpsState.value,
                                speedState.value,
                            ) + dwSnapshotScript(onFrameSnapshotState.value != null),
                            null,
                        )
                    }
                }
            },
            onErrorCallback = { reason ->
                mainHandler.post { onErrorState.value(reason) }
            },
            onFrameCallback = { dataUrl ->
                if (onFrameSnapshotState.value != null && snapshotBusy.compareAndSet(false, true)) {
                    runCatching {
                        snapshotExecutor.execute {
                            val bitmap = decodeDwFrame(dataUrl)
                            snapshotBusy.set(false)
                            if (bitmap != null) {
                                mainHandler.post {
                                    if (!snapshotExecutor.isShutdown) {
                                        onFrameSnapshotState.value?.invoke(bitmap)
                                    }
                                }
                            }
                        }
                    }.onFailure { snapshotBusy.set(false) }
                }
            },
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = webViewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view.onResume()
                    runCatching { view.evaluateJavascript("window.DW && window.DW.resume();", null) }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching {
                        view.evaluateJavascript("window.DW && window.DW.pause();") {
                            view.onPause()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    key(path, kind) {
        AndroidView<View>(
            modifier = modifier,
            factory = { viewContext ->
                runCatching {
                    server.setPageHtml(
                        buildDwHtml(
                            template = htmlTemplate,
                            sourceUrl = mediaUrl,
                            isVideo = isVideo,
                            muted = mutedState.value,
                            blur = blurState.value,
                            transform = transformState.value,
                            fps = fpsState.value,
                            speed = speedState.value,
                        ),
                    )
                    createDwWebView(
                        context = viewContext,
                        pageUrl = server.pageUrl,
                        bridge = bridge,
                        currentMuted = { mutedState.value },
                        currentBlur = { blurState.value },
                        currentTransform = { transformState.value },
                        currentFps = { fpsState.value },
                        currentSpeed = { speedState.value },
                        onFailure = { reason -> onErrorState.value(reason) },
                    )
                }.getOrElse { error ->
                    Log.w(DW_TAG, "Dynamic wallpaper WebView unavailable", error)
                    mainHandler.post {
                        onErrorState.value("webview-create: ${error.message ?: error.javaClass.simpleName}")
                    }
                    View(viewContext)
                }.also { view ->
                    if (view is WebView) webViewRef.value = view
                }
            },
            update = { view ->
                if (view is WebView) {
                    runCatching {
                        view.evaluateJavascript(
                            dwStateScript(
                                mutedState.value,
                                blurState.value,
                                transformState.value,
                                fpsState.value,
                                speedState.value,
                            ) + dwSnapshotScript(onFrameSnapshotState.value != null),
                            null,
                        )
                    }
                }
            },
            onRelease = { view ->
                if (view is WebView) {
                    if (webViewRef.value === view) webViewRef.value = null
                    destroyDwWebView(view)
                }
            },
        )
    }
}

private class DynamicWallpaperBridge(
    private val onReadyCallback: () -> Unit,
    private val onErrorCallback: (String) -> Unit,
    private val onFrameCallback: (String) -> Unit,
) {
    @JavascriptInterface
    fun onReady() = onReadyCallback()

    @JavascriptInterface
    fun onError(reason: String) = onErrorCallback(reason)

    @JavascriptInterface
    fun onFrame(dataUrl: String) = onFrameCallback(dataUrl)
}

private fun decodeDwFrame(dataUrl: String): Bitmap? = runCatching {
    val sep = dataUrl.indexOf(',')
    if (sep <= 0) null else {
        val bytes = Base64.decode(dataUrl.substring(sep + 1), Base64.DEFAULT)
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}.getOrNull()

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createDwWebView(
    context: Context,
    pageUrl: String,
    bridge: DynamicWallpaperBridge,
    currentMuted: () -> Boolean,
    currentBlur: () -> Float,
    currentTransform: () -> DynamicWallpaperTransform,
    currentFps: () -> Int,
    currentSpeed: () -> Float,
    onFailure: (String) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(Color.TRANSPARENT)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    isNestedScrollingEnabled = false
    isClickable = false
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    setOnTouchListener { _, _ -> true }
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        textZoom = 100
        offscreenPreRaster = true
        loadsImagesAutomatically = true
        setGeolocationEnabled(false)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
    }
    addJavascriptInterface(bridge, DW_BRIDGE_NAME)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        override fun onPageFinished(view: WebView, url: String) {
            runCatching {
                view.evaluateJavascript(
                    dwStateScript(currentMuted(), currentBlur(), currentTransform(), currentFps(), currentSpeed()),
                    null,
                )
            }
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onFailure("page-error")
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onFailure("renderer-gone")
            (view.parent as? ViewGroup)?.removeView(view)
            destroyDwWebView(view)
            return true
        }
    }
    loadUrl(pageUrl)
}

private fun destroyDwWebView(view: WebView) {
    runCatching {
        view.removeJavascriptInterface(DW_BRIDGE_NAME)
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.loadUrl("about:blank")
        view.destroy()
    }
}

private fun dwStateScript(
    muted: Boolean,
    blur: Float,
    transform: DynamicWallpaperTransform,
    fps: Int,
    speed: Float,
): String {
    val g = transform.sanitized()
    return "window.DW && (window.DW.setMuted(${if (muted) "true" else "false"}), " +
        "window.DW.setBlur(${String.format(Locale.US, "%.3f", blur)}), " +
        "window.DW.setTransform('${g.fit.jsName}', ${String.format(Locale.US, "%.3f", g.zoom)}, " +
        "${String.format(Locale.US, "%.3f", g.offsetX)}, ${String.format(Locale.US, "%.3f", g.offsetY)}), " +
        "window.DW.setPlayback(${fps.coerceIn(0, 120)}, ${String.format(Locale.US, "%.3f", speed.coerceIn(0.25f, 2f))}));"
}

private fun dwSnapshotScript(enabled: Boolean): String =
    "window.DW && window.DW.setFrameSnapshots(${if (enabled) "true" else "false"});"

private fun buildDwHtml(
    template: String,
    sourceUrl: String,
    isVideo: Boolean,
    muted: Boolean,
    blur: Float,
    transform: DynamicWallpaperTransform,
    fps: Int,
    speed: Float,
): String {
    val g = transform.sanitized()
    return template
        .replace("@SOURCE@", sourceUrl)
        .replace("@MODE@", if (isVideo) "video" else "image")
        .replace("@MUTED@", if (muted) "true" else "false")
        .replace("@BLUR@", String.format(Locale.US, "%.3f", blur))
        .replace("@FIT@", g.fit.jsName)
        .replace("@ZOOM@", String.format(Locale.US, "%.3f", g.zoom))
        .replace("@OFFSET_X@", String.format(Locale.US, "%.3f", g.offsetX))
        .replace("@OFFSET_Y@", String.format(Locale.US, "%.3f", g.offsetY))
        .replace("@FPS@", fps.coerceIn(0, 120).toString())
        .replace("@SPEED@", String.format(Locale.US, "%.3f", speed.coerceIn(0.25f, 2f)))
}

private class DynamicWallpaperServer(
    private val backgroundsDir: File,
    private val previewDir: File,
) : Closeable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val origin: String = "http://127.0.0.1:${serverSocket.localPort}/$token/"
    val pageUrl: String = origin + "index.html"

    @Volatile private var pageHtml: String = ""
    @Volatile private var closed = false
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    init {
        Thread({
            while (!closed) {
                val socket = try { serverSocket.accept() }
                catch (_: IOException) { break }
                catch (_: SecurityException) { break }
                connections.add(socket)
                Thread({
                    runCatching { handle(socket) }
                    connections.remove(socket)
                }, "ziyu-dw-http").apply { isDaemon = true }.start()
            }
        }, "ziyu-dw-accept").apply { isDaemon = true }.start()
    }

    fun setPageHtml(html: String) { pageHtml = html }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
                writeEmpty(output, "405 Method Not Allowed"); output.flush(); return
            }
            val includeBody = parts[0] == "GET"
            var rangeHeader: String? = null
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).equals("Range", ignoreCase = true)) {
                    rangeHeader = line.substring(colon + 1).trim()
                }
            }
            val path = parts[1].substringBefore('?').substringBefore('#')
            val prefix = "/$token/"
            if (!path.startsWith(prefix)) {
                writeEmpty(output, "404 Not Found"); output.flush(); return
            }
            val relative = path.removePrefix(prefix)
            when {
                relative == "index.html" -> writePage(output, includeBody)
                relative.startsWith("applied/") -> writeMedia(output, backgroundsDir, relative.removePrefix("applied/"), rangeHeader, includeBody)
                relative.startsWith("preview/") -> writeMedia(output, previewDir, relative.removePrefix("preview/"), rangeHeader, includeBody)
                else -> writeEmpty(output, "404 Not Found")
            }
            output.flush()
        }
    }

    private fun writePage(output: OutputStream, includeBody: Boolean) {
        val body = pageHtml.toByteArray(Charsets.UTF_8)
        if (body.isEmpty()) { writeEmpty(output, "503 Service Unavailable"); return }
        writeHeaders(output, "200 OK", "text/html; charset=utf-8", body.size.toLong(), listOf("Cache-Control" to "no-store"))
        if (includeBody) output.write(body)
    }

    private fun writeMedia(output: OutputStream, directory: File, name: String, rangeHeader: String?, includeBody: Boolean) {
        val safeName = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        val kind = DynamicWallpaperKind.fromFileName(name)
        if (!safeName || name.contains("..") || kind == null) {
            writeEmpty(output, "403 Forbidden"); return
        }
        val file = File(directory, name)
        val inside = runCatching { file.parentFile?.canonicalFile == directory.canonicalFile }.getOrDefault(false)
        if (!file.isFile || !inside || file.length() <= 0L) {
            writeEmpty(output, "404 Not Found"); return
        }
        val total = file.length()
        val range = rangeHeader?.let { parseRange(it, total) }
        if (rangeHeader != null && range == null) {
            writeHeaders(output, "416 Range Not Satisfiable", kind.mimeType, 0L, listOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes */$total"))
            return
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1L)
        val length = end - start + 1L
        val extra = mutableListOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        if (range != null) extra += "Content-Range" to "bytes $start-$end/$total"
        writeHeaders(output, if (range != null) "206 Partial Content" else "200 OK", kind.mimeType, length, extra)
        if (!includeBody) return
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    remaining -= read
                }
            }
        } catch (_: IOException) {
            // Stream closed by WebView seek/cancel.
        }
    }

    private fun writeHeaders(output: OutputStream, status: String, mime: String, length: Long, extra: List<Pair<String, String>>) {
        val sb = StringBuilder(256)
            .append("HTTP/1.1 ").append(status).append("\r\n")
            .append("Content-Type: ").append(mime).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Connection: close\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeEmpty(output: OutputStream, status: String) =
        writeHeaders(output, status, "text/plain; charset=utf-8", 0L, emptyList())

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(96)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b == '\r'.code) continue
            if (sb.length >= 8 * 1024) return null
            sb.append(b.toChar())
        }
    }

    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val ranges = value.substringAfter('=').split(',')
        if (ranges.size != 1) return null
        val part = ranges[0].trim()
        val dash = part.indexOf('-')
        if (dash < 0) return null
        val startText = part.substring(0, dash).trim()
        val endText = part.substring(dash + 1).trim()
        return try {
            if (startText.isEmpty()) {
                val suffix = endText.toLong().takeIf { it > 0L } ?: return null
                (total - suffix).coerceAtLeast(0L) to (total - 1L)
            } else {
                val start = startText.toLong()
                val end = if (endText.isEmpty()) total - 1L else endText.toLong()
                if (start !in 0L..<total) return null
                val boundedEnd = end.coerceAtMost(total - 1L)
                if (boundedEnd < start) null else start to boundedEnd
            }
        } catch (_: NumberFormatException) { null }
    }
}
