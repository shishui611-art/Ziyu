package io.github.xgl34222220.ziyu.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode as CouiColorSchemeMode
import io.github.suqi8.coui.kmp.theme.Colors as CouiColors
import io.github.suqi8.coui.kmp.theme.ThemeController as CouiThemeController
import io.github.suqi8.coui.kmp.theme.ThemePaletteStyle as CouiPaletteStyle
import io.github.suqi8.coui.kmp.theme.TextStyles as CouiTextStyles
import io.github.suqi8.coui.kmp.theme.defaultTextStyles as couiDefaultTextStyles
import io.github.suqi8.coui.kmp.utils.COUIPopupUtils.Companion.COUIPopupHost
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceSettings
import io.github.xgl34222220.ziyu.ui.appearance.KolorStyle
import io.github.xgl34222220.ziyu.ui.appearance.ColorStandard
import io.github.xgl34222220.ziyu.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.ziyu.ui.appearance.ThemeMode
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode as MiuixColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors as MiuixColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController as MiuixThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle as MiuixPaletteStyle
import top.yukonga.miuix.kmp.theme.ThemeColorSpec as MiuixColorSpec
import top.yukonga.miuix.kmp.theme.TextStyles as MiuixTextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles as miuixDefaultTextStyles
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val MiuixBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val CouiBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val MaterialTypography = Typography(
    displaySmall = TextStyle(fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .2.sp),
)

/** MIUIX uses its own text roles so native controls receive the MIUIX type scale. */
private val MiuixNativeTextStyles: MiuixTextStyles = miuixDefaultTextStyles(
    main = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    paragraph = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    body1 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    body2 = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    button = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    footnote1 = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    footnote2 = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    headline1 = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold),
    headline2 = TextStyle(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold),
    subtitle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    title1 = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    title2 = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    title3 = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    title4 = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)

/** COUI uses a tighter heading and control scale from its own native text roles. */
private val CouiNativeTextStyles: CouiTextStyles = couiDefaultTextStyles(
    main = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    paragraph = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    body1 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    body2 = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    button = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    footnote1 = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    footnote2 = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    headline1 = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headline2 = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    subtitle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    title1 = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    title2 = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    title3 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    title4 = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
)

private val MiuixBridgeTypography = Typography(
    displaySmall = MiuixNativeTextStyles.headline1,
    headlineLarge = MiuixNativeTextStyles.headline1,
    headlineMedium = MiuixNativeTextStyles.headline2,
    headlineSmall = MiuixNativeTextStyles.title1,
    titleLarge = MiuixNativeTextStyles.title1,
    titleMedium = MiuixNativeTextStyles.title3,
    titleSmall = MiuixNativeTextStyles.title4,
    bodyLarge = MiuixNativeTextStyles.body1,
    bodyMedium = MiuixNativeTextStyles.body2,
    bodySmall = MiuixNativeTextStyles.footnote1,
    labelLarge = MiuixNativeTextStyles.button,
    labelSmall = MiuixNativeTextStyles.footnote2,
)

private val CouiBridgeTypography = Typography(
    displaySmall = CouiNativeTextStyles.headline1,
    headlineLarge = CouiNativeTextStyles.headline1,
    headlineMedium = CouiNativeTextStyles.headline2,
    headlineSmall = CouiNativeTextStyles.title1,
    titleLarge = CouiNativeTextStyles.title1,
    titleMedium = CouiNativeTextStyles.title3,
    titleSmall = CouiNativeTextStyles.title4,
    bodyLarge = CouiNativeTextStyles.body1,
    bodyMedium = CouiNativeTextStyles.body2,
    bodySmall = CouiNativeTextStyles.footnote1,
    labelLarge = CouiNativeTextStyles.button,
    labelSmall = CouiNativeTextStyles.footnote2,
)

@Immutable
data class MiuixTokens(
    val pageBackground: Color,
    val cardBackground: Color,
    val elevatedCardBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val success: Color = Color(0xFF30D968),
    val successContainer: Color,
    val warning: Color = Color(0xFFF0A532),
)

val LocalMiuixTokens = staticCompositionLocalOf {
    MiuixTokens(
        pageBackground = Color(0xFFF1F5F9),
        cardBackground = Color.White,
        elevatedCardBackground = Color.White,
        textPrimary = Color(0xFF16171B),
        textSecondary = Color(0xFF70727C),
        textTertiary = Color(0xFF8B8D96),
        successContainer = Color(0xFFE6F4EB),
    )
}

/** Neutral semantic palette used by shared routes across all three design systems. */
internal typealias ZiyuPaletteTokens = MiuixTokens
internal val LocalZiyuPaletteTokens = LocalMiuixTokens

val LocalUiStyle = staticCompositionLocalOf { UiStyle.MIUIX }

@Composable
fun ZiyuTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    // Moving between native theme providers must retain the current route,
    // scroll positions and editing state. Keep the slot stable across branches.
    val latestContent = rememberUpdatedState(content)
    val retainedContent = remember { movableContentOf { latestContent.value() } }
    val dark = resolveDark(settings.themeMode)
    val view = LocalView.current
    SideEffect {
        view.context.findWindow()?.let { window ->
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    CompositionLocalProvider(
        LocalAppearanceSettings provides settings,
        LocalUiStyle provides settings.uiStyle,
    ) {
        when (settings.uiStyle) {
            UiStyle.MATERIAL -> ZiyuMaterialTheme(settings, retainedContent)
            UiStyle.MIUIX -> ZiyuMiuixTheme(settings, retainedContent)
            UiStyle.COUI -> ZiyuCouiTheme(settings, retainedContent)
        }
    }
}

@Composable
private fun ZiyuMaterialTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    DynamicMaterialTheme(
        primary = resolveSeedColor(settings),
        isDark = dark,
        isAmoled = pureBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        specVersion = settings.effectiveColorSpec(),
        shapes = MaterialShapes,
        typography = MaterialTypography,
        animate = true,
    ) {
        val base = MaterialTheme.colorScheme
        val scheme = if (pureBlack) base.withAmoledSurfaces() else base
        ProvideZiyuThemeData(
            style = UiStyle.MATERIAL,
            colorScheme = scheme,
            shapes = MaterialShapes,
            typography = MaterialTypography,
            preferAmoledCardSurface = pureBlack,
            content = content,
        )
    }
}

@Composable
private fun ZiyuMiuixTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    val seedColor = resolveSeedColor(settings)
    val colorSchemeMode = settings.themeMode.toMiuixColorSchemeMode()
    val paletteStyle = settings.kolorStyle.toMiuixPaletteStyle()
    val colorSpec = if (settings.effectiveColorSpec() == ColorSpec.SpecVersion.SPEC_2025) MiuixColorSpec.Spec2025 else MiuixColorSpec.Spec2021
    val controller = remember(colorSchemeMode, seedColor, paletteStyle, colorSpec) {
        MiuixThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = seedColor,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec,
        )
    }

    if (pureBlack) {
        val generatedColors = controller.currentColors()
        val nativeColors = remember(generatedColors, pureBlack) { generatedColors.withAmoledSurfaces() }
        MiuixTheme(colors = nativeColors, textStyles = MiuixNativeTextStyles) {
            ProvideZiyuThemeData(
                style = UiStyle.MIUIX,
                colorScheme = nativeColors.toMaterialColorScheme(dark = true),
                shapes = MiuixBridgeShapes,
                typography = MiuixBridgeTypography,
                preferAmoledCardSurface = true,
            ) {
                content()
                MiuixPopupHost()
            }
        }
    } else {
        MiuixTheme(controller = controller, textStyles = MiuixNativeTextStyles) {
            val nativeColors = MiuixTheme.colorScheme
            ProvideZiyuThemeData(
                style = UiStyle.MIUIX,
                colorScheme = nativeColors.toMaterialColorScheme(dark = dark),
                shapes = MiuixBridgeShapes,
                typography = MiuixBridgeTypography,
                preferAmoledCardSurface = false,
            ) {
                content()
                MiuixPopupHost()
            }
        }
    }
}

@Composable
private fun ZiyuCouiTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    val seedColor = resolveSeedColor(settings)
    val colorSchemeMode = settings.themeMode.toCouiColorSchemeMode()
    val paletteStyle = settings.kolorStyle.toCouiPaletteStyle()
    val controller = remember(colorSchemeMode, seedColor, paletteStyle) {
        CouiThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = seedColor,
            paletteStyle = paletteStyle,
        )
    }

    if (pureBlack) {
        val generatedColors = controller.currentColors()
        val nativeColors = remember(generatedColors, pureBlack) { generatedColors.withAmoledSurfaces() }
        COUITheme(colors = nativeColors, textStyles = CouiNativeTextStyles) {
            ProvideZiyuThemeData(
                style = UiStyle.COUI,
                colorScheme = nativeColors.toMaterialColorScheme(dark = true),
                shapes = CouiBridgeShapes,
                typography = CouiBridgeTypography,
                preferAmoledCardSurface = true,
            ) {
                content()
                COUIPopupHost()
            }
        }
    } else {
        COUITheme(controller = controller, textStyles = CouiNativeTextStyles) {
            val nativeColors = COUITheme.colorScheme
            ProvideZiyuThemeData(
                style = UiStyle.COUI,
                colorScheme = nativeColors.toMaterialColorScheme(dark = dark),
                shapes = CouiBridgeShapes,
                typography = CouiBridgeTypography,
                preferAmoledCardSurface = false,
            ) {
                content()
                COUIPopupHost()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProvideZiyuThemeData(
    style: UiStyle,
    colorScheme: ColorScheme,
    shapes: Shapes,
    typography: Typography,
    preferAmoledCardSurface: Boolean,
    content: @Composable () -> Unit,
) {
    // Resolve the root surface once for all native providers and the Material bridge.
    val pageBackground = if (preferAmoledCardSurface) Color.Black else colorScheme.background
    val dark = pageBackground.luminance() < .5f
    val tokens = MiuixTokens(
        pageBackground = if (LocalAppearanceSettings.current.backgroundPath.isNotBlank()) Color.Transparent else pageBackground,
        cardBackground = when {
            style == UiStyle.MATERIAL -> if (preferAmoledCardSurface) colorScheme.surfaceContainerLow else colorScheme.surfaceBright
            preferAmoledCardSurface -> colorScheme.surfaceContainerLow
            // Native CardDefaults uses surfaceContainer, not the page/dialog surface.
            // Resolve translucent COUI container roles over the page exactly once.
            else -> colorScheme.surfaceContainer.compositeOver(pageBackground)
        },
        elevatedCardBackground = colorScheme.surfaceContainerHigh.compositeOver(pageBackground),
        textPrimary = colorScheme.onSurface,
        textSecondary = colorScheme.onSurfaceVariant,
        textTertiary = colorScheme.onSurfaceVariant.copy(alpha = .78f),
        success = if (dark) Color(0xFF72D9A0) else Color(0xFF187B58),
        successContainer = if (dark) Color(0xFF153D27) else Color(0xFFE6F4EB),
        warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319),
    )
    val nativeTokens = ZiyuNativeThemeTokens(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = typography,
        layout = style.layoutMetrics(),
    )
    val themeContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalMiuixTokens provides tokens,
            LocalZiyuThemeTokens provides nativeTokens,
            LocalContentColor provides colorScheme.onSurface,
            content = content,
        )
    }
    if (style == UiStyle.MATERIAL) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme, motionScheme = MotionScheme.expressive(),
            shapes = shapes, typography = typography, content = themeContent,
        )
    } else {
        MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography, content = themeContent)
    }
}

private data class NativeColorRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
)

private fun MiuixColors.toMaterialColorScheme(dark: Boolean): ColorScheme =
    NativeColorRoles(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiaryContainerVariant,
        onTertiary = onTertiaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = dividerLine,
    ).toMaterialColorScheme(dark)

private fun CouiColors.toMaterialColorScheme(dark: Boolean): ColorScheme =
    NativeColorRoles(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiaryContainerVariant,
        onTertiary = onTertiaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = dividerLine,
    ).toMaterialColorScheme(dark)

private fun NativeColorRoles.toMaterialColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
    )
}

private fun MiuixColors.withAmoledSurfaces(): MiuixColors = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF17191D),
    primaryVariant = Color(0xFF17191D),
    surfaceContainer = Color(0xFF101114),
    surfaceContainerHigh = Color(0xFF17191D),
    surfaceContainerHighest = Color(0xFF202329),
)

private fun CouiColors.withAmoledSurfaces(): CouiColors = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF17191D),
    primaryVariant = Color(0xFF17191D),
    surfaceContainer = Color(0xFF101114),
    surfaceContainerHigh = Color(0xFF17191D),
    surfaceContainerHighest = Color(0xFF202329),
)

private fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF101114),
    surfaceContainer = Color(0xFF17191D),
    surfaceContainerHigh = Color(0xFF202329),
    surfaceContainerHighest = Color(0xFF292D33),
)

private fun UiStyle.layoutMetrics() = when (this) {
    UiStyle.MATERIAL -> MaterialLayoutMetrics
    UiStyle.MIUIX -> MiuixLayoutMetrics
    UiStyle.COUI -> CouiLayoutMetrics
}

@Composable
private fun resolveSeedColor(settings: AppearanceSettings): Color {
    val resources = LocalResources.current
    return if (settings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color(resources.getColor(android.R.color.system_accent1_500, null))
    } else {
        Color(settings.seedArgb)
    }
}

@Composable
private fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private fun ThemeMode.toMiuixColorSchemeMode() = when (this) {
    ThemeMode.SYSTEM -> MiuixColorSchemeMode.MonetSystem
    ThemeMode.LIGHT -> MiuixColorSchemeMode.MonetLight
    ThemeMode.DARK -> MiuixColorSchemeMode.MonetDark
}

private fun ThemeMode.toCouiColorSchemeMode() = when (this) {
    ThemeMode.SYSTEM -> CouiColorSchemeMode.MonetSystem
    ThemeMode.LIGHT -> CouiColorSchemeMode.MonetLight
    ThemeMode.DARK -> CouiColorSchemeMode.MonetDark
}

private fun KolorStyle.toPaletteStyle(): PaletteStyle = PaletteStyle.valueOf(paletteName)

private fun KolorStyle.toMiuixPaletteStyle() = MiuixPaletteStyle.valueOf(paletteName)

private fun KolorStyle.toCouiPaletteStyle() = CouiPaletteStyle.valueOf(paletteName)

// Match SukiSU's supported 2025 palettes. Other variants deliberately keep the
// 2021 rules instead of applying a partially supported palette specification.
private fun AppearanceSettings.effectiveColorSpec(): ColorSpec.SpecVersion =
    if (colorStandard == ColorStandard.SPEC_2025 && kolorStyle.supportsSpec2025) ColorSpec.SpecVersion.SPEC_2025
    else ColorSpec.SpecVersion.SPEC_2021

/** Preview only: no nested app themes, popup hosts or system-bar side effects. */
@Composable
internal fun rememberAppearancePreviewColors(settings: AppearanceSettings, dark: Boolean): ColorScheme {
    val seed = resolveSeedColor(settings)
    if (settings.uiStyle == UiStyle.MIUIX) {
        val spec = if (settings.effectiveColorSpec() == ColorSpec.SpecVersion.SPEC_2025) MiuixColorSpec.Spec2025 else MiuixColorSpec.Spec2021
        val controller = remember(seed, dark, settings.kolorStyle, spec) {
            MiuixThemeController(
                colorSchemeMode = if (dark) MiuixColorSchemeMode.MonetDark else MiuixColorSchemeMode.MonetLight,
                keyColor = seed, paletteStyle = settings.kolorStyle.toMiuixPaletteStyle(), colorSpec = spec,
            )
        }
        val colors = controller.currentColors()
        return (if (dark && settings.amoledBlack) colors.withAmoledSurfaces() else colors).toMaterialColorScheme(dark)
    }
    if (settings.uiStyle == UiStyle.COUI) {
        val controller = remember(seed, dark, settings.kolorStyle) {
            CouiThemeController(
                colorSchemeMode = if (dark) CouiColorSchemeMode.MonetDark else CouiColorSchemeMode.MonetLight,
                keyColor = seed, paletteStyle = settings.kolorStyle.toCouiPaletteStyle(),
            )
        }
        val colors = controller.currentColors()
        return (if (dark && settings.amoledBlack) colors.withAmoledSurfaces() else colors).toMaterialColorScheme(dark)
    }
    val colors = rememberDynamicColorScheme(
        seedColor = seed, isDark = dark, isAmoled = dark && settings.amoledBlack,
        style = settings.kolorStyle.toPaletteStyle(), specVersion = settings.effectiveColorSpec(),
    )
    return if (dark && settings.amoledBlack) colors.withAmoledSurfaces() else colors
}

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
