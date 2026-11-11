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
            enabled = result.stdout.contains("enabled=1")
            busy = false
            message = if (enabled) {
                "已保留时钟字体。重新应用字体后生效。"
            } else {
                "时钟数字也会一起换。重新应用字体后生效。"
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
            enabled = value
            busy = false
            message = if (value) {
                val count = Regex("\"replaced\":(\\d+)").find(result.stdout)?.groupValues?.get(1)
                "已写入主题字体${count?.let { "（$it 个文件）" } ?: ""}。主题商店里的选择会失效。"
            } else {
                "已还原主题字体原件。"
            }
        }
    }
    LaunchedEffect(Unit) {
        val result = RootShell.exec(
            "sh /data/adb/modules/ziyu/common/app_bridge.sh theme_store get",
            timeoutMs = 8_000L,
        )
        enabled = result.stdout.contains("enabled=1")
        loaded = true
    }
    SettingCard("主题商店字体") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                if (enabled) "设置里的字体样式已跟随字域，主题商店里的选择会失效。"
                else "只换系统字体。开启后连「设置 → 字体样式」里选的那份也换成你的字体。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuOutlinedButton(onClick = { apply(!enabled) }, enabled = loaded && !busy) {
                    ZiyuText(if (enabled) "还原主题字体" else "同时替换主题字体")
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
