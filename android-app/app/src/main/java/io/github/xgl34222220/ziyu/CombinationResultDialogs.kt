package io.github.xgl34222220.ziyu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedTextField
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuModalBottomSheet
import io.github.xgl34222220.ziyu.ui.design.rememberZiyuModalBottomSheetState

/**
 * Preparation/reboot dialogs stay on the project design entry points so all three
 * UI styles render the same button hierarchy: filled primary, outlined secondary,
 * text-only dismiss. Actions are stacked full-width instead of raw Material text
 * buttons, which looked like plain labels under the Miuix and COUI styles.
 */
@Composable
internal fun CombinationResultDialogs(viewModel: ZiyuViewModel) {
    val restartOptionsSheet = rememberZiyuModalBottomSheetState(skipPartiallyExpanded = true)
    viewModel.fontCompatibilityNotice?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFontCompatibilityNotice,
            icon = { ZiyuIcon(Icons.Rounded.Warning, contentDescription = null, tint = LocalZiyuPaletteTokens.current.warning) },
            title = { ZiyuText("已切换兼容引擎") },
            text = { ZiyuText(message) },
            confirmButton = {
                ZiyuButton(onClick = viewModel::dismissFontCompatibilityNotice, modifier = Modifier.fillMaxWidth()) {
                    ZiyuText("知道了")
                }
            },
            dismissButton = null,
        )
    }
    if (viewModel.rebootError.isNotBlank()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRebootError,
            title = { ZiyuText("重启失败") },
            text = { ZiyuText(viewModel.rebootError) },
            confirmButton = { ZiyuButton(onClick = viewModel::dismissRebootError, modifier = Modifier.fillMaxWidth()) { ZiyuText("知道了") } },
        )
    }
    if (viewModel.rebootOptionsVisible) {
        ZiyuModalBottomSheet(
            onDismissRequest = viewModel::dismissRestartOptions,
            sheetState = restartOptionsSheet,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZiyuText("字体已应用，需要重启", style = MaterialTheme.typography.titleLarge)
                ZiyuText("选择重启方式，启动后会核验字体挂载结果。")
                ZiyuButton(
                    onClick = { viewModel.chooseRestartFromOptions("reboot") },
                    modifier = Modifier.fillMaxWidth(),
                ) { ZiyuText("普通重启") }
                ZiyuOutlinedButton(
                    onClick = { viewModel.chooseRestartFromOptions("soft") },
                    modifier = Modifier.fillMaxWidth(),
                ) { ZiyuText("内核软重启") }
                ZiyuTextButton(
                    onClick = viewModel::dismissRestartOptions,
                    modifier = Modifier.fillMaxWidth(),
                ) { ZiyuText("稍后") }
            }
        }
    }
    if (viewModel.rebootChoiceVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRebootChoice,
            title = { ZiyuText("确认${viewModel.rebootChoiceLabel}") },
            text = { ZiyuText(viewModel.rebootChoiceMessage) },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZiyuButton(
                        onClick = viewModel::confirmReboot,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText(if(viewModel.rebootChoiceFinal) viewModel.rebootChoiceLabel else "继续") }
                    ZiyuTextButton(
                        onClick = viewModel::dismissRebootChoice,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("取消") }
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
        var compareRestart by remember(result.taskId) { mutableStateOf<Boolean?>(null) }
        val current = comparisonSource(viewModel.snapshot, viewModel.fonts, font)
        if (compareRestart != null && current != null) {
            io.github.xgl34222220.ziyu.ui.library.FontABCompareScreen(current, font,
                onDismiss = { compareRestart = null },
                onConfirm = {
                    val restart = compareRestart ?: return@FontABCompareScreen
                    compareRestart = null
                    viewModel.applyPreparedCombination(restart)
                })
        } else {
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
                        onClick = { if (current != null) compareRestart = true else viewModel.applyPreparedCombination(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("应用并重启") }
                    ZiyuOutlinedButton(
                        enabled = ready,
                        onClick = { if (current != null) compareRestart = false else viewModel.applyPreparedCombination(false) },
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
