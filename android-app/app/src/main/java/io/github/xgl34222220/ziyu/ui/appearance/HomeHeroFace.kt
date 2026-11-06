package io.github.xgl34222220.ziyu.ui.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.ui.design.ZiyuText

/** One geometry for the live card and the scaled theme preview. */
@Immutable
internal data class HomeHeroColors(val background: Color, val text: Color, val secondary: Color,
                                   val badgeBackground: Color, val badgeText: Color)

internal fun homeHeroColors(settings: AppearanceSettings, colors: ColorScheme, dark: Boolean): HomeHeroColors =
    if(settings.uiStyle==UiStyle.MATERIAL) HomeHeroColors(colors.primary,colors.onPrimary,colors.onPrimary,colors.onPrimary.copy(alpha=.14f),colors.onPrimary)
    else HomeHeroColors(if(settings.nativeMonetEnabled)colors.secondaryContainer else if(dark)Color(0xFF1A3825) else Color(0xFFDFFAE4),
        colors.onSurface,colors.onSurfaceVariant,colors.primary,colors.onPrimary)

@Composable
internal fun HomeHeroFace(
    settings: AppearanceSettings,
    colors: HomeHeroColors,
    badge: String,
    version: String,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val container=colors.background
    val foreground=colors.text
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val referenceWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(240).dp
        val factor = if(preview) (maxWidth / referenceWidth).coerceAtMost(1f) else 1f
        val minimum = (if(preview) referenceWidth else maxWidth).div(1.76f).coerceAtLeast(200.dp) * factor
        val decoration = settings.homeCard
        Box(Modifier.fillMaxWidth().heightIn(min=minimum)) {
            HomeCardArtwork(decoration, container, Modifier.matchParentSize(), showStickers=false)
            if(!decoration.hideSample && decoration.backgroundPath.isBlank()) Canvas(Modifier.matchParentSize()) {
                val center=Offset(size.width*.90f,size.height*.18f)
                val radius=size.width*.43f
                val color=foreground.copy(alpha=.13f)
                val stroke=(1.5.dp*factor).toPx()
                drawCircle(color,radius,center,style=Stroke(stroke))
                drawCircle(color,radius*.76f,center,style=Stroke(stroke,pathEffect=PathEffect.dashPathEffect(floatArrayOf(stroke*2.5f,stroke*5f))))
                drawCircle(color,radius*.52f,center,style=Stroke(stroke))
                drawCircle(color,radius*.17f,center)
            }
            Column(Modifier.fillMaxWidth().heightIn(min=minimum).padding(22.dp*factor),verticalArrangement=Arrangement.SpaceBetween) {
                ZiyuText(badge,Modifier.background(colors.badgeBackground,RoundedCornerShape(6.dp*factor))
                    .padding(horizontal=9.dp*factor,vertical=5.dp*factor),color=colors.badgeText,
                    fontSize=12.sp*factor,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(!decoration.hideLabel) Column(Modifier.padding(top=32.dp*factor),verticalArrangement=Arrangement.spacedBy(6.dp*factor)) {
                    val title=resolveThemeText(settings.customTexts,ThemeTextKey.STATUS_WORKING)
                    ZiyuText(title,color=foreground,fontSize=36.sp*factor,lineHeight=43.sp*factor,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    val subtitle=themeTextOverride(settings.customTexts,ThemeTextKey.LATIN_SAMPLE)
                    ZiyuText(subtitle ?: "版本：$version",color=colors.secondary,fontSize=14.sp*factor,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            HomeCardStickers(decoration.stickers, Modifier.matchParentSize())
        }
    }
}
