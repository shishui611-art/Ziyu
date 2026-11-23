package io.github.xgl34222220.ziyu.ui.settings

import android.graphics.ImageDecoder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.appearanceCardBackground
import io.github.xgl34222220.ziyu.ui.theme.rememberAppearancePreviewColors
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ThemeLibraryCard(settings: AppearanceSettings) {
    var open by rememberSaveable { mutableStateOf(false) }
    val trialActive by ThemeTrialManager.active.collectAsStateWithLifecycle()
    SettingCard("主题商店") {
        ZiyuText("在线浏览、试用与下载主题，或导入分享自己的外观。", style = MaterialTheme.typography.bodyMedium)
        if (settings.themeName.isNotBlank()) {
            ZiyuText(
                "当前主题：${settings.themeName}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalZiyuPaletteTokens.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trialActive) {
            ThemeTrialBanner(modifier = Modifier.fillMaxWidth())
        }
        ZiyuButton(onClick = { open = true }) { ZiyuText("打开主题商店") }
    }
    if (open) {
        ThemeStoreColors(settings) {
            ThemeStoreHost(settings = settings, onDismiss = { open = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeTrialBanner(
    modifier: Modifier = Modifier,
    onRestored: () -> Unit = {},
    onKept: () -> Unit = {},
) {
    val active by ThemeTrialManager.active.collectAsStateWithLifecycle()
    val trialName by ThemeTrialManager.trialThemeName.collectAsStateWithLifecycle()
    if (!active) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZiyuIcon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp), tint = scheme.primary)
                ZiyuText(
                    text = if (trialName.isNotBlank()) "正在试用「$trialName」" else "正在试用主题",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            ZiyuText(
                "重启应用会自动恢复试用前的外观。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSecondaryContainer.copy(alpha = 0.85f),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZiyuButton(
                    onClick = {
                        if (busy) return@ZiyuButton
                        busy = true
                        scope.launch {
                            try {
                                ThemeTrialManager.restoreNow(context)
                                onRestored()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    ZiyuIcon(Icons.Rounded.Restore, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    ZiyuText(if (busy) "正在恢复…" else "恢复原主题")
                }
                ZiyuOutlinedButton(
                    onClick = {
                        ThemeTrialManager.keepCurrent(context)
                        onKept()
                    },
                    enabled = !busy,
                ) {
                    ZiyuIcon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    ZiyuText("保留当前")
                }
            }
        }
    }
}

internal val LocalThemeStoreDark = staticCompositionLocalOf { false }

@Composable
internal fun ThemeStoreColors(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ink = if (dark) Color(0xFFF3F5F8) else Color(0xFF14161A)
    val subInk = if (dark) Color(0xFFB7BDC9) else Color(0xFF5A606B)
    val background = if (dark) Color(0xFF111318) else Color(0xFFF8F9FB)
    val surface = if (dark) Color(0xFF1E222A) else Color(0xFFFFFFFF)
    val surfaceHigh = if (dark) Color(0xFF282D37) else Color(0xFFF0F2F6)
    val palette = LocalZiyuPaletteTokens.current.copy(
        pageBackground = background,
        cardBackground = surface,
        elevatedCardBackground = surfaceHigh,
        textPrimary = ink,
        textSecondary = subInk,
        textTertiary = subInk,
    )
    val colors = MaterialTheme.colorScheme.copy(
        background = background,
        surface = background,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        onBackground = ink,
        onSurface = ink,
        onSurfaceVariant = subInk,
        onSecondaryContainer = ink,
        secondaryContainer = surfaceHigh,
        onPrimaryContainer = ink,
        primaryContainer = surfaceHigh,
    )
    CompositionLocalProvider(
        LocalThemeStoreDark provides dark,
        LocalZiyuPaletteTokens provides palette,
        LocalContentColor provides ink,
        LocalZiyuContentColor provides ink,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

@Composable
internal fun ThemeStoreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = if (selected) scheme.primary else scheme.onSurfaceVariant
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { ZiyuText(label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.heightIn(min = 40.dp),
        leadingIcon = if (selected) {
            { ZiyuIcon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = color) }
        } else null,
    )
}

@Composable
internal fun ThemeStoreBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        ZiyuText(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ThemeAppearancePreview(settings: AppearanceSettings) {
    val systemDark = isSystemInDarkTheme()
    val selectedDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val current = settings.forDark(selectedDark)
    val dark = when (current.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = rememberAppearancePreviewColors(current, dark)
    val hero = homeHeroColors(current, colors, dark)
    val card = appearanceCardBackground(current, colors, dark)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.background)) {
        val titleScale = ((maxWidth - 24.dp) / (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(240).dp).coerceAtMost(1f)
        ThemeFileImage(
            path = current.backgroundPath,
            modifier = Modifier.matchParentSize(),
            effects = current.overrides.effects,
            blur = current.backgroundBlur,
            contentScale = ContentScale.Crop,
            placeholder = false,
        )
        Box(Modifier.matchParentSize().background(backgroundScrimColor(current, colors.background)))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ZiyuText(
                    resolveThemeText(current.customTexts, ThemeTextKey.HOME_TITLE),
                    color = colors.onSurface,
                    fontSize = 40.sp * titleScale,
                    lineHeight = 48.sp * titleScale,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (current.dynamicWallpaperPath.isNotBlank()) {
                    ThemeStoreBadge("动态壁纸", colors.primaryContainer, colors.onPrimaryContainer)
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(hero.background.copy(alpha = hero.background.alpha * (1f - current.cardTransparency / 100f))),
            ) {
                HomeHeroFace(current, hero, "模块已连接", io.github.xgl34222220.ziyu.BuildConfig.VERSION_NAME, preview = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeStatPreview(resolveThemeText(current.customTexts, ThemeTextKey.STAT_FONT), "字体名称", colors, card, Modifier.weight(1f), current.cardTransparency)
                ThemeStatPreview(resolveThemeText(current.customTexts, ThemeTextKey.STAT_LIBRARY), "—", colors, card, Modifier.weight(1f), current.cardTransparency)
            }
        }
    }
}

@Composable
private fun ThemeStatPreview(
    title: String,
    value: String,
    colors: ColorScheme,
    card: Color,
    modifier: Modifier,
    transparency: Int,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(card.copy(alpha = card.alpha * (1f - transparency / 100f)))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZiyuText(value, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ZiyuText(title, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RemoteThemeImage(
    model: ThemeLibraryViewModel,
    source: ThemeSource,
    path: String,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxDimension: Int = 768,
    blurBackdropOnFit: Boolean = false,
    onAspect: (Float) -> Unit = {},
) {
    val file by produceState<File?>(null, source.url, path) {
        value = null
        if (path.isBlank()) return@produceState
        try {
            value = model.preview(source, path)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            value = null
        }
    }
    ThemeFileImage(
        path = file?.absolutePath.orEmpty(),
        modifier = modifier,
        contentScale = contentScale,
        maxDimension = maxDimension,
        blurBackdropOnFit = blurBackdropOnFit,
        onAspect = onAspect,
    )
}

@Composable
internal fun ThemeFileImage(
    path: String,
    modifier: Modifier,
    effects: BackgroundEffects = BackgroundEffects(),
    blur: Int = 0,
    contentScale: ContentScale = ContentScale.Fit,
    maxDimension: Int = 768,
    blurBackdropOnFit: Boolean = false,
    onAspect: (Float) -> Unit = {},
    placeholder: Boolean = true,
) {
    val bitmap by produceState<ImageBitmap?>(null, path, maxDimension) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (path.isBlank() || !file.isFile) return@runCatching null
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    LaunchedEffect(bitmap) {
        bitmap?.let { onAspect(it.width.toFloat() / it.height.coerceAtLeast(1)) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .then(if (placeholder) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
    ) {
        bitmap?.let { img ->
            if (blurBackdropOnFit && contentScale == ContentScale.Fit) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().then(
                        if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier,
                    ),
                    contentScale = ContentScale.Crop,
                    alpha = 0.45f,
                )
            }
            Image(
                bitmap = img,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .backgroundEffects(effects)
                    .then(if (android.os.Build.VERSION.SDK_INT >= 31 && blur > 0) Modifier.blur((blur * .32f).dp) else Modifier),
                contentScale = contentScale,
            )
        }
    }
}

internal fun themeBytes(value: Long): String = when {
    value >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", value / 1048576.0)
    value >= 1024L -> "${value / 1024} KB"
    else -> "$value B"
}

internal fun themeEtaText(seconds: Long?): String = when {
    seconds == null || seconds <= 0L -> ""
    seconds < 60L -> "约 ${seconds} 秒"
    seconds < 3600L -> "约 ${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "约 ${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
}
