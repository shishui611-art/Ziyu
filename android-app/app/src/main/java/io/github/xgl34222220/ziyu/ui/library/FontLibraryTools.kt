package io.github.xgl34222220.ziyu.ui.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedTextField
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton

@Composable
internal fun FontLibraryTools(
    style: UiStyle,
    state: FontLibraryUiState,
    actions: FontLibraryActions,
    fileTools: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("font-preview-v1", Context.MODE_PRIVATE) }
    val collectionStore = remember(context) { FontLibraryCollectionStore(context) }
    var collections by remember { mutableStateOf(collectionStore.load()) }
    var previewText by rememberSaveable { mutableStateOf(preferences.getString("text", "").orEmpty()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showPreviewEditor by rememberSaveable { mutableStateOf(false) }
    var showManagement by rememberSaveable { mutableStateOf(false) }
    var detailFont by remember { mutableStateOf<FontItem?>(null) }
    var previewFont by remember { mutableStateOf<FontItem?>(null) }
    val conflicts = remember(state.allFonts) { analyzeFontLibraryConflicts(state.allFonts) }
    val enabled = !state.loading && !state.operationBusy

    LaunchedEffect(state.pageActive, state.allFonts, state.pendingDeletionIds, state.loading, state.indexReady) {
        if (!state.pageActive) return@LaunchedEffect
        previewText = preferences.getString("text", "").orEmpty()
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
    }

    fun persistCollections(next: FontLibraryCollections) {
        collections = next
        collectionStore.save(next)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fileTools()
        ZiyuOutlinedButton(
            onClick = { draft = previewText; showPreviewEditor = true },
            modifier = Modifier.fillMaxWidth(),
        ) { ZiyuText("预览文字") }
        ZiyuOutlinedButton(
            onClick = actions.importSystemFonts,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { ZiyuText("添加系统字体") }
        ZiyuOutlinedButton(
            onClick = actions.restoreDefault,
            enabled = enabled && state.activeFontId != "default",
            modifier = Modifier.fillMaxWidth(),
        ) { ZiyuText("恢复系统默认字体") }
        FontLibraryUtilitiesBar(
            style = style,
            fonts = state.allFonts,
            collections = collections,
            enabled = enabled,
            onCollectionsChange = ::persistCollections,
        )
        FontArchiveExportTool(
            style = style,
            fonts = state.allFonts,
            collections = collections,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        FontLibraryManagementButton(
            style = style,
            favoriteCount = collections.favoriteIds.size,
            issueCount = conflicts.issueIds.size,
            loading = state.loading || state.operationBusy,
            onClick = { showManagement = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showManagement) {
        FontLibraryManagementDialog(
            style = style,
            fonts = state.allFonts,
            activeFontId = state.activeFontId,
            activeFontPending = state.activeFontPending,
            operationBusy = state.operationBusy,
            collections = collections,
            conflicts = conflicts,
            onCollectionsChange = { visibleNext ->
                val visibleIds = state.allFonts.map { it.id }.toSet()
                persistCollections(FontLibraryCollections(
                    favoriteIds = (collections.favoriteIds - visibleIds) + visibleNext.favoriteIds,
                    tags = collections.tags.filterKeys { it !in visibleIds } + visibleNext.tags,
                ))
            },
            onDeleteFonts = { ids -> showManagement = false; actions.deleteMany(ids) },
            onOpenDetails = { font -> showManagement = false; detailFont = font },
            onDismiss = { showManagement = false },
        )
    }
    detailFont?.let { font ->
        FontDetailsDialogRoute(
            style = style,
            font = font,
            active = state.activeFontId == font.id,
            pending = state.activeFontPending,
            busy = state.operationBusy,
            customPreviewText = previewText,
            onGlyphPreview = { detailFont = null; previewFont = font },
            onDismiss = { detailFont = null },
            onApply = { detailFont = null; actions.apply(font) },
            onDelete = { detailFont = null; actions.delete(font) },
        )
    }
    previewFont?.let { font -> FontGlyphPreviewSheet(font = font, onDismiss = { previewFont = null }) }
    if (showPreviewEditor) {
        val count = draft.codePointCount(0, draft.length)
        ZiyuAlertDialog(
            onDismissRequest = { showPreviewEditor = false },
            title = { ZiyuText("预览文字") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZiyuText("修改字体卡片上的样例，最多 200 个字符。")
                    ZiyuOutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        isError = count > 200,
                        supportingText = { ZiyuText("$count / 200") },
                    )
                    ZiyuTextButton(onClick = { draft = "" }) { ZiyuText("恢复默认") }
                }
            },
            dismissButton = { ZiyuTextButton(onClick = { showPreviewEditor = false }) { ZiyuText("取消") } },
            confirmButton = {
                ZiyuButton(
                    onClick = {
                        previewText = draft.takeUnless { it.isBlank() }.orEmpty()
                        preferences.edit().putString("text", previewText).apply()
                        showPreviewEditor = false
                    },
                    enabled = count <= 200,
                ) { ZiyuText("保存") }
            },
        )
    }
}
