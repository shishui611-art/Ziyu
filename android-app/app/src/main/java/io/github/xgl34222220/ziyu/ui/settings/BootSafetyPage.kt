package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuShapeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val BOOT_SAFETY_BRIDGE = "/data/adb/modules/ziyu/common/app_bridge.sh"

@Composable
internal fun BootSafetyPage() {
    val scope = rememberCoroutineScope()
    val palette = LocalZiyuPaletteTokens.current
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(emptyMap<String, String>()) }
    var errorMessage by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }
    var confirmRecovery by remember { mutableStateOf(false) }

    suspend fun read(action: String): Map<String, String> {
        val response = RootShell.exec(
            "sh ${RootShell.quote(BOOT_SAFETY_BRIDGE)} boot_safety $action",
            timeoutMs = 15_000L,
        )
        val values = linkedMapOf<String, String>()
        response.stdout.lineSequence().take(64).forEach { raw ->
            val line = raw.trimEnd('\r')
            val split = line.indexOf('=')
            if (split > 0) values.putIfAbsent(line.substring(0, split), line.substring(split + 1).take(512))
        }
        check(response.code == 0 && values["status"] != "error") {
            response.stderr.take(180).ifBlank { "读取或恢复开机保护失败，请查看运行日志。" }
        }
        return values
    }

    fun perform(action: String) {
        if (busy) return
        scope.launch {
            busy = true
            errorMessage = ""
            try {
                val response = read(action)
                if (action == "recover") {
                    resultMessage = "已处理解除保护请求。请先核对 Root 管理器中的模块启用状态，再完整重启。"
                    state = read("status")
                } else if (action == "rescan") {
                    resultMessage = "已请求重新扫描原厂字体，请稍后点击重新检测；扫描完成后可重新应用字体。"
                    state = read("status")
                } else state = response
                check(state["safe_mode"] in setOf("0", "1")) { "保护状态格式无效，请确认模块与 App 版本一致。" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                errorMessage = failure.message ?: "无法读取开机保护状态"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { perform("status") }
    val safe = state["safe_mode"] == "1"
    val otaPending = state["ota_pending"] == "1"
    val nextReady = state["next_generation_ready"] == "1"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, maxOf(LocalDockContentPadding.current, 24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(shape = ZiyuShapeTokens.Card, color = palette.cardBackground, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when { busy -> "正在读取…"; state.isEmpty() -> "尚未读取保护状态"; safe -> "开机保护已启用"; otaPending && nextReady -> "新字体负载已准备，待完整重启"; otaPending -> "系统更新后字体待重新应用"; else -> "当前未进入开机保护" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (safe) "字域已暂停字体加载，字体文件和选择仍保留。建议先恢复默认字体，再解除保护。"
                        else if (otaPending && nextReady) "已按新系统原厂字体生成并校验下一启动负载。请完整重启，重启后的实际挂载验证通过后再解除系统更新保护。"
                        else if (otaPending) "旧字体负载暂缓加载，字体文件和选择仍保留。完成原厂字体重新扫描后，请在字体库重新应用字体，或恢复默认字体。"
                        else "连续两次未完成开机后，字域会在下一次启动挂载前进入保护。系统已完成开机后的临时 Root 加载与软重启不计入失败。",
                        color = palette.textSecondary,
                    )
                    state["unfinished_boots"]?.toIntOrNull()?.let { Text("未完成开机记录：$it 次", color = palette.textSecondary) }
                    if (otaPending) Text(
                        if (nextReady) "下一启动负载已准备；完整重启后自动核对。"
                        else if (state["scan_complete"] == "1") "原厂字体扫描已完成，可以在字体库重新应用字体。"
                        else "原厂字体重新扫描尚未完成；可请求重新扫描，稍后再检测。",
                        color = palette.textSecondary,
                    )
                    if (errorMessage.isNotBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    if (resultMessage.isNotBlank()) Text(resultMessage, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { perform("status") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新检测") }
                    if (otaPending && !nextReady) OutlinedButton(onClick = { perform("rescan") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新扫描原厂字体") }
                    Button(onClick = { confirmRecovery = true }, enabled = !busy && safe, modifier = Modifier.fillMaxWidth()) { Text("解除开机保护") }
                }
            }
        }
    }
    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text("解除开机保护") },
        text = { Text("若此前发生卡开机，建议先在字体库恢复默认字体。解除保护后请完整重启；系统更新后的字体仍需完成原厂重扫并重新应用。是否继续？") },
        confirmButton = { TextButton(onClick = { confirmRecovery = false; perform("recover") }) { Text("解除保护") } },
        dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("取消") } },
    )
}
