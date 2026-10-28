package io.github.xgl34222220.ziyu.ui.library

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuFilledTonalButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TTF_EXPORT_BRIDGE = "/data/adb/modules/ziyu/common/font_ttf_export.sh"

private suspend fun ttfCommand(command: String): JSONObject {
    val result = RootShell.exec("sh ${RootShell.quote(TTF_EXPORT_BRIDGE)} $command", timeoutMs = 360_000L)
    val root = result.stdout.lineSequence().mapNotNull { line ->
        runCatching { JSONObject(line) }.getOrNull()
    }.lastOrNull() ?: error(result.stderr.ifBlank { "字体模块没有返回导出结果，请确认 App 与模块版本一致" })
    if (result.code != 0 || root.optString("status") != "ok") {
        error(root.optString("message", "字体导出失败"))
    }
    return root.getJSONObject("data")
}

@Composable
internal fun RowScope.FontTtfExportButton(font: FontItem, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var workPath by rememberSaveable(font.id) { mutableStateOf("") }
    var token by rememberSaveable(font.id) { mutableStateOf("") }
    val close: () -> Unit = {
        if (!busy) {
            show = false
            val previous = workPath
            workPath = ""
            token = ""
            choices = emptyList()
            if (previous.isNotBlank()) scope.launch {
                withContext(Dispatchers.IO) { File(previous).deleteRecursively() }
            }
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("font/ttf")) { uri ->
        val path = workPath
        if (path.isBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            show = true
            message = if (uri == null) "已取消导出" else "正在生成并导出 TTF…"
            error = false
            try {
                if (uri != null) {
                    val result = ttfCommand("export ${RootShell.quote(path)} ${RootShell.quote(token)}")
                    withContext(Dispatchers.IO) {
                        val file = File(path, "export.ttf")
                        require(file.isFile && file.length() == result.getLong("bytes")) { "TTF 输出文件不完整" }
                        val sha = MessageDigest.getInstance("SHA-256")
                        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("无法打开导出位置")
                        output.buffered().use { destination ->
                            file.inputStream().buffered().use { source ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    sha.update(buffer, 0, count)
                                    destination.write(buffer, 0, count)
                                }
                            }
                        }
                        val actual = sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(actual == result.getString("sha256")) { "TTF 文件校验失败" }
                    }
                    message = "TTF 已导出到所选位置"
                }
            } catch (failure: Exception) {
                error = true
                message = failure.message ?: "TTF 导出失败"
                if (uri != null) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                if (failure is CancellationException) throw failure
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { File(path).deleteRecursively() }
                workPath = ""
                token = ""
                choices = emptyList()
                busy = false
            }
        }
    }

    ZiyuFilledTonalButton(
        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
        enabled = enabled && !busy,
        onClick = {
            show = true
            busy = true
            message = "正在读取可导出的字重与字体面…"
            error = false
            choices = emptyList()
            scope.launch {
                try {
                    if (workPath.isNotBlank()) withContext(Dispatchers.IO) { File(workPath).deleteRecursively() }
                    val root = File(context.cacheDir, "font_archive/ttf-${UUID.randomUUID()}")
                    withContext(Dispatchers.IO) { require(root.parentFile!!.mkdirs() || root.parentFile!!.isDirectory) }
                    workPath = root.absolutePath
                    val result = ttfCommand("prepare ${RootShell.quote(font.id)} ${RootShell.quote(workPath)}")
                    val values = result.getJSONArray("choices")
                    choices = List(values.length()) { values.getJSONObject(it) }
                    token = choices.first().getString("token")
                    message = "请选择要导出的字重或字体面。普通 TTF 保留原文件；OTF 转换为 TrueType 轮廓。"
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = true
                    message = failure.message ?: "读取导出字体失败"
                } finally {
                    busy = false
                }
            }
        },
    ) { ZiyuText(if (busy) "导出中" else "导出 TTF", fontSize = 12.sp) }

    if (show) ZiyuAlertDialog(
        onDismissRequest = close,
        title = { ZiyuText("导出 ${font.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuText(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(choices, key = { it.getString("token") }) { choice ->
                        ZiyuFilledTonalButton(
                            onClick = { token = choice.getString("token") }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                ZiyuText((if (token == choice.getString("token")) "✓ " else "") + choice.getString("label"))
                                ZiyuText(choice.getString("note"), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ZiyuTextButton(enabled = !busy && choices.isNotEmpty(), onClick = {
                show = false
                val selected = choices.first { it.getString("token") == token }
                save.launch(safeFontArchiveSegment(font.name + "-" + selected.getString("label")) + ".ttf")
            }) { ZiyuText("选择保存位置") }
        },
        dismissButton = {
            ZiyuTextButton(enabled = !busy, onClick = close) { ZiyuText("关闭") }
        },
    )
}
