package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceSettings
import top.yukonga.miuix.kmp.theme.Colors as MiuixColors
import io.github.suqi8.coui.kmp.theme.Colors as CouiColors

// Wallpaper accents remain colorful. Neutral surfaces retain their perceptual
// lightness, so deep blue no longer colors the whole page. Theme packages and
// explicit color overrides keep their own palette (overrides are applied last).
private val AppearanceSettings.balanceAutomaticSurfaces: Boolean
    get() = monetEnabled && nativeMonetEnabled && themeName.isBlank() && adaptive == null

private fun Color.quiet(): Color {
    val lab = DoubleArray(3)
    ColorUtils.colorToLAB(toArgb(), lab)
    return Color(ColorUtils.LABToColor(lab[0], lab[1] * .12, lab[2] * .12)).copy(alpha = alpha)
}

internal fun ColorScheme.withAutomaticSurfaces(settings: AppearanceSettings): ColorScheme =
    if (!settings.balanceAutomaticSurfaces) this else copy(
        background = background.quiet(), onBackground = onBackground.quiet(),
        surface = surface.quiet(), onSurface = onSurface.quiet(),
        surfaceVariant = surfaceVariant.quiet(), onSurfaceVariant = onSurfaceVariant.quiet(),
        surfaceDim = surfaceDim.quiet(), surfaceBright = surfaceBright.quiet(),
        surfaceContainerLowest = surfaceContainerLowest.quiet(),
        surfaceContainerLow = surfaceContainerLow.quiet(), surfaceContainer = surfaceContainer.quiet(),
        surfaceContainerHigh = surfaceContainerHigh.quiet(), surfaceContainerHighest = surfaceContainerHighest.quiet(),
        outline = outline.quiet(), outlineVariant = outlineVariant.quiet(),
    )

internal fun MiuixColors.withAutomaticSurfaces(settings: AppearanceSettings): MiuixColors =
    if (!settings.balanceAutomaticSurfaces) this else copy(
        background = background.quiet(), onBackground = onBackground.quiet(),
        onBackgroundVariant = onBackgroundVariant.quiet(),
        surface = surface.quiet(), onSurface = onSurface.quiet(),
        surfaceVariant = surfaceVariant.quiet(), onSurfaceSecondary = onSurfaceSecondary.quiet(),
        surfaceContainer = surfaceContainer.quiet(), surfaceContainerHigh = surfaceContainerHigh.quiet(),
        surfaceContainerHighest = surfaceContainerHighest.quiet(),
        onSurfaceContainer = onSurfaceContainer.quiet(), onSurfaceContainerVariant = onSurfaceContainerVariant.quiet(),
        onSurfaceContainerHigh = onSurfaceContainerHigh.quiet(), onSurfaceContainerHighest = onSurfaceContainerHighest.quiet(),
        onSurfaceVariantSummary = onSurfaceVariantSummary.quiet(), onSurfaceVariantActions = onSurfaceVariantActions.quiet(),
        outline = outline.quiet(), dividerLine = dividerLine.quiet(),
    )

internal fun CouiColors.withAutomaticSurfaces(settings: AppearanceSettings): CouiColors =
    if (!settings.balanceAutomaticSurfaces) this else copy(
        background = background.quiet(), onBackground = onBackground.quiet(),
        onBackgroundVariant = onBackgroundVariant.quiet(),
        surface = surface.quiet(), onSurface = onSurface.quiet(),
        surfaceVariant = surfaceVariant.quiet(), onSurfaceSecondary = onSurfaceSecondary.quiet(),
        surfaceContainer = surfaceContainer.quiet(), surfaceContainerHigh = surfaceContainerHigh.quiet(),
        surfaceContainerHighest = surfaceContainerHighest.quiet(),
        onSurfaceContainer = onSurfaceContainer.quiet(), onSurfaceContainerVariant = onSurfaceContainerVariant.quiet(),
        onSurfaceContainerHigh = onSurfaceContainerHigh.quiet(), onSurfaceContainerHighest = onSurfaceContainerHighest.quiet(),
        onSurfaceVariantSummary = onSurfaceVariantSummary.quiet(), onSurfaceVariantActions = onSurfaceVariantActions.quiet(),
        outline = outline.quiet(), dividerLine = dividerLine.quiet(),
    )
