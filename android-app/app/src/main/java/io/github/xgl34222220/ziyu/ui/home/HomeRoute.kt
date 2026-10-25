package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Layers
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val STOCK_SCAN_COMMAND =
    "sh /data/adb/modules/ziyu/common/font_manager.sh action stock_scan"

private fun mountModeLabel(mode: String): String = when (mode) {
    "magic" -> "Magic Mount"
    "overlayfs" -> "OverlayFS"
    "self_mount" -> "字域自挂载"
    else -> "自动挂载"
}

private fun stockScanResultMessage(stdout: String, stderr: String, code: Int): String {
    val jsonLine = stdout.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("{") && it.endsWith("}") }
        ?: stderr.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") && it.endsWith("}") }
    val parsed = jsonLine?.let { runCatching { JSONObject(it) }.getOrNull() }
    val message = parsed?.optString("message").orEmpty()
    if (code != 0 || parsed?.optString("status") == "error") {
        return message.ifBlank { stderr.ifBlank { stdout }.trim().ifBlank { "原厂字体扫描失败" } }
    }
    val slots = parsed?.optInt("slotCount", -1) ?: -1
    val mainSlot = parsed?.optString("mainSlot").orEmpty()
    return buildString {
        append("原厂字体扫描完成")
        if (slots >= 0) append(" · ").append(slots).append(" 个槽位")
        if (mainSlot.isNotBlank()) append(" · 主槽 ").append(mainSlot)
    }
}

@Composable
fun HomeRoute(
    style: UiStyle,
    state: HomeUiState,
    actions: HomeActions,
) {
    var trustState by remember { mutableStateOf(DeviceTrustState()) }
    var showTrustDetails by remember { mutableStateOf(false) }
    var showMountSettings by remember { mutableStateOf(false) }
    var showAdvancedMount by remember { mutableStateOf(false) }
    var showUndo by remember { mutableStateOf(false) }
    var showAcceptanceGuide by remember { mutableStateOf(false) }
    var showTemporaryRootGuide by remember { mutableStateOf(false) }
    var trustRefreshGeneration by remember { mutableIntStateOf(0) }
    var stockScanBusy by remember { mutableStateOf(false) }
    var stockScanMessage by remember { mutableStateOf("") }
    var stockScanError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visibleActions = actions.copy(
        reboot = if (state.temporaryRootMode) ({ showTemporaryRootGuide = true }) else actions.reboot,
        mountSettings = { actions.mountSettings(); showMountSettings = true },
    )

    LaunchedEffect(state.typographyVisible, state.currentFontId, state.currentFontRevision, state.loading) {
        if (state.typographyVisible && state.moduleInstalled && state.rootGranted && !state.loading) actions.loadTypography()
    }
    LaunchedEffect(showMountSettings, state.mountPreferenceLoaded) {
        if (showMountSettings && state.mountPreferenceLoaded) showAdvancedMount = state.mountBackendPreference != "auto"
    }

    LaunchedEffect(state.mountBackendRebootPrompt) {
        if (state.mountBackendRebootPrompt != null) showMountSettings = false
    }

    LaunchedEffect(
        state.moduleInstalled,
        state.currentFont,
        state.rebootRequired,
        state.taskRunning,
        trustRefreshGeneration,
    ) {
        if (!state.moduleInstalled) {
            trustState = DeviceTrustState(loading = false, error = "请先安装字域模块")
            return@LaunchedEffect
        }

        var latest = loadDeviceTrustState()
        trustState = latest
        var attempt = 0
        while (latest.level == DeviceTrustLevel.PENDING && attempt < 9 && !state.taskRunning) {
            delay(5_000L)
            latest = loadDeviceTrustState()
            trustState = latest
            attempt += 1
        }
    }

    val trustContent: @Composable () -> Unit = {
        if (state.moduleInstalled) {
            Column(Modifier.fillMaxWidth()) {
                if (state.taskRunning) {
                    TextButton(onClick = actions.cancelTask, modifier = Modifier.fillMaxWidth()) { Text("终止当前字体任务") }
                }
                if (state.undoAvailable) {
                    TextButton(onClick = { showUndo = true }, enabled = !state.taskRunning, modifier = Modifier.fillMaxWidth()) { Text("撤销上一次字体应用") }
                }
                DeviceTrustChip(
                    style = style,
                    state = trustState,
                    onClick = { showTrustDetails = true },
                )
                Spacer(Modifier.height(7.dp))
                TextButton(
                    onClick = {
                        if (stockScanBusy) return@TextButton
                        stockScanBusy = true
                        stockScanMessage = "正在扫描设备原厂字体…"
                        stockScanError = false
                        scope.launch {
                            val result = RootShell.exec(STOCK_SCAN_COMMAND, timeoutMs = 180_000L)
                            stockScanMessage = stockScanResultMessage(result.stdout, result.stderr, result.code)
                            stockScanError = result.code != 0 || stockScanMessage.contains("失败") || stockScanMessage.contains("无法")
                            stockScanBusy = false
                            if (!stockScanError) {
                                actions.refresh()
                                trustRefreshGeneration += 1
                            }
                        }
                    },
                    enabled = !stockScanBusy && !state.taskRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (stockScanBusy) "正在扫描原厂字体…" else "重新扫描原厂字体",
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (stockScanMessage.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stockScanMessage,
                        color = if (stockScanError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
    when (style) {
        UiStyle.MATERIAL -> HomeScreenMaterial(state, visibleActions, trustContent)
        UiStyle.MIUIX -> HomeScreenMiuix(state, visibleActions, trustContent)
        UiStyle.COUI -> HomeScreenCoui(state, visibleActions, trustContent)
    }

    if (showMountSettings) {
        AlertDialog(
            onDismissRequest = { showMountSettings = false },
            title = { Text("字体挂载方式") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MountModeOption(
                        title = "自动挂载 · 推荐",
                        description = "自动选择可用挂载方式，失败时尝试字域自挂载。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "auto",
                        onClick = { actions.setMountBackend("auto") },
                    )
                    ZiyuOutlinedButton(onClick = { showAdvancedMount = !showAdvancedMount }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAdvancedMount) "收起高级选项" else "高级选项")
                    }
                    if (showAdvancedMount) {
                    MountModeOption(
                        title = "字域自挂载",
                        description = "优先 OverlayFS，不可用时使用 bind。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "self_mount",
                        onClick = { actions.setMountBackend("self_mount") },
                    )
                    MountModeOption(
                        title = "OverlayFS",
                        description = "仅使用 OverlayFS；内核不支持时显示失败原因。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "overlayfs",
                        onClick = { actions.setMountBackend("overlayfs") },
                    )
                    MountModeOption(
                        title = "Magic Mount",
                        description = "使用 bind 挂载，适合 OverlayFS 不兼容时选择。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "magic",
                        onClick = { actions.setMountBackend("magic") },
                    )
                    }
                    if (state.mountPreferenceSaving) Text("正在保存挂载选择…")
                    if (!state.mountPreferenceLoaded && state.mountPreferences.isNotBlank()) Text(state.mountPreferences)
                    Text(if (state.temporaryRootMode) "切换模式后需通过 KernelSU 软重启验证。" else "切换模式后需完整重启验证。")
                }
            },
            confirmButton = { TextButton(onClick = { showMountSettings = false }) { Text("完成") } },
        )
    }
    state.mountBackendRebootPrompt?.let { mode ->
        AlertDialog(
            onDismissRequest = actions.dismissMountBackendRebootPrompt,
            title = { Text("是否重启验证挂载？") },
            text = {
                Text("已保存为${mountModeLabel(mode)}。" +
                    if (state.temporaryRootMode) "字域将请求 KernelSU 软重启应用选择，完成后回到字域查看实际挂载方式和验证结果。"
                    else "完整重启后执行所选挂载方式，完成后回到字域查看实际挂载方式和验证结果。")
            },
            confirmButton = {
                TextButton(onClick = { actions.dismissMountBackendRebootPrompt(); actions.reboot() }) {
                    Text(if (state.temporaryRootMode) "立即软重启" else "立即重启")
                }
            },
            dismissButton = { TextButton(onClick = actions.dismissMountBackendRebootPrompt) { Text("稍后重启") } },
        )
    }
    if (showUndo) {
        AlertDialog(onDismissRequest = { showUndo = false }, title = { Text("撤销字体应用") },
            text = { Text("恢复上一次实际应用的字体。已重启生效的字体需要再次重启才能完成回退。") },
            confirmButton = { TextButton(onClick = { showUndo = false; actions.undoApply() }) { Text("确认回退") } },
            dismissButton = { TextButton(onClick = { showUndo = false }) { Text("取消") } })
    }

    if (showTrustDetails) {
        DeviceTrustDialog(
            style = style,
            state = trustState,
            onDismiss = { showTrustDetails = false },
            onOpenAcceptance = { showAcceptanceGuide = true },
        )
    }
    if (showAcceptanceGuide) {
        DeviceAcceptanceGuideDialog(
            style = style,
            state = state,
            trust = trustState,
            onRefresh = {
                actions.refresh()
                trustRefreshGeneration += 1
            },
            onReboot = visibleActions.reboot,
            onDismiss = { showAcceptanceGuide = false },
        )
    }
    if (showTemporaryRootGuide) {
        AlertDialog(
            onDismissRequest = { showTemporaryRootGuide = false },
            title = { Text("KernelSU 软重启") },
            text = { Text("是否请求 KernelSU 软重启？完成后回到字域，查看本次挂载和字体加载验证结果。") },
            confirmButton = { TextButton(onClick = { showTemporaryRootGuide = false; actions.reboot() }) { Text("立即软重启") } },
            dismissButton = { TextButton(onClick = { showTemporaryRootGuide = false }) { Text("稍后") } },
        )
    }
}

@Composable
private fun MountModeOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalZiyuPaletteTokens.current
    ZiyuButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) scheme.primaryContainer else palette.cardBackground,
            contentColor = if (selected) scheme.onPrimaryContainer else palette.textPrimary,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (selected) ZiyuIcon(Icons.Rounded.CheckCircle, contentDescription = "已选择")
    }
}

@Composable
internal fun HomeMountButton(state: HomeUiState, actions: HomeActions) {
    ZiyuOutlinedButton(
        onClick = actions.mountSettings, enabled = !state.taskRunning && state.moduleInstalled && state.rootGranted,
        modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        ZiyuIcon(Icons.Rounded.Layers, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text("挂载方式", modifier = Modifier.weight(1f))
        Text(mountModeLabel(state.mountBackendPreference), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        ZiyuIcon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
}
