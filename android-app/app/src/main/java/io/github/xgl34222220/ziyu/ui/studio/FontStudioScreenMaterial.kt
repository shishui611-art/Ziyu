package io.github.xgl34222220.ziyu.ui.studio

import android.view.Gravity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard as Card
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
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTopBar
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import kotlin.math.roundToInt

@Composable
internal fun FontStudioScreenMaterial(
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            end = layout.pageHorizontal,
            bottom = maxOf(LocalDockContentPadding.current, layout.dockSafeBottom),
        ),
        verticalArrangement = Arrangement.spacedBy(maxOf(layout.itemGap, 20.dp)),
    ) {
        item { MaterialStudioHeader(state.loading, actions.refresh, topAction) }
        item { MaterialCompositionOverview(state, actions) }

        if (state.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) }
        }
        if (state.error.isNotBlank()) {
            item { MaterialStudioNotice(state.error, error = true) }
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
                MaterialFinalAction(state, actions)
                if (state.busy || state.taskState == "success" || state.taskState == "failed") {
                    MaterialStudioTask(state)
                    if (state.busy || state.operationBusy) {
                        androidx.compose.material3.OutlinedButton(onClick = actions.cancel, modifier = Modifier.fillMaxWidth()) { Text("终止当前任务") }
                    }
                }
            }
        }
        item { ZiyuSectionHeading("字形覆盖", "需要时查看所选中文字体包含哪些字符") }
        item { MaterialCoverageCard(state, actions) }
    }
}

@Composable
private fun MaterialStudioHeader(loading: Boolean, onRefresh: () -> Unit, topAction: @Composable () -> Unit) {
    val actionColor = MaterialTheme.colorScheme.primary
    ZiyuTopBar(title = "字体组合") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            topAction()
            ZiyuHeaderAction(
                icon = Icons.Rounded.Refresh,
                contentDescription = "刷新组合配置",
                onClick = onRefresh,
                enabled = !loading,
                loading = loading,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .84f),
                contentColor = actionColor,
            )
        }
    }
}

@Composable
private fun MaterialCompositionOverview(state: FontStudioUiState, actions: FontStudioActions) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("组合预览", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("分别选择中文、英文和数字字体。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Color.Transparent,
            ) {
                StudioSelectionPreview(state.slots)
            }

        }
    }
}

@Composable
private fun MaterialStudioTask(state: FontStudioUiState) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (state.busy) "正在生成组合字体" else if (state.taskState == "failed") "组合未完成" else "组合字体已保存", fontWeight = FontWeight.SemiBold)
                    Text(state.message, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .74f), fontSize = 12.sp)
                }
                Text("${state.progress}%", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(13.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(7.dp),
            )
        }
    }
}

@Composable
private fun MaterialCoverageCard(state: FontStudioUiState, actions: FontStudioActions) {
    val cjk = state.slots.firstOrNull { it.slot == MixSlot.Cjk }
    val fontId = cjk?.font?.id.orEmpty()
    val probe = state.coverage
    val metrics = probe.metrics.takeIf { probe.fontId == fontId }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("所选字体覆盖率", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(cjk?.font?.name ?: "请先选择中文基底", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { actions.inspectCoverage(fontId) },
                    enabled = fontId.isNotBlank() && !probe.loading && !state.busy && !state.operationBusy,
                ) {
                    if (probe.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("检测")
                }
            }
            if (metrics != null) {
                Spacer(Modifier.height(13.dp))
                MaterialCoverageRow("中文", metrics.cjkRatio)
                MaterialCoverageRow("英文", metrics.latinRatio)
                MaterialCoverageRow("数字", metrics.digitRatio)
                MaterialCoverageRow("标点", metrics.punctuationRatio)
                if (metrics.missingSample.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("缺失示例：${metrics.missingSample}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            } else if (probe.error.isNotBlank() && probe.fontId == fontId) {
                Spacer(Modifier.height(10.dp))
                Text(probe.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MaterialCoverageRow(label: String, ratio: Float) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(42.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.weight(1f).height(7.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("${(ratio * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MaterialFinalAction(state: FontStudioUiState, actions: FontStudioActions) {
    val direct = state.directApplyFontId
    val selectionReady = state.slots.size == MixSlot.entries.size && state.slots.all { it.font?.valid == true }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
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
                    Text(if (direct != null) "准备应用" else "生成组合", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (direct != null) "三个部分使用同一款字体，可直接应用。"
                        else "按所选字重生成组合，保存到字体库并预览。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(17.dp))
            Button(
                onClick = { if (direct != null) actions.applyDirect(direct) else actions.startMix("") },
                enabled = !state.loading && !state.busy && !state.operationBusy && selectionReady,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(if (direct != null) Icons.Rounded.FontDownload else Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.busy || state.operationBusy) "正在处理，请稍候…"
                    else if (!selectionReady) "先选择组合字体"
                    else if (direct != null) "直接应用此字体" else "生成组合",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MaterialStudioNotice(message: String, error: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(10.dp))
            Text(message, modifier = Modifier.weight(1f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MaterialStudioPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = .12f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
