package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeStoreDetailScreen(
    theme: StoreTheme,
    settings: AppearanceSettings,
    state: ThemeLibraryState,
    model: ThemeLibraryViewModel,
    onBack: () -> Unit,
    onOpenAuthor: (authorName: String, authorHash: String, avatarPath: String, source: ThemeSource) -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme
    val images = remember(theme.key, theme.preview, theme.screenshots) {
        buildList {
            if (theme.preview.isNotBlank()) add(theme.preview)
            theme.screenshots.forEach { if (it.isNotBlank() && it !in this) add(it) }
        }
    }
    val supported = theme.format in ThemePackages.MIN_SUPPORTED_FORMAT..ThemePackages.MAX_SUPPORTED_FORMAT
    val downloadedRecord = remember(state.downloads, theme.key) {
        state.downloads.firstOrNull { it.key == theme.key }
    }
    val sameVersionLocal = downloadedRecord != null && downloadedRecord.version == theme.version
    val verified = state.verifiedThemeKey == theme.key || sameVersionLocal
    val downloadingThis = state.busy && state.downloadingKey == theme.key

    val authorCount = remember(state.catalog, theme.author, theme.authorHash, state.showR18) {
        val visibleCatalog = if (state.showR18) state.catalog else state.catalog.filterNot { it.isR18Theme() }
        visibleCatalog.count { other ->
            if (theme.authorHash.isNotBlank() && other.authorHash.isNotBlank()) {
                other.authorHash == theme.authorHash
            } else {
                theme.author.isNotBlank() && other.author.equals(theme.author, ignoreCase = true)
            }
        }.coerceAtLeast(1)
    }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var expandDescription by remember(theme.key) { mutableStateOf(false) }
    var expandChangelog by remember(theme.key) { mutableStateOf(false) }
    var expandTechnical by remember(theme.key) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZiyuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, palette.cardBackground, contentColor = palette.textPrimary)
            ZiyuText(
                theme.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!theme.source.isDefault) {
                ThemeStoreBadge(theme.source.displayName(), palette.cardBackground, palette.textSecondary)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ThemeTrialBanner(modifier = Modifier.fillMaxWidth())

            if (images.isNotEmpty()) {
                val pagerState = rememberPagerState(pageCount = { images.size })
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(scheme.surfaceContainerHighest),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        RemoteThemeImage(
                            model = model,
                            source = theme.source,
                            path = images[page],
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { viewerIndex = page },
                            contentScale = ContentScale.Fit,
                            maxDimension = 1080,
                            blurBackdropOnFit = true,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ZiyuIcon(Icons.Rounded.ZoomIn, null, Modifier.size(14.dp), tint = Color.White)
                            ZiyuText(
                                "${pagerState.currentPage + 1} / ${images.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZiyuText(
                    theme.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (theme.isDynamicTheme()) {
                        ThemeStoreBadge("动态壁纸", scheme.primary, scheme.onPrimary)
                    }
                    if (theme.isAdaptiveTheme()) {
                        ThemeStoreBadge("自适应主题", scheme.primaryContainer, scheme.onPrimaryContainer)
                    }
                    if (theme.isR18Theme()) {
                        ThemeStoreBadge("R18", scheme.errorContainer, scheme.onErrorContainer)
                    }
                    if (sameVersionLocal) {
                        ThemeStoreBadge("已下载 v${theme.version}", palette.successContainer, palette.success)
                    }
                    ThemeStoreBadge("v${theme.version} · ${themeBytes(theme.size)}", palette.cardBackground, palette.textSecondary)
                }

                val date = theme.updated.substringBefore('T').replace('-', '/')
                val stats = buildList {
                    add("下载 ${theme.downloads}")
                    theme.heat?.let { add("热度 $it") }
                    if (date.isNotBlank()) add("更新 $date")
                }.joinToString(" · ")
                ZiyuText(stats, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            }

            if (!supported) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.errorContainer.copy(alpha = 0.7f),
                ) {
                    ZiyuText(
                        "当前版本的字域暂不支持此主题包格式（v${theme.format}）。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZiyuOutlinedButton(
                    onClick = {
                        if (sameVersionLocal && downloadedRecord != null) {
                            model.openDownloaded(downloadedRecord, settings.uiStyle, trial = true, currentSettings = settings)
                        } else {
                            model.download(theme, settings.uiStyle, trial = true, currentSettings = settings)
                        }
                    },
                    enabled = !state.busy && supported,
                    modifier = Modifier.weight(1f),
                ) {
                    ZiyuIcon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    ZiyuText("试用")
                }
                ZiyuButton(
                    onClick = {
                        if (sameVersionLocal && downloadedRecord != null) {
                            model.openDownloaded(downloadedRecord, settings.uiStyle, trial = false)
                        } else {
                            model.download(theme, settings.uiStyle, trial = false)
                        }
                    },
                    enabled = !state.busy && supported,
                    modifier = Modifier.weight(1.3f),
                ) {
                    ZiyuIcon(
                        if (sameVersionLocal) Icons.Rounded.CheckCircle else Icons.Rounded.FileDownload,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    ZiyuText(if (sameVersionLocal) "应用本地包" else "下载并应用")
                }
            }
            if (sameVersionLocal) {
                ZiyuTextButton(
                    onClick = { model.download(theme, settings.uiStyle, trial = false) },
                    enabled = !state.busy && supported,
                ) {
                    ZiyuText("重新从来源下载")
                }
            }

            if (downloadingThis && state.transfer != null) {
                ThemeTransferProgressCard(
                    transfer = state.transfer,
                    isTrial = state.downloadTrial,
                    onCancel = model::cancelTransfer,
                )
            } else if (downloadingThis) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.cardBackground).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ZiyuText("正在准备主题…", style = MaterialTheme.typography.bodySmall)
                        ZiyuTextButton(onClick = model::cancelTransfer) { ZiyuText("取消") }
                    }
                }
            }

            if (state.message.isNotBlank()) {
                ZiyuText(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isError) scheme.error else scheme.primary,
                )
            }

            if (verified) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.successContainer.copy(alpha = 0.72f),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ZiyuIcon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp), tint = palette.success)
                        ZiyuText(
                            "主题包已通过 SHA-256 完整性校验并保存在本地离线库。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPrimary,
                        )
                    }
                }
            }

            // 作者卡片
            val authorDisplay = theme.author.ifBlank { "未署名" }
            Surface(
                onClick = {
                    if (theme.author.isNotBlank() || theme.authorHash.isNotBlank()) {
                        onOpenAuthor(authorDisplay, theme.authorHash, theme.avatar, theme.source)
                    }
                },
                shape = RoundedCornerShape(20.dp),
                color = palette.cardBackground,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemeAuthorAvatar(model, theme.source, theme.avatar, authorDisplay, 44.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ZiyuText(authorDisplay, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ZiyuText("查看作者全部作品（$authorCount 个）", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                    }
                    ZiyuIcon(Icons.Rounded.ChevronRight, null, tint = palette.textSecondary)
                }
            }

            if (theme.description.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZiyuText("主题介绍", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val rendered = rememberThemeStoreMarkdown(theme.description)
                    Text(
                        text = rendered,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        maxLines = if (expandDescription) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (theme.description.length > 160 || theme.description.count { it == '\n' } >= 5) {
                        ZiyuTextButton(onClick = { expandDescription = !expandDescription }) {
                            ZiyuText(if (expandDescription) "收起" else "展开全文")
                        }
                    }
                }
            }

            if (theme.changelog.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZiyuText("更新日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val rendered = rememberThemeStoreMarkdown(theme.changelog)
                    Text(
                        text = rendered,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        maxLines = if (expandChangelog) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (theme.changelog.length > 140 || theme.changelog.count { it == '\n' } >= 4) {
                        ZiyuTextButton(onClick = { expandChangelog = !expandChangelog }) {
                            ZiyuText(if (expandChangelog) "收起" else "展开全文")
                        }
                    }
                }
            }

            val displayTags = remember(theme.tags) {
                theme.tags.map(::canonicalThemeTag).distinct().filter { it.isNotBlank() }
            }
            if (displayTags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    displayTags.forEach { tag ->
                        ThemeStoreBadge("#${themeTagDisplayLabel(tag)}", palette.cardBackground, palette.textSecondary)
                    }
                }
            }

            // 许可与详情（按需展开）
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.cardBackground)
                    .clickable { expandTechnical = !expandTechnical }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZiyuText("许可与校验信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ZiyuIcon(
                        if (expandTechnical) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        tint = palette.textSecondary,
                    )
                }
                if (theme.license.isNotBlank()) {
                    ZiyuText("许可协议：${theme.license}", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
                if (expandTechnical) {
                    ZiyuText("主题标识：${theme.id} · 格式 v${theme.format}", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                    ZiyuText("来源：${theme.source.url}", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                    if (theme.sha256.isNotBlank()) {
                        ZiyuText(
                            "SHA-256：${theme.sha256}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = palette.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    viewerIndex?.let { start ->
        ThemeFullscreenGalleryDialog(
            images = images,
            initialPage = start,
            source = theme.source,
            model = model,
            onDismiss = { viewerIndex = null },
        )
    }
}

@Composable
internal fun ThemeAuthorAvatar(
    model: ThemeLibraryViewModel,
    source: ThemeSource,
    avatarPath: String,
    authorName: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val initial = authorName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "主"
        ZiyuText(
            initial,
            color = scheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (avatarPath.isNotBlank()) {
            RemoteThemeImage(
                model = model,
                source = source,
                path = avatarPath,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
                maxDimension = 256,
            )
        }
    }
}

@Composable
private fun ThemeFullscreenGalleryDialog(
    images: List<String>,
    initialPage: Int,
    source: ThemeSource,
    model: ThemeLibraryViewModel,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
            pageCount = { images.size },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                var scale by remember(page) { mutableFloatStateOf(1f) }
                var offset by remember(page) { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.2f
                                    }
                                },
                            )
                        }
                        .then(
                            if (scale > 1.05f) {
                                Modifier.pointerInput(page) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val nextScale = (scale * zoom).coerceIn(1f, 3.5f)
                                        scale = nextScale
                                        offset = if (nextScale <= 1.05f) Offset.Zero else offset + pan
                                    }
                                }
                            } else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    RemoteThemeImage(
                        model = model,
                        source = source,
                        path = images[page],
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentScale = ContentScale.Fit,
                        maxDimension = 2048,
                    )
                }
            }

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    ZiyuText(
                        "${pagerState.currentPage + 1} / ${images.size} · 双击缩放",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                    ),
                ) {
                    ZiyuIcon(Icons.Rounded.Close, "关闭大图")
                }
            }
        }
    }
}
