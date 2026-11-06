package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.rememberAppearancePreviewColors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceCustomizationCard(settings: AppearanceSettings, actions: AppearanceActions) {
    var editor by rememberSaveable { mutableStateOf("") }
    SettingCard("自定义外观") {
        ZiyuText("调整文字、卡片配色和背景效果。",style=MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            ZiyuButton(onClick={editor="colors"}) { ZiyuText("配色") }
            ZiyuOutlinedButton(onClick={editor="effects"}) { ZiyuText("背景效果") }
            ZiyuTextButton(onClick={editor="json"}) { ZiyuText("高级") }
        }
    }
    if(editor=="colors") ColorsDialog(settings,actions.setAppearanceOverrides) {editor=""}
    if(editor=="effects") EffectsDialog(settings,actions.setBackgroundEffects) {editor=""}
    if(editor=="json") {
        var raw by remember { mutableStateOf(settings.overrides.toJson().toString(2)) }
        var error by remember { mutableStateOf("") }
        ZiyuAlertDialog(onDismissRequest={editor=""},title={ZiyuText("配色配置")},text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ZiyuText("编辑当前配色和图片效果。",style=MaterialTheme.typography.bodySmall)
                ZiyuOutlinedTextField(raw,{if(it.toByteArray(Charsets.UTF_8).size<=AppearanceOverrides.MAX_BYTES)raw=it},modifier=Modifier.fillMaxWidth(),minLines=6,maxLines=16)
                if(error.isNotBlank())ZiyuText(error,color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={ZiyuButton(onClick={runCatching { AppearanceOverrides.parse(raw) }.onSuccess {actions.setAppearanceOverrides(it);editor=""}.onFailure {error=it.message.orEmpty()}}) {ZiyuText("保存")}},
            dismissButton={ZiyuTextButton(onClick={editor=""}) {ZiyuText("取消")}})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsDialog(settings: AppearanceSettings,onSave:(AppearanceOverrides)->Unit,onDismiss:()->Unit) {
    var draft by remember { mutableStateOf(settings.overrides) }
    var mode by remember { mutableStateOf(AppearanceColorMode.SHARED) }
    var selected by remember { mutableStateOf<AppearanceColorRole?>(null) }
    val dark=when(mode) { AppearanceColorMode.SHARED->isSystemInDarkTheme(); AppearanceColorMode.LIGHT->false; AppearanceColorMode.DARK->true }
    val preview=settings.forDark(dark).copy(themeMode=if(dark)ThemeMode.DARK else ThemeMode.LIGHT,overrides=draft)
    val scheme=rememberAppearancePreviewColors(preview,dark)
    fun color(role: AppearanceColorRole): Color = when(role) {
        AppearanceColorRole.ACCENT->scheme.primary; AppearanceColorRole.ON_ACCENT->scheme.onPrimary
        AppearanceColorRole.TEXT->scheme.onSurface; AppearanceColorRole.SECONDARY->scheme.onSurfaceVariant
        AppearanceColorRole.DISABLED->scheme.onSurface.copy(alpha=.38f); AppearanceColorRole.SURFACE->scheme.background
        AppearanceColorRole.CARD->scheme.surfaceContainer; AppearanceColorRole.OUTLINE->scheme.outline; AppearanceColorRole.ERROR->scheme.error
    }
    ZiyuAlertDialog(onDismissRequest=onDismiss,title={ZiyuText("配色")},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                AppearanceColorMode.entries.forEach { value-> ZiyuTextButton(onClick={mode=value}) { ZiyuText(if(mode==value)"✓ ${value.label}" else value.label) } }
            }
            ThemeAppearancePreview(preview)
            AppearanceColorRole.entries.forEach { role ->
                val direct=draft.section(mode)[role.key]
                val inherited=mode!=AppearanceColorMode.SHARED && role.key in draft.shared
                Row(Modifier.fillMaxWidth().clickable {selected=role}.padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).background(direct?.let(::Color) ?: color(role),CircleShape))
                    Column(Modifier.weight(1f)) {
                        ZiyuText(role.label,style=MaterialTheme.typography.bodyMedium)
                        ZiyuText(direct?.let(::colorHex) ?: if(inherited)"跟随通用" else "自动",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ZiyuTextButton(onClick={draft=AppearanceOverrides(effects=draft.effects)}) {ZiyuText("恢复自动配色")}
        }
    },confirmButton={ZiyuButton(onClick={onSave(draft);onDismiss()}) {ZiyuText("保存")}},
        dismissButton={ZiyuTextButton(onClick=onDismiss) {ZiyuText("取消")}})
    selected?.let { role ->
        val initial=draft.section(mode)[role.key] ?: android.graphics.Color.argb(
            (color(role).alpha*255).roundToInt(),(color(role).red*255).roundToInt(),(color(role).green*255).roundToInt(),(color(role).blue*255).roundToInt())
        ColorDialog(role.label,initial,if(mode==AppearanceColorMode.SHARED)"自动" else "跟随通用",
            onSave={draft=draft.withColor(mode,role.key,it);selected=null},onDismiss={selected=null})
    }
}

@Composable
private fun ColorDialog(title:String,initial:Int,resetLabel:String,onSave:(Int?)->Unit,onDismiss:()->Unit) {
    val initialHsv=remember(initial) {FloatArray(3).also {android.graphics.Color.colorToHSV(initial,it)}}
    var hue by remember {mutableFloatStateOf(initialHsv[0])}
    var saturation by remember {mutableFloatStateOf(initialHsv[1])}
    var value by remember {mutableFloatStateOf(initialHsv[2])}
    var alpha by remember {mutableFloatStateOf(android.graphics.Color.alpha(initial)/255f)}
    var hex by remember {mutableStateOf(colorHex(initial))}
    fun update() {hex=colorHex(android.graphics.Color.HSVToColor((alpha*255).roundToInt(),floatArrayOf(hue,saturation,value)))}
    ZiyuAlertDialog(onDismissRequest=onDismiss,title={ZiyuText(title)},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(48.dp).background(Color(parseColorHex(hex) ?: initial),MaterialTheme.shapes.medium))
            ZiyuOutlinedTextField(hex,{text->
                hex=text.take(9);parseColorHex(hex)?.let {c->
                    val hsv=FloatArray(3);android.graphics.Color.colorToHSV(c,hsv)
                    hue=hsv[0];saturation=hsv[1];value=hsv[2];alpha=android.graphics.Color.alpha(c)/255f
                }
            },label={ZiyuText("颜色值")},singleLine=true,isError=parseColorHex(hex)==null,modifier=Modifier.fillMaxWidth())
            ZiyuText("色相",style=MaterialTheme.typography.bodySmall);ZiyuSlider(hue,{hue=it;update()},valueRange=0f..360f)
            ZiyuText("饱和度",style=MaterialTheme.typography.bodySmall);ZiyuSlider(saturation,{saturation=it;update()})
            ZiyuText("亮度",style=MaterialTheme.typography.bodySmall);ZiyuSlider(value,{value=it;update()})
            ZiyuText("不透明度",style=MaterialTheme.typography.bodySmall);ZiyuSlider(alpha,{alpha=it;update()})
            ZiyuTextButton(onClick={onSave(null)}) {ZiyuText(resetLabel)}
        }
    },confirmButton={ZiyuButton(onClick={parseColorHex(hex)?.let(onSave)},enabled=parseColorHex(hex)!=null) {ZiyuText("确定")}},
        dismissButton={ZiyuTextButton(onClick=onDismiss) {ZiyuText("取消")}})
}

@Composable
private fun EffectsDialog(settings:AppearanceSettings,onSave:(BackgroundEffects,Int,Int)->Unit,onDismiss:()->Unit) {
    val dark=when(settings.themeMode){ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true}
    val active=settings.forDark(dark)
    var draft by remember {mutableStateOf(settings.overrides.effects)}
    var blur by remember {mutableIntStateOf(active.backgroundBlur)}
    var scrim by remember {mutableIntStateOf(active.backgroundScrim)}
    ZiyuAlertDialog(onDismissRequest=onDismiss,title={ZiyuText("背景效果")},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ThemeAppearancePreview(active.copy(overrides=active.overrides.copy(effects=draft),backgroundBlur=blur,backgroundScrim=scrim))
            EffectSlider("饱和度",draft.saturation,20f..200f){draft=draft.copy(saturation=it)}
            EffectSlider("对比度",draft.contrast,20f..200f){draft=draft.copy(contrast=it)}
            EffectSlider("亮度",draft.brightness,-100f..100f){draft=draft.copy(brightness=it)}
            EffectSlider("锐化",draft.sharpen,0f..100f,android.os.Build.VERSION.SDK_INT>=33){draft=draft.copy(sharpen=it)}
            EffectSlider("模糊",blur.toFloat(),0f..100f,android.os.Build.VERSION.SDK_INT>=31){blur=it.roundToInt()}
            EffectSlider("遮罩",scrim.toFloat(),if(active.backgroundScrimIsSigned)-100f..100f else 0f..100f){scrim=it.roundToInt()}
            if(android.os.Build.VERSION.SDK_INT<33) ZiyuText("锐化需要 Android 13；模糊需要 Android 12。",style=MaterialTheme.typography.bodySmall)
            if(settings.adaptive!=null) ZiyuText("保存后固定为当前外观。",style=MaterialTheme.typography.bodySmall)
            ZiyuTextButton(onClick={draft=BackgroundEffects();blur=0;scrim=if(active.backgroundScrimIsSigned)0 else 64}) {ZiyuText("恢复默认")}
        }
    },confirmButton={ZiyuButton(onClick={onSave(draft,blur,scrim);onDismiss()}) {ZiyuText("保存")}},
        dismissButton={ZiyuTextButton(onClick=onDismiss) {ZiyuText("取消")}})
}

@Composable
private fun EffectSlider(title:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean=true,onChange:(Float)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        ZiyuText(title,style=MaterialTheme.typography.bodyMedium)
        ZiyuText(value.roundToInt().toString(),style=MaterialTheme.typography.bodySmall)
    }
    ZiyuSlider(value,onChange,enabled=enabled,valueRange=range)
}
