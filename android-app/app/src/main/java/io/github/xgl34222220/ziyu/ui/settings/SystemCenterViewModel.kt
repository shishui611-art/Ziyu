package io.github.xgl34222220.ziyu.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.ziyu.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class HealthLevel {
    HEALTHY,
    WARNING,
    ERROR,
}

internal data class ModuleConflict(
    val moduleId: String,
    val moduleName: String,
    val target: String,
    val type: String,
    val fileCount: Int,
)

internal data class SystemHealthSnapshot(
    val loading: Boolean = true,
    val error: String = "",
    val modulePresent: Boolean = false,
    val pendingModulePresent: Boolean = false,
    val moduleVersion: String = "",
    val moduleVersionCode: Int = 0,
    val rootManager: String = "Unknown",
    val androidSdk: Int = 0,
    val activeFont: String = "default",
    val payloadFonts: Int = 0,
    val lockState: String = "idle",
    val engineState: String = "",
    val templateState: String = "",
    val alignmentState: String = "",
    val alignmentMode: String = "",
    val mountWarning: String = "",
    val selfMountState: String = "",
    val selfMountBackend: String = "",
    val mountEngine: String = "unknown",
    val cachePending: Boolean = false,
    val rebootRequired: Boolean = false,
    val recentWarnings: Int = 0,
    val recentErrors: Int = 0,
    val ignoredWarnings: Int = 0,
    val conflicts: List<ModuleConflict> = emptyList(),
) {
    private val errorNotices: List<String>
        get() {
            if (loading) return emptyList()
            return buildList {
                if (error.isNotBlank()) add(error)
                else if (!modulePresent) add("未检测到已安装的字域模块")
                if (alignmentState == "failed" || selfMountState == "failed") {
                    add("字体挂载运行时验证失败，请查看挂载详情中的后端和失败原因")
                }
                if (activeFont != "default" && payloadFonts <= 0) {
                    add("当前字体已选中，但模块字体负载未检测到")
                }
            }
        }

    private val warningNotices: List<String>
        get() {
            if (loading) return emptyList()
            return buildList {
                if (lockState == "stale") add("检测到失效字体切换锁，可在安全页一键清理")
                if (conflicts.isNotEmpty()) add("发现 ${conflicts.size} 个其它模块字体覆盖目标")
                if (recentErrors > 0) add("最近日志中有 $recentErrors 条错误记录")
                if (recentWarnings > 0) add("最近日志中有 $recentWarnings 条未忽略的警告")
                if (cachePending) add("设备字体缓存仍在等待完成")
                when (alignmentState) {
                    "partial" -> if (recentWarnings > 0) add(mountWarning.ifBlank { "字体已应用；槽位挂载与加载状态见提示日志" })
                    "", "verified", "ready", "ok", "passed", "failed" -> Unit
                    "not-applicable" -> if (activeFont != "default") add("当前自定义字体没有执行挂载")
                    "skipped" -> add("当前自定义字体的挂载已跳过，请检查排除设置")
                    "pending" -> add("本次启动的字体挂载尚未完成运行时验证")
                    else -> add("字体加载对齐状态需要确认：$alignmentState")
                }
            }
        }

    val attentionNotices: List<String>
        get() {
            if (loading) return emptyList()
            return buildList {
                addAll(errorNotices)
                addAll(warningNotices)
                if (rebootRequired) add("存在等待重启后生效的字体变更")
                if (selfMountState == "degraded") add("字域自挂载正在使用 OverlayFS + Bind 降级路径")
            }
        }

    val level: HealthLevel
        get() = when {
            errorNotices.isNotEmpty() -> HealthLevel.ERROR
            warningNotices.isNotEmpty() -> HealthLevel.WARNING
            else -> HealthLevel.HEALTHY
        }

    val summary: String
        get() = if (loading) "正在读取系统状态…" else when (level) {
            HealthLevel.HEALTHY -> "字体引擎状态正常"
            HealthLevel.WARNING -> "发现可处理的兼容性提醒"
            HealthLevel.ERROR -> "发现需要处理的问题"
        }
}

internal data class MaintenanceState(
    val busy: Boolean = false,
    val message: String = "",
    val error: String = "",
)

internal data class OnlineUpdateInfo(
    val loading: Boolean = false,
    val error: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val zipUrl: String = "",
    val changelogUrl: String = "",
    val sha256: String = "",
    val currentVersion: String = "",
    val currentVersionCode: Int = 0,
    val pendingVersion: String = "",
    val pendingVersionCode: Int = 0,
    val zipBytes: Long = 0,
) {
    val available: Boolean get() = !loading && error.isBlank() && versionCode > 0 && currentVersionCode > 0 && zipUrl.isNotBlank()
    val hasUpdate: Boolean get() = available && pendingVersion.isBlank() && isNewerZiyuVersion(version, versionCode, currentVersion, currentVersionCode)
}

internal fun isNewerZiyuVersion(candidate: String, candidateCode: Int, current: String, currentCode: Int): Boolean {
    val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)")
    fun parts(value: String): List<Int>? = pattern.find(value.trim())?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }
    val next = parts(candidate) ?: return false
    val now = parts(current) ?: return false
    for (index in 0..2) {
        if (next[index] != now[index]) return next[index] > now[index]
    }
    return candidateCode > currentCode
}

internal class SystemCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val healthScript = "/data/adb/modules/LuoShu/.luoshu-payload/system/bin/luoshu-health"
    private val updater = ModuleReleaseUpdater(context)
    var moduleUpdate by mutableStateOf(ModuleUpdateState())
        private set
    var updatePromptVisible by mutableStateOf(false)
        private set
    private var startupPromptAllowed = false

    var health by mutableStateOf(SystemHealthSnapshot())
        private set

    var maintenance by mutableStateOf(MaintenanceState())
        private set

    var updateInfo by mutableStateOf(OnlineUpdateInfo())
        private set

    private var updateJob: Job? = null
    private var healthJob: Job? = null

    fun enterApp() {
        if (moduleUpdate.busy || moduleUpdate.restartRequired) return
        startupPromptAllowed = true
        checkUpdate()
    }

    fun skipUpdate() {
        startupPromptAllowed = false
        updatePromptVisible = false
    }

    fun dismissUpdateResult() {
        if (!moduleUpdate.busy) moduleUpdate = moduleUpdate.copy(message = "", error = "")
    }

    fun installModuleUpdate() {
        if (moduleUpdate.busy || maintenance.busy || !updateInfo.hasUpdate) return
        val info = updateInfo
        skipUpdate()
        moduleUpdate = ModuleUpdateState(busy = true, message = "正在准备模块更新…")
        viewModelScope.launch {
            try {
                updater.install(info) { message ->
                    withContext(Dispatchers.Main) { moduleUpdate = moduleUpdate.copy(message = message) }
                }
                moduleUpdate = ModuleUpdateState(message = "模块更新已安装，完整重启后生效。内置 App 将随模块更新。", restartRequired = true)
                checkUpdate()
            } catch (cancelled: CancellationException) {
                moduleUpdate = ModuleUpdateState(error = "更新已中断，请检查模块管理器是否有待重启更新")
                throw cancelled
            } catch (error: Exception) {
                moduleUpdate = ModuleUpdateState(error = error.message ?: "模块更新失败")
            }
        }
    }

    fun refreshHealth() {
        if (healthJob?.isActive == true) return
        health = health.copy(loading = true, error = "")
        healthJob = viewModelScope.launch {
            val result = RootShell.exec(
                "script=${RootShell.quote(healthScript)}; [ -f \"${'$'}script\" ] || script=/data/adb/modules/LuoShu/system/bin/luoshu-health; sh \"${'$'}script\" report",
                timeoutMs = 25_000L,
            )
            health = if (result.code == 0) {
                runCatching { parseHealthReport(result.stdout) }
                    .getOrElse { error ->
                        SystemHealthSnapshot(loading = false, error = error.message ?: "体检结果解析失败")
                    }
            } else {
                SystemHealthSnapshot(
                    loading = false,
                    error = result.stderr.ifBlank { "无法运行字域系统体检" },
                )
            }
        }
    }

    fun ignoreCurrentWarnings() {
        if (maintenance.busy || moduleUpdate.busy) return
        maintenance = MaintenanceState(busy = true, message = "正在忽略当前警告…")
        viewModelScope.launch {
            val result = RootShell.exec("sh /data/adb/modules/LuoShu/common/app_bridge.sh log_review ignore-warnings", timeoutMs = 20_000L)
            val response = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            maintenance = if (result.code == 0 && response?.optString("status") == "ok") {
                MaintenanceState(message = response.optJSONObject("data")?.optString("message").orEmpty())
            } else MaintenanceState(error = response?.optString("message").orEmpty().ifBlank { result.stderr.ifBlank { "忽略警告失败" } })
            refreshHealth()
        }
    }

    fun clearStaleState() {
        if (maintenance.busy || moduleUpdate.busy) return
        maintenance = MaintenanceState(busy = true, message = "正在清理失效锁与残留 PID…")
        viewModelScope.launch {
            val result = RootShell.exec(
                "script=${RootShell.quote(healthScript)}; [ -f \"${'$'}script\" ] || script=/data/adb/modules/LuoShu/system/bin/luoshu-health; sh \"${'$'}script\" repair-stale",
                timeoutMs = 20_000L,
            )
            maintenance = if (result.code == 0 && result.stdout.lineSequence().any { it == "status=ok" }) {
                val changed = result.stdout.lineSequence()
                    .firstOrNull { it.startsWith("changed=") }
                    ?.substringAfter('=')
                    ?.toIntOrNull()
                    ?: 0
                MaintenanceState(message = if (changed > 0) "已清理 $changed 项失效状态" else "没有发现需要清理的残留状态")
            } else {
                MaintenanceState(error = result.stderr.ifBlank { "残留状态清理失败" })
            }
            refreshHealth()
        }
    }

    fun restoreDefault() {
        if (maintenance.busy || moduleUpdate.busy) return
        maintenance = MaintenanceState(busy = true, message = "正在准备恢复系统默认字体…")
        viewModelScope.launch {
            val result = RootShell.exec(
                "script=${RootShell.quote(healthScript)}; [ -f \"${'$'}script\" ] || script=/data/adb/modules/LuoShu/system/bin/luoshu-health; sh \"${'$'}script\" restore-default",
                timeoutMs = 120_000L,
            )
            val restored = result.code == 0 && result.stdout.lineSequence().any { line ->
                runCatching { JSONObject(line.trim()).optString("status") == "ok" }.getOrDefault(false)
            }
            maintenance = if (restored) {
                MaintenanceState(message = "系统字体恢复任务已完成，请按提示完整重启手机")
            } else {
                MaintenanceState(error = result.stderr.ifBlank { result.stdout.ifBlank { "恢复系统字体失败" } })
            }
            refreshHealth()
        }
    }

    fun checkUpdate() {
        if (updateJob?.isActive == true) return
        updatePromptVisible = false
        updateInfo = updateInfo.copy(loading = true, error = "")
        updateJob = viewModelScope.launch {
            try {
                updateInfo = updater.check()
                updatePromptVisible = startupPromptAllowed && updateInfo.hasUpdate
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateInfo = OnlineUpdateInfo(error = error.message ?: "检查模块更新失败")
                updatePromptVisible = false
            }
        }
    }

}

internal fun parseHealthReport(raw: String): SystemHealthSnapshot {
    val values = linkedMapOf<String, String>()
    val conflicts = mutableListOf<ModuleConflict>()
    raw.lineSequence().forEach { line ->
        when {
            line.startsWith("conflict=") -> {
                val parts = line.substringAfter('=').split('|', limit = 5)
                if (parts.size == 5) {
                    conflicts += ModuleConflict(
                        moduleId = parts[0],
                        moduleName = parts[1],
                        target = parts[2],
                        type = parts[3],
                        fileCount = parts[4].toIntOrNull() ?: 0,
                    )
                }
            }
            '=' in line -> values[line.substringBefore('=')] = line.substringAfter('=')
        }
    }
    if (values["healthVersion"] != "1") error("不支持的体检结果版本")
    return SystemHealthSnapshot(
        loading = false,
        modulePresent = values.bool("modulePresent"),
        pendingModulePresent = values.bool("pendingModulePresent"),
        moduleVersion = values["moduleVersion"].orEmpty(),
        moduleVersionCode = values.int("moduleVersionCode"),
        rootManager = values["rootManager"].orEmpty().ifBlank { "Unknown" },
        androidSdk = values.int("androidSdk"),
        activeFont = values["activeFont"].orEmpty().ifBlank { "default" },
        payloadFonts = values.int("payloadFonts"),
        lockState = values["lockState"].orEmpty().ifBlank { "idle" },
        engineState = values["engineState"].orEmpty(),
        templateState = values["templateState"].orEmpty(),
        alignmentState = values["alignmentState"].orEmpty(),
        alignmentMode = values["alignmentMode"].orEmpty(),
        mountWarning = values["mountWarning"].orEmpty(),
        selfMountState = values["selfMountState"].orEmpty(),
        selfMountBackend = values["selfMountBackend"].orEmpty(),
        mountEngine = values["mountEngine"].orEmpty().ifBlank { "unknown" },
        cachePending = values.bool("cachePending"),
        rebootRequired = values.bool("rebootRequired"),
        recentWarnings = values.int("recentWarnings"),
        recentErrors = values.int("recentErrors"),
        ignoredWarnings = values.int("ignoredWarnings"),
        conflicts = conflicts,
    )
}

private fun Map<String, String>.bool(key: String): Boolean = this[key].equals("true", ignoreCase = true)
private fun Map<String, String>.int(key: String): Int = this[key]?.toIntOrNull() ?: 0
