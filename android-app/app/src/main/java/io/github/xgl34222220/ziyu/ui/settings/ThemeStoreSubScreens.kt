package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction

@Composable
internal fun ThemeStoreAuthorScreen(
    authorName: String,
    authorHash: String,
    avatarPath: String,
    source: ThemeSource,
    settings: AppearanceSettings,
    state: ThemeLibraryState,
    model: ThemeLibraryViewModel,
    aspects: Map<String, Float>,
    onAspect: (String, Float) -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (StoreTheme) -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val works = remember(state.catalog, state.showR18, authorName, authorHash) {
        val visible = if (state.showR18) state.catalog else state.catalog.filterNot { it.isR18Theme() }
        visible.filter { t ->
            if (authorHash.isNotBlank() && t.authorHash.isNotBlank()) t.authorHash == authorHash
            else t.author.equals(authorName, ignoreCase = true)
        }
    }
    val downloadedKeys = remember(state.downloads) { state.downloads.map { it.key }.toSet() }
    val totalDownloads = remember(works) { works.sumOf { it.downloads } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZiyuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, palette.cardBackground, contentColor = palette.textPrimary)
            ZiyuText("作者主页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val twoColumns = state.twoColumns && maxWidth >= 360.dp && LocalConfiguration.current.fontScale <= 1.3f
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (twoColumns) 2 else 1),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item(key = "author-header", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(palette.cardBackground)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ThemeAuthorAvatar(model, source, avatarPath, authorName, 56.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ZiyuText(authorName.ifBlank { "未署名" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ZiyuText("${works.size} 个作品 · 累计下载 $totalDownloads 次", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                        }
                    }
                }

                if (works.isEmpty()) {
                    item(key = "author-empty", span = { GridItemSpan(maxLineSpan) }) {
                        ZiyuText("当前目录下暂无该作者的可见主题。", modifier = Modifier.padding(vertical = 24.dp), color = palette.textSecondary)
                    }
                } else {
                    items(
                        items = works,
                        key = { "author-${it.key}" },
                        span = { theme -> GridItemSpan(if (twoColumns && (aspects[theme.key] ?: 0f) > 1f) maxLineSpan else 1) },
                    ) { theme ->
                        val compact = twoColumns && (aspects[theme.key] ?: 0.65f) <= 1f
                        ThemeStorePreviewCard(
                            theme = theme,
                            model = model,
                            compact = compact,
                            enabled = !state.busy,
                            downloading = state.downloadingKey == theme.key,
                            downloaded = theme.key in downloadedKeys,
                            showSourceTag = state.activeSources.size > 1 && !theme.source.isDefault,
                            onDetails = { onOpenDetail(theme) },
                            onDownload = { model.download(theme, settings.uiStyle, trial = false) },
                            onAspect = { ratio -> onAspect(theme.key, ratio) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ThemeStoreDownloadsScreen(
    settings: AppearanceSettings,
    state: ThemeLibraryState,
    model: ThemeLibraryViewModel,
    onBack: () -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    val selecting = selectedKeys.isNotEmpty()
    val totalBytes = remember(state.downloads) { state.downloads.sumOf { it.size.coerceAtLeast(0L) } }

    LaunchedEffect(Unit) { model.refreshDownloads() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZiyuHeaderAction(
                icon = if (selecting) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = if (selecting) "退出多选" else "返回",
                onClick = { if (selecting) selectedKeys = emptySet() else onBack() },
                containerColor = palette.cardBackground,
                contentColor = palette.textPrimary,
            )
            Column(Modifier.weight(1f)) {
                ZiyuText(
                    if (selecting) "已选择 ${selectedKeys.size} 项" else "我的下载",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!selecting && state.downloads.isNotEmpty()) {
                    ZiyuText(
                        "${state.downloads.size} 个本地主题 · 共 ${themeBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }
            }
            if (selecting) {
                ZiyuHeaderAction(
                    icon = Icons.Rounded.Delete,
                    contentDescription = "删除已选",
                    onClick = { confirmDeleteSelected = true },
                    containerColor = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                )
            } else if (state.downloads.isNotEmpty()) {
                ZiyuTextButton(onClick = { confirmClear = true }, enabled = !state.busy) {
                    ZiyuText("清空")
                }
            }
        }

        if (state.downloads.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    ZiyuIcon(Icons.Rounded.DownloadDone, null, Modifier.size(40.dp), tint = palette.textSecondary)
                    ZiyuText("还没有离线主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    ZiyuText("从商店下载过的主题会自动保存在这里，断网也可随时应用或试用。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "dl-hint") {
                    ZiyuText("长按卡片可批量删除。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
                listItems(state.downloads, key = { it.key }) { record ->
                    val chosen = record.key in selectedKeys
                    val previewPath = remember(record.key, record.previewFile) {
                        model.previewFileForRecord(record)?.absolutePath.orEmpty()
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (chosen) scheme.primary.copy(alpha = 0.14f) else palette.cardBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .combinedClickable(
                                onClick = {
                                    if (selecting) {
                                        selectedKeys = if (chosen) selectedKeys - record.key else selectedKeys + record.key
                                    } else if (!state.busy) {
                                        model.openDownloaded(record, settings.uiStyle, trial = false)
                                    }
                                },
                                onLongClick = {
                                    selectedKeys = if (chosen) selectedKeys - record.key else selectedKeys + record.key
                                },
                            ),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ThemeFileImage(
                                    path = previewPath,
                                    modifier = Modifier.size(width = 64.dp, height = 84.dp),
                                    contentScale = ContentScale.Crop,
                                    maxDimension = 320,
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ZiyuText(
                                        record.name.ifBlank { record.themeId },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    ZiyuText(
                                        listOf(
                                            record.author.ifBlank { "未署名" },
                                            "v${record.version}",
                                            themeBytes(record.size),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (record.dynamic) ThemeStoreBadge("动态壁纸", scheme.primary, scheme.onPrimary)
                                        if (record.adaptive) ThemeStoreBadge("自适应", scheme.primaryContainer, scheme.onPrimaryContainer)
                                        if (record.r18) ThemeStoreBadge("R18", scheme.errorContainer, scheme.onErrorContainer)
                                    }
                                }
                                if (selecting) {
                                    Checkbox(
                                        checked = chosen,
                                        onCheckedChange = {
                                            selectedKeys = if (it) selectedKeys + record.key else selectedKeys - record.key
                                        },
                                    )
                                }
                            }
                            if (!selecting) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ZiyuOutlinedButton(
                                        onClick = { model.openDownloaded(record, settings.uiStyle, trial = true, currentSettings = settings) },
                                        enabled = !state.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { ZiyuText("试用") }
                                    ZiyuButton(
                                        onClick = { model.openDownloaded(record, settings.uiStyle, trial = false) },
                                        enabled = !state.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { ZiyuText("应用") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { ZiyuText("删除已选主题？") },
            text = { ZiyuText("将删除所选的 ${selectedKeys.size} 个本地主题包，不影响已应用的当前外观。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    val keys = selectedKeys
                    selectedKeys = emptySet()
                    confirmDeleteSelected = false
                    model.deleteDownloads(keys)
                }) { ZiyuText("删除") }
            },
            dismissButton = { ZiyuTextButton(onClick = { confirmDeleteSelected = false }) { ZiyuText("取消") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { ZiyuText("清空我的下载？") },
            text = { ZiyuText("将删除全部 ${state.downloads.size} 个离线主题包（共 ${themeBytes(totalBytes)}），不影响当前正在使用的主题。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    confirmClear = false
                    selectedKeys = emptySet()
                    model.clearDownloads()
                }) { ZiyuText("清空") }
            },
            dismissButton = { ZiyuTextButton(onClick = { confirmClear = false }) { ZiyuText("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeStoreSourcesScreen(
    state: ThemeLibraryState,
    model: ThemeLibraryViewModel,
    onBack: () -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme
    val storeSettings = state.storeSettings

    var defaultAuto by remember(storeSettings) { mutableStateOf(storeSettings.defaultAuto) }
    var defaultUrl by remember(storeSettings) { mutableStateOf(storeSettings.defaultUrl) }
    var defaultCode by remember(storeSettings) { mutableStateOf(storeSettings.defaultAccessCode) }
    var defaultError by remember { mutableStateOf("") }

    var editingExtra by remember { mutableStateOf<ThemeSource?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deletingExtra by remember { mutableStateOf<ThemeSource?>(null) }
    var showSpecHint by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { model.clearSourceTest() } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZiyuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, palette.cardBackground, contentColor = palette.textPrimary)
            ZiyuText("主题来源与镜像", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ZiyuTextButton(onClick = { showAddDialog = true }, enabled = !state.busy) { ZiyuText("添加来源") }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 默认来源与镜像选路
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZiyuText("默认来源与镜像", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ZiyuText("自动选路会按地区与网络环境择优连接，并在国内镜像故障时自动切换备用节点。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeStoreChip("自动选路", defaultAuto) {
                        defaultAuto = true
                        defaultUrl = ""
                        defaultError = ""
                    }
                    ThemeStoreDefaults.BASE_URLS.forEach { mirrorUrl ->
                        val selected = !defaultAuto && runCatching {
                            ThemeStoreRepository.normalize(defaultUrl) == ThemeStoreRepository.normalize(mirrorUrl)
                        }.getOrDefault(false)
                        ThemeStoreChip(ThemeStoreDefaults.mirrorLabel(mirrorUrl), selected) {
                            defaultAuto = false
                            defaultUrl = mirrorUrl
                            defaultError = ""
                        }
                    }
                }

                if (!defaultAuto) {
                    ZiyuOutlinedTextField(
                        value = defaultUrl,
                        onValueChange = { defaultUrl = it; defaultError = "" },
                        label = { ZiyuText("HTTPS 地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZiyuOutlinedTextField(
                    value = defaultCode,
                    onValueChange = { defaultCode = it; defaultError = "" },
                    label = { ZiyuText("访问码（默认内置镜像无需填写）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (defaultError.isNotBlank()) {
                    ZiyuText(defaultError, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZiyuButton(
                        onClick = {
                            val check = if (defaultAuto || defaultUrl.isBlank()) Result.success("")
                            else runCatching { ThemeStoreRepository.normalize(defaultUrl) }
                            check.onSuccess {
                                model.saveDefaultSource(defaultAuto, defaultUrl, defaultCode)
                            }.onFailure {
                                defaultError = it.message ?: "地址格式无效"
                            }
                        },
                        enabled = !state.busy,
                    ) { ZiyuText("保存并连接") }

                    ZiyuOutlinedButton(
                        onClick = {
                            val target = state.activeSources.firstOrNull() ?: ThemeSource(ThemeStoreDefaults.BASE_URL)
                            val testUrl = if (defaultAuto || defaultUrl.isBlank()) target.url else defaultUrl
                            runCatching { ThemeStoreRepository.normalize(testUrl) }
                                .onSuccess { norm -> model.testSource(target.copy(url = norm, accessCode = defaultCode.trim())) }
                                .onFailure { defaultError = it.message ?: "地址格式无效" }
                        },
                        enabled = !state.sourceTestBusy,
                    ) { ZiyuText(if (state.sourceTestBusy) "测试中…" else "测试连接") }

                    ZiyuTextButton(
                        onClick = {
                            val target = state.activeSources.firstOrNull() ?: ThemeSource(ThemeStoreDefaults.BASE_URL)
                            model.reconnectSource(target)
                        },
                        enabled = !state.busy,
                    ) { ZiyuText("重新授权") }
                }
            }

            // 测试结果卡片
            state.sourceTest?.let { test ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (test.ok) palette.successContainer.copy(alpha = 0.72f) else scheme.errorContainer.copy(alpha = 0.72f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ZiyuText(
                            if (test.ok) "连接测试通过" else "连接测试失败",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (test.ok) palette.success else scheme.onErrorContainer,
                        )
                        ZiyuText(test.message, style = MaterialTheme.typography.bodySmall, color = palette.textPrimary)
                        test.health?.let { h ->
                            val extras = buildList {
                                h.databaseLatencyMs?.let { add("数据库 ${it} ms") }
                                h.themes?.let { add("在线 $it 个") }
                                h.pending?.let { if (it > 0) add("待审 $it 个") }
                                if (h.version.isNotBlank()) add("服务 ${h.version}")
                            }.joinToString(" · ")
                            if (extras.isNotBlank()) {
                                ZiyuText(extras, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                            }
                        }
                    }
                }
            }

            // 自定义来源列表
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZiyuText("自定义来源（${storeSettings.extraSources.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ZiyuTextButton(onClick = { showAddDialog = true }, enabled = !state.busy) { ZiyuText("新增") }
                }
                if (storeSettings.extraSources.isEmpty()) {
                    ZiyuText("可添加自建或第三方兼容主题仓库，商店会自动合并展示全部来源的主题。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                } else {
                    storeSettings.extraSources.forEachIndexed { idx, extra ->
                        if (idx > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.3f))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ZiyuText(extra.displayName(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ZiyuText(extra.url, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ZiyuOutlinedButton(onClick = { model.testSource(extra) }, enabled = !state.sourceTestBusy) { ZiyuText("测试") }
                                ZiyuTextButton(onClick = { editingExtra = extra }, enabled = !state.busy) { ZiyuText("编辑") }
                                ZiyuTextButton(onClick = { deletingExtra = extra }, enabled = !state.busy) { ZiyuText("删除") }
                            }
                        }
                    }
                }
            }

            // 可折叠接口要求说明
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.cardBackground)
                    .clickable { showSpecHint = !showSpecHint }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ZiyuText("自定义来源兼容说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    ZiyuIcon(if (showSpecHint) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = palette.textSecondary)
                }
                if (showSpecHint) {
                    ZiyuText(
                        "仅支持 HTTPS 地址，目录路径为 catalog.json（可选 /health 健康检查接口）。下载文件强制校验 SHA-256，支持 v1 至 v6 主题包（含自适应、底栏图标与动态壁纸）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }
            }

            if (state.message.isNotBlank()) {
                ZiyuText(state.message, style = MaterialTheme.typography.bodySmall, color = if (state.isError) scheme.error else scheme.primary)
            }
        }
    }

    if (showAddDialog || editingExtra != null) {
        val current = editingExtra
        var name by remember(current) { mutableStateOf(current?.name.orEmpty()) }
        var url by remember(current) { mutableStateOf(current?.url.orEmpty()) }
        var code by remember(current) { mutableStateOf(current?.accessCode.orEmpty()) }
        var err by remember(current) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false; editingExtra = null },
            title = { ZiyuText(if (current == null) "添加自定义来源" else "编辑主题来源") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZiyuOutlinedTextField(name, { name = it }, label = { ZiyuText("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ZiyuOutlinedTextField(url, { url = it; err = "" }, label = { ZiyuText("HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ZiyuOutlinedTextField(
                        code,
                        { code = it; err = "" },
                        label = { ZiyuText("访问码（可选）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (err.isNotBlank()) ZiyuText(err, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
            },
            confirmButton = {
                ZiyuButton(onClick = {
                    runCatching { ThemeStoreRepository.normalize(url) }
                        .onSuccess {
                            model.saveExtraSource(current?.id, name, url, code)
                            showAddDialog = false
                            editingExtra = null
                        }
                        .onFailure { err = it.message ?: "地址格式无效" }
                }) { ZiyuText("保存") }
            },
            dismissButton = { ZiyuTextButton(onClick = { showAddDialog = false; editingExtra = null }) { ZiyuText("取消") } },
        )
    }

    deletingExtra?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingExtra = null },
            title = { ZiyuText("删除自定义来源？") },
            text = { ZiyuText("移除「${target.displayName()}」后，商店不再从该地址加载目录；已下载到本地的主题不受影响。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    model.removeExtraSource(target.id)
                    deletingExtra = null
                }) { ZiyuText("删除") }
            },
            dismissButton = { ZiyuTextButton(onClick = { deletingExtra = null }) { ZiyuText("取消") } },
        )
    }
}

@Composable
internal fun ThemeStorePreferencesScreen(
    state: ThemeLibraryState,
    model: ThemeLibraryViewModel,
    onBack: () -> Unit,
) {
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme
    var confirmRevoke by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZiyuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, palette.cardBackground, contentColor = palette.textPrimary)
            ZiyuText("内容与隐私", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ZiyuText("显示 R18 标记主题", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        ZiyuText("默认隐藏；开启后会在商店目录和作者页中显示带 R18 标记的主题。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    ZiyuSwitch(
                        checked = state.showR18,
                        onCheckedChange = model::setShowR18,
                        enabled = state.accepted && !state.busy,
                    )
                }
                if (!state.accepted) {
                    ZiyuText("请先在商店首页同意连接主题来源后再调整此项。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
            }

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.cardBackground).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZiyuText("连接与隐私说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ZiyuText(
                    "字域主题商店保持免登录运行，不收集账号、评论或个人资料。连接在线目录与下载主题时，所选镜像服务器会收到你的 IP 地址、请求路径与设备授权令牌（用于接口限流）。已下载的主题包与预览图保存在本机应用目录，可在「我的下载」中随时清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                if (state.accepted) {
                    ZiyuOutlinedButton(
                        onClick = { confirmRevoke = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("撤回连接同意") }
                } else {
                    ZiyuButton(
                        onClick = model::accept,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ZiyuText("重新同意并连接") }
                }
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { ZiyuText("撤回主题商店连接同意？") },
            text = { ZiyuText("撤回后将停止访问在线主题来源并关闭 R18 显示；已下载到本地的主题和当前外观不受影响。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    confirmRevoke = false
                    model.revokeConsent()
                }) { ZiyuText("撤回") }
            },
            dismissButton = { ZiyuTextButton(onClick = { confirmRevoke = false }) { ZiyuText("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeImportOptionsDialog(
    prepared: PreparedTheme,
    currentSettings: AppearanceSettings,
    busy: Boolean,
    onApply: (ThemeImportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var appearanceMode by remember(prepared) { mutableStateOf(prepared.settings.themeMode) }
    var applyColorsAndStyle by remember(prepared) { mutableStateOf(true) }
    var applyBackground by remember(prepared) { mutableStateOf(prepared.hasBackground) }
    val packageHasLiveWallpaper = prepared.hasDynamicWallpaper && !prepared.explicitClearDynamicWallpaper
    val canClearExistingLiveWallpaper = !packageHasLiveWallpaper && currentSettings.dynamicWallpaperPath.isNotBlank()
    var applyDynamicWallpaper by remember(prepared) {
        mutableStateOf(packageHasLiveWallpaper || canClearExistingLiveWallpaper || prepared.explicitClearDynamicWallpaper)
    }
    var applyHomeCard by remember(prepared) { mutableStateOf(prepared.hasCardBackground) }
    val packageHasLiveCard = prepared.hasDynamicHomeCard && !prepared.explicitClearDynamicHomeCard
    val canClearExistingLiveCard = !packageHasLiveCard && currentSettings.dynamicHomeCardPath.isNotBlank()
    var applyDynamicHomeCard by remember(prepared) {
        mutableStateOf(packageHasLiveCard || canClearExistingLiveCard || prepared.explicitClearDynamicHomeCard)
    }
    var applyStickers by remember(prepared) { mutableStateOf(prepared.hasStickers) }
    var applyBottomBarIcons by remember(prepared) { mutableStateOf(prepared.hasBottomBarIcons) }
    var applyAppIcon by remember(prepared) { mutableStateOf(prepared.hasAppIcon) }
    var textGroups by remember(prepared) { mutableStateOf(prepared.textGroups) }

    val previewSettings = remember(
        prepared, appearanceMode, applyColorsAndStyle, applyBackground, applyDynamicWallpaper,
        applyHomeCard, applyStickers, textGroups, currentSettings,
    ) {
        val base = if (applyColorsAndStyle) prepared.settings else currentSettings
        val mergedTexts = if (textGroups.isEmpty()) currentSettings.customTexts else {
            currentSettings.customTexts.filterKeys { ThemeTextKey.forKey(it)?.group !in textGroups } +
                prepared.settings.customTexts.filterKeys { ThemeTextKey.forKey(it)?.group in textGroups }
        }
        base.copy(
            themeMode = appearanceMode,
            backgroundPath = if (applyBackground) prepared.settings.backgroundPath else currentSettings.backgroundPath,
            dynamicWallpaperPath = if (applyDynamicWallpaper) prepared.settings.dynamicWallpaperPath else currentSettings.dynamicWallpaperPath,
            homeCard = if (applyHomeCard) prepared.settings.homeCard.copy(
                stickers = if (applyStickers) prepared.settings.homeCard.stickers else currentSettings.homeCard.stickers,
            ) else currentSettings.homeCard.copy(
                stickers = if (applyStickers) prepared.settings.homeCard.stickers else currentSettings.homeCard.stickers,
            ),
            customTexts = mergedTexts,
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { ZiyuText("应用主题") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ZiyuText(prepared.settings.themeName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val meta = listOf(
                        prepared.settings.themeAuthor,
                        "格式 v${prepared.formatVersion}",
                        if (prepared.isAdaptive) "浅深自适应" else "",
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) {
                        ZiyuText(meta, style = MaterialTheme.typography.bodySmall, color = LocalZiyuPaletteTokens.current.textSecondary)
                    }
                }

                ThemeAppearancePreview(previewSettings)

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listItems(ThemeMode.entries) { mode ->
                        ThemeStoreChip(mode.label, appearanceMode == mode) { appearanceMode = mode }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ZiyuText("选择要应用的内容", style = MaterialTheme.typography.labelLarge)

                ImportOptionRow("配色与界面风格", null, applyColorsAndStyle, !busy) { applyColorsAndStyle = it }
                if (prepared.hasBackground) {
                    ImportOptionRow("背景图片", null, applyBackground, !busy) { applyBackground = it }
                }
                if (packageHasLiveWallpaper) {
                    ImportOptionRow("动态壁纸", "应用主题内置的动态视频或动图背景", applyDynamicWallpaper, !busy) { applyDynamicWallpaper = it }
                } else if (canClearExistingLiveWallpaper) {
                    ImportOptionRow("清除当前动态壁纸", "关闭现有动态壁纸以展示新主题背景", applyDynamicWallpaper, !busy) { applyDynamicWallpaper = it }
                }
                if (prepared.hasCardBackground) {
                    ImportOptionRow("首页卡片背景", null, applyHomeCard, !busy) { applyHomeCard = it }
                }
                if (packageHasLiveCard) {
                    ImportOptionRow("首页动态卡片", "应用主题内置的首页动态卡片背景", applyDynamicHomeCard, !busy) { applyDynamicHomeCard = it }
                } else if (canClearExistingLiveCard) {
                    ImportOptionRow("清除当前首页动态卡片", null, applyDynamicHomeCard, !busy) { applyDynamicHomeCard = it }
                }
                if (prepared.hasStickers) {
                    ImportOptionRow("首页贴纸", null, applyStickers, !busy) { applyStickers = it }
                }
                if (prepared.hasBottomBarIcons) {
                    ImportOptionRow("底部导航栏图标", "替换底部导航栏的自定义图标", applyBottomBarIcons, !busy) { applyBottomBarIcons = it }
                }
                if (prepared.hasAppIcon) {
                    val iconLabel = BuiltinAppIcons.find(prepared.settings.appIconId).label
                    ImportOptionRow("应用桌面图标（$iconLabel）", "同时切换桌面启动图标", applyAppIcon, !busy) { applyAppIcon = it }
                }

                if (prepared.textGroups.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ZiyuText("自定义文字分组", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("首页", "页面", "设置").filter { it in prepared.textGroups }.forEach { group ->
                                val selected = group in textGroups
                                ThemeStoreChip(group, selected) {
                                    textGroups = if (selected) textGroups - group else textGroups + group
                                }
                            }
                        }
                    }
                }

                prepared.warnings.forEach { warn ->
                    ZiyuText(warn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (prepared.settings.themeLicense.isNotBlank()) {
                    ZiyuText("许可：${prepared.settings.themeLicense}", style = MaterialTheme.typography.bodySmall, color = LocalZiyuPaletteTokens.current.textSecondary)
                }
            }
        },
        confirmButton = {
            ZiyuButton(
                onClick = {
                    onApply(
                        ThemeImportOptions(
                            applyColorsAndStyle = applyColorsAndStyle,
                            applyBackground = applyBackground,
                            applyDynamicWallpaper = applyDynamicWallpaper,
                            applyHomeCard = applyHomeCard,
                            applyDynamicHomeCard = applyDynamicHomeCard,
                            applyStickers = applyStickers,
                            applyBottomBarIcons = applyBottomBarIcons,
                            applyAppIcon = applyAppIcon,
                            textGroups = textGroups,
                            themeMode = appearanceMode,
                        ),
                    )
                },
                enabled = !busy,
            ) { ZiyuText(if (busy) "正在应用…" else "应用") }
        },
        dismissButton = {
            ZiyuTextButton(onClick = onDismiss, enabled = !busy) { ZiyuText("取消") }
        },
    )
}

@Composable
private fun ImportOptionRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZiyuText(title, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                ZiyuText(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalZiyuPaletteTokens.current.textSecondary)
            }
        }
        Spacer(Modifier.width(10.dp))
        ZiyuSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
