#!/bin/sh
set -eux
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MIUIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/library/FontLibraryScreenMiuix.kt"
MATERIAL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/library/FontLibraryScreenMaterial.kt"
COUI="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/library/FontLibraryScreenCoui.kt"
ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/library/FontLibraryRoute.kt"
COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/library/FontLibraryScreenContent.kt"
DETAILS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/library/FontDetailsDialog.kt"
SHEETS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/design/ZiyuSheets.kt"
HOME_ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/HomeRoute.kt"
HOME_COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/HomeScreenContent.kt"
HOME_MATERIAL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/HomeScreenMaterial.kt"
HOME_MIUIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/HomeScreenMiuix.kt"
HOME_COUI="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/HomeScreenCoui.kt"
ACCEPTANCE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/DeviceAcceptanceGuide.kt"
MATRIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/home/DeviceTestMatrix.kt"
LOGS_ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/logs/LogsRoute.kt"
LOGS_COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/logs/LogsScreenContent.kt"
DIAGNOSTIC="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/logs/DiagnosticExportUi.kt"
STUDIO_ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/FontStudioRoute.kt"
STUDIO_TOOLS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/StudioToolLauncher.kt"
STUDIO_MIUIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/FontStudioScreenNative.kt"
STUDIO_MATERIAL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/FontStudioScreenMaterial.kt"
OVERLAY="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/NativeImportOverlay.kt"
SHELL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ZiyuAppShell.kt"
SETTINGS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/settings/SettingsHubScreen.kt"
ICON_SYSTEM="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/theme/ZiyuIconSystem.kt"
COMPACT_LAYOUT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/theme/ZiyuCompactLayout.kt"
THEME="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/theme/ZiyuTheme.kt"
DOCK_INSETS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/theme/DockInsets.kt"
APP_BRIDGE="$ROOT/common/app_bridge.sh"
UTIL="$ROOT/common/util_functions_core.sh"
CACHE="$ROOT/common/device_font_cache.sh"

# All three appearance routes keep their own screen adapter and share the
# library content and management flow.
grep -q 'UiStyle.MATERIAL -> FontLibraryScreenMaterial' "$ROUTE"
grep -q 'UiStyle.MIUIX -> FontLibraryScreenMiuix' "$ROUTE"
grep -q 'UiStyle.COUI -> FontLibraryScreenCoui' "$ROUTE"
grep -q 'FontLibraryPageContent(UiStyle.MATERIAL' "$MATERIAL"
grep -q 'FontLibraryPageContent(UiStyle.MIUIX' "$MIUIX"
grep -q 'FontLibraryPageContent(UiStyle.COUI' "$COUI"
grep -q 'StaggeredManagementItem(index = 0)' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 1)' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 2)' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 3)' "$ROUTE"
grep -q 'index.coerceAtLeast(0) \* 30' "$ROUTE"
grep -q 'UiStyle.MATERIAL -> HomeScreenMaterial' "$HOME_ROUTE"
grep -q 'UiStyle.MIUIX -> HomeScreenMiuix' "$HOME_ROUTE"
grep -q 'UiStyle.COUI -> HomeScreenCoui' "$HOME_ROUTE"
grep -q 'UiStyle.MATERIAL -> LogsScreenMaterial' "$LOGS_ROUTE"
grep -q 'UiStyle.MIUIX -> LogsScreenMiuix' "$LOGS_ROUTE"
grep -q 'UiStyle.COUI -> LogsScreenCoui' "$LOGS_ROUTE"
for home in "$HOME_MATERIAL" "$HOME_MIUIX" "$HOME_COUI"; do
  grep -q 'HomePageHeader' "$home"
  grep -q 'HomeCurrentFont' "$home"
done

# Every appearance preset feeds its own whole-page palette and shape system into
# the shared design adapter. Native headers use their matching component families.
grep -q 'rememberUpdatedState(content)' "$THEME"
grep -Fq 'movableContentOf { latestContent.value() }' "$THEME"
grep -q 'UiStyle.MATERIAL -> ZiyuMaterialTheme(settings, retainedContent)' "$THEME"
grep -q 'UiStyle.MIUIX -> ZiyuMiuixTheme(settings, retainedContent)' "$THEME"
grep -q 'UiStyle.COUI -> ZiyuCouiTheme(settings, retainedContent)' "$THEME"
grep -q 'ProvideZiyuThemeData(' "$THEME"
grep -q 'LocalZiyuThemeTokens provides nativeTokens' "$THEME"
grep -q 'COUITheme.colorScheme' "$THEME"
grep -q 'LocalUiStyle provides settings.uiStyle' "$THEME"
grep -q 'MiuixLargeTopAppBar(' "$COMPACT_LAYOUT"
grep -q 'io.github.suqi8.coui.kmp.basic.SmallTopAppBar(' "$COMPACT_LAYOUT"
grep -q 'MaterialTopAppBar(' "$COMPACT_LAYOUT"
grep -q 'val titleFontSize = when (style)' "$COMPACT_LAYOUT"

# Font library: management tools are collapsed, the card itself opens details,
# each card has one readable native preview, and detail viewing is a stable large preview sheet.
grep -q 'var showTools' "$COMPACT"
grep -q '导入与管理' "$COMPACT"
grep -q 'LibraryFontCard' "$COMPACT"
grep -q 'NativeFontPreview' "$COMPACT"
grep -q 'onClick = onDetails' "$COMPACT"
grep -q '山海有相逢 Aa 0123' "$COMPACT"
grep -q 'Hello, Ziyu 0123' "$COMPACT"
! grep -q '"Aa 12"' "$COMPACT"
! grep -q '点击卡片查看完整预览与字体信息' "$COMPACT"
grep -q 'fontMetadataSummary' "$COMPACT"
grep -q 'LocalZiyuPaletteTokens.current' "$COMPACT"
grep -q 'LightCardOutline' "$DETAILS"
! grep -q 'height(102.dp)' "$COMPACT"
! grep -q '轻触卡片预览' "$COMPACT"
grep -q 'modifier = Modifier.heightIn(min = 44.dp)' "$COMPACT"
grep -q 'ModalBottomSheet' "$DETAILS"
grep -q 'sheetGesturesEnabled = true' "$DETAILS"
grep -q 'fillMaxHeight(0.94f)' "$DETAILS"
grep -q 'verticalScroll(detailScrollState)' "$DETAILS"
grep -q 'UiStyle.MATERIAL -> {' "$SHEETS"
grep -q 'UiStyle.MIUIX -> NativeZiyuBottomSheet' "$SHEETS"
grep -q 'UiStyle.COUI -> NativeZiyuBottomSheet' "$SHEETS"
grep -q 'sheetGesturesEnabled = sheetGesturesEnabled' "$SHEETS"
grep -q 'detailScrollState.scrollTo(0)' "$DETAILS"
! grep -q 'heightIn(max = 760.dp)' "$DETAILS"
grep -q 'FontPreviewMode' "$DETAILS"
grep -q 'PreviewModeChip' "$DETAILS"
grep -q '花间一壶酒' "$DETAILS"
grep -q 'Ziyu Aa 0123456789' "$DETAILS"
grep -q '应用此字体' "$DETAILS"
! grep -q 'AlertDialog' "$DETAILS"

# Home keeps one state-aware primary action, font shortcuts, and collapsible device details.
# The trust chip participates in normal layout rather than using a fixed offset.
grep -q 'HomeNextStep' "$HOME_COMPACT"
grep -q 'state.taskRunning -> HomeNextStep("查看任务"' "$HOME_COMPACT"
grep -q 'state.error.isNotBlank() -> HomeNextStep("查看问题"' "$HOME_COMPACT"
grep -q '打开任务中心查看错误原因与诊断信息' "$HOME_COMPACT"
grep -q 'state.currentFont.contains("系统") -> HomeNextStep("打开字体库"' "$HOME_COMPACT"
grep -q 'else -> HomeNextStep("打开组合"' "$HOME_COMPACT"
! grep -q 'QUICK ACCESS' "$HOME_COMPACT"
! grep -q 'bottom = 108.dp' "$HOME_ROUTE"
grep -q 'ZiyuTopBar("字域")' "$HOME_COMPACT"
! grep -q 'FONT ENGINE' "$HOME_COMPACT"
! grep -q 'FONT LIBRARY' "$COMPACT"
! grep -q 'TASK CENTER' "$LOGS_COMPACT"

# Acceptance guidance follows the installed version and never treats an
# unverified compatibility mapping as proof that the font is effective.
grep -q "targetVersion: String = state.version.substringBefore('-').substringBefore('+')" "$MATRIX"
grep -q '真机测试矩阵 · ${report.targetVersion}' "$MATRIX"
grep -q '测试矩阵与当前版本预发行门禁' "$ACCEPTANCE"
grep -q 'val blocking: Boolean = true' "$ACCEPTANCE"
grep -q '尚无系统实际加载证据，不能判定字体已经生效' "$ACCEPTANCE"
! grep -q '不影响正常使用' "$ACCEPTANCE"
grep -q '兼容映射尚未获得加载证据；设备对齐缓存仍在后台准备' "$ACCEPTANCE"
grep -q '加载验证失败，请打开问题页查看具体失败分区' "$ACCEPTANCE"
! grep -q 'v2.2.2' "$MATRIX"
! grep -q 'v2.2.2' "$ACCEPTANCE"

# ROM detection is a state fact, not a polling log. Existing duplicate records
# are compacted once and new entries are emitted only when the detected version changes.
grep -q 'compact_rom_detection_logs' "$UTIL"
grep -q 'log_rom_detection_once coloros' "$UTIL"
grep -q 'log_rom_detection_once hyperos' "$UTIL"

# A detached background cache worker is preparation-only. It may publish an immutable
# ready cache, but activation, reboot markers and transaction commits belong exclusively
# to the explicit foreground switch.
CACHE_WORKER=$(sed -n '/^_dfcache_build_pending_inner()/,/^}/p' "$CACHE")
printf '%s\n' "$CACHE_WORKER" | grep -q 'LUOSHU_CACHE_FOREGROUND'
! printf '%s\n' "$CACHE_WORKER" | grep -q 'device_font_cache_activate'
! printf '%s\n' "$CACHE_WORKER" | grep -q 'text_reboot_required.conf'
! printf '%s\n' "$CACHE_WORKER" | grep -q 'luoshu_payload_transaction_commit'
grep -q '设备对齐缓存已就绪且未改动当前字体' "$CACHE"

# Task center separates user-facing tasks/issues from raw logs and all routed header
# actions use the same compact visible box while preserving a 48 dp touch target.
grep -q 'enum class LogsTab' "$LOGS_COMPACT"
grep -q 'TASKS("刷写")' "$LOGS_COMPACT"
grep -q 'FlashProgressCard(state)' "$LOGS_COMPACT"
grep -q 'ISSUES("问题")' "$LOGS_COMPACT"
grep -q 'LOGS("日志")' "$LOGS_COMPACT"
grep -q 'TaskPhase.FAILED' "$LOGS_COMPACT"
grep -q 'ZiyuHeaderAction' "$DIAGNOSTIC"
grep -q 'ZiyuHeaderAction' "$LOGS_COMPACT"
! grep -q 'Modifier.size(50.dp)' "$LOGS_COMPACT"

# Studio uses one in-flow final action. Both title actions share one Row. The
# shell uses HorizontalPager for page swipes and owns Quick Return dock clearance
# so long lists regain viewport space while the frosted dock keeps its overlap behavior.
grep -q 'NativeFinalAction(state, actions)' "$STUDIO_MIUIX"
grep -q 'MaterialFinalAction(state, actions)' "$STUDIO_MATERIAL"
grep -q 'topAction: @Composable () -> Unit' "$STUDIO_MIUIX"
grep -q 'topAction: @Composable () -> Unit' "$STUDIO_MATERIAL"
grep -q 'topAction()' "$STUDIO_MIUIX"
grep -q 'topAction()' "$STUDIO_MATERIAL"
grep -q 'ZiyuTopBar(title = "字体组合")' "$STUDIO_MIUIX"
! grep -q 'FONT MIX' "$STUDIO_MIUIX"
grep -q 'ZiyuTopBar' "$STUDIO_MATERIAL"
grep -q 'horizontalArrangement = Arrangement.spacedBy(0.dp)' "$STUDIO_MIUIX"
grep -q 'horizontalArrangement = Arrangement.spacedBy(6.dp)' "$STUDIO_MATERIAL"
grep -q 'contentColor = actionColor' "$STUDIO_MIUIX"
grep -q 'contentColor = actionColor' "$STUDIO_MATERIAL"
grep -q 'ZiyuHeaderAction' "$STUDIO_MATERIAL"
grep -q 'InteractiveAxisSlider' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/StudioAxisControls.kt"
grep -q 'height(72.dp)' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/StudioAxisControls.kt"
! grep -q 'modifier = Modifier.size(56.dp)' "$STUDIO_MATERIAL"
grep -q 'maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)' "$STUDIO_MIUIX"
grep -q 'maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)' "$STUDIO_MATERIAL"
! grep -q 'align(Alignment.TopEnd)' "$STUDIO_ROUTE"
! grep -q 'statusBarsPadding()' "$STUDIO_ROUTE"
! grep -q 'navigationBarsPadding()' "$STUDIO_ROUTE"
grep -q 'ZiyuHeaderAction' "$STUDIO_TOOLS"
grep -q 'opticalScale = 1.08f' "$STUDIO_TOOLS"
! grep -q 'modifier = modifier.size(56.dp)' "$STUDIO_TOOLS"
! grep -q 'align(Alignment.BottomStart)' "$STUDIO_ROUTE"
! grep -q 'align(Alignment.BottomCenter)' "$STUDIO_ROUTE"
[ "$(grep -c 'padding(bottom = dockClearance)' "$SHELL")" -eq 5 ]
grep -q 'val quickReturnEnabled = appearance.uiStyle == UiStyle.MIUIX && appearance.floatingDock' "$SHELL"
grep -q 'navigationBottom + when {' "$SHELL"
grep -q 'val dockPaddingTarget = if (showDock)' "$SHELL"
grep -q 'dockPaddingTarget' "$SHELL"
grep -q 'dockHiddenByScroll -> 28.dp' "$SHELL"
grep -q 'Modifier.nestedScroll(dockScrollConnection)' "$SHELL"
grep -q 'dockHideThresholdPx' "$SHELL"
grep -q 'dockShowThresholdPx' "$SHELL"
[ "$(grep -c 'LocalDockContentPadding provides dockContentPadding' "$SHELL")" -eq 5 ]
grep -q 'LocalDockContentPadding' "$DOCK_INSETS"

# Four primary destinations are rendered by all three design systems; task logs remain a detail page.
grep -q 'private val dockPages' "$SHELL"
[ "$(sed -n '/private val dockPages = listOf(/,/^)/p' "$SHELL" | grep -c 'AppPage\.')" -eq 4 ]
sed -n '/private val dockPages = listOf(/,/^)/p' "$SHELL" | grep -q 'AppPage.Settings'
! sed -n '/private val dockPages = listOf(/,/^)/p' "$SHELL" | grep -q 'AppPage.Logs'
grep -q 'val showDock = page != AppPage.Logs' "$SHELL"
MATERIAL_DOCK=$(sed -n '/private fun MaterialAppDock/,/private fun MiuixAppDock/p' "$SHELL")
printf '%s\n' "$MATERIAL_DOCK" | grep -q 'dockPages.forEach'
printf '%s\n' "$MATERIAL_DOCK" | grep -q 'NavigationBarItem('
# Native navigation items keep their own sizing contract. The expressive
# floating Row separately weights its animated selected label.
MATERIAL_NATIVE_ITEM=$(printf '%s\n' "$MATERIAL_DOCK" | sed -n '/MaterialNavigationBarItem(/,/^[[:space:]]*)/p')
test -n "$MATERIAL_NATIVE_ITEM"
! printf '%s\n' "$MATERIAL_NATIVE_ITEM" | grep -q 'modifier = Modifier.weight(1f)'
grep -q 'private fun MiuixAppDock' "$SHELL"
MIUIX_DOCK=$(sed -n '/private fun MiuixAppDock/,/private fun CouiAppDock/p' "$SHELL")
printf '%s\n' "$MIUIX_DOCK" | grep -q 'FloatingBottomBar('
printf '%s\n' "$MIUIX_DOCK" | grep -q 'tabsCount = dockPages.size'
printf '%s\n' "$MIUIX_DOCK" | grep -q 'dockPages.forEachIndexed'
printf '%s\n' "$MIUIX_DOCK" | grep -q 'NavigationBarItem('
! printf '%s\n' "$MIUIX_DOCK" | grep -q 'modifier = Modifier.weight(1f)'
COUI_DOCK=$(sed -n '/private fun CouiAppDock/,$p' "$SHELL")
printf '%s\n' "$COUI_DOCK" | grep -q 'CouiNavigationBar('
printf '%s\n' "$COUI_DOCK" | grep -q 'CouiNavigationBarItem('
grep -q 'fun FloatingBottomBar(' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/navigation/kernelsu/FloatingBottomBar.kt"
grep -q 'isBlurEnabled = appearance.blurEnabled' "$SHELL"
grep -q 'isGlassEnabled = appearance.glassEnabled' "$SHELL"
grep -q 'AnimatedVisibility(' "$SHELL"
grep -Fq 'HorizontalPager(' "$SHELL"
grep -Fq 'pagerState.isScrollInProgress -> dockPages[pagerState.targetPage]' "$SHELL"
grep -Fq 'key = { dockPages[it] }' "$SHELL"
! grep -q 'AnimatedContent' "$SHELL"

# Settings follows a grouped home -> detail hierarchy instead of a clipped horizontal tab strip.
grep -q 'SettingCard("视觉与显示")' "$SETTINGS"
grep -Fq 'ToggleLine("液态玻璃", "悬浮底栏使用半透明、折射与可拖动的选中指示器", settings.glassEnabled, actions.setGlassEnabled, settings.floatingDock)' "$SETTINGS"
grep -Fq 'ToggleLine("背景模糊", "关闭后保留玻璃的半透明与折射效果", settings.blurEnabled, actions.setBlurEnabled, settings.glassEnabled && settings.floatingDock)' "$SETTINGS"
grep -q 'ToggleLine("悬浮底栏", "关闭后使用贴合屏幕底部的常规导航栏"' "$SETTINGS"
grep -q 'private fun SettingsGroup' "$SETTINGS"
grep -q 'private fun SettingsNavigationRow' "$SETTINGS"
grep -q 'settingsDetailTransition' "$SETTINGS"
grep -q 'ZiyuDetailBar' "$SETTINGS"
! grep -q 'Modifier.width(70.dp)' "$SETTINGS"
grep -q 'embedded: Boolean = false' "$OVERLAY"
grep -q 'embedded = true' "$SHELL"
grep -q 'dockClearance' "$SHELL"
grep -q 'val HeaderTouchTarget = 48.dp' "$ICON_SYSTEM"
grep -q 'val HeaderContainer = 48.dp' "$ICON_SYSTEM"
grep -q 'val HeaderGlyph = 21.dp' "$ICON_SYSTEM"
grep -q 'IconButtonDefaults.iconButtonColors' "$ICON_SYSTEM"
grep -q 'val DockGlyph = 22.dp' "$ICON_SYSTEM"
grep -q 'val SectionGlyph = 18.dp' "$ICON_SYSTEM"
grep -q 'val ToolGlyph = 20.dp' "$ICON_SYSTEM"
grep -q 'maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)' "$HOME_MATERIAL"
grep -q 'maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)' "$HOME_MIUIX"
grep -q 'maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)' "$HOME_COUI"
grep -q 'maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)' "$COMPACT"
# Logs is a detail route without a dock; reserve the measured import controls instead.
grep -q 'controlsBottomPadding + layout.dockSafeBottom / 3' "$LOGS_COMPACT"
grep -q 'onSizeChanged { importControlsHeight = it.height }' "$LOGS_ROUTE"
grep -q 'HomeConnectionRow' "$HOME_COMPACT"
grep -q 'luoshu_mount_backend_details' "$APP_BRIDGE"
grep -q 'MOUNT_PROVIDER_NAME.*MOUNT_METHOD' "$APP_BRIDGE"
grep -q '"mountEngine":"%s"' "$APP_BRIDGE"
grep -q 'mountEngineLabel(state.mountEngine)' "$SETTINGS"
grep -q 'mountBackendLabel(state.selfMountBackend)' "$SETTINGS"
grep -q 'mountSummary(h)' "$SETTINGS"
grep -q 'selfMountSummary(h)' "$SETTINGS"
grep -q 'shape = ZiyuShapeTokens.Card' "$SETTINGS"
grep -q 'heightIn(min = 64.dp)' "$SETTINGS"
grep -q 'Role.Switch' "$SETTINGS"
grep -q 'ZiyuSwitch as Switch' "$SETTINGS"
grep -q 'onCheckedChange = onChange' "$SETTINGS"
grep -q 'pageBackground = if (preferAmoledCardSurface) Color.Black else colorScheme.background' "$THEME"
grep -q 'LocalMiuixTokens provides tokens' "$THEME"
grep -q 'internal fun ZiyuTopBar' "$COMPACT_LAYOUT"
grep -q 'internal fun ZiyuDetailBar' "$COMPACT_LAYOUT"
# Task errors can be expanded, and raw logs are filtered and composed lazily.
grep -q 'items(state.tasks' "$LOGS_COMPACT"
grep -q 'maxLines = if (expanded) Int.MAX_VALUE else 2' "$LOGS_COMPACT"
grep -q 'Text(taskDisplayMessage(task.message)' "$LOGS_COMPACT"
grep -q 'SelectionContainer { Text(task.message' "$LOGS_COMPACT"
grep -q 'items(visibleLines' "$LOGS_COMPACT"
grep -q 'visibleLines.joinToString' "$LOGS_COMPACT"
grep -q 'logMatchesFilter' "$LOGS_COMPACT"
! grep -q 'padding(bottom = 96.dp)' "$LOGS_ROUTE"
! grep -q 'if (page == AppPage.Studio)' "$SHELL"

# Composite previews expose an independent light/dark mode and render both
# Compose and Android TextView previews inside that selected color scheme.
STUDIO_PREVIEW="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/StudioCompositePreview.kt"
STUDIO_PREVIEW_COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/StudioCompositePreviewCompact.kt"
for preview in "$STUDIO_PREVIEW" "$STUDIO_PREVIEW_COMPACT"; do
  grep -q 'StudioPreviewTheme(previewDark)' "$preview"
  grep -q 'StudioPreviewAppearanceSelector(previewDark)' "$preview"
done
grep -q 'onSurface = if (dark)' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/studio/StudioPreviewTheme.kt"

# The former Miuix visual option now uses COUI's ColorOS theme and native navigation components.
grep -q 'MIUIX("Miuix")' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/appearance/AppearanceSettings.kt"
grep -q 'COUI("COUI")' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/appearance/AppearanceSettings.kt"
grep -q 'COUITheme(controller = controller, textStyles = CouiNativeTextStyles)' "$THEME"
grep -q 'UiStyle.MIUIX -> ZiyuMiuixTheme' "$THEME"
grep -q 'UiStyle.COUI -> ZiyuCouiTheme' "$THEME"
grep -q 'io.github.suqi8.coui.kmp:coui-ui-android:1.1.0' "$ROOT/android-app/app/build.gradle.kts"
grep -q 'CouiAppDock(' "$SHELL"
grep -q 'CouiNavigationBar(' "$SHELL"
grep -q 'CouiNavigationBarItem(' "$SHELL"

echo 'Ziyu shared UI layout regression passed.'
