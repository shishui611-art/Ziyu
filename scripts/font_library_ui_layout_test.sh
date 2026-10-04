#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MIUIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/library/FontLibraryScreenMiuix.kt"
MATERIAL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/library/FontLibraryScreenMaterial.kt"
ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/library/FontLibraryRoute.kt"
COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/library/FontLibraryScreenCompact.kt"
DETAILS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/library/FontDetailsDialog.kt"
HOME_ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/home/HomeRoute.kt"
HOME_COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/home/HomeScreenCompact.kt"
ACCEPTANCE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/home/DeviceAcceptanceGuide.kt"
MATRIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/home/DeviceTestMatrix.kt"
LOGS_ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/logs/LogsRoute.kt"
LOGS_COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/logs/LogsScreenCompact.kt"
DIAGNOSTIC="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/logs/DiagnosticExportUi.kt"
STUDIO_ROUTE="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/FontStudioRoute.kt"
STUDIO_TOOLS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/StudioToolLauncher.kt"
STUDIO_MIUIX="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/FontStudioScreenMiuix.kt"
STUDIO_MATERIAL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/FontStudioScreenMaterial.kt"
OVERLAY="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/NativeImportOverlay.kt"
SHELL="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/LuoShuAppShell.kt"
SETTINGS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/settings/SettingsHubScreen.kt"
ICON_SYSTEM="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/theme/LuoShuIconSystem.kt"
COMPACT_LAYOUT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/theme/LuoShuCompactLayout.kt"
THEME="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/theme/LuoShuTheme.kt"
DOCK_INSETS="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/theme/DockInsets.kt"
APP_BRIDGE="$ROOT/common/app_bridge.sh"
UTIL="$ROOT/common/util_functions_core.sh"
CACHE="$ROOT/common/device_font_cache.sh"

# Legacy style screens stay available as a rollback path while the routes use the
# shared compact hierarchy.
grep -q 'private fun MiuixCapabilityStrip' "$MIUIX"
grep -q 'private fun MaterialCapabilityStrip' "$MATERIAL"
grep -q 'FontLibraryScreenCompact' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 0)' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 1)' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 2)' "$ROUTE"
grep -q 'StaggeredManagementItem(index = 3)' "$ROUTE"
grep -q 'index.coerceAtLeast(0) \* 30' "$ROUTE"
grep -q 'HomeScreenCompact' "$HOME_ROUTE"
grep -q 'LogsScreenCompact' "$LOGS_ROUTE"

# Every appearance preset feeds its own whole-page palette and shape system into
# shared content. The page header also receives a style-specific treatment.
grep -q 'ProvideMaterialTokens(settings, content)' "$THEME"
grep -q 'ProvideMiuixTokens(settings, content)' "$THEME"
grep -q 'ProvideCouiTokens(settings, content)' "$THEME"
grep -q 'COUITheme.colorScheme' "$THEME"
grep -q 'LocalUiStyle provides settings.uiStyle' "$THEME"
grep -q 'UiStyle.MATERIAL -> MaterialTheme.typography.headlineSmall.fontSize' "$COMPACT_LAYOUT"
grep -q 'UiStyle.COUI -> MaterialTheme.typography.titleLarge.fontSize' "$COMPACT_LAYOUT"
grep -q 'if (couiHeader) Modifier.clip(MaterialTheme.shapes.medium)' "$COMPACT_LAYOUT"

# Font library: management tools are collapsed, the card itself opens details,
# each card has one readable native preview, and detail viewing is a stable large preview sheet.
grep -q 'var showTools' "$COMPACT"
grep -q '导入与管理' "$COMPACT"
grep -q 'CompactFontRow' "$COMPACT"
grep -q 'NativeFontPreview' "$COMPACT"
grep -q 'onClick = onDetails' "$COMPACT"
grep -q '山海有相逢 Aa 0123' "$COMPACT"
grep -q 'Hello, Ziyu 0123' "$COMPACT"
! grep -q '"Aa 12"' "$COMPACT"
! grep -q '点击卡片查看完整预览与字体信息' "$COMPACT"
grep -q 'fontMetadataSummary' "$COMPACT"
grep -q 'LightCardOutline' "$COMPACT"
grep -q 'NeutralSecondaryText' "$COMPACT"
grep -q '轻触卡片查看详情' "$COMPACT"
! grep -q 'height(102.dp)' "$COMPACT"
! grep -q '轻触卡片预览' "$COMPACT"
grep -q 'modifier = Modifier.heightIn(min = 44.dp)' "$COMPACT"
grep -q 'ModalBottomSheet' "$DETAILS"
grep -q 'sheetGesturesEnabled = false' "$DETAILS"
grep -q 'fillMaxHeight(0.94f)' "$DETAILS"
grep -q 'dragHandle = null' "$DETAILS"
grep -q 'detailScrollState.scrollTo(0)' "$DETAILS"
! grep -q 'heightIn(max = 760.dp)' "$DETAILS"
grep -q 'FontPreviewMode' "$DETAILS"
grep -q 'PreviewModeChip' "$DETAILS"
grep -q '花间一壶酒' "$DETAILS"
grep -q 'LuoShu Aa 0123456789' "$DETAILS"
grep -q '应用此字体' "$DETAILS"
! grep -q 'AlertDialog' "$DETAILS"

# Home keeps one state-aware primary action, font shortcuts, and collapsible device details.
# The trust chip participates in normal layout rather than using a fixed offset.
grep -q 'HomeNextStep' "$HOME_COMPACT"
grep -q '继续调整当前字体' "$HOME_COMPACT"
grep -q '打开任务中心查看错误原因' "$HOME_COMPACT"
! grep -q 'QUICK ACCESS' "$HOME_COMPACT"
! grep -q 'bottom = 108.dp' "$HOME_ROUTE"
grep -q 'LuoShuTopBar(title = "字域")' "$HOME_COMPACT"
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
grep -q 'LuoShuHeaderAction' "$DIAGNOSTIC"
grep -q 'LuoShuHeaderAction' "$LOGS_COMPACT"
! grep -q 'Modifier.size(50.dp)' "$LOGS_COMPACT"

# Studio uses one in-flow final action. Both title actions share one Row. The
# shell now also owns the directional page motion and Quick Return dock clearance
# so long lists regain viewport space while the frosted dock keeps its overlap behavior.
grep -q 'MiuixFinalAction(state, actions)' "$STUDIO_MIUIX"
grep -q 'MaterialFinalAction(state, actions)' "$STUDIO_MATERIAL"
grep -q 'topAction: @Composable () -> Unit' "$STUDIO_MIUIX"
grep -q 'topAction: @Composable () -> Unit' "$STUDIO_MATERIAL"
grep -q 'topAction()' "$STUDIO_MIUIX"
grep -q 'topAction()' "$STUDIO_MATERIAL"
grep -q 'LuoShuTopBar(title = "字体组合")' "$STUDIO_MIUIX"
! grep -q 'FONT MIX' "$STUDIO_MIUIX"
grep -q 'LuoShuTopBar' "$STUDIO_MATERIAL"
grep -q 'horizontalArrangement = Arrangement.spacedBy(0.dp)' "$STUDIO_MIUIX"
grep -q 'horizontalArrangement = Arrangement.spacedBy(6.dp)' "$STUDIO_MATERIAL"
grep -q 'contentColor = actionColor' "$STUDIO_MIUIX"
grep -q 'contentColor = actionColor' "$STUDIO_MATERIAL"
grep -q 'LuoShuHeaderAction' "$STUDIO_MATERIAL"
grep -q 'InteractiveAxisSlider' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/StudioAxisControls.kt"
grep -q 'height(72.dp)' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/StudioAxisControls.kt"
! grep -q 'modifier = Modifier.size(56.dp)' "$STUDIO_MATERIAL"
grep -q 'LuoShuLayoutTokens.FloatingDockSafeBottom' "$STUDIO_MIUIX"
grep -q 'LuoShuLayoutTokens.FloatingDockSafeBottom' "$STUDIO_MATERIAL"
! grep -q 'align(Alignment.TopEnd)' "$STUDIO_ROUTE"
! grep -q 'statusBarsPadding()' "$STUDIO_ROUTE"
! grep -q 'navigationBarsPadding()' "$STUDIO_ROUTE"
grep -q 'LuoShuHeaderAction' "$STUDIO_TOOLS"
grep -q 'opticalScale = 1.08f' "$STUDIO_TOOLS"
! grep -q 'modifier = modifier.size(56.dp)' "$STUDIO_TOOLS"
! grep -q 'align(Alignment.BottomStart)' "$STUDIO_ROUTE"
! grep -q 'align(Alignment.BottomCenter)' "$STUDIO_ROUTE"
[ "$(grep -c 'padding(bottom = dockClearance)' "$SHELL")" -eq 5 ]
grep -q 'val miuix = appearance.uiStyle == UiStyle.MIUIX' "$SHELL"
grep -q 'navigationBottom + when {' "$SHELL"
grep -q 'val dockPaddingTarget = if (showDock)' "$SHELL"
grep -q 'dockPaddingTarget' "$SHELL"
grep -q 'dockHiddenByScroll -> 28.dp' "$SHELL"
grep -q 'Modifier.nestedScroll(dockScrollConnection)' "$SHELL"
grep -q 'dockHideThresholdPx' "$SHELL"
grep -q 'dockShowThresholdPx' "$SHELL"
[ "$(grep -c 'LocalDockContentPadding provides dockContentPadding' "$SHELL")" -eq 5 ]
grep -q 'LocalDockContentPadding' "$DOCK_INSETS"

# Four primary destinations are rendered by both app styles; task logs remain a detail page.
grep -q 'private val dockPages' "$SHELL"
[ "$(sed -n '/private val dockPages = listOf(/,/^)/p' "$SHELL" | grep -c 'AppPage\.')" -eq 4 ]
sed -n '/private val dockPages = listOf(/,/^)/p' "$SHELL" | grep -q 'AppPage.Settings'
! sed -n '/private val dockPages = listOf(/,/^)/p' "$SHELL" | grep -q 'AppPage.Logs'
grep -q 'val showDock = page != AppPage.Logs' "$SHELL"
MATERIAL_DOCK=$(sed -n '/private fun MaterialAppDock/,/private fun MiuixAppDock/p' "$SHELL")
printf '%s\n' "$MATERIAL_DOCK" | grep -q 'dockPages.forEach'
printf '%s\n' "$MATERIAL_DOCK" | grep -q 'NavigationBarItem('
! printf '%s\n' "$MATERIAL_DOCK" | grep -q 'modifier = Modifier.weight(1f)'
grep -q 'private fun MiuixAppDock' "$SHELL"
MIUIX_DOCK=$(sed -n '/private fun MiuixAppDock/,$p' "$SHELL")
printf '%s\n' "$MIUIX_DOCK" | grep -q 'FloatingBottomBar('
printf '%s\n' "$MIUIX_DOCK" | grep -q 'tabsCount = dockPages.size'
printf '%s\n' "$MIUIX_DOCK" | grep -q 'dockPages.forEachIndexed'
printf '%s\n' "$MIUIX_DOCK" | grep -q 'NavigationBarItem('
! printf '%s\n' "$MIUIX_DOCK" | grep -q 'modifier = Modifier.weight(1f)'
grep -q 'fun FloatingBottomBar(' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/navigation/kernelsu/FloatingBottomBar.kt"
grep -q 'isBlurEnabled = appearance.glassEnabled && appearance.blurEnabled' "$SHELL"
grep -q 'AnimatedVisibility(' "$SHELL"
grep -q 'key(page)' "$SHELL"
grep -q 'translationX = (1f - pageEnter.value) \* 14.dp.toPx() \* pageDirection' "$SHELL"
grep -q 'page.motionIndex() - previousPageForMotion.motionIndex()' "$SHELL"
! grep -q 'AnimatedContent' "$SHELL"

# Settings follows a grouped home -> detail hierarchy instead of a clipped horizontal tab strip.
grep -q 'SettingCard("视觉与显示")' "$SETTINGS"
grep -q 'ToggleLine("玻璃半透明", "为 Miuix 悬浮底栏启用 KSU 风格的玻璃效果"' "$SETTINGS"
grep -q 'ToggleLine("背景模糊", "模糊悬浮底栏后方的页面内容"' "$SETTINGS"
grep -q 'ToggleLine("悬浮底栏", "关闭后使用贴合屏幕底部的常规导航栏"' "$SETTINGS"
grep -q 'private fun SettingsGroup' "$SETTINGS"
grep -q 'private fun SettingsNavigationRow' "$SETTINGS"
grep -q 'settingsDetailTransition' "$SETTINGS"
grep -q 'LuoShuDetailBar' "$SETTINGS"
! grep -q 'Modifier.width(70.dp)' "$SETTINGS"
grep -q 'embedded: Boolean = false' "$OVERLAY"
grep -q 'embedded = true' "$SHELL"
grep -q 'dockClearance' "$SHELL"
grep -q 'val HeaderTouchTarget = 48.dp' "$ICON_SYSTEM"
grep -q 'val HeaderContainer = 44.dp' "$ICON_SYSTEM"
grep -q 'val HeaderGlyph = 21.dp' "$ICON_SYSTEM"
grep -q 'IconButtonDefaults.iconButtonColors' "$ICON_SYSTEM"
grep -q 'val DockGlyph = 22.dp' "$ICON_SYSTEM"
grep -q 'val SectionGlyph = 18.dp' "$ICON_SYSTEM"
grep -q 'val ToolGlyph = 20.dp' "$ICON_SYSTEM"
grep -q 'LuoShuLayoutTokens.FloatingDockSafeBottom' "$HOME_COMPACT"
grep -q 'LuoShuLayoutTokens.FloatingDockSafeBottom' "$COMPACT"
# Logs is a detail route without a dock; reserve the measured import controls instead.
grep -q 'controlsBottomPadding + 28.dp' "$LOGS_COMPACT"
grep -q 'onSizeChanged { importControlsHeight = it.height }' "$LOGS_ROUTE"
grep -q 'CompactStatusCell' "$HOME_COMPACT"
grep -q "self-mount) printf '字域自挂载'" "$APP_BRIDGE"
grep -q 'mountSummary(h)' "$SETTINGS"
grep -q 'selfMountSummary(h)' "$SETTINGS"
grep -q 'shape = LuoShuShapeTokens.Card' "$SETTINGS"
grep -q 'heightIn(min = 64.dp)' "$SETTINGS"
grep -q 'Role.Switch' "$SETTINGS"
grep -q 'Switch(checked = checked, onCheckedChange = null' "$SETTINGS"
grep -q 'pageBackground = Color(0xFFF1F5F9)' "$THEME"
grep -q 'internal fun LuoShuTopBar' "$COMPACT_LAYOUT"
grep -q 'internal fun LuoShuDetailBar' "$COMPACT_LAYOUT"
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
STUDIO_PREVIEW="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/StudioCompositePreview.kt"
STUDIO_PREVIEW_COMPACT="$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/StudioCompositePreviewCompact.kt"
for preview in "$STUDIO_PREVIEW" "$STUDIO_PREVIEW_COMPACT"; do
  grep -q 'StudioPreviewTheme(previewDark)' "$preview"
  grep -q 'StudioPreviewAppearanceSelector(previewDark)' "$preview"
done
grep -q 'onSurface = if (dark)' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/studio/StudioPreviewTheme.kt"

# The former Miuix visual option now uses COUI's ColorOS theme and native navigation components.
grep -q 'MIUIX("Miuix")' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/appearance/AppearanceSettings.kt"
grep -q 'COUI("COUI")' "$ROOT/android-app/app/src/main/java/io/github/xgl34222220/luoshu/ui/appearance/AppearanceSettings.kt"
grep -q 'COUITheme(controller = couiController)' "$THEME"
grep -q 'UiStyle.MIUIX -> LuoShuMiuixTheme' "$THEME"
grep -q 'UiStyle.COUI -> LuoShuCouiTheme' "$THEME"
grep -q 'io.github.suqi8.coui.kmp:coui-ui-android:1.1.0' "$ROOT/android-app/app/build.gradle.kts"
grep -q 'CouiAppDock(' "$SHELL"
grep -q 'CouiNavigationBar(' "$SHELL"
grep -q 'CouiNavigationBarItem(' "$SHELL"

echo 'LuoShu compact UI layout regression passed.'
