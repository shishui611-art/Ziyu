package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ZiyuLayoutTokens {
    val SpacingXs = 4.dp
    val SpacingSm = 8.dp
    val SpacingMd = 12.dp
    val SpacingLg = 16.dp
    val SpacingXl = 24.dp
    val PageHorizontal = SpacingXl
    val PageTop = SpacingSm
    val ItemGap = SpacingLg
    val CardGap = SpacingLg
    val CardPadding = 22.dp
    val CardElevation = 4.dp
    val CompactPadding = SpacingLg
    val FloatingDockSafeBottom = 92.dp
    val FloatingDockHorizontal = 28.dp
    val FloatingDockBottomGap = SpacingSm

    val BrandBlue = Color(0xFF2A62F6)
    val SecondaryBlueSurface = Color(0xFFEEF2FF)
    val LightCardOutline = Color(0xFFE2E8F0)
    val TechnicalSurface = Color(0xFFF1F5F9)
    val NeutralSecondaryText = Color(0xFF64748B)
}

/** Shared shape values for Miuix surfaces, controls and grouped content. */
internal object ZiyuShapeTokens {
    val Small = RoundedCornerShape(14.dp)
    val Medium = RoundedCornerShape(20.dp)
    val Large = RoundedCornerShape(26.dp)
    val Card = RoundedCornerShape(28.dp)
    val Hero = RoundedCornerShape(32.dp)
    val Pill = RoundedCornerShape(999.dp)
}

/** Type scale used by the app shell and shared Miuix pages. */
internal object ZiyuTypographyTokens {
    val PageTitle = 40.sp
    val DetailTitle = 26.sp
    val StatusValue = 30.sp
    val SectionTitle = 21.sp
    val CardTitle = 18.sp
    val Body = 16.sp
    val Secondary = 14.sp
    val Caption = 13.sp
    val DockLabel = 12.sp
}

/** Tunable glass values; ordinary cards intentionally do not use backdrop blur. */
internal object ZiyuGlassTokens {
    const val DarkSurfaceAlpha = .38f
    const val DarkHighlightAlpha = .10f
    const val DarkEdgeAlpha = .08f
    const val DarkInnerShadowAlpha = .10f
    const val DarkShadowAlpha = .12f
    const val LightShadowAlpha = .10f
    const val IndicatorAlpha = .10f
    const val LightIndicatorAlpha = .10f
    const val RefractionAmountDp = 14f
    const val RefractionHeightDp = 16f
    const val ChromaticAberration = .04f
    val BlurRadius = 4.dp
}
