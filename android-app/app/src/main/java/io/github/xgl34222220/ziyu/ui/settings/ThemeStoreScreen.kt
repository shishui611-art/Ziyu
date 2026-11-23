package io.github.xgl34222220.ziyu.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import java.util.Locale

internal sealed interface ThemeStorePage {
    data object Browse : ThemeStorePage
    data class Detail(val theme: StoreTheme) : ThemeStorePage
    data class Author(
        val authorName: String,
        val authorHash: String,
        val avatarPath: String,
        val source: ThemeSource,
    ) : ThemeStorePage
    data object Downloads : ThemeStorePage
    data object Sources : ThemeStorePage
    data object Preferences : ThemeStorePage
}

private const val PAGE_STEP = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeStoreHost(settings: AppearanceSettings, onDismiss: () -> Unit) {
    val model: ThemeLibraryViewModel = viewModel(key = "ziyu-theme-library")
    val state by model.state.collectAsStateWithLifecycle()
    val pageStack = remember { mutableStateListOf<ThemeStorePage>(ThemeStorePage.Browse) }
    val browseGridState = rememberLazyGridState()

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var visibleLimit by rememberSaveable { mutableIntStateOf(PAGE_STEP) }
    var aspects by remember { mutableStateOf(emptyMap<String, Float>()) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.import(it, settings.uiStyle) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { model.export(settings, it) }
    }

    LaunchedEffect(Unit) {
        model.refreshDownloads()
        if (state.accepted && state.catalog.isEmpty()) model.refresh()
    }
    DisposableEffect(model) { onDispose { model.close() } }

    // 筛选或排序条件变化时重置分页，防止列表跳动。
    LaunchedEffect(query, sort, selectedTags, state.showR18) {
        visibleLimit = PAGE_STEP
    }

    // 当 R18 关闭时移除可能残留的 r18 标签筛选。
    LaunchedEffect(state.showR18) {
        if (!state.showR18 && R18_THEME_TAG in selectedTags) {
            selectedTags = selectedTags - R18_THEME_TAG
        }
    }

    fun push(page: ThemeStorePage) {
        if (pageStack.lastOrNull() != page) pageStack.add(page)
    }

    fun pop() {
        if (pageStack.size > 1) pageStack.removeAt(pageStack.lastIndex) else onDismiss()
    }

    Dialog(
        onDismissRequest = ::pop,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val currentPage = pageStack.lastOrNull() ?: ThemeStorePage.Browse
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .imePadding(),
        ) {
            when (currentPage) {
                ThemeStorePage.Browse -> ThemeStoreBrowseScreen(
                    settings = settings,
                    state = state,
                    model = model,
                    gridState = browseGridState,
                    query = query,
                    onQueryChange = { query = it },
                    sort = sort,
                    onSortChange = { sort = it },
                    selectedTags = selectedTags,
                    onSelectedTagsChange = { selectedTags = it },
                    visibleLimit = visibleLimit,
                    onLoadMore = { visibleLimit += PAGE_STEP },
                    aspects = aspects,
                    onAspect = { key, ratio -> if (aspects[key] != ratio) aspects = aspects + (key to ratio) },
                    onBack = onDismiss,
                    onOpenDetail = { push(ThemeStorePage.Detail(it)) },
                    onOpenDownloads = { push(ThemeStorePage.Downloads) },
                    onOpenSources = { push(ThemeStorePage.Sources) },
                    onOpenPreferences = { push(ThemeStorePage.Preferences) },
                    onImportFile = { importer.launch(arrayOf("*/*")) },
                    onExportTheme = {
                        val safe = settings.themeName.trim().ifBlank { "字域主题" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        exporter.launch("$safe.ziyu-theme")
                    },
                )
                is ThemeStorePage.Detail -> ThemeStoreDetailScreen(
                    theme = currentPage.theme,
                    settings = settings,
                    state = state,
                    model = model,
                    onBack = ::pop,
                    onOpenAuthor = { authorName, authorHash, avatar, source ->
                        push(ThemeStorePage.Author(authorName, authorHash, avatar, source))
                    },
                )
                is ThemeStorePage.Author -> ThemeStoreAuthorScreen(
                    authorName = currentPage.authorName,
                    authorHash = currentPage.authorHash,
                    avatarPath = currentPage.avatarPath,
                    source = currentPage.source,
                    settings = settings,
                    state = state,
                    model = model,
                    aspects = aspects,
                    onAspect = { key, ratio -> if (aspects[key] != ratio) aspects = aspects + (key to ratio) },
                    onBack = ::pop,
                    onOpenDetail = { push(ThemeStorePage.Detail(it)) },
                )
                ThemeStorePage.Downloads -> ThemeStoreDownloadsScreen(
                    settings = settings,
                    state = state,
                    model = model,
                    onBack = ::pop,
                )
                ThemeStorePage.Sources -> ThemeStoreSourcesScreen(
                    state = state,
                    model = model,
                    onBack = ::pop,
                )
                ThemeStorePage.Preferences -> ThemeStorePreferencesScreen(
                    state = state,
                    model = model,
                    onBack = ::pop,
                )
            }
        }

        state.pending?.let { prepared ->
            ThemeImportOptionsDialog(
                prepared = prepared,
                currentSettings = settings,
                busy = state.busy,
                onApply = model::apply,
                onDismiss = model::discard,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeStoreBrowseScreen(
    settings: AppearanceSettings,
    state: ThemeLibraryState,
    model: ThemeLibraryViewModel,
    gridState: LazyGridState,
    query: String,
    onQueryChange: (String) -> Unit,
    sort: Int,
    onSortChange: (Int) -> Unit,
    selectedTags: List<String>,
    onSelectedTagsChange: (List<String>) -> Unit,
    visibleLimit: Int,
    onLoadMore: () -> Unit,
    aspects: Map<String, Float>,
    onAspect: (String, Float) -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (StoreTheme) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenPreferences: () -> Unit,
    onImportFile: () -> Unit,
    onExportTheme: () -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme
    var actionsMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val baseCatalog = remember(state.catalog, state.showR18) {
        if (state.showR18) state.catalog else state.catalog.filterNot { it.isR18Theme() }
    }

    val availableTags = remember(baseCatalog, state.showR18) {
        val counts = baseCatalog.flatMap { it.tags }
            .map(::canonicalThemeTag)
            .filter {
                it.isNotBlank() &&
                    it != ADAPTIVE_THEME_TAG &&
                    it != DYNAMIC_THEME_TAG &&
                    it != R18_THEME_TAG
            }
            .groupingBy { it }.eachCount()
        buildList {
            if (baseCatalog.any { it.isAdaptiveTheme() }) add(ADAPTIVE_THEME_TAG)
            if (baseCatalog.any { it.isDynamicTheme() }) add(DYNAMIC_THEME_TAG)
            if (state.showR18 && baseCatalog.any { it.isR18Theme() }) add(R18_THEME_TAG)
            addAll(counts.keys.sortedWith(compareByDescending<String> { counts[it] }.thenBy { it }).take(18))
        }
    }

    val hasHeat = remember(baseCatalog) { baseCatalog.any { it.heat != null } }
    val sortOptions = remember(hasHeat) {
        buildList {
            add(0 to "默认排序")
            if (hasHeat) add(1 to "热度最高")
            add(2 to "下载最多")
            add(3 to "最近更新")
            add(4 to "名称排序")
            add(5 to "体积最大")
        }
    }

    val filtered = remember(baseCatalog, query, sort, selectedTags) {
        val needle = themeSearchKey(query)
        baseCatalog.filter { theme ->
            val matchQuery = needle.isBlank() || theme.searchBody.contains(needle) || theme.searchCompact.contains(needle)
            val matchTags = selectedTags.isEmpty() || selectedTags.all { selected ->
                when (selected) {
                    ADAPTIVE_THEME_TAG -> theme.isAdaptiveTheme()
                    DYNAMIC_THEME_TAG -> theme.isDynamicTheme()
                    R18_THEME_TAG -> theme.isR18Theme()
                    else -> theme.tags.any { canonicalThemeTag(it) == selected }
                }
            }
            matchQuery && matchTags
        }.let { list ->
            when (sort) {
                1 -> list.sortedWith(compareByDescending<StoreTheme> { it.heat ?: -1L }.thenByDescending { it.downloads })
                2 -> list.sortedByDescending { it.downloads }
                3 -> list.sortedByDescending { it.updated }
                4 -> list.sortedBy { themeSearchKey(it.name) }
                5 -> list.sortedByDescending { it.size }
                else -> list
            }
        }
    }

    val paged = remember(filtered, visibleLimit) { filtered.take(visibleLimit) }
    val hasMore = paged.size < filtered.size
    val downloadedKeys = remember(state.downloads) { state.downloads.map { it.key }.toSet() }
    val multiSource = state.activeSources.size > 1

    // 滚动到接近末尾时自动追加下一页（每次 10 项）。
    LaunchedEffect(gridState, hasMore, paged.size) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (info.totalItemsCount - 3).coerceAtLeast(0)
        }.collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZiyuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, palette.cardBackground, contentColor = palette.textPrimary)
            Spacer(Modifier.weight(1f))
            ZiyuHeaderAction(
                Icons.Rounded.DownloadDone,
                "我的下载",
                onOpenDownloads,
                palette.cardBackground,
                contentColor = palette.textPrimary,
            )
            ZiyuHeaderAction(
                Icons.Rounded.Refresh,
                "刷新",
                model::refresh,
                palette.cardBackground,
                enabled = state.accepted && !state.catalogLoading,
                contentColor = palette.textPrimary,
            )
            Box {
                ZiyuHeaderAction(Icons.Rounded.MoreVert, "更多", { actionsMenu = true }, palette.cardBackground, contentColor = palette.textPrimary)
                DropdownMenu(expanded = actionsMenu, onDismissRequest = { actionsMenu = false }) {
                    DropdownMenuItem(
                        text = { ZiyuText("我的下载（${state.downloads.size}）") },
                        leadingIcon = { ZiyuIcon(Icons.Rounded.DownloadDone, null, Modifier.size(18.dp)) },
                        onClick = { actionsMenu = false; onOpenDownloads() },
                    )
                    DropdownMenuItem(
                        text = { ZiyuText("主题来源与镜像") },
                        leadingIcon = { ZiyuIcon(Icons.Rounded.Dns, null, Modifier.size(18.dp)) },
                        onClick = { actionsMenu = false; onOpenSources() },
                    )
                    DropdownMenuItem(
                        text = { ZiyuText("内容与隐私") },
                        leadingIcon = { ZiyuIcon(Icons.Rounded.PrivacyTip, null, Modifier.size(18.dp)) },
                        onClick = { actionsMenu = false; onOpenPreferences() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { ZiyuText("导出当前主题") },
                        leadingIcon = { ZiyuIcon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp)) },
                        enabled = !state.busy,
                        onClick = { actionsMenu = false; onExportTheme() },
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val canTwoColumns = maxWidth >= 360.dp && LocalConfiguration.current.fontScale <= 1.3f
            val twoColumns = state.twoColumns && canTwoColumns
            PullToRefreshBox(
                isRefreshing = state.catalogLoading,
                onRefresh = { if (state.accepted) model.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (twoColumns) 2 else 1),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                ) {
                    item(key = "store-title", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ZiyuText(
                                text = resolveThemeText(settings.customTexts, ThemeTextKey.STORE),
                                fontSize = 36.sp,
                                lineHeight = 44.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val primaryMirror = state.activeSources.firstOrNull()?.url.orEmpty()
                            if (state.accepted && primaryMirror.isNotBlank()) {
                                ZiyuText(
                                    text = buildString {
                                        append(ThemeStoreDefaults.mirrorLabel(primaryMirror))
                                        if (state.activeSources.size > 1) append(" 等 ${state.activeSources.size} 个来源")
                                        if (filtered.isNotEmpty()) append(" · ${filtered.size} 个主题")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    item(key = "store-trial-banner", span = { GridItemSpan(maxLineSpan) }) {
                        ThemeTrialBanner(modifier = Modifier.fillMaxWidth())
                    }

                    item(key = "store-search", span = { GridItemSpan(maxLineSpan) }) {
                        ZiyuOutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            placeholder = { ZiyuText("搜索主题、作者或标签") },
                            leadingIcon = { ZiyuSearchIconSlot { ZiyuIcon(Icons.Rounded.Search, null) } },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    ZiyuIconButton(onClick = { onQueryChange("") }) {
                                        ZiyuIcon(Icons.Rounded.Close, "清空搜索")
                                    }
                                }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.cardBackground,
                                unfocusedContainerColor = palette.cardBackground,
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = scheme.primary,
                            ),
                        )
                    }

                    if (!state.accepted) {
                        item(key = "store-consent", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(palette.cardBackground)
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ZiyuText("连接主题商店", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                ZiyuText(
                                    "在线主题来自兼容的主题商店镜像。同意后，提供方会收到你的 IP 地址及浏览、下载请求；你可以随时在「内容与隐私」中撤回同意。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textSecondary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ZiyuButton(onClick = model::accept, enabled = !state.busy) { ZiyuText("同意并连接") }
                                    ZiyuOutlinedButton(onClick = onOpenSources) { ZiyuText("查看来源") }
                                }
                            }
                        }
                    }

                    if (state.accepted) {
                        item(key = "store-toolbar", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.weight(1f)) {
                                    val tagLabel = when {
                                        selectedTags.isEmpty() -> "标签：全部"
                                        selectedTags.size == 1 -> "标签：${themeTagDisplayLabel(selectedTags.first())}"
                                        else -> "标签：已选 ${selectedTags.size} 项"
                                    }
                                    Surface(
                                        onClick = { tagMenu = true },
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (selectedTags.isNotEmpty()) scheme.primary.copy(alpha = 0.14f) else palette.cardBackground,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            ZiyuText(
                                                tagLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (selectedTags.isNotEmpty()) scheme.primary else palette.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            ZiyuIcon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp))
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = tagMenu,
                                        onDismissRequest = { tagMenu = false },
                                        modifier = Modifier.heightIn(max = 360.dp),
                                    ) {
                                        DropdownMenuItem(
                                            text = { ZiyuText("全部标签") },
                                            leadingIcon = {
                                                if (selectedTags.isEmpty()) ZiyuIcon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                                            },
                                            onClick = { onSelectedTagsChange(emptyList()); tagMenu = false },
                                        )
                                        HorizontalDivider()
                                        availableTags.forEach { tag ->
                                            val active = tag in selectedTags
                                            DropdownMenuItem(
                                                text = { ZiyuText(themeTagDisplayLabel(tag)) },
                                                leadingIcon = {
                                                    if (active) ZiyuIcon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = scheme.primary)
                                                },
                                                onClick = {
                                                    onSelectedTagsChange(if (active) selectedTags - tag else selectedTags + tag)
                                                },
                                            )
                                        }
                                    }
                                }

                                Box {
                                    val currentSortLabel = sortOptions.firstOrNull { it.first == sort }?.second ?: "默认排序"
                                    Surface(
                                        onClick = { sortMenu = true },
                                        shape = RoundedCornerShape(18.dp),
                                        color = palette.cardBackground,
                                        modifier = Modifier.heightIn(min = 42.dp),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            ZiyuText(currentSortLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                            ZiyuIcon(Icons.Rounded.Sort, null, Modifier.size(16.dp))
                                        }
                                    }
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                        sortOptions.forEach { (id, label) ->
                                            DropdownMenuItem(
                                                text = { ZiyuText(label) },
                                                leadingIcon = {
                                                    if (sort == id) ZiyuIcon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = scheme.primary)
                                                },
                                                onClick = { onSortChange(id); sortMenu = false },
                                            )
                                        }
                                    }
                                }

                                if (canTwoColumns) {
                                    ZiyuHeaderAction(
                                        icon = if (twoColumns) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                                        contentDescription = if (twoColumns) "切换单列" else "切换双列",
                                        onClick = { model.setTwoColumns(!state.twoColumns) },
                                        containerColor = palette.cardBackground,
                                        contentColor = palette.textPrimary,
                                    )
                                }
                            }
                        }
                    }

                    if (state.busy && state.transfer != null) {
                        item(key = "store-progress", span = { GridItemSpan(maxLineSpan) }) {
                            ThemeTransferProgressCard(
                                transfer = state.transfer,
                                isTrial = state.downloadTrial,
                                onCancel = model::cancelTransfer,
                            )
                        }
                    } else if (state.busy) {
                        item(key = "store-busy", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(palette.cardBackground)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    ZiyuText("正在处理主题…", style = MaterialTheme.typography.bodySmall)
                                    ZiyuTextButton(onClick = model::cancelTransfer) { ZiyuText("取消") }
                                }
                            }
                        }
                    }

                    if (state.message.isNotBlank()) {
                        item(key = "store-message", span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (state.isError) scheme.errorContainer.copy(alpha = 0.7f) else palette.cardBackground,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    ZiyuText(
                                        state.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.isError) scheme.onErrorContainer else scheme.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ZiyuIconButton(onClick = model::clearMessage) {
                                        ZiyuIcon(Icons.Rounded.Close, "关闭提示", Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (state.accepted && state.maintenance) {
                        item(key = "store-maintenance", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ZiyuText("主题商店维护中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                ZiyuText("在线目录暂时关闭，已下载的主题仍可在「我的下载」中离线使用。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                                ZiyuOutlinedButton(onClick = onOpenDownloads) { ZiyuText("打开我的下载") }
                            }
                        }
                    } else if (state.accepted && !state.catalogLoading && filtered.isEmpty() && state.catalog.isNotEmpty()) {
                        item(key = "store-empty", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ZiyuText("没有符合条件的主题", color = palette.textSecondary)
                                if (query.isNotEmpty() || selectedTags.isNotEmpty()) {
                                    ZiyuTextButton(onClick = { onQueryChange(""); onSelectedTagsChange(emptyList()) }) {
                                        ZiyuText("清空筛选条件")
                                    }
                                }
                            }
                        }
                    }

                    items(
                        items = paged,
                        key = { "theme-${it.key}" },
                        span = { theme ->
                            GridItemSpan(if (twoColumns && (aspects[theme.key] ?: 0f) > 1f) maxLineSpan else 1)
                        },
                    ) { theme ->
                        val compact = twoColumns && (aspects[theme.key] ?: 0.65f) <= 1f
                        ThemeStorePreviewCard(
                            theme = theme,
                            model = model,
                            compact = compact,
                            enabled = !state.busy,
                            downloading = state.downloadingKey == theme.key,
                            downloaded = theme.key in downloadedKeys,
                            showSourceTag = multiSource && !theme.source.isDefault,
                            onDetails = { onOpenDetail(theme) },
                            onDownload = { model.download(theme, settings.uiStyle, trial = false) },
                            onAspect = { ratio -> onAspect(theme.key, ratio) },
                        )
                    }

                    if (hasMore) {
                        item(key = "store-load-more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                ZiyuOutlinedButton(onClick = onLoadMore) {
                                    ZiyuText("加载更多（剩余 ${filtered.size - paged.size} 个）")
                                }
                            }
                        }
                    }
                }
            }

            ZiyuButton(
                onClick = onImportFile,
                enabled = !state.busy,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                ZiyuIcon(Icons.Rounded.FileOpen, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                ZiyuText("从文件导入")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeStorePreviewCard(
    theme: StoreTheme,
    model: ThemeLibraryViewModel,
    compact: Boolean,
    enabled: Boolean,
    downloading: Boolean,
    downloaded: Boolean,
    showSourceTag: Boolean,
    onDetails: () -> Unit,
    onDownload: () -> Unit,
    onAspect: (Float) -> Unit,
) {
    val ink = if (LocalThemeStoreDark.current) Color.White else Color.Black
    val scrim = if (LocalThemeStoreDark.current) Color.Black else Color.White
    val fontScale = LocalConfiguration.current.fontScale.coerceAtLeast(1f)
    val supported = theme.format in ThemePackages.MIN_SUPPORTED_FORMAT..ThemePackages.MAX_SUPPORTED_FORMAT
    val imagePath = theme.preview.ifBlank { theme.screenshots.firstOrNull().orEmpty() }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = (200 * fontScale).dp)
            .aspectRatio(if (compact) 0.64f else 1.62f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = true, role = Role.Button, onClick = onDetails),
    ) {
        RemoteThemeImage(
            model = model,
            source = theme.source,
            path = imagePath,
            modifier = Modifier.matchParentSize(),
            contentScale = if (compact) ContentScale.Fit else ContentScale.Crop,
            blurBackdropOnFit = compact,
            onAspect = onAspect,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.32f to Color.Transparent,
                    0.68f to scrim.copy(alpha = 0.56f),
                    1.0f to scrim.copy(alpha = 0.94f),
                ),
            ),
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                theme.heat?.let { ThemeStoreMetric(Icons.Rounded.FlashOn, it, "热度") }
                ThemeStoreMetric(Icons.Rounded.FileDownload, theme.downloads, "下载量")
                if (downloaded) {
                    ThemeStoreBadge("已下载", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (theme.isDynamicTheme()) {
                    ThemeStoreBadge(if (compact) "动态" else "动态壁纸", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
                if (theme.isAdaptiveTheme()) {
                    ThemeStoreBadge(if (compact) "自适应" else "自适应主题", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (theme.isR18Theme()) {
                    ThemeStoreBadge("R18", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ZiyuText(
                    theme.name,
                    color = ink,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val date = theme.updated.substringBefore('T').replace('-', '/')
                val meta = buildList {
                    add(theme.author.ifBlank { "未署名" })
                    if (date.isNotBlank()) add(date)
                    if (showSourceTag) add(theme.source.displayName())
                }.joinToString(" · ")
                ZiyuText(meta, color = ink.copy(alpha = 0.86f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (downloading) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
            } else if (compact) {
                ZiyuIconButton(
                    onClick = onDownload,
                    enabled = enabled && supported,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = scrim.copy(alpha = 0.85f), contentColor = ink),
                ) {
                    ZiyuIcon(Icons.Rounded.FileDownload, "下载 ${theme.name}")
                }
            } else {
                ZiyuButton(
                    onClick = onDownload,
                    enabled = enabled && supported,
                    colors = ButtonDefaults.buttonColors(containerColor = scrim.copy(alpha = 0.85f), contentColor = ink),
                    border = BorderStroke(1.dp, ink.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    ZiyuIcon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    ZiyuText(if (downloaded) "应用" else "下载")
                }
            }
        }
    }
}

@Composable
internal fun ThemeTransferProgressCard(
    transfer: ThemeTransfer,
    isTrial: Boolean,
    onCancel: () -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val progress = if (transfer.total > 0L) (transfer.done.toFloat() / transfer.total).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.cardBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ZiyuText(
                if (isTrial) "正在下载试用主题…" else "正在下载主题包…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            ZiyuText(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        val speedText = if (transfer.bytesPerSecond > 0L) " · ${themeBytes(transfer.bytesPerSecond)}/秒" else ""
        val etaText = themeEtaText(transfer.etaSeconds).takeIf { it.isNotBlank() }?.let { " · 剩余 $it" }.orEmpty()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ZiyuText(
                "${themeBytes(transfer.done)} / ${themeBytes(transfer.total)}$speedText$etaText",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZiyuTextButton(onClick = onCancel) { ZiyuText("取消") }
        }
    }
}

@Composable
private fun ThemeStoreMetric(icon: ImageVector, value: Long, label: String) {
    val ink = if (LocalThemeStoreDark.current) Color.White else Color.Black
    val background = if (LocalThemeStoreDark.current) Color.Black else Color.White
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background.copy(alpha = 0.84f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZiyuIcon(icon, label, Modifier.size(13.dp), tint = ink)
        val text = if (value >= 1000L) String.format(Locale.ROOT, "%.1fk", value / 1000.0) else value.toString()
        ZiyuText(text, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
