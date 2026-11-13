package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.legacy.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 临时 Root 模式与软重启（KernelSU late-load / 软重启验证）。 */
@Composable
internal fun TemporaryRootCard() {
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        scope.launch {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh temporary_root get",
                timeoutMs = 8_000L,
            )
            enabled = result.stdout.contains("enabled=true")
            loaded = true
        }
    }
    fun setMode(value: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh temporary_root set $value",
                timeoutMs = 8_000L,
            )
            val accepted = result.code == 0 && result.stdout.contains("enabled=$value")
            if (accepted) enabled = value
            busy = false
            message = if (!accepted) "设置未保存，请重试。"
                else if (value) "已开启临时 Root 模式。" else "已关闭临时 Root 模式。"
        }
    }
    fun softReboot() {
        if (busy) return
        busy = true
        scope.launch {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh soft_reboot request",
                timeoutMs = 60_000L,
            )
            val reply = runCatching {
                JSONObject(result.stdout.lineSequence().last { it.trim().startsWith("{") })
            }.getOrNull()
            val accepted = result.code == 0 && reply?.optString("status") == "ok"
            message = reply?.optString("message")?.takeIf { it.isNotBlank() }
                ?: reply?.optJSONObject("data")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: if (accepted) "已请求内核软重启；启动后会核验字体和 PPID。" else "内核软重启请求失败，请查看日志。"
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    if (confirmation > 0) {
        ZiyuAlertDialog(
            onDismissRequest = { confirmation = 0 },
            title = { ZiyuText(if (confirmation == 1) "内核软重启" else "现在执行内核软重启？") },
            text = { ZiyuText(if (confirmation == 1) "重新加载 KernelSU 模块和字体，并核对新 zygote PPID。" else "请先保存其他应用中未完成的内容。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    if (confirmation == 1) confirmation = 2
                    else { confirmation = 0; softReboot() }
                }) { ZiyuText(if (confirmation == 1) "继续" else "内核软重启") }
            },
            dismissButton = { ZiyuOutlinedButton(onClick = { confirmation = 0 }) { ZiyuText("取消") } },
        )
    }
    SettingCard("临时 Root") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                "临时获取 Root 时开启；切换字体后可选普通重启或内核软重启。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuOutlinedButton(onClick = { setMode(!enabled) }, enabled = loaded && !busy) {
                    ZiyuText(if (enabled) "关闭临时 Root" else "开启临时 Root")
                }
                ZiyuButton(onClick = { confirmation = 1 }, enabled = !busy) {
                    ZiyuText("内核软重启")
                }
            }
            if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 临时 Root：模式开关与内核软重启。 */
@Composable
internal fun TempRootPage() {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TemporaryRootCard()
    }
}
