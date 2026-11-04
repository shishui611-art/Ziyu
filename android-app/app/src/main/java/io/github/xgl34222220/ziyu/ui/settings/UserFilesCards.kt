package io.github.xgl34222220.ziyu.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceSettings
import io.github.xgl34222220.ziyu.ui.appearance.importBackground
import io.github.xgl34222220.ziyu.ui.design.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun BackgroundImageCard(settings: AppearanceSettings, onPath: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = ""
            runCatching { importBackground(context, uri) }
                .onSuccess(onPath).onFailure { error = it.message ?: "无法解码该图片" }
            busy = false
        }
    }
    SettingCard("背景图片") {
        ZiyuText("选择一张图片作为背景。动图显示静态画面。")
        Row(horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
            ZiyuButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy) { ZiyuText(if (busy) "正在导入…" else "导入图片") }
            ZiyuOutlinedButton(onClick = { onPath("") }, enabled = !busy && settings.backgroundPath.isNotBlank()) { ZiyuText("恢复默认") }
        }
        if (error.isNotBlank()) ZiyuText(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun UserStorageCard() {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    suspend fun command(action: String) {
        busy = true
        try {
            val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh storage $action", 15_000L)
            val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            require(result.code == 0 && json?.optString("status") == "ok") { json?.optString("message")?.takeIf { it.isNotBlank() } ?: result.stderr.ifBlank { "无法读取用户目录" } }
            val data = json!!.getJSONObject("data")
            current = data.optString("activePath"); pending = data.optString("pendingPath")
            path = pending.ifBlank { current }; error = false
            message = if (pending.isNotBlank()) "已保存，完整重启后迁移。" else ""
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Throwable) { error = true; message = failure.message ?: "无法读取用户目录" }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { command("status") }
    SettingCard("字体与文件") {
        ZiyuOutlinedTextField(
            value = path, onValueChange = { path = it; message = "" },
            placeholder = { ZiyuText(if (busy) "读取中…" else "保存路径") },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        if (message.isBlank() || error) ZiyuText("更改后，完整重启迁移文件。", style = MaterialTheme.typography.bodySmall)
        ZiyuButton(
            onClick = { scope.launch { command("request ${RootShell.quote(path.trim())}") } },
            enabled = !busy && path.isNotBlank() && path.trim() != pending.ifBlank { current }, modifier = Modifier.fillMaxWidth(),
        ) { ZiyuText(if (busy) "处理中…" else "保存") }
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (error) ZiyuTextButton(onClick = { scope.launch { command("status") } }, enabled = !busy) { ZiyuText("重新读取") }
    }
}

@Composable
internal fun DisplayConfigStorageCard() {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var defaultPath by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    suspend fun command(action: String) {
        busy = true
        try {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh display_config_storage $action", 15_000L,
            )
            val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            require(result.code == 0 && json?.optString("status") == "ok") {
                json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: result.stderr.ifBlank { "无法读取配置保存位置" }
            }
            val data = json!!.getJSONObject("data")
            current = data.optString("path"); defaultPath = data.optString("defaultPath")
            path = current; message = if (action.startsWith("set ")) "已保存" else ""; error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            message = failure.message ?: "无法读取配置保存位置"; error = true
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) { command("status") }
    SettingCard("DPI 配置") {
        ZiyuOutlinedTextField(
            value = path, onValueChange = { path = it; message = "" },
            placeholder = { ZiyuText(if (busy) "读取中…" else "保存路径") },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        if (message.isBlank() || error) ZiyuText("保存后使用新目录。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
            ZiyuButton(
                onClick = { scope.launch { command("set ${RootShell.quote(path.trim())}") } },
                enabled = !busy && path.isNotBlank() && path.trim() != current, modifier = Modifier.weight(1f),
            ) { ZiyuText(if (busy) "保存中…" else "保存") }
            ZiyuOutlinedButton(
                onClick = { scope.launch { command("set ${RootShell.quote(defaultPath)}") } },
                enabled = !busy && defaultPath.isNotBlank() && current != defaultPath, modifier = Modifier.weight(1f),
            ) { ZiyuText("恢复默认") }
        }
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (error) ZiyuTextButton(onClick = { scope.launch { command("status") } }, enabled = !busy) { ZiyuText("重新读取") }
    }
}
