package io.github.xgl34222220.ziyu.ui.appearance

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ThemeLibraryState(
    val source: ThemeSource, val accepted: Boolean, val busy: Boolean = false,
    val catalog: List<StoreTheme> = emptyList(), val cached: Boolean = false,
    val message: String = "", val transfer: ThemeTransfer? = null, val pending: PreparedTheme? = null,
)

internal class ThemeLibraryViewModel(application: Application): AndroidViewModel(application) {
    private val store = ThemeStoreRepository(application)
    private val appearance = AppearanceRepository(application)
    private val mutable = MutableStateFlow(ThemeLibraryState(store.source(), store.accepted()))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    fun refresh() = launch {
        val source=mutable.value.source
        val (catalog,cached)=store.catalog(source)
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(catalog=catalog,cached=cached,message=if(cached)"连接失败，显示上次保存的主题" else if(catalog.isEmpty())"暂无主题" else "") }
    }
    fun accept() {
        if(work?.isActive==true)return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.accept() } }
                .onSuccess { mutable.update { it.copy(accepted=true) }; refresh() }
                .onFailure { e -> if(e is CancellationException)throw e; mutable.update { it.copy(message="无法保存连接选择") } }
        }
    }
    fun saveSource(url: String, accessCode: String) = launch {
        val normalized=ThemeStoreRepository.normalize(url)
        val old=mutable.value.source
        // Never carry an existing source credential over to a different host.
        val code=if(normalized!=old.url && accessCode==old.accessCode)"" else accessCode
        withContext(Dispatchers.IO) { store.save(ThemeSource(normalized,code)) }
        val source=store.source()
        store.reconnect(source)
        mutable.update { it.copy(source=source,accepted=true,catalog=emptyList()) }
        val (catalog,cached)=store.catalog(source)
        mutable.update { it.copy(catalog=catalog,cached=cached,message=if(cached)"连接失败，显示上次保存的主题" else "") }
    }
    fun import(uri: Uri, style: UiStyle) = launch {
        val prepared=ThemePackages.prepare(getApplication(),uri,style)
        mutable.value.pending?.discard()
        mutable.update { it.copy(pending=prepared) }
    }
    fun download(theme: StoreTheme, style: UiStyle) = launch {
        val source=mutable.value.source
        val file=store.download(source,theme) { transfer -> mutable.update { it.copy(transfer=transfer) } }
        val prepared=ThemePackages.prepare(getApplication(),Uri.fromFile(file),style,theme.name,theme.author,theme.license)
        mutable.value.pending?.discard()
        mutable.update { it.copy(pending=prepared,transfer=null) }
    }
    fun apply(includeTexts: Boolean = true, themeMode: ThemeMode? = null) = launch {
        val pending=mutable.value.pending ?: return@launch
        appearance.applyTheme(pending.settings.copy(themeMode=themeMode ?: pending.settings.themeMode), includeTexts)
        mutable.update { it.copy(pending=null,message="已应用") }; pending.discard()
    }
    fun discard() {
        if(mutable.value.busy)return
        mutable.value.pending?.discard(); mutable.update { it.copy(pending=null) }
    }
    fun export(settings: AppearanceSettings, uri: Uri) = launch {
        ThemePackages.export(getApplication(),settings,uri)
        mutable.update { it.copy(message="已导出") }
    }
    suspend fun preview(source: ThemeSource, path: String) = store.preview(source,path)
    fun close() {
        generation++
        work?.cancel(); work=null
        mutable.value.pending?.discard()
        mutable.update { it.copy(pending=null,busy=false,transfer=null) }
    }
    private fun launch(block: suspend ()->Unit) {
        if(work?.isActive==true)return
        val owner = ++generation
        work=viewModelScope.launch {
            mutable.update { it.copy(busy=true,message="",transfer=null) }
            try { block() }
            catch(c: CancellationException) { throw c }
            catch(e: Exception) { if(owner==generation)mutable.update { it.copy(message=e.message?.take(180) ?: "操作失败，请重试") } }
            finally { if(owner==generation)mutable.update { it.copy(busy=false,transfer=null) } }
        }
    }
    override fun onCleared() { close(); super.onCleared() }
}
