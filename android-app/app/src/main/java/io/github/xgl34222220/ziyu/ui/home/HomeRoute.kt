package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val STOCK_SCAN_COMMAND =
    "sh /data/adb/modules/LuoShu/common/font_manager.sh action stock_scan"

private fun mountModeLabel(mode: String): String = when (mode) {
    "magic" -> "Magic Mount"
    "overlayfs" -> "OverlayFS"
    "self_mount" -> "字域自挂载"
    else -> "元模块自动挂载"
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
    var showUndo by remember { mutableStateOf(false) }
    var showAcceptanceGuide by remember { mutableStateOf(false) }
    var showTemporaryRootGuide by remember { mutableStateOf(false) }
    var trustRefreshGeneration by remember { mutableIntStateOf(0) }
    var stockScanBusy by remember { mutableStateOf(false) }
    var stockScanMessage by remember { mutableStateOf("") }
    var stockScanError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visibleActions = if (state.temporaryRootMode) actions.copy(reboot = { showTemporaryRootGuide = true }) else actions

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

    HomeScreenCompact(
        style = style,
        state = state,
        actions = visibleActions,
        trustContent = {
            if (state.moduleInstalled) {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { actions.mountSettings(); showMountSettings = true }, enabled = !state.taskRunning, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.mountPreferenceLoaded) "挂载方式：${mountModeLabel(state.mountBackendPreference)}" else "挂载方式：读取中…")
                    }
                    if (state.taskRunning) {
                        OutlinedButton(onClick = actions.cancelTask, modifier = Modifier.fillMaxWidth()) { Text("终止当前字体任务") }
                    }
                    if (state.undoAvailable) {
                        OutlinedButton(onClick = { showUndo = true }, enabled = !state.taskRunning, modifier = Modifier.fillMaxWidth()) { Text("撤销上一次字体应用") }
                    }
                    DeviceTrustChip(
                        style = style,
                        state = trustState,
                        onClick = { showTrustDetails = true },
                    )
                    Spacer(Modifier.height(7.dp))
                    OutlinedButton(
                        onClick = {
                            if (stockScanBusy) return@OutlinedButton
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
        },
    )

    if (showMountSettings) {
        AlertDialog(
            onDismissRequest = { showMountSettings = false },
            title = { Text("字体挂载方式") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    MountModeOption(
                        title = "OverlayFS",
                        description = "仅使用 OverlayFS；内核不支持时显示失败原因。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "overlayfs",
                        onClick = { actions.setMountBackend("overlayfs") },
                    )
                    MountModeOption(
                        title = "Magic Mount",
                        description = "由字域执行 bind；字体别名冲突时使用目录镜像 bind。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "magic",
                        onClick = { actions.setMountBackend("magic") },
                    )
                    MountModeOption(
                        title = "元模块自动挂载",
                        description = "交给当前可用的元模块或管理器内置挂载；失败时显示原因。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "auto",
                        onClick = { actions.setMountBackend("auto") },
                    )
                    MountModeOption(
                        title = "字域自挂载",
                        description = "由字域执行 OverlayFS / bind；遇到别名冲突使用目录镜像。",
                        enabled = state.mountPreferenceLoaded && !state.mountPreferenceSaving,
                        selected = state.mountPreferenceLoaded && state.mountBackendPreference == "self_mount",
                        onClick = { actions.setMountBackend("self_mount") },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.mountPreferences.isNotBlank()) Text(state.mountPreferences)
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
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
