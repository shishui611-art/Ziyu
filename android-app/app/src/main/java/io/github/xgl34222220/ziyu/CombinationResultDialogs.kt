package io.github.xgl34222220.ziyu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CombinationResultDialogs(viewModel: ZiyuViewModel) {
    viewModel.preparedCombination?.let { result ->
        var sample by remember(result.taskId) { mutableStateOf("字域 · 让文字更悦目\nZiyu Typography 0123456789") }
        var ready by remember(result.taskId) { mutableStateOf(false) }
        // Export by the published family ID; this is the generated font, not three independent previews.
        val font = viewModel.fonts.firstOrNull { it.id == result.fontId } ?: FontItem(
            result.fontId, result.name, "TTF", "", "", false, true, "", listOf("regular"),
        )
        AlertDialog(
            onDismissRequest = viewModel::dismissPreparedCombination,
            title = { Text("「${result.name}」已生成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("组合已保存到字体库。预览实际生成的中文、英文和数字后，再决定是否应用。")
                    NativeFontPreview(font, sample, maxLines = 4, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), onReady = { ready = it })
                    OutlinedTextField(sample, { sample = it.take(200) }, label = { Text("自定义样例") }, maxLines = 3)
                }
            },
            confirmButton = {
                Column {
                    TextButton(enabled = ready, onClick = { viewModel.applyPreparedCombination(true) }) { Text("应用并重启") }
                    TextButton(enabled = ready, onClick = { viewModel.applyPreparedCombination(false) }) { Text("应用，稍后重启") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissPreparedCombination) { Text("仅保存") } },
        )
    }
    if (viewModel.restartCountdown > 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${viewModel.restartCountdown} 秒后完整重启") },
            text = { Text("字体已准备完成。现在改变主意，可以取消重启并恢复上一次字体。重启后也可在首页回退。") },
            confirmButton = { TextButton(onClick = viewModel::undoFontApplication) { Text("取消并回退") } },
        )
    }
}
