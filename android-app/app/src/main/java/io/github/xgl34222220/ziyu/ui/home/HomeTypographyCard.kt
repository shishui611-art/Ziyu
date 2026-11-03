package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.DisplayTypographyState
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.*
import kotlin.math.roundToInt

@Composable
internal fun HomeTypographyCard(state: HomeUiState, actions: HomeActions) {
    val data = state.typography.takeIf { it.fontId == state.currentFontId && it.sourceRevision == state.currentFontRevision }
        ?: DisplayTypographyState()
    val values = data.values
    val palette = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    var density by rememberSaveable(state.currentFontId) { mutableIntStateOf(values?.density ?: 0) }
    LaunchedEffect(values) {
        values?.let { density = it.density }
    }
    val enabled = values != null && !data.loading && !data.saving && !state.taskRunning && state.rootGranted
    val dirty = values != null && density != values.density
    val context = LocalContext.current
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.cardBackground, contentColor = palette.textPrimary),
    ) {
        Column(Modifier.padding(layout.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuGlyph(Icons.Rounded.Tune, null, ZiyuIconTokens.SectionGlyph, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    ZiyuText("屏幕 DPI", style = MaterialTheme.typography.titleMedium)
                }
            }
            ZiyuText(
                "调整文字和界面的整体大小。",
                style = MaterialTheme.typography.bodySmall, color = palette.textSecondary,
            )
            if (!state.moduleInstalled || !state.rootGranted) {
                ZiyuText("连接模块并授予 Root 后可调整。", color = palette.textSecondary)
            } else if (data.loading) {
                ZiyuText("正在读取显示配置…", color = palette.textSecondary)
            } else if (values != null) {
                TypographySlider("屏幕 DPI", "$density", density, values.densityMin..values.densityMax, enabled) { density = it }
                ZiyuText(
                    "系统默认 ${values.baseDensity} DPI",
                    style = MaterialTheme.typography.bodySmall, color = palette.textSecondary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZiyuOutlinedButton(
                        onClick = { density = values.baseDensity },
                        enabled = enabled, modifier = Modifier.weight(1f),
                    ) { ZiyuText("默认 DPI") }
                    ZiyuButton(
                        onClick = { actions.applyDensity(density) },
                        enabled = enabled && dirty, modifier = Modifier.weight(1f),
                    ) { ZiyuText(if (data.saving) "应用中…" else "应用") }
                }
            }
            if (data.error.isNotBlank()) ZiyuText(data.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (data.message.isNotBlank()) ZiyuText(data.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (!data.loading && state.moduleInstalled && state.rootGranted) ZiyuTextButton(
                onClick = actions.refreshTypography, enabled = !data.saving, modifier = Modifier.fillMaxWidth(),
            ) { ZiyuText("刷新 DPI") }
            ZiyuTextButton(onClick = {
                val opened = runCatching { context.startActivity(android.content.Intent("android.settings.FONT_SIZE_SETTINGS")) }.isSuccess
                if (!opened) runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) }
            }, modifier = Modifier.fillMaxWidth()) { ZiyuText("字号与粗细 · 前往系统设置") }
        }
    }
}

@Composable
private fun TypographySlider(
    label: String, valueLabel: String, value: Int, range: IntRange, enabled: Boolean,
    step: Int = 1, onChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZiyuText(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            ZiyuText(valueLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZiyuIconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled && value > range.first) {
                ZiyuIcon(Icons.Rounded.Remove, contentDescription = "减小$label")
            }
            ZiyuSlider(
                value = value.coerceIn(range).toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                enabled = enabled, modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
            ZiyuIconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled && value < range.last) {
                ZiyuIcon(Icons.Rounded.Add, contentDescription = "增大$label")
            }
        }
    }
}
