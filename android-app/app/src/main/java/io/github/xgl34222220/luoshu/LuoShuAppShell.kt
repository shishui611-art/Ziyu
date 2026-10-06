package io.github.xgl34222220.luoshu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar as MaterialNavigationBar
import androidx.compose.material3.NavigationBarItem as MaterialNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.luoshu.ui.appearance.AppearanceSettings
import io.github.xgl34222220.luoshu.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.luoshu.ui.appearance.UiStyle
import io.github.suqi8.coui.kmp.basic.NavigationBar as CouiNavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem as CouiNavigationBarItem
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.xgl34222220.luoshu.ui.dialogs.FontActionDialogRoute
import io.github.xgl34222220.luoshu.ui.dialogs.FontActionKind
import io.github.xgl34222220.luoshu.ui.dialogs.FontPickerDialogRoute
import io.github.xgl34222220.luoshu.ui.font.fontNormalizedWeight
import io.github.xgl34222220.luoshu.ui.font.selectedFontId
import io.github.xgl34222220.luoshu.ui.home.HomeActions
import io.github.xgl34222220.luoshu.ui.home.HomeRoute
import io.github.xgl34222220.luoshu.ui.home.toHomeUiState
import io.github.xgl34222220.luoshu.ui.library.FontLibraryActions
import io.github.xgl34222220.luoshu.ui.library.FontLibraryRoute
import io.github.xgl34222220.luoshu.ui.library.toFontLibraryUiState
import io.github.xgl34222220.luoshu.ui.logs.LogsActions
import io.github.xgl34222220.luoshu.ui.logs.LogsRoute
import io.github.xgl34222220.luoshu.ui.logs.toLogsUiState
import io.github.xgl34222220.luoshu.ui.navigation.kernelsu.FloatingBottomBar
import io.github.xgl34222220.luoshu.ui.navigation.kernelsu.FloatingBottomBarItem
import io.github.xgl34222220.luoshu.ui.settings.AppearanceActions
import io.github.xgl34222220.luoshu.ui.settings.AppearanceSettingsRoute
import io.github.xgl34222220.luoshu.ui.studio.FontStudioActions
import io.github.xgl34222220.luoshu.ui.studio.FontStudioRoute
import io.github.xgl34222220.luoshu.ui.studio.toFontStudioUiState
import io.github.xgl34222220.luoshu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.luoshu.ui.theme.LocalMiuixTokens
import io.github.xgl34222220.luoshu.ui.theme.LuoShuTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class AppPage(
    val label: String,
    val icon: ImageVector,
    val dockOpticalScale: Float = 1f,
) {
    Home("首页", Icons.Rounded.Home, .94f),
    Library("字体库", Icons.Rounded.ListAlt, 1.00f),
    Studio("组合", Icons.Rounded.Layers, .96f),
    Logs("任务", Icons.Rounded.Description, .96f),
    Settings("设置", Icons.Rounded.Settings, .94f),
}

private val dockPages = listOf(
    AppPage.Home,
    AppPage.Library,
    AppPage.Studio,
    AppPage.Settings,
)

private fun AppPage.motionIndex(): Int = when (this) {
    AppPage.Home -> 0
    AppPage.Library -> 1
    AppPage.Studio -> 2
    AppPage.Settings -> 3
    AppPage.Logs -> 4
}

@Composable
internal fun LuoShuAppShell(
    viewModel: LuoShuViewModel,
    features: Alpha15FeatureViewModel,
    appearanceViewModel: AppearanceViewModel,
) {
    val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(AppPage.Home) }
    var previousPageForMotion by remember { mutableStateOf(AppPage.Home) }
    var settingsDetailVisible by rememberSaveable { mutableStateOf(false) }
    var logsReturnPage by rememberSaveable { mutableStateOf(AppPage.Home) }
    var pendingApply by remember { mutableStateOf<FontItem?>(null) }
    var pendingDelete by remember { mutableStateOf<FontItem?>(null) }
    var restoreDefault by remember { mutableStateOf(false) }
    var pickerSlot by remember { mutableStateOf<MixSlot?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    LaunchedEffect(viewModel.snapshot.installed, viewModel.snapshot.rootGranted) {
        if (viewModel.snapshot.installed && viewModel.snapshot.rootGranted) {
            features.refreshSystemWeight()
        }
    }
    LaunchedEffect(page) {
        when (page) {
            AppPage.Home -> Unit
            AppPage.Library -> viewModel.ensureFonts()
            AppPage.Studio -> {
                viewModel.ensureFonts()
                viewModel.refreshMixConfig()
            }
            AppPage.Logs -> viewModel.refreshLogs()
            AppPage.Settings -> Unit
        }
    }
    LaunchedEffect(page) {
        if (page != AppPage.Settings) settingsDetailVisible = false
    }
    BackHandler(
        enabled = page != AppPage.Home && !(page == AppPage.Settings && settingsDetailVisible),
    ) { page = if (page == AppPage.Logs) logsReturnPage else AppPage.Home }

    val homeActions = remember(viewModel, features) {
        HomeActions(
            refresh = {
                viewModel.refresh()
                if (viewModel.snapshot.installed && viewModel.snapshot.rootGranted) {
                    features.refreshSystemWeight()
                }
            },
            openFontLibrary = { page = AppPage.Library },
            openFontStudio = { page = AppPage.Studio },
            openLogs = {
                logsReturnPage = AppPage.Home
                page = AppPage.Logs
            },
            openSettings = { page = AppPage.Settings },
            restoreDefault = { restoreDefault = true },
            reboot = viewModel::rebootDevice,
            previewSystemWeight = features::previewSystemWeight,
            resetSystemWeight = features::resetSystemWeight,
        )
    }
    val libraryActions = remember(viewModel) {
        FontLibraryActions(
            refresh = { viewModel.refreshFonts(force = true) },
            setQuery = viewModel::setSearchQuery,
            apply = {
                pendingApply = it
                viewModel.prewarmFont(it.id)
            },
            delete = { pendingDelete = it },
            restoreDefault = { restoreDefault = true },
        )
    }
    val studioActions = remember(viewModel, features) {
        FontStudioActions(
            refresh = viewModel::refreshMixConfig,
            pickSlot = { pickerSlot = it },
            updateWeight = viewModel::updateMixWeight,
            updateAxis = viewModel::updateMixAxis,
            inspectCoverage = features::inspectCoverage,
            startMix = viewModel::startMix,
            applyDirect = viewModel::applyFont,
        )
    }
    val logsActions = remember(viewModel) { LogsActions(refresh = viewModel::refreshLogs) }
    val appearanceActions = remember(appearanceViewModel) {
        AppearanceActions(
            setUiStyle = appearanceViewModel::setUiStyle,
            setThemeMode = appearanceViewModel::setThemeMode,
            setSeedArgb = appearanceViewModel::setSeedArgb,
            setKolorStyle = appearanceViewModel::setKolorStyle,
            setMonetEnabled = appearanceViewModel::setMonetEnabled,
            setAmoledBlack = appearanceViewModel::setAmoledBlack,
            setBlurEnabled = appearanceViewModel::setBlurEnabled,
            setGlassEnabled = appearanceViewModel::setGlassEnabled,
            setFloatingDock = appearanceViewModel::setFloatingDock,
            setHighRefreshRate = appearanceViewModel::setHighRefreshRate,
        )
    }
    val validFonts = remember(viewModel.fonts) { viewModel.fonts.filter { it.valid } }

    LuoShuTheme(appearance) {
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        val showDock = page != AppPage.Logs && !(page == AppPage.Settings && settingsDetailVisible)
        val quickReturnEnabled = appearance.uiStyle == UiStyle.MIUIX && appearance.floatingDock &&
            showDock &&
            page in listOf(AppPage.Library, AppPage.Studio, AppPage.Settings)
        var dockHiddenByScroll by remember(page) { mutableStateOf(false) }
        var dockScrollAccumulator by remember(page) { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val dockHideThresholdPx = with(density) { 34.dp.toPx() }
        val dockShowThresholdPx = with(density) { 20.dp.toPx() }
        val dockScrollConnection = remember(page, quickReturnEnabled, dockHideThresholdPx, dockShowThresholdPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!quickReturnEnabled) return Offset.Zero
                    when {
                        available.y < -1f -> {
                            if (dockScrollAccumulator < 0f) dockScrollAccumulator = 0f
                            dockScrollAccumulator += -available.y
                            if (dockScrollAccumulator >= dockHideThresholdPx) {
                                dockHiddenByScroll = true
                                dockScrollAccumulator = 0f
                            }
                        }
                        available.y > 1f -> {
                            if (dockScrollAccumulator > 0f) dockScrollAccumulator = 0f
                            dockScrollAccumulator -= available.y
                            if (-dockScrollAccumulator >= dockShowThresholdPx) {
                                dockHiddenByScroll = false
                                dockScrollAccumulator = 0f
                            }
                        }
                    }
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(quickReturnEnabled, showDock) {
            if (!quickReturnEnabled || !showDock) {
                dockHiddenByScroll = false
                dockScrollAccumulator = 0f
            }
        }
        val dockActuallyVisible = showDock && !dockHiddenByScroll
        val blurActive = appearance.uiStyle == UiStyle.MIUIX &&
            appearance.floatingDock &&
            appearance.blurEnabled &&
            appearance.glassEnabled &&
            showDock
        val liquidBackdrop = rememberLayerBackdrop()
        val liquidGlassSupported = blurActive &&
            appearance.uiStyle == UiStyle.MIUIX &&
            isRuntimeShaderSupported()
        val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Every primary page owns its scrollable bottom clearance. Keep one inset source so
        // switching glass, floating, gesture and three-button modes never stacks blank space.
        val dockClearance = 0.dp
        val dockPaddingTarget = if (showDock) {
            navigationBottom + when {
                dockHiddenByScroll -> 28.dp
                appearance.uiStyle == UiStyle.MATERIAL -> 80.dp
                appearance.floatingDock -> 72.dp
                else -> 56.dp
            }
        } else 0.dp
        val dockContentPadding by animateDpAsState(
            targetValue = dockPaddingTarget,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "dockContentPadding",
        )
        val contentModifier = Modifier
            .fillMaxSize()
            .then(if (quickReturnEnabled) Modifier.nestedScroll(dockScrollConnection) else Modifier)
            .then(if (liquidGlassSupported) Modifier.layerBackdrop(liquidBackdrop) else Modifier)

        Box(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
            ),
        ) {
            Box(modifier = contentModifier) {
                AppBackdrop(appearance, dark)
                // Only the destination page participates in the transition. AnimatedContent kept
                // the outgoing page alive for 210–360 ms; the backdrop shader then refracted that
                // stale layer through the dock, producing the one-frame/old-page flash in recordings.
                key(page) {
                    val pageDirection = remember(page) {
                        val delta = page.motionIndex() - previousPageForMotion.motionIndex()
                        when {
                            delta > 0 -> 1f
                            delta < 0 -> -1f
                            else -> 0f
                        }
                    }
                    val pageEnter = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        pageEnter.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(210, easing = FastOutSlowInEasing),
                        )
                        previousPageForMotion = page
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = .94f + (.06f * pageEnter.value)
                                translationX = (1f - pageEnter.value) * 14.dp.toPx() * pageDirection
                            },
                    ) {
                    when (page) {
                        AppPage.Home -> Box(
                            modifier = Modifier.fillMaxSize().padding(bottom = dockClearance),
                        ) {
                            CompositionLocalProvider(LocalDockContentPadding provides dockContentPadding) {
                                HomeRoute(
                                    style = appearance.uiStyle,
                                    state = viewModel.snapshot.toHomeUiState(features.systemWeight),
                                    actions = homeActions,
                                )
                            }
                        }
                        AppPage.Library -> Box(
                            modifier = Modifier.fillMaxSize().padding(bottom = dockClearance),
                        ) {
                            CompositionLocalProvider(LocalDockContentPadding provides dockContentPadding) {
                                FontLibraryRoute(
                                    style = appearance.uiStyle,
                                    state = viewModel.toFontLibraryUiState(),
                                    actions = libraryActions,
                                    topActions = {
                                        NativeImportOverlay(
                                            viewModel = viewModel,
                                            style = appearance.uiStyle,
                                            modifier = Modifier.fillMaxWidth(),
                                            embedded = true,
                                        )
                                    },
                                )
                            }
                        }
                        AppPage.Studio -> Box(
                            modifier = Modifier.fillMaxSize().padding(bottom = dockClearance),
                        ) {
                            CompositionLocalProvider(LocalDockContentPadding provides dockContentPadding) {
                                FontStudioRoute(
                                    style = appearance.uiStyle,
                                    state = viewModel.toFontStudioUiState(features),
                                    actions = studioActions,
                                )
                            }
                        }
                        AppPage.Logs -> {
                            val detailShape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = if (appearance.uiStyle == UiStyle.MIUIX) 6.dp else 0.dp)
                                    .then(
                                        if (appearance.uiStyle == UiStyle.MIUIX) {
                                            Modifier
                                                .shadow(22.dp, detailShape, clip = false)
                                                .clip(detailShape)
                                                .background(LocalMiuixTokens.current.pageBackground)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(bottom = dockClearance),
                            ) {
                                CompositionLocalProvider(LocalDockContentPadding provides dockContentPadding) {
                                    LogsRoute(
                                        style = appearance.uiStyle,
                                        state = viewModel.toLogsUiState(),
                                        actions = logsActions,
                                        onBack = { page = logsReturnPage },
                                    )
                                }
                            }
                        }
                        AppPage.Settings -> Box(
                            modifier = Modifier.fillMaxSize().padding(bottom = dockClearance),
                        ) {
                            CompositionLocalProvider(LocalDockContentPadding provides dockContentPadding) {
                                AppearanceSettingsRoute(
                                    settings = appearance,
                                    actions = appearanceActions,
                                    onOpenTasks = {
                                        logsReturnPage = AppPage.Settings
                                        page = AppPage.Logs
                                    },
                                    onDetailChanged = { settingsDetailVisible = it },
                                )
                            }
                        }
                    }
                    }
                }
            }

            val dockPage = if (page in dockPages) page else logsReturnPage
            AnimatedVisibility(
                visible = dockActuallyVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(210, easing = FastOutSlowInEasing)) { it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(190, easing = FastOutSlowInEasing)) { it },
            ) {
                when (appearance.uiStyle) {
                    UiStyle.MATERIAL -> MaterialAppDock(
                        current = dockPage,
                        onSelect = { page = it },
                    )
                    UiStyle.MIUIX -> MiuixAppDock(
                        current = dockPage,
                        onSelect = { page = it },
                        appearance = appearance,
                        backdrop = liquidBackdrop,
                    )
                    UiStyle.COUI -> CouiAppDock(
                        current = dockPage,
                        onSelect = { page = it },
                        appearance = appearance,
                    )
                }
            }

        }

        pendingApply?.let { font ->
            FontActionDialogRoute(
                style = appearance.uiStyle,
                kind = FontActionKind.APPLY,
                message = if (font.supportsCjk) {
                    "直接应用「${font.name}」。准备完成后需要完整重启手机。"
                } else {
                    "「${font.name}」不包含完整中文字形。直接应用后中文会继续使用系统默认字体，看起来可能没有变化；建议在组合页把它作为英文字体使用。"
                },
                onDismiss = { pendingApply = null },
                onConfirm = {
                    pendingApply = null
                    viewModel.applyFont(font.id)
                },
            )
        }

        pendingDelete?.let { font ->
            FontActionDialogRoute(
                style = appearance.uiStyle,
                kind = FontActionKind.DELETE,
                message = "确定删除「${font.name}」吗？字体文件和相关缓存将一并移除。",
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    viewModel.deleteFont(font.id)
                },
            )
        }

        if (restoreDefault) {
            FontActionDialogRoute(
                style = appearance.uiStyle,
                kind = FontActionKind.RESTORE,
                message = "恢复 ROM 自带字体映射。完成后需要完整重启手机。",
                onDismiss = { restoreDefault = false },
                onConfirm = {
                    restoreDefault = false
                    viewModel.applyFont("default")
                },
            )
        }

        pickerSlot?.let { slot ->
            FontPickerDialogRoute(
                style = appearance.uiStyle,
                slot = slot,
                fonts = validFonts,
                selected = selectedFontId(viewModel.mixState, slot),
                onDismiss = { pickerSlot = null },
                onChoose = { font ->
                    viewModel.updateMixFont(slot, font.id)
                    viewModel.updateMixWeight(
                        slot,
                        fontNormalizedWeight(
                            font,
                            when (slot) {
                                MixSlot.Cjk -> viewModel.mixState.cjkWeight
                                MixSlot.Latin -> viewModel.mixState.latinWeight
                                MixSlot.Digit -> viewModel.mixState.digitWeight
                            },
                        ),
                    )
                    pickerSlot = null
                },
            )
        }
    }
}

@Composable
private fun AppBackdrop(appearance: AppearanceSettings, dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val miuix = appearance.uiStyle == UiStyle.MIUIX
    val base = when {
        miuix -> listOf(LocalMiuixTokens.current.pageBackground, LocalMiuixTokens.current.pageBackground)
        dark -> listOf(scheme.background, scheme.surfaceContainerLow, scheme.background)
        else -> listOf(scheme.background, scheme.surfaceContainerLowest, scheme.background)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(base))
            .drawBehind {
                if (miuix && dark) return@drawBehind
                if (miuix) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(scheme.primary.copy(alpha = if (dark) .09f else .10f), Color.Transparent),
                            center = Offset(size.width * .92f, size.height * .02f),
                            radius = size.width * .85f,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(scheme.secondary.copy(alpha = if (dark) .06f else .07f), Color.Transparent),
                            center = Offset(size.width * .04f, size.height * .82f),
                            radius = size.width,
                        ),
                    )
                } else {
                    drawRect(
                        Brush.radialGradient(
                            listOf(scheme.secondary.copy(alpha = if (dark) .13f else .20f), Color.Transparent),
                            center = Offset(size.width * .9f, size.height * .06f),
                            radius = size.width * .72f,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(scheme.primary.copy(alpha = if (dark) .10f else .16f), Color.Transparent),
                            center = Offset(size.width * .02f, size.height * .54f),
                            radius = size.width * .82f,
                        ),
                    )
                }
            },
    )
}

@Composable
private fun MaterialAppDock(
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialNavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
    ) {
        dockPages.forEach { destination ->
            val selected = current == destination
            // NavigationBarItem supplies its own RowScope weight; an extra weight modifier
            // constrains the item twice and can hide the remaining destinations.
            MaterialNavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun MiuixAppDock(
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    appearance: AppearanceSettings,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    if (appearance.floatingDock) {
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding = if (bottomInset != 0.dp) 8.dp + bottomInset else 28.dp
        FloatingBottomBar(
            modifier = modifier
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
            selectedIndex = dockPages.indexOf(current).coerceIn(dockPages.indices),
            onSelected = { index -> dockPages.getOrNull(index)?.let(onSelect) },
            backdrop = backdrop,
            tabsCount = dockPages.size,
            isBlurEnabled = appearance.glassEnabled && appearance.blurEnabled,
        ) { activateTab ->
            dockPages.forEachIndexed { index, destination ->
                FloatingBottomBarItem(
                    selected = current == destination,
                    onClick = { activateTab(index) },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                ) {
                    Icon(destination.icon, contentDescription = null)
                    Text(
                        text = destination.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    } else {
        MaterialNavigationBar(
            modifier = modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            dockPages.forEach { destination ->
                val selected = current == destination
                MaterialNavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) onSelect(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(destination.label, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun CouiAppDock(
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    appearance: AppearanceSettings,
    modifier: Modifier = Modifier,
) {
    if (appearance.floatingDock) {
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding = if (bottomInset != 0.dp) 8.dp + bottomInset else 28.dp
        Surface(
            modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = bottomPadding),
            shape = RoundedCornerShape(28.dp),
            color = COUITheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp,
        ) {
            CouiNavigationBar(
                color = Color.Transparent,
                showDivider = false,
                defaultWindowInsetsPadding = false,
            ) {
                dockPages.forEach { destination ->
                    val selected = current == destination
                    CouiNavigationBarItem(
                        selected = selected,
                        onClick = { if (!selected) onSelect(destination) },
                        icon = destination.icon,
                        label = destination.label,
                    )
                }
            }
        }
    } else {
        CouiNavigationBar(
            modifier = modifier.fillMaxWidth(),
            color = COUITheme.colorScheme.surfaceContainer,
            defaultWindowInsetsPadding = true,
        ) {
            dockPages.forEach { destination ->
                val selected = current == destination
                CouiNavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) onSelect(destination) },
                    icon = destination.icon,
                    label = destination.label,
                )
            }
        }
    }
}
