package io.github.xgl34222220.ziyu.ui.appearance

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decode only on a path change, away from the UI thread, with a fixed pixel cap. */
@Composable
internal fun ThemeArtworkImage(path: String, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                if (path.isBlank() || !File(path).isFile) null else ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, info, _ ->
                    val factor = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
                    d.setTargetSize(maxOf(1,(info.size.width*factor).toInt()), maxOf(1,(info.size.height*factor).toInt()))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: OutOfMemoryError) { null }
        }
    }
    bitmap?.let { Image(it, null, modifier, contentScale = scale) }
}

/** Shared background and optional stickers; visual layers never intercept touch input. */
@Composable
internal fun HomeCardArtwork(card: HomeCardDecoration, scrimColor: Color, modifier: Modifier = Modifier, showStickers: Boolean = true) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        if (card.backgroundPath.isNotBlank()) {
            ThemeArtworkImage(card.backgroundPath, Modifier.fillMaxSize().graphicsLayer {
                alpha = card.imageAlpha / 100f
                scaleX = card.zoom / 100f; scaleY = card.zoom / 100f
                translationX = widthPx * card.offsetX / 200f; translationY = heightPx * card.offsetY / 200f
            }.then(if (android.os.Build.VERSION.SDK_INT >= 31 && card.blur > 0) Modifier.blur((card.blur*.32f).dp) else Modifier))
            Box(Modifier.fillMaxSize().background(scrimColor.copy(alpha = card.scrim / 100f)))
        }
        if(showStickers) HomeCardStickers(card.stickers, Modifier.matchParentSize())
    }
}

@Composable
internal fun HomeCardStickers(stickers: List<CardSticker>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        stickers.forEach { sticker ->
            key(sticker.path) {
                ThemeArtworkImage(sticker.path, Modifier.align(Alignment.Center).size(maxWidth * sticker.size).graphicsLayer {
                    translationX = widthPx * sticker.offsetX; translationY = widthPx * sticker.offsetY; rotationZ = sticker.rotation
                }, ContentScale.Fit)
            }
        }
    }
}
