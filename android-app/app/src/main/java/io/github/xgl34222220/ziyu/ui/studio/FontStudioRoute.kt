package io.github.xgl34222220.ziyu.ui.studio

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xgl34222220.ziyu.ZiyuViewModel
import io.github.xgl34222220.ziyu.MixSlot
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle

@Composable
internal fun FontStudioRoute(
    style: UiStyle,
    state: FontStudioUiState,
    actions: FontStudioActions,
) {
    val context = LocalContext.current
    val studioViewModel: ZiyuViewModel = viewModel()
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
    val stableActions = remember(studioViewModel) {
        FontStudioActions(
            refresh = { latestActions.refresh() },
            pickSlot = { latestActions.pickSlot(it) },
            updateFont = studioViewModel::updateMixFont,
            updateWeight = { slot, weight -> latestActions.updateWeight(slot, weight) },
            updateAxis = { slot, tag, value -> latestActions.updateAxis(slot, tag, value) },
            inspectCoverage = { latestActions.inspectCoverage(it) },
            startMix = {
                mixName = ""
                showMixName = true
            },
            applyDirect = { latestActions.applyDirect(it) },
            cancel = { latestActions.cancel() },
        )
    }

    LaunchedEffect(state.loading, state.fonts, state.slots) {
        if (!state.loading && state.slots.all { it.font != null }) {
            profileBridge.saveCurrent(encodeStudioProfile(state))
        }
        val pending = profileBridge.peekPending()
        if (pending.isBlank() || state.loading || state.fonts.isEmpty()) return@LaunchedEffect
        val parsed = parseStudioProfile(pending, state.fonts)
        profileBridge.clearPending()
        if (parsed.valid && parsed.profile != null) {
            applyStudioProfile(parsed.profile, stableActions)
            restoreNotice = buildString {
                append("备份中的组合方案已载入")
                if (parsed.warnings.isNotEmpty()) append("\n${parsed.warnings.joinToString("\n")}")
            }
        } else {
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
    val pageScale by animateFloatAsState(
        targetValue = if (childLayerActive) .96f else 1f,
        animationSpec = spring(dampingRatio = .86f, stiffness = Spring.StiffnessMediumLow),
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
            UiStyle.COUI -> FontStudioScreenMiuix(state, stableActions, studioTools)
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
