/* Adapted from SukiSU-Ultra 075ac81f1dfe58b3f59c665a1b07fda8b975015f.
 * GPL-3.0-or-later; Ziyu adaptation under GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import top.yukonga.miuix.kmp.theme.Colors

/**
 * Theme packages can contain translucent semantic colors. Blend surface roles over the
 * generated palette, then make text roles opaque and readable before exposing them to UI.
 */
private fun readableTokens(
    tokens: Map<String, Int>?,
    pageBase: Color,
    cardBase: Color,
    accentBase: Color,
    textBase: Color,
    secondaryBase: Color,
    disabledBase: Color,
    errorBase: Color,
): Map<String, Int>? {
    if (tokens.isNullOrEmpty()) return tokens
    val result = tokens.toMutableMap()
    fun blend(role: String, under: Color, fallback: Color): Color? = tokens[role]?.let {
        Color(it).compositeOver(under).copy(alpha = 1f)
    } ?: fallback.copy(alpha = 1f)

    val page = blend("surface", pageBase, pageBase)!!
    val card = blend("card", page, cardBase.compositeOver(page))!!
    val accent = blend("accent", accentBase, accentBase)!!
    tokens["surface"]?.let { result["surface"] = page.toArgb() }
    tokens["card"]?.let { result["card"] = card.toArgb() }
    tokens["accent"]?.let { result["accent"] = accent.toArgb() }
    tokens["textPrimary"]?.let {
        result["textPrimary"] = ensureContrast(Color(it), listOf(page, card), 4.5f, textBase).toArgb()
    }
    tokens["textSecondary"]?.let {
        result["textSecondary"] = ensureContrast(Color(it), listOf(page, card), 4.5f, secondaryBase).toArgb()
    }
    tokens["textDisabled"]?.let {
        result["textDisabled"] = ensureContrast(Color(it), listOf(page, card), 3f, disabledBase).toArgb()
    }
    tokens["onAccent"]?.let {
        result["onAccent"] = ensureContrast(Color(it), listOf(accent), 4.5f, Color.White).toArgb()
    }
    tokens["error"]?.let {
        result["error"] = ensureContrast(Color(it), listOf(page, card), 4.5f, errorBase).toArgb()
    }
    tokens["outline"]?.let {
        result["outline"] = ensureContrast(Color(it), listOf(page, card), 3f, secondaryBase).toArgb()
    }
    return result
}

private fun ensureContrast(color: Color, backgrounds: List<Color>, minimum: Float, fallback: Color): Color {
    val foreground = color.copy(alpha = 1f)
    fun ratio(candidate: Color, background: Color): Float {
        val first = candidate.luminance()
        val second = background.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
    fun passes(candidate: Color) = backgrounds.all { ratio(candidate, it) >= minimum }
    if (passes(foreground)) return foreground

    val black = Color.Black
    val white = Color.White
    fun weakest(candidate: Color) = backgrounds.minOf { ratio(candidate, it) }
    val target = when {
        passes(black) && passes(white) -> if (weakest(black) >= weakest(white)) black else white
        passes(black) -> black
        passes(white) -> white
        else -> if (weakest(black) >= weakest(white)) black else white
    }
    if (!passes(target)) return target

    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (passes(lerp(foreground, target, middle))) high = middle else low = middle
    }
    return lerp(foreground, target, high).copy(alpha = 1f)
}

/**
 * Applies the semantic overlay to the generated Miuix palette. Only provided tokens are
 * replaced, so an overlay that fixes secondary text keeps the rest of the generated scheme.
 *
 * Keep this mapping in one place: Miuix role names are library details and may move between
 * releases, while the JSON token names are part of the theme package format.
 */
fun Colors.withAppearance(tokens: Map<String, Int>?): Colors {
    if (tokens == null || tokens.isEmpty()) return this
    val safe = readableTokens(tokens, background, surfaceContainer, primary, onSurface,
        onSurfaceVariantSummary, disabledOnSurface, error) ?: tokens
    val accent = safe["accent"]?.let(::Color)
    val onAccent = safe["onAccent"]?.let(::Color)
    val textPrimary = safe["textPrimary"]?.let(::Color)
    val textSecondary = safe["textSecondary"]?.let(::Color)
    val textDisabled = safe["textDisabled"]?.let(::Color)
    val surfaceColor = safe["surface"]?.let(::Color)
    val card = safe["card"]?.let(::Color)
    val outlineColor = safe["outline"]?.let(::Color)
    val errorColor = safe["error"]?.let(::Color)

    return copy(
        primary = accent ?: primary,
        primaryVariant = accent ?: primaryVariant,
        onPrimary = onAccent ?: onPrimary,
        onPrimaryVariant = onAccent ?: onPrimaryVariant,
        error = errorColor ?: error,
        onBackground = textPrimary ?: onBackground,
        onBackgroundVariant = textSecondary ?: onBackgroundVariant,
        onSurface = textPrimary ?: onSurface,
        onSurfaceSecondary = textPrimary ?: onSurfaceSecondary,
        onSurfaceContainer = textPrimary ?: onSurfaceContainer,
        onSurfaceContainerVariant = textPrimary ?: onSurfaceContainerVariant,
        onSurfaceContainerHigh = textPrimary ?: onSurfaceContainerHigh,
        onSurfaceContainerHighest = textPrimary ?: onSurfaceContainerHighest,
        onSurfaceVariantSummary = textSecondary ?: onSurfaceVariantSummary,
        onSurfaceVariantActions = textSecondary ?: onSurfaceVariantActions,
        disabledOnSurface = textDisabled ?: disabledOnSurface,
        disabledOnSecondary = textDisabled ?: disabledOnSecondary,
        disabledOnSecondaryVariant = textDisabled ?: disabledOnSecondaryVariant,
        surface = surfaceColor ?: surface,
        background = surfaceColor ?: background,
        surfaceContainer = card ?: surfaceContainer,
        surfaceContainerHigh = card ?: surfaceContainerHigh,
        surfaceContainerHighest = card ?: surfaceContainerHighest,
        outline = outlineColor ?: outline,
        dividerLine = outlineColor ?: dividerLine,
    )
}

/**
 * The Material 3 counterpart. Material has no disabled text role (components lower the alpha
 * themselves), so `textDisabled` intentionally has no Material mapping.
 */
fun ColorScheme.withAppearance(tokens: Map<String, Int>?): ColorScheme {
    if (tokens == null || tokens.isEmpty()) return this
    val safe = readableTokens(tokens, background, surfaceContainer, primary, onSurface,
        onSurfaceVariant, onSurface.copy(alpha = .60f), error) ?: tokens
    val accent = safe["accent"]?.let(::Color)
    val onAccent = safe["onAccent"]?.let(::Color)
    val textPrimary = safe["textPrimary"]?.let(::Color)
    val textSecondary = safe["textSecondary"]?.let(::Color)
    val surfaceColor = safe["surface"]?.let(::Color)
    val card = safe["card"]?.let(::Color)
    val outlineColor = safe["outline"]?.let(::Color)
    val errorColor = safe["error"]?.let(::Color)

    return copy(
        primary = accent ?: primary,
        onPrimary = onAccent ?: onPrimary,
        error = errorColor ?: error,
        onBackground = textPrimary ?: onBackground,
        onSurface = textPrimary ?: onSurface,
        onSurfaceVariant = textSecondary ?: onSurfaceVariant,
        surface = surfaceColor ?: surface,
        background = surfaceColor ?: background,
        surfaceContainer = card ?: surfaceContainer,
        surfaceContainerLow = card ?: surfaceContainerLow,
        surfaceContainerHigh = card ?: surfaceContainerHigh,
        surfaceContainerHighest = card ?: surfaceContainerHighest,
        outline = outlineColor ?: outline,
        outlineVariant = outlineColor ?: outlineVariant,
    )
}

fun io.github.suqi8.coui.kmp.theme.Colors.withAppearance(tokens: Map<String, Int>?): io.github.suqi8.coui.kmp.theme.Colors {
    if (tokens == null || tokens.isEmpty()) return this
    val safe = readableTokens(tokens, background, surfaceContainer, primary, onSurface,
        onSurfaceVariantSummary, disabledOnSurface, error) ?: tokens
    val accent = safe["accent"]?.let(::Color)
    val onAccent = safe["onAccent"]?.let(::Color)
    val textPrimary = safe["textPrimary"]?.let(::Color)
    val textSecondary = safe["textSecondary"]?.let(::Color)
    val textDisabled = safe["textDisabled"]?.let(::Color)
    val surfaceColor = safe["surface"]?.let(::Color)
    val card = safe["card"]?.let(::Color)
    val outlineColor = safe["outline"]?.let(::Color)
    val errorColor = safe["error"]?.let(::Color)

    return copy(
        primary = accent ?: primary,
        primaryVariant = accent ?: primaryVariant,
        onPrimary = onAccent ?: onPrimary,
        onPrimaryVariant = onAccent ?: onPrimaryVariant,
        error = errorColor ?: error,
        onBackground = textPrimary ?: onBackground,
        onBackgroundVariant = textSecondary ?: onBackgroundVariant,
        onSurface = textPrimary ?: onSurface,
        onSurfaceSecondary = textPrimary ?: onSurfaceSecondary,
        onSurfaceContainer = textPrimary ?: onSurfaceContainer,
        onSurfaceContainerVariant = textPrimary ?: onSurfaceContainerVariant,
        onSurfaceContainerHigh = textPrimary ?: onSurfaceContainerHigh,
        onSurfaceContainerHighest = textPrimary ?: onSurfaceContainerHighest,
        onSurfaceVariantSummary = textSecondary ?: onSurfaceVariantSummary,
        onSurfaceVariantActions = textSecondary ?: onSurfaceVariantActions,
        disabledOnSurface = textDisabled ?: disabledOnSurface,
        disabledOnSecondary = textDisabled ?: disabledOnSecondary,
        disabledOnSecondaryVariant = textDisabled ?: disabledOnSecondaryVariant,
        surface = surfaceColor ?: surface,
        background = surfaceColor ?: background,
        surfaceContainer = card ?: surfaceContainer,
        surfaceContainerHigh = card ?: surfaceContainerHigh,
        surfaceContainerHighest = card ?: surfaceContainerHighest,
        outline = outlineColor ?: outline,
        dividerLine = outlineColor ?: dividerLine,
    )
}
