package io.github.xgl34222220.ziyu.ui.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import io.github.xgl34222220.ziyu.ui.design.ZiyuSlider as Slider
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.MIX_LATIN_OFFSET_LIMIT
import io.github.xgl34222220.ziyu.MIX_LATIN_SIZE_LIMIT
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard
import io.github.suqi8.coui.kmp.basic.Slider as CouiSlider
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import kotlin.math.roundToInt

@Composable
internal fun StudioLatinTuneControls(state: FontStudioUiState, actions: FontStudioActions) {
    val idle = !state.loading && !state.busy && !state.operationBusy
    val enabled = idle && state.manualTuneSupported
    val palette = LocalZiyuPaletteTokens.current
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = palette.cardBackground,
            contentColor = palette.textPrimary,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("英数微调", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { actions.updateTune(0, 0) },
                    enabled = idle && (state.latinSize != 0 || state.latinOffset != 0),
                ) { Text("恢复默认") }
            }
            Text("在自动对齐结果上调整英文和数字，重新生成组合后生效。",
                style = MaterialTheme.typography.bodySmall)
            if (!state.loading && !state.manualTuneSupported) {
                Text("请更新配套模块以使用英数微调。", style = MaterialTheme.typography.bodySmall)
            }
            LatinTuneSlider("英数大小", state.latinSize, MIX_LATIN_SIZE_LIMIT,
                "负值缩小，正值放大", enabled) { actions.updateTune(it, state.latinOffset) }
            LatinTuneSlider("英数上下位置", state.latinOffset, MIX_LATIN_OFFSET_LIMIT,
                "负值下移，正值上移", enabled) { actions.updateTune(state.latinSize, it) }
        }
    }
}

@Composable
private fun LatinTuneSlider(
    label: String,
    value: Int,
    limit: Int,
    hint: String,
    enabled: Boolean,
    onValue: (Int) -> Unit,
) {
    // Keep drag feedback local; persist the integer value when the gesture ends.
    var draft by remember(value, enabled) { mutableStateOf(value) }
    Text("$label：${if (draft > 0) "+" else ""}$draft%", modifier = Modifier.padding(top = 12.dp))
    val onDraftChange: (Float) -> Unit = {
        if (enabled) draft = it.roundToInt().coerceIn(-limit, limit)
    }
    val onFinished: () -> Unit = { if (enabled && draft != value) onValue(draft) }
    val range = -limit.toFloat()..limit.toFloat()
    val steps = limit * 2 - 1
    // Library accessibility actions don't finish a drag. Commit those actions
    // directly and enforce the same disabled/whole-percent rules as touch.
    val sliderModifier = Modifier.fillMaxWidth().semantics {
        contentDescription = label
        if (!enabled) disabled()
        setProgress { target ->
            if (!enabled || !target.isFinite()) {
                false
            } else {
                val next = target.roundToInt().coerceIn(-limit, limit)
                if (next == draft) {
                    false
                } else {
                    draft = next
                    onValue(next)
                    true
                }
            }
        }
    }
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> Slider(
            value = draft.toFloat(),
            onValueChange = onDraftChange,
            onValueChangeFinished = onFinished,
            modifier = sliderModifier,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
        UiStyle.MIUIX -> {
            // The surrounding page uses Ziyu's dynamic Material palette. Supply
            // all colors so the library slider follows it in light/dark modes.
            val scheme = MaterialTheme.colorScheme
            MiuixSlider(
                value = draft.toFloat(),
                onValueChange = onDraftChange,
                onValueChangeFinished = onFinished,
                modifier = sliderModifier.padding(vertical = 14.dp),
                valueRange = range,
                steps = steps,
                enabled = enabled,
                colors = MiuixSliderDefaults.sliderColors(
                    foregroundColor = scheme.primary,
                    disabledForegroundColor = scheme.onSurface.copy(alpha = .12f),
                    backgroundColor = scheme.surfaceContainerHighest,
                    disabledBackgroundColor = scheme.onSurface.copy(alpha = .08f),
                    thumbColor = scheme.onPrimary,
                    disabledThumbColor = scheme.onSurface.copy(alpha = .38f),
                    keyPointColor = scheme.onSurfaceVariant,
                    keyPointForegroundColor = scheme.onPrimary,
                ),
            )
        }
        UiStyle.COUI -> CouiSlider(
            value = draft.toFloat(),
            onValueChange = onDraftChange,
            onValueChangeFinished = onFinished,
            modifier = sliderModifier.padding(vertical = 14.dp),
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
    Text(hint, style = MaterialTheme.typography.bodySmall)
}
