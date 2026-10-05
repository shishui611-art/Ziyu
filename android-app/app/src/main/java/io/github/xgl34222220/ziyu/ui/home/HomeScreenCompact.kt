package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.ZiyuLayoutTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalMiuixTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuLoadingSkeleton
import io.github.xgl34222220.ziyu.ui.theme.ZiyuGlyph
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import io.github.xgl34222220.ziyu.ui.theme.ZiyuIconTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuSectionHeading
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTopBar
import io.github.xgl34222220.ziyu.ui.theme.ZiyuShapeTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTypographyTokens

@Composable
internal fun HomeScreenCompact(
    style: UiStyle,
    state: HomeUiState,
    actions: HomeActions,
    trustContent: @Composable () -> Unit,
) {
    val tokens = LocalMiuixTokens.current
    val scheme = MaterialTheme.colorScheme
    val cardColor = tokens.cardBackground
    val textPrimary = tokens.textPrimary
    val textSecondary = tokens.textSecondary
    val shape = MaterialTheme.shapes.large
    var deviceDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    val canChange = state.moduleInstalled && state.rootGranted && !state.taskRunning
    val next = nextStepFor(state, actions)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ZiyuLayoutTokens.PageHorizontal,
            end = ZiyuLayoutTokens.PageHorizontal,
            bottom = maxOf(LocalDockContentPadding.current, ZiyuLayoutTokens.FloatingDockSafeBottom),
        ),
        verticalArrangement = Arrangement.spacedBy(ZiyuLayoutTokens.ItemGap),
    ) {
        item(key = "header") {
            ZiyuTopBar(title = "字域") {
                ZiyuHeaderAction(
                    icon = Icons.Rounded.Description,
                    contentDescription = "任务中心",
                    onClick = actions.openLogs,
                    containerColor = cardColor,
                )
                ZiyuHeaderAction(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    onClick = actions.refresh,
                    containerColor = cardColor,
                    loading = state.loading,
                )
            }
        }
        item(key = "current-font") {
            val dark = scheme.background.luminance() < .5f
            val statusHealthy = state.moduleInstalled && state.rootGranted && state.mountHealthy &&
                !state.taskRunning && !state.rebootRequired && state.error.isBlank()
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (statusHealthy) tokens.successContainer else cardColor,
                shadowElevation = ZiyuLayoutTokens.CardElevation,
                border = BorderStroke(
                    0.5.dp,
                    if (dark) Color.Transparent else ZiyuLayoutTokens.LightCardOutline,
                ),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (statusHealthy) {
                        ZiyuGlyph(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            size = 148.dp,
                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 28.dp, y = 28.dp),
                            tint = tokens.success.copy(alpha = .10f),
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .background(
                                if (statusHealthy) Brush.verticalGradient(listOf(tokens.successContainer, tokens.successContainer))
                                else Brush.linearGradient(listOf(scheme.primary.copy(alpha = .08f), cardColor)),
                            )
                            .padding(ZiyuLayoutTokens.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = cardColor.copy(alpha = .72f)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(if (state.moduleInstalled && state.rootGranted) tokens.success else tokens.warning, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when {
                                        state.loading -> "正在连接"
                                        !state.moduleInstalled -> "等待模块"
                                        !state.rootGranted -> "需要授权"
                                        state.taskRunning -> "正在处理"
                                        state.rebootRequired -> "等待重启"
                                        else -> "模块已连接"
                                    },
                                    color = textPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(state.version, color = textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前字体", color = textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(state.currentFont, color = textPrimary, fontSize = ZiyuTypographyTokens.StatusValue, lineHeight = 38.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Aa Bb  ·  0123456789", color = scheme.primary, fontSize = 19.sp,
                            lineHeight = 28.sp, letterSpacing = .5.sp)
                    }
                    Button(
                        onClick = next.onClick,
                        enabled = next.enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = ZiyuShapeTokens.Medium,
                    ) {
                        ZiyuGlyph(next.icon, null, ZiyuIconTokens.ToolGlyph)
                        Spacer(Modifier.width(8.dp))
                        Text(next.actionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        }
        if (state.taskRunning || state.rebootRequired || state.error.isNotBlank()) {
            item(key = "task-status") {
                val failed = state.error.isNotBlank() && !state.taskRunning
                Surface(
                    color = if (failed) scheme.errorContainer else cardColor,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = actions.openLogs),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ZiyuGlyph(
                                if (failed) Icons.Rounded.Warning else if (state.rebootRequired) Icons.Rounded.RestartAlt else Icons.Rounded.Refresh,
                                null, ZiyuIconTokens.StatusGlyph,
                                tint = if (failed) scheme.error else scheme.primary,
                            )
                            Text(if (state.rebootRequired && !state.taskRunning) "重启后生效" else state.taskTitle,
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                color = if (failed) scheme.onErrorContainer else textPrimary)
                            if (state.taskRunning) Text("${state.taskProgress.coerceIn(0, 100)}%", color = scheme.primary,
                                style = MaterialTheme.typography.titleSmall)
                            ZiyuGlyph(Icons.Rounded.ChevronRight, null, ZiyuIconTokens.TrailingGlyph, tint = textSecondary)
                        }
                        Text(if (failed) state.error else next.description,
                            color = if (failed) scheme.onErrorContainer else textSecondary,
                            style = MaterialTheme.typography.bodySmall)
                        if (state.taskRunning) LinearProgressIndicator(
                            progress = { state.taskProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        )
                    }
                }
                }
            }
        if (state.moduleInstalled && state.rootGranted) {
            item(key = "global-font-weight") {
                HomeGlobalWeightCard(
                    state = state.systemWeight,
                    actions = actions,
                    cardColor = cardColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                )
            }
        }
        item(key = "font-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuSectionHeading("我的字体", "从挑选到组合，让每一处文字更合心意")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeShortcut("字体库", "导入 · 预览 · 应用", Icons.Rounded.FontDownload, actions.openFontLibrary,
                        Modifier.weight(1f))
                    HomeShortcut("字体组合", "中文 · 英文 · 数字", Icons.Rounded.Layers, actions.openFontStudio,
                        Modifier.weight(1f))
                }
            }
        }
        item(key = "device-details") {
            Surface(shape = shape, color = cardColor) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .semantics { stateDescription = if (deviceDetailsExpanded) "已展开" else "已收起" }
                            .clickable(role = Role.Button) { deviceDetailsExpanded = !deviceDetailsExpanded }
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ZiyuGlyph(Icons.Rounded.Security, null, ZiyuIconTokens.StatusGlyph, tint = scheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("设备与生效状态", style = MaterialTheme.typography.titleSmall, color = textPrimary)
                            Text(if (state.rootGranted && state.mountHealthy) "${state.rootManager} · 挂载正常" else "查看权限、挂载与字体检查",
                                style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        }
                        ZiyuGlyph(if (deviceDetailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            null, ZiyuIconTokens.ToolGlyph, tint = textSecondary)
                    }
                    AnimatedVisibility(visible = deviceDetailsExpanded) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactStatusCell(Modifier.weight(1f), Icons.Rounded.Security, "Root",
                                    if (state.rootGranted) state.rootManager else "未授权", state.rootGranted, textPrimary, textSecondary)
                                CompactStatusCell(Modifier.weight(1f), Icons.Rounded.Layers, "挂载",
                                    state.mountEngine, state.mountHealthy, textPrimary, textSecondary)
                            }
                            trustContent()
                        }
                    }
                }
            }
        }
        item(key = "restore") {
            OutlinedButton(
                onClick = actions.restoreDefault,
                enabled = canChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                ZiyuGlyph(Icons.Rounded.RestartAlt, null, ZiyuIconTokens.ToolGlyph)
                Spacer(Modifier.width(8.dp))
                Text("恢复系统字体")
            }
        }
    }
}

@Composable
private fun HomeGlobalWeightCard(
    state: HomeWeightUiState,
    actions: HomeActions,
    cardColor: Color,
    textPrimary: Color,
    textSecondary: Color,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cardColor,
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = ZiyuLayoutTokens.CardElevation,
    ) {
        Column(
            Modifier.padding(ZiyuLayoutTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZiyuGlyph(Icons.Rounded.Speed, null, ZiyuIconTokens.SectionGlyph, tint = scheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("全局粗细微调", color = textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text("只调整系统粗细，不改字体文件", color = textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (state.loading) "读取中" else state.weight.toString(),
                    color = scheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            when {
                state.loading -> ZiyuLoadingSkeleton(
                    Modifier.fillMaxWidth().height(12.dp),
                    shape = RoundedCornerShape(999.dp),
                )
                !state.supported -> Text(
                    state.error.ifBlank { "当前系统不支持全局粗细微调" },
                    color = scheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Slider(
                        value = state.weight.coerceIn(state.min, state.max).toFloat(),
                        onValueChange = actions.previewSystemWeight,
                        enabled = !state.applying,
                        valueRange = state.min.toFloat()..state.max.toFloat(),
                        steps = (((state.max - state.min) / state.step) - 1).coerceAtLeast(0),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.min} · 细", color = textSecondary, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text("${state.max} · 粗", color = textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.error.ifBlank { state.message },
                            modifier = Modifier.weight(1f),
                            color = if (state.error.isNotBlank()) scheme.error else textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                        TextButton(
                            onClick = actions.resetSystemWeight,
                            enabled = state.ownedByModule && !state.applying,
                        ) {
                            Text("恢复原始")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeShortcut(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val tokens = LocalMiuixTokens.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = tokens.cardBackground,
        shadowElevation = ZiyuLayoutTokens.CardElevation,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    ZiyuGlyph(icon, null, ZiyuIconTokens.StatusGlyph, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class HomeNextStep(
    val title: String,
    val description: String,
    val actionLabel: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private fun nextStepFor(state: HomeUiState, actions: HomeActions): HomeNextStep = when {
    !state.moduleInstalled || !state.rootGranted -> HomeNextStep(
        title = "连接字域模块",
        description = "安装模块并授予 Root 权限后才能应用全局字体",
        actionLabel = "重新检查",
        icon = Icons.Rounded.Refresh,
        onClick = actions.refresh,
    )
    state.taskRunning -> HomeNextStep(
        title = "字体任务正在处理",
        description = state.taskMessage.ifBlank { "可以离开 App，后台任务会继续运行" },
        actionLabel = "查看任务",
        icon = Icons.Rounded.Description,
        onClick = actions.openLogs,
    )
    state.rebootRequired -> HomeNextStep(
        title = "字体已经准备完成",
        description = "执行一次完整重启后应用全局字体并自动验证",
        actionLabel = "立即重启",
        icon = Icons.Rounded.RestartAlt,
        onClick = actions.reboot,
    )
    state.error.isNotBlank() -> HomeNextStep(
        title = "发现需要处理的问题",
        description = "打开任务中心查看错误原因与诊断信息",
        actionLabel = "查看问题",
        icon = Icons.Rounded.Warning,
        onClick = actions.openLogs,
    )
    state.currentFont.contains("系统") -> HomeNextStep(
        title = "选择一款字体",
        description = "从字体库导入、预览并应用单字体",
        actionLabel = "打开字体库",
        icon = Icons.Rounded.FontDownload,
        onClick = actions.openFontLibrary,
    )
    else -> HomeNextStep(
        title = "继续调整当前字体",
        description = "组合中文、英文与数字字体，或调整真实设计轴",
        actionLabel = "打开组合",
        icon = Icons.Rounded.Layers,
        onClick = actions.openFontStudio,
    )
}


private fun homeOpticalScale(icon: ImageVector): Float = when (icon) {
    Icons.Rounded.Security -> .95f
    Icons.Rounded.Layers -> .96f
    Icons.Rounded.Description -> .96f
    Icons.Rounded.FontDownload -> .98f
    Icons.Rounded.Warning -> .96f
    Icons.Rounded.RestartAlt -> .96f
    else -> 1f
}

@Composable
private fun CompactStatusCell(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    healthy: Boolean,
    textPrimary: Color,
    textSecondary: Color,
) {
    val accent = if (healthy) LocalMiuixTokens.current.success else MaterialTheme.colorScheme.error
    Row(
        modifier = modifier.padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(13.dp),
            color = accent.copy(alpha = .09f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                ZiyuGlyph(
                    imageVector = icon,
                    contentDescription = null,
                    size = ZiyuIconTokens.SectionGlyph,
                    opticalScale = homeOpticalScale(icon),
                    tint = accent,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                value,
                color = textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$title · ${if (healthy) "正常" else "需检查"}",
                color = if (healthy) textSecondary else accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

