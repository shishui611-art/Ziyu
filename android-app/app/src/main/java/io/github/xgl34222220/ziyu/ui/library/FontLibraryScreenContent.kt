package io.github.xgl34222220.ziyu.ui.library

import android.view.Gravity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuCheckbox
import io.github.xgl34222220.ziyu.ui.design.ZiyuDropdownMenu
import io.github.xgl34222220.ziyu.ui.design.ZiyuDropdownMenuItem
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon
import io.github.xgl34222220.ziyu.ui.design.ZiyuIconButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuLinearProgressIndicator
import io.github.xgl34222220.ziyu.ui.design.ZiyuSearchIconSlot
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextField
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import io.github.xgl34222220.ziyu.ui.theme.ZiyuSegmentedTabs
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTopBar

private val fontLibraryFilterLabels = listOf("全部", "收藏", "组合", "可变", "多字重", "冲突", "异常")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FontLibraryPageContent(
    state: FontLibraryUiState,
    actions: FontLibraryActions,
    importAction: @Composable () -> Unit,
) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val style = LocalUiStyle.current
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val context = LocalContext.current.applicationContext
    val layoutPreferences = remember(context) { context.getSharedPreferences("font-library-layout-v1", Context.MODE_PRIVATE) }
    var columns by rememberSaveable { mutableStateOf(layoutPreferences.getInt("columns", 0).coerceIn(0, 4)) }
    var showColumns by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var deletingIds by remember { mutableStateOf(listOf<String>()) }
    var showSort by remember { mutableStateOf(false) }
    val canSelect = !state.operationBusy || state.deletionInProgress
    val visibleIds = state.fonts.map { it.id }

    LaunchedEffect(state.allFonts) {
        val availableIds = state.allFonts.map { it.id }.toSet()
        selectedIds = selectedIds.filter { it in availableIds }
    }
    BackHandler(enabled = selecting && state.pageActive) {
        selecting = false
        selectedIds = emptyList()
    }

    LazyVerticalGrid(
        columns = if (columns == 0) GridCells.Adaptive(140.dp * fontScale) else GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            end = layout.pageHorizontal,
            bottom = maxOf(LocalDockContentPadding.current, layout.dockSafeBottom),
        ),
        verticalArrangement = Arrangement.spacedBy(maxOf(layout.itemGap, 16.dp)),
        horizontalArrangement = Arrangement.spacedBy(maxOf(layout.itemGap, 16.dp)),
    ) {
        item(key = "library_header", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZiyuTopBar(title = "字体库") {
                    ZiyuHeaderAction(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "刷新字体库",
                        onClick = actions.refresh,
                        enabled = !state.loading && !state.operationBusy,
                        loading = state.loading,
                        containerColor = if (style == UiStyle.MATERIAL) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .84f) else Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
                ZiyuTextField(
                    value = state.query,
                    onValueChange = actions.setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    leadingIcon = { ZiyuSearchIconSlot { ZiyuIcon(Icons.Rounded.Search, null) } },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            ZiyuIconButton(onClick = { actions.setQuery("") }) {
                                ZiyuIcon(Icons.Rounded.Close, "清空搜索")
                            }
                        }
                    },
                    placeholder = { ZiyuText("搜索字体") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = tokens.elevatedCardBackground,
                        unfocusedContainerColor = tokens.elevatedCardBackground,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                ZiyuSegmentedTabs(
                    labels = fontLibraryFilterLabels,
                    selectedIndex = FontLibraryFilter.entries.indexOf(state.filter),
                    onSelected = { FontLibraryFilter.entries.getOrNull(it)?.let(actions.setFilter) },
                )
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZiyuText(
                        if (state.deletionInProgress) "${state.pendingDeletionIds.size} 款正在后台删除"
                        else "${state.visibleCount} 款字体",
                        color = tokens.textSecondary,
                        fontSize = 12.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            ZiyuTextButton(onClick = { showColumns = true }) {
                                ZiyuText(if (columns == 0) "自动列数" else "每行 $columns 列", fontSize = 12.sp)
                                ZiyuIcon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                            }
                            ZiyuDropdownMenu(expanded = showColumns, onDismissRequest = { showColumns = false }) {
                                (0..4).forEach { option ->
                                    ZiyuDropdownMenuItem(
                                        text = { ZiyuText(if (option == 0) "自动" else "每行 $option 列") },
                                        trailingIcon = { if (columns == option) ZiyuIcon(Icons.Rounded.Check, "已选择") },
                                        onClick = {
                                            columns = option
                                            layoutPreferences.edit().putInt("columns", option).apply()
                                            showColumns = false
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            ZiyuTextButton(onClick = { showSort = true }) {
                                ZiyuText(state.sort.label, fontSize = 12.sp)
                                ZiyuIcon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                            }
                            ZiyuDropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                FontLibrarySort.entries.forEach { option ->
                                    ZiyuDropdownMenuItem(
                                        text = { ZiyuText(option.label) },
                                        trailingIcon = {
                                            if (state.sort == option) ZiyuIcon(Icons.Rounded.Check, "已选择")
                                        },
                                        onClick = { actions.setSort(option); showSort = false },
                                    )
                                }
                            }
                        }
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selecting) {
                        val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
                        ZiyuText("已选 ${selectedIds.size} 款", modifier = Modifier.align(Alignment.CenterVertically))
                        ZiyuTextButton(
                            onClick = {
                                selectedIds = if (allVisibleSelected) selectedIds - visibleIds.toSet()
                                else (selectedIds + visibleIds).distinct()
                            },
                            enabled = visibleIds.isNotEmpty(),
                        ) { ZiyuText(if (allVisibleSelected) "取消全选" else "全选") }
                        ZiyuTextButton(
                            onClick = { deletingIds = selectedIds.toList() },
                            enabled = selectedIds.isNotEmpty() && canSelect,
                        ) { ZiyuText("删除", color = MaterialTheme.colorScheme.error) }
                        ZiyuTextButton(onClick = { selecting = false; selectedIds = emptyList() }) { ZiyuText("取消") }
                    } else {
                        Box(Modifier.width(148.dp * fontScale)) { importAction() }
                        ZiyuTextButton(
                            onClick = { selecting = true },
                            enabled = state.allFonts.isNotEmpty() && canSelect,
                        ) { ZiyuText("批量选择") }
                    }
                }
            }
        }
        if (state.loading || (state.operationBusy && !state.deletionInProgress)) {
            item(key = "progress", span = { GridItemSpan(maxLineSpan) }) {
                ZiyuLinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            }
        }
        if (state.error.isNotBlank()) {
            item(key = "error", span = { GridItemSpan(maxLineSpan) }) { NoticeCard(state.error, error = true) }
        }
        if (state.operationMessage.isNotBlank()) {
            item(key = "operation", span = { GridItemSpan(maxLineSpan) }) { NoticeCard(state.operationMessage, error = false) }
        }
        if (!state.loading && state.fonts.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                ZiyuText(
                    when {
                        state.deletionInProgress -> "其余字体正在后台删除"
                        state.query.isNotBlank() || state.filter != FontLibraryFilter.ALL -> "没有找到匹配的字体"
                        else -> "点击导入，添加喜欢的字体"
                    },
                    modifier = Modifier.padding(vertical = 28.dp),
                    color = tokens.textSecondary,
                )
            }
        }
        items(state.fonts, key = { it.id }, contentType = { "font" }) { font ->
            LibraryFontCard(
                font = font,
                previewText = state.previewText,
                selecting = selecting,
                selected = font.id in selectedIds,
                onClick = {
                    if (selecting) {
                        selectedIds = if (font.id in selectedIds) selectedIds - font.id else selectedIds + font.id
                    } else actions.openMenu(font)
                },
            )
        }
    }

    if (deletingIds.isNotEmpty()) {
        val includesActive = state.activeFontId in deletingIds
        val pendingActive = includesActive && state.activeFontPending
        ZiyuAlertDialog(
            onDismissRequest = { deletingIds = emptyList() },
            title = { ZiyuText("删除 ${deletingIds.size} 款字体？") },
            text = {
                ZiyuText(buildString {
                    append("字体及相关文件将被永久删除。")
                    if (pendingActive) append(" 当前字体正在等待重启，请完成重启后再删除。")
                    else if (includesActive) append(" 正在使用的字体会先恢复为系统默认字体。")
                })
            },
            confirmButton = {
                ZiyuTextButton(
                    onClick = {
                        val confirmed = deletingIds.toSet()
                        deletingIds = emptyList()
                        selectedIds = emptyList()
                        selecting = false
                        actions.deleteMany(confirmed)
                    },
                    enabled = canSelect && !pendingActive,
                ) { ZiyuText("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { ZiyuTextButton(onClick = { deletingIds = emptyList() }) { ZiyuText("取消") } },
        )
    }
}

@Composable
private fun LibraryFontCard(
    font: FontItem,
    previewText: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalZiyuPaletteTokens.current
    ZiyuCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
        border = BorderStroke(if (selected) 2.dp else .5.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 124.dp
                val narrow = maxWidth < 84.dp
                val sample = when {
                    font.supportsCjk && narrow -> "山海\nAa12"
                    font.supportsCjk && compact -> "山海相逢\nAa 012"
                    font.supportsCjk -> "山海有相逢\nAa 012345"
                    compact -> "Ziyu\nAa12"
                    else -> "Hello Ziyu\nAa 012345"
                }
                val previewHeight = (maxWidth * .95f).coerceIn(88.dp, 146.dp)
                ZiyuSurface(color = scheme.primary.copy(alpha = .07f)) {
                    if (font.valid) {
                        NativeFontPreview(
                            font = font,
                            text = previewText.ifBlank { sample },
                            modifier = Modifier.fillMaxWidth().height(previewHeight).padding(horizontal = 10.dp, vertical = 12.dp),
                            textSizeSp = when {
                                narrow -> 14f
                                compact -> 16f
                                else -> 21f
                            },
                            gravity = Gravity.CENTER,
                            maxLines = 3,
                            color = tokens.textPrimary,
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
                            ZiyuText("预览不可用", color = tokens.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
                if (selecting) {
                    ZiyuCheckbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            ZiyuText(
                font.name,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).heightIn(min = 42.dp),
                color = tokens.textPrimary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!selecting) {
                ZiyuTextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    ZiyuText("选择操作", fontSize = 13.sp)
                }
            } else Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun NoticeCard(message: String, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    ZiyuSurface(
        shape = RoundedCornerShape(20.dp),
        color = if (error) scheme.errorContainer else scheme.primaryContainer,
    ) {
        ZiyuText(
            message,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = if (error) scheme.onErrorContainer else scheme.onPrimaryContainer,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}
