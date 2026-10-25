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
    var scale by rememberSaveable(state.currentFontId) { mutableIntStateOf(values?.scale ?: 100) }
    var weight by rememberSaveable(state.currentFontId) { mutableIntStateOf(values?.weight ?: 0) }
    var density by rememberSaveable(state.currentFontId) { mutableIntStateOf(values?.density ?: 0) }
    LaunchedEffect(values) {
        values?.let { scale = it.scale; weight = it.weight; density = it.density }
    }
    val enabled = values != null && !data.loading && !data.saving && !state.taskRunning && state.rootGranted
    val dirty = values != null && (scale != values.scale || density != values.density ||
        data.supportsWeight && weight != values.weight)
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.cardBackground, contentColor = palette.textPrimary),
    ) {
        Column(Modifier.padding(layout.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZiyuGlyph(Icons.Rounded.Tune, null, ZiyuIconTokens.SectionGlyph, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    ZiyuText("全局字体与显示", style = MaterialTheme.typography.titleMedium)
                    ZiyuText("字体大小、粗细与屏幕密度", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
            }
            if (data.loading) {
                ZiyuText("正在读取本地配置与字体能力…", color = palette.textSecondary)
            } else if (values != null) {
                TypographySlider("字体大小", "$scale%", scale, 50..200, enabled) { scale = it }
                if (data.supportsWeight) {
                    ZiyuHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                    TypographySlider(
                        "字体粗细", if (weight == 0) "标准" else if (weight > 0) "+$weight" else "$weight",
                        weight, minOf(-300, values.weight)..maxOf(300, values.weight), enabled, step = 10,
                    ) { weight = it }
                    ZiyuText("系统字重偏移，0 为原始粗细", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
                ZiyuHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                TypographySlider("屏幕 DPI", "$density", density, values.densityMin..values.densityMax, enabled) { density = it }
                ZiyuText(
                    "当前 ${values.density} · 系统默认 ${values.baseDensity} DPI。DPI 会同时改变文字和界面大小。",
                    style = MaterialTheme.typography.bodySmall, color = palette.textSecondary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZiyuOutlinedButton(
                        onClick = { scale = 100; weight = 0; density = values.baseDensity },
                        enabled = enabled, modifier = Modifier.weight(1f),
                    ) { ZiyuText("默认数值") }
                    ZiyuButton(
                        onClick = { actions.applyTypography(scale, weight.takeIf { data.supportsWeight }, density) },
                        enabled = enabled && dirty && scale in 50..200, modifier = Modifier.weight(1f),
                    ) { ZiyuText(if (data.saving) "应用中…" else "应用调整") }
                }
            }
            if (data.error.isNotBlank()) ZiyuText(data.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (data.message.isNotBlank()) ZiyuText(data.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (values != null) ZiyuText(
                "优先使用本地配置；在系统设置中改动后，可点击下方同步。",
                style = MaterialTheme.typography.bodySmall, color = palette.textSecondary,
            )
            if (!data.loading) ZiyuOutlinedButton(
                onClick = actions.refreshTypography, enabled = !data.saving, modifier = Modifier.fillMaxWidth(),
            ) { ZiyuText("同步系统参数") }
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
