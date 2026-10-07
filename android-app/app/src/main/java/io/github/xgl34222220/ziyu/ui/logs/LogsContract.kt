package io.github.xgl34222220.ziyu.ui.logs

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.ziyu.ZiyuViewModel
import io.github.xgl34222220.ziyu.NativeImportPhase
import io.github.xgl34222220.ziyu.NativeImportState

@Immutable
internal data class LogsUiState(
    val content: String = "尚未读取日志",
    val lineCount: Int = 0,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val tasks: List<TaskCenterItem> = emptyList(),
    val activeTaskCount: Int = 0,
    val completedTaskCount: Int = 0,
    val failedTaskCount: Int = 0,
    val rebootRequired: Boolean = false,
    val temporaryRootMode: Boolean = false,
    val logsViewed: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoRebootRequired: Boolean = false,
    val actionMessage: String = "",
)

internal data class LogsActions(
    val refresh: () -> Unit,
    val cancelTask: (String, String) -> Unit = { _, _ -> },
    val undoApply: () -> Unit = {},
    val markViewed: () -> Unit = {},
    val clearLogs: () -> Unit = {},
)

internal fun isWarningLogRecord(line: String): Boolean =
    line.contains("warn", ignoreCase = true) || line.contains("警告")

internal fun isErrorLogRecord(line: String): Boolean =
    !isWarningLogRecord(line) && (
        line.contains("error", ignoreCase = true) ||
            line.contains("failed", ignoreCase = true) ||
            line.contains("失败") ||
            line.contains("错误")
        )

internal fun ZiyuViewModel.toLogsUiState(): LogsUiState {
    val normalized = logs.ifBlank { "尚未读取日志" }
    val lines = normalized.lineSequence().toList()
    // The backend marker can outlive a failed boot verification. A completed
    // task's old wording never proves that another reboot is still required.
    val currentRebootRequired = (rebootRequired || snapshot.rebootRequired) &&
        !snapshot.effectFailed && !snapshot.rollbackPending &&
        snapshot.verificationState != "failed" && snapshot.mountState != "failed"
    val current = buildList {
        if (fontLoading || fontRefreshing) {
            add(
                TaskCenterItem(
                    id = "current-font-scan",
                    kind = TaskKind.SCAN,
                    phase = TaskPhase.RUNNING,
                    title = if (fontRefreshing) "正在更新字体索引" else "正在扫描字体库",
                    message = if (fontRefreshing) "正在检查字体目录变化并更新本地缓存" else "正在读取已导入字体与可用字重",
                    progress = -1,
                    timeLabel = "当前",
                    current = true,
                ),
            )
        } else if (fontError.isNotBlank()) {
            add(
                TaskCenterItem(
                    id = "current-font-scan-error",
                    kind = TaskKind.SCAN,
                    phase = TaskPhase.FAILED,
                    title = "字体扫描失败",
                    message = fontError,
                    progress = 100,
                    timeLabel = "最近",
                    current = true,
                ),
            )
        }

        if (snapshot.effectFailed) {
            add(
                TaskCenterItem(
                    id = "effective-font-failure",
                    kind = TaskKind.APPLY,
                    phase = TaskPhase.FAILED,
                    title = "字体未生效",
                    message = snapshot.effectFailureMessage,
                    progress = 100,
                    timeLabel = "当前",
                    current = true,
                ),
            )
        }

        val snapshotPhase = if (snapshot.taskType == "switch" &&
            snapshot.taskState in setOf("success", "prepared", "pending-reboot")
        ) {
            if (currentRebootRequired) TaskPhase.WAITING_REBOOT else TaskPhase.SUCCESS
        } else taskPhaseFor("", snapshot.taskMessage, snapshot.taskState)
        if (!snapshot.effectFailed && snapshot.taskState != "idle" && snapshot.taskType != "none") {
            val kind = taskKindFor(snapshot.taskMessage, snapshot.taskType)
            add(
                TaskCenterItem(
                    id = snapshot.taskId.ifBlank { "current-${snapshot.taskType}" },
                    kind = kind,
                    phase = snapshotPhase,
                    title = taskTitle(kind, snapshotPhase),
                    message = if (snapshot.taskType == "switch" && snapshotPhase == TaskPhase.SUCCESS) {
                        when {
                            snapshot.fontEffectState == "verified" && snapshot.effectiveFont == snapshot.activeFont ->
                                "字体应用已完成，本次开机验证通过"
                            snapshot.fontEffectState == "system" -> "已恢复系统默认字体"
                            else -> "字体应用任务已完成；当前效果请查看首页挂载与验证状态"
                        }
                    } else taskDisplayMessage(snapshot.taskMessage),
                    progress = snapshot.taskProgress,
                    timeLabel = "当前",
                    current = true,
                ),
            )
        }

        if (mixState.busy && snapshot.taskType != "mix") {
            add(
                TaskCenterItem(
                    id = mixState.taskId.ifBlank { "current-mix" },
                    kind = TaskKind.MIX,
                    phase = taskPhaseFor("", mixState.message, mixState.taskState),
                    title = "字体组合进行中",
                    message = mixState.message,
                    progress = mixState.progress,
                    timeLabel = "当前",
                    current = true,
                ),
            )
        }

        if (operationBusy && snapshot.taskType != "switch") {
            val kind = taskKindFor(operationMessage)
            add(
                TaskCenterItem(
                    id = "current-operation",
                    kind = kind,
                    phase = TaskPhase.RUNNING,
                    title = taskTitle(kind, TaskPhase.RUNNING),
                    message = operationMessage.ifBlank { "正在处理字体任务" },
                    progress = -1,
                    timeLabel = "当前",
                    current = true,
                ),
            )
        } else if (!operationBusy && operationMessage.isNotBlank()) {
            val kind = taskKindFor(operationMessage)
            val observedPhase = taskPhaseFor("", operationMessage)
            val phase = when {
                observedPhase == TaskPhase.WAITING_REBOOT && kind in setOf(TaskKind.APPLY, TaskKind.RESTORE) ->
                    if (currentRebootRequired) TaskPhase.WAITING_REBOOT else TaskPhase.SUCCESS
                observedPhase == TaskPhase.RUNNING || observedPhase == TaskPhase.QUEUED -> TaskPhase.INFO
                else -> observedPhase
            }
            add(
                TaskCenterItem(
                    id = "latest-operation-${operationMessage.hashCode()}",
                    kind = kind,
                    phase = phase,
                    title = taskTitle(kind, phase),
                    message = if (observedPhase == TaskPhase.WAITING_REBOOT && !currentRebootRequired) {
                        "字体任务已完成；当前效果请查看首页挂载与验证状态"
                    } else taskDisplayMessage(operationMessage),
                    progress = if (phase == TaskPhase.INFO) -1 else 100,
                    timeLabel = "最近",
                    current = true,
                ),
            )
        }

        if (currentRebootRequired) {
            add(
                TaskCenterItem(
                    id = "waiting-reboot",
                    kind = TaskKind.REBOOT,
                    phase = TaskPhase.WAITING_REBOOT,
                    title = "等待完整重启",
                    message = "字体文件已经准备完成，完整重启手机后全局生效",
                    progress = 100,
                    timeLabel = "待处理",
                    current = true,
                ),
            )
        }
    }
    val activeKinds = current.asSequence()
        .filter { it.active }
        .map { it.kind }
        .toSet()
    val history = parseTaskLogItems(normalized).filterNot { item ->
        item.active && item.kind in activeKinds
    }
    val tasks = mergeTaskItems(current, history)

    return LogsUiState(
        content = normalized,
        lineCount = lines.count { it.isNotBlank() },
        errorCount = lines.count(::isErrorLogRecord),
        warningCount = lines.count(::isWarningLogRecord),
        tasks = tasks,
        activeTaskCount = tasks.count { it.active },
        completedTaskCount = tasks.count { it.completed },
        failedTaskCount = tasks.count { it.phase == TaskPhase.FAILED },
        rebootRequired = currentRebootRequired,
        temporaryRootMode = snapshot.temporaryRootMode,
        logsViewed = logsViewed,
        undoAvailable = undoAvailable,
        undoRebootRequired = undoRebootRequired,
        actionMessage = operationMessage,
    )
}

internal fun LogsUiState.withNativeImport(state: NativeImportState): LogsUiState {
    if (state.phase == NativeImportPhase.IDLE) return this
    val phase = when (state.phase) {
        NativeImportPhase.IDLE -> TaskPhase.INFO
        NativeImportPhase.QUEUED -> TaskPhase.QUEUED
        NativeImportPhase.RUNNING -> TaskPhase.RUNNING
        NativeImportPhase.PAUSED -> TaskPhase.INFO
        NativeImportPhase.SUCCESS -> TaskPhase.SUCCESS
        NativeImportPhase.FAILED -> TaskPhase.FAILED
        NativeImportPhase.CANCELLED -> TaskPhase.CANCELLED
    }
    val title = when (state.phase) {
        NativeImportPhase.PAUSED -> "字体导入已暂停"
        NativeImportPhase.CANCELLED -> "字体导入已取消"
        else -> taskTitle(TaskKind.IMPORT, phase)
    }
    val item = TaskCenterItem(
        id = state.taskId.ifBlank { "native-import" },
        kind = TaskKind.IMPORT,
        phase = phase,
        title = title,
        message = state.message,
        progress = state.progress,
        timeLabel = when {
            state.paused -> "已暂停"
            state.busy -> "当前"
            else -> "最近"
        },
        current = state.busy || state.paused,
    )
    val merged = mergeTaskItems(listOf(item), tasks)
    return copy(
        tasks = merged,
        activeTaskCount = merged.count { it.active },
        completedTaskCount = merged.count { it.completed },
        failedTaskCount = merged.count { it.phase == TaskPhase.FAILED },
    )
}
