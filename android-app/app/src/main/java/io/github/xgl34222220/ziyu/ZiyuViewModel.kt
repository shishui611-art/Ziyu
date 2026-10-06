package io.github.xgl34222220.ziyu

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class ModuleSnapshot(
    val loading: Boolean = true,
    val rootGranted: Boolean = false,
    val installed: Boolean = false,
    val version: String = "检测中…",
    val versionCode: Int = 0,
    val activeFont: String = "default",
    val activeFontName: String = "",
    val effectiveFont: String = "unknown",
    val fontEffectState: String = "unknown",
    val verificationState: String = "unknown",
    val verificationGrade: String = "PENDING",
    val verificationMode: String = "unknown",
    val verificationReason: String = "",
    val mountState: String = "unknown",
    val mountFailure: String = "",
    val cutoverState: String = "idle",
    val cutoverDecision: String = "none",
    val rollbackState: String = "none",
    val rollbackPending: Boolean = false,
    val rollbackTargetFont: String = "",
    val rollbackTargetMode: String = "",
    val taskType: String = "none",
    val taskId: String = "",
    val taskState: String = "idle",
    val taskMessage: String = "暂无后台任务",
    val taskProgress: Int = 0,
    val rebootRequired: Boolean = false,
    val rootManager: String = "未知",
    val mountEngine: String = "未知",
    val error: String = "",
) {
    val activeLabel: String
        get() = when (activeFont) {
            "mix" -> "完整复合字体"
            "default", "" -> "系统默认字体"
            else -> activeFontName.ifBlank { activeFont }
        }

    val rollbackTargetLabel: String
        get() = when (rollbackTargetFont) {
            "", "unknown" -> "上一份可用字体"
            "default" -> "系统默认字体"
            else -> rollbackTargetFont
        }

    val effectiveLabel: String
        get() = when {
            rollbackPending || fontEffectState == "rollback-pending" ->
                "${activeLabel}（验证失败，待重启恢复 ${rollbackTargetLabel}）"
            activeFont in setOf("", "default") || fontEffectState == "system" -> "系统默认字体"
            fontEffectState == "verified" && effectiveFont == activeFont -> activeLabel
            fontEffectState == "failed" && verificationMode.startsWith("universal") ->
                "${activeLabel}（运行验证失败）"
            fontEffectState == "failed" && effectiveFont == "unknown" ->
                "${activeLabel}（挂载失败，回滚待检查）"
            fontEffectState == "failed" -> "系统默认字体（${activeLabel}未生效）"
            fontEffectState == "pending-reboot" -> "${activeLabel}（等待完整重启）"
            else -> "${activeLabel}（已准备，待本次启动验证）"
        }

    val effectFailed: Boolean
        get() = activeFont !in setOf("", "default") &&
            (fontEffectState == "failed" || fontEffectState == "rollback-pending" || rollbackPending)

    val effectFailureMessage: String
        get() = when {
            rollbackPending || fontEffectState == "rollback-pending" ->
                "通用字体运行验证失败，已准备安全回退到 ${rollbackTargetLabel}；完整重启后恢复"
            fontEffectState == "failed" && verificationMode.startsWith("universal") ->
                "通用字体运行验证失败，当前启动的实际字体效果无法安全确认；请查看运行日志并重新选择字体或恢复系统字体"
            fontEffectState == "failed" && effectiveFont == "unknown" ->
                "本次挂载失败且安全回滚尚未确认，实际字体效果无法安全确认；请查看运行日志"
            mountFailure.isNotBlank() -> "自挂载失败（${mountFailure}），已安全回滚到系统字体"
            else -> when (verificationReason) {
                "self-mount-not-visible" -> "开机挂载未完整生效，系统已安全使用默认字体"
                "self-mount-failed" -> "本次启动的原子挂载事务失败，已完整回滚到系统字体"
                "self-mount-invalid-backend" -> "检测到不受支持的挂载后端，字域没有提交字体负载"
                "self-mount-manifest-missing" -> "本次启动的字体与配置挂载清单缺失，已回滚到系统字体"
                "aligned-manifest-missing" -> "字体负载清单缺失，系统已安全使用默认字体"
                "dynamic-config-overridden" -> "系统动态字体配置覆盖了字域负载，已安全回到系统字体"
                "dynamic-config-mount-failed" -> "系统动态字体配置挂载失败，已完整回滚到系统字体"
                else -> "开机字体验证失败，系统已安全使用默认字体"
            }
        }
}

internal data class FontItem(
    val id: String,
    val name: String,
    val format: String,
    val size: String,
    val date: String,
    val variable: Boolean,
    val valid: Boolean,
    val error: String,
    val weights: List<String>,
    val supportsCjk: Boolean = true,
    val combination: Boolean = false,
) {
    val weightLabel: String
        get() = when {
            variable -> "可变字体"
            weights.isEmpty() -> "单字重"
            else -> weights.joinToString(" · ") { role ->
                when (role) {
                    "thin" -> "极细"
                    "extralight" -> "超细"
                    "light" -> "细体"
                    "regular" -> "常规"
                    "medium" -> "中等"
                    "semibold" -> "半粗"
                    "bold" -> "粗体"
                    "extrabold" -> "特粗"
                    "black" -> "黑体"
                    else -> role
                }
            }
        }
}

internal enum class MixSlot { Cjk, Latin, Digit }

internal data class MixState(
    val loading: Boolean = false,
    val cjk: String = "",
    val latin: String = "",
    val digit: String = "",
    val cjkWeight: Int = 400,
    val latinWeight: Int = 400,
    val digitWeight: Int = 400,
    val cjkAxes: Map<String, Float> = mapOf("wght" to 400f),
    val latinAxes: Map<String, Float> = mapOf("wght" to 400f),
    val digitAxes: Map<String, Float> = mapOf("wght" to 400f),
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val taskId: String = "",
    val taskState: String = "idle",
    val message: String = "请选择中文、英文和数字字体",
    val progress: Int = 0,
    val error: String = "",
)

private data class FontFingerprint(
    val value: String,
    val currentFont: String,
)

internal class ZiyuViewModel(application: Application) : AndroidViewModel(application) {
    private val bridge = "/data/adb/modules/LuoShu/common/app_bridge.sh"
    private val fingerprintBridge = "/data/adb/modules/LuoShu/common/font_library_cache.sh"
    private val fontIndexStore = FontIndexStore(application)
    private val completionPrefs = application.getSharedPreferences("combination-completion", 0)
    private var restartJob: Job? = null
    private var restoredCombinationTask = ""
    private val mountPreferenceMutex = Mutex()
    var restartCountdown by mutableStateOf(0)
        private set
    var preparedCombination by mutableStateOf<PreparedCombination?>(null)
        private set
    var mountPreferences by mutableStateOf("尚未读取挂载状态")
        private set
    var logsViewed by mutableStateOf(false)
        private set
    var undoAvailable by mutableStateOf(false)
        private set
    var undoRebootRequired by mutableStateOf(false)
        private set

    private fun refreshActionStatus() {
        viewModelScope.launch {
            val result = RootShell.exec("sh ${RootShell.quote(bridge)} action_status", timeoutMs = 10_000L)
            runCatching {
                val root = firstJson(result.stdout)
                val data = root.optJSONObject("data") ?: root
                undoAvailable = data.optBoolean("undoAvailable")
                undoRebootRequired = data.optBoolean("undoRebootRequired")
            }
        }
    }

    fun dismissPreparedCombination() {
        preparedCombination?.let { completionPrefs.edit().putString("acknowledged", it.taskId).apply() }
        preparedCombination = null
    }

    fun applyPreparedCombination(restart: Boolean) {
        val result = preparedCombination ?: return
        dismissPreparedCombination()
        startApplyFont(result.fontId, restart)
    }

    fun cancelTask(kind: String, taskId: String) {
        if (kind !in setOf("mix", "switch") || taskId.isBlank()) return
        viewModelScope.launch {
            operationMessage = "正在终止任务并清理本次临时状态…"
            val result = RootShell.exec("sh ${RootShell.quote(bridge)} action_cancel ${RootShell.quote(kind)} ${RootShell.quote(taskId)}", timeoutMs = 40_000L)
            operationMessage = runCatching { val root = firstJson(result.stdout); (root.optJSONObject("data") ?: root).optString("message") }.getOrNull()
                .orEmpty().ifBlank { result.stderr.ifBlank { "取消请求已提交" } }
            refresh()
        }
    }

    fun cancelCurrentTask() {
        if (mixState.busy) cancelTask("mix", mixState.taskId)
        else cancelTask("switch", snapshot.taskId)
    }

    fun undoFontApplication() {
        restartJob?.cancel()
        restartCountdown = 0
        viewModelScope.launch {
            operationBusy = true
            try {
                val result = RootShell.exec("sh ${RootShell.quote(bridge)} action_undo", timeoutMs = 40_000L)
                val root = firstJson(result.stdout)
                val data = root.optJSONObject("data") ?: root
                if (result.code != 0 || root.optString("status") == "error") error(root.optString("message", result.stderr))
                operationMessage = data.optString("message", "已准备恢复上一次字体")
                rebootRequired = data.optBoolean("rebootRequired", false)
                refresh()
            } catch (error: Throwable) {
                operationMessage = error.message ?: "回退失败，请查看日志"
            } finally { operationBusy = false }
        }
    }

    fun markLogsViewed() = logReview("mark")
    fun clearLogs() = logReview("clear")
    private fun logReview(action: String) {
        viewModelScope.launch {
            val result = RootShell.exec("sh ${RootShell.quote(bridge)} log_review $action", timeoutMs = 20_000L)
            operationMessage = runCatching { val root = firstJson(result.stdout); (root.optJSONObject("data") ?: root).optString("message") }.getOrNull()
                .orEmpty().ifBlank { result.stderr.ifBlank { "日志操作完成" } }
            runCatching { val root = firstJson(result.stdout); logsViewed = (root.optJSONObject("data") ?: root).optBoolean("viewed") }
            refreshLogs()
        }
    }

    fun loadMountPreferences() = mountPreferenceRequest("get")
    fun setMountPreference(value: String) {
        if (value in setOf("meta", "self", "auto")) mountPreferenceRequest("set $value")
    }
    fun cancelMountPreference() = mountPreferenceRequest("cancel")
    private fun mountPreferenceRequest(action: String) {
        viewModelScope.launch {
            mountPreferenceMutex.withLock {
            val result = RootShell.exec("sh ${RootShell.quote(bridge)} mount_preferences $action", timeoutMs = 15_000L)
            mountPreferences = runCatching {
                val data = firstJson(result.stdout)
                if (!data.optBoolean("ok")) error(data.optString("message").ifBlank { data.optString("error", result.stderr) })
                fun label(value: String) = when (value) {
                    "external", "meta" -> "外部提供者"
                    "self" -> "字域兼容挂载"
                    "unresolved" -> "等待确认"
                    else -> "未启用"
                }
                buildString {
                    append("挂载方式：自动选择")
                    append("\nRoot：${data.optString("rootManager", "尚未确认")} ${data.optString("rootVersion")}")
                    append("\n提供者：${data.optString("providerName", "尚未确认")} ${data.optString("providerVersion")}")
                    append("\n实现方式：${data.optString("mountMethod", "尚未确认")}")
                    append("\n判断依据：${data.optString("mountMethodEvidence")}")
                    append("\n计划：${label(data.optString("plannedBackend"))}")
                    append("\n实际生效：${label(data.optString("activeBackend"))}")
                    append("\n验证结果：${data.optString("verification", "pending")}")
                    val lastError = data.optString("lastError")
                    if (lastError.isNotBlank() && lastError != "none" && lastError != "provider-scan-pending") {
                        append("\n状态原因：$lastError")
                    }
                    if (data.optBoolean("pending")) append("\n等待完整重启后的挂载结果。")
                }
            }.getOrElse { it.message ?: "挂载状态读取失败" }
            if (action != "get") operationMessage = mountPreferences
            }
        }
    }

    private fun acceptPreparedResult(taskId: String, result: JSONObject) {
        restoredCombinationTask = taskId
        val fontId = result.optString("generatedFontId")
        if (CombinationCompletion.shouldPreview(result.optString("state"), result.optString("result"), fontId, taskId, completionPrefs.getString("acknowledged", "").orEmpty())) {
            preparedCombination = PreparedCombination(taskId, fontId, result.optString("generatedFontName", fontId))
        }
    }

    private fun scheduleConfirmedRestart() {
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            restartCountdown = 10
            while (restartCountdown > 0) { delay(1_000L); restartCountdown -= 1 }
            rebootDevice()
        }
    }
    private var watchedTaskId: String = ""
    private var cachedFingerprint: String = ""
    private var fontRequestJob: Job? = null
    private var refreshJob: Job? = null
    private var logsJob: Job? = null
    private var mixConfigJob: Job? = null
    private val foreground = MutableStateFlow(true)
    private var pendingForceRefresh = false
    private var prewarmRequested = false

    var snapshot by mutableStateOf(ModuleSnapshot())
        private set

    var logs by mutableStateOf("尚未读取日志")
        private set

    var fonts by mutableStateOf<List<FontItem>>(emptyList())
        private set

    var fontLoading by mutableStateOf(false)
        private set

    var fontRefreshing by mutableStateOf(false)
        private set

    var fontCacheReady by mutableStateOf(false)
        private set

    var fontError by mutableStateOf("")
        private set

    private var _searchQuery by mutableStateOf("")
    val searchQuery: String get() = _searchQuery

    var operationBusy by mutableStateOf(false)
        private set

    var operationMessage by mutableStateOf("")
        private set

    var rebootRequired by mutableStateOf(false)
        private set

    var mixState by mutableStateOf(MixState())
        private set

    private val cacheLoadJob = viewModelScope.launch {
        val cached = withContext(Dispatchers.IO) { fontIndexStore.load() }
        if (cached != null && cached.fonts.isNotEmpty()) {
            fonts = cached.fonts
            cachedFingerprint = cached.fingerprint
            normalizeMixSelections()
        }
        fontCacheReady = true
        if (snapshot.installed) requestFontPrewarm()
    }

    val filteredFonts: List<FontItem>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return fonts
            return fonts.filter { item ->
                item.name.contains(query, ignoreCase = true) ||
                    item.id.contains(query, ignoreCase = true) ||
                    item.format.contains(query, ignoreCase = true)
            }
        }

    fun setSearchQuery(value: String) {
        _searchQuery = value
    }

    fun setForeground(visible: Boolean) {
        foreground.value = visible
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        snapshot = snapshot.copy(loading = true, error = "")
        refreshJob = viewModelScope.launch {
            val result = RootShell.exec(
                "if [ -f ${RootShell.quote(bridge)} ]; then sh ${RootShell.quote(bridge)} status; " +
                    "else printf '%s\\n' '{\"status\":\"error\",\"message\":\"请先刷入匹配的字域模块\"}'; fi",
                timeoutMs = 20_000L,
            )
            if (result.code != 0) {
                snapshot = ModuleSnapshot(
                    loading = false,
                    rootGranted = false,
                    error = result.stderr.ifBlank { "Root 授权失败或 su 不可用" },
                )
                return@launch
            }
            val parsed = parseSnapshot(result.stdout)
            snapshot = parsed
            rebootRequired = parsed.rebootRequired
            if (parsed.installed) refreshActionStatus()
            resumePendingTask(parsed)
            if (parsed.installed) requestFontPrewarm()
        }
    }

    fun ensureFonts(force: Boolean = false) {
        if (force) {
            refreshFonts(force = true)
            return
        }
        requestFontPrewarm()
    }

    fun refreshFonts(force: Boolean = false) {
        if (fontRequestJob?.isActive == true) {
            if (force) pendingForceRefresh = true
            return
        }
        launchFontWork(force = force, showErrors = force)
    }

    fun importSystemFonts() {
        if (operationBusy || mixState.busy || !snapshot.installed) return
        operationBusy = true
        operationMessage = "正在读取并添加系统原厂字体…"
        fontError = ""
        viewModelScope.launch {
            try {
                val result = RootShell.exec(
                    "sh ${RootShell.quote(bridge)} import_system_fonts",
                    timeoutMs = 120_000L,
                )
                val root = firstJson(result.stdout)
                if (result.code != 0 || root.optString("status") != "ok") {
                    error(root.optString("message", result.stderr.ifBlank { "系统字体添加失败" }))
                }
                operationMessage = "已添加系统默认中文、英文和数字字体，可在组合页选择"
                refreshFonts(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fontError = error.message ?: "系统字体添加失败"
                operationMessage = ""
            } finally {
                operationBusy = false
            }
        }
    }

    private fun requestFontPrewarm() {
        if (prewarmRequested && fonts.isNotEmpty()) return
        prewarmRequested = true
        if (fontRequestJob?.isActive == true) return
        launchFontWork(force = false, showErrors = false)
    }

    private fun launchFontWork(force: Boolean, showErrors: Boolean) {
        fontRequestJob = viewModelScope.launch {
            cacheLoadJob.join()
            if (!snapshot.installed && snapshot.versionCode == 0) return@launch
            val hadFonts = fonts.isNotEmpty()
            fontLoading = !hadFonts
            fontRefreshing = hadFonts
            if (showErrors) fontError = ""
            try {
                when {
                    force -> rebuildFontIndex(showErrors = true)
                    fonts.isEmpty() -> rebuildFontIndex(showErrors = showErrors)
                    else -> refreshOnlyWhenChanged(showErrors = showErrors)
                }
            } finally {
                fontLoading = false
                fontRefreshing = false
                fontRequestJob = null
                if (pendingForceRefresh && currentCoroutineContext().isActive) {
                    pendingForceRefresh = false
                    refreshFonts(force = true)
                }
            }
        }
    }

    private suspend fun refreshOnlyWhenChanged(showErrors: Boolean) {
        val fingerprint = readFontFingerprint()
        if (fingerprint == null) {
            if (showErrors) fontError = "无法检查字体目录变化，已继续使用本地索引"
            return
        }
        if (fingerprint.currentFont.isNotBlank()) {
            snapshot = snapshot.copy(activeFont = fingerprint.currentFont, activeFontName = resolvedFontName(fingerprint.currentFont))
        }
        if (fingerprint.value.isNotBlank() && fingerprint.value == cachedFingerprint) {
            persistFontIndex(currentFont = fingerprint.currentFont)
            return
        }
        rebuildFontIndex(
            showErrors = showErrors,
            knownFingerprint = fingerprint,
        )
    }

    private suspend fun rebuildFontIndex(
        showErrors: Boolean,
        knownFingerprint: FontFingerprint? = null,
    ) {
        val suffix = if (knownFingerprint != null || fonts.isNotEmpty()) " refresh" else ""
        val result = RootShell.exec(
            "sh ${RootShell.quote(bridge)} fonts$suffix",
            timeoutMs = 60_000L,
        )
        if (result.code != 0) {
            if (fonts.isEmpty() || showErrors) {
                fontError = result.stderr.ifBlank { "字体库读取失败" }
            }
            return
        }
        try {
            val root = firstJson(result.stdout)
            if (root.optString("status") != "ok") error(root.optString("message", "字体库读取失败"))
            val data = root.getJSONObject("data")
            val parsedFonts = parseFonts(data.optJSONArray("fonts") ?: JSONArray())
            val current = data.optString("current", knownFingerprint?.currentFont ?: snapshot.activeFont)
            val fingerprint = knownFingerprint ?: readFontFingerprint()
            fonts = parsedFonts
            snapshot = snapshot.copy(activeFont = current, activeFontName = resolvedFontName(current))
            cachedFingerprint = fingerprint?.value.orEmpty()
            normalizeMixSelections()
            persistFontIndex(currentFont = current)
            fontError = ""
        } catch (error: Throwable) {
            if (fonts.isEmpty() || showErrors) {
                fontError = error.message ?: "字体库解析失败"
            }
        }
    }

    private suspend fun readFontFingerprint(): FontFingerprint? {
        val result = RootShell.exec(
            "if [ -f ${RootShell.quote(fingerprintBridge)} ]; then " +
                "sh ${RootShell.quote(fingerprintBridge)} fingerprint; else exit 127; fi",
            timeoutMs = 8_000L,
        )
        if (result.code != 0) return null
        return runCatching {
            val root = firstJson(result.stdout)
            if (root.optString("status") != "ok") return@runCatching null
            val data = root.optJSONObject("data") ?: return@runCatching null
            FontFingerprint(
                value = data.optString("fingerprint", ""),
                currentFont = data.optString("current", snapshot.activeFont),
            )
        }.getOrNull()
    }

    private suspend fun persistFontIndex(currentFont: String = snapshot.activeFont) {
        val index = CachedFontIndex(
            fingerprint = cachedFingerprint,
            currentFont = currentFont.ifBlank { "default" },
            fonts = fonts,
            savedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            runCatching { fontIndexStore.save(index) }
        }
    }

    fun refreshMixConfig() {
        if (mixState.loading || mixState.busy) return
        mixState = mixState.copy(loading = true, error = "")
        mixConfigJob?.cancel()
        mixConfigJob = viewModelScope.launch {
            val result = RootShell.exec(
                "sh ${RootShell.quote(bridge)} mix_config",
                timeoutMs = 25_000L,
            )
            try {
                if (result.code != 0) error(result.stderr.ifBlank { "组合配置读取失败" })
                val root = firstJson(result.stdout)
                if (root.optString("status") != "ok") error(root.optString("message", "组合配置读取失败"))
                val data = root.getJSONObject("data")
                val cjkWeight = data.optInt("cjkWeight", mixState.cjkWeight).coerceIn(1, 1000)
                val latinWeight = data.optInt("latinWeight", mixState.latinWeight).coerceIn(1, 1000)
                val digitWeight = data.optInt("digitWeight", mixState.digitWeight).coerceIn(1, 1000)
                mixState = mixState.copy(
                    loading = false,
                    enabled = data.optBoolean("enabled", false),
                    cjk = data.optString("cjk", mixState.cjk),
                    latin = data.optString("latin", mixState.latin),
                    digit = data.optString("digit", mixState.digit),
                    cjkWeight = cjkWeight,
                    latinWeight = latinWeight,
                    digitWeight = digitWeight,
                    cjkAxes = parseAxes(data.optString("cjkAxes"), cjkWeight),
                    latinAxes = parseAxes(data.optString("latinAxes"), latinWeight),
                    digitAxes = parseAxes(data.optString("digitAxes"), digitWeight),
                    message = if (data.optBoolean("enabled", false)) "当前正在使用复合字体" else "可直接生成新的复合字体",
                    error = "",
                )
                normalizeMixSelections()
            } catch (error: Throwable) {
                val message = error.message.orEmpty()
                mixState = if (message.contains("interrupted by close", ignoreCase = true)) {
                    mixState.copy(loading = false, error = "")
                } else {
                    mixState.copy(loading = false, error = message.ifBlank { "组合配置读取失败" })
                }
            }
        }
    }

    fun updateMixFont(slot: MixSlot, fontId: String) {
        mixState = when (slot) {
            MixSlot.Cjk -> mixState.copy(cjk = fontId, cjkAxes = mapOf("wght" to mixState.cjkWeight.toFloat()))
            MixSlot.Latin -> mixState.copy(latin = fontId, latinAxes = mapOf("wght" to mixState.latinWeight.toFloat()))
            MixSlot.Digit -> mixState.copy(digit = fontId, digitAxes = mapOf("wght" to mixState.digitWeight.toFloat()))
        }
    }

    fun updateMixWeight(slot: MixSlot, weight: Int) {
        updateMixAxis(slot, "wght", weight.coerceIn(1, 1000).toFloat())
    }

    fun updateMixAxis(slot: MixSlot, tag: String, value: Float) {
        val cleanTag = tag.trim()
        if (cleanTag.length != 4 || !value.isFinite()) return
        val safe = if (cleanTag == "wght") value.coerceIn(1f, 1000f) else value
        mixState = when (slot) {
            MixSlot.Cjk -> mixState.copy(
                cjkWeight = if (cleanTag == "wght") safe.roundToInt() else mixState.cjkWeight,
                cjkAxes = mixState.cjkAxes + (cleanTag to safe),
            )
            MixSlot.Latin -> mixState.copy(
                latinWeight = if (cleanTag == "wght") safe.roundToInt() else mixState.latinWeight,
                latinAxes = mixState.latinAxes + (cleanTag to safe),
            )
            MixSlot.Digit -> mixState.copy(
                digitWeight = if (cleanTag == "wght") safe.roundToInt() else mixState.digitWeight,
                digitAxes = mixState.digitAxes + (cleanTag to safe),
            )
        }
    }

    fun startMix(name: String) {
        if (mixState.busy || operationBusy) return
        val combinationName = name.trim()
        if (combinationName.isBlank() || combinationName.length > 60 ||
            combinationName.any { it in "\r\n\t/\\\u0000|" }) {
            mixState = mixState.copy(error = "请输入 1–60 个字符的组合名称，不含路径分隔符或换行")
            return
        }
        val cjk = mixState.cjk
        val latin = mixState.latin
        val digit = mixState.digit
        if (cjk.isBlank() || latin.isBlank() || digit.isBlank()) {
            mixState = mixState.copy(error = "请先选择中文、英文和数字字体")
            return
        }

        val cjkAxes = serializeAxes(mixState.cjkAxes, mixState.cjkWeight)
        val latinAxes = serializeAxes(mixState.latinAxes, mixState.latinWeight)
        val digitAxes = serializeAxes(mixState.digitAxes, mixState.digitWeight)
        mixState = mixState.copy(
            busy = true,
            taskId = "",
            taskState = "queued",
            message = "正在提交复合字体任务…",
            progress = 1,
            error = "",
        )
        viewModelScope.launch {
            try {
                val command = buildString {
                    append("sh ${RootShell.quote(bridge)} mix_start ")
                    append(RootShell.quote(cjk)).append(' ')
                    append(RootShell.quote(latin)).append(' ')
                    append(RootShell.quote(digit)).append(' ')
                    append(RootShell.quote(cjkAxes)).append(' ')
                    append(RootShell.quote(latinAxes)).append(' ')
                    append(RootShell.quote(digitAxes)).append(' ')
                    append(RootShell.quote(combinationName))
                }
                val start = RootShell.exec(command, timeoutMs = 20_000L)
                if (start.code != 0) error(start.stderr.ifBlank { "无法启动复合字体任务" })
                val root = firstJson(start.stdout)
                if (root.optString("status") != "ok") error(root.optString("message", "无法启动复合字体任务"))
                val taskId = root.optJSONObject("data")?.optString("task").orEmpty()
                if (taskId.isBlank()) error("复合字体任务 ID 缺失")
                watchMixTask(taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                finishMixFailure(error.message ?: "复合字体生成失败")
            }
        }
    }

    fun prewarmFont(fontId: String) {
        if (fontId.isBlank() || fontId == "default" || !snapshot.installed) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                RootShell.exec(
                    "sh ${RootShell.quote(bridge)} prewarm ${RootShell.quote(fontId)}",
                    timeoutMs = 6_000L,
                )
            }
        }
    }

    fun applyFont(fontId: String) = startApplyFont(fontId, false)

    private fun startApplyFont(fontId: String, restartAfter: Boolean) {
        if (operationBusy || mixState.busy) return
        operationBusy = true
        operationMessage = if (fontId == "default") "正在准备恢复系统字体…" else "正在验证并应用字体…"
        viewModelScope.launch {
            try {
                if (fontId != "default") {
                    val validation = RootShell.exec(
                        "sh ${RootShell.quote(bridge)} validate ${RootShell.quote(fontId)}",
                        timeoutMs = 35_000L,
                    )
                    if (validation.code != 0) error(validation.stderr.ifBlank { "字体验证失败" })
                    val validationJson = firstJson(validation.stdout)
                    if (validationJson.optString("status") != "ok" ||
                        validationJson.optJSONObject("data")?.optBoolean("valid", true) == false
                    ) {
                        error(
                            validationJson.optString(
                                "message",
                                validationJson.optJSONObject("data")?.optString("error", "字体文件不可用")
                                    ?: "字体文件不可用",
                            ),
                        )
                    }
                }

                val start = RootShell.exec(
                    "sh ${RootShell.quote(bridge)} switch_start ${RootShell.quote(fontId)}",
                    timeoutMs = 20_000L,
                )
                if (start.code != 0) error(start.stderr.ifBlank { "无法启动字体切换" })
                val startJson = firstJson(start.stdout)
                if (startJson.optString("status") != "ok") error(startJson.optString("message", "无法启动字体切换"))
                val taskId = startJson.optJSONObject("data")?.optString("task").orEmpty()
                if (taskId.isBlank()) error("字体任务 ID 缺失")
                watchSwitchTask(taskId, fontId, restartAfter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                operationMessage = error.message ?: "字体应用失败"
                snapshot = snapshot.copy(taskState = "failed", taskMessage = operationMessage)
                operationBusy = false
            }
        }
    }

    fun deleteFont(fontId: String) {
        if (operationBusy || mixState.busy || fontId.isBlank() || fontId == "default") return
        operationBusy = true
        operationMessage = "正在删除字体…"
        viewModelScope.launch {
            try {
                val result = RootShell.exec(
                    "sh ${RootShell.quote(bridge)} delete ${RootShell.quote(fontId)}",
                    timeoutMs = 35_000L,
                )
                if (result.code != 0) error(result.stderr.ifBlank { "字体删除失败" })
                val root = firstJson(result.stdout)
                if (root.optString("status") != "ok") error(root.optString("message", "字体删除失败"))
                fonts = fonts.filterNot { it.id == fontId }
                cachedFingerprint = ""
                normalizeMixSelections()
                persistFontIndex()
                operationMessage = "字体已删除"
                refreshFonts(force = true)
            } catch (error: Throwable) {
                operationMessage = error.message ?: "字体删除失败"
            } finally {
                operationBusy = false
            }
        }
    }

    fun rebootDevice() {
        // Complete reboot is deliberately independent from font-task busy state. A stale worker
        // flag used to make the button look dead for tens of seconds even though reboot itself is
        // immediate. The shell bridge backgrounds the reboot command, so use a short request timeout.
        operationMessage = "正在请求完整重启…"
        viewModelScope.launch {
            val result = RootShell.exec("sh ${RootShell.quote(bridge)} reboot", timeoutMs = 4_000L)
            if (result.code != 0) {
                operationMessage = result.stderr.ifBlank { "重启请求失败" }
            }
        }
    }

    fun refreshLogs() {
        if (logsJob?.isActive == true) return
        logsJob = viewModelScope.launch {
            val result = RootShell.exec(
                "if [ -f ${RootShell.quote(bridge)} ]; then sh ${RootShell.quote(bridge)} logs 180; " +
                    "else tail -n 180 /data/adb/modules/LuoShu/logs/fontswitch.log 2>/dev/null; fi",
                timeoutMs = 20_000L,
            )
            logs = when {
                result.code != 0 -> result.stderr.ifBlank { "日志读取失败" }
                result.stdout.isBlank() -> "当前还没有字体任务日志。"
                else -> result.stdout.trimEnd()
            }
            val review = RootShell.exec("sh ${RootShell.quote(bridge)} log_review status", timeoutMs = 8_000L)
            runCatching { val root = firstJson(review.stdout); logsViewed = (root.optJSONObject("data") ?: root).optBoolean("viewed") }
            refreshActionStatus()
        }
    }

    private fun resumePendingTask(state: ModuleSnapshot) {
        if (state.taskId.isBlank() || state.taskId == watchedTaskId) return
        when {
            state.taskType == "mix" && state.taskState in setOf("queued", "running") -> {
                mixState = mixState.copy(
                    busy = true,
                    taskId = state.taskId,
                    taskState = state.taskState,
                    message = state.taskMessage,
                    progress = state.taskProgress,
                    error = "",
                )
                viewModelScope.launch { watchMixTask(state.taskId) }
            }
            state.taskType == "switch" && state.taskState in setOf("queued", "running") -> {
                operationBusy = true
                operationMessage = state.taskMessage
                viewModelScope.launch { watchSwitchTask(state.taskId, state.activeFont) }
            }
            state.taskType == "mix" && state.taskState == "success" -> {
                mixState = mixState.copy(
                    busy = false,
                    enabled = state.activeFont == "mix",
                    taskId = state.taskId,
                    taskState = "success",
                    message = state.taskMessage,
                    progress = 100,
                    error = "",
                )
                if (restoredCombinationTask != state.taskId) {
                    restoredCombinationTask = state.taskId
                    viewModelScope.launch {
                        val result = RootShell.exec("sh ${RootShell.quote(bridge)} mix_status ${RootShell.quote(state.taskId)}", timeoutMs = 15_000L)
                        runCatching { firstJson(result.stdout).optJSONObject("data")?.let { acceptPreparedResult(state.taskId, it) } }
                        refreshFonts(force = true)
                    }
                }
            }
            state.taskState in setOf("failed", "cancelled") -> {
                if (state.taskType == "mix") {
                    mixState = mixState.copy(
                        busy = false,
                        taskId = state.taskId,
                        taskState = state.taskState,
                        message = state.taskMessage,
                        progress = 100,
                        error = if (state.taskState == "failed") state.taskMessage else "",
                    )
                } else {
                    operationMessage = state.taskMessage
                }
            }
        }
    }

    private suspend fun watchSwitchTask(taskId: String, fontId: String, restartAfter: Boolean = false) {
        if (watchedTaskId == taskId) return
        watchedTaskId = taskId
        operationBusy = true
        try {
            val result = waitForTask("switch_status", taskId, timeoutSeconds = 390) { data ->
                operationMessage = data.optString("message", "正在处理字体…")
                snapshot = snapshot.copy(
                    taskType = "switch",
                    taskId = taskId,
                    taskState = data.optString("state", "running"),
                    taskMessage = operationMessage,
                    taskProgress = data.optInt("percent", snapshot.taskProgress).coerceIn(0, 100),
                )
            }
            if (result.optString("state") == "cancelled") {
                operationMessage = result.optString("message", "字体应用已终止")
                snapshot = snapshot.copy(taskState = "cancelled", taskMessage = operationMessage)
                return
            }
            if (result.optString("state") != "success") error(result.optString("message", "字体应用失败"))
            val applied = result.optString("font", fontId).ifBlank { fontId }
            val reused = result.optBoolean("reused", false)
            operationMessage = when {
                reused -> "当前字体已验证，无需重新生成或重启"
                applied == "default" -> "已准备恢复系统字体，重启后生效"
                else -> "字体已准备完成，重启后全局生效"
            }
            val nextRebootRequired = if (reused) rebootRequired else true
            rebootRequired = nextRebootRequired
            snapshot = snapshot.copy(
                activeFont = applied,
                activeFontName = resolvedFontName(applied),
                taskType = "switch",
                taskId = taskId,
                taskState = "success",
                taskMessage = operationMessage,
                taskProgress = 100,
                rebootRequired = nextRebootRequired,
            )
            persistFontIndex(currentFont = applied)
            refreshActionStatus()
            if (CombinationCompletion.shouldRestart(result.optString("state"), restartAfter) && !reused) scheduleConfirmedRestart()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            operationMessage = error.message ?: "字体应用失败"
            snapshot = snapshot.copy(taskState = "failed", taskMessage = operationMessage, taskProgress = 100)
        } finally {
            operationBusy = false
            watchedTaskId = ""
        }
    }

    private suspend fun watchMixTask(taskId: String) {
        if (watchedTaskId == taskId) return
        watchedTaskId = taskId
        mixState = mixState.copy(
            busy = true,
            taskId = taskId,
            taskState = "running",
            message = "复合字体正在后台生成",
            error = "",
        )
        try {
            val result = waitForTask("mix_status", taskId, timeoutSeconds = 720) { data ->
                val state = data.optString("state", "running")
                val progress = data.optJSONObject("progress")
                    ?.optInt("percent", data.optInt("percent", 0))
                    ?: data.optInt("percent", 0)
                val message = data.optString("message", "复合字体正在后台生成")
                mixState = mixState.copy(
                    taskId = taskId,
                    taskState = state,
                    message = message,
                    progress = progress.coerceIn(0, 100),
                )
                snapshot = snapshot.copy(
                    taskType = "mix",
                    taskId = taskId,
                    taskState = state,
                    taskMessage = message,
                    taskProgress = progress.coerceIn(0, 100),
                )
            }
            if (result.optString("state") == "cancelled") {
                mixState = mixState.copy(busy = false, taskState = "cancelled", message = result.optString("message", "组合生成已终止"), error = "")
                return
            }
            if (result.optString("state") != "success") error(result.optString("message", "复合字体生成失败"))
            val message = result.optString("message", "组合已保存到字体库，请预览后选择是否应用")
            mixState = mixState.copy(
                busy = false,
                enabled = false,
                taskId = taskId,
                taskState = "success",
                message = message,
                progress = 100,
                error = "",
            )
            snapshot = snapshot.copy(
                taskType = "mix",
                taskId = taskId,
                taskState = "success",
                taskMessage = message,
                taskProgress = 100,
            )
            acceptPreparedResult(taskId, result)
            refreshFonts(force = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            finishMixFailure(error.message ?: "复合字体生成失败")
        } finally {
            watchedTaskId = ""
        }
    }

    private suspend fun waitForTask(
        command: String,
        taskId: String,
        timeoutSeconds: Int,
        onProgress: (JSONObject) -> Unit,
    ): JSONObject {
        var failures = 0
        val budget = TaskPollBudget(timeoutSeconds.toLong() * 1_000L)
        while (budget.remainingMs > 0L) {
            awaitForeground(budget)
            val intervalMs = if (budget.elapsedMs < 30_000L) 1_000L else 2_000L
            delay(minOf(intervalMs, budget.remainingMs))
            awaitForeground(budget)
            val remainingMs = budget.remainingMs
            if (remainingMs <= 0L) break
            val status = RootShell.exec(
                "sh ${RootShell.quote(bridge)} $command ${RootShell.quote(taskId)}",
                timeoutMs = minOf(15_000L, remainingMs),
            )
            if (status.code != 0) {
                failures += 1
                if (failures >= 8) error(status.stderr.ifBlank { "连续无法读取任务状态" })
                continue
            }
            val root = runCatching { firstJson(status.stdout) }.getOrNull()
            val data = root?.optJSONObject("data")
            if (root?.optString("status") != "ok" || data == null) {
                failures += 1
                if (failures >= 8) error(root?.optString("message", "任务状态读取失败") ?: "任务状态读取失败")
                continue
            }
            failures = 0
            val advertisedTimeout = data.optInt("timeout", 0)
            if (advertisedTimeout > 0) budget.extendTo((advertisedTimeout.toLong() + 30L) * 1_000L)
            onProgress(data)
            when (data.optString("state")) {
                "success", "failed", "cancelled" -> return data
            }
        }
        error("字体任务超时，请查看日志")
    }

    private suspend fun awaitForeground(budget: TaskPollBudget) {
        if (foreground.value) return
        val pausedAt = System.nanoTime()
        foreground.first { it }
        budget.excludePause((System.nanoTime() - pausedAt) / 1_000_000L)
    }

    private fun finishMixFailure(message: String) {
        mixState = mixState.copy(
            busy = false,
            taskState = "failed",
            message = message,
            error = message,
            progress = 100,
        )
        snapshot = snapshot.copy(taskState = "failed", taskMessage = message, taskProgress = 100)
    }

    private fun normalizeMixSelections() {
        val available = fonts.filter { it.valid }
        if (available.isEmpty()) return
        val ids = available.map { it.id }.toSet()
        val first = available.first().id
        mixState = mixState.copy(
            cjk = mixState.cjk.takeIf { it in ids } ?: first,
            latin = mixState.latin.takeIf { it in ids } ?: available.getOrNull(1)?.id ?: first,
            digit = mixState.digit.takeIf { it in ids } ?: available.getOrNull(2)?.id ?: available.getOrNull(1)?.id ?: first,
        )
    }

    private fun parseSnapshot(raw: String): ModuleSnapshot {
        return try {
            val root = firstJson(raw)
            if (root.optString("status") != "ok") {
                return ModuleSnapshot(
                    loading = false,
                    rootGranted = true,
                    error = root.optString("message", "模块状态读取失败"),
                )
            }
            val data = root.getJSONObject("data")
            ModuleSnapshot(
                loading = false,
                rootGranted = data.optBoolean("root", true),
                installed = data.optBoolean("installed", false),
                version = data.optString("version", "未知版本"),
                versionCode = data.optInt("versionCode", 0),
                activeFont = data.optString("active", "default"),
                activeFontName = resolvedFontName(data.optString("active", "default")),
                effectiveFont = data.optString("effectiveActive", "unknown"),
                fontEffectState = data.optString("fontEffectState", "unknown"),
                verificationState = data.optString("verificationState", "unknown"),
                verificationGrade = data.optString("verificationGrade", "PENDING"),
                verificationMode = data.optString("verificationMode", "unknown"),
                verificationReason = data.optString("verificationReason", ""),
                mountState = data.optString("mountState", "unknown"),
                mountFailure = data.optString("mountFailure", ""),
                cutoverState = data.optString("cutoverState", "idle"),
                cutoverDecision = data.optString("cutoverDecision", "none"),
                rollbackState = data.optString("rollbackState", "none"),
                rollbackPending = data.optBoolean("rollbackPending", false),
                rollbackTargetFont = data.optString("rollbackTargetFont", ""),
                rollbackTargetMode = data.optString("rollbackTargetMode", ""),
                taskType = data.optString("taskType", "none"),
                taskId = data.optString("taskId", ""),
                taskState = data.optString("taskState", "idle"),
                taskMessage = data.optString("taskMessage", "暂无后台任务"),
                taskProgress = data.optInt("taskProgress", 0).coerceIn(0, 100),
                rebootRequired = data.optBoolean("rebootRequired", false),
                rootManager = data.optString("rootManager", "Root"),
                mountEngine = data.optString("mountEngine", "原生模块挂载"),
            )
        } catch (error: Throwable) {
            ModuleSnapshot(
                loading = false,
                rootGranted = true,
                error = error.message ?: "模块状态解析失败",
            )
        }
    }

    private fun parseFonts(array: JSONArray): List<FontItem> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || id == "default") continue
            val weightsArray = item.optJSONArray("weights")
            val weights = buildList {
                if (weightsArray != null) {
                    for (weightIndex in 0 until weightsArray.length()) {
                        weightsArray.optString(weightIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            add(
                FontItem(
                    id = id,
                    name = item.optString("name", id),
                    format = item.optString("format", "TTF"),
                    size = item.optString("size", ""),
                    date = item.optString("date", ""),
                    variable = item.optBoolean("variable", weights.contains("variable")),
                    valid = item.optBoolean("valid", true),
                    error = item.optString("error", ""),
                    weights = weights,
                    supportsCjk = item.optBoolean("supportsCjk", true),
                    combination = item.optBoolean("combination", false),
                ),
            )
        }
    }

    private fun resolvedFontName(id: String): String =
        fonts.firstOrNull { it.id == id }?.name
            ?.takeIf { name -> name.isNotBlank() && name != id }
            .orEmpty()

    private fun parseAxes(raw: String, fallbackWeight: Int): Map<String, Float> {
        val axes = linkedMapOf<String, Float>()
        raw.split(',').forEach { item ->
            val parts = item.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val tag = parts[0].trim()
            val value = parts[1].trim().toFloatOrNull()
            if (tag.length == 4 && value != null && value.isFinite()) axes[tag] = value
        }
        if ("wght" !in axes) axes["wght"] = fallbackWeight.toFloat()
        return axes.toMap()
    }

    private fun serializeAxes(axes: Map<String, Float>, fallbackWeight: Int): String {
        val normalized = axes.filter { (tag, value) -> tag.length == 4 && value.isFinite() }.toMutableMap()
        if ("wght" !in normalized) normalized["wght"] = fallbackWeight.toFloat()
        return normalized.toSortedMap().entries.joinToString(",") { (tag, value) ->
            val number = if (value % 1f == 0f) value.roundToInt().toString() else value.toString().trimEnd('0').trimEnd('.')
            "$tag=$number"
        }
    }

    private fun firstJson(raw: String): JSONObject {
        val line = raw.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: error("未收到 JSON 数据")
        return JSONObject(line.trim())
    }
}
