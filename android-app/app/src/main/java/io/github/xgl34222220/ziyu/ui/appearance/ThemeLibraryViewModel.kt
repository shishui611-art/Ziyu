package io.github.xgl34222220.ziyu.ui.appearance

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SourceTestSummary(
    val sourceUrl: String,
    val ok: Boolean,
    val message: String,
    val health: SourceHealthResult? = null,
)

internal data class ThemeLibraryState(
    val storeSettings: ThemeStoreSettings,
    val activeSources: List<ThemeSource>,
    val accepted: Boolean,
    val showR18: Boolean,
    val twoColumns: Boolean = true,
    val catalogLoading: Boolean = false,
    val busy: Boolean = false,
    val catalog: List<StoreTheme> = emptyList(),
    val cached: Boolean = false,
    val maintenance: Boolean = false,
    val failedSources: Int = 0,
    val downloads: List<ThemeDownloadRecord> = emptyList(),
    val downloadingKey: String? = null,
    val downloadTrial: Boolean = false,
    val transfer: ThemeTransfer? = null,
    val verifiedThemeKey: String? = null,
    val pending: PreparedTheme? = null,
    val message: String = "",
    val isError: Boolean = false,
    val sourceTestBusy: Boolean = false,
    val sourceTest: SourceTestSummary? = null,
)

internal class ThemeLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Context = application.applicationContext
    private val store = ThemeStoreRepository(appContext)
    private val downloadStore = ThemeDownloadStore(appContext)
    private val appearance = AppearanceRepository(appContext)
    private val uiPrefs = appContext.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private val initialSettings = store.settings()
    private val mutable = MutableStateFlow(
        ThemeLibraryState(
            storeSettings = initialSettings,
            activeSources = store.activeSources(),
            accepted = initialSettings.consent,
            showR18 = initialSettings.showR18,
            twoColumns = uiPrefs.getBoolean(KEY_TWO_COLUMNS, true),
        ),
    )
    val state = mutable.asStateFlow()

    private var catalogJob: Job? = null
    private var transferJob: Job? = null
    private var sourceTestJob: Job? = null
    private var transferGeneration = 0

    init {
        ThemeTrialManager.syncState(appContext)
        refreshDownloads()
    }

    fun refresh() {
        if (!mutable.value.accepted) return
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            mutable.update { it.copy(catalogLoading = true, message = "", isError = false) }
            try {
                val batch = store.loadAllCatalogs()
                currentCoroutineContext().ensureActive()
                val note = when {
                    batch.maintenance -> "主题商店正在维护，请稍后再试"
                    batch.fromCache && batch.failedSources > 0 -> "部分来源不可用，已显示上次缓存的主题"
                    batch.fromCache -> "当前无法连接在线目录，显示上次缓存的主题"
                    batch.failedSources > 0 -> "有 ${batch.failedSources} 个主题来源连接失败"
                    batch.themes.isEmpty() -> "暂无可用主题"
                    else -> ""
                }
                mutable.update {
                    it.copy(
                        catalog = batch.themes,
                        activeSources = batch.activeSources,
                        cached = batch.fromCache,
                        maintenance = batch.maintenance,
                        failedSources = batch.failedSources,
                        message = note,
                        isError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update {
                    it.copy(
                        message = error.message?.take(180) ?: "无法加载主题目录",
                        isError = true,
                    )
                }
            } finally {
                mutable.update { it.copy(catalogLoading = false) }
            }
        }
    }

    fun refreshDownloads() {
        viewModelScope.launch {
            val list = runCatching { downloadStore.list() }.getOrDefault(emptyList())
            mutable.update { it.copy(downloads = list) }
        }
    }

    fun setTwoColumns(enabled: Boolean) {
        uiPrefs.edit().putBoolean(KEY_TWO_COLUMNS, enabled).apply()
        mutable.update { it.copy(twoColumns = enabled) }
    }

    fun accept() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.accept() } }
                .onSuccess {
                    syncSettingsFromStore()
                    refresh()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(message = "无法保存连接同意", isError = true) }
                }
        }
    }

    fun revokeConsent() {
        catalogJob?.cancel()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.revokeConsent() } }
                .onSuccess {
                    syncSettingsFromStore()
                    mutable.update {
                        it.copy(
                            catalog = emptyList(),
                            cached = false,
                            maintenance = false,
                            failedSources = 0,
                            message = "已撤回主题来源连接同意",
                            isError = false,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(message = "无法撤回同意", isError = true) }
                }
        }
    }

    fun setShowR18(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.setShowR18(enabled) } }
                .onSuccess { syncSettingsFromStore() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(message = "保存显示设置失败", isError = true) }
                }
        }
    }

    fun saveDefaultSource(auto: Boolean, url: String, accessCode: String) = launchWork {
        val current = store.settings()
        val normalized = if (auto || url.isBlank()) "" else ThemeStoreRepository.normalize(url)
        val oldNormalized = if (current.defaultAuto || current.defaultUrl.isBlank()) "" else current.defaultUrl
        // 切换主机时绝不沿用旧访问码。
        val finalCode = if (normalized != oldNormalized && accessCode == current.defaultAccessCode) "" else accessCode.trim()
        val next = current.copy(
            defaultAuto = auto || normalized.isBlank(),
            defaultUrl = normalized,
            defaultAccessCode = finalCode,
            consent = true,
        )
        withContext(Dispatchers.IO) { store.saveSettings(next) }
        val primary = store.source()
        store.reconnect(primary)
        syncSettingsFromStore()
        mutable.update { it.copy(message = "默认来源已保存", isError = false) }
        refresh()
    }

    fun saveExtraSource(existingId: String?, name: String, url: String, accessCode: String) = launchWork {
        val current = store.settings()
        val normalized = ThemeStoreRepository.normalize(url)
        val old = existingId?.let { id -> current.extraSources.firstOrNull { it.id == id } }
        val finalCode = if (old != null && normalized != old.url && accessCode == old.accessCode) "" else accessCode.trim()
        val item = ThemeSource(
            url = normalized,
            accessCode = finalCode,
            id = existingId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = name.trim().take(60),
            isDefault = false,
        )
        val others = current.extraSources.filterNot { it.id == item.id || it.url == item.url }
        val next = current.copy(extraSources = others + item, consent = true)
        withContext(Dispatchers.IO) { store.saveSettings(next) }
        syncSettingsFromStore()
        mutable.update { it.copy(message = "已保存主题来源", isError = false) }
        refresh()
    }

    fun removeExtraSource(id: String) = launchWork {
        val current = store.settings()
        val next = current.copy(extraSources = current.extraSources.filterNot { it.id == id })
        withContext(Dispatchers.IO) { store.saveSettings(next) }
        syncSettingsFromStore()
        mutable.update { it.copy(message = "已移除自定义来源", isError = false) }
        if (next.consent) refresh()
    }

    fun reconnectSource(source: ThemeSource) = launchWork {
        store.reconnect(source)
        mutable.update { it.copy(message = "已重新授权连接", isError = false) }
        if (mutable.value.accepted) refresh()
    }

    fun testSource(source: ThemeSource) {
        sourceTestJob?.cancel()
        sourceTestJob = viewModelScope.launch {
            mutable.update { it.copy(sourceTestBusy = true, sourceTest = null) }
            try {
                val summary = coroutineScope {
                    val catalogDeferred = async { runCatching { store.testCatalog(source) } }
                    val healthDeferred = async { runCatching { store.checkHealth(source) } }
                    val catalogRes = catalogDeferred.await()
                    val healthRes = healthDeferred.await().getOrNull()
                    catalogRes.fold(
                        onSuccess = { (count, fromCache, notModified) ->
                            val stateText = when {
                                fromCache -> "使用缓存"
                                notModified -> "目录未变化（304）"
                                else -> "目录正常"
                            }
                            val latency = healthRes?.let { " · ${it.latencyMs} ms" }.orEmpty()
                            SourceTestSummary(
                                sourceUrl = source.url,
                                ok = true,
                                message = "$stateText，共 $count 个主题$latency",
                                health = healthRes,
                            )
                        },
                        onFailure = { err ->
                            val healthHint = healthRes?.let { "（健康状态：${it.status}，${it.latencyMs} ms）" }.orEmpty()
                            SourceTestSummary(
                                sourceUrl = source.url,
                                ok = false,
                                message = "${err.message ?: "连接失败"}$healthHint",
                                health = healthRes,
                            )
                        },
                    )
                }
                mutable.update { it.copy(sourceTest = summary) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                mutable.update { it.copy(sourceTestBusy = false) }
            }
        }
    }

    fun clearSourceTest() {
        sourceTestJob?.cancel()
        mutable.update { it.copy(sourceTestBusy = false, sourceTest = null) }
    }

    fun import(uri: Uri, style: UiStyle) = launchWork {
        val prepared = ThemePackages.prepare(appContext, uri, style)
        mutable.value.pending?.discard()
        mutable.update { it.copy(pending = prepared) }
    }

    fun download(
        theme: StoreTheme,
        style: UiStyle,
        trial: Boolean = false,
        currentSettings: AppearanceSettings? = null,
    ) = launchWork(downloadingKey = theme.key, trial = trial) {
        val file = store.download(theme) { transfer ->
            mutable.update { it.copy(transfer = transfer) }
        }
        val previewPath = theme.preview.ifBlank { theme.screenshots.firstOrNull().orEmpty() }
        val previewFile = if (previewPath.isNotBlank()) runCatching { store.preview(theme.source, previewPath) }.getOrNull() else null
        downloadStore.save(theme, file, previewFile)
        val refreshedDownloads = runCatching { downloadStore.list() }.getOrDefault(mutable.value.downloads)
        mutable.update { it.copy(downloads = refreshedDownloads, verifiedThemeKey = theme.key) }

        val prepared = ThemePackages.prepare(appContext, Uri.fromFile(file), style, theme.name, theme.author, theme.license)
        if (trial && currentSettings != null) {
            try {
                ThemeTrialManager.startTrial(
                    context = appContext,
                    currentSettings = currentSettings,
                    candidate = prepared.settings,
                    options = trialImportOptions(prepared),
                )
                mutable.update {
                    it.copy(
                        transfer = null,
                        message = "正在试用「${prepared.settings.themeName}」，退出或点击恢复即可还原",
                        isError = false,
                    )
                }
            } finally {
                prepared.discard()
            }
        } else {
            mutable.value.pending?.discard()
            mutable.update { it.copy(pending = prepared, transfer = null) }
        }
    }

    fun openDownloaded(
        record: ThemeDownloadRecord,
        style: UiStyle,
        trial: Boolean = false,
        currentSettings: AppearanceSettings? = null,
    ) = launchWork(downloadingKey = record.key, trial = trial) {
        val file = downloadStore.fileFor(record)
        require(file.isFile) { "离线主题文件已丢失，请重新下载" }
        val prepared = ThemePackages.prepare(appContext, Uri.fromFile(file), style, record.name, record.author, record.license)
        if (trial && currentSettings != null) {
            try {
                ThemeTrialManager.startTrial(
                    context = appContext,
                    currentSettings = currentSettings,
                    candidate = prepared.settings,
                    options = trialImportOptions(prepared),
                )
                mutable.update {
                    it.copy(
                        message = "正在试用「${prepared.settings.themeName}」，退出或点击恢复即可还原",
                        isError = false,
                    )
                }
            } finally {
                prepared.discard()
            }
        } else {
            mutable.value.pending?.discard()
            mutable.update { it.copy(pending = prepared) }
        }
    }

    fun deleteDownloads(keys: Set<String>) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val remaining = runCatching { downloadStore.delete(keys) }.getOrNull() ?: return@launch
            mutable.update {
                it.copy(
                    downloads = remaining,
                    message = "已删除 ${keys.size} 个离线主题",
                    isError = false,
                )
            }
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            runCatching { downloadStore.clear() }
                .onSuccess {
                    mutable.update {
                        it.copy(
                            downloads = emptyList(),
                            message = "已清空离线主题库",
                            isError = false,
                        )
                    }
                }
        }
    }

    fun apply(options: ThemeImportOptions) = launchWork {
        val pending = mutable.value.pending ?: return@launchWork
        ThemeTrialManager.keepCurrent(appContext)
        appearance.applyTheme(pending.settings, options)
        mutable.update { it.copy(pending = null, message = "主题已应用", isError = false) }
        pending.discard()
    }

    fun restoreTrial() = launchWork {
        val restored = ThemeTrialManager.restoreNow(appContext)
        mutable.update {
            it.copy(
                message = if (restored) "已恢复试用前的主题" else "当前没有正在试用的主题",
                isError = false,
            )
        }
    }

    fun keepTrial() {
        ThemeTrialManager.keepCurrent(appContext)
        mutable.update { it.copy(message = "已保留当前主题", isError = false) }
    }

    fun discard() {
        if (mutable.value.busy) return
        mutable.value.pending?.discard()
        mutable.update { it.copy(pending = null) }
    }

    fun cancelTransfer() {
        transferGeneration++
        transferJob?.cancel()
        transferJob = null
        mutable.update {
            it.copy(
                busy = false,
                downloadingKey = null,
                downloadTrial = false,
                transfer = null,
                message = "已取消操作",
                isError = false,
            )
        }
    }

    fun export(settings: AppearanceSettings, uri: Uri) = launchWork {
        val note = ThemePackages.export(appContext, settings, uri)
        mutable.update { it.copy(message = note, isError = false) }
    }

    suspend fun preview(source: ThemeSource, path: String): File? = store.preview(source, path)

    fun previewFileForRecord(record: ThemeDownloadRecord): File? = downloadStore.previewFor(record)

    fun clearMessage() {
        mutable.update { it.copy(message = "", isError = false) }
    }

    fun close() {
        transferGeneration++
        catalogJob?.cancel(); catalogJob = null
        transferJob?.cancel(); transferJob = null
        sourceTestJob?.cancel(); sourceTestJob = null
        mutable.value.pending?.discard()
        mutable.update {
            it.copy(
                pending = null,
                busy = false,
                catalogLoading = false,
                downloadingKey = null,
                downloadTrial = false,
                transfer = null,
                sourceTestBusy = false,
            )
        }
    }

    private fun trialImportOptions(prepared: PreparedTheme): ThemeImportOptions = ThemeImportOptions(
        applyColorsAndStyle = true,
        applyBackground = true,
        applyDynamicWallpaper = true,
        applyHomeCard = true,
        applyDynamicHomeCard = true,
        applyStickers = true,
        applyBottomBarIcons = true,
        applyAppIcon = false,
        textGroups = ThemeImportOptions.ALL_TEXT_GROUPS,
        themeMode = prepared.settings.themeMode,
    )

    private fun syncSettingsFromStore() {
        val s = store.settings()
        mutable.update {
            it.copy(
                storeSettings = s,
                activeSources = store.activeSources(),
                accepted = s.consent,
                showR18 = s.showR18,
            )
        }
    }

    private fun launchWork(
        downloadingKey: String? = null,
        trial: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (transferJob?.isActive == true) return
        val owner = ++transferGeneration
        transferJob = viewModelScope.launch {
            mutable.update {
                it.copy(
                    busy = true,
                    downloadingKey = downloadingKey,
                    downloadTrial = trial,
                    transfer = null,
                    message = "",
                    isError = false,
                )
            }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner == transferGeneration) {
                    mutable.update {
                        it.copy(
                            message = error.message?.take(180) ?: "操作失败，请重试",
                            isError = true,
                        )
                    }
                }
            } finally {
                if (owner == transferGeneration) {
                    mutable.update {
                        it.copy(
                            busy = false,
                            downloadingKey = null,
                            downloadTrial = false,
                            transfer = null,
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    companion object {
        private const val UI_PREFS = "ziyu_theme_store_ui"
        private const val KEY_TWO_COLUMNS = "two_columns"
    }
}
