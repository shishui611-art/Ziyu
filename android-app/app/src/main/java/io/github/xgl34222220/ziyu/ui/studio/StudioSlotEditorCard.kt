package io.github.xgl34222220.ziyu.ui.studio

import android.view.Gravity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.MixSlot
import io.github.xgl34222220.ziyu.NativeFontPreview
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.font.fontCapabilityLabel
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard

/** One editor layout, with native controls and colors supplied by each style. */
@Composable
internal fun StudioSlotEditorCard(slot: StudioSlotUiState, busy: Boolean, actions: FontStudioActions) {
    val palette = LocalZiyuPaletteTokens.current
    val material = LocalUiStyle.current == UiStyle.MATERIAL
    val font = slot.font
    ZiyuCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (material) MaterialTheme.colorScheme.surfaceContainerLow else palette.cardBackground,
            contentColor = palette.textPrimary,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ZiyuSurface(
                    modifier = Modifier.size(48.dp), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ZiyuText(when (slot.slot) { MixSlot.Cjk -> "中"; MixSlot.Latin -> "Aa"; MixSlot.Digit -> "123" },
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ZiyuText(slot.title, color = palette.textPrimary, style = MaterialTheme.typography.titleMedium)
                    ZiyuText(slot.subtitle, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            ZiyuSurface(
                onClick = { actions.pickSlot(slot.slot) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                color = palette.elevatedCardBackground, contentColor = palette.textPrimary,
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ZiyuText(font?.name ?: "选择字体", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (font != null) ZiyuText(fontCapabilityLabel(font), color = palette.textSecondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    ZiyuIcon(Icons.Rounded.KeyboardArrowDown, "选择${slot.title}", tint = palette.textSecondary)
                }
            }
            if (font != null) {
                ZiyuSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    color = palette.elevatedCardBackground, contentColor = palette.textPrimary) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
                        NativeFontPreview(font = font, text = slot.sample, axes = slot.axes,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp), textSizeSp = 24f,
                            gravity = Gravity.CENTER, maxLines = 2)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (material) MaterialStudioAxisControls(font, slot.weight, slot.axes, !busy,
                        { actions.updateWeight(slot.slot, it) }, { tag, value -> actions.updateAxis(slot.slot, tag, value) })
                    else NativeStudioAxisControls(font, slot.weight, slot.axes, !busy,
                        { actions.updateWeight(slot.slot, it) }, { tag, value -> actions.updateAxis(slot.slot, tag, value) })
                }
            }
        }
    }
}
