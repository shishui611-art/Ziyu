package io.github.xgl34222220.ziyu.ui.studio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.github.xgl34222220.ziyu.ui.theme.ZiyuMotionTokens
import androidx.compose.foundation.layout.Box
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedTextField as OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import io.github.xgl34222220.ziyu.MixSlot
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle

@Composable
internal fun FontStudioRoute(
    style: UiStyle,
    state: FontStudioUiState,
    actions: FontStudioActions,
) {
    val context = LocalContext.current
    val profileBridge = remember(context.applicationContext) {
        StudioProfileBridgeStore(context.applicationContext)
    }
    val latestActions by rememberUpdatedState(actions)
    var showCompositePreview by remember { mutableStateOf(false) }
    var showPresetLibrary by remember { mutableStateOf(false) }
    var showProfileTransfer by remember { mutableStateOf(false) }
    var showGlyphBrowser by remember { mutableStateOf(false) }
    var showSwitchHistory by remember { mutableStateOf(false) }
    var restoreNotice by remember { mutableStateOf("") }
    var showMixName by remember { mutableStateOf(false) }
    var mixName by remember { mutableStateOf("") }
    val nameValid = mixName.trim().let { name ->
        name.isNotBlank() && name.length <= 60 && name.none { it in "\r\n\t/\\\u0000|" }
    }
    val stableActions = remember {
        FontStudioActions(
            refresh = { latestActions.refresh() },
            pickSlot = { latestActions.pickSlot(it) },
            updateFont = { slot, id -> latestActions.updateFont(slot, id) },
            updateWeight = { slot, weight -> latestActions.updateWeight(slot, weight) },
            updateAxis = { slot, tag, value -> latestActions.updateAxis(slot, tag, value) },
            updateTune = { size, offset -> latestActions.updateTune(size, offset) },
            applyProfile = { latestActions.applyProfile(it) },
            inspectCoverage = { latestActions.inspectCoverage(it) },
            startMix = {
                mixName = ""
                showMixName = true
            },
            applyDirect = { latestActions.applyDirect(it) },
            cancel = { latestActions.cancel() },
        )
    }

    LaunchedEffect(state.loading, state.busy, state.operationBusy, state.fonts, state.slots, state.latinSize, state.latinOffset) {
        if (!state.loading && state.slots.all { it.font != null }) {
            profileBridge.saveCurrent(encodeStudioProfile(state))
        }
        val pending = profileBridge.peekPending()
        if (pending.isBlank() || state.loading || state.busy || state.operationBusy || state.fonts.isEmpty()) return@LaunchedEffect
        val parsed = parseStudioProfile(pending, state.fonts)
        if (parsed.valid && parsed.profile != null) {
            val failure = applyStudioProfile(parsed.profile, stableActions)
            if (failure != null) {
                restoreNotice = "组合方案未恢复：$failure"
                return@LaunchedEffect
            }
            profileBridge.clearPending()
            restoreNotice = buildString {
                append("备份中的组合方案已载入")
                if (parsed.warnings.isNotEmpty()) append("\n${parsed.warnings.joinToString("\n")}")
            }
        } else {
            profileBridge.clearPending()
            restoreNotice = "组合方案未恢复：${parsed.errors.joinToString("；").ifBlank { "配置无效" }}"
        }
    }

    LaunchedEffect(state.taskState) {
        if (state.taskState == "success") recordSuccessfulMixHistory()
    }

    val studioTools: @Composable () -> Unit = {
        StudioToolLauncher(
            style = style,
            enabled = state.hasFonts && !state.loading,
            childLayerActive = showCompositePreview ||
                showPresetLibrary ||
                showSwitchHistory ||
                showProfileTransfer ||
                showGlyphBrowser,
            onPreview = { showCompositePreview = true },
            onPresets = { showPresetLibrary = true },
            onHistory = { showSwitchHistory = true },
            onProfile = { showProfileTransfer = true },
            onGlyphs = { showGlyphBrowser = true },
        )
    }
    val childLayerActive = showCompositePreview ||
        showPresetLibrary ||
        showProfileTransfer ||
        showGlyphBrowser ||
        showSwitchHistory ||
        restoreNotice.isNotBlank()
    // MIUIX uses the native child-layer depth response. COUI manages its own overlay
    // transitions and Material preserves its platform dialog geometry.
    val pageScale by animateFloatAsState(
        targetValue = if (style == UiStyle.MIUIX && childLayerActive) .96f else 1f,
        animationSpec = tween(ZiyuMotionTokens.Emphasized, easing = FastOutSlowInEasing),
        label = "studioDepthScale",
    )
    Box(
        modifier = androidx.compose.ui.Modifier.graphicsLayer {
            scaleX = pageScale
            scaleY = pageScale
        },
    ) {
        when (style) {
            UiStyle.MATERIAL -> FontStudioScreenMaterial(state, stableActions, studioTools)
            UiStyle.MIUIX -> FontStudioScreenMiuix(state, stableActions, studioTools)
            UiStyle.COUI -> FontStudioScreenCoui(state, stableActions, studioTools)
        }
    }

    if (showCompositePreview) {
        StudioCompositePreviewDialogCompact(
            style = style,
            state = state,
            onApplyPreset = { preset ->
                latestActions.updateWeight(MixSlot.Cjk, preset.weightFor(MixSlot.Cjk))
                latestActions.updateWeight(MixSlot.Latin, preset.weightFor(MixSlot.Latin))
                latestActions.updateWeight(MixSlot.Digit, preset.weightFor(MixSlot.Digit))
            },
            onDismiss = { showCompositePreview = false },
        )
    }
    if (showPresetLibrary) {
        StudioPresetDialog(
            style = style,
            state = state,
            actions = stableActions,
            onDismiss = { showPresetLibrary = false },
        )
    }
    if (showProfileTransfer) {
        StudioProfileTransferDialog(
            style = style,
            state = state,
            actions = stableActions,
            onDismiss = { showProfileTransfer = false },
        )
    }
    if (showGlyphBrowser) {
        StudioGlyphBrowserDialog(
            style = style,
            state = state,
            onInspectCoverage = stableActions.inspectCoverage,
            onDismiss = { showGlyphBrowser = false },
        )
    }
    if (showSwitchHistory) {
        SwitchHistoryDialog(style = style, onDismiss = { showSwitchHistory = false })
    }
    if (restoreNotice.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { restoreNotice = "" },
            title = { Text("备份恢复结果", fontWeight = FontWeight.Black) },
            text = { Text(restoreNotice) },
            confirmButton = { TextButton(onClick = { restoreNotice = "" }) { Text("完成") } },
        )
    }
    if (showMixName) {
        AlertDialog(
            onDismissRequest = { showMixName = false },
            title = { Text("为字体组合命名") },
            text = {
                Column {
                    Text("按当前中文、英文和数字字重生成一份组合，完成后保存到字体库。")
                    OutlinedTextField(
                        value = mixName,
                        onValueChange = { mixName = it },
                        label = { Text("组合名称") },
                        singleLine = true,
                        isError = mixName.isNotEmpty() && !nameValid,
                        supportingText = { Text("1–60 个字符，不能含换行、路径分隔符或竖线") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameValid && !state.busy && !state.operationBusy,
                    onClick = {
                        showMixName = false
                        latestActions.startMix(mixName.trim())
                    },
                ) { Text("生成组合") }
            },
            dismissButton = { TextButton(onClick = { showMixName = false }) { Text("取消") } },
        )
    }
}
