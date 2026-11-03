package io.github.xgl34222220.ziyu.ui.settings

import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun ModuleUpdateDialogRoute(model: SystemCenterViewModel, fontsBusy: Boolean, reboot: () -> Unit) {
    val task = model.moduleUpdate
    when {
        task.busy -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在更新模块") },
            text = { Text(task.message) },
            confirmButton = {},
        )
        task.error.isNotBlank() || task.message.isNotBlank() -> AlertDialog(
            onDismissRequest = model::dismissUpdateResult,
            title = { Text(if (task.error.isNotBlank()) "模块更新失败" else "模块更新完成") },
            text = { Text(task.error.ifBlank { task.message }) },
            dismissButton = { TextButton(model::dismissUpdateResult) { Text(if (task.restartRequired) "稍后重启" else "关闭") } },
            confirmButton = {
                if (task.restartRequired) TextButton(reboot) { Text("完整重启") }
            },
        )
        model.updatePromptVisible && !fontsBusy && !model.maintenance.busy -> AlertDialog(
            onDismissRequest = model::skipUpdate,
            title = { Text("发现正式模块更新 ${model.updateInfo.version}") },
            text = { Text("当前版本：${model.updateInfo.currentVersion}\n更新 App 与模块。完成后请完整重启。") },
            dismissButton = { TextButton(model::skipUpdate) { Text("暂时跳过") } },
            confirmButton = { TextButton(model::installModuleUpdate) { Text("更新模块") } },
        )
    }
}
