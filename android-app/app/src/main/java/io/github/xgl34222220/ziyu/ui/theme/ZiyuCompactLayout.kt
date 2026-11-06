package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar as MaterialTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.appearance.themeText
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixLargeTopAppBar

/** Root-page title treatment follows the active design system while preserving action slots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZiyuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 22.sp,
    actions: @Composable () -> Unit = {},
) {
    val style = LocalUiStyle.current
    if (style == UiStyle.MIUIX) {
        MiuixLargeTopAppBar(
            title = themeText(title),
            largeTitle = themeText(title),
            modifier = modifier.fillMaxWidth(),
            color = Color.Transparent,
            titleColor = LocalZiyuPaletteTokens.current.textPrimary,
            largeTitleColor = LocalZiyuPaletteTokens.current.textPrimary,
            titlePadding = 0.dp,
            actionIconPadding = 0.dp,
            actions = { actions() },
        )
        return
    }
    if (style == UiStyle.COUI) {
        io.github.suqi8.coui.kmp.basic.SmallTopAppBar(
            title = themeText(title),
            modifier = modifier.fillMaxWidth(),
            color = Color.Transparent,
            titleColor = LocalZiyuPaletteTokens.current.textPrimary,
            showDivider = false,
            titlePadding = 0.dp,
            actionIconPadding = 0.dp,
            actions = { actions() },
        )
        return
    }
    val titleFontSize = titleSize
    MaterialTopAppBar(
        title = {
            Text(
                text = themeText(title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = titleFontSize,
                lineHeight = titleFontSize * 1.27f,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.fillMaxWidth(),
        actions = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZiyuDetailBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val style = LocalUiStyle.current
    if (style == UiStyle.MIUIX) {
        MiuixSmallTopAppBar(
            title = themeText(title),
            modifier = modifier.fillMaxWidth(),
            color = Color.Transparent,
            titleColor = LocalZiyuPaletteTokens.current.textPrimary,
            navigationIcon = { ZiyuHeaderAction(Icons.Rounded.ArrowBack, "返回", onBack, LocalZiyuPaletteTokens.current.cardBackground, contentColor = LocalZiyuPaletteTokens.current.textPrimary) },
            actions = { actions() },
        )
        return
    }
    if (style == UiStyle.COUI) {
        io.github.suqi8.coui.kmp.basic.SmallTopAppBar(
            title = themeText(title),
            modifier = modifier.fillMaxWidth(),
            color = Color.Transparent,
            titleColor = LocalZiyuPaletteTokens.current.textPrimary,
            showDivider = false,
            navigationIcon = { ZiyuHeaderAction(Icons.Rounded.ArrowBack, "返回", onBack, LocalZiyuPaletteTokens.current.cardBackground, contentColor = LocalZiyuPaletteTokens.current.textPrimary) },
            actions = { actions() },
        )
        return
    }
    val titleFontSize = when (style) {
        UiStyle.MATERIAL -> MaterialTheme.typography.titleLarge.fontSize
        UiStyle.MIUIX -> ZiyuTypographyTokens.DetailTitle
        UiStyle.COUI -> MaterialTheme.typography.titleLarge.fontSize
    }
    MaterialTopAppBar(
        title = {
            Text(
                text = themeText(title),
                fontSize = titleFontSize,
                lineHeight = titleFontSize * 1.27f,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.fillMaxWidth(),
        navigationIcon = {
            ZiyuHeaderAction(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                containerColor = LocalZiyuPaletteTokens.current.cardBackground,
                contentColor = LocalZiyuPaletteTokens.current.textPrimary,
            )
        },
        actions = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}
