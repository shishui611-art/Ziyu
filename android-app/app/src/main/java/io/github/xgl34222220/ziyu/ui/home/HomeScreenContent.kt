package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.*

/** Shared business sections; the three page shells decide their order and presentation. */
internal val HomeUiState.hasTaskNotice: Boolean
    get() = taskRunning || rebootRequired || error.isNotBlank() || romCheckMessage.isNotBlank()

@Composable
internal fun HomePageHeader(loading: Boolean, actions: HomeActions) {
    ZiyuTopBar("字域") {
        ZiyuHeaderAction(Icons.Rounded.Description, "任务中心", actions.openLogs, Color.Transparent)
        ZiyuHeaderAction(Icons.Rounded.Refresh, "刷新", actions.refresh, Color.Transparent, loading = loading)
    }
}

@Composable
internal fun HomeConnectionRow(moduleInstalled: Boolean, rootGranted: Boolean, version: String) {
    val palette = LocalZiyuPaletteTokens.current
    val ready = moduleInstalled && rootGranted
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZiyuSurface(
            modifier = Modifier.size(7.dp),
            shape = CircleShape,
            color = if (ready) palette.success else MaterialTheme.colorScheme.error,
        ) {}
        Spacer(Modifier.width(8.dp))
        ZiyuText(
            if (ready) "模块已连接" else if (moduleInstalled) "等待 Root 授权" else "等待模块连接",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        ZiyuText(version, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
    }
}

@Composable
internal fun HomeCurrentFont(
    style: UiStyle,
    fontName: String,
    moduleInstalled: Boolean,
    rootGranted: Boolean,
    version: String,
    next: HomeNextStep,
) {
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme
    val layout = LocalZiyuThemeTokens.current.layout
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.cardBackground, contentColor = palette.textPrimary),
    ) {
        Column(
            Modifier.padding(layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(layout.itemGap),
        ) {
            if (style == UiStyle.MATERIAL) HomeConnectionRow(moduleInstalled, rootGranted, version)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZiyuText("当前字体", style = MaterialTheme.typography.labelLarge, color = palette.textSecondary)
                ZiyuText(
                    fontName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (style) {
                UiStyle.MIUIX -> {
                    ZiyuHorizontalDivider(color = scheme.outlineVariant.copy(alpha = .45f))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZiyuText("天地有大美，字里见山河", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Normal)
                        ZiyuText("Aa Bb  0123456789", style = MaterialTheme.typography.titleMedium, color = palette.textSecondary)
                    }
                }
                UiStyle.COUI -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ZiyuText("Aa", style = MaterialTheme.typography.displaySmall, color = scheme.primary, fontWeight = FontWeight.Normal)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        ZiyuText("字里见山河", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal)
                        ZiyuText("0123456789", style = MaterialTheme.typography.bodyLarge, color = palette.textSecondary)
                    }
                }
                UiStyle.MATERIAL -> ZiyuSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.primaryContainer.copy(alpha = .55f),
                    contentColor = scheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZiyuText("字里见山河", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Normal)
                        ZiyuText("Aa Bb  0123456789", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            ZiyuButton(
                onClick = next.onClick,
                enabled = next.enabled,
                modifier = if (style == UiStyle.MATERIAL) Modifier.align(Alignment.End) else Modifier.fillMaxWidth(),
            ) {
                ZiyuGlyph(next.icon, null, ZiyuIconTokens.ToolGlyph)
                Spacer(Modifier.width(8.dp))
                ZiyuText(next.actionLabel)
            }
        }
    }
}

@Composable
internal fun HomeTaskNotice(state: HomeUiState, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalZiyuPaletteTokens.current
    val failed = state.error.isNotBlank() && !state.taskRunning
    val romNotice = state.romCheckMessage.isNotBlank() && !state.taskRunning && !state.rebootRequired && !failed
    ZiyuSurface(
        onClick = actions.openLogs,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (failed) scheme.errorContainer else palette.cardBackground,
        contentColor = if (failed) scheme.onErrorContainer else palette.textPrimary,
        border = ZiyuCardDefaults.border(if (failed) scheme.errorContainer else palette.cardBackground),
        shadowElevation = LocalZiyuThemeTokens.current.layout.cardElevation,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuGlyph(
                    if (failed) Icons.Rounded.Warning else if (state.rebootRequired) Icons.Rounded.RestartAlt else Icons.Rounded.Refresh,
                    null, ZiyuIconTokens.StatusGlyph,
                    tint = if (failed) scheme.error else scheme.primary,
                )
                ZiyuText(
                    if (romNotice) "系统字体基准核验" else if (state.rebootRequired && !state.taskRunning) "重启后生效" else state.taskTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.taskRunning) ZiyuText("${state.taskProgress.coerceIn(0, 100)}%", style = MaterialTheme.typography.labelLarge)
                ZiyuGlyph(Icons.Rounded.ChevronRight, null, ZiyuIconTokens.TrailingGlyph)
            }
            ZiyuText(
                if (failed) state.error else if (romNotice) state.romCheckMessage else nextStepFor(state, actions).description,
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.taskRunning) ZiyuLinearProgressIndicator(
                progress = { state.taskProgress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun HomeNavigation(style: UiStyle, actions: HomeActions) {
    val palette = LocalZiyuPaletteTokens.current
    if (style == UiStyle.MATERIAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeActionTile("字体库", "导入与预览", Icons.Rounded.FontDownload, actions.openFontLibrary, Modifier.weight(1f))
            HomeActionTile("字体组合", "中文、英文与数字", Icons.Rounded.Layers, actions.openFontStudio, Modifier.weight(1f))
        }
    } else {
        ZiyuSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = palette.cardBackground,
            border = ZiyuCardDefaults.border(),
            shadowElevation = LocalZiyuThemeTokens.current.layout.cardElevation,
        ) {
            Column {
                HomeNavigationRow("字体库", "导入、预览并应用字体", Icons.Rounded.FontDownload, actions.openFontLibrary)
                ZiyuHorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                HomeNavigationRow("字体组合", "选择中文、英文与数字字体", Icons.Rounded.Layers, actions.openFontStudio)
            }
        }
    }
}

@Composable
private fun HomeActionTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    ZiyuCard(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZiyuGlyph(icon, null, ZiyuIconTokens.SectionGlyph)
            ZiyuText(title, style = MaterialTheme.typography.titleMedium)
            ZiyuText(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HomeNavigationRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ZiyuListItem(
        onClick = onClick,
        onClickLabel = title,
        headlineContent = { ZiyuText(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { ZiyuText(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { ZiyuGlyph(icon, null, ZiyuIconTokens.SectionGlyph, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { ZiyuGlyph(Icons.Rounded.ChevronRight, null, ZiyuIconTokens.TrailingGlyph) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun HomeDevicePanel(state: HomeUiState, trustContent: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val palette = LocalZiyuPaletteTokens.current
    ZiyuSurface(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = palette.cardBackground, border = ZiyuCardDefaults.border(),
        shadowElevation = LocalZiyuThemeTokens.current.layout.cardElevation,
    ) {
        Column {
            ZiyuListItem(
                modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                onClick = { expanded = !expanded },
                onClickLabel = "设备与生效状态",
                headlineContent = { ZiyuText("设备与生效状态", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    ZiyuText(
                        if (state.rootGranted && state.mountHealthy) "${state.rootManager} · 挂载正常" else "查看权限、挂载与字体检查",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = { ZiyuGlyph(Icons.Rounded.Security, null, ZiyuIconTokens.SectionGlyph, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { ZiyuGlyph(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, ZiyuIconTokens.TrailingGlyph) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZiyuText("Root：${if (state.rootGranted) state.rootManager else "未授权"}", style = MaterialTheme.typography.bodyMedium)
                    ZiyuText("挂载：${state.mountEngine}", style = MaterialTheme.typography.bodyMedium)
                    trustContent()
                }
            }
        }
    }
}

@Composable
internal fun HomeRecovery(enabled: Boolean, onRestore: () -> Unit) {
    ZiyuTextButton(
        onClick = onRestore,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZiyuGlyph(Icons.Rounded.RestartAlt, null, ZiyuIconTokens.ToolGlyph)
        Spacer(Modifier.width(8.dp))
        ZiyuText("恢复系统字体")
    }
}

@Composable
internal fun HomeCommunityLinks() {
    val uriHandler = LocalUriHandler.current
    val palette = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.cardBackground, contentColor = palette.textPrimary),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(layout.compactPadding).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeCommunityLink("酷安主页", Icons.Rounded.AccountCircle, Modifier.weight(1f).fillMaxHeight()) {
                uriHandler.openUri("https://www.coolapk.com/u/28601616")
            }
            HomeCommunityLink("QQ群", Icons.Rounded.Forum, Modifier.weight(1f).fillMaxHeight()) {
                uriHandler.openUri("https://qm.qq.com/q/XgWlqIOI8Q")
            }
        }
    }
}

@Composable
private fun HomeCommunityLink(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ZiyuSurface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = scheme.primary.copy(alpha = .08f),
        contentColor = scheme.primary,
        onClick = onClick,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZiyuGlyph(icon, null, ZiyuIconTokens.ToolGlyph, tint = scheme.primary)
                ZiyuText(
                    title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Immutable
internal data class HomeNextStep(
    val actionLabel: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val description: String = "",
    val enabled: Boolean = true,
)

internal fun nextStepFor(state: HomeUiState, actions: HomeActions): HomeNextStep = when {
    !state.moduleInstalled || !state.rootGranted -> HomeNextStep("重新检查", Icons.Rounded.Refresh, actions.refresh, "安装模块并授予 Root 权限后才能应用全局字体")
    state.taskRunning -> HomeNextStep("查看任务", Icons.Rounded.Description, actions.openLogs, state.taskMessage.ifBlank { "可以离开 App，后台任务会继续运行" })
    state.rebootRequired -> HomeNextStep(
        if (state.temporaryRootMode) "立即软重启" else "立即重启",
        Icons.Rounded.RestartAlt, actions.reboot,
        if (state.temporaryRootMode) "通过内置脚本请求 KernelSU 软重启，完成后返回查看挂载验证" else "执行一次完整重启后应用全局字体并自动验证",
    )
    state.error.isNotBlank() -> HomeNextStep("查看问题", Icons.Rounded.Warning, actions.openLogs, "打开任务中心查看错误原因与诊断信息")
    state.currentFont.contains("系统") -> HomeNextStep("打开字体库", Icons.Rounded.FontDownload, actions.openFontLibrary)
    else -> HomeNextStep("打开组合", Icons.Rounded.Layers, actions.openFontStudio)
}
