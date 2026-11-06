package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Decode by content and cap both input bytes and decoded bitmap allocation. */
internal suspend fun importBackground(context: Context, uri: Uri, directory: File = File(context.filesDir, "backgrounds")): String = withContext(Dispatchers.IO) {
    val dir = directory.apply { mkdirs() }
    val input = File(dir, ".import-${UUID.randomUUID()}")
    val output = File(dir, "background-${UUID.randomUUID()}.png")
    try {
        context.contentResolver.openInputStream(uri)?.use { src ->
            input.outputStream().use { dst ->
                val buf = ByteArray(65536); var total = 0L
                while (true) {
                    val size = src.read(buf); if (size < 0) break
                    total += size; require(total <= 32L * 1024 * 1024) { "图片超过 32 MB，请先缩小" }
                    dst.write(buf, 0, size)
                }
            }
        } ?: error("无法读取图片")
        normalizeBackgroundFile(input, output)
        output.absolutePath
    } catch (error: Throwable) {
        output.delete(); throw error
    } finally { input.delete() }
}

internal fun normalizeBackgroundFile(input: File, output: File) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, info, _ ->
            val width = info.size.width; val height = info.size.height
            require(width > 0 && height > 0) { "图片尺寸无效" }
            val scale = minOf(1.0, 2048.0 / maxOf(width, height))
            decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            output.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败" } }
        } finally { bitmap.recycle() }
}

internal data class BackgroundFrame(val path: String, val bitmap: ImageBitmap?)

internal val LocalBackgroundFrame = staticCompositionLocalOf<State<BackgroundFrame?>?> { null }

/** One bounded decode per theme scope, shared by the current and preview pages. */
@Composable
internal fun rememberBackgroundFrame(path: String): State<BackgroundFrame?> =
    produceState<BackgroundFrame?>(null, path) {
        val bitmap = if (path.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.isFile) null
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        }
        value = BackgroundFrame(path, bitmap)
    }

@Composable
internal fun BackgroundBackdrop() {
    val settings = LocalAppearanceSettings.current
    val frame by (LocalBackgroundFrame.current ?: rememberBackgroundFrame(settings.backgroundPath))
    // A new path must never temporarily display the previous theme's bitmap.
    val bitmap = frame?.takeIf { it.path == settings.backgroundPath }?.bitmap
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background)) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()
                .backgroundEffects(settings.overrides.effects)
                .then(if (android.os.Build.VERSION.SDK_INT >= 31 && settings.backgroundBlur > 0)
                    Modifier.blur((settings.backgroundBlur * .32f).dp) else Modifier), contentScale = ContentScale.Crop)
            // Match light/dark theme while keeping controls and labels readable.
            Box(Modifier.fillMaxSize().background(backgroundScrimColor(settings, background)))
        }
    }
}

internal fun backgroundScrimColor(settings: AppearanceSettings, background: Color): Color =
    if(settings.backgroundScrimIsSigned) {
        (if(settings.backgroundScrim<0)Color.Black else Color.White).copy(alpha=kotlin.math.abs(settings.backgroundScrim.coerceIn(-100,100))/100f)
    } else background.copy(alpha=settings.backgroundScrim.coerceIn(0,100)/100f)
