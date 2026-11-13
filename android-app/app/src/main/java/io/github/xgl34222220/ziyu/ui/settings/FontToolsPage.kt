package io.github.xgl34222220.ziyu.ui.settings

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
import org.json.JSONObject

private fun themeStoreReply(output: String): JSONObject? = runCatching {
    JSONObject(output.lineSequence().map(String::trim).last { it.startsWith("{") })
}.getOrNull()

/** 保留锁屏时钟字体：避免换字体后锁屏/桌面时钟排版错位。 */
@Composable
internal fun KeepLockscreenCard() {
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun apply(value: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh keep_lockscreen set ${if (value) "on" else "off"}",
                timeoutMs = 8_000L,
            )
            val actual = result.stdout.lineSequence().map(String::trim).firstOrNull {
                it == "enabled=1" || it == "enabled=0"
            }
            busy = false
            message = if (result.code != 0 || actual == null) {
                "设置未保存，请重试。"
            } else {
                enabled = actual == "enabled=1"
                "已保存，重新应用字体后生效。"
            }
        }
    }
    LaunchedEffect(Unit) {
        val result = RootShell.exec(
            "sh /data/adb/modules/ziyu/common/app_bridge.sh keep_lockscreen get",
            timeoutMs = 8_000L,
        )
        enabled = !result.stdout.contains("enabled=0")
        loaded = true
    }
    SettingCard("保留锁屏时钟字体") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                if (enabled) "锁屏和桌面的时钟数字保持系统原样，避免排版错位。"
                else "时钟数字也会换成你的字体，可能出现错位。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuButton(onClick = { apply(!enabled) }, enabled = loaded && !busy) {
                    ZiyuText(if (enabled) "改为一起替换" else "保留时钟字体")
                }
            }
            if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 主题商店字体：让「设置 → 字体样式」里选的那份也跟随字域。 */
@Composable
internal fun ThemeStoreCard() {
    var enabled by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun apply(value: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh theme_store set ${if (value) "on" else "off"}",
                timeoutMs = 120_000L,
            )
            val reply = themeStoreReply(result.stdout)
            val data = reply?.optJSONObject("data")
            if (data?.has("pending") == true) pendingRestore = data.optInt("pending", 0) > 0
            val success = result.code == 0 && reply?.optString("status") == "ok" &&
                (data?.optInt("failed", 0) ?: 0) == 0
            if (data?.has("enabled") == true && !data.isNull("enabled")) {
                enabled = data.optBoolean("enabled", enabled)
            } else if (success) {
                enabled = value // Compatibility with the previous module's reply.
            }
            busy = false
            message = reply?.optString("message").orEmpty().ifBlank {
                data?.optString("message").orEmpty()
            }.ifBlank {
                if (success) {
                    if (value) "主题字体已更新。" else "主题字体已还原。"
                } else "处理未完成，请重试或导出日志。"
            }
        }
    }
    LaunchedEffect(Unit) {
        val result = RootShell.exec(
            "sh /data/adb/modules/ziyu/common/app_bridge.sh theme_store get",
            timeoutMs = 8_000L,
        )
        val reply = themeStoreReply(result.stdout)
        if (result.code == 0 && reply?.optString("status") == "ok") {
            enabled = reply.optJSONObject("data")?.optBoolean("enabled", false) ?: false
            pendingRestore = (reply.optJSONObject("data")?.optInt("backed_up", 0) ?: 0) > 0
        } else if (result.code == 0 && result.stdout.lineSequence().any { it.trim() == "enabled=1" || it.trim() == "enabled=0" }) {
            enabled = result.stdout.lineSequence().any { it.trim() == "enabled=1" }
        } else {
            message = reply?.optString("message").orEmpty().ifBlank { "状态未读取，请重试或导出日志。" }
        }
        loaded = true
    }
    SettingCard("主题商店字体") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                "替换主题中选用的字体，替换前会备份。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuOutlinedButton(onClick = { apply(!(enabled || pendingRestore)) }, enabled = loaded && !busy) {
                    ZiyuText(if (enabled || pendingRestore) "还原主题字体" else "同时替换主题字体")
                }
            }
            if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 字体工具：导入上限、锁屏时钟、主题商店字体等进阶设置集中在这里。 */
@Composable
internal fun FontToolsPage() {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FontImportLimitCard()
        KeepLockscreenCard()
        ThemeStoreCard()
    }
}
