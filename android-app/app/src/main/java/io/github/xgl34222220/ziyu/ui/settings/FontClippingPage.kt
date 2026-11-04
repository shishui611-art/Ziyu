package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.FontItem
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.library.FontClippingControl

@Composable
internal fun FontClippingPage(fonts: List<FontItem>, currentFontId: String, busy: Boolean, loading: Boolean) {
    var selected by rememberSaveable { mutableStateOf(currentFontId) }
    var choose by rememberSaveable { mutableStateOf(false) }
    val available = fonts.filter { it.valid && it.id != "default" }
    val font = available.firstOrNull { it.id == selected }
        ?: available.firstOrNull { it.id == currentFontId }
    pageList {
        item {
            SettingCard("选择字体") {
                ZiyuText(font?.name ?: if (loading) "正在读取字体…" else "请选择字体",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ZiyuOutlinedButton(onClick = { choose = true }, enabled = available.isNotEmpty() && !busy) {
                    ZiyuText("选择字体")
                }
                if (font != null) FontClippingControl(font, busy)
                else if (!loading) ZiyuText("先在字体库导入一款字体。")
            }
        }
    }
    if (choose) ZiyuAlertDialog(
        onDismissRequest = { choose = false },
        title = { ZiyuText("选择字体") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().height((available.size * 64).coerceIn(64, 320).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(available.size, key = { available[it].id }) { index ->
                    val item = available[index]
                    ZiyuOutlinedButton(onClick = { selected = item.id; choose = false }, modifier = Modifier.fillMaxWidth()) {
                        ZiyuText(item.name)
                    }
                }
            }
        },
        confirmButton = { ZiyuTextButton(onClick = { choose = false }) { ZiyuText("取消") } },
    )
}
