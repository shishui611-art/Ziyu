package io.github.xgl34222220.ziyu.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.ui.design.*
import kotlinx.coroutines.delay

@Composable
internal fun FontABCompareScreen(current: FontItem, target: FontItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    var weight by remember { mutableFloatStateOf(400f) }
    var previewWeight by remember { mutableFloatStateOf(400f) }
    LaunchedEffect(weight) { delay(100); previewWeight = weight }
    var text by remember { mutableStateOf(context.getSharedPreferences("font-preview-v1", 0)
        .getString("text", "").orEmpty().ifBlank { "山海有相逢\nAa 0123456789\n字体切换对比" }) }
    ZiyuAlertDialog(onDismissRequest = onDismiss, title = { ZiyuText("应用前对比") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuText("左侧按当前已核验生效的字体来源预览。两侧使用相同文字、字号和预览字重；应用中的缓存、自带字体或固定行框效果仍需实际查看。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("当前字体" to current, "目标字体" to target).forEach { (label, font) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZiyuText(label)
                            ZiyuText(font.name, maxLines = 3)
                            NativeFontPreview(font, text, axes = mapOf("wght" to previewWeight),
                                modifier = Modifier.fillMaxWidth().height(170.dp), textSizeSp = 22f, maxLines = 6)
                        }
                    }
                }
                ZiyuOutlinedTextField(value = text, onValueChange = { if (it.codePointCount(0, it.length) <= 200) text = it },
                    label = { ZiyuText("对比文字") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                ZiyuText("预览字重 ${weight.toInt()}（静态字体按已有字重显示）")
                ZiyuSlider(value = weight, onValueChange = { weight = it }, valueRange = 100f..900f)
                if (!target.supportsCjk) ZiyuText("目标字体中文覆盖有限，应用后中文可能继续使用原厂回退字体。")
                ZiyuText("此滑块仅用于预览，确认后沿用现有应用流程和系统字重设置。")
            }
        }, dismissButton = { ZiyuTextButton(onClick = onDismiss) { ZiyuText("取消") } },
        confirmButton = { ZiyuButton(onClick = onConfirm) { ZiyuText("确认应用") } })
}
