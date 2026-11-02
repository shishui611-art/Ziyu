package io.github.xgl34222220.ziyu.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FilledTonalButton as MaterialFilledTonalButton
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.suqi8.coui.kmp.basic.Button as CouiButton
import io.github.suqi8.coui.kmp.basic.ButtonColors as CouiButtonColors
import io.github.suqi8.coui.kmp.basic.ButtonDefaults as CouiButtonDefaults
import io.github.suqi8.coui.kmp.basic.IconButton as CouiIconButton
import io.github.suqi8.coui.kmp.basic.Surface as CouiSurface
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonColors as MiuixButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ActionButtonKind { Filled, Tonal, Outlined, Text }

@Composable
private fun ZiyuActionButton(
    kind: ActionButtonKind,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape?,
    colors: ButtonColors?,
    elevation: ButtonElevation?,
    border: BorderStroke?,
    contentPadding: PaddingValues?,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    when (uiStyle) {
        UiStyle.MATERIAL -> {
            val materialShape = shape ?: when (kind) {
                ActionButtonKind.Filled -> ButtonDefaults.shape
                ActionButtonKind.Tonal -> ButtonDefaults.filledTonalShape
                ActionButtonKind.Outlined -> ButtonDefaults.outlinedShape
                ActionButtonKind.Text -> ButtonDefaults.textShape
            }
            val materialColors = colors ?: when (kind) {
                ActionButtonKind.Filled -> ButtonDefaults.buttonColors()
                ActionButtonKind.Tonal -> ButtonDefaults.filledTonalButtonColors()
                ActionButtonKind.Outlined -> ButtonDefaults.outlinedButtonColors()
                ActionButtonKind.Text -> ButtonDefaults.textButtonColors()
            }
            val materialElevation = elevation ?: when (kind) {
                ActionButtonKind.Filled -> ButtonDefaults.buttonElevation()
                ActionButtonKind.Tonal -> ButtonDefaults.filledTonalButtonElevation()
                ActionButtonKind.Outlined, ActionButtonKind.Text -> null
            }
            val padding = contentPadding ?: if (kind == ActionButtonKind.Text) {
                ButtonDefaults.TextButtonContentPadding
            } else {
                ButtonDefaults.ContentPadding
            }
            val buttonContentColor = if (enabled) {
                materialColors.contentColor
            } else {
                materialColors.disabledContentColor
            }
            val actualContent: @Composable RowScope.() -> Unit = {
                CompositionLocalProvider(LocalZiyuContentColor provides buttonContentColor) { content() }
            }
            when (kind) {
                ActionButtonKind.Filled -> MaterialButton(
                    onClick, modifier, enabled, materialShape, materialColors,
                    materialElevation, border, padding, interactionSource, actualContent,
                )
                ActionButtonKind.Tonal -> MaterialFilledTonalButton(
                    onClick, modifier, enabled, materialShape, materialColors,
                    materialElevation, border, padding, interactionSource, actualContent,
                )
                ActionButtonKind.Outlined -> MaterialOutlinedButton(
                    onClick, modifier, enabled, materialShape, materialColors,
                    materialElevation, border ?: ButtonDefaults.outlinedButtonBorder(enabled),
                    padding, interactionSource, actualContent,
                )
                ActionButtonKind.Text -> MaterialTextButton(
                    onClick, modifier, enabled, materialShape, materialColors,
                    materialElevation, border, padding, interactionSource, actualContent,
                )
            }
        }
        UiStyle.MIUIX -> {
            val nativeDefaults = MiuixButtonDefaults.buttonColors()
            val nativePrimaryDefaults = MiuixButtonDefaults.buttonColorsPrimary()
            val nativeColors = nativeButtonColors(kind, colors, nativeDefaults, nativePrimaryDefaults)
            val buttonContentColor = if (enabled) nativeColors.contentColor else nativeColors.disabledContentColor
            val resolvedBorder = nativeButtonBorder(border, kind, enabled, UiStyle.MIUIX)
            if (shape != null) {
                NativeShapeButton(
                    style = UiStyle.MIUIX,
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    shape = shape,
                    containerColor = if (enabled) nativeColors.color else nativeColors.disabledColor,
                    contentColor = buttonContentColor,
                    border = resolvedBorder,
                    contentPadding = contentPadding ?: MiuixButtonDefaults.InsideMargin,
                    minWidth = MiuixButtonDefaults.MinWidth,
                    minHeight = MiuixButtonDefaults.MinHeight,
                    interactionSource = interactionSource,
                    content = content,
                )
            } else {
                MiuixButton(
                    onClick = onClick,
                    modifier = nativeButtonModifier(modifier, border, kind, enabled),
                    enabled = enabled,
                    colors = nativeColors,
                    insideMargin = contentPadding ?: MiuixButtonDefaults.InsideMargin,
                    interactionSource = interactionSource,
                    content = { NativeActionButtonContent(UiStyle.MIUIX, buttonContentColor, content) },
                )
            }
        }
        UiStyle.COUI -> {
            val nativeDefaults = CouiButtonDefaults.buttonColors()
            val nativePrimaryDefaults = CouiButtonDefaults.buttonColorsPrimary()
            val nativeColors = nativeCouiButtonColors(kind, colors, nativeDefaults, nativePrimaryDefaults)
            val buttonContentColor = if (enabled) nativeColors.contentColor else nativeColors.disabledContentColor
            val resolvedBorder = nativeButtonBorder(border, kind, enabled, UiStyle.COUI)
            if (shape != null) {
                NativeShapeButton(
                    style = UiStyle.COUI,
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    shape = shape,
                    containerColor = if (enabled) nativeColors.color else nativeColors.disabledColor,
                    contentColor = buttonContentColor,
                    border = resolvedBorder,
                    contentPadding = contentPadding ?: CouiButtonDefaults.InsideMargin,
                    minWidth = CouiButtonDefaults.MinWidth,
                    minHeight = CouiButtonDefaults.MinHeight,
                    interactionSource = interactionSource,
                    content = content,
                )
            } else {
                CouiButton(
                    onClick = onClick,
                    modifier = nativeButtonModifier(modifier, border, kind, enabled),
                    enabled = enabled,
                    colors = nativeColors,
                    insideMargin = contentPadding ?: CouiButtonDefaults.InsideMargin,
                    interactionSource = interactionSource,
                    content = { NativeActionButtonContent(UiStyle.COUI, buttonContentColor, content) },
                )
            }
        }
    }
}

@Composable
private fun nativeButtonModifier(
    modifier: Modifier,
    border: BorderStroke?,
    kind: ActionButtonKind,
    enabled: Boolean,
): Modifier {
    val style = LocalUiStyle.current
    val resolvedBorder = nativeButtonBorder(border, kind, enabled, style) ?: return modifier
    val nativeCornerRadius = when (style) {
        UiStyle.MIUIX -> MiuixButtonDefaults.CornerRadius
        UiStyle.COUI -> CouiButtonDefaults.CornerRadius
        UiStyle.MATERIAL -> return modifier
    }
    return modifier.nativeSquircleBorder(resolvedBorder, style, nativeCornerRadius)
}

@Composable
private fun nativeButtonBorder(
    border: BorderStroke?,
    kind: ActionButtonKind,
    enabled: Boolean,
    style: UiStyle,
): BorderStroke? {
    if (border != null || kind != ActionButtonKind.Outlined) return border
    val outline = when (style) {
        UiStyle.MATERIAL -> MaterialTheme.colorScheme.outline
        UiStyle.MIUIX -> MiuixTheme.colorScheme.outline
        UiStyle.COUI -> COUITheme.colorScheme.outline
    }
    return BorderStroke(1.dp, if (enabled) outline else outline.copy(alpha = .38f))
}

@Composable
private fun RowScope.NativeActionButtonContent(
    style: UiStyle,
    contentColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val textStyle = LocalZiyuTextStyle.current ?: when (style) {
        UiStyle.MIUIX -> MiuixTheme.textStyles.button
        UiStyle.COUI -> COUITheme.textStyles.button
        UiStyle.MATERIAL -> MaterialTheme.typography.labelLarge
    }
    CompositionLocalProvider(
        LocalZiyuContentColor provides contentColor,
        LocalZiyuTextStyle provides textStyle,
    ) { content() }
}

@Composable
private fun NativeShapeButton(
    style: UiStyle,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    minWidth: Dp,
    minHeight: Dp,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    ZiyuSurface(
        modifier = modifier.semantics { role = Role.Button },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minWidth = minWidth, minHeight = minHeight).padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NativeActionButtonContent(style, contentColor, content)
        }
    }
}

@Composable
private fun nativeButtonColors(
    kind: ActionButtonKind,
    colors: ButtonColors?,
    defaults: MiuixButtonColors,
    primaryDefaults: MiuixButtonColors,
): MiuixButtonColors = MiuixButtonDefaults.buttonColors(
    color = colors?.containerColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.color
        ActionButtonKind.Tonal -> MiuixTheme.colorScheme.primaryContainer
        ActionButtonKind.Outlined, ActionButtonKind.Text -> Color.Transparent
    },
    disabledColor = colors?.disabledContainerColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.disabledColor
        ActionButtonKind.Tonal -> MiuixTheme.colorScheme.primaryContainer.copy(alpha = .38f)
        ActionButtonKind.Outlined, ActionButtonKind.Text -> Color.Transparent
    },
    contentColor = colors?.contentColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.contentColor
        ActionButtonKind.Tonal -> MiuixTheme.colorScheme.onPrimaryContainer
        ActionButtonKind.Text, ActionButtonKind.Outlined -> MiuixTheme.colorScheme.primary
    },
    disabledContentColor = colors?.disabledContentColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.disabledContentColor
        ActionButtonKind.Tonal -> MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = .38f)
        ActionButtonKind.Text, ActionButtonKind.Outlined -> defaults.disabledContentColor
    },
)

@Composable
private fun nativeCouiButtonColors(
    kind: ActionButtonKind,
    colors: ButtonColors?,
    defaults: CouiButtonColors,
    primaryDefaults: CouiButtonColors,
): CouiButtonColors = CouiButtonDefaults.buttonColors(
    color = colors?.containerColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.color
        ActionButtonKind.Tonal -> COUITheme.colorScheme.primaryContainer
        ActionButtonKind.Outlined, ActionButtonKind.Text -> Color.Transparent
    },
    disabledColor = colors?.disabledContainerColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.disabledColor
        ActionButtonKind.Tonal -> COUITheme.colorScheme.primaryContainer.copy(alpha = .38f)
        ActionButtonKind.Outlined, ActionButtonKind.Text -> Color.Transparent
    },
    contentColor = colors?.contentColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.contentColor
        ActionButtonKind.Tonal -> COUITheme.colorScheme.onPrimaryContainer
        ActionButtonKind.Text, ActionButtonKind.Outlined -> COUITheme.colorScheme.primary
    },
    disabledContentColor = colors?.disabledContentColor ?: when (kind) {
        ActionButtonKind.Filled -> primaryDefaults.disabledContentColor
        ActionButtonKind.Tonal -> COUITheme.colorScheme.onPrimaryContainer.copy(alpha = .38f)
        ActionButtonKind.Text, ActionButtonKind.Outlined -> defaults.disabledContentColor
    },
)

@Composable
fun ZiyuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = ZiyuActionButton(
    ActionButtonKind.Filled, onClick, modifier, enabled, shape, colors, elevation,
    border, contentPadding, interactionSource, content,
)

@Composable
fun ZiyuFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = ZiyuActionButton(
    ActionButtonKind.Tonal, onClick, modifier, enabled, shape, colors, elevation,
    border, contentPadding, interactionSource, content,
)

@Composable
fun ZiyuOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = ZiyuActionButton(
    ActionButtonKind.Outlined, onClick, modifier, enabled, shape, colors, elevation,
    border, contentPadding, interactionSource, content,
)

@Composable
fun ZiyuTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = ZiyuActionButton(
    ActionButtonKind.Text, onClick, modifier, enabled, shape, colors, elevation,
    border, contentPadding, interactionSource, content,
)

@Composable
fun ZiyuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> {
            val materialColors = colors ?: IconButtonDefaults.iconButtonColors()
            val iconContentColor = if (enabled) materialColors.contentColor else materialColors.disabledContentColor
            MaterialIconButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                colors = materialColors,
                interactionSource = interactionSource,
            ) {
                CompositionLocalProvider(LocalZiyuContentColor provides iconContentColor, content = content)
            }
        }
        UiStyle.MIUIX -> {
            val nativeBackground = colors?.let {
                if (enabled) it.containerColor else it.disabledContainerColor
            } ?: Color.Unspecified
            val nativeContentColor = colors?.let {
                if (enabled) it.contentColor else it.disabledContentColor
            } ?: if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurface.copy(alpha = .38f)
            }
            MiuixIconButton(
                onClick = onClick,
                modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
                enabled = enabled,
                backgroundColor = nativeBackground,
            ) {
                CompositionLocalProvider(LocalZiyuContentColor provides nativeContentColor, content = content)
            }
        }
        UiStyle.COUI -> {
            val nativeBackground = colors?.let {
                if (enabled) it.containerColor else it.disabledContainerColor
            } ?: Color.Unspecified
            val nativeContentColor = colors?.let {
                if (enabled) it.contentColor else it.disabledContentColor
            } ?: if (enabled) {
                COUITheme.colorScheme.onSurface
            } else {
                COUITheme.colorScheme.onSurface.copy(alpha = .38f)
            }
            CouiIconButton(
                onClick = onClick,
                modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
                enabled = enabled,
                backgroundColor = nativeBackground,
            ) {
                CompositionLocalProvider(LocalZiyuContentColor provides nativeContentColor, content = content)
            }
        }
    }
}

/** Translucent fills expose the hardware shadow caster's interior occlusion region. */
internal fun ziyuSurfaceShadowElevation(containerColor: Color, requested: Dp): Dp =
    if (containerColor.alpha < 1f) 0.dp else requested

/** Surface wrapper whose non-Material branches stay on the active native surface implementation. */
@Composable
fun ZiyuSurface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val style = LocalUiStyle.current
    when (style) {
        UiStyle.MATERIAL -> {
            val resolvedColor = color.ifUnspecified(MaterialTheme.colorScheme.surface)
            val resolvedContentColor = contentColor.ifUnspecified(
                LocalZiyuContentColor.current ?: androidx.compose.material3.contentColorFor(resolvedColor),
            )
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(LocalZiyuContentColor provides resolvedContentColor, content = content)
            }
            if (onClick == null) {
                MaterialSurface(
                    modifier = modifier,
                    shape = shape ?: MaterialTheme.shapes.medium,
                    color = resolvedColor,
                    contentColor = resolvedContentColor,
                    tonalElevation = tonalElevation,
                    shadowElevation = ziyuSurfaceShadowElevation(resolvedColor, shadowElevation),
                    border = border,
                    content = body,
                )
            } else {
                MaterialSurface(
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    shape = shape ?: MaterialTheme.shapes.medium,
                    color = resolvedColor,
                    contentColor = resolvedContentColor,
                    tonalElevation = tonalElevation,
                    shadowElevation = ziyuSurfaceShadowElevation(resolvedColor, shadowElevation),
                    border = border,
                    interactionSource = interactionSource,
                    content = body,
                )
            }
        }
        UiStyle.MIUIX -> {
            val resolvedColor = color.ifUnspecified(MiuixTheme.colorScheme.surface)
            val resolvedContentColor = contentColor.ifUnspecified(
                LocalZiyuContentColor.current ?: MiuixTheme.colorScheme.onSurface,
            )
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(LocalZiyuContentColor provides resolvedContentColor, content = content)
            }
            if (onClick == null) {
                MiuixSurface(
                    modifier = modifier,
                    shape = shape ?: top.yukonga.miuix.kmp.basic.SurfaceDefaults.Shape,
                    color = resolvedColor,
                    contentColor = resolvedContentColor,
                    border = border,
                    shadowElevation = ziyuSurfaceShadowElevation(resolvedColor, shadowElevation),
                    content = body,
                )
            } else {
                MiuixSurface(
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    shape = shape ?: top.yukonga.miuix.kmp.basic.SurfaceDefaults.Shape,
                    color = resolvedColor,
                    contentColor = resolvedContentColor,
                    border = border,
                    shadowElevation = ziyuSurfaceShadowElevation(resolvedColor, shadowElevation),
                    interactionSource = interactionSource,
                    content = body,
                )
            }
        }
        UiStyle.COUI -> {
            val resolvedColor = color.ifUnspecified(COUITheme.colorScheme.surface)
            val resolvedContentColor = contentColor.ifUnspecified(
                LocalZiyuContentColor.current ?: COUITheme.colorScheme.onSurface,
            )
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(LocalZiyuContentColor provides resolvedContentColor, content = content)
            }
            if (onClick == null) {
                CouiSurface(
                    modifier = modifier,
                    shape = shape ?: io.github.suqi8.coui.kmp.basic.SurfaceDefaults.Shape,
                    color = resolvedColor,
                    contentColor = resolvedContentColor,
                    border = border,
                    shadowElevation = ziyuSurfaceShadowElevation(resolvedColor, shadowElevation),
                    content = body,
                )
            } else {
                CouiSurface(
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    shape = shape ?: io.github.suqi8.coui.kmp.basic.SurfaceDefaults.Shape,
                    color = resolvedColor,
                    contentColor = resolvedContentColor,
                    border = border,
                    shadowElevation = ziyuSurfaceShadowElevation(resolvedColor, shadowElevation),
                    interactionSource = interactionSource,
                    content = body,
                )
            }
        }
    }
}

private fun Color.ifUnspecified(fallback: Color): Color =
    if (this == Color.Unspecified) fallback else this
