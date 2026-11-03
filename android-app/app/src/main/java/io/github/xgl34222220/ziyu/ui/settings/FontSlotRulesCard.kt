package io.github.xgl34222220.ziyu.ui.settings

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    suspend fun request(action: String, data: String = "", proof: String = ""): JSONObject {
        val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh slot_rules $action " +
            "${RootShell.quote(data)} ${RootShell.quote(proof)}", 20_000L)
        val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            ?: error(result.stderr.ifBlank { "槽位规则组件未就绪，请确认已刷入同版模块" })
        if (json.optString("status") != "ok") error(json.optString("message", "规则读取失败"))
        return json
    }
    LaunchedEffect(Unit) {
        try {
            val rules = request("get").getJSONObject("rules")
            fun lines(key: String): String = rules.optJSONArray(key)?.let { a ->
                (0 until a.length()).joinToString("\n") { a.getString(it) }
            }.orEmpty()
            includes = lines("includeOnly"); excludes = lines("excludePaths"); prefixes = lines("excludePathPrefixes")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { message = error.message.orEmpty()
        } finally { loaded = true }
    }
    val payload = remember(includes, excludes, prefixes) {
        fun rows(text: String) = JSONArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
        Base64.encodeToString(JSONObject().put("schema", "ziyu-font-slot-overrides-v1").put("revision", 1)
            .put("includeOnly", rows(includes)).put("excludePaths", rows(excludes))
            .put("excludePathPrefixes", rows(prefixes)).toString().toByteArray(), Base64.NO_WRAP)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ZiyuText("字体槽位规则")
        ZiyuText("每行一个完整路径。白名单留空时沿用原范围；黑名单优先。规则只能缩小替换范围，不能解除表情、符号等系统保护。保存不改变当前字体，下一次确认应用时生效。")
        listOf(Triple("只允许替换这些槽位（可留空）", includes, { s: String -> includes = s }),
            Triple("不替换这些槽位", excludes, { s: String -> excludes = s }),
            Triple("排除这些目录", prefixes, { s: String -> prefixes = s })).forEach { (label, value, change) ->
            ZiyuOutlinedTextField(value = value, onValueChange = change, enabled = loaded && !busy,
                label = { ZiyuText(label) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        }
        if (message.isNotBlank()) ZiyuText(message)
        ZiyuOutlinedButton(enabled = loaded && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val result = request("preview", payload)
                    token = result.getString("token"); checkedPayload = payload
                    val before = if (result.isNull("before")) "原配置无效" else result.getInt("before").toString()
                    message = "候选槽位：$before → ${result.getInt("after")}。确认后保存规则；当前挂载保持原状。"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { message = error.message.orEmpty(); token = ""
                } finally { busy = false }
            }
        }) { ZiyuText(if (busy) "正在处理…" else "预览规则变化") }
        ZiyuButton(enabled = !busy && token.isNotEmpty() && checkedPayload == payload, onClick = {
            busy = true
            scope.launch {
                try { message = request("save", payload, token).getString("message"); token = ""
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { message = error.message.orEmpty()
                } finally { busy = false }
            }
        }) { ZiyuText("确认保存规则") }
    }
}
