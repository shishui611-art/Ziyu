package io.github.xgl34222220.luoshu.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.github.xgl34222220.luoshu.ui.appearance.UiStyle

/** A flexible title column keeps long titles clear of every action at large font scales. */
@Composable
internal fun LuoShuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = LuoShuTypographyTokens.PageTitle,
    actions: @Composable () -> Unit = {},
) {
    val tokens = LocalMiuixTokens.current
    val style = LocalUiStyle.current
    val titleFontSize = when (style) {
        UiStyle.MATERIAL -> MaterialTheme.typography.headlineSmall.fontSize
        UiStyle.MIUIX -> titleSize
        UiStyle.COUI -> MaterialTheme.typography.titleLarge.fontSize
    }
    val couiHeader = style == UiStyle.COUI
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 76.dp)
            .then(
                if (couiHeader) Modifier.clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp)
                else Modifier,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = tokens.textPrimary,
            fontSize = titleFontSize,
            lineHeight = titleSize * 1.15f,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() }
    }
}

@Composable
internal fun LuoShuDetailBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val tokens = LocalMiuixTokens.current
    val style = LocalUiStyle.current
    val titleFontSize = when (style) {
        UiStyle.MATERIAL -> MaterialTheme.typography.titleLarge.fontSize
        UiStyle.MIUIX -> LuoShuTypographyTokens.DetailTitle
        UiStyle.COUI -> MaterialTheme.typography.titleLarge.fontSize
    }
    val couiHeader = style == UiStyle.COUI
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp)
            .then(
                if (couiHeader) Modifier.clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 8.dp)
                else Modifier,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuoShuHeaderAction(
            icon = Icons.Rounded.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            containerColor = tokens.cardBackground,
            contentColor = tokens.textPrimary,
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = tokens.textPrimary,
            fontSize = titleFontSize,
            lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() }
    }
}
