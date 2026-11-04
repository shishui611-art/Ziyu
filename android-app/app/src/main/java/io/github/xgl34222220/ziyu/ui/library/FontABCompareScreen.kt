package io.github.xgl34222220.ziyu.ui.library

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
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.NativeCurrentFontPreview
import io.github.xgl34222220.ziyu.ui.design.*
import kotlinx.coroutines.delay

@Composable
internal fun FontABCompareScreen(current: FontItem, target: FontItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    val revision = remember(current.id, target.id) { java.util.UUID.randomUUID().toString() }
    var targetReady by remember(revision) { mutableStateOf(false) }
    var weight by remember { mutableFloatStateOf(400f) }
    var previewWeight by remember { mutableFloatStateOf(400f) }
    var editing by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(weight) { delay(100); previewWeight = weight }
    var text by remember { mutableStateOf(context.getSharedPreferences("font-preview-v1", 0)
        .getString("text", "").orEmpty().ifBlank { "山海有相逢\nAa 0123" }) }

    @Composable
    fun Preview(font: FontItem, isCurrent: Boolean, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZiyuText(if (isCurrent) "当前" else "新字体", style = MaterialTheme.typography.labelMedium)
            ZiyuText(font.name, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val previewModifier = Modifier.fillMaxWidth().height((100f * fontScale.coerceAtLeast(1f)).dp)
            if (isCurrent) NativeCurrentFontPreview(font.id, text, previewWeight, previewModifier, textSizeSp = 22f)
            else NativeFontPreview(font, text, axes = mapOf("wght" to previewWeight),
                modifier = previewModifier, textSizeSp = 22f, maxLines = 6,
                refreshKey = revision, onReady = { targetReady = it })
        }
    }

    ZiyuAlertDialog(onDismissRequest = onDismiss,
        title = { ZiyuText("字体对比", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Narrow dialogs and large system text need full-width samples.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 360.dp && fontScale <= 1.3f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Preview(current, true, Modifier.weight(1f))
                            Preview(target, false, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Preview(current, true, Modifier.fillMaxWidth())
                            Preview(target, false, Modifier.fillMaxWidth())
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ZiyuTextButton(onClick = { editing = !editing }) {
                        ZiyuText(if (editing) "收起设置" else "调整预览")
                    }
                    ZiyuTextButton(onClick = { details = !details }) {
                        ZiyuText(if (details) "收起名称" else "完整名称")
                    }
                }
                if (editing) {
                    ZiyuOutlinedTextField(value = text,
                        onValueChange = { if (it.codePointCount(0, it.length) <= 200) text = it },
                        label = { ZiyuText("预览文字") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    ZiyuText("预览字重 ${weight.toInt()}", style = MaterialTheme.typography.bodySmall)
                    ZiyuSlider(value = weight, onValueChange = { weight = it }, valueRange = 100f..900f)
                    ZiyuText("仅影响预览", style = MaterialTheme.typography.bodySmall)
                }
                if (details) {
                    ZiyuText("当前：${current.name}\n新字体：${target.name}", style = MaterialTheme.typography.bodySmall)
                }
                if (!target.supportsCjk) ZiyuText("部分中文可能沿用系统字体。", style = MaterialTheme.typography.bodySmall)
            }
        }, dismissButton = { ZiyuTextButton(onClick = onDismiss) { ZiyuText("取消") } },
        confirmButton = { ZiyuButton(enabled = targetReady, onClick = onConfirm) { ZiyuText("应用") } })
}
