package io.github.xgl34222220.ziyu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

private data class CurrentPreview(val file: File? = null, val data: JSONObject? = null, val error: String = "")

@Composable
internal fun NativeCurrentFontPreview(fontId: String, text: String, weight: Float, modifier: Modifier,
    textSizeSp: Float = 22f) {
    val context = LocalContext.current.applicationContext
    val preview by produceState(CurrentPreview(), fontId) {
        val file = File(context.cacheDir, "current-font-${UUID.randomUUID()}.ttf")
        try {
            value = withContext(Dispatchers.IO) {
                val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh current_font_preview " +
                    "${RootShell.quote(fontId)} ${RootShell.quote(file.absolutePath)}", 25_000L)
                val root = result.stdout.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
                    ?.let { JSONObject(it) } ?: error("未收到当前字体样例")
                check(result.code == 0 && root.optString("status") == "ok") { root.optString("message", "当前字体样例不可用") }
                val data = root.getJSONObject("data")
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                check(hash.digest().joinToString("") { "%02x".format(it) } == data.getString("sha256")) { "当前样例校验失败" }
                CurrentPreview(file, data)
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            value = CurrentPreview(error = error.message ?: "当前字体样例不可用")
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }
    Column(modifier) {
        val file = preview.file
        if (file == null) ZiyuText(preview.error.ifBlank { "正在读取当前字体…" },
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall) else {
            val axis = preview.data?.optJSONObject("weightAxis")
            val range = axis?.let { it.getDouble("min").toFloat()..it.getDouble("max").toFloat() }
            NativeLocalFontPreview(file, preview.data!!.getString("sha256"), preview.data!!.optInt("faceIndex"), text,
                range?.let { weight.coerceIn(it) }, Modifier.fillMaxWidth().weight(1f), textSizeSp = textSizeSp,
                errorMessage = "当前字体样例无法预览。")
        }
    }
}
