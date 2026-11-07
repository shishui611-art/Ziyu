package io.github.xgl34222220.ziyu

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.design.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

private data class ImportPreview(val name: String = "", val file: File? = null,
    val sha256: String = "", val metadata: JSONObject? = null, val error: String = "")

@Composable
internal fun NativeImportFontPreview(uris: List<Uri>, enabled: Boolean, onCancel: () -> Unit,
    onImport: (List<Uri>) -> Unit) {
    val context = LocalContext.current.applicationContext
    var selected by remember(uris) { mutableStateOf(uris.take(32)) }
    var index by remember { mutableIntStateOf(0) }
    var faceIndex by remember { mutableIntStateOf(0) }
    var weight by remember { mutableFloatStateOf(400f) }
    var renderedWeight by remember { mutableFloatStateOf(400f) }
    var editing by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(weight) { delay(100); renderedWeight = weight }
    var text by remember { mutableStateOf(context.getSharedPreferences("font-preview-v1", 0)
        .getString("text", "").orEmpty().ifBlank { "山海有相逢\nAa 0123" }) }
    val uri = selected.getOrNull(index)
    val preview by produceState(ImportPreview(), uri) {
        value = ImportPreview()
        val dir = File(context.cacheDir, "native-import-preview-${UUID.randomUUID()}")
        try {
            val staged = withContext(Dispatchers.IO) {
                requireNotNull(uri)
                dir.mkdirs()
                val name = queryDisplayName(context, uri) ?: "font.ttf"
                val extension = name.substringAfterLast('.', "").lowercase()
                require(extension in FONT_IMPORT_EXTENSIONS) { "此文件不支持预览，可继续导入后查看结果" }
                val temp = File(dir, "copy.tmp")
                val hash = MessageDigest.getInstance("SHA-256")
                copyUriWithLimit(context, uri, temp, hash)
                val sha = hash.digest().joinToString("") { "%02x".format(it) }
                val file = File(dir, "$sha.$extension")
                check(temp.renameTo(file)) { "预览临时文件保存失败" }
                ImportPreview(name, file, sha)
            }
            value = staged
            val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh import_preview ${RootShell.quote(staged.file!!.absolutePath)}", 25_000L)
            val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            value = if (json?.optString("status") == "ok") staged.copy(metadata = json.getJSONObject("data"))
                else staged.copy(error = json?.optString("message") ?: "详细信息暂不可用，可继续预览和导入")
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            value = value.copy(error = error.message ?: "预览失败，可继续导入")
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }
    val faces = preview.metadata?.optJSONArray("faces")
    val face = faces?.optJSONObject(faceIndex)
    val axes = face?.optJSONArray("axes")
    val weightAxis = (0 until (axes?.length() ?: 0)).mapNotNull { axes?.optJSONObject(it) }
        .firstOrNull { it.optString("tag") == "wght" }
    val range = weightAxis?.let { it.optDouble("min", 100.0).toFloat()..it.optDouble("max", 900.0).toFloat() }
        ?.takeIf { it.start.isFinite() && it.endInclusive.isFinite() && it.start < it.endInclusive }
    LaunchedEffect(uri) { faceIndex = 0; details = false }
    val defaultWeight = importPreviewDefaultWeight(weightAxis?.optDouble("default", 400.0)?.toFloat(), range)
    // Metadata arrives after the staged file. Reset once when its axis is known,
    // without resetting a user's slider on ordinary recompositions.
    LaunchedEffect(preview.sha256, faceIndex, defaultWeight, range) {
        weight = defaultWeight
        renderedWeight = defaultWeight
    }
    ZiyuAlertDialog(
        onDismissRequest = onCancel,
        title = { ZiyuText(if (selected.size > 1) "导入预览 ${index + 1}/${selected.size}" else "导入预览",
            style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZiyuText(preview.name.ifBlank { "正在读取文件…" },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ZiyuTextButton(modifier = Modifier.weight(1f), enabled = index > 0, onClick = { index-- }) { ZiyuText("上一个") }
                        ZiyuTextButton(modifier = Modifier.weight(1f), enabled = index + 1 < selected.size, onClick = { index++ }) { ZiyuText("下一个") }
                        ZiyuTextButton(modifier = Modifier.weight(1f), onClick = {
                            selected = selected.filterIndexed { i, _ -> i != index }
                            index = index.coerceAtMost(selected.lastIndex)
                        }) { ZiyuText("移除") }
                    }
                }
                preview.file?.takeUnless { it.extension in FONT_IMPORT_ARCHIVE_EXTENSIONS }?.let { file ->
                    NativeLocalFontPreview(file, preview.sha256, faceIndex, text,
                        if (range != null) renderedWeight.coerceIn(range) else null,
                        Modifier.fillMaxWidth().height((112f * fontScale.coerceAtLeast(1f)).dp), textSizeSp = 24f)
                }
                val isArchive = preview.file?.extension in FONT_IMPORT_ARCHIVE_EXTENSIONS
                if (isArchive) ZiyuText("压缩包导入后可预览字体。", style = MaterialTheme.typography.bodySmall)
                if ((faces?.length() ?: 0) > 1) {
                    ZiyuTextButton(onClick = { faceIndex = (faceIndex + 1) % faces!!.length() }) {
                        ZiyuText("切换字体 ${faceIndex + 1}/${faces?.length()}")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (!isArchive) ZiyuTextButton(onClick = { editing = !editing }) {
                        ZiyuText(if (editing) "收起设置" else "调整预览")
                    }
                    ZiyuTextButton(onClick = { details = !details }) {
                        ZiyuText(if (details) "收起详情" else "文件详情")
                    }
                }
                if (editing && !isArchive) {
                    ZiyuOutlinedTextField(value = text, onValueChange = { if (it.codePointCount(0, it.length) <= 200) text = it },
                        label = { ZiyuText("预览文字") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    if (range != null) {
                        ZiyuText("预览字重 ${weight.toInt()}", style = MaterialTheme.typography.bodySmall)
                        ZiyuSlider(value = weight.coerceIn(range), onValueChange = { weight = it }, valueRange = range)
                    }
                }
                if (details) {
                    ZiyuText(preview.name, style = MaterialTheme.typography.bodySmall)
                    if (face != null) {
                        ZiyuText("${face.optString("fullName")} · ${face.optString("format")}", style = MaterialTheme.typography.bodySmall)
                        val coverage = face.optJSONObject("coverage")
                        fun sample(key: String): String = coverage?.optJSONObject(key)?.let { "${it.optInt("present")}/${it.optInt("required")}" } ?: "未测"
                        ZiyuText("样本：中文 ${sample("cjkProbe")} · 英文 ${sample("latinProbe")} · 数字 ${sample("digitProbe")}",
                            style = MaterialTheme.typography.bodySmall)
                        ZiyuText("样本结果不代表完整字库。", style = MaterialTheme.typography.bodySmall)
                    }
                    preview.metadata?.optJSONArray("archiveEntries")?.let { entries ->
                        ZiyuText("共 ${preview.metadata?.optInt("entryCount")} 个文件，最多显示 128 项。", style = MaterialTheme.typography.bodySmall)
                        ZiyuText((0 until entries.length()).joinToString("\n") { entries.optString(it) }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (preview.error.isNotBlank()) ZiyuText(preview.error,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { ZiyuTextButton(onClick = onCancel) { ZiyuText("取消") } },
        confirmButton = { ZiyuButton(enabled = enabled && selected.isNotEmpty(), onClick = { onImport(selected) }) {
            ZiyuText(if (selected.size > 1) "导入 ${selected.size} 个" else "导入")
        } },
    )
}
