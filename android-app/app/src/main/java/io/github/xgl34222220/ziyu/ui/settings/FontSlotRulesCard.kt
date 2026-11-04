package io.github.xgl34222220.ziyu.ui.settings

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class RetainableFont(val path: String, val name: String, val role: String)
private data class CheckedFontRules(val payload: String, val token: String, val retained: Int)
private val FontRuleGroups = linkedMapOf(
    "ui-sans" to "界面文字", "cjk" to "中文字体", "latin" to "英文字体",
    "numeric" to "数字字体", "clock" to "时钟字体",
)
private fun rulePaths(text: String): Set<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
private fun JSONObject.ruleLines(key: String): String = optJSONArray(key)?.let { rows ->
    (0 until rows.length()).joinToString("\n") { rows.getString(it) }
}.orEmpty()

@Composable
internal fun FontSlotRulesCard() {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fonts by remember { mutableStateOf(emptyList<RetainableFont>()) }
    var includes by remember { mutableStateOf("") }
    var excludes by remember { mutableStateOf("") }
    var prefixes by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var savedValues by remember { mutableStateOf(Triple("", "", "")) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<CheckedFontRules?>(null) }
    var confirmSimpleMode by remember { mutableStateOf(false) }
    fun applyRules(rules: JSONObject) {
        includes = rules.ruleLines("includeOnly")
        excludes = rules.ruleLines("excludePaths")
        prefixes = rules.ruleLines("excludePathPrefixes")
        savedValues = Triple(includes, excludes, prefixes)
    }
    fun edited() { pendingSave = null; message = ""; failed = false }
    suspend fun request(action: String, data: String = "", proof: String = ""): JSONObject {
        val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh slot_rules $action " +
            "${RootShell.quote(data)} ${RootShell.quote(proof)}", 20_000L)
        val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            ?: error(result.stderr.ifBlank { "请同时更新 App 和模块" })
        if (result.code != 0 || json.optString("status") != "ok") error(json.optString("message", "设置读取失败"))
        return json
    }
    suspend fun load() {
        busy = true; loaded = false
        try {
            val result = request("list")
            val rows = result.getJSONArray("fonts")
            val available = (0 until rows.length()).map { index ->
                val item = rows.getJSONObject(index)
                RetainableFont(item.getString("path"), item.getString("name"), item.getString("role"))
            }.distinctBy { it.path }
            applyRules(result.getJSONObject("rules"))
            fonts = available
            loaded = true; failed = false; message = ""
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { message = error.message ?: "字体列表读取失败"; failed = true
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { load() }
    val includePaths = remember(includes) { rulePaths(includes) }
    val excludePaths = remember(excludes) { rulePaths(excludes) }
    val prefixPaths = remember(prefixes) { rulePaths(prefixes) }
    val advancedRules = includePaths.isNotEmpty() || prefixPaths.isNotEmpty()
    val dirty = Triple(includes, excludes, prefixes) != savedValues
    fun retained(font: RetainableFont): Boolean = font.path in excludePaths ||
        (includePaths.isNotEmpty() && font.path !in includePaths) ||
        prefixPaths.any { font.path.startsWith(it.trimEnd('/') + "/") }
    fun retain(paths: List<String>, keep: Boolean) {
        val updated = if (keep) excludePaths + paths else excludePaths - paths.toSet()
        if (updated.size > 512) { message = "最多保留 512 个字体文件。"; failed = true; return }
        excludes = updated.sorted().joinToString("\n"); edited()
    }
    val payload = remember(includes, excludes, prefixes) {
        fun rows(text: String) = JSONArray(rulePaths(text).toList())
        Base64.encodeToString(JSONObject().put("schema", "ziyu-font-slot-overrides-v1").put("revision", 1)
            .put("includeOnly", rows(includes)).put("excludePaths", rows(excludes))
            .put("excludePathPrefixes", rows(prefixes)).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ZiyuText("勾选后保留原厂字体，保存后重新应用生效。", style = MaterialTheme.typography.bodyMedium)
        if (!loaded && busy) ZiyuText("正在读取字体…", style = MaterialTheme.typography.bodySmall)
        if (loaded) {
            ZiyuText(when {
                dirty -> "有未保存的更改"
                savedValues.toList().all { it.isBlank() } -> "当前使用默认设置"
                else -> "已保存自定义设置"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (advancedRules) {
                ZiyuText("高级规则已启用，切换后可直接勾选。", style = MaterialTheme.typography.bodySmall)
                ZiyuOutlinedButton(enabled = !busy, onClick = { confirmSimpleMode = true }) { ZiyuText("切换为勾选设置") }
            }
            FontRuleGroups.forEach { (role, label) ->
                val group = fonts.filter { it.role == role }
                if (group.isNotEmpty()) {
                    val count = group.count(::retained)
                    val state = when (count) {
                        0 -> ToggleableState.Off
                        group.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).triStateToggleable(
                            state = state, enabled = !busy && !advancedRules, role = Role.Checkbox,
                            onClick = { retain(group.map { it.path }, state != ToggleableState.On) },
                        ).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ZiyuCheckbox(checked = state == ToggleableState.On, onCheckedChange = null,
                                indeterminate = state == ToggleableState.Indeterminate,
                                enabled = !busy && !advancedRules, modifier = Modifier.padding(end = 8.dp))
                            Column {
                                ZiyuText(label, style = MaterialTheme.typography.bodyMedium)
                                ZiyuText("保留 $count / ${group.size} 个文件", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ZiyuTextButton(enabled = !busy && !advancedRules, onClick = { selectedGroup = role }) { ZiyuText("选择") }
                    }
                    ZiyuHorizontalDivider()
                }
            }
            if (fonts.isEmpty()) ZiyuText("暂无可选择的字体。", style = MaterialTheme.typography.bodySmall)
        }
        ZiyuTextButton(enabled = loaded && !busy, onClick = { expanded = !expanded }) {
            ZiyuText(if (expanded) "收起高级设置" else "高级设置")
        }
        if (expanded) {
            ZiyuText("每行一个完整路径。", style = MaterialTheme.typography.bodySmall)
            listOf(Triple("只替换这些字体", includes, { s: String -> includes = s }),
                Triple("保留这些字体", excludes, { s: String -> excludes = s }),
                Triple("保留这些目录", prefixes, { s: String -> prefixes = s })).forEach { (label, value, change) ->
                ZiyuOutlinedTextField(value = value, onValueChange = { change(it); edited() }, enabled = loaded && !busy,
                    label = { ZiyuText(label, style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZiyuOutlinedButton(enabled = loaded && !busy && (dirty || savedValues.toList().any { it.isNotBlank() }), onClick = {
                includes = ""; excludes = ""; prefixes = ""; edited()
            }, modifier = Modifier.weight(1f)) { ZiyuText("恢复默认") }
            ZiyuButton(enabled = loaded && !busy && dirty, onClick = {
                val draft = payload; busy = true
                scope.launch {
                    try {
                        val result = request("preview", draft)
                        pendingSave = CheckedFontRules(draft, result.getString("token"), result.getInt("retained"))
                        failed = false; message = ""
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) { message = error.message ?: "检查失败"; failed = true
                    } finally { busy = false }
                }
            }, modifier = Modifier.weight(1f)) { ZiyuText(if (busy) "处理中…" else "保存") }
        }
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (!loaded && failed) ZiyuTextButton(onClick = { scope.launch { load() } }, enabled = !busy) { ZiyuText("重新读取") }
    }
    selectedGroup?.let { role ->
        RetainedFontPicker(title = FontRuleGroups.getValue(role), fonts = fonts.filter { it.role == role },
            retainedPaths = excludePaths, onRetain = { path, keep -> retain(listOf(path), keep) },
            onDismiss = { selectedGroup = null })
    }
    pendingSave?.let { checked ->
        ZiyuAlertDialog(
            onDismissRequest = { if (!busy) pendingSave = null }, title = { ZiyuText("保存设置？") },
            text = { ZiyuText(if (checked.retained == 0) "将保存设置，重新应用字体后生效。"
                else "将保留 ${checked.retained} 个字体文件，重新应用字体后生效。") },
            confirmButton = {
                ZiyuButton(enabled = !busy && checked.payload == payload, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = request("save", checked.payload, checked.token)
                            applyRules(result.getJSONObject("rules"))
                            message = "已保存，重新应用字体后生效。"; pendingSave = null; failed = false
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { message = error.message ?: "保存失败"; pendingSave = null; failed = true
                        } finally { busy = false }
                    }
                }) { ZiyuText(if (busy) "保存中…" else "保存") }
            },
            dismissButton = { ZiyuTextButton(enabled = !busy, onClick = { pendingSave = null }) { ZiyuText("取消") } },
        )
    }
    if (confirmSimpleMode) ZiyuAlertDialog(
        onDismissRequest = { confirmSimpleMode = false }, title = { ZiyuText("改用勾选设置？") },
        text = { ZiyuText("保留当前选择并清除高级规则。以后新增的字体按默认设置处理。") },
        confirmButton = {
            ZiyuButton(onClick = {
                val kept = excludePaths + fonts.filter(::retained).map { it.path }
                if (kept.size <= 512) {
                    includes = ""; prefixes = ""; excludes = kept.sorted().joinToString("\n"); edited()
                } else { message = "最多保留 512 个字体文件，请在高级设置中调整。"; failed = true }
                confirmSimpleMode = false
            }) { ZiyuText("切换") }
        },
        dismissButton = { ZiyuTextButton(onClick = { confirmSimpleMode = false }) { ZiyuText("取消") } },
    )
}

@Composable
private fun RetainedFontPicker(
    title: String, fonts: List<RetainableFont>, retainedPaths: Set<String>,
    onRetain: (String, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var query by remember(title) { mutableStateOf("") }
    val visible = remember(fonts, query) { fonts.filter { query.isBlank() || it.path.contains(query.trim(), ignoreCase = true) } }
    val duplicateNames = remember(fonts) { fonts.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys }
    ZiyuAlertDialog(
        onDismissRequest = onDismiss, title = { ZiyuText("保留$title") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuOutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { ZiyuText("搜索字体") }, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { ZiyuIcon(Icons.Rounded.Search, contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp).size(20.dp)) })
                if (visible.isEmpty()) ZiyuText("没有匹配的字体", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(visible, key = { it.path }) { font ->
                        val checked = font.path in retainedPaths
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = checked, role = Role.Checkbox, onValueChange = { onRetain(font.path, it) },
                        ).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ZiyuCheckbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                ZiyuText(font.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (font.name in duplicateNames) ZiyuText(font.path.substringBeforeLast('/'),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { ZiyuTextButton(onClick = onDismiss) { ZiyuText("完成") } },
    )
}
