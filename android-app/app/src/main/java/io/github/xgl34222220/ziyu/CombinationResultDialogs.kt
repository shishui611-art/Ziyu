package io.github.xgl34222220.ziyu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CombinationResultDialogs(viewModel: ZiyuViewModel) {
    if (viewModel.fontCacheRefreshPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFontCacheRefresh,
            title = { Text("系统界面仍使用旧字体") },
            text = { Text(if (viewModel.fontCacheSoftRebootAvailable)
                "新字体已热挂载，但状态栏、下拉面板和通知栏仍保留旧字体缓存。是否通过 KernelSU 软重启刷新？软重启会中断当前应用，完成后返回查看结果。也可以稍后处理。"
                else "新字体已热挂载，但系统界面仍保留旧字体缓存，需要重启刷新。当前未确认支持 KernelSU 软重启，可稍后手动重启。") },
            confirmButton = {
                if (viewModel.fontCacheSoftRebootAvailable) {
                    TextButton(onClick = viewModel::confirmFontCacheRefresh) { Text("软重启刷新") }
                } else {
                    TextButton(onClick = viewModel::dismissFontCacheRefresh) { Text("知道了") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissFontCacheRefresh) { Text("稍后处理") } },
        )
    }
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
