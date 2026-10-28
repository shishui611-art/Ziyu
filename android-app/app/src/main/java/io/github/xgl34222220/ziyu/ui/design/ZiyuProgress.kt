package io.github.xgl34222220.ziyu.ui.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.DividerDefaults as MaterialDividerDefaults
import androidx.compose.material3.HorizontalDivider as MaterialHorizontalDivider
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator as CouiCircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.DividerDefaults as CouiDividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider as CouiHorizontalDivider
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator as CouiLinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults as CouiProgressDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DividerDefaults as MiuixDividerDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults as MiuixProgressDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ZiyuCircularProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeWidth: Dp? = null,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> {
            val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
            val resolvedTrack = if (trackColor == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerHighest else trackColor
            val resolvedWidth = strokeWidth ?: 4.dp
            if (progress == null) MaterialCircularProgressIndicator(
                modifier = modifier, color = resolvedColor, trackColor = resolvedTrack,
                strokeWidth = resolvedWidth, strokeCap = strokeCap,
            ) else MaterialCircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) }, modifier = modifier,
                color = resolvedColor, trackColor = resolvedTrack, strokeWidth = resolvedWidth, strokeCap = strokeCap,
            )
        }
        UiStyle.MIUIX -> CircularProgressBounds(
            modifier, MiuixProgressDefaults.DefaultCircularProgressIndicatorSize,
        ) { diameter -> MiuixCircularProgressIndicator(
            modifier = Modifier.size(diameter),
            size = diameter,
            progress = progress?.coerceIn(0f, 1f),
            colors = MiuixProgressDefaults.progressIndicatorColors(
                foregroundColor = if (color == Color.Unspecified) MiuixTheme.colorScheme.primary else color,
                backgroundColor = if (trackColor == Color.Unspecified) MiuixTheme.colorScheme.secondaryContainer else trackColor,
            ),
            strokeWidth = strokeWidth ?: MiuixProgressDefaults.DefaultCircularProgressIndicatorStrokeWidth,
        ) }
        UiStyle.COUI -> CircularProgressBounds(
            modifier, CouiProgressDefaults.DefaultCircularProgressIndicatorSize,
        ) { diameter -> CouiCircularProgressIndicator(
            modifier = Modifier.size(diameter),
            size = diameter,
            progress = progress?.coerceIn(0f, 1f),
            colors = CouiProgressDefaults.circularProgressIndicatorColors(
                foregroundColor = if (color == Color.Unspecified) COUITheme.colorScheme.onSurfaceContainerHigh else color,
                backgroundColor = if (trackColor == Color.Unspecified) COUITheme.colorScheme.dividerLine else trackColor,
            ),
            strokeWidth = strokeWidth ?: CouiProgressDefaults.DefaultCircularProgressIndicatorStrokeWidth,
        ) }
    }
}

@Composable
private fun CircularProgressBounds(
    modifier: Modifier,
    defaultSize: Dp,
    content: @Composable (Dp) -> Unit,
) {
    // These libraries draw from their size argument rather than the Canvas
    // bounds. A caller's smaller Modifier.size must also resize that geometry.
    BoxWithConstraints(modifier = modifier.size(defaultSize), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight)
        if (diameter > 0.dp) content(diameter)
    }
}

@Composable
fun ZiyuCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeWidth: Dp? = null,
    strokeCap: StrokeCap = StrokeCap.Round,
) = ZiyuCircularProgressIndicator(
    modifier = modifier,
    progress = progress(),
    color = color,
    trackColor = trackColor,
    strokeWidth = strokeWidth,
    strokeCap = strokeCap,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ZiyuLinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> {
            val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
            val resolvedTrack = if (trackColor == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerHighest else trackColor
            if (progress == null) MaterialLinearProgressIndicator(
                modifier = modifier, color = resolvedColor, trackColor = resolvedTrack, strokeCap = strokeCap,
            ) else MaterialLinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) }, modifier = modifier,
                color = resolvedColor, trackColor = resolvedTrack, strokeCap = strokeCap,
            )
        }
        UiStyle.MIUIX -> MiuixLinearProgressIndicator(
            modifier = modifier,
            progress = progress?.coerceIn(0f, 1f),
            colors = MiuixProgressDefaults.progressIndicatorColors(
                foregroundColor = if (color == Color.Unspecified) MiuixTheme.colorScheme.primary else color,
                backgroundColor = if (trackColor == Color.Unspecified) MiuixTheme.colorScheme.secondaryContainer else trackColor,
            ),
        )
        UiStyle.COUI -> CouiLinearProgressIndicator(
            modifier = modifier,
            progress = progress?.coerceIn(0f, 1f),
            colors = CouiProgressDefaults.progressIndicatorColors(
                foregroundColor = if (color == Color.Unspecified) COUITheme.colorScheme.primary else color,
                backgroundColor = if (trackColor == Color.Unspecified) COUITheme.colorScheme.dividerLine else trackColor,
            ),
        )
    }
}

@Composable
fun ZiyuLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = StrokeCap.Round,
) = ZiyuLinearProgressIndicator(
    modifier = modifier,
    progress = progress(),
    color = color,
    trackColor = trackColor,
    strokeCap = strokeCap,
)

@Composable
fun ZiyuHorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp? = null,
    color: Color = Color.Unspecified,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> MaterialHorizontalDivider(
            modifier = modifier,
            thickness = thickness ?: MaterialDividerDefaults.Thickness,
            color = if (color == Color.Unspecified) MaterialDividerDefaults.color else color,
        )
        UiStyle.MIUIX -> MiuixHorizontalDivider(
            modifier = modifier,
            thickness = thickness ?: MiuixDividerDefaults.Thickness,
            color = if (color == Color.Unspecified) MiuixDividerDefaults.DividerColor else color,
        )
        UiStyle.COUI -> CouiHorizontalDivider(
            modifier = modifier,
            thickness = thickness ?: CouiDividerDefaults.Thickness,
            color = if (color == Color.Unspecified) CouiDividerDefaults.DividerColor else color,
        )
    }
}
