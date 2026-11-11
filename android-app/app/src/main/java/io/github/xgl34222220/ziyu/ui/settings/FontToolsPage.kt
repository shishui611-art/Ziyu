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

/** 是否把其它语言字体槽位也纳入覆盖（默认关闭；开启后阿拉伯/泰文等可能回退）。 */
@Composable
internal fun SlotLanguageCard() {
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun apply(value: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh slot_lang set ${if (value) "on" else "off"}",
                timeoutMs = 8_000L,
            )
            enabled = value
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        val result = RootShell.exec(
            "sh /data/adb/modules/ziyu/common/app_bridge.sh slot_lang get",
            timeoutMs = 8_000L,
        )
        enabled = result.stdout.contains("enabled=1")
        loaded = true
    }
    SettingCard("全量槽位覆盖（100+）") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText(
                if (enabled) "已开启全量槽位：约 200+ 个槽位（含日语、韩语及其它语种）。字体缺字时这些语种可能显示为回退字体。"
                else "默认只覆盖中文/英文/数字安全槽位（约 27 个）。开启后覆盖其它语种，槽位数量可到 100+，但可能影响显示与开机稳定。",
                style = MaterialTheme.typography.bodySmall,
            )
            ZiyuButton(
                onClick = { apply(!enabled) },
                enabled = loaded && !busy,
            ) { ZiyuText(if (enabled) "关闭全量槽位覆盖" else "开启全量槽位覆盖（100+）") }
        }
    }
}

/** 字体工具：导入上限、槽位范围、临时 Root 等新增设置集中在这里。 */
@Composable
internal fun FontToolsPage() {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FontImportLimitCard()
    }
}
