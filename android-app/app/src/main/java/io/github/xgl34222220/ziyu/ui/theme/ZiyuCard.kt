package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.LocalZiyuContentColor
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface
import io.github.xgl34222220.ziyu.ui.design.nativeSquircleBorder
import io.github.xgl34222220.ziyu.ui.design.rememberNativeSquircleShape
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import io.github.suqi8.coui.kmp.basic.Card as CouiCard
import io.github.suqi8.coui.kmp.basic.CardDefaults as CouiCardDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType as MiuixPressFeedback
import io.github.suqi8.coui.kmp.utils.PressFeedbackType as CouiPressFeedback

// Identity marks the omitted argument while keeping the existing Shape parameter contract.
private val DefaultZiyuCardShape: Shape = RoundedCornerShape(0.dp)

/** Shared edge treatment for cards, including page groups implemented as Surface. */
internal object ZiyuCardDefaults {
    @Composable
    fun border(containerColor: Color = LocalZiyuPaletteTokens.current.cardBackground): BorderStroke {
        if (LocalUiStyle.current == UiStyle.MATERIAL &&
            MaterialTheme.colorScheme.background.luminance() >= .5f
        ) {
            val scheme = MaterialTheme.colorScheme
            return BorderStroke(1.dp, lerp(scheme.outlineVariant, scheme.outline, .55f))
        }
        val palette = LocalZiyuPaletteTokens.current
        val background = containerColor.compositeOver(palette.pageBackground)
        val edgeFraction = if (palette.pageBackground.luminance() < .5f) .20f else .14f
        return BorderStroke(.75.dp, lerp(background, palette.textPrimary, edgeFraction))
    }
}

/** Semantic card contract. Each design owns its surface and press feedback. */
@Composable
internal fun ZiyuCard(
    modifier: Modifier = Modifier,
    shape: Shape = DefaultZiyuCardShape,
    colors: CardColors = CardDefaults.cardColors(),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalUiStyle.current
    val background = if (enabled) colors.containerColor else colors.disabledContainerColor
    val resolvedBorder = border ?: ZiyuCardDefaults.border(background)
    val shadow = LocalZiyuThemeTokens.current.layout.cardElevation
    if (style != UiStyle.MATERIAL) {
        val foreground = if (enabled) colors.contentColor else colors.disabledContentColor
        val usesNativeShape = shape === DefaultZiyuCardShape
        val nativeClick = onClick.takeIf { enabled }
        if (!usesNativeShape) {
            ZiyuSurface(
                modifier = modifier.semantics { if (!enabled) disabled() },
                shape = shape,
                color = background,
                contentColor = foreground,
                border = resolvedBorder,
                shadowElevation = shadow,
                onClick = nativeClick,
                enabled = enabled,
                interactionSource = interactionSource,
            ) {
                Column(content = content)
            }
            return
        }
        val nativeCornerRadius = when (style) {
            UiStyle.MIUIX -> MiuixCardDefaults.CornerRadius
            UiStyle.COUI -> CouiCardDefaults.CornerRadius
            UiStyle.MATERIAL -> 0.dp
        }
        val nativeModifier = modifier
            .shadow(shadow, rememberNativeSquircleShape(style, nativeCornerRadius), clip = false)
            .nativeSquircleBorder(resolvedBorder, style, nativeCornerRadius)
            .semantics { if (!enabled) disabled() }
        val nativeContent: @Composable ColumnScope.() -> Unit = {
            CompositionLocalProvider(LocalZiyuContentColor provides foreground) { content() }
        }
        when (style) {
            UiStyle.MIUIX -> MiuixCard(
                modifier = nativeModifier,
                insideMargin = PaddingValues(0.dp),
                colors = MiuixCardDefaults.defaultColors(background, foreground),
                pressFeedbackType = MiuixPressFeedback.Sink,
                onClick = nativeClick,
                content = nativeContent,
            )
            UiStyle.COUI -> CouiCard(
                modifier = nativeModifier,
                insideMargin = PaddingValues(0.dp),
                colors = CouiCardDefaults.defaultColors(background, foreground),
                pressFeedbackType = CouiPressFeedback.Tint,
                onClick = nativeClick,
                content = nativeContent,
            )
            else -> Unit
        }
        return
    }
    val materialShape = if (shape === DefaultZiyuCardShape || shape == ZiyuShapeTokens.Card) MaterialTheme.shapes.large else shape
    // Material previously discarded the resolved default border. Its light
    // card/page surfaces can share a tone, so both card variants need the edge.
    val materialBorder = border ?: resolvedBorder.takeIf {
        MaterialTheme.colorScheme.background.luminance() >= .5f
    }
    val elevation = CardDefaults.cardElevation(
        defaultElevation = shadow,
        pressedElevation = shadow,
        focusedElevation = shadow,
        hoveredElevation = shadow,
        draggedElevation = shadow,
        disabledElevation = shadow,
    )
    val materialContent: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalZiyuContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor) { content() }
    }
    if (onClick == null) {
        MaterialCard(modifier = modifier, shape = materialShape, colors = colors,
            elevation = elevation, border = materialBorder, content = materialContent)
    } else {
        // SukiSU TonalCard uses a spring-driven press scale; reuse Card's own
        // interaction source so keyboard, touch and cancellation stay aligned.
        val interactions = interactionSource ?: remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed && enabled) .97f else 1f,
            animationSpec = spring(dampingRatio = .8f, stiffness = 400f), label = "tonalCardPress")
        MaterialCard(
            onClick = onClick, modifier = modifier.scale(scale), enabled = enabled,
            shape = materialShape, colors = colors, elevation = elevation,
            border = materialBorder, interactionSource = interactions, content = materialContent,
        )
    }
}
