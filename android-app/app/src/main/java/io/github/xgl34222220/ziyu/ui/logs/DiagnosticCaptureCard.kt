package io.github.xgl34222220.ziyu.ui.logs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun DiagnosticCaptureCard() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var state by remember { mutableStateOf(JSONObject().put("state", "idle")) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var sensitive by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf("") }
    suspend fun request(action: String) {
        val session = if (action in setOf("stop", "export")) state.optString("session") else ""
        val options = if (action == "start" && sensitive) " --sensitive" else ""
        val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh diagnostic_capture $action ${RootShell.quote(session)}$options", 20_000L)
        val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            ?: error(result.stderr.ifBlank { "故障记录组件未就绪，请刷入同版模块后重试" })
        if (json.optString("status") != "ok") error(json.optString("message", "操作失败"))
        if (action == "export") exportPath = json.getString("path") else state = json
    }
    fun act(action: String) {
        busy = true
        scope.launch {
            try { request(action); message = ""
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { message = error.message.orEmpty()
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { request("status")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { message = error.message.orEmpty() }
    }
    val phase = state.optString("state", "idle")
    LaunchedEffect(phase) {
        if (phase in setOf("running", "finalizing")) while (true) {
            delay(2000)
            try { request("status")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { message = error.message.orEmpty(); break }
        }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ZiyuText("故障复现记录")
        ZiyuText(when (phase) {
            "running" -> "正在记录，请复现问题后点击停止。最多采集 120 秒，日志总量受限，离开此页面仍会自动结束。"
            "finalizing" -> "实时日志采集已结束，正在整理快照和压缩包…"
            "completed" -> "采集已结束，记录已在字域私有目录打包。可导出后检查、分享。"
            "failed", "interrupted" -> "采集被中断或部分失败，原始日志已保留，可导出已有记录。"
            else -> "主动记录短时间的系统、崩溃和字体相关日志，用于排查偶发问题。不会自动上传。"
        })
        if (state.optString("error").isNotBlank()) ZiyuText(state.optString("error"))
        if (message.isNotBlank()) ZiyuText(message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZiyuButton(enabled = !busy && phase != "finalizing", onClick = { if (phase == "running") act("stop") else confirm = true }) {
                ZiyuText(if (phase == "running") "停止采集并打包" else if (phase == "finalizing") "正在打包…" else "开始采集")
            }
            ZiyuOutlinedButton(enabled = !busy && phase !in setOf("idle", "running", "finalizing"), onClick = { act("export") }) { ZiyuText("导出采集记录") }
        }
        ZiyuTextButton(enabled = !busy, onClick = { act("status") }) { ZiyuText("刷新记录状态") }
    }
    if (confirm) ZiyuAlertDialog(onDismissRequest = { confirm = false }, title = { ZiyuText("开始故障记录？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText("日志可能包含应用名称、文件路径和个人信息。最多采集 120 秒，约 12 MB；仅停止本次启动的记录进程。请在分享前检查内容。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuSwitch(checked = sensitive, onCheckedChange = { sensitive = it })
                ZiyuText("额外收集原始 ANR 与 DropBox（可选，可能含敏感信息）")
            }
        } }, confirmButton = { ZiyuButton(onClick = { confirm = false; act("start") }) { ZiyuText("开始记录") } },
        dismissButton = { ZiyuTextButton(onClick = { confirm = false }) { ZiyuText("取消") } })
    if (exportPath.isNotBlank()) ZiyuAlertDialog(onDismissRequest = { exportPath = "" }, title = { ZiyuText("故障记录已导出") },
        text = { ZiyuText("$exportPath\n\n请先检查日志内容，再自行决定是否分享。") },
        confirmButton = { ZiyuButton(onClick = { clipboard.setText(AnnotatedString(exportPath)) }) { ZiyuText("复制保存位置") } },
        dismissButton = { ZiyuTextButton(onClick = { exportPath = "" }) { ZiyuText("完成") } })
}
