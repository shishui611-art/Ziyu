package io.github.xgl34222220.ziyu.ui.logs

import io.github.xgl34222220.ziyu.ui.theme.ZiyuLayoutTokens
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.RestartAlt
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import io.github.xgl34222220.ziyu.ui.design.ZiyuLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens

/** The task snapshot owns liveness. Historical text never starts an animation. */
@Composable
internal fun FlashProgressCard(state: LogsUiState) {
    val tokens = LocalZiyuPaletteTokens.current
    val style = LocalUiStyle.current
    val layout = LocalZiyuThemeTokens.current.layout
    val task = primaryFlashTask(state.tasks)
    val running = task?.active == true
    val failed = task?.phase == TaskPhase.FAILED
    val reboot = !failed && !running && state.rebootRequired
    val complete = !failed && !running && !reboot && task?.completed == true
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        reboot -> tokens.warning
        complete -> tokens.success
        else -> MaterialTheme.colorScheme.primary
    }
    val title = when {
        running -> if (task?.phase == TaskPhase.QUEUED) "正在等待执行" else "正在处理字体"
        failed -> if (task?.kind == TaskKind.APPLY) "字体应用失败" else "本次任务失败"
        reboot -> "准备就绪，等待重启"
        complete -> if (task?.kind == TaskKind.APPLY) "字体应用成功" else "本次任务已完成"
        else -> "等待新的字体任务"
    }
    val progress by animateFloatAsState(
        targetValue = ((task?.progress ?: 0).coerceIn(0, 100) / 100f),
        animationSpec = tween(280), label = "flashProgress",
    )
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardBackground, contentColor = tokens.textPrimary),
    ) {
        Column(
            modifier = Modifier.animateContentSize(tween(220))
                .padding(layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(layout.itemGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = .12f)) {
                    Box(Modifier.size(if (style == UiStyle.MATERIAL) 48.dp else 36.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            when { failed -> Icons.Rounded.Close; reboot -> Icons.Rounded.RestartAlt
                                complete -> Icons.Rounded.Check; else -> Icons.Rounded.FontDownload },
                            contentDescription = null, tint = accent, modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("字体任务", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(title, color = tokens.textPrimary, style = if (style == UiStyle.MATERIAL) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                }
            }
            if (running) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(task?.kind?.label.orEmpty(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if ((task?.progress ?: -1) >= 0) {
                        Text("${task!!.progress.coerceIn(0, 100)}", color = tokens.textPrimary, style = MaterialTheme.typography.headlineMedium)
                        Text(" %", color = tokens.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                    } else {
                        Text("请稍候", color = tokens.textSecondary, fontSize = 14.sp)
                    }
                }
                val track = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(7.dp))
                if ((task?.progress ?: -1) >= 0) {
                    LinearProgressIndicator(progress = { progress }, modifier = track, color = accent, trackColor = accent.copy(alpha = .1f))
                } else {
                    LinearProgressIndicator(modifier = track, color = accent, trackColor = accent.copy(alpha = .1f))
                }
            }
            Text(
                task?.message?.let(::taskDisplayMessage)?.ifBlank { null }
                    ?: "从字体库选择字体后，这里会展示实际进度与处理结果。",
                color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            if (reboot) {
                Text("字体已准备，请完整重启后查看验证结果。", color = accent, style = MaterialTheme.typography.bodySmall)
            } else if (failed) {
                Text("展开下方详情查看原因；原始输出保留在日志页。", color = accent, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlashCount("进行中", state.activeTaskCount, Modifier.weight(1f))
                FlashCount("已完成", state.completedTaskCount, Modifier.weight(1f))
                FlashCount("未完成", state.failedTaskCount, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlashCount(label: String, count: Int, modifier: Modifier) {
    val tokens = LocalZiyuPaletteTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), color = tokens.textPrimary, style = MaterialTheme.typography.titleLarge)
        Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
