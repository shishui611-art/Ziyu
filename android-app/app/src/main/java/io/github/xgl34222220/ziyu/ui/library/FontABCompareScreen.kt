package io.github.xgl34222220.ziyu.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    LaunchedEffect(weight) { delay(100); previewWeight = weight }
    var text by remember { mutableStateOf(context.getSharedPreferences("font-preview-v1", 0)
        .getString("text", "").orEmpty().ifBlank { "山海有相逢\nAa 0123456789\n字体切换对比" }) }
    ZiyuAlertDialog(onDismissRequest = onDismiss, title = { ZiyuText("应用前对比") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuText("比较当前字体与目标字体，确认后应用。实际显示可能因应用而异。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("当前字体" to current, "目标字体" to target).forEach { (label, font) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZiyuText(label)
                            ZiyuText(font.name, maxLines = 3)
                            if (label == "当前字体") NativeCurrentFontPreview(font.id, text, previewWeight,
                                Modifier.fillMaxWidth().height(170.dp))
                            else NativeFontPreview(font, text, axes = mapOf("wght" to previewWeight),
                                modifier = Modifier.fillMaxWidth().height(170.dp), textSizeSp = 22f, maxLines = 6,
                                refreshKey = revision, onReady = { targetReady = it })
                        }
                    }
                }
                ZiyuOutlinedTextField(value = text, onValueChange = { if (it.codePointCount(0, it.length) <= 200) text = it },
                    label = { ZiyuText("对比文字") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                ZiyuText("预览字重 ${weight.toInt()}（静态字体按已有字重显示）")
                ZiyuSlider(value = weight, onValueChange = { weight = it }, valueRange = 100f..900f)
                if (!target.supportsCjk) ZiyuText("目标字体中文覆盖有限，应用后中文可能继续使用原厂回退字体。")
                ZiyuText("预览字重不会更改系统设置。")
            }
        }, dismissButton = { ZiyuTextButton(onClick = onDismiss) { ZiyuText("取消") } },
        confirmButton = { ZiyuButton(enabled = targetReady, onClick = onConfirm) { ZiyuText("确认应用") } })
}
