package io.github.xgl34222220.ziyu.ui.studio

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard as Card
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCardDefaults
import io.github.xgl34222220.ziyu.ui.theme.withCardTransparency
import androidx.compose.material3.CardDefaults
import io.github.xgl34222220.ziyu.ui.design.ZiyuCircularProgressIndicator as CircularProgressIndicator
import io.github.xgl34222220.ziyu.ui.design.ZiyuHorizontalDivider as HorizontalDivider
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import io.github.xgl34222220.ziyu.ui.design.ZiyuIconButton as IconButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.ui.theme.ZiyuSectionHeading
import io.github.xgl34222220.ziyu.ui.theme.ZiyuLayoutTokens
import io.github.xgl34222220.ziyu.MixSlot
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.ui.font.fontCapabilityLabel
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTopBar
import io.github.xgl34222220.ziyu.ui.theme.ZiyuShapeTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.ZiyuListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt

@Composable
internal fun FontStudioScreenMiuix(state: FontStudioUiState, actions: FontStudioActions, topAction: @Composable () -> Unit) {
    FontStudioPageNative(UiStyle.MIUIX, state, actions, topAction)
}

@Composable
internal fun FontStudioScreenCoui(state: FontStudioUiState, actions: FontStudioActions, topAction: @Composable () -> Unit) {
    FontStudioPageNative(UiStyle.COUI, state, actions, topAction)
}

@Composable
private fun FontStudioPageNative(
    style: UiStyle,
    state: FontStudioUiState,
    actions: FontStudioActions,
    topAction: @Composable () -> Unit,
) {
    val layout = LocalZiyuThemeTokens.current.layout
    val applyRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.busy, state.taskState) {
        if (state.busy || state.taskState == "success" || state.taskState == "failed") {
            applyRequester.bringIntoView()
        }
    }
    val dockBottomPadding = maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            end = layout.pageHorizontal,
            bottom = dockBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(maxOf(layout.itemGap, 20.dp)),
    ) {
        item { NativeStudioHeader(state.loading, actions.refresh, topAction) }
        item { NativeCompositionMap(state, actions) }

        if (state.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) }
        }
        if (state.error.isNotBlank()) {
            item { NativeStudioNotice(state.error, error = true) }
        }

        state.slots.forEach { slotState ->
            item(key = slotState.slot.name) {
                StudioSlotEditorCard(slotState, state.busy || state.operationBusy, actions)
            }
        }
        item { StudioLatinTuneControls(state, actions) }

        item {
            Column(modifier = Modifier.bringIntoViewRequester(applyRequester),
                   verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NativeFinalAction(state, actions)
                if (state.busy || state.taskState == "success" || state.taskState == "failed") {
                    NativeStudioTask(state)
                    if (state.busy || state.operationBusy) {
                        OutlinedButton(onClick = actions.cancel, modifier = Modifier.fillMaxWidth()) { Text("终止当前任务") }
                    }
                }
            }
        }
        item { ZiyuSectionHeading("字形覆盖", "需要时查看所选中文字体包含哪些字符") }
        item { NativeCoverageGroup(state, actions) }
    }
}

@Composable
private fun NativeStudioHeader(loading: Boolean, onRefresh: () -> Unit, topAction: @Composable () -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    val actionColor = MaterialTheme.colorScheme.primary
    ZiyuTopBar(title = "字体组合") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            topAction()
            ZiyuHeaderAction(
                icon = Icons.Rounded.Refresh,
                contentDescription = "刷新组合配置",
                onClick = onRefresh,
                enabled = !loading,
                loading = loading,
                containerColor = Color.Transparent,
                contentColor = actionColor,
            )
        }
    }
}

@Composable
private fun NativeCompositionMap(state: FontStudioUiState, actions: FontStudioActions) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val coui = LocalUiStyle.current == UiStyle.COUI
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(layout.cardPadding),
        ) {
            Text(if (coui) "组合预览" else "组合预览", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text("分别选择中文、英文和数字字体。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (coui) StudioSelectionPreview(state.slots) else Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.elevatedCardBackground,
            ) {
                StudioSelectionPreview(state.slots)
            }

        }
    }
}

@Composable
private fun NativeStudioTask(state: FontStudioUiState) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
    ) {
        Column(Modifier.padding(layout.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .11f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.busy) "正在生成组合字体" else if (state.taskState == "failed") "组合未完成" else "组合字体已保存",
                        color = tokens.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(state.message, color = tokens.textSecondary, fontSize = 13.sp)
                }
                NativeStudioPill("${state.progress}%", MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(7.dp),
            )
        }
    }
}

@Composable
private fun NativeCoverageGroup(state: FontStudioUiState, actions: FontStudioActions) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val cjk = state.slots.firstOrNull { it.slot == MixSlot.Cjk }
    val fontId = cjk?.font?.id.orEmpty()
    val probe = state.coverage
    val metrics = probe.metrics.takeIf { probe.fontId == fontId }
    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
    ) {
        Column(Modifier.padding(layout.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("所选字体覆盖率", color = tokens.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(cjk?.font?.name ?: "请先选择中文基底", color = tokens.textSecondary, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { actions.inspectCoverage(fontId) },
                    enabled = fontId.isNotBlank() && !probe.loading && !state.busy && !state.operationBusy,
                    shape = RoundedCornerShape(17.dp),
                ) {
                    if (probe.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("检测")
                }
            }
            if (metrics != null) {
                Spacer(Modifier.height(12.dp))
                NativeCoverageRow("中文", metrics.cjkRatio)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
                NativeCoverageRow("英文", metrics.latinRatio)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
                NativeCoverageRow("数字", metrics.digitRatio)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
                NativeCoverageRow("标点", metrics.punctuationRatio)
                if (metrics.missingSample.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = tokens.textPrimary.copy(alpha = .035f),
                    ) {
                        Text(
                            "缺失示例：${metrics.missingSample}",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                            color = tokens.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else if (probe.error.isNotBlank() && probe.fontId == fontId) {
                Spacer(Modifier.height(10.dp))
                Text(probe.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NativeCoverageRow(label: String, ratio: Float) {
    val tokens = LocalZiyuPaletteTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tokens.textPrimary, modifier = Modifier.width(42.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.weight(1f).height(7.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("${(ratio * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NativeFinalAction(state: FontStudioUiState, actions: FontStudioActions) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val direct = state.directApplyFontId
    val selectionReady = state.slots.size == MixSlot.entries.size && state.slots.all { it.font?.valid == true }
    val shape = ZiyuShapeTokens.Large
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
    ) {
        Column(Modifier.padding(layout.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .11f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (direct != null) Icons.Rounded.FontDownload else Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (direct != null) "准备应用" else "生成组合",
                        color = tokens.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (direct != null) "三个部分使用同一款字体，可直接应用。"
                        else "按所选字重生成组合，保存到字体库并预览。",
                        color = tokens.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (direct != null) actions.applyDirect(direct) else actions.startMix("") },
                enabled = !state.loading && !state.busy && !state.operationBusy && selectionReady,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(
                    if (direct != null) Icons.Rounded.FontDownload else Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.busy || state.operationBusy) "正在处理，请稍候…"
                    else if (!selectionReady) "先选择组合字体"
                    else if (direct != null) "直接应用此字体" else "生成组合",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun NativeStudioNotice(message: String, error: Boolean) {
    val tokens = LocalZiyuPaletteTokens.current
    val containerColor = if (error) MaterialTheme.colorScheme.errorContainer else tokens.cardBackground
    Surface(
        color = containerColor.withCardTransparency(),
        border = ZiyuCardDefaults.border(containerColor),
        shadowElevation = LocalZiyuThemeTokens.current.layout.cardElevation,
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else tokens.textPrimary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun NativeStudioPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = .12f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
