package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            text = { Text("已安装模块：${model.updateInfo.currentVersion}\n将下载 GitHub 正式版模块 ZIP，校验后通过当前 Root 管理器安装。内置 App 随模块更新，完整重启后生效。") },
            dismissButton = { TextButton(model::skipUpdate) { Text("暂时跳过") } },
            confirmButton = { TextButton(model::installModuleUpdate) { Text("更新模块") } },
        )
    }
}
