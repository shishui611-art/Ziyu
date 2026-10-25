package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layout measurements which belong to the active design system. */
@Immutable
internal data class ZiyuLayoutMetrics(
    val pageHorizontal: Dp,
    val pageTop: Dp,
    val itemGap: Dp,
    val cardGap: Dp,
    val cardPadding: Dp,
    val compactPadding: Dp,
    val cardElevation: Dp,
    val dockSafeBottom: Dp,
    val dockHorizontal: Dp,
    val dockBottomGap: Dp,
)

/** Native palette, shape and type scale exposed to shared app-shell code. */
@Immutable
internal data class ZiyuNativeThemeTokens(
    val colorScheme: ColorScheme,
    val shapes: Shapes,
    val typography: Typography,
    val layout: ZiyuLayoutMetrics,
)

internal val MaterialLayoutMetrics = ZiyuLayoutMetrics(
    pageHorizontal = 24.dp,
    pageTop = 8.dp,
    itemGap = 16.dp,
    cardGap = 16.dp,
    cardPadding = 22.dp,
    compactPadding = 16.dp,
    cardElevation = 0.dp,
    dockSafeBottom = 92.dp,
    dockHorizontal = 28.dp,
    dockBottomGap = 8.dp,
)

internal val MiuixLayoutMetrics = ZiyuLayoutMetrics(
    pageHorizontal = 22.dp,
    pageTop = 8.dp,
    itemGap = 14.dp,
    cardGap = 14.dp,
    cardPadding = 20.dp,
    compactPadding = 16.dp,
    cardElevation = 1.dp,
    dockSafeBottom = 88.dp,
    dockHorizontal = 24.dp,
    dockBottomGap = 8.dp,
)

internal val CouiLayoutMetrics = ZiyuLayoutMetrics(
    pageHorizontal = 22.dp,
    pageTop = 8.dp,
    itemGap = 14.dp,
    cardGap = 14.dp,
    cardPadding = 20.dp,
    compactPadding = 16.dp,
    cardElevation = 1.dp,
    dockSafeBottom = 88.dp,
    dockHorizontal = 24.dp,
    dockBottomGap = 8.dp,
)

internal val LocalZiyuThemeTokens = staticCompositionLocalOf {
    ZiyuNativeThemeTokens(
        colorScheme = androidx.compose.material3.lightColorScheme(),
        shapes = Shapes(),
        typography = Typography(),
        layout = MiuixLayoutMetrics,
    )
}
