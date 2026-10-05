package io.github.xgl34222220.luoshu.ui.theme

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.theme.ThemePaletteStyle
import io.github.xgl34222220.luoshu.ui.appearance.AppearanceSettings
import io.github.xgl34222220.luoshu.ui.appearance.KolorStyle
import io.github.xgl34222220.luoshu.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.luoshu.ui.appearance.ThemeMode
import io.github.xgl34222220.luoshu.ui.appearance.UiStyle

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp),
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

private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val MiuixTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = .15.sp),
)

private val CouiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val CouiTypography = Typography(
    displaySmall = TextStyle(fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .15.sp),
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

val LocalUiStyle = staticCompositionLocalOf { UiStyle.MIUIX }

@Composable
fun LuoShuTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
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
            UiStyle.MATERIAL -> LuoShuMaterialTheme(settings, content)
            UiStyle.MIUIX -> LuoShuMiuixTheme(settings, content)
            UiStyle.COUI -> LuoShuCouiTheme(settings, content)
        }
    }
}

@Composable
private fun LuoShuMaterialTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    DynamicMaterialTheme(
        primary = resolveSeedColor(settings),
        isDark = resolveDark(settings.themeMode),
        isAmoled = settings.amoledBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = MaterialShapes,
        typography = MaterialTypography,
        animate = true,
    ) {
        ProvideMaterialTokens(settings, content)
    }
}

@Composable
private fun LuoShuMiuixTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    DynamicMaterialTheme(
        primary = resolveSeedColor(settings),
        isDark = dark,
        isAmoled = pureBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = MiuixShapes,
        typography = MiuixTypography,
        animate = true,
    ) {
        ProvideMiuixTokens(settings, content)
    }
}

@Composable
private fun LuoShuCouiTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    val seedColor = resolveSeedColor(settings)
    val couiMode = when (settings.themeMode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        ThemeMode.DARK -> ColorSchemeMode.MonetDark
    }
    val couiPalette = when (settings.kolorStyle) {
        KolorStyle.SOFT -> ThemePaletteStyle.TonalSpot
        KolorStyle.VIBRANT -> ThemePaletteStyle.Vibrant
        KolorStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    }
    val couiController = remember(settings, seedColor) {
        ThemeController(
            colorSchemeMode = couiMode,
            keyColor = seedColor,
            paletteStyle = couiPalette,
        )
    }
    COUITheme(controller = couiController) {
        DynamicMaterialTheme(
            primary = seedColor,
            isDark = dark,
            isAmoled = pureBlack,
            style = settings.kolorStyle.toPaletteStyle(),
            shapes = CouiShapes,
            typography = CouiTypography,
            animate = true,
        ) {
            ProvideCouiTokens(settings, content)
        }
    }
}

@Composable
private fun ProvideMaterialTokens(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = resolveDark(settings.themeMode)
    val scheme = if (dark && settings.amoledBlack) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF101114),
            surfaceContainer = Color(0xFF17191D),
            surfaceContainerHigh = Color(0xFF202329),
            surfaceContainerHighest = Color(0xFF292D33),
        )
    } else base
    val tokens = MiuixTokens(
        pageBackground = scheme.background,
        cardBackground = scheme.surfaceContainerLow,
        elevatedCardBackground = scheme.surfaceContainerHigh,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.onSurfaceVariant.copy(alpha = .78f),
        success = if (dark) Color(0xFF72D9A0) else Color(0xFF187B58),
        successContainer = if (dark) Color(0xFF153D27) else Color(0xFFE6F4EB),
        warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319),
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialShapes,
        typography = MaterialTypography,
    ) {
        CompositionLocalProvider(
            LocalMiuixTokens provides tokens,
            LocalContentColor provides scheme.onSurface,
            content = content,
        )
    }
}

@Composable
private fun ProvideCouiTokens(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val coui = COUITheme.colorScheme
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    val base = MaterialTheme.colorScheme
    val scheme = base.copy(
        primary = coui.primary,
        onPrimary = coui.onPrimary,
        primaryContainer = coui.primaryContainer,
        onPrimaryContainer = coui.onPrimaryContainer,
        secondary = coui.secondary,
        onSecondary = coui.onSecondary,
        secondaryContainer = coui.secondaryContainer,
        onSecondaryContainer = coui.onSecondaryContainer,
        tertiaryContainer = coui.tertiaryContainer,
        onTertiaryContainer = coui.onTertiaryContainer,
        background = if (pureBlack) Color.Black else coui.background,
        onBackground = coui.onBackground,
        surface = if (pureBlack) Color.Black else coui.surface,
        onSurface = coui.onSurface,
        surfaceVariant = coui.surfaceVariant,
        onSurfaceVariant = coui.onSurfaceVariantSummary,
        surfaceContainerLowest = if (pureBlack) Color.Black else coui.surface,
        surfaceContainerLow = if (pureBlack) Color(0xFF101114) else coui.surfaceContainer,
        surfaceContainer = if (pureBlack) Color(0xFF17191D) else coui.surfaceContainer,
        surfaceContainerHigh = if (pureBlack) Color(0xFF202329) else coui.surfaceContainerHigh,
        surfaceContainerHighest = if (pureBlack) Color(0xFF292D33) else coui.surfaceContainerHighest,
        error = coui.error,
        onError = coui.onError,
        errorContainer = coui.errorContainer,
        onErrorContainer = coui.onErrorContainer,
        outline = coui.outline,
        outlineVariant = coui.dividerLine,
    )
    val tokens = MiuixTokens(
        pageBackground = scheme.background,
        cardBackground = scheme.surfaceContainerLow,
        elevatedCardBackground = scheme.surfaceContainerHigh,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.onSurfaceVariant.copy(alpha = .78f),
        success = if (dark) Color(0xFF72D9A0) else Color(0xFF187B58),
        successContainer = if (dark) Color(0xFF153D27) else Color(0xFFE6F4EB),
        warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319),
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = CouiShapes,
        typography = CouiTypography,
    ) {
        CompositionLocalProvider(
            LocalMiuixTokens provides tokens,
            LocalContentColor provides scheme.onSurface,
            content = content,
        )
    }
}

/** Shared screens use the same resolved palette in either appearance mode. */
@Composable
private fun ProvideMiuixTokens(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val pureBlack = dark && settings.amoledBlack
    val baseScheme = MaterialTheme.colorScheme
    val scheme = baseScheme.copy(
        background = when {
            pureBlack -> Color.Black
            dark -> Color(0xFF0A0A0B)
            else -> Color(0xFFF3F6FA)
        },
        onBackground = if (dark) Color(0xFFF5F5F7) else Color(0xFF17181C),
        surface = if (dark) Color(0xFF1C1C1E) else Color.White,
        onSurface = if (dark) Color(0xFFF5F5F7) else Color(0xFF17181C),
        surfaceVariant = if (dark) Color(0xFF2C2C2E) else Color(0xFFF1F2F5),
        onSurfaceVariant = if (dark) Color(0xFFA8A8AD) else Color(0xFF5F626A),
        surfaceContainerLowest = if (dark) Color(0xFF151517) else Color.White,
        surfaceContainerLow = if (dark) Color(0xFF1B1B1D) else Color(0xFFF8F9FB),
        surfaceContainer = if (dark) Color(0xFF222224) else Color(0xFFF1F2F5),
        surfaceContainerHigh = if (dark) Color(0xFF29292B) else Color(0xFFECEEF2),
        surfaceContainerHighest = if (dark) Color(0xFF303033) else Color(0xFFE5E8ED),
        error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        errorContainer = if (dark) Color(0xFF690005) else Color(0xFFF9DEDC),
        onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410E0B),
        outline = if (dark) Color(0xFF8E8E93) else Color(0xFF777A82),
        outlineVariant = if (dark) Color(0xFF49494D) else Color(0xFFD8DBE1),
    )
    val tokens = MiuixTokens(
        pageBackground = when {
            pureBlack -> Color.Black
            dark -> Color(0xFF0A0A0B)
            else -> lerp(scheme.background, scheme.primaryContainer, .025f)
        },
        cardBackground = when {
            pureBlack -> Color(0xFF111214)
            dark -> Color(0xFF1C1C1E)
            else -> scheme.surfaceContainerLowest
        },
        elevatedCardBackground = when {
            pureBlack -> Color(0xFF1B1C20)
            dark -> Color(0xFF252528)
            else -> lerp(scheme.surfaceContainerLowest, scheme.primaryContainer, .12f)
        },
        textPrimary = if (dark) Color(0xFFF5F5F7) else scheme.onSurface,
        textSecondary = if (dark) Color(0xFFA0A0A5) else scheme.onSurfaceVariant,
        textTertiary = if (dark) Color(0xFF707075) else scheme.onSurfaceVariant.copy(alpha = .78f),
        success = if (dark) Color(0xFF30D968) else Color(0xFF187B58),
        successContainer = if (dark) Color(0xFF153D27) else Color(0xFFE6F4EB),
        warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319),
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(
            LocalMiuixTokens provides tokens,
            LocalContentColor provides scheme.onSurface,
            content = content,
        )
    }
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

private fun KolorStyle.toPaletteStyle(): PaletteStyle = when (this) {
    KolorStyle.SOFT -> PaletteStyle.TonalSpot
    KolorStyle.VIBRANT -> PaletteStyle.Vibrant
    KolorStyle.NEUTRAL -> PaletteStyle.Neutral
}

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
