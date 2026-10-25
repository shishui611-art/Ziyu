package io.github.xgl34222220.ziyu.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.suqi8.coui.kmp.overlay.OverlayDialog as CouiOverlayDialog
import io.github.suqi8.coui.kmp.layout.DialogDefaults as CouiDialogDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.LocalContentColor as CouiLocalContentColor
import top.yukonga.miuix.kmp.overlay.OverlayDialog as MiuixOverlayDialog
import top.yukonga.miuix.kmp.layout.DialogDefaults as MiuixDialogDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** AlertDialog-compatible entry point dispatched to the active design system. */
@Composable
fun ZiyuAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = Color.Unspecified,
    titleContentColor: Color = Color.Unspecified,
    textContentColor: Color = Color.Unspecified,
    tonalElevation: androidx.compose.ui.unit.Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> MaterialAlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon?.let { slot ->
                {
                    CompositionLocalProvider(
                        LocalZiyuContentColor provides if (iconContentColor != Color.Unspecified) iconContentColor else AlertDialogDefaults.iconContentColor,
                        content = slot,
                    )
                }
            },
            title = title?.let { slot ->
                {
                    CompositionLocalProvider(
                        LocalZiyuContentColor provides if (titleContentColor != Color.Unspecified) titleContentColor else AlertDialogDefaults.titleContentColor,
                        content = slot,
                    )
                }
            },
            text = text?.let { slot ->
                {
                    CompositionLocalProvider(
                        LocalZiyuContentColor provides if (textContentColor != Color.Unspecified) textContentColor else AlertDialogDefaults.textContentColor,
                        content = slot,
                    )
                }
            },
            shape = shape,
            containerColor = if (containerColor != Color.Unspecified) containerColor else AlertDialogDefaults.containerColor,
            iconContentColor = if (iconContentColor != Color.Unspecified) iconContentColor else AlertDialogDefaults.iconContentColor,
            titleContentColor = if (titleContentColor != Color.Unspecified) titleContentColor else AlertDialogDefaults.titleContentColor,
            textContentColor = if (textContentColor != Color.Unspecified) textContentColor else AlertDialogDefaults.textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )

        UiStyle.MIUIX -> NativeZiyuAlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            icon = icon,
            title = title,
            text = text,
            dismissButton = dismissButton,
            confirmButton = confirmButton,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            properties = properties,
            nativeDialog = NativeDialog.Miuix,
        )

        UiStyle.COUI -> NativeZiyuAlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            icon = icon,
            title = title,
            text = text,
            dismissButton = dismissButton,
            confirmButton = confirmButton,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            properties = properties,
            nativeDialog = NativeDialog.Coui,
        )
    }
}

private enum class NativeDialog { Miuix, Coui }

@Composable
private fun NativeZiyuAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
    containerColor: Color,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color,
    properties: DialogProperties,
    nativeDialog: NativeDialog,
) {
    val background = if (containerColor != Color.Unspecified) containerColor else {
        when (nativeDialog) {
            NativeDialog.Miuix -> MiuixDialogDefaults.backgroundColor()
            NativeDialog.Coui -> CouiDialogDefaults.backgroundColor()
        }
    }
    val baseContentColor = when (nativeDialog) {
        NativeDialog.Miuix -> MiuixTheme.colorScheme.onSurface
        NativeDialog.Coui -> COUITheme.colorScheme.onSurface
    }
    val content: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalContentColor provides baseContentColor,
            LocalZiyuContentColor provides baseContentColor,
            MiuixLocalContentColor provides baseContentColor,
            CouiLocalContentColor provides baseContentColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (icon != null) {
                        ProvideNativeDialogColor(iconContentColor, fallback = {
                            when (nativeDialog) {
                                NativeDialog.Miuix -> MiuixTheme.colorScheme.primary
                                NativeDialog.Coui -> COUITheme.colorScheme.primary
                            }
                        }) {
                            icon()
                        }
                    }
                    if (title != null) {
                        ProvideNativeDialogColor(titleContentColor, fallback = {
                            when (nativeDialog) {
                                NativeDialog.Miuix -> MiuixTheme.colorScheme.onSurface
                                NativeDialog.Coui -> COUITheme.colorScheme.onSurface
                            }
                        }) {
                            title()
                        }
                    }
                    if (text != null) {
                        ProvideNativeDialogColor(textContentColor, fallback = {
                            when (nativeDialog) {
                                NativeDialog.Miuix -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                NativeDialog.Coui -> COUITheme.colorScheme.onSurfaceVariantSummary
                            }
                        }) {
                            text()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
    when (nativeDialog) {
        NativeDialog.Miuix -> MiuixOverlayDialog(
            show = true,
            modifier = modifier,
            backgroundColor = background,
            onDismissRequest = if (properties.dismissOnBackPress || properties.dismissOnClickOutside) onDismissRequest else null,
            content = content,
        )

        NativeDialog.Coui -> CouiOverlayDialog(
            show = true,
            modifier = modifier,
            backgroundColor = background,
            onDismissRequest = if (properties.dismissOnBackPress || properties.dismissOnClickOutside) onDismissRequest else null,
            content = content,
        )
    }
}

@Composable
private fun ProvideNativeDialogColor(
    color: Color,
    fallback: @Composable () -> Color,
    content: @Composable () -> Unit,
) {
    val resolved = if (color != Color.Unspecified) color else fallback()
    CompositionLocalProvider(
        LocalContentColor provides resolved,
        LocalZiyuContentColor provides resolved,
        MiuixLocalContentColor provides resolved,
        CouiLocalContentColor provides resolved,
        content = content,
    )
}
