package io.github.xgl34222220.ziyu

import android.os.Build
import io.github.xgl34222220.ziyu.ui.navigation.rememberPredictiveBack
import io.github.xgl34222220.ziyu.ui.navigation.predictiveBackMotion
import io.github.xgl34222220.ziyu.ui.navigation.predictiveBackPreviewMotion
import io.github.xgl34222220.ziyu.ui.navigation.backDestinationPreview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import io.github.xgl34222220.ziyu.ui.theme.ZiyuMotionTokens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar as MaterialNavigationBar
import androidx.compose.material3.NavigationBarItem as MaterialNavigationBarItem
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xgl34222220.ziyu.ui.settings.SystemCenterViewModel
import io.github.xgl34222220.ziyu.ui.settings.ModuleUpdateDialogRoute
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceSettings
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.suqi8.coui.kmp.basic.NavigationBar as CouiNavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem as CouiNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.xgl34222220.ziyu.ui.dialogs.FontActionDialogRoute
import io.github.xgl34222220.ziyu.ui.dialogs.FontActionKind
import io.github.xgl34222220.ziyu.ui.dialogs.FontPickerDialogRoute
import io.github.xgl34222220.ziyu.ui.font.fontNormalizedWeight
import io.github.xgl34222220.ziyu.ui.font.selectedFontId
import io.github.xgl34222220.ziyu.ui.home.HomeActions
import io.github.xgl34222220.ziyu.ui.home.HomeRoute
import io.github.xgl34222220.ziyu.ui.home.toHomeUiState
import io.github.xgl34222220.ziyu.ui.library.FontLibraryActions
import io.github.xgl34222220.ziyu.ui.library.FontLibraryRoute
import io.github.xgl34222220.ziyu.ui.library.toFontLibraryUiState
import io.github.xgl34222220.ziyu.ui.logs.LogsActions
import io.github.xgl34222220.ziyu.ui.logs.LogsRoute
import io.github.xgl34222220.ziyu.ui.logs.toLogsUiState
import io.github.xgl34222220.ziyu.ui.navigation.kernelsu.FloatingBottomBar
import io.github.xgl34222220.ziyu.ui.navigation.kernelsu.FloatingBottomBarItem
import io.github.xgl34222220.ziyu.ui.settings.AppearanceActions
import io.github.xgl34222220.ziyu.ui.settings.AppearanceSettingsRoute
import io.github.xgl34222220.ziyu.ui.studio.FontStudioActions
import io.github.xgl34222220.ziyu.ui.studio.FontStudioRoute
import io.github.xgl34222220.ziyu.ui.studio.toFontStudioUiState
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTheme
import io.github.xgl34222220.ziyu.ui.theme.ZiyuGlassDockSurface
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
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

@Composable
internal fun ZiyuAppShell(
    viewModel: ZiyuViewModel,
    features: Alpha15FeatureViewModel,
    appearanceViewModel: AppearanceViewModel,
) {
    val systemCenter: SystemCenterViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, systemCenter) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) systemCenter.enterApp()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) systemCenter.enterApp()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(AppPage.Home) }
    var settingsDetailVisible by rememberSaveable { mutableStateOf(false) }
    var logsReturnPage by rememberSaveable { mutableStateOf(AppPage.Home) }
    var pendingApply by remember { mutableStateOf<FontItem?>(null) }
    var pendingDelete by remember { mutableStateOf<FontItem?>(null) }
    var restoreDefault by remember { mutableStateOf(false) }
    var pickerSlot by remember { mutableStateOf<MixSlot?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
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

    val homeActions = remember(viewModel, features) {
        HomeActions(
            refresh = {
                viewModel.refresh()
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
            mountSettings = viewModel::loadMountPreferences,
            setMountBackend = viewModel::setMountPreference,
            dismissMountBackendRebootPrompt = viewModel::dismissMountBackendRebootPrompt,
            cancelMountChange = viewModel::cancelMountPreference,
            undoApply = viewModel::undoFontApplication,
            cancelTask = viewModel::cancelCurrentTask,
            loadTypography = viewModel::loadDisplayTypography,
            refreshTypography = viewModel::refreshDisplayTypography,
            applyTypography = viewModel::applyDisplayTypography,
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
            deleteMany = viewModel::deleteFonts,
            restoreDefault = { restoreDefault = true },
            importSystemFonts = viewModel::importSystemFonts,
        )
    }
    val studioActions = remember(viewModel, features) {
        FontStudioActions(
            refresh = viewModel::refreshMixConfig,
            pickSlot = { pickerSlot = it },
            updateWeight = viewModel::updateMixWeight,
            updateAxis = viewModel::updateMixAxis,
            updateTune = viewModel::updateMixTune,
            inspectCoverage = features::inspectCoverage,
            startMix = viewModel::startMix,
            applyDirect = viewModel::applyFont,
            cancel = viewModel::cancelCurrentTask,
        )
    }
    val logsActions = remember(viewModel) { LogsActions(refresh = viewModel::refreshLogs, cancelTask = viewModel::cancelTask, undoApply = viewModel::undoFontApplication, markViewed = viewModel::markLogsViewed, clearLogs = viewModel::clearLogs, ignoreWarnings = viewModel::ignoreCurrentWarnings) }
    val appearanceActions = remember(appearanceViewModel) {
        AppearanceActions(
            setUiStyle = appearanceViewModel::setUiStyle,
            setThemeMode = appearanceViewModel::setThemeMode,
            setSeedArgb = appearanceViewModel::setSeedArgb,
            setKolorStyle = appearanceViewModel::setKolorStyle,
            setColorStandard = appearanceViewModel::setColorStandard,
            setMonetEnabled = appearanceViewModel::setMonetEnabled,
            setAmoledBlack = appearanceViewModel::setAmoledBlack,
            setBlurEnabled = appearanceViewModel::setBlurEnabled,
            setGlassEnabled = appearanceViewModel::setGlassEnabled,
            setFloatingDock = appearanceViewModel::setFloatingDock,
            setHighRefreshRate = appearanceViewModel::setHighRefreshRate,
            setBackgroundPath = appearanceViewModel::setBackgroundPath,
            setThemeDisplay = appearanceViewModel::setThemeDisplay,
            setPredictiveBack = appearanceViewModel::setPredictiveBack,
        )
    }
    val validFonts = remember(viewModel.fonts) { viewModel.fonts.filter { it.valid } }

    ZiyuTheme(appearance) {
        val showDock = page != AppPage.Logs && !(page == AppPage.Settings && settingsDetailVisible)
        val quickReturnEnabled = appearance.uiStyle == UiStyle.MIUIX && appearance.floatingDock &&
            showDock &&
            page in listOf(AppPage.Library, AppPage.Studio, AppPage.Settings)
        var dockHiddenByScroll by remember(page) { mutableStateOf(false) }
        var dockScrollAccumulator by remember(page) { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val pagerState = rememberPagerState(
            initialPage = dockPages.indexOf(if (page == AppPage.Logs) logsReturnPage else page).coerceAtLeast(0),
            pageCount = { dockPages.size },
        )
        var programmaticPageTarget by remember { mutableStateOf<Int?>(null) }
        val rootBack = rememberPredictiveBack(
            page != AppPage.Home && !(page == AppPage.Settings && settingsDetailVisible), appearance.predictiveBack,
        ) { gesture ->
            val destination = if (page == AppPage.Logs) logsReturnPage else AppPage.Home
            if (gesture) {
                val target = dockPages.indexOf(destination)
                if (target >= 0) {
                    programmaticPageTarget = target
                    try { pagerState.scrollToPage(target) } finally { programmaticPageTarget = null }
                }
            }
            page = destination
        }
        LaunchedEffect(page, pagerState) {
            val target = dockPages.indexOf(page)
            if (target >= 0 && (pagerState.settledPage != target ||
                    pagerState.currentPage != target || kotlin.math.abs(pagerState.currentPageOffsetFraction) > .001f)) {
                programmaticPageTarget = target
                try {
                    pagerState.animateScrollToPage(
                        target,
                        animationSpec = tween(ZiyuMotionTokens.Emphasized, easing = FastOutSlowInEasing),
                    )
                } finally {
                    programmaticPageTarget = null
                }
            }
        }
        LaunchedEffect(pagerState) {
            snapshotFlow {
                if (programmaticPageTarget == null && !pagerState.isScrollInProgress &&
                    kotlin.math.abs(pagerState.currentPageOffsetFraction) <= .001f) pagerState.settledPage else null
            }
                .distinctUntilChanged()
                .collect { index ->
                    if (index != null && page != AppPage.Logs) {
                        val selected = dockPages[index]
                        if (page != selected) page = selected
                    }
                }
        }
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
        val dockVisibility = remember { MutableTransitionState(dockActuallyVisible) }
        dockVisibility.targetState = dockActuallyVisible
        val liquidBackdrop = rememberLayerBackdrop()
        val liquidGlassSupported = appearance.floatingDock && appearance.glassEnabled &&
            (dockVisibility.currentState || dockVisibility.targetState) && Build.VERSION.SDK_INT >= 33 &&
            isRuntimeShaderSupported()
        val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Every primary page owns its scrollable bottom clearance. Keep one inset source so
        // switching glass, floating, gesture and three-button modes never stacks blank space.
        val dockClearance = 0.dp
        val dockPaddingTarget = if (showDock) {
            navigationBottom + when {
                dockHiddenByScroll -> 28.dp
                appearance.uiStyle == UiStyle.MATERIAL -> 80.dp + if (appearance.floatingDock) LocalZiyuThemeTokens.current.layout.dockBottomGap else 0.dp
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

        Box(Modifier.fillMaxSize()) {
            val prepareRootPreview = appearance.predictiveBack && Build.VERSION.SDK_INT >= 34 &&
                page != AppPage.Home && !(page == AppPage.Settings && settingsDetailVisible)
            if (prepareRootPreview) Box(Modifier.fillMaxSize()
                .predictiveBackPreviewMotion(rootBack)
                .background(LocalZiyuPaletteTokens.current.pageBackground)
                .backDestinationPreview()) {
                if (page == AppPage.Logs && logsReturnPage == AppPage.Settings) {
                    io.github.xgl34222220.ziyu.ui.settings.SettingsHome(systemCenter, {}, {})
                } else {
                    val previewActions = homeActions.copy(refresh = {}, loadTypography = {}, mountSettings = {})
                    val state = viewModel.snapshot.toHomeUiState()
                    when (appearance.uiStyle) {
                        UiStyle.MATERIAL -> io.github.xgl34222220.ziyu.ui.home.HomeScreenMaterial(state, previewActions)
                        UiStyle.MIUIX -> io.github.xgl34222220.ziyu.ui.home.HomeScreenMiuix(state, previewActions)
                        UiStyle.COUI -> io.github.xgl34222220.ziyu.ui.home.HomeScreenCoui(state, previewActions)
                    }
                }
            }
        Box(
            Modifier.fillMaxSize().predictiveBackMotion(rootBack).windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
            ),
        ) {
            Box(modifier = contentModifier) {
                AppBackdrop(appearance.backgroundPath)
                val pageContent: @Composable (AppPage) -> Unit = { visiblePage ->
                    when (visiblePage) {
                        AppPage.Home -> Box(
                            modifier = Modifier.fillMaxSize().padding(bottom = dockClearance),
                        ) {
                            CompositionLocalProvider(LocalDockContentPadding provides dockContentPadding) {
                                HomeRoute(
                                    style = appearance.uiStyle,
                                    state = viewModel.snapshot.toHomeUiState().copy(
                                        mountPreferences = viewModel.mountPreferences,
                                        mountBackendPreference = viewModel.mountBackendPreference,
                                        mountBackendRebootPrompt = viewModel.mountBackendRebootPrompt,
                                        mountPreferenceSaving = viewModel.mountPreferenceSaving,
                                        mountPreferenceLoaded = viewModel.mountPreferenceLoaded,
                                        undoAvailable = viewModel.undoAvailable,
                                        taskRunning = viewModel.operationBusy || viewModel.mixState.busy ||
                                            viewModel.snapshot.taskState in setOf("queued", "running"),
                                        currentFontRevision = viewModel.fonts.firstOrNull { it.id == viewModel.snapshot.activeFont }?.sourceRevision.orEmpty(),
                                        typographyVisible = viewModel.snapshot.activeFont in setOf("", "default") ||
                                            viewModel.fonts.any { it.id == viewModel.snapshot.activeFont && !it.combination },
                                        typography = viewModel.displayTypography,
                                    ),
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
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (appearance.uiStyle == UiStyle.MIUIX) {
                                            Modifier
                                                .background(LocalZiyuPaletteTokens.current.pageBackground)
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
                                    active = page == AppPage.Settings,
                                    actions = appearanceActions,
                                    onOpenTasks = {
                                        logsReturnPage = AppPage.Settings
                                        page = AppPage.Logs
                                    },
                                    onDetailChanged = { settingsDetailVisible = it },
                                    onTemporaryRootChanged = viewModel::refresh,
                                )
                            }
                        }
                    }
                }
                if (page == AppPage.Logs) {
                    pageContent(AppPage.Logs)
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapAnimationSpec = tween(ZiyuMotionTokens.Normal, easing = FastOutSlowInEasing),
                        ),
                        userScrollEnabled = !settingsDetailVisible,
                        key = { dockPages[it] },
                    ) { index ->
                        pageContent(dockPages[index])
                    }
                }
            }

            // The settled route is committed after the swipe. targetPage changes while the
            // finger is still moving, so the dock can follow the pager immediately.
            val dockPage = when {
                page == AppPage.Logs -> logsReturnPage
                pagerState.isScrollInProgress -> dockPages[pagerState.targetPage]
                else -> page
            }
            AnimatedVisibility(
                visibleState = dockVisibility,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(210, easing = FastOutSlowInEasing)) { it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(190, easing = FastOutSlowInEasing)) { it },
            ) {
                when (appearance.uiStyle) {
                    UiStyle.MATERIAL -> MaterialAppDock(
                        current = dockPage,
                        onSelect = { page = it },
                        appearance = appearance,
                        backdrop = liquidBackdrop,
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
                        backdrop = liquidBackdrop,
                    )
                }
            }

        }

        }

        ModuleUpdateDialogRoute(systemCenter, viewModel.operationBusy || viewModel.mixState.busy, viewModel::rebootDevice)
        CombinationResultDialogs(viewModel)

        pendingApply?.let { font ->
            FontActionDialogRoute(
                style = appearance.uiStyle,
                kind = FontActionKind.APPLY,
                message = if (font.supportsCjk) {
                    if (viewModel.snapshot.temporaryRootMode) "直接应用「${font.name}」。完成后显示挂载结果；如需重启应用或刷新系统字体缓存，请使用 KernelSU 软重启。"
                    else "直接应用「${font.name}」。完成后显示挂载结果，以及系统字体缓存是否需要重启刷新。"
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
                message = if (viewModel.snapshot.temporaryRootMode) "恢复 ROM 自带字体映射。完成后请在 KernelSU 管理器中软重启。" else "恢复 ROM 自带字体映射。完成后需要完整重启手机。",
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
private fun AppBackdrop(path: String) {
    io.github.xgl34222220.ziyu.ui.appearance.BackgroundBackdrop(path)
}
@Composable
private fun MaterialAppDock(
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    appearance: AppearanceSettings,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    // SukiSU's Material liquid variant shares the lens and drag indicator with
    // Miuix. The shared ziyu bar already reads the active Material color bridge.
    if (appearance.floatingDock && appearance.glassEnabled) {
        MiuixAppDock(current, onSelect, appearance, backdrop, modifier)
        return
    }
    val navigationItems: @Composable RowScope.() -> Unit = {
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
    if (appearance.floatingDock) {
        val layout = LocalZiyuThemeTokens.current.layout
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
            modifier = modifier.padding(start = layout.dockHorizontal, end = layout.dockHorizontal, bottom = bottomInset + layout.dockBottomGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                dockPages.forEach { destination ->
                    val selected = current == destination
                    val container by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "expressiveDockContainer")
                    val foreground by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "expressiveDockContent")
                    Row(
                        Modifier.weight(if (selected) 1.85f else 1f).clip(RoundedCornerShape(50)).background(container)
                            .selectable(selected, role = Role.Tab, onClick = { if (!selected) onSelect(destination) })
                            .animateContentSize().padding(horizontal = if (selected) 14.dp else 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(destination.icon, contentDescription = if (selected) null else destination.label, tint = foreground)
                        AnimatedVisibility(selected, modifier = Modifier.weight(1f), enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                            Text(destination.label, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    } else {
        MaterialNavigationBar(
            modifier = modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ),
            content = navigationItems,
        )
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
        val layout = LocalZiyuThemeTokens.current.layout
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding = if (bottomInset != 0.dp) layout.dockBottomGap + bottomInset else 28.dp
        FloatingBottomBar(
            modifier = modifier
                .padding(start = layout.dockHorizontal, end = layout.dockHorizontal, bottom = bottomPadding),
            selectedIndex = dockPages.indexOf(current).coerceIn(dockPages.indices),
            onSelected = { index -> dockPages.getOrNull(index)?.let(onSelect) },
            backdrop = backdrop,
            tabsCount = dockPages.size,
            isBlurEnabled = appearance.blurEnabled,
            isGlassEnabled = appearance.glassEnabled,
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
        MiuixNavigationBar(
            modifier = modifier.fillMaxWidth(),
            color = MiuixTheme.colorScheme.surfaceContainer,
        ) {
            dockPages.forEach { destination ->
                val selected = current == destination
                MiuixNavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) onSelect(destination) },
                    icon = destination.icon,
                    label = destination.label,
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
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    if (appearance.floatingDock) {
        val layout = LocalZiyuThemeTokens.current.layout
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding = if (bottomInset != 0.dp) layout.dockBottomGap + bottomInset else 28.dp
        ZiyuGlassDockSurface(
            appearance = appearance,
            backdrop = backdrop,
            modifier = modifier.padding(start = layout.dockHorizontal, end = layout.dockHorizontal, bottom = bottomPadding),
            shape = RoundedCornerShape(28.dp),
            color = COUITheme.colorScheme.surfaceContainer,
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
