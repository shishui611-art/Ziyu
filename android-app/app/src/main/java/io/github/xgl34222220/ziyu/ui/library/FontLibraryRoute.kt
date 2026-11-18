package io.github.xgl34222220.ziyu.ui.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton

@Composable
internal fun FontLibraryRoute(
    style: UiStyle,
    state: FontLibraryUiState,
    actions: FontLibraryActions,
    topActions: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val previewPreferences = remember(context) { context.getSharedPreferences("font-preview-v1", Context.MODE_PRIVATE) }
    var customPreviewText by remember { mutableStateOf(previewPreferences.getString("text", "").orEmpty()) }
    val collectionStore = remember(context) { FontLibraryCollectionStore(context) }
    var collections by remember { mutableStateOf(collectionStore.load()) }
    var filter by rememberSaveable { mutableStateOf(FontLibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(FontLibrarySort.ACTIVE_FIRST) }
    var detailFont by remember { mutableStateOf<FontItem?>(null) }
    var previewFont by remember { mutableStateOf<FontItem?>(null) }
    var actionFont by remember { mutableStateOf<FontItem?>(null) }
    val conflicts = remember(state.allFonts) { analyzeFontLibraryConflicts(state.allFonts) }
    val displayFonts = remember(state.fonts, state.activeFontId, filter, sort, collections.favoriteIds, conflicts) {
        state.forDisplay(filter, sort, collections.favoriteIds, conflicts.issueIds).fonts
    }
    val displayState = state.copy(
        fonts = displayFonts, visibleCount = displayFonts.size,
        filter = filter, sort = sort, previewText = customPreviewText,
    )
    LaunchedEffect(state.allFonts, state.loading, state.pendingDeletionIds, state.pageActive, state.indexReady) {
        if (!state.pageActive) {
            actionFont = null
            return@LaunchedEffect
        }
        customPreviewText = previewPreferences.getString("text", "").orEmpty()
        collections = collectionStore.load()
        if (!state.loading && state.indexReady) {
            val existing = state.allFonts.map { it.id }.toSet() + state.pendingDeletionIds
            val cleaned = FontLibraryCollections(
                favoriteIds = collections.favoriteIds.intersect(existing),
                tags = collections.tags.filterKeys { it in existing },
            )
            if (cleaned != collections) {
                collections = cleaned
                collectionStore.save(cleaned)
            }
        }
        if (detailFont?.id in state.pendingDeletionIds) detailFont = null
        if (previewFont?.id in state.pendingDeletionIds) previewFont = null
        if (actionFont?.id in state.pendingDeletionIds) actionFont = null
    }
    val displayActions = actions.copy(
        preview = { previewFont = it },
        details = { detailFont = it },
        openMenu = { actionFont = it },
        setFilter = { filter = it },
        setSort = { sort = it },
    )
    when (style) {
        UiStyle.MATERIAL -> FontLibraryScreenMaterial(displayState, displayActions, topActions)
        UiStyle.MIUIX -> FontLibraryScreenMiuix(displayState, displayActions, topActions)
        UiStyle.COUI -> FontLibraryScreenCoui(displayState, displayActions, topActions)
    }
    actionFont?.takeIf { state.pageActive }?.let { font ->
        val pendingActive = state.activeFontId == font.id && state.activeFontPending
        ZiyuAlertDialog(
            onDismissRequest = { actionFont = null },
            title = { ZiyuText(font.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ZiyuButton(
                        onClick = { actionFont = null; actions.apply(font) },
                        enabled = font.valid && !state.operationBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { ZiyuText("应用") }
                    ZiyuOutlinedButton(
                        onClick = { actionFont = null; actions.delete(font) },
                        enabled = (!state.operationBusy || state.deletionInProgress) && !pendingActive,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { ZiyuText("删除", color = MaterialTheme.colorScheme.error) }
                    ZiyuOutlinedButton(
                        onClick = { actionFont = null; previewFont = font },
                        enabled = font.valid,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { ZiyuText("字库预览") }
                    ZiyuOutlinedButton(
                        onClick = { actionFont = null; detailFont = font },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { ZiyuText("字体详情") }
                    if (pendingActive) ZiyuText("当前字体等待重启，完成后可删除。")
                }
            },
            confirmButton = { ZiyuTextButton(onClick = { actionFont = null }) { ZiyuText("取消") } },
        )
    }
    detailFont?.let { font ->
        FontDetailsDialogRoute(
            style = style,
            font = font,
            active = state.activeFontId == font.id,
            pending = state.activeFontPending,
            busy = state.operationBusy,
            customPreviewText = customPreviewText,
            onGlyphPreview = { detailFont = null; previewFont = font },
            onDismiss = { detailFont = null },
            onApply = { detailFont = null; actions.apply(font) },
            onDelete = { detailFont = null; actions.delete(font) },
        )
    }
    previewFont?.let { font ->
        FontGlyphPreviewSheet(font = font, onDismiss = { previewFont = null })
    }
}
