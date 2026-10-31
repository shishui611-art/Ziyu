package io.github.xgl34222220.ziyu.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSwitch
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private suspend fun clippingPreference(fontId: String, requested: Boolean? = null): JSONObject {
    val action = if (requested == null) "status" else "set"
    val value = requested?.let { if (it) "on" else "off" }.orEmpty()
    val result = RootShell.exec(
        "sh /data/adb/modules/ziyu/common/app_bridge.sh font_clipping $action ${RootShell.quote(fontId)} $value",
        timeoutMs = 15_000L,
    )
    val response = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
    check(result.code == 0 && response?.optString("status") == "ok") {
        response?.optString("message").orEmpty().ifBlank { result.stderr.ifBlank { "防裁切设置读取或保存失败，请确认模块与 App 均已更新" } }
    }
    return checkNotNull(response)
}

@Composable
internal fun FontClippingControl(font: FontItem, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var enabled by remember(font.id) { mutableStateOf(false) }
    var loaded by remember(font.id) { mutableStateOf(false) }
    var saving by remember(font.id) { mutableStateOf(false) }
    var message by remember(font.id) { mutableStateOf("") }
    var failure by remember(font.id) { mutableStateOf("") }
    var reload by remember(font.id) { mutableStateOf(false) }
    LaunchedEffect(font.id, reload) {
        try {
            failure = ""
            enabled = clippingPreference(font.id).getBoolean("enabled")
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "防裁切设置读取失败"
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZiyuText("自动防裁切（ColorOS）", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            ZiyuSwitch(
                checked = enabled, enabled = loaded && !busy && !saving && font.valid,
                onCheckedChange = { requested ->
                    saving = true
                    failure = ""
                    scope.launch {
                        try {
                            val result = clippingPreference(font.id, requested)
                            enabled = result.getBoolean("enabled")
                            message = result.optString("message")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error.message ?: "防裁切设置保存失败"
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        }
        ZiyuText(
            "按这款字体保存，开启后重新应用生效。自动测量实际边界，仅在超出原厂行框时统一缩放或调整基线，视觉字号可能略小；关闭后重新应用恢复原比例。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ZiyuText(
            "用于普通字体或工作室已保存的静态组合。不支持的字体会提示并保留当前字体；字体库预览仍显示原字体。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (failure.isNotBlank()) {
            ZiyuText(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!loaded) ZiyuOutlinedButton(onClick = { reload = !reload }, enabled = !saving && !busy,
                modifier = Modifier.widthIn(min = 100.dp)) { ZiyuText("重新读取") }
        }
    }
}
