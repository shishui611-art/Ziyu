package io.github.xgl34222220.ziyu.ui.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Warning
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.MixSlot
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val STUDIO_PROFILE_SCHEMA = 1
private const val STUDIO_PROFILE_TYPE = "ziyu-studio-profile"
private const val STUDIO_PROFILE_MAX_CHARS = 262_144

@Immutable
internal data class StudioProfileSlot(
    val fontId: String,
    val fontName: String,
    val weight: Int,
    val axes: Map<String, Float>,
)

@Immutable
internal data class StudioProfile(
    val name: String,
    val slots: Map<MixSlot, StudioProfileSlot>,
    val latinSize: Int = 0,
    val latinOffset: Int = 0,
)

@Immutable
internal data class StudioProfileParseResult(
    val profile: StudioProfile? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val valid: Boolean get() = profile != null && errors.isEmpty()
}

internal fun encodeStudioProfile(state: FontStudioUiState): String {
    val slots = JSONObject()
    state.slots.forEach { slot ->
        val font = slot.font ?: return@forEach
        slots.put(
            slot.slot.profileKey,
            JSONObject()
                .put("fontId", font.id)
                .put("fontName", font.name)
                .put("weight", slot.weight.coerceIn(1, 1000))
                .put(
                    "axes",
                    JSONObject().apply {
                        normalizedProfileAxes(slot.axes, slot.weight).toSortedMap().forEach { (tag, value) ->
                            put(tag, value.toDouble())
                        }
                    },
                ),
        )
    }
    return JSONObject()
        .put("schema", STUDIO_PROFILE_SCHEMA)
        .put("type", STUDIO_PROFILE_TYPE)
        .put("name", "字域组合方案")
        .put("createdAt", System.currentTimeMillis())
        .put("slots", slots)
        .put("latinSize", state.latinSize.coerceIn(-15, 15))
        .put("latinOffset", state.latinOffset.coerceIn(-10, 10))
        .toString(2)
}

internal fun parseStudioProfile(
    raw: String,
    availableFonts: List<FontItem>,
): StudioProfileParseResult {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val root = runCatching { JSONObject(raw) }.getOrElse {
        return StudioProfileParseResult(errors = listOf("JSON 格式无效：${it.message ?: "无法解析"}"))
    }
    if (root.optInt("schema", -1) != STUDIO_PROFILE_SCHEMA) errors += "不支持的方案版本"
    if (!io.github.xgl34222220.ziyu.LegacyIdentityCompatibility.matchesFormat(root.optString("type"), STUDIO_PROFILE_TYPE)) errors += "这不是字域组合方案文件"
    val slotsObject = root.optJSONObject("slots")
    if (slotsObject == null) errors += "方案缺少 slots 配置"
    val fontMap = availableFonts.associateBy { it.id }
    val parsedSlots = linkedMapOf<MixSlot, StudioProfileSlot>()

    MixSlot.entries.forEach { slot ->
        val item = slotsObject?.optJSONObject(slot.profileKey)
        if (item == null) {
            errors += "缺少${slot.profileLabel}槽位"
            return@forEach
        }
        val fontId = item.optString("fontId").trim()
        val savedName = item.optString("fontName").trim()
        // Re-imports can change an ID. Rebind only an unambiguous, usable name;
        // never silently replace a missing source with the first library font.
        val font = fontMap[fontId] ?: availableFonts.filter {
            it.valid && !it.combination && savedName.isNotBlank() && it.name == savedName
        }.singleOrNull()
        when {
            fontId.isBlank() -> errors += "${slot.profileLabel}槽位没有字体 ID"
            font == null -> errors += "本机字体库缺少：${item.optString("fontName", fontId)}"
            !font.valid -> errors += "${font.name} 当前未通过字体验证"
            font.combination -> errors += "${font.name} 是组合字体，请选择原字体"
        }
        if (font == null || !font.valid || font.combination || fontId.isBlank()) return@forEach
        if (font.id != fontId) warnings += "已重新关联：${font.name}"

        val weight = item.optInt("weight", 400).coerceIn(1, 1000)
        val axesObject = item.optJSONObject("axes") ?: JSONObject()
        val axes = linkedMapOf<String, Float>()
        val keys = axesObject.keys()
        while (keys.hasNext() && axes.size < 16) {
            val tag = keys.next().trim()
            val value = axesObject.optDouble(tag, Double.NaN).toFloat()
            if (tag.length == 4 && tag.all { it.code in 33..126 } && value.isFinite()) {
                axes[tag] = if (tag == "wght") value.coerceIn(1f, 1000f) else value
            } else {
                warnings += "已忽略 ${slot.profileLabel}槽位的无效设计轴：$tag"
            }
        }
        axes["wght"] = (axes["wght"] ?: weight.toFloat()).coerceIn(1f, 1000f)
        if (savedName.isNotBlank() && savedName != font.name) warnings += "$savedName 在本机显示为 ${font.name}"
        parsedSlots[slot] = StudioProfileSlot(
            fontId = font.id,
            fontName = font.name,
            weight = axes.getValue("wght").roundToInt().coerceIn(1, 1000),
            axes = axes,
        )
    }

    if (errors.isNotEmpty() || parsedSlots.size != MixSlot.entries.size) {
        return StudioProfileParseResult(errors = errors.distinct(), warnings = warnings.distinct())
    }
    return StudioProfileParseResult(
        profile = StudioProfile(
            name = root.optString("name", "导入方案").ifBlank { "导入方案" },
            slots = parsedSlots,
            latinSize = root.optInt("latinSize", 0).coerceIn(-15, 15),
            latinOffset = root.optInt("latinOffset", 0).coerceIn(-10, 10),
        ),
        warnings = warnings.distinct(),
    )
}

internal fun applyStudioProfile(profile: StudioProfile, actions: FontStudioActions): String? =
    actions.applyProfile(profile)

@Composable
internal fun StudioProfileTransferDialog(
    style: UiStyle,
    state: FontStudioUiState,
    actions: FontStudioActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingProfile by remember { mutableStateOf<StudioProfile?>(null) }
    var status by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        writer.write(encodeStudioProfile(state))
                    } ?: throw IllegalStateException("无法打开目标文件")
                }
            }
            errorMessage = result.exceptionOrNull()?.message.orEmpty()
            status = if (result.isSuccess) "方案 JSON 已导出" else ""
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { readProfileText(context.contentResolver, uri) }
            }.mapCatching { raw -> parseStudioProfile(raw, state.fonts) }
            val parsed = result.getOrNull()
            when {
                result.isFailure -> {
                    pendingProfile = null
                    status = ""
                    errorMessage = result.exceptionOrNull()?.message ?: "方案读取失败"
                }
                parsed == null || !parsed.valid -> {
                    pendingProfile = null
                    status = ""
                    errorMessage = parsed?.errors?.joinToString("\n") ?: "方案读取失败"
                }
                else -> {
                    pendingProfile = parsed.profile
                    errorMessage = ""
                    status = buildString {
                        append("已读取：${parsed.profile?.name}")
                        if (parsed.warnings.isNotEmpty()) append("\n${parsed.warnings.joinToString("\n")}")
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(if (style == UiStyle.MIUIX) 34.dp else 28.dp),
        icon = { Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("组合方案 JSON", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "保存或载入字体搭配与字重。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("导入")
                    }
                    OutlinedButton(
                        onClick = { exportLauncher.launch(profileFileName()) },
                        enabled = state.slots.all { it.font != null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("导出")
                    }
                }
                if (status.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(status, modifier = Modifier.fillMaxWidth().padding(11.dp), fontSize = 10.sp)
                    }
                }
                if (errorMessage.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(7.dp))
                            Text(errorMessage, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val profile = pendingProfile
            if (profile != null) {
                TextButton(
                    onClick = {
                        val failure = applyStudioProfile(profile, actions)
                        errorMessage = failure.orEmpty()
                        if (failure == null) {
                            pendingProfile = null
                            status = "方案已载入"
                        }
                    },
                ) { Text("应用方案", fontWeight = FontWeight.Black) }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (pendingProfile != null) TextButton(onClick = { pendingProfile = null }) { Text("取消导入") }
        },
    )
}

private fun normalizedProfileAxes(axes: Map<String, Float>, weight: Int): Map<String, Float> = buildMap {
    axes.entries.take(16).forEach { (tag, value) ->
        if (tag.length == 4 && value.isFinite()) put(tag, if (tag == "wght") value.coerceIn(1f, 1000f) else value)
    }
    put("wght", (get("wght") ?: weight.toFloat()).coerceIn(1f, 1000f))
}

private fun readProfileText(contentResolver: android.content.ContentResolver, uri: Uri): String {
    contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                output.append(buffer, 0, count)
                if (output.length > STUDIO_PROFILE_MAX_CHARS) throw IllegalArgumentException("方案文件过大")
            }
            return output.toString()
        }
    }
    throw IllegalStateException("无法打开方案文件")
}

private fun profileFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "Ziyu-profile-$timestamp.json"
}

private val MixSlot.profileKey: String
    get() = when (this) {
        MixSlot.Cjk -> "cjk"
        MixSlot.Latin -> "latin"
        MixSlot.Digit -> "digit"
    }

private val MixSlot.profileLabel: String
    get() = when (this) {
        MixSlot.Cjk -> "中文"
        MixSlot.Latin -> "英文"
        MixSlot.Digit -> "数字"
    }
