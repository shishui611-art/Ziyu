package io.github.xgl34222220.ziyu.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import io.github.xgl34222220.ziyu.ui.navigation.rememberPredictiveBack
import io.github.xgl34222220.ziyu.ui.navigation.predictiveBackMotion
import io.github.xgl34222220.ziyu.ui.navigation.predictiveBackPreviewMotion
import io.github.xgl34222220.ziyu.ui.navigation.backDestinationPreview
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import io.github.xgl34222220.ziyu.ui.theme.ZiyuMotionTokens
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard as Card
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCardDefaults
import io.github.xgl34222220.ziyu.ui.theme.ZiyuSegmentedTabs
import androidx.compose.material3.CardDefaults
import io.github.xgl34222220.ziyu.ui.design.ZiyuCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import io.github.xgl34222220.ziyu.ui.design.ZiyuHorizontalDivider as HorizontalDivider
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import io.github.xgl34222220.ziyu.ui.design.ZiyuIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuModalBottomSheet as ModalBottomSheet
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuRadioButton as RadioButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuSwitch as Switch
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.design.rememberZiyuModalBottomSheetState as rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.xgl34222220.ziyu.BuildConfig
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.appearance.AccentOptions
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceSettings
import io.github.xgl34222220.ziyu.ui.appearance.KolorStyle
import io.github.xgl34222220.ziyu.ui.appearance.ThemeMode
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.theme.ZiyuLayoutTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuShapeTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.ZiyuDetailBar
import io.github.xgl34222220.ziyu.ui.theme.ZiyuGlyph
import io.github.xgl34222220.ziyu.ui.theme.ZiyuIconTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTopBar
import kotlinx.coroutines.launch
import org.json.JSONObject

data class AppearanceActions(
    val setUiStyle: (UiStyle) -> Unit,
    val setThemeMode: (ThemeMode) -> Unit,
    val setSeedArgb: (Int) -> Unit,
    val setKolorStyle: (KolorStyle) -> Unit,
    val setColorStandard: (io.github.xgl34222220.ziyu.ui.appearance.ColorStandard) -> Unit = {},
    val setMonetEnabled: (Boolean) -> Unit,
    val setAmoledBlack: (Boolean) -> Unit,
    val setBlurEnabled: (Boolean) -> Unit,
    val setGlassEnabled: (Boolean) -> Unit,
    val setFloatingDock: (Boolean) -> Unit,
    val setHighRefreshRate: (Boolean) -> Unit,
    val setBackgroundPath: (String) -> Unit = {},
    val setThemeDisplay: (ThemeMode, Boolean) -> Unit = { _, _ -> },
    val setPredictiveBack: (Boolean) -> Unit = {},
)

internal enum class SettingsSection(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val opticalScale: Float,
) {
    OVERVIEW("字域状态", "版本、Root、挂载与当前字体", Icons.Rounded.Settings, .94f),
    APPEARANCE("外观与主题", "颜色、深色模式与界面效果", Icons.Rounded.Palette, 1.00f),
    NOTIFICATIONS("通知与实时活动", "授权状态、进度通知与预览", Icons.Rounded.Info, 1.00f),
    SAFETY("安全与维护", "字体加载检查、冲突与安全清理", Icons.Rounded.Security, .96f),
    BOOT_SAFETY("开机保护", "查看保护状态与解除保护", Icons.Rounded.Security, .96f),
    APP_COVERAGE("应用字体诊断", "按应用检查字体来源，扫描未覆盖项", Icons.Rounded.Search, .98f),
    GOOGLE("Google 字体兼容", "谷歌英数回退处理、状态与中文说明", Icons.Rounded.Build, .96f),
    STORAGE("文件与配置位置", "用户文件和显示参数配置的保存目录", Icons.Rounded.Backup, 1.08f),
    BACKUP("备份与恢复", "完整备份字域数据和组合方案", Icons.Rounded.Backup, 1.08f),
    UPDATE("模块更新", "GitHub 正式版模块与更新说明", Icons.Rounded.SystemUpdate, 1.03f),
}

@Composable
fun AppearanceSettingsRoute(
    settings: AppearanceSettings,
    actions: AppearanceActions,
    onOpenTasks: () -> Unit = {},
    onDetailChanged: (Boolean) -> Unit = {},
    onTemporaryRootChanged: () -> Unit = {},
    active: Boolean = true,
) {
    SettingsHubRoute(settings, actions, onOpenTasks, onDetailChanged, onTemporaryRootChanged, active)
}

@Composable
internal fun SettingsHubRoute(
    settings: AppearanceSettings,
    actions: AppearanceActions,
    onOpenTasks: () -> Unit,
    onDetailChanged: (Boolean) -> Unit,
    onTemporaryRootChanged: () -> Unit,
    active: Boolean,
) {
    val model: SystemCenterViewModel = viewModel()
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { runCatching { SettingsSection.valueOf(it) }.getOrNull() }
    LaunchedEffect(Unit) {
        model.refreshHealth()
        model.checkUpdate()
    }
    LaunchedEffect(section, active) { onDetailChanged(active && section != null) }
    var skipBackTransition by remember { mutableStateOf(false) }
    val back = rememberPredictiveBack(active && section != null, settings.predictiveBack) { gesture ->
        skipBackTransition = gesture
        sectionName = null
    }

    fun openSection(item: SettingsSection) {
        skipBackTransition = false
        sectionName = item.name
        if (item == SettingsSection.SAFETY) model.refreshHealth()
        if (item == SettingsSection.UPDATE) model.checkUpdate()
    }

    Box(Modifier.fillMaxSize()) {
    if (active && section != null && settings.predictiveBack && android.os.Build.VERSION.SDK_INT >= 34) Box(
        Modifier.fillMaxSize().predictiveBackPreviewMotion(back).backDestinationPreview()
    ) {
        SettingsHome(model, {}, {})
    }
    AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize().predictiveBackMotion(back).background(LocalZiyuPaletteTokens.current.pageBackground).clipToBounds(),
        transitionSpec = {
            val slide = tween<androidx.compose.ui.unit.IntOffset>(ZiyuMotionTokens.Emphasized, easing = FastOutSlowInEasing)
            (if (skipBackTransition && targetState == null) {
                EnterTransition.None.togetherWith(ExitTransition.None)
            } else if (targetState != null) {
                slideInHorizontally(slide) { it }
                    .togetherWith(slideOutHorizontally(slide) { -it / 4 })
                    .apply { targetContentZIndex = 1f }
            } else {
                slideInHorizontally(slide) { -it / 4 }
                    .togetherWith(slideOutHorizontally(slide) { it })
                    .apply { targetContentZIndex = 0f }
            }).using(null)
        },
        label = "settingsDetailTransition",
    ) { target ->
        if (target == null) {
            SettingsHome(
                model = model,
                onOpenSection = ::openSection,
                onOpenTasks = onOpenTasks,
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .background(LocalZiyuPaletteTokens.current.pageBackground),
            ) {
                ZiyuDetailBar(title = target.label, onBack = { sectionName = null })
                Box(Modifier.weight(1f)) {
                    when (target) {
                        SettingsSection.OVERVIEW -> OverviewPage(model)
                        SettingsSection.APPEARANCE -> AppearancePage(settings, actions)
                        SettingsSection.NOTIFICATIONS -> pageList { item { io.github.xgl34222220.ziyu.NotificationAccessCard() } }
                        SettingsSection.SAFETY -> SafetyPage(model, settings.uiStyle, onTemporaryRootChanged)
                        SettingsSection.BOOT_SAFETY -> BootSafetyPage()
                        SettingsSection.APP_COVERAGE -> UncoveredAppsPage()
                        SettingsSection.GOOGLE -> GoogleFontCompatibilityPage()
                        SettingsSection.STORAGE -> pageList {
                            item { UserStorageCard() }
                            item { DisplayConfigStorageCard() }
                        }
                        SettingsSection.BACKUP -> pageList { item { FullBackupCard(settings, actions) } }
                        SettingsSection.UPDATE -> UpdatePage(model)
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun SettingsHome(
    model: SystemCenterViewModel,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenTasks: () -> Unit,
) {
    val h = model.health
    val layout = LocalZiyuThemeTokens.current.layout
    val style = LocalUiStyle.current
    val bottom = maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LocalZiyuPaletteTokens.current.pageBackground),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            top = layout.pageTop,
            end = layout.pageHorizontal,
            bottom = bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(layout.cardGap),
    ) {
        item { ZiyuTopBar("设置") }
        item {
            SettingsOverviewCard(h) { onOpenSection(SettingsSection.OVERVIEW) }
        }
        item { SettingsSectionLabel("你的字域", "界面外观与任务记录") }
        item {
            SettingsGroup {
                SettingsNavigationRow(
                    section = SettingsSection.APPEARANCE,
                    onClick = { onOpenSection(SettingsSection.APPEARANCE) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    icon = Icons.Rounded.Description,
                    iconScale = .96f,
                    title = "任务与日志",
                    subtitle = "任务进度、失败记录与运行日志",
                    onClick = onOpenTasks,
                )
            }
        }
        item { SettingsSectionLabel("管理与维护") }
        item { SettingsGroup { SettingsNavigationRow(section = SettingsSection.NOTIFICATIONS, onClick = { onOpenSection(SettingsSection.NOTIFICATIONS) }) } }
        item {
            SettingsGroup {
                SettingsNavigationRow(
                    section = SettingsSection.SAFETY,
                    onClick = { onOpenSection(SettingsSection.SAFETY) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    section = SettingsSection.BOOT_SAFETY,
                    onClick = { onOpenSection(SettingsSection.BOOT_SAFETY) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    section = SettingsSection.APP_COVERAGE,
                    onClick = { onOpenSection(SettingsSection.APP_COVERAGE) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    section = SettingsSection.GOOGLE,
                    onClick = { onOpenSection(SettingsSection.GOOGLE) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    section = SettingsSection.STORAGE,
                    onClick = { onOpenSection(SettingsSection.STORAGE) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    section = SettingsSection.BACKUP,
                    onClick = { onOpenSection(SettingsSection.BACKUP) },
                )
                SettingsDivider()
                SettingsNavigationRow(
                    section = SettingsSection.UPDATE,
                    subtitle = model.updateInfo.version.takeIf { model.updateInfo.hasUpdate }
                        ?.let { "新版本 $it 可用" }
                        ?: SettingsSection.UPDATE.description,
                    onClick = { onOpenSection(SettingsSection.UPDATE) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(title: String, subtitle: String? = null) {
    val style = LocalUiStyle.current
    val tokens = LocalZiyuPaletteTokens.current
    val titleStyle = when (style) {
        UiStyle.MATERIAL, UiStyle.MIUIX -> MaterialTheme.typography.titleMedium
        UiStyle.COUI -> MaterialTheme.typography.titleSmall
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = titleStyle, color = tokens.textPrimary)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
    }
}

@Composable
private fun SettingsOverviewCard(health: SystemHealthSnapshot, onClick: () -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val style = LocalUiStyle.current
    val scheme = MaterialTheme.colorScheme
    val accent = when {
        health.loading -> scheme.primary
        health.level == HealthLevel.ERROR -> scheme.error
        health.level == HealthLevel.WARNING -> tokens.warning
        else -> tokens.success
    }
    val statusIcon = when {
        health.loading -> Icons.Rounded.Info
        health.level == HealthLevel.ERROR -> Icons.Rounded.Error
        health.level == HealthLevel.WARNING -> Icons.Rounded.Info
        else -> Icons.Rounded.CheckCircle
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ZiyuShapeTokens.Card,
        color = if (style == UiStyle.MATERIAL) scheme.surfaceContainerLow else tokens.cardBackground,
        border = ZiyuCardDefaults.border(),
        shadowElevation = layout.cardElevation,
    ) {
        Column(Modifier.padding(layout.compactPadding), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = ZiyuShapeTokens.Small,
                    color = accent.copy(alpha = .10f),
                    contentColor = accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(statusIcon, null, modifier = Modifier.size(19.dp), tint = accent)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("字域状态", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (health.loading) "正在读取模块状态…" else health.summary,
                        color = tokens.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ZiyuGlyph(Icons.Rounded.ChevronRight, null, ZiyuIconTokens.TrailingGlyph, tint = tokens.textSecondary.copy(alpha = .72f))
            }
            SettingsContentDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("当前字体", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(
                    if (health.loading) "正在读取…" else if (health.activeFont == "default") "系统默认" else health.activeFont.ifBlank { "尚未选择" },
                    color = tokens.textPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (health.rebootRequired) {
                    Text("字体变更等待重启生效", color = scheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ZiyuShapeTokens.Card,
        colors = CardDefaults.cardColors(
            containerColor = tokens.cardBackground,
            contentColor = tokens.textPrimary,
        ),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsDivider() {
    val layout = LocalZiyuThemeTokens.current.layout
    HorizontalDivider(
        modifier = Modifier.padding(start = layout.compactPadding + 42.dp, end = layout.compactPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .28f),
    )
}

@Composable
private fun SettingsContentDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .28f),
    )
}

@Composable
private fun SettingsNavigationRow(
    section: SettingsSection,
    subtitle: String = section.description,
    onClick: () -> Unit,
) = SettingsNavigationRow(
    icon = section.icon,
    iconScale = section.opticalScale,
    title = section.label,
    subtitle = subtitle,
    onClick = onClick,
)

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    iconScale: Float,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = LocalZiyuPaletteTokens.current
    val style = LocalUiStyle.current
    val layout = LocalZiyuThemeTokens.current.layout
    val minHeight = 68.dp
    val rowContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = ZiyuShapeTokens.Small,
                color = MaterialTheme.colorScheme.primary.copy(alpha = .075f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ZiyuGlyph(icon, null, ZiyuIconTokens.SectionGlyph, opticalScale = iconScale)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        ZiyuGlyph(Icons.Rounded.ChevronRight, null, ZiyuIconTokens.TrailingGlyph, tint = tokens.textSecondary.copy(alpha = .72f))
    }
    when (style) {
        UiStyle.MATERIAL -> Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            color = Color.Transparent,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = layout.compactPadding, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = rowContent)
        }
        UiStyle.MIUIX -> top.yukonga.miuix.kmp.basic.BasicComponent(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            insideMargin = PaddingValues(horizontal = layout.compactPadding, vertical = 9.dp),
            onClick = onClick,
            onClickLabel = title,
            role = androidx.compose.ui.semantics.Role.Button,
        ) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = rowContent) }
        UiStyle.COUI -> io.github.suqi8.coui.kmp.basic.BasicComponent(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            insideMargin = PaddingValues(horizontal = layout.compactPadding, vertical = 9.dp),
            onClick = onClick,
            onClickLabel = title,
            role = androidx.compose.ui.semantics.Role.Button,
        ) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = rowContent) }
        }
}

@Composable
private fun pageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val layout = LocalZiyuThemeTokens.current.layout
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            top = layout.pageTop,
            end = layout.pageHorizontal,
            bottom = maxOf(24.dp, layout.cardPadding),
        ),
        verticalArrangement = Arrangement.spacedBy(layout.cardGap),
        content = content,
    )
}

@Composable
private fun OverviewPage(model: SystemCenterViewModel) = pageList {
    val h = model.health
    item {
        StatusCard("字域状态", h.summary, h.level, h.loading) {
            InfoLine("App", BuildConfig.VERSION_NAME)
            InfoLine("模块", h.moduleVersion.ifBlank { if (h.modulePresent) "已安装" else "未检测到" })
            InfoLine("Root", h.rootManager)
            InfoLine("挂载", mountSummary(h))
            InfoLine("当前字体", if (h.activeFont == "default") "系统默认" else h.activeFont)
        }
    }
    item {
        SettingCard("需要关注") {
            if (h.loading) {
                Text("正在读取系统提醒…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else if (h.attentionNotices.isEmpty()) {
                Text("暂未发现需要处理的提醒", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                h.attentionNotices.forEach { NoticeLine(it) }
            }
        }
    }
}

@Composable
private fun AppearancePage(settings: AppearanceSettings, actions: AppearanceActions) {
    pageList {
        item {
            SettingCard("界面风格") {
                Text("让文字更悦目", color = LocalZiyuPaletteTokens.current.textPrimary, style = MaterialTheme.typography.headlineSmall)
                Text(when (settings.uiStyle) {
                    UiStyle.MATERIAL -> "Material 3 Expressive · 圆角与灵动色彩"
                    UiStyle.MIUIX -> "Miuix · 原生控件与液态玻璃"
                    UiStyle.COUI -> "COUI · 熟悉的 ColorOS 风格"
                }, color = LocalZiyuPaletteTokens.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                ChoiceRow(UiStyle.entries, settings.uiStyle, { it.label }, actions.setUiStyle)
            }
        }
        item { AppearanceModePreview(settings, actions) }
        item {
            SettingCard("视觉与显示") {
                ToggleLine("Monet 动态取色", "跟随系统壁纸强调色", settings.monetEnabled, actions.setMonetEnabled)
                SettingsContentDivider()
                ToggleLine("悬浮底栏", "关闭后使用贴合屏幕底部的常规导航栏", settings.floatingDock, actions.setFloatingDock)
                SettingsContentDivider()
                ToggleLine("液态玻璃", "悬浮底栏使用半透明、折射与可拖动的选中指示器", settings.glassEnabled, actions.setGlassEnabled, settings.floatingDock)
                SettingsContentDivider()
                ToggleLine("背景模糊", "关闭后保留玻璃的半透明与折射效果", settings.blurEnabled, actions.setBlurEnabled, settings.glassEnabled && settings.floatingDock)
                SettingsContentDivider()
                ToggleLine("高刷新率", "优先同分辨率高刷新模式", settings.highRefreshRate, actions.setHighRefreshRate)
                SettingsContentDivider()
                ToggleLine("预测性返回", if (android.os.Build.VERSION.SDK_INT >= 34) "返回时预览上一页，取消手势即可留在当前页" else "当前系统使用普通返回", settings.predictiveBack && android.os.Build.VERSION.SDK_INT >= 34, actions.setPredictiveBack, android.os.Build.VERSION.SDK_INT >= 34)
            }
        }
        item { AppearancePaletteCard(settings, actions) }
        item { BackgroundImageCard(settings, actions.setBackgroundPath) }
    }
}

@Composable
private fun SafetyPage(model: SystemCenterViewModel, style: UiStyle, onTemporaryRootChanged: () -> Unit) {
    val h = model.health
    val m = model.maintenance
    var confirmRestore by remember { mutableStateOf(false) }
    var temporaryRootEnabled by remember { mutableStateOf(false) }
    var temporaryRootBusy by remember { mutableStateOf(false) }
    var temporaryRootMessage by remember { mutableStateOf("") }
    var preloadState by remember { mutableStateOf("loading") }
    var preloadCallback by remember { mutableStateOf("unobserved") }
    var preloadBusy by remember { mutableStateOf(false) }
    var preloadMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun refreshPreload() {
        val result = RootShell.exec("sh /data/adb/modules/ziyu/common/font_preload_control.sh status", 8_000L)
        if (result.code != 0) {
            preloadState = "error"
            preloadMessage = result.stderr.ifBlank { "无法读取预加载组件状态" }
        } else {
            val values = result.stdout.lineSequence().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            preloadState = values["state"] ?: "error"
            preloadCallback = values["callback"] ?: "unobserved"
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !preloadBusy) {
                scope.launch { refreshPreload() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val ksuAvailable = h.rootManager == "KernelSU" || h.rootManager == "SukiSU Ultra"
    LaunchedEffect(ksuAvailable) {
        if (ksuAvailable) {
            val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh temporary_root status", 8_000L)
            runCatching { JSONObject(result.stdout.trim()).optJSONObject("data")?.optBoolean("enabled") }
                .getOrNull()?.let { temporaryRootEnabled = it }
        }
    }
    pageList {
        item {
            SettingCard("Zygisk 字体预加载") {
                val stateText = when (preloadState) {
                    "enabled" -> if (preloadCallback == "observed") "已启用，本次开机已收到回调" else "已启用，尚未观察到回调"
                    "disabled" -> "已停用"
                    "removed" -> "已标记移除"
                    "absent" -> "配套组件未安装"
                    "foreign" -> "组件身份不匹配"
                    "loading" -> "正在读取…"
                    else -> "状态不可用"
                }
                InfoLine("配套组件", stateText)
                InfoLine("本次开机回调", if (preloadCallback == "observed") "已观察到" else "尚未观察到")
                Text("仅在 Zygisk 实际加载并执行回调时预加载已验证字体；不会自动启用框架或修改排除列表。回调记录不等于字体显示验证。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (preloadState == "enabled" || preloadState == "disabled") {
                    ToggleLine("启用预加载组件", "更改后完整重启生效", preloadState == "enabled", { enabled ->
                        scope.launch {
                            preloadBusy = true
                            val action = if (enabled) "enable" else "disable"
                            val result = RootShell.exec("sh /data/adb/modules/ziyu/common/font_preload_control.sh $action", 8_000L)
                            preloadMessage = if (result.code == 0) "已更新组件状态，完整重启后生效" else result.stderr.ifBlank { "更新组件状态失败" }
                            refreshPreload()
                            preloadBusy = false
                        }
                    }, !preloadBusy)
                }
                if (preloadMessage.isNotBlank()) Text(preloadMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { scope.launch { preloadBusy = true; refreshPreload(); preloadBusy = false } }, enabled = !preloadBusy) { Text("刷新状态") }
            }
        }
        item {
            SettingCard("LSPosed 网页字体") {
                Text("在 LSPosed 中启用字域并手动勾选目标应用，然后重新打开应用。支持系统 WebView 中允许 JavaScript 的网页文字，使用当前系统无衬线字体；图标和代码区尽量保留原样。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text("自带浏览器内核、画布文字、部分 iframe 或受网页策略限制的内容可能不生效。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        if (ksuAvailable) item {
            SettingCard("KernelSU 软重启") {
                ToggleLine(
                    title = "使用临时 Root 流程",
                    description = "完整重启会掉 Root 时开启。字体准备后可在字域内确认软重启；late-load 会自动识别，无需手动开启。",
                    checked = temporaryRootEnabled,
                    enabled = !temporaryRootBusy,
                    onChange = { enabled ->
                        scope.launch {
                            temporaryRootBusy = true
                            val action = if (enabled) "enable" else "disable"
                            val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh temporary_root $action", 8_000L)
                            val response = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
                            if (result.code == 0 && response?.optString("status") == "ok") {
                                temporaryRootEnabled = response.optJSONObject("data")?.optBoolean("enabled") == true
                                temporaryRootMessage = if (temporaryRootEnabled) "已切换为 KernelSU 软重启流程" else "已恢复常规重启流程"
                                onTemporaryRootChanged()
                            } else {
                                temporaryRootMessage = response?.optString("message").orEmpty().ifBlank { result.stderr.ifBlank { "设置失败" } }
                            }
                            temporaryRootBusy = false
                        }
                    },
                )
                if (temporaryRootMessage.isNotBlank()) Text(temporaryRootMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            StatusCard("字域安全体检", h.summary, h.level, h.loading) {
                if (h.error.isNotBlank()) Text(h.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                else {
                    InfoLine("Root 管理器", h.rootManager)
                    InfoLine("Android API", h.androidSdk.takeIf { it > 0 }?.toString() ?: "未知")
                    InfoLine("引擎 / 模板", "${h.engineState.ifBlank { "?" }} · ${h.templateState.ifBlank { "?" }}")
                    InfoLine("字体加载", h.alignmentState.ifBlank { "待确认" })
                    InfoLine("自挂载", selfMountSummary(h))
                    InfoLine("Payload 字体", h.payloadFonts.toString())
                    InfoLine("切换锁", when (h.lockState) { "idle" -> "空闲"; "active" -> "切换中"; "stale" -> "失效残留"; else -> h.lockState })
                    InfoLine("最近日志", "${h.recentWarnings} 警告 · ${h.recentErrors} 错误")
                    if (h.ignoredWarnings > 0) InfoLine("已忽略警告", "${h.ignoredWarnings} 条；原始日志仍保留")
                }
                Spacer(Modifier.height(10.dp))
                if (h.recentWarnings > 0) {
                    TextButton(model::ignoreCurrentWarnings, enabled = !h.loading && !m.busy) { Text("忽略当前警告") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(model::refreshHealth, Modifier.weight(1f), enabled = !h.loading && !m.busy) { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("重新体检") }
                    Button(model::clearStaleState, Modifier.weight(1f), enabled = !m.busy) { Icon(Icons.Rounded.Build, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("安全清理") }
                }
                if (m.message.isNotBlank() || m.error.isNotBlank()) Text(m.error.ifBlank { m.message }, color = if (m.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
        item {
            SettingCard("模块冲突检测") {
                if (h.conflicts.isEmpty()) Text("未发现其它启用模块覆盖字域关注的字体目录或 fonts.xml。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                else {
                    Text("发现 ${h.conflicts.size} 个覆盖目标，只报告不自动禁用。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    h.conflicts.forEach { ConflictLine(it) }
                }
            }
        }
        item {
            SettingCard("恢复与保护") {
                Text("将当前字体恢复为系统默认，完成后需要重启手机。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton({ confirmRestore = true }, Modifier.fillMaxWidth(), enabled = !m.busy) { Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(6.dp)); Text("恢复系统默认字体") }
            }
        }
    }
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        shape = RoundedCornerShape(if (style == UiStyle.MIUIX) 34.dp else 28.dp),
        icon = { Icon(Icons.Rounded.Restore, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("恢复系统默认字体？", fontWeight = FontWeight.Black) },
        text = { Text("完成后需要完整重启手机。") },
        dismissButton = { TextButton({ confirmRestore = false }) { Text("取消") } },
        confirmButton = { TextButton({ confirmRestore = false; model.restoreDefault() }) { Text("恢复", fontWeight = FontWeight.Bold) } },
    )
}

@Composable
private fun UpdatePage(model: SystemCenterViewModel) {
    val info = model.updateInfo
    val task = model.moduleUpdate
    val context = LocalContext.current
    pageList {
        item {
            StatusCard("正式模块版本", when {
                info.loading -> "正在检查 GitHub 正式 Release…"
                info.error.isNotBlank() -> "检查失败"
                info.pendingVersion.isNotBlank() -> "模块更新等待完整重启"
                info.hasUpdate -> "发现新模块 ${info.version}"
                info.available && isNewerZiyuVersion(info.currentVersion, info.currentVersionCode, info.version, info.versionCode) -> "当前模块版本高于线上正式版"
                info.available -> "没有更新的正式模块版本"
                else -> "尚未检查"
            }, if (info.error.isNotBlank()) HealthLevel.WARNING else HealthLevel.HEALTHY, info.loading) {
                InfoLine("已安装模块", info.currentVersion.ifBlank { "尚未读取" })
                if (info.available) InfoLine("GitHub 正式版", info.version)
                if (info.pendingVersion.isNotBlank()) InfoLine("待重启模块", info.pendingVersion)
                Text("更新完整模块 ZIP，内置 App 随模块更新；完整重启后生效。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.error.isNotBlank()) Text(info.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(model::checkUpdate, Modifier.fillMaxWidth(), enabled = !info.loading && !task.busy) { Text("检查模块更新") }
            }
        }
        if (info.hasUpdate) item {
            SettingCard("更新模块") {
                Text("来源：shishui611-art/Ziyu 的正式 Release。下载后校验 SHA-256 与模块身份，再调用当前 Root 管理器安装。", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Button(model::installModuleUpdate, Modifier.fillMaxWidth(), enabled = !task.busy && !model.maintenance.busy) { Text("下载并更新模块 ${info.version}") }
            }
        }
        if (task.message.isNotBlank() || task.error.isNotBlank() || task.restartRequired) item {
            SettingCard("模块安装状态") {
                Text(task.error.ifBlank { task.message.ifBlank { "模块已更新，请完整重启" } }, color = if (task.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (info.changelogUrl.isNotBlank()) item {
            OutlinedButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.changelogUrl))) } }, Modifier.fillMaxWidth()) { Text("查看 GitHub 正式版说明") }
        }
    }
}

@Composable
private fun <T> ChoiceRow(entries: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
    val selectedIndex = entries.indexOf(selected)
    ZiyuSegmentedTabs(
        labels = entries.map(label),
        selectedIndex = selectedIndex.coerceAtLeast(0),
        onSelected = { entries.getOrNull(it)?.let(onSelected) },
    )
}

@Composable
private fun ToggleLine(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val style = LocalUiStyle.current
    val layout = LocalZiyuThemeTokens.current.layout
    val minHeight = when (style) {
        UiStyle.MATERIAL -> 66.dp
        UiStyle.MIUIX -> 62.dp
        UiStyle.COUI -> 62.dp
    }
    val insideVertical = 9.dp
    val nativeRowModifier = Modifier.fillMaxWidth().heightIn(min = minHeight).semantics(mergeDescendants = true) {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
        if (!enabled) disabled()
    }
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f),
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Keep native thumb gestures functional. The row owns the single accessible
        // switch node, while child pointer gestures invoke the same state callback.
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
    when (style) {
        UiStyle.MATERIAL -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            ).heightIn(min = minHeight).padding(horizontal = layout.compactPadding, vertical = insideVertical),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        UiStyle.MIUIX -> top.yukonga.miuix.kmp.basic.BasicComponent(
            modifier = nativeRowModifier,
            insideMargin = PaddingValues(horizontal = layout.compactPadding, vertical = insideVertical),
            onClick = { onChange(!checked) },
            role = Role.Switch,
            enabled = enabled,
        ) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content) }
        UiStyle.COUI -> io.github.suqi8.coui.kmp.basic.BasicComponent(
            modifier = nativeRowModifier,
            insideMargin = PaddingValues(horizontal = layout.compactPadding, vertical = insideVertical),
            onClick = { onChange(!checked) },
            role = Role.Switch,
            enabled = enabled,
        ) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content) }
    }
}

@Composable
internal fun SettingCard(title: String, content: @Composable () -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    if (LocalUiStyle.current == UiStyle.MATERIAL) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = tokens.cardBackground,
                contentColor = tokens.textPrimary,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(layout.cardPadding)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SettingsSectionLabel(title)
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(layout.cardPadding)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, level: HealthLevel, loading: Boolean, content: @Composable () -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val style = LocalUiStyle.current
    val accent = when (level) { HealthLevel.HEALTHY -> tokens.success; HealthLevel.WARNING -> tokens.warning; HealthLevel.ERROR -> MaterialTheme.colorScheme.error }
    Card(
        shape = ZiyuShapeTokens.Large,
        colors = CardDefaults.cardColors(
            containerColor = if (style == UiStyle.MATERIAL) MaterialTheme.colorScheme.surfaceContainerLow else tokens.cardBackground,
            contentColor = tokens.textPrimary,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(layout.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(34.dp), ZiyuShapeTokens.Medium, color = accent.copy(alpha = .10f), contentColor = accent) {
                    Box(contentAlignment = Alignment.Center) { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(when (level) { HealthLevel.HEALTHY -> Icons.Rounded.CheckCircle; HealthLevel.WARNING -> Icons.Rounded.Info; HealthLevel.ERROR -> Icons.Rounded.Error }, null, Modifier.size(21.dp)) }
                }
                Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(if (loading) "正在读取状态…" else subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(8.dp)); SettingsContentDivider(); content()
        }
    }
}

private fun mountEngineLabel(value: String): String = when (value) {
    "self-mount" -> "字域自挂载"
    "洛书自挂载" -> "字域自挂载"
    "native-module-mount" -> "Root 原生挂载"
    "meta-overlayfs", "dual-dir-metamodule" -> "Meta OverlayFS"
    "hybrid-mount" -> "Hybrid Mount"
    "magic-mount", "magic-mount-rs" -> "Magic Mount"
    "mountify" -> "Mountify"
    "unknown", "" -> ""
    else -> value.replace("洛书", "字域")
}

private fun mountBackendLabel(value: String): String = when (value) {
    "self-overlay" -> "OverlayFS"
    "self-overlay-bind" -> "OverlayFS + Bind"
    "self-existing", "external-mount" -> "已接管"
    "overlayfs" -> "OverlayFS"
    "magic-mount" -> "Magic Mount"
    "mountify" -> "Mountify"
    "none", "unknown", "" -> ""
    else -> value
}

private fun mountStateLabel(value: String): String = when (value) {
    "mounted" -> "已挂载"
    "degraded" -> "降级"
    "failed" -> "失败"
    "idle" -> "待命"
    "skipped" -> "已跳过"
    "unknown", "" -> ""
    else -> value
}

private fun mountSummary(state: SystemHealthSnapshot): String = listOf(
    mountEngineLabel(state.mountEngine),
    mountBackendLabel(state.selfMountBackend),
).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "待检测" }

private fun selfMountSummary(state: SystemHealthSnapshot): String = listOf(
    mountStateLabel(state.selfMountState),
    mountBackendLabel(state.selfMountBackend),
).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "待确认" }

@Composable
private fun InfoLine(label: String, value: String) {
    val technical = label.contains("SHA", ignoreCase = true) || label.endsWith(" ID")
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        if (technical && value.isNotBlank()) {
            val clipboard = LocalClipboardManager.current
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (scheme.background.luminance() < .5f) {
                    scheme.surfaceContainerHigh
                } else {
                    ZiyuLayoutTokens.TechnicalSurface
                },
                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = .42f)),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(value)) },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = "复制 $label",
                            modifier = Modifier.size(16.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Text(
                value.ifBlank { "—" },
                Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun NoticeLine(text: String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text(text, Modifier.weight(1f), fontSize = 13.sp) }

@Composable
private fun ConflictLine(c: ModuleConflict) = Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .48f)) {
    Column(Modifier.fillMaxWidth().padding(11.dp)) { Text(c.moduleName, fontSize = 12.sp, fontWeight = FontWeight.Black); Text("${c.moduleId} · ${c.target}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(if (c.type == "directory") "目录覆盖 · ${c.fileCount} 个字体/配置文件" else "配置文件覆盖", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DownloadButton(label: String, url: String, sha: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    enabled = url.startsWith("https://"),
    shape = RoundedCornerShape(18.dp),
) {
    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
    Spacer(Modifier.width(9.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (sha.isNotBlank()) {
            Text(
                "SHA-256 ${sha.take(16)}…",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
