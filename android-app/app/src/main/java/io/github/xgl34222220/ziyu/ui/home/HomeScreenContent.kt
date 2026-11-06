package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.BuildConfig
import io.github.xgl34222220.ziyu.ui.appearance.HomeHeroFace
import io.github.xgl34222220.ziyu.ui.appearance.homeHeroColors
import io.github.xgl34222220.ziyu.ui.appearance.ThemeMode
import io.github.xgl34222220.ziyu.ui.appearance.ThemeTextKey
import io.github.xgl34222220.ziyu.ui.appearance.resolveThemeText
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.ziyu.ui.appearance.themeText
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.*

/** Shared business sections; the three page shells decide their order and presentation. */
internal val HomeUiState.hasTaskNotice: Boolean
    get() = taskRunning || rebootRequired || error.isNotBlank() || romCheckMessage.isNotBlank()

@Composable
internal fun HomePageHeader(loading: Boolean, actions: HomeActions) {
    val palette=LocalZiyuPaletteTokens.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom=4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
            ZiyuHeaderAction(Icons.Rounded.Description, "任务中心", actions.openLogs, Color.Transparent, contentColor=palette.textPrimary)
            ZiyuHeaderAction(Icons.Rounded.Refresh, "刷新", actions.refresh, Color.Transparent, loading=loading, contentColor=palette.textPrimary)
        }
        ZiyuText(themeText("字域"),Modifier.padding(top=16.dp,bottom=12.dp),color=palette.textPrimary,fontSize=40.sp,lineHeight=48.sp,
            fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
internal fun HomeCurrentFont(state: HomeUiState, actions: HomeActions) {
    val scheme=MaterialTheme.colorScheme
    val settings=LocalAppearanceSettings.current
    val dark=when(settings.themeMode) { ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true }
    val hero=homeHeroColors(settings,scheme,dark)
    val status=when {
        state.loading -> "检测中…"
        !state.moduleInstalled -> "未连接模块"
        !state.rootGranted -> "等待 Root 授权"
        state.taskRunning -> "正在应用"
        state.rebootRequired -> "重启后生效"
        state.error.isNotBlank() -> "状态待更新"
        !state.mountHealthy -> "待验证"
        else -> "模块已连接"
    }
    ZiyuCard(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp),
        colors=CardDefaults.cardColors(containerColor=hero.background,contentColor=hero.text),
        onClick=if(!state.moduleInstalled || !state.rootGranted) actions.refresh else actions.openLogs) {
        HomeHeroFace(settings,hero,status,state.version)
    }
}

@Composable
internal fun HomeStatsRow(state: HomeUiState, actions: HomeActions) {
    val texts=LocalAppearanceSettings.current.customTexts
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        HomeStatTile(resolveThemeText(texts,ThemeTextKey.STAT_FONT),state.currentFont,false,actions.viewCurrentFont,Modifier.weight(1f).fillMaxHeight())
        val count=state.libraryCount?.toString() ?: if(state.libraryCountLoading)"读取中…" else "未读取"
        val label=resolveThemeText(texts,ThemeTextKey.STAT_LIBRARY)+if(state.libraryCount!=null && state.libraryCountStale)" · 上次记录" else ""
        HomeStatTile(label,count,true,actions.openFontLibrary,Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun HomeStatTile(title: String,value: String,number: Boolean,onClick: ()->Unit,modifier: Modifier) {
    val palette=LocalZiyuPaletteTokens.current
    ZiyuCard(modifier=modifier,shape=RoundedCornerShape(24.dp),onClick=onClick,
        colors=CardDefaults.cardColors(containerColor=palette.cardBackground,contentColor=palette.textPrimary)) {
        Column(Modifier.fillMaxWidth().heightIn(min=100.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp,Alignment.Bottom)) {
            ZiyuText(value,Modifier.weight(1f,fill=false),fontSize=if(number && value.all {it.isDigit()})28.sp else 18.sp,
                lineHeight=if(number && value.all {it.isDigit()})34.sp else 24.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            ZiyuText(title,color=palette.textSecondary,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
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
        color = (if (failed) scheme.errorContainer else palette.cardBackground).withCardTransparency(),
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
            if(state.taskRunning || state.rebootRequired || failed) {
                val next=nextStepFor(state,actions)
                ZiyuOutlinedButton(onClick=next.onClick,enabled=next.enabled,modifier=Modifier.fillMaxWidth()) { ZiyuText(next.actionLabel) }
            }
        }
    }
}

@Composable
internal fun HomeDevicePanel(state: HomeUiState, trustContent: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val palette=LocalZiyuPaletteTokens.current
    ZiyuCard(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),
        colors=CardDefaults.cardColors(containerColor=palette.cardBackground,contentColor=palette.textPrimary)) {
        Column(Modifier.padding(horizontal=16.dp,vertical=4.dp)) {
            HomeInfoRow("App 版本",BuildConfig.VERSION_NAME)
            HomeInfoRow("模块版本",state.version)
            HomeInfoRow("Root 管理器",if(state.rootGranted)state.rootManager else "未授权")
            HomeInfoRow("挂载方式",state.mountEngine)
            HomeInfoRow("设备",android.os.Build.MODEL)
            HomeInfoRow("Android",android.os.Build.VERSION.RELEASE)
            ZiyuListItem(onClick={expanded=!expanded},onClickLabel="生效检查",
                headlineContent={ZiyuText("生效检查",style=MaterialTheme.typography.bodyMedium)},
                trailingContent={ZiyuGlyph(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,ZiyuIconTokens.TrailingGlyph)},
                colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            AnimatedVisibility(expanded) {Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {trustContent()}}
        }
    }
}

@Composable
private fun HomeInfoRow(label: String,value: String) {
    val palette=LocalZiyuPaletteTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical=15.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        ZiyuText(label,Modifier.weight(.42f),color=palette.textSecondary,style=MaterialTheme.typography.bodyMedium)
        ZiyuText(value,Modifier.weight(.58f),textAlign=TextAlign.End,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
    ZiyuHorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.32f))
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
