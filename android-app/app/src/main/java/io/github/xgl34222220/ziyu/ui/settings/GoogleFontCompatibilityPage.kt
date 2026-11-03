package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.design.ZiyuCircularProgressIndicator as CircularProgressIndicator
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.withCardTransparency
import io.github.xgl34222220.ziyu.ui.theme.ZiyuLoadingSkeleton
import io.github.xgl34222220.ziyu.ui.theme.ZiyuMotionTokens

@Composable
internal fun GoogleFontCompatibilityPage() {
    val model: GoogleFontCompatibilityModel = viewModel()
    val state = model.ui
    val owner = LocalLifecycleOwner.current
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refresh() }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val idle = !state.loading && !state.busy
    pageList {
        item {
            SettingCard("当前状态") {
                if (state.loading && !state.busy) {
                    ZiyuLoadingSkeleton(Modifier.fillMaxWidth(.52f).heightIn(min = 22.dp))
                    ZiyuLoadingSkeleton(Modifier.fillMaxWidth().heightIn(min = 14.dp))
                    ZiyuLoadingSkeleton(Modifier.fillMaxWidth(.76f).heightIn(min = 14.dp))
                } else {
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.busy) "正在处理，请稍候…" else state.title,
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    GoogleCompatibilityText(state.message)
                    state.user?.let { GoogleCompatibilityText("当前用户：$it") }
                    if (state.managed) GoogleCompatibilityText("已保存原设置，可随时恢复")
                    if (state.resultMessage.isNotBlank()) {
                        Text(state.resultMessage, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    if (state.error.isNotBlank()) {
                        Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                }
                OutlinedButton(onClick = model::refresh, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("重新检测")
                }
                Button(onClick = { confirmAction = "enable" }, enabled = idle && state.canEnable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("开启 Google 字体兼容") }
                OutlinedButton(onClick = { confirmAction = "restore" }, enabled = idle && state.canRestore,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复原设置") }
            }
        }
        item {
            SettingCard("使用方法") {
                GoogleCompatibilityText("Google 应用的英文或数字恢复默认时，可开启此功能。")
                GoogleStepCard("1", "先应用字体", "应用字体，并按提示重启。")
                GoogleStepCard("2", "开启兼容", "开启后完整重启。")
                GoogleStepCard("3", "查看效果", "重新打开 Google 应用，查看英文与数字。")
                GoogleCompatibilityText("已开启时无需重复操作。")
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .62f).withCardTransparency(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Root 管理器设置",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        "请关闭「默认卸载模块」，以免字体失效。",
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = .82f),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
        item {
            SettingCard("影响与恢复") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("影响与恢复", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (detailsExpanded) "收起说明" else "查看说明",
                            color = LocalZiyuPaletteTokens.current.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = detailsExpanded,
                    enter = fadeIn(tween(ZiyuMotionTokens.Fast)) +
                        expandVertically(tween(ZiyuMotionTokens.Normal, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(140)) +
                        shrinkVertically(tween(170, easing = FastOutSlowInEasing)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GoogleCompatibilityText("停用当前用户的 Google 下载字体组件。账户与应用数据会保留。")
                        GoogleCompatibilityText("可能影响依赖 Google 下载字体或表情的应用，并重启相关服务。")
                        GoogleCompatibilityText("设置会跨重启保留。停用或卸载字域前，请先恢复原设置并完整重启。")
                        GoogleCompatibilityText("应用内置字体和网页字体可能不受影响。")
                    }
                }
            }
        }
    }
    confirmAction?.let { action ->
        val enabling = action == "enable"
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text(if (enabling) "开启 Google 字体兼容？" else "恢复原设置？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (enabling)
                        "将停用当前用户的 Google 下载字体组件。可能影响下载字体与表情，并重启相关服务。"
                    else "恢复开启前的设置。")
                    Text("账户与应用数据会保留。完成后请完整重启。")
                    if (enabling) Text("停用或卸载字域前，请先恢复原设置。")
                }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("取消") } },
            confirmButton = {
                TextButton(enabled = idle && (if (enabling) state.canEnable else state.canRestore), onClick = {
                    confirmAction = null
                    if (enabling) model.enable() else model.restore()
                }) { Text(if (enabling) "开启" else "确认恢复") }
            },
        )
    }
}

@Composable
private fun GoogleStepCard(number: String, title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = .07f).withCardTransparency(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .13f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(number, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                GoogleCompatibilityText(body)
            }
        }
    }
}

@Composable
private fun GoogleCompatibilityText(text: String) {
    Text(text, color = LocalZiyuPaletteTokens.current.textSecondary, fontSize = 14.sp, lineHeight = 22.sp)
}
