package io.github.xgl34222220.ziyu.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.NativeLocalFontPreview
import io.github.xgl34222220.ziyu.legacy.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class EmojiAsset(val id: String, val name: String)
private data class EmojiState(
    val items: List<EmojiAsset>, val selected: String, val active: String,
    val selectedName: String, val activeName: String, val pending: Boolean, val mounted: Boolean,
    val problem: String,
)

private suspend fun emojiCommand(action: String, vararg arguments: String): JSONObject {
    val command = listOf(action, *arguments).joinToString(" ", transform = RootShell::quote)
    val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh emoji $command", 180_000L)
    val reply = result.stdout.lineSequence().map(String::trim).lastOrNull { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
    check(result.code == 0 && reply?.optString("status") == "ok") {
        reply?.optString("message").orEmpty().ifBlank {
            result.stderr.trim().ifBlank { "Emoji 操作未完成，请确认模块已更新后重试" }
        }
    }
    return reply!!.optJSONObject("data") ?: JSONObject()
}

private suspend fun loadEmojiState(): EmojiState {
    val data = emojiCommand("status")
    val entries = data.optJSONArray("items")
    val items = (0 until (entries?.length() ?: 0)).map { index ->
        val item = entries!!.getJSONObject(index)
        EmojiAsset(item.getString("id"), item.getString("name"))
    }
    return EmojiState(items, data.getString("selected"), data.getString("active"),
        data.getString("selectedName"), data.getString("activeName"),
        data.getBoolean("pending"), data.getBoolean("mounted"), data.optString("problem"))
}

private suspend fun importEmoji(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    var temporary = File(context.cacheDir, "emoji-${UUID.randomUUID()}.ttf")
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开所选文件" }
            temporary.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 128L * 1024 * 1024) { "导入文件超过 128 MiB" }
                    output.write(buffer, 0, count)
                }
            }
        }
        val zipped = temporary.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
        if (zipped) {
            val archive = File(temporary.parentFile, temporary.nameWithoutExtension + ".zip")
            check(temporary.renameTo(archive)) { "导入临时文件保存失败" }
            temporary = archive
        }
        emojiCommand("import", temporary.absolutePath).getString("id")
    } finally {
        temporary.delete()
    }
}

@Composable
internal fun EmojiToolsCard() {
    var open by remember { mutableStateOf(false) }
    SettingCard("Emoji") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZiyuText("导入彩色 Emoji 字体，应用后完整重启。", style = MaterialTheme.typography.bodySmall)
            ZiyuOutlinedButton(onClick = { open = true }) { ZiyuText("管理 Emoji") }
        }
    }
    if (open) EmojiManagerDialog(onClose = { open = false })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EmojiManagerDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EmojiState?>(null) }
    var chosen by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }

    suspend fun refresh(preferred: String? = null) {
        val updated = loadEmojiState()
        state = updated
        chosen = preferred ?: chosen.takeIf { id -> updated.items.any { it.id == id } }
            ?: updated.selected.takeUnless { it == "default" } ?: updated.items.firstOrNull()?.id.orEmpty()
    }
    fun reload() {
        if (busy) return
        busy = true
        scope.launch {
            try { refresh(); message = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Emoji 状态读取失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = error.message ?: "Emoji 状态读取失败" }
        finally { busy = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            message = "正在导入…"
            scope.launch {
                try {
                    val imported = importEmoji(context, uri)
                    refresh(imported)
                    message = "导入完成"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { message = error.message ?: "Emoji 导入失败"
                } finally { busy = false }
            }
        }
    }
    ZiyuAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { ZiyuText("Emoji") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val current = state
                if (current != null) {
                    ZiyuText("本次启动 ${current.activeName}", style = MaterialTheme.typography.bodySmall)
                    if (current.pending) {
                        ZiyuText("待完整重启 ${current.selectedName}", style = MaterialTheme.typography.bodySmall)
                    } else if (current.problem.isNotBlank()) {
                        ZiyuText(current.problem, style = MaterialTheme.typography.bodySmall)
                    } else if (current.active != "default" && !current.mounted) {
                        ZiyuText("Emoji 挂载尚未确认，请导出日志。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ZiyuText("支持 TTF、OTF 和 ZIP。应用自带的图片表情由应用决定。",
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZiyuButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { ZiyuText("导入") }
                    ZiyuTextButton(onClick = ::reload, enabled = !busy) { ZiyuText("刷新") }
                }
                current?.items?.forEach { item ->
                    ZiyuOutlinedButton(onClick = { chosen = item.id }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()) {
                        ZiyuText((if (chosen == item.id) "✓ " else "") + item.name,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (chosen.isNotBlank()) EmojiSample(chosen)
                if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall)
                if (busy) ZiyuText("正在处理…", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZiyuButton(onClick = { confirmation = chosen }, enabled = !busy && chosen.isNotBlank()) {
                        ZiyuText("应用")
                    }
                    ZiyuOutlinedButton(onClick = { confirmation = "default" },
                        enabled = !busy && current != null && (current.selected != "default" || current.active != "default")) {
                        ZiyuText("恢复默认")
                    }
                }
            }
        },
        confirmButton = { ZiyuTextButton(onClick = onClose, enabled = !busy) { ZiyuText("关闭") } },
    )
    confirmation?.let { target ->
        ZiyuAlertDialog(
            onDismissRequest = { confirmation = null },
            title = { ZiyuText(if (target == "default") "恢复默认 Emoji？" else "应用 Emoji？") },
            text = { ZiyuText("保留文字字体选择，完整重启后生效。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    confirmation = null
                    busy = true
                    message = "正在准备 Emoji…"
                    scope.launch {
                        try {
                            emojiCommand("apply", target)
                            message = "Emoji 已准备，完整重启后生效"
                            refresh()
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) {
                            message = error.message ?: "Emoji 应用未完成"
                            // A committed transaction can outlive an interrupted App reply.
                            try { refresh() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Keep the operation's original failure visible. */ }
                        } finally { busy = false }
                    }
                }) { ZiyuText(if (target == "default") "恢复默认" else "应用") }
            },
            dismissButton = { ZiyuTextButton(onClick = { confirmation = null }) { ZiyuText("取消") } },
        )
    }
}

private data class EmojiPreview(val file: File? = null, val error: String = "")

@Composable
private fun EmojiSample(id: String) {
    val context = LocalContext.current.applicationContext
    val preview by produceState(EmojiPreview(), id) {
        value = EmojiPreview()
        val file = File(context.cacheDir, "emoji-${UUID.randomUUID()}.ttf")
        try {
            withContext(Dispatchers.IO) { check(file.createNewFile()) { "无法创建 Emoji 预览" } }
            emojiCommand("preview", id, file.absolutePath)
            withContext(Dispatchers.IO) {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                check(hash.digest().joinToString("") { "%02x".format(it) } == id) { "Emoji 预览校验失败" }
            }
            value = EmojiPreview(file)
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            value = EmojiPreview(error = error.message ?: "Emoji 预览失败")
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }
    val file = preview.file
    if (file != null) {
        NativeLocalFontPreview(file, id, 0, "😀 😍 🥰 🤣 🫠\n👍🏽 ❤️ 1️⃣ 🇨🇳 👨‍👩‍👧‍👦", null,
            Modifier.fillMaxWidth().height(136.dp), textSizeSp = 27f,
            errorMessage = "当前系统无法预览这份 Emoji。")
    } else ZiyuText(preview.error.ifBlank { "正在读取预览…" }, style = MaterialTheme.typography.bodySmall)
}
