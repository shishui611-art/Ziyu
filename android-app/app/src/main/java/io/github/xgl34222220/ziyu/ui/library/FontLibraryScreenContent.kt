package io.github.xgl34222220.ziyu.ui.library

import android.view.Gravity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Warning
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import io.github.xgl34222220.ziyu.ui.design.ZiyuDropdownMenu as DropdownMenu
import io.github.xgl34222220.ziyu.ui.design.ZiyuDropdownMenuItem as DropdownMenuItem
import io.github.xgl34222220.ziyu.ui.design.ZiyuFilledTonalButton as FilledTonalButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import io.github.xgl34222220.ziyu.ui.design.ZiyuIconButton as IconButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextField as TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.ui.font.fontCombinationSourceSummary
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import io.github.xgl34222220.ziyu.ui.theme.ZiyuLoadingSkeleton
import io.github.xgl34222220.ziyu.ui.theme.ZiyuMotionTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuSectionHeading
import io.github.xgl34222220.ziyu.ui.theme.ZiyuSegmentedTabs
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTopBar

private val fontLibraryFilterLabels = FontLibraryFilter.entries.map { filter ->
    when (filter) {
        FontLibraryFilter.ALL -> "全部"
        FontLibraryFilter.FAVORITE -> "收藏"
        FontLibraryFilter.COMBINATION -> "组合"
        FontLibraryFilter.VARIABLE -> "可变"
        FontLibraryFilter.MULTI_WEIGHT -> "多字重"
        FontLibraryFilter.CONFLICT -> "冲突"
        FontLibraryFilter.INVALID -> "异常"
    }
}

@Composable
internal fun FontLibraryPageContent(
    style: UiStyle,
    state: FontLibraryUiState,
    actions: FontLibraryActions,
    tools: @Composable () -> Unit,
) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val scheme = MaterialTheme.colorScheme
    val cardColor = tokens.cardBackground
    val elevatedColor = tokens.elevatedCardBackground
    val textPrimary = tokens.textPrimary
    val textSecondary = tokens.textSecondary
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val filtered = state.query.isNotBlank() || state.filter != FontLibraryFilter.ALL

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            end = layout.pageHorizontal,
            bottom = maxOf(LocalDockContentPadding.current, layout.dockSafeBottom),
        ),
        verticalArrangement = Arrangement.spacedBy(layout.itemGap),
    ) {
        item(key = "header") {
            ZiyuTopBar(title = "字体库") {
                ZiyuHeaderAction(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "刷新字体库",
                    onClick = actions.refresh,
                    enabled = !state.loading && !state.operationBusy,
                    loading = state.loading,
                    containerColor = elevatedColor,
                )
            }
        }
        item(key = "search") {
            TextField(
                value = state.query,
                onValueChange = actions.setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { actions.setQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                placeholder = { Text("搜索你的字体") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = elevatedColor,
                    unfocusedContainerColor = elevatedColor,
                    disabledContainerColor = elevatedColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
        item(key = "filters") {
            ZiyuSegmentedTabs(
                labels = fontLibraryFilterLabels,
                selectedIndex = FontLibraryFilter.entries.indexOf(state.filter),
                onSelected = { FontLibraryFilter.entries.getOrNull(it)?.let(actions.setFilter) },
            )
        }
        item(key = "collection_heading") {
            Column {
                ZiyuSectionHeading(
                    title = if (filtered) "筛选结果" else "本地字体",
                    subtitle = "${state.visibleCount} 款字体 · ${state.validCount} 款可用",
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { showSort = true }) {
                            Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(state.sort.label, fontSize = 12.sp)
                            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                            FontLibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = {
                                        if (state.sort == option) Icon(Icons.Rounded.Check, contentDescription = "已选择")
                                    },
                                    onClick = { actions.setSort(option); showSort = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showTools = !showTools }) {
                        Text(if (showTools) "收起管理" else "导入与管理", fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (showTools) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        item(key = "tools") {
            AnimatedVisibility(
                visible = showTools,
                enter = fadeIn(tween(ZiyuMotionTokens.Fast)) +
                    expandVertically(tween(ZiyuMotionTokens.Normal, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(ZiyuMotionTokens.Normal, easing = FastOutSlowInEasing)) { it / 6 },
                exit = fadeOut(tween(140)) +
                    shrinkVertically(tween(170, easing = FastOutSlowInEasing)),
            ) {
                tools()
            }
        }
        if (state.loading) {
            item(key = "loading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZiyuLoadingSkeleton(
                        modifier = Modifier.fillMaxWidth(.42f).height(14.dp),
                    )
                    ZiyuLoadingSkeleton(
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        shape = RoundedCornerShape(999.dp),
                    )
                }
            }
        } else if (state.operationBusy && !state.deletionInProgress) {
            item(key = "loading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("正在处理字体，请稍候…", color = textSecondary, fontSize = 13.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
        if (state.error.isNotBlank()) item(key = "error") { NoticeCard(state.error, error = true) }
        if (state.operationMessage.isNotBlank()) {
            item(key = "operation") { NoticeCard(state.operationMessage, error = false) }
        }
        item(key = "system_font") {
            CompactSystemFontRow(
                active = state.activeFontId == "default", busy = state.operationBusy,
                cardColor = cardColor, textPrimary = textPrimary, textSecondary = textSecondary,
                onRestore = actions.restoreDefault,
                onImport = actions.importSystemFonts,
            )
        }
        if (!state.loading && state.fonts.isEmpty()) {
            item(key = "empty") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(22.dp), color = scheme.primary.copy(alpha = .08f)) {
                            Icon(
                                if (filtered) Icons.Rounded.Search else Icons.Rounded.FontDownload,
                                contentDescription = null, tint = scheme.primary,
                                modifier = Modifier.padding(18.dp).size(30.dp),
                            )
                        }
                        Text(
                            if (filtered) "没有找到匹配的字体" else "从第一款字体开始",
                            color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (filtered) "试试其他关键词，或清除筛选条件。" else "导入喜欢的字体，在这里预览、整理和应用。",
                            color = textSecondary, fontSize = 13.sp, lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                        )
                        FilledTonalButton(onClick = {
                            if (filtered) { actions.setQuery(""); actions.setFilter(FontLibraryFilter.ALL) }
                            else showTools = true
                        }) { Text(if (filtered) "清除筛选" else "打开导入与管理") }
                    }
                }
            }
        }
        items(state.fonts, key = { "font:${it.id}" }, contentType = { "font" }) { font ->
            LibraryFontCard(
                font = font, active = state.activeFontId == font.id, busy = state.operationBusy,
                cardColor = cardColor, textPrimary = textPrimary, textSecondary = textSecondary,
                onDetails = { actions.details(font) }, onApply = { actions.apply(font) },
                onDelete = { actions.delete(font) }, style = style,
            )
        }
    }
}

@Composable
private fun CompactSystemFontRow(
    active: Boolean, busy: Boolean, cardColor: Color,
    textPrimary: Color, textSecondary: Color, onRestore: () -> Unit, onImport: () -> Unit,
) {
    val layout = LocalZiyuThemeTokens.current.layout
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Row(Modifier.fillMaxWidth().padding(layout.compactPadding), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text("Aa", color = MaterialTheme.colorScheme.primary, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("系统默认", color = textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("恢复手机原有字体", color = textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (active) StatusPill("使用中")
            else FilledTonalButton(
                onClick = onRestore, enabled = !busy,
                modifier = Modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("恢复", fontSize = 13.sp) }
        }
        TextButton(
            onClick = onImport,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) { Text("添加系统字体到字体库，用于组合") }

    }
}

@Composable
private fun LibraryFontCard(
    font: FontItem, active: Boolean, busy: Boolean, cardColor: Color,
    textPrimary: Color, textSecondary: Color,
    onDetails: () -> Unit, onApply: () -> Unit, onDelete: () -> Unit,
    style: UiStyle,
) {
    val scheme = MaterialTheme.colorScheme
    val layout = LocalZiyuThemeTokens.current.layout
    Card(
        onClick = onDetails,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (font.valid) cardColor else scheme.errorContainer.copy(alpha = .34f),
        ),
        border = if (font.valid) null else BorderStroke(.75.dp, scheme.error.copy(alpha = .40f)),
    ) {
        Column(Modifier.padding(layout.compactPadding), verticalArrangement = Arrangement.spacedBy(layout.itemGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        font.name, color = textPrimary, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        fontMetadataSummary(font),
                        color = textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (active) StatusPill("使用中")
            }
            if (font.combination) {
                Text(
                    fontCombinationSourceSummary(font),
                    color = textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (font.valid) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.primary.copy(alpha = if (active) .12f else .08f),
                    border = BorderStroke(1.dp, scheme.primary.copy(alpha = .40f)),
                ) {
                    NativeFontPreview(
                        font = font,
                        text = if (font.supportsCjk) "山海有相逢 Aa 0123" else "Hello, Ziyu 0123",
                        axes = if (font.variable) mapOf("wght" to 400f) else emptyMap(),
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
                        textSizeSp = 23f, gravity = Gravity.CENTER_VERTICAL, maxLines = 1,
                    )
                }
            } else if (font.error.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(font.error, color = scheme.error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                val deleteEnabled = !busy && !active
                Button(
                    onClick = onDelete,
                    enabled = deleteEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除", fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApply,
                    enabled = font.valid && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Text(if (active) "重新应用" else "应用字体", fontSize = 13.sp)
                }
            }
        }
    }
}

private fun fontMetadataSummary(font: FontItem): String {
    val weight = when {
        font.variable -> "可变字重"
        font.weights.size > 1 -> "${font.weights.size} 档字重"
        else -> "单字重"
    }
    return listOf(
        font.format,
        font.size,
        weight,
        if (font.supportsCjk) "中文 / 拉丁" else "拉丁",
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun NoticeCard(message: String, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (error) scheme.errorContainer else scheme.primaryContainer,
    ) {
        Text(
            message, modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = if (error) scheme.onErrorContainer else scheme.onPrimaryContainer,
            fontSize = 13.sp, lineHeight = 20.sp,
        )
    }
}

@Composable
private fun StatusPill(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = scheme.primary.copy(alpha = .10f)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(text, color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
