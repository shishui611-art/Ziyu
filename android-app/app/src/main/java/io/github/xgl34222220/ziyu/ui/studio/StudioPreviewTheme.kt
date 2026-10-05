package io.github.xgl34222220.ziyu.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.ui.theme.LocalMiuixTokens
import io.github.xgl34222220.ziyu.ui.theme.MiuixTokens

@Composable
internal fun StudioPreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val baseScheme = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val typography = MaterialTheme.typography
    val scheme = remember(baseScheme, dark) {
        baseScheme.copy(
            background = if (dark) Color(0xFF0A0A0B) else Color(0xFFF3F6FA),
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
    }
    val tokens = remember(scheme, dark) {
        MiuixTokens(
            pageBackground = scheme.background,
            cardBackground = scheme.surfaceContainerLowest,
            elevatedCardBackground = scheme.surfaceContainerHigh,
            textPrimary = scheme.onSurface,
            textSecondary = scheme.onSurfaceVariant,
            textTertiary = scheme.onSurfaceVariant.copy(alpha = .78f),
            success = if (dark) Color(0xFF30D968) else Color(0xFF187B58),
            successContainer = if (dark) Color(0xFF153D27) else Color(0xFFE6F4EB),
            warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319),
        )
    }
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography) {
        CompositionLocalProvider(LocalMiuixTokens provides tokens, LocalContentColor provides scheme.onSurface, content = content)
    }
}

@Composable
internal fun StudioPreviewAppearanceSelector(
    dark: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(false to "浅色预览", true to "深色预览").forEach { (isDark, label) ->
            val selected = dark == isDark
            Surface(
                onClick = { onChange(isDark) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(vertical = 9.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
