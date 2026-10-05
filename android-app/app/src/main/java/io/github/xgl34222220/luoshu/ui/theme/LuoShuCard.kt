package io.github.xgl34222220.luoshu.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** All page cards share the same shadow, including disabled and active cards. */
@Composable
internal fun LuoShuCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shadow = LuoShuLayoutTokens.CardElevation
    val elevation = CardDefaults.cardElevation(
        defaultElevation = shadow,
        pressedElevation = shadow,
        focusedElevation = shadow,
        hoveredElevation = shadow,
        draggedElevation = shadow,
        disabledElevation = shadow,
    )
    if (onClick == null) {
        MaterialCard(modifier = modifier, shape = shape, colors = colors,
            elevation = elevation, border = border, content = content)
    } else {
        MaterialCard(
            onClick = onClick, modifier = modifier, enabled = enabled,
            shape = shape, colors = colors, elevation = elevation,
            border = border, interactionSource = interactionSource, content = content,
        )
    }
}
