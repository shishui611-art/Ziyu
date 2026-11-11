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
import kotlinx.coroutines.launch

/** 临时 Root 模式与软重启（KernelSU late-load / 软重启验证）。 */
@Composable
internal fun TemporaryRootCard() {
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
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
            RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh temporary_root set $value",
                timeoutMs = 8_000L,
            )
            enabled = value
            busy = false
            message = if (value) {
                "已启用临时 Root 标记；用 KernelSU 的 late-load/软重启验证字体。"
            } else {
                "已关闭临时 Root 标记；之后按完整重启验证。"
            }
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
            message = result.stdout.trim().ifBlank {
                result.stderr.trim().ifBlank { "软重启请求已发送（退出码 ${result.code}）" }
            }.take(300)
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    SettingCard("临时 Root / 内核软重启") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                if (enabled) "临时 Root 模式：字体切换后用 KernelSU 软重启验证，不完整重启。"
                else "默认完整重启；只在 KernelSU late-load/软重启流程下开启临时 Root 模式。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuOutlinedButton(onClick = { setMode(!enabled) }, enabled = loaded && !busy) {
                    ZiyuText(if (enabled) "关闭临时 Root" else "开启临时 Root")
                }
                ZiyuButton(onClick = { softReboot() }, enabled = !busy) {
                    ZiyuText("内核软重启")
                }
            }
            ZiyuText(
                "内核软重启会向 KernelSU 发送 soft-reboot 请求，重新走模块加载与挂载阶段；耗时较长，完成后字体生效。",
                style = MaterialTheme.typography.bodySmall,
            )
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
