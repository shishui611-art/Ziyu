package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.ziyu.ModuleSnapshot

@Immutable
data class HomeUiState(
    val loading: Boolean = false,
    val version: String = "检测中…",
    val currentFont: String = "系统默认字体",
    val rootGranted: Boolean = false,
    val rootManager: String = "未授权",
    val moduleInstalled: Boolean = false,
    val mountEngine: String = "未知",
    val mountHealthy: Boolean = false,
    val taskRunning: Boolean = false,
    val taskTitle: String = "字体引擎等待中",
    val taskMessage: String = "暂无后台字体任务",
    val taskProgress: Int = 0,
    val rebootRequired: Boolean = false,
    val temporaryRootMode: Boolean = false,
    val error: String = "",
    val mountPreferences: String = "",
    val mountBackendPreference: String = "auto",
    val mountBackendRebootPrompt: String? = null,
    val mountPreferenceSaving: Boolean = false,
    val mountPreferenceLoaded: Boolean = false,
    val undoAvailable: Boolean = false,
)

@Immutable
data class HomeActions(
    val refresh: () -> Unit,
    val openFontLibrary: () -> Unit,
    val openFontStudio: () -> Unit,
    val openLogs: () -> Unit,
    val openSettings: () -> Unit = {},
    val restoreDefault: () -> Unit,
    val reboot: () -> Unit,
    val mountSettings: () -> Unit = {},
    val setMountBackend: (String) -> Unit = {},
    val dismissMountBackendRebootPrompt: () -> Unit = {},
    val cancelMountChange: () -> Unit = {},
    val undoApply: () -> Unit = {},
    val cancelTask: () -> Unit = {},
)

internal fun ModuleSnapshot.toHomeUiState(): HomeUiState {
    val running = taskState == "running" || taskState == "queued"
    return HomeUiState(
        loading = loading,
        version = version,
        currentFont = effectiveLabel,
        rootGranted = rootGranted,
        rootManager = rootManager,
        moduleInstalled = installed,
        mountEngine = mountEngine.replace("洛书", "字域"),
        mountHealthy = rootGranted && installed && !effectFailed && mountState != "failed" &&
            (mountState in setOf("mounted", "partial") || (activeFont in setOf("", "default") && mountState in setOf("idle", "not-applicable"))),
        taskRunning = running,
        taskTitle = when {
            running -> "字体任务执行中"
            fontEffectState == "live" -> "字体热挂载成功"
            fontEffectState == "live-partial" -> "字体热挂载成功（有提示）"
            fontEffectState == "partial" -> "字体应用成功（有提示）"
            rollbackPending || fontEffectState == "rollback-pending" -> "正在等待安全回退"
            effectFailed -> "字体未生效"
            mountState == "failed" -> "挂载验证未通过"
            mountState == "skipped" -> "当前自定义字体未挂载"
            installed && rootGranted -> "字体引擎已就绪"
            installed -> "模块已连接"
            else -> "正在等待模块连接"
        },
        taskMessage = when {
            fontEffectState in setOf("live", "live-partial") -> "新字体挂载已验证。部分已打开界面可能仍使用缓存，可重新打开应用或手动软重启。" +
                if (fontEffectState == "live-partial") " ${mountFailure.ifBlank { verificationReason }}" else ""
            fontEffectState == "partial" -> mountFailure.ifBlank { verificationReason }
            mountState == "failed" && (mountFailure.contains("rollback-failed") ||
                mountFailure.contains("rollback-verification-failed") || mountFailure.contains("cleanup") ||
                mountFailure.contains("backend-conflict")) -> "挂载失败且安全回滚尚未确认，请查看日志：$mountFailure"
            effectFailed -> effectFailureMessage
            mountState == "failed" -> "本次启动的挂载验证未通过，请查看日志：${mountFailure.ifBlank { verificationReason }}"
            mountState == "skipped" -> "当前字体没有执行挂载，请检查挂载排除设置"
            else -> taskMessage
        },
        taskProgress = taskProgress,
        rebootRequired = rebootRequired,
        temporaryRootMode = temporaryRootMode,
        error = error,
    )
}
