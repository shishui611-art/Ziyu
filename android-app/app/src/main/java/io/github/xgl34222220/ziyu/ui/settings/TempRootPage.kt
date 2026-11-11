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
    SettingCard("临时 Root / 软重启") {
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
                    ZiyuText("软重启")
                }
            }
            if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 临时 Root 立即生效：不重启把当前负载挂到 init 命名空间。 */
@Composable
internal fun LiveApplyCard() {
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(action: String, label: String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh live $action",
                timeoutMs = 120_000L,
            )
            message = result.stdout.trim().ifBlank {
                result.stderr.trim().ifBlank { "$label 未返回结果（退出码 ${result.code}）" }
            }.take(400)
            busy = false
        }
    }
    SettingCard("临时 Root 立即生效") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                "不重启，把当前字体立即挂到系统命名空间；已打开的应用可能需要重新打开。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuButton(onClick = { run("mount", "立即生效") }, enabled = !busy) {
                    ZiyuText("立即生效")
                }
                ZiyuOutlinedButton(onClick = { run("unmount", "撤销") }, enabled = !busy) {
                    ZiyuText("撤销")
                }
                ZiyuOutlinedButton(onClick = { run("status", "状态") }, enabled = !busy) {
                    ZiyuText("状态")
                }
            }
            if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 临时 Root：模式开关、软重启与立即生效。 */
@Composable
internal fun TempRootPage() {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TemporaryRootCard()
        LiveApplyCard()
    }
}
