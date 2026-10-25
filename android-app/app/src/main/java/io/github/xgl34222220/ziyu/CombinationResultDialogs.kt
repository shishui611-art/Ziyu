package io.github.xgl34222220.ziyu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedTextField
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton

/**
 * Preparation/reboot dialogs stay on the project design entry points so all three
 * UI styles render the same button hierarchy: filled primary, outlined secondary,
 * text-only dismiss. Actions are stacked full-width instead of raw Material text
 * buttons, which looked like plain labels under the Miuix and COUI styles.
 */
@Composable
internal fun CombinationResultDialogs(viewModel: ZiyuViewModel) {
    if (viewModel.rebootChoiceVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRebootChoice,
            title = { ZiyuText("重启应用字体") },
            text = { ZiyuText(viewModel.rebootChoiceMessage) },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZiyuButton(
                        onClick = { viewModel.confirmReboot(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("完整重启") }
                    if (viewModel.softRebootSupported) {
                        ZiyuOutlinedButton(
                            onClick = { viewModel.confirmReboot(true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { ZiyuText("KernelSU 软重启") }
                    }
                    ZiyuTextButton(
                        onClick = viewModel::dismissRebootChoice,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("稍后") }
                }
            },
            dismissButton = null,
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
            title = { ZiyuText("「${result.name}」已生成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZiyuText("组合已保存到字体库。预览实际生成的中文、英文和数字后，再决定是否应用。")
                    NativeFontPreview(font, sample, maxLines = 4, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), onReady = { ready = it })
                    ZiyuOutlinedTextField(
                        value = sample,
                        onValueChange = { sample = it.take(200) },
                        label = { ZiyuText("自定义样例") },
                        maxLines = 3,
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZiyuButton(
                        enabled = ready,
                        onClick = { viewModel.applyPreparedCombination(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("应用并重启") }
                    ZiyuOutlinedButton(
                        enabled = ready,
                        onClick = { viewModel.applyPreparedCombination(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("应用，稍后重启") }
                    ZiyuTextButton(
                        onClick = viewModel::dismissPreparedCombination,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("仅保存") }
                }
            },
            dismissButton = null,
        )
    }
    if (viewModel.restartCountdown > 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { ZiyuText("${viewModel.restartCountdown} 秒后完整重启") },
            text = { ZiyuText("字体已准备完成。现在改变主意，可以取消重启并恢复上一次字体。重启后也可在首页回退。") },
            confirmButton = {
                ZiyuButton(
                    onClick = viewModel::undoFontApplication,
                    modifier = Modifier.fillMaxWidth(),
                ) { ZiyuText("取消并回退") }
            },
            dismissButton = null,
        )
    }
}
