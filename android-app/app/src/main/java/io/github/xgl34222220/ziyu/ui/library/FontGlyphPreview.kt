package io.github.xgl34222220.ziyu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuDropdownMenu
import io.github.xgl34222220.ziyu.ui.design.ZiyuDropdownMenuItem
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon
import io.github.xgl34222220.ziyu.ui.design.ZiyuIconButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuModalBottomSheet
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton
import io.github.xgl34222220.ziyu.ui.design.rememberZiyuModalBottomSheetState
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard
import io.github.xgl34222220.ziyu.ui.theme.ZiyuDetailBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GlyphCorpusAsset = "font-preview/coolapk-53620096-r1.txt"

private data class GlyphSection(val id: Int, val label: String, val text: String)
private data class GlyphCorpus(val original: String, val sections: List<GlyphSection>)
private data class GlyphCorpusState(val corpus: GlyphCorpus? = null, val error: String = "")

// Supplemental everyday samples are separate from the immutable Coolapk r1 text.
private val CommonGlyphSections = listOf(
    GlyphSection(-4, "常用中文", "天地有大美，字里见山河。\n春夏秋冬，风雨晴雪。\n字体预览，山海有相逢。"),
    GlyphSection(-3, "英文字母", "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\nThe quick brown fox jumps over the lazy dog."),
    GlyphSection(-2, "数字", "0123456789\n2026/11/07 12:34:56\n¥123.45  100%  +−×÷="),
    GlyphSection(-1, "常用标点", "，。！？、；：\n“你好”‘世界’（字体）【预览】《山海》\n… — · ～\n.,!?;: ' \" () [] {} <> @ # & _ / \\"),
)

// Headings supply navigation only. Keep every original sample line intact, including
// duplicate characters, combining marks, supplementary characters and literal escapes.
private fun parseGlyphCorpus(original: String): GlyphCorpus {
    val sections = mutableListOf<GlyphSection>()
    var label = "字体测试"
    val lines = mutableListOf<String>()
    fun finish() {
        val body = lines.dropWhile(String::isEmpty).dropLastWhile(String::isEmpty)
        if (body.isNotEmpty()) sections += GlyphSection(sections.size, label, body.joinToString("\n"))
        lines.clear()
    }
    original.lineSequence().forEach { line ->
        if (line.endsWith("：") || line == "字体测试" || line == "字体测试plus版") {
            finish()
            label = line.removeSuffix("：")
        } else {
            lines += line
        }
    }
    finish()
    check(sections.isNotEmpty()) { "预览文字为空" }
    return GlyphCorpus(original, CommonGlyphSections + sections)
}

/** Library previews share the active native sheet, card, menu and typography dispatchers. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FontGlyphPreviewSheet(font: FontItem, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val palette = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val sheetState = rememberZiyuModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var sizeSp by rememberSaveable { mutableStateOf(24) }
    var sectionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    val loaded by produceState(GlyphCorpusState(), context) {
        value = withContext(Dispatchers.IO) {
            try {
                val original = context.assets.open(GlyphCorpusAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                GlyphCorpusState(corpus = parseGlyphCorpus(original))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                GlyphCorpusState(error = error.message ?: "读取失败")
            }
        }
    }
    val corpus = loaded.corpus
    val sections = remember(corpus, sectionId) {
        corpus?.sections.orEmpty().filter { sectionId == null || it.id == sectionId }
    }
    LaunchedEffect(sectionId) { listState.scrollToItem(0) }

    ZiyuModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The page token is transparent with wallpaper; a modal needs its own solid surface.
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            ZiyuDetailBar("字库预览", onBack = { sheetState.dismiss() })
            Column(
                Modifier.fillMaxWidth().padding(horizontal = layout.pageHorizontal),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZiyuText(
                    font.name,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "查看完整名称") { showName = true },
                    color = palette.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ZiyuText("查看字形和符号，缺字可能由系统补齐。", color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ZiyuText("字号", color = palette.textSecondary, style = MaterialTheme.typography.labelMedium)
                        ZiyuIconButton(onClick = { sizeSp -= 2 }, enabled = sizeSp > 14) {
                            ZiyuIcon(Icons.Rounded.Remove, "缩小预览字号")
                        }
                        ZiyuText("$sizeSp", color = palette.textPrimary, style = MaterialTheme.typography.labelLarge)
                        ZiyuIconButton(onClick = { sizeSp += 2 }, enabled = sizeSp < 40) {
                            ZiyuIcon(Icons.Rounded.Add, "放大预览字号")
                        }
                    }
                    Box {
                        ZiyuTextButton(onClick = { showCategories = true }, enabled = corpus != null) {
                            ZiyuText(
                                corpus?.sections?.firstOrNull { it.id == sectionId }?.label ?: "全部分类",
                                modifier = Modifier.widthIn(max = 180.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(4.dp))
                            ZiyuIcon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                        }
                        ZiyuDropdownMenu(
                            expanded = showCategories,
                            onDismissRequest = { showCategories = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            fun select(id: Int?) { sectionId = id; showCategories = false }
                            ZiyuDropdownMenuItem(
                                text = { ZiyuText("全部分类") },
                                onClick = { select(null) },
                                trailingIcon = { if (sectionId == null) ZiyuIcon(Icons.Rounded.Check, "已选择") },
                            )
                            corpus?.sections.orEmpty().forEach { section ->
                                ZiyuDropdownMenuItem(
                                    text = { ZiyuText(section.label) },
                                    onClick = { select(section.id) },
                                    trailingIcon = { if (sectionId == section.id) ZiyuIcon(Icons.Rounded.Check, "已选择") },
                                )
                            }
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(
                    start = layout.pageHorizontal, end = layout.pageHorizontal,
                    top = 8.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(layout.itemGap),
            ) {
                if (corpus == null) {
                    item(key = "load") {
                        ZiyuText(
                            if (loaded.error.isBlank()) "正在读取预览文字…" else "预览文字读取失败：${loaded.error}",
                            color = if (loaded.error.isBlank()) palette.textSecondary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(sections, key = { it.id }, contentType = { "glyph-section" }) { section ->
                    ZiyuCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = palette.cardBackground),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(layout.compactPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ZiyuText(section.label, color = palette.textSecondary, style = MaterialTheme.typography.labelLarge)
                            NativeFontPreview(
                                font = font,
                                text = section.text,
                                modifier = Modifier.fillMaxWidth(),
                                textSizeSp = sizeSp.toFloat(),
                                maxLines = Int.MAX_VALUE,
                                color = palette.textPrimary,
                                includeFontPadding = true,
                            )
                        }
                    }
                }
                if (corpus != null) {
                    item(key = "source") {
                        ZiyuTextButton(onClick = { clipboard.setText(AnnotatedString(corpus.original)) }) {
                            ZiyuIcon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            ZiyuText("复制完整测试文字")
                        }
                    }
                }
            }
        }
    }
    if (showName) {
        ZiyuAlertDialog(
            onDismissRequest = { showName = false },
            title = { ZiyuText("字体名称") },
            text = { SelectionContainer { ZiyuText(font.name) } },
            confirmButton = { ZiyuTextButton(onClick = { showName = false }) { ZiyuText("完成") } },
        )
    }
}
