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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.ui.theme.LocalMiuixTokens

/** The task snapshot owns liveness. Historical text never starts an animation. */
@Composable
internal fun FlashProgressCard(state: LogsUiState) {
    val tokens = LocalMiuixTokens.current
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
        failed -> "这次应用未完成"
        reboot -> if (state.temporaryRootMode) "准备就绪，等待软重启" else "准备就绪，等待重启"
        complete -> "本次任务已完成"
        else -> "等待新的字体任务"
    }
    val progress by animateFloatAsState(
        targetValue = ((task?.progress ?: 0).coerceIn(0, 100) / 100f),
        animationSpec = tween(280), label = "flashProgress",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = tokens.cardBackground,
        shadowElevation = ZiyuLayoutTokens.CardElevation,
    ) {
        Column(
            modifier = Modifier.animateContentSize(tween(220))
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = .075f), tokens.cardBackground)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = .12f)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            when { failed -> Icons.Rounded.Close; reboot -> Icons.Rounded.RestartAlt
                                complete -> Icons.Rounded.Check; else -> Icons.Rounded.FontDownload },
                            contentDescription = null, tint = accent, modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("字体刷写", color = tokens.textSecondary, fontSize = 12.sp)
                    Text(title, color = tokens.textPrimary, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (running) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(task?.kind?.label.orEmpty(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if ((task?.progress ?: -1) >= 0) {
                        Text("${task!!.progress.coerceIn(0, 100)}", color = tokens.textPrimary, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
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
                color = tokens.textSecondary, fontSize = 14.sp, lineHeight = 22.sp,
            )
            if (reboot) {
                Text("文件已准备，不代表当前系统已经切换。请完整重启手机。", color = accent, fontSize = 13.sp, lineHeight = 20.sp)
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
    val tokens = LocalMiuixTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), color = tokens.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = tokens.textSecondary, fontSize = 12.sp)
    }
}
