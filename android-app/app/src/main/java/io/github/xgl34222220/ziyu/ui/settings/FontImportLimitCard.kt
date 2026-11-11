package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.xgl34222220.ziyu.ui.design.ZiyuSlider
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import kotlinx.coroutines.launch

/** 字体导入/应用的单文件大小上限，模块侧持久化到 config/font-import-limit.conf。 */
@Composable
internal fun FontImportLimitCard() {
    var limitMb by remember { mutableStateOf(256f) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val result = RootShell.exec(
            "sh /data/adb/modules/ziyu/common/app_bridge.sh import_limit get",
            timeoutMs = 8_000L,
        )
        result.stdout.lineSequence()
            .firstOrNull { it.startsWith("max_mb=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()
            ?.let { limitMb = it.toFloat() }
        loaded = true
    }

    SettingCard("字体大小上限") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText("${limitMb.toInt()} MB", style = MaterialTheme.typography.titleMedium)
            ZiyuSlider(
                value = limitMb,
                onValueChange = { limitMb = it },
                valueRange = 32f..512f,
                steps = 14,
                enabled = loaded && !saving,
                onValueChangeFinished = {
                    saving = true
                    scope.launch {
                        RootShell.exec(
                            "sh /data/adb/modules/ziyu/common/app_bridge.sh import_limit set ${limitMb.toInt()}",
                            timeoutMs = 8_000L,
                        )
                        saving = false
                    }
                },
            )
            ZiyuText(
                "单个字体或字体包的上限；超过上限的字体需要先拆分合集或减少文件。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
