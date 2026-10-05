package io.github.xgl34222220.ziyu.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Quiet shared surfaces, without an animated backdrop for each scrolling card. */
@Composable
internal fun ZiyuSurfaceCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalMiuixTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (emphasized) {
            lerp(tokens.cardBackground, MaterialTheme.colorScheme.primaryContainer, .16f)
        } else tokens.cardBackground,
        contentColor = tokens.textPrimary,
        shadowElevation = ZiyuLayoutTokens.CardElevation,
    ) {
        Column(
            modifier = Modifier.padding(ZiyuLayoutTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(ZiyuLayoutTokens.ItemGap),
            content = content,
        )
    }
}

@Composable
internal fun ZiyuSectionHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val tokens = LocalMiuixTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        action()
    }
}
