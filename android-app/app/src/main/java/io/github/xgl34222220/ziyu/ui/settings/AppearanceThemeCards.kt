/*
 * Theme preview and palette adapted from SukiSU-Ultra at
 * df84920b20a925fd1135530f6e403361ad494d7b (GPL-3.0-or-later).
 * Adaptation for ziyu is distributed under GPL-3.0-only.
 * See THIRD_PARTY_NOTICES.md and licenses/SukiSU-Ultra-GPL-3.0.txt.
 */
package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.rememberAppearancePreviewColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceModePreview(settings: AppearanceSettings, actions: AppearanceActions) {
    val dark = when (settings.themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    var allColors by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.widthIn(max = 168.dp).fillMaxWidth().aspectRatio(.52f)) {
            MiniThemePreview(settings, dark, Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)))
            ZiyuText("字域", modifier = Modifier.padding(start = 18.dp, top = 30.dp),
                color = rememberAppearancePreviewColors(settings, dark).onBackground, style = MaterialTheme.typography.titleMedium)
        }
        val quick = remember(settings.seedArgb) {
            val defaults = listOf(AccentOptions.first { it.id == "sakura" }, AccentOptions.first { it.id == "blue" }, AccentOptions.first { it.id == "purple" })
            if (defaults.any { it.argb == settings.seedArgb }) defaults else listOf(accentOptionFor(settings.seedArgb)) + defaults.take(2)
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PaletteShortcut(settings.copy(monetEnabled = true), "壁纸", settings.monetEnabled, Modifier.weight(1f)) { actions.setMonetEnabled(true) }
            quick.forEach { option ->
                PaletteShortcut(settings.copy(seedArgb = option.argb, monetEnabled = false), option.label,
                    !settings.monetEnabled && settings.seedArgb == option.argb, Modifier.weight(1f)) { actions.setSeedArgb(option.argb) }
            }
        }
        ZiyuTextButton(onClick = { allColors = true }) { ZiyuText("更多主题色") }
        val modes = listOf("自动", "浅色", "深色", "纯黑")
        val icons = listOf(Icons.Rounded.BrightnessAuto, Icons.Rounded.LightMode, Icons.Rounded.DarkMode, Icons.Rounded.Contrast)
        val selected = when { settings.themeMode == ThemeMode.SYSTEM -> 0; settings.themeMode == ThemeMode.LIGHT -> 1; settings.amoledBlack -> 3; else -> 2 }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEachIndexed { index, label ->
                val active = selected == index
                ZiyuSurface(
                    onClick = { actions.setThemeDisplay(when (index) { 0 -> ThemeMode.SYSTEM; 1 -> ThemeMode.LIGHT; else -> ThemeMode.DARK }, index == 3) },
                    modifier = Modifier.weight(1f).semantics { this.selected = active; contentDescription = "$label 显示模式" },
                    shape = RoundedCornerShape(if (active) 28.dp else 16.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ZiyuIcon(icons[index], null, Modifier.size(24.dp))
                        ZiyuText(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (allColors) ZiyuModalBottomSheet(onDismissRequest = { allColors = false }, sheetState = rememberZiyuModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp)) {
            ZiyuText("主题色", style = MaterialTheme.typography.titleLarge)
            AppearanceAccentCard(settings) { actions.setSeedArgb(it); allColors = false }
        }
    }
}

@Composable
private fun PaletteShortcut(settings: AppearanceSettings, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = rememberAppearancePreviewColors(settings, false)
    Column(modifier.clip(RoundedCornerShape(20.dp)).selectable(active, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(54.dp).then(if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier).padding(5.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                clipRect(bottom = size.height / 2) { drawCircle(colors.primaryContainer) }
                clipRect(top = size.height / 2) { drawCircle(colors.tertiaryContainer) }
                drawCircle(colors.primary, radius = size.width * .22f)
            }
            if (active) ZiyuIcon(Icons.Rounded.Check, null, modifier = Modifier.align(Alignment.Center).size(18.dp), tint = colors.onPrimary)
        }
        ZiyuText(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MiniThemePreview(settings: AppearanceSettings, dark: Boolean, modifier: Modifier) {
    val colors = rememberAppearancePreviewColors(settings, dark)
    val native = settings.uiStyle != UiStyle.MATERIAL
    Canvas(modifier.clip(RoundedCornerShape(24.dp)).background(colors.background).semantics { contentDescription = "${settings.uiStyle.label}当前主题预览" }) {
        val unit = size.width / 100f
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) {
            drawRoundRect(color, Offset(x * unit, y * unit), Size(w * unit, h * unit), CornerRadius(radius * unit))
        }
        rect(10f, 9f, 16f, 2f, 1f, colors.onBackground.copy(alpha = .4f))
        rect(79f, 9f, 11f, 2f, 1f, colors.onBackground.copy(alpha = .3f))
        rect(8f, 42f, 84f, 28f, 9f, colors.primaryContainer)
        val card = if (dark && settings.amoledBlack) colors.surfaceContainerLow else if (native) colors.surfaceContainer else colors.surfaceBright
        rect(8f, 75f, 84f, 78f, 9f, card)
        repeat(3) { i -> rect(16f, 88f + i * 9, if (i == 0) 48f else 36f, 2.5f, 1f, colors.onSurface.copy(alpha = if (i == 0) .5f else .2f)) }
        val bottom = size.height / unit - 23f
        rect(if (settings.floatingDock) 8f else 0f, bottom, if (settings.floatingDock) 84f else 100f, 16f,
            if (settings.floatingDock) 8f else 0f, colors.surfaceContainerHighest)
        rect(10f, bottom + 2f, 20f, 12f, 6f, colors.primaryContainer)
        repeat(4) { i -> rect(17f + i * 21f, bottom + 6f, 5f, 4f, 2f, if (i == 0) colors.primary else colors.onSurfaceVariant) }
    }
}

@Composable
internal fun AppearancePaletteCard(settings: AppearanceSettings, actions: AppearanceActions) {
    var chooser by rememberSaveable { mutableStateOf<String?>(null) }
    // SettingCard owns the container; native surface and card surface are different
    // color roles. Let both rows reveal the card instead of painting a rectangle.
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    SettingCard("色彩") {
        ZiyuListItem(
            headlineContent = { ZiyuText("色彩风格") }, leadingContent = { ZiyuIcon(Icons.Rounded.Palette, null) },
            trailingContent = { ZiyuText(settings.kolorStyle.label, color = MaterialTheme.colorScheme.primary) },
            colors = rowColors,
            onClick = { chooser = "style" },
        )
        if (settings.uiStyle != UiStyle.COUI) ZiyuListItem(
            headlineContent = { ZiyuText("色彩标准") }, leadingContent = { ZiyuIcon(Icons.Rounded.Tune, null) },
            supportingContent = if (!settings.kolorStyle.supportsSpec2025 && settings.colorStandard == ColorStandard.SPEC_2025) ({ ZiyuText("当前风格按经典标准呈现") }) else null,
            trailingContent = { ZiyuText(settings.colorStandard.label, color = MaterialTheme.colorScheme.primary) },
            colors = rowColors,
            onClick = { chooser = "standard" },
        )
    }
    if (chooser != null) ZiyuAlertDialog(
        onDismissRequest = { chooser = null }, title = { ZiyuText(if (chooser == "style") "色彩风格" else "色彩标准") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                val labels = if (chooser == "style") KolorStyle.entries.map { it.label } else ColorStandard.entries.map { it.label }
                val selected = if (chooser == "style") settings.kolorStyle.ordinal else settings.colorStandard.ordinal
                labels.forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().selectable(selected == index, role = Role.RadioButton, onClick = {
                        if (chooser == "style") actions.setKolorStyle(KolorStyle.entries[index]) else actions.setColorStandard(ColorStandard.entries[index])
                        chooser = null
                    }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZiyuText(label, Modifier.weight(1f))
                        ZiyuRadioButton(selected == index, onClick = null)
                    }
                }
            }
        }, confirmButton = { ZiyuTextButton(onClick = { chooser = null }) { ZiyuText("关闭") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceAccentCard(settings: AppearanceSettings, onColor: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentOptions.forEach { option ->
            val active = settings.seedArgb == option.argb && !settings.monetEnabled
            val color = Color(option.argb)
            Column(Modifier.width(48.dp).clip(RoundedCornerShape(14.dp)).selectable(active, role = Role.RadioButton, onClick = { onColor(option.argb) }).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    if (active) ZiyuIcon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = if (color.luminance() > .5f) Color.Black else Color.White)
                }
                ZiyuText(option.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
