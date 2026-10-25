package io.github.xgl34222220.ziyu.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet as MaterialModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState as rememberMaterialSheetState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.layout.BottomSheetDefaults as CouiBottomSheetDefaults
import io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet as CouiOverlayBottomSheet
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.LocalContentColor as CouiLocalContentColor
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults as MiuixBottomSheetDefaults
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet as MiuixOverlayBottomSheet
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val DefaultSheetDragHandle: @Composable () -> Unit = {}

/**
 * Shared dismiss request. The active design system completes its exit animation before
 * removing the sheet from its caller; a reused state is reset when the sheet leaves composition.
 */
@Stable
class ZiyuModalBottomSheetState internal constructor(
    val skipPartiallyExpanded: Boolean,
) {
    internal var dismissRequested by mutableStateOf(false)
        private set

    fun dismiss() { dismissRequested = true }

    internal fun resetDismissRequest() { dismissRequested = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberZiyuModalBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
): ZiyuModalBottomSheetState {
    return remember(skipPartiallyExpanded) { ZiyuModalBottomSheetState(skipPartiallyExpanded) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZiyuModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: ZiyuModalBottomSheetState = rememberZiyuModalBottomSheetState(),
    sheetMaxWidth: Dp = 640.dp,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color.Black.copy(alpha = 0.32f),
    dragHandle: (@Composable () -> Unit)? = DefaultSheetDragHandle,
    sheetGesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    var dismissalDelivered by remember(sheetState) { mutableStateOf(false) }
    val finishDismiss: () -> Unit = {
        if (!dismissalDelivered) {
            dismissalDelivered = true
            latestOnDismiss()
        }
    }
    DisposableEffect(sheetState) {
        onDispose { sheetState.resetDismissRequest() }
    }
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> {
            val materialState = rememberMaterialSheetState(
                skipPartiallyExpanded = sheetState.skipPartiallyExpanded,
            )
            LaunchedEffect(sheetState.dismissRequested) {
                if (sheetState.dismissRequested) {
                    materialState.hide()
                    if (!materialState.isVisible) finishDismiss()
                }
            }
            val materialHandle = if (dragHandle === DefaultSheetDragHandle) {
                { BottomSheetDefaults.DragHandle() }
            } else {
                dragHandle
            }
            val materialContainerColor = if (containerColor != Color.Unspecified) containerColor else BottomSheetDefaults.ContainerColor
            val materialContentColor = if (contentColor != Color.Unspecified) contentColor else MaterialTheme.colorScheme.onSurface
            MaterialModalBottomSheet(
                onDismissRequest = finishDismiss,
                modifier = modifier,
                sheetState = materialState,
                sheetMaxWidth = sheetMaxWidth,
                shape = shape,
                containerColor = materialContainerColor,
                contentColor = materialContentColor,
                tonalElevation = tonalElevation,
                scrimColor = scrimColor,
                dragHandle = materialHandle,
                sheetGesturesEnabled = sheetGesturesEnabled,
                content = {
                    CompositionLocalProvider(LocalZiyuContentColor provides materialContentColor) {
                        content()
                    }
                },
            )
        }

        UiStyle.MIUIX -> NativeZiyuBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            onDismissFinished = finishDismiss,
            sheetMaxWidth = sheetMaxWidth,
            containerColor = containerColor,
            contentColor = contentColor,
            dragHandle = dragHandle,
            sheetGesturesEnabled = sheetGesturesEnabled,
            nativeSheet = NativeSheet.Miuix,
            content = content,
        )

        UiStyle.COUI -> NativeZiyuBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            onDismissFinished = finishDismiss,
            sheetMaxWidth = sheetMaxWidth,
            containerColor = containerColor,
            contentColor = contentColor,
            dragHandle = dragHandle,
            sheetGesturesEnabled = sheetGesturesEnabled,
            nativeSheet = NativeSheet.Coui,
            content = content,
        )
    }
}

private enum class NativeSheet { Miuix, Coui }

@Composable
private fun NativeZiyuBottomSheet(
    modifier: Modifier,
    onDismissRequest: () -> Unit,
    sheetState: ZiyuModalBottomSheetState,
    onDismissFinished: () -> Unit,
    sheetMaxWidth: Dp,
    containerColor: Color,
    contentColor: Color,
    dragHandle: (@Composable () -> Unit)?,
    sheetGesturesEnabled: Boolean,
    nativeSheet: NativeSheet,
    content: @Composable ColumnScope.() -> Unit,
) {
    val usesNativeHandle = dragHandle === DefaultSheetDragHandle
    val hideHandle = dragHandle == null || !usesNativeHandle
    val customHandle = dragHandle.takeUnless { usesNativeHandle }
    // MIUIX/COUI use allowDismiss for both drag and Back. Preserve the existing
    // meaning of sheetGesturesEnabled (drag control) while keeping Back usable.
    BackHandler(enabled = !sheetGesturesEnabled, onBack = onDismissRequest)
    val onContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .9f)) {
            if (customHandle != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    customHandle()
                }
            }
            val resolvedColor = if (contentColor != Color.Unspecified) contentColor else when (nativeSheet) {
                NativeSheet.Miuix -> MiuixTheme.colorScheme.onSurface
                NativeSheet.Coui -> COUITheme.colorScheme.onSurface
            }
            when (nativeSheet) {
                NativeSheet.Miuix -> CompositionLocalProvider(
                    LocalContentColor provides resolvedColor,
                    LocalZiyuContentColor provides resolvedColor,
                    MiuixLocalContentColor provides resolvedColor,
                    CouiLocalContentColor provides resolvedColor,
                    content = { content() },
                )

                NativeSheet.Coui -> CompositionLocalProvider(
                    LocalContentColor provides resolvedColor,
                    LocalZiyuContentColor provides resolvedColor,
                    MiuixLocalContentColor provides resolvedColor,
                    CouiLocalContentColor provides resolvedColor,
                    content = { content() },
                )
            }
        }
    }
    val background = containerColor
    val dragColor = when {
        hideHandle -> Color.Transparent
        nativeSheet == NativeSheet.Miuix -> MiuixBottomSheetDefaults.dragHandleColor()
        else -> CouiBottomSheetDefaults.dragHandleColor()
    }
    when (nativeSheet) {
        NativeSheet.Miuix -> MiuixOverlayBottomSheet(
            show = !sheetState.dismissRequested,
            modifier = modifier,
            backgroundColor = if (background != Color.Unspecified) background else MiuixBottomSheetDefaults.backgroundColor(),
            sheetMaxWidth = sheetMaxWidth,
            dragHandleColor = dragColor,
            allowDismiss = sheetGesturesEnabled,
            enableNestedScroll = true,
            onDismissRequest = sheetState::dismiss,
            onDismissFinished = onDismissFinished,
            content = onContent,
        )

        NativeSheet.Coui -> CouiOverlayBottomSheet(
            show = !sheetState.dismissRequested,
            modifier = modifier,
            backgroundColor = if (background != Color.Unspecified) background else CouiBottomSheetDefaults.backgroundColor(),
            sheetMaxWidth = sheetMaxWidth,
            dragHandleColor = dragColor,
            allowDismiss = sheetGesturesEnabled,
            enableNestedScroll = true,
            onDismissRequest = sheetState::dismiss,
            onDismissFinished = onDismissFinished,
            content = onContent,
        )
    }
}
