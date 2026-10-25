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
        ZiyuText("支持 PNG、JPG/JPEG、WebP、GIF、BMP，以及本机支持解码的 HEIF/AVIF。动图使用静态画面；导入后自动适配当前主题。")
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
            message = if (pending.isNotBlank()) "已保存，完整重启后迁移；迁移失败时原目录继续生效。" else ""
        } catch (failure: Throwable) { error = true; message = failure.message.orEmpty() }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { command("status") }
    SettingCard("用户文件存放位置") {
        ZiyuText("当前目录：${current.ifBlank { "读取中…" }}")
        if (pending.isNotBlank()) ZiyuText("下次启动：$pending", color = MaterialTheme.colorScheme.primary)
        ZiyuText("字体、生成的组合字体、模块备份及报告使用此目录。请选择共享存储中的空子目录；原文件会保留，校验完成后才切换。手动导出的完整备份仍由文件选择器指定位置。")
        ZiyuOutlinedTextField(value = path, onValueChange = { path = it }, label = { ZiyuText("例如 /sdcard/ziyu") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        ZiyuButton(onClick = { scope.launch { command("request ${RootShell.quote(path.trim())}") } }, enabled = !busy && path.isNotBlank()) { ZiyuText(if (busy) "处理中…" else "保存，重启生效") }
        if (message.isNotBlank()) ZiyuText(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
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
            path = current; message = data.optString("message"); error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            message = failure.message.orEmpty(); error = true
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) { command("status") }
    SettingCard("显示参数配置保存位置") {
        ZiyuText("当前目录：${current.ifBlank { "读取中…" }}")
        ZiyuText("保存字体大小、粗细和 DPI 的本地配置。可单独选择专用目录，保存后即切换；内部保留小型镜像。挂载与回滚记录继续由模块管理。")
        ZiyuOutlinedTextField(
            value = path, onValueChange = { path = it }, label = { ZiyuText("例如 /sdcard/ziyu-config") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
            ZiyuButton(
                onClick = { scope.launch { command("set ${RootShell.quote(path.trim())}") } },
                enabled = !busy && path.isNotBlank(), modifier = Modifier.weight(1f),
            ) { ZiyuText(if (busy) "保存中…" else "保存并切换") }
            ZiyuOutlinedButton(
                onClick = { scope.launch { command("set ${RootShell.quote(defaultPath)}") } },
                enabled = !busy && defaultPath.isNotBlank() && current != defaultPath, modifier = Modifier.weight(1f),
            ) { ZiyuText("恢复默认位置") }
        }
        if (message.isNotBlank()) ZiyuText(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}
