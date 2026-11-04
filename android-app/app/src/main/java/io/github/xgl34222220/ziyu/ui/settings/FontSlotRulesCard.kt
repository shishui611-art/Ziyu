package io.github.xgl34222220.ziyu.ui.settings

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun FontSlotRulesCard() {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var includes by remember { mutableStateOf("") }
    var excludes by remember { mutableStateOf("") }
    var prefixes by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var checkedPayload by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var savedValues by remember { mutableStateOf(Triple("", "", "")) }
    suspend fun request(action: String, data: String = "", proof: String = ""): JSONObject {
        val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh slot_rules $action " +
            "${RootShell.quote(data)} ${RootShell.quote(proof)}", 20_000L)
        val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            ?: error(result.stderr.ifBlank { "无法读取设置，请更新同版模块" })
        if (result.code != 0 || json.optString("status") != "ok") error(json.optString("message", "设置读取失败"))
        return json
    }
    suspend fun load() {
        busy = true
        try {
            val rules = request("get").getJSONObject("rules")
            fun lines(key: String): String = rules.optJSONArray(key)?.let { a ->
                (0 until a.length()).joinToString("\n") { a.getString(it) }
            }.orEmpty()
            includes = lines("includeOnly"); excludes = lines("excludePaths"); prefixes = lines("excludePathPrefixes")
            savedValues = Triple(includes, excludes, prefixes)
            loaded = true; failed = false; message = ""
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { message = error.message ?: "设置读取失败"; failed = true
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { load() }
    val payload = remember(includes, excludes, prefixes) {
        fun rows(text: String) = JSONArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
        Base64.encodeToString(JSONObject().put("schema", "ziyu-font-slot-overrides-v1").put("revision", 1)
            .put("includeOnly", rows(includes)).put("excludePaths", rows(excludes))
            .put("excludePathPrefixes", rows(prefixes)).toString().toByteArray(), Base64.NO_WRAP)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ZiyuText("选择哪些系统字体保持原样。", style = MaterialTheme.typography.bodyMedium)
        if (loaded) ZiyuText(if (savedValues.toList().all { it.isBlank() }) "当前使用默认设置" else "已设置自定义规则",
            style = MaterialTheme.typography.bodySmall)
        ZiyuOutlinedButton(enabled = loaded && !busy, onClick = { expanded = !expanded }) {
            ZiyuText(if (expanded) "收起高级设置" else "高级设置")
        }
        if (expanded) {
            ZiyuText("每行一个完整路径；留空使用默认设置。", style = MaterialTheme.typography.bodySmall)
            listOf(Triple("只替换这些字体", includes, { s: String -> includes = s }),
                Triple("保留这些字体", excludes, { s: String -> excludes = s }),
                Triple("保留这些目录", prefixes, { s: String -> prefixes = s })).forEach { (label, value, change) ->
                ZiyuOutlinedTextField(value = value, onValueChange = { change(it); token = ""; message = "" }, enabled = loaded && !busy,
                    label = { ZiyuText(label, style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            }
            ZiyuTextButton(enabled = loaded && !busy, onClick = {
                includes = ""; excludes = ""; prefixes = ""; token = ""; message = "请检查更改后保存。"
            }) { ZiyuText("恢复默认") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZiyuOutlinedButton(enabled = loaded && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = request("preview", payload)
                            token = result.getString("token"); checkedPayload = payload
                            val before = if (result.isNull("before")) "原设置不可用" else "原来 ${result.getInt("before")} 个"
                            message = "将替换 ${result.getInt("after")} 个字体文件，$before。"
                            failed = false
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { message = error.message ?: "检查失败"; token = ""; failed = true
                        } finally { busy = false }
                    }
                }, modifier = Modifier.weight(1f)) { ZiyuText(if (busy) "处理中…" else "检查更改") }
                ZiyuButton(enabled = !busy && token.isNotEmpty() && checkedPayload == payload, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            request("save", payload, token)
                            savedValues = Triple(includes, excludes, prefixes)
                            message = "已保存，重新应用字体后生效。"; token = ""; failed = false
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { message = error.message ?: "保存失败"; failed = true
                        } finally { busy = false }
                    }
                }, modifier = Modifier.weight(1f)) { ZiyuText("保存") }
            }
            if (message.isBlank()) ZiyuText("保存后重新应用字体生效。", style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (!loaded && failed) ZiyuTextButton(onClick = { scope.launch { load() } }, enabled = !busy) { ZiyuText("重新读取") }
    }
}
