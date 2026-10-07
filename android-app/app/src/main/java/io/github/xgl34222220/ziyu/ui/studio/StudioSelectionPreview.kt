package io.github.xgl34222220.ziyu.ui.studio

import android.view.Gravity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.MixSlot
import io.github.xgl34222220.ziyu.NativeFontPreview

@Composable
internal fun StudioSelectionPreview(slots: List<StudioSlotUiState>) {
    val cjk = slots.firstOrNull { it.slot == MixSlot.Cjk }
    val latin = slots.firstOrNull { it.slot == MixSlot.Latin }
    val digit = slots.firstOrNull { it.slot == MixSlot.Digit }
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioSelectionPreviewPart(cjk, "中文", Modifier.weight(1.05f))
        StudioSelectionPreviewPart(latin, "Aa", Modifier.weight(.8f))
        StudioSelectionPreviewPart(digit, "0123", Modifier.weight(1f))
    }
}

@Composable
private fun StudioSelectionPreviewPart(slot: StudioSlotUiState?, sample: String, modifier: Modifier) {
    val font = slot?.font?.takeIf { it.valid }
    if (font != null) {
        NativeFontPreview(
            font = font,
            text = sample,
            axes = slot.axes,
            modifier = modifier.height(42.dp),
            textSizeSp = 23f,
            gravity = Gravity.CENTER,
            maxLines = 1,
        )
    } else {
        Text(
            sample,
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
