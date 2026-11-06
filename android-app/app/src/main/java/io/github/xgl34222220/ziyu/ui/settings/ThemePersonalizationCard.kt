package io.github.xgl34222220.ziyu.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemePersonalizationCard(settings: AppearanceSettings) {
    var editor by rememberSaveable { mutableStateOf("") }
    SettingCard("个性化") {
        ZiyuText("更换首页卡片图片，改成你喜欢的文字。",style=MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            ZiyuButton(onClick={editor="card"}) { ZiyuText("首页卡片") }
            ZiyuOutlinedButton(onClick={editor="text"}) { ZiyuText(themeText("自定义文字")) }
        }
    }
    if(editor=="card") HomeCardEditor(settings) {editor=""}
    if(editor=="text") ThemeTextEditor(settings) {editor=""}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeTextEditor(settings: AppearanceSettings, onDismiss: () -> Unit) {
    val context=LocalContext.current
    val repository=remember(context) {AppearanceRepository(context)}
    val scope=rememberCoroutineScope()
    var values by remember {mutableStateOf(settings.customTexts)}
    var group by rememberSaveable {mutableStateOf("首页")}
    var selected by remember {mutableStateOf<ThemeTextKey?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    ZiyuAlertDialog(onDismissRequest={if(!busy)onDismiss()},title={ZiyuText(themeText("自定义文字"))},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ZiyuText("修改标题和名称，留空恢复默认。",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("首页","页面","设置").forEach { g -> ZiyuTextButton(onClick={group=g},enabled=!busy) {ZiyuText(if(g==group)"✓ $g" else g)} }
            }
            ThemeTextKey.entries.filter { it.group==group && it !in setOf(ThemeTextKey.SHORT_SAMPLE, ThemeTextKey.LOGO, ThemeTextKey.DIGITS, ThemeTextKey.SAMPLE, ThemeTextKey.FONT_LABEL) }.forEach { key ->
                Column(Modifier.fillMaxWidth().clickable(enabled=!busy) {selected=key}.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    ZiyuText(key.label,style=MaterialTheme.typography.labelMedium)
                    ZiyuText(resolveThemeText(values,key),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            ZiyuTextButton(onClick={values=values.filterKeys { ThemeTextKey.forKey(it)?.group!=group }},enabled=!busy) {ZiyuText("恢复本组默认")}
            if(error.isNotBlank()) ZiyuText(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={ZiyuButton(onClick={scope.launch {
        busy=true;error=""
        try {repository.setCustomTexts(values);onDismiss()} catch(c:CancellationException){throw c} catch(e:Exception){error=e.message ?: "保存失败"} finally {busy=false}
    }},enabled=!busy) {ZiyuText(if(busy)"保存中…" else "保存")}},dismissButton={ZiyuTextButton(onClick=onDismiss,enabled=!busy) {ZiyuText("取消")}})
    selected?.let { key ->
        var text by remember(key) {mutableStateOf(values[key.key].orEmpty())}
        ZiyuAlertDialog(onDismissRequest={selected=null},title={ZiyuText(key.label)},text={
            ZiyuOutlinedTextField(text,{text=it.take(key.limit)},placeholder={ZiyuText(key.default.ifBlank { "自动显示模块版本" })},modifier=Modifier.fillMaxWidth(),maxLines=4)
        },confirmButton={ZiyuButton(onClick={values=values.toMutableMap().apply {
                if(key==ThemeTextKey.STATUS_WORKING)remove(ThemeTextKey.SAMPLE.key)
                if(key==ThemeTextKey.STAT_FONT)remove(ThemeTextKey.FONT_LABEL.key)
                if(text.isBlank())remove(key.key) else put(key.key,text.trim())
            };selected=null}) {ZiyuText("确定")}},
            dismissButton={ZiyuTextButton(onClick={text=""}) {ZiyuText("恢复默认")}})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeCardEditor(settings: AppearanceSettings, onDismiss: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val repository=remember(context) {AppearanceRepository(context)}
    val systemDark=isSystemInDarkTheme()
    var dark by remember {mutableStateOf(when(settings.themeMode){ThemeMode.SYSTEM->systemDark;ThemeMode.LIGHT->false;ThemeMode.DARK->true})}
    val adaptive=settings.adaptive!=null
    var lightCard by remember {mutableStateOf(settings.adaptive?.light?.homeCard ?: settings.homeCard)}
    var darkCard by remember {mutableStateOf(settings.adaptive?.dark?.homeCard ?: settings.homeCard)}
    val card=if(adaptive && dark)darkCard else lightCard
    fun change(value: HomeCardDecoration) {if(adaptive && dark)darkCard=value else lightCard=value}
    var stickerIndex by remember {mutableIntStateOf(0)}
    var pickSticker by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val staging=remember {File(context.cacheDir,"themes/card-editor-${java.util.UUID.randomUUID()}").apply {mkdirs()}}
    DisposableEffect(staging) {onDispose {staging.listFiles()?.forEach {it.delete()};staging.delete()}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri ->
        if(uri!=null)scope.launch {
            busy=true;error=""
            try {
                val path=importBackground(context,uri,staging)
                if(pickSticker && card.stickers.size<CardSticker.MAX_COUNT) {
                    change(card.copy(stickers=card.stickers+CardSticker(path)));stickerIndex=card.stickers.size
                } else if(!pickSticker) change(card.copy(backgroundPath=path))
            } catch(c:CancellationException){throw c} catch(e:Exception){error=e.message ?: "无法读取图片"} finally {busy=false}
        }
    }
    fun pick(sticker: Boolean) {pickSticker=sticker;picker.launch("image/*")}
    val preview=settings.forDark(dark).copy(themeMode=if(dark)ThemeMode.DARK else ThemeMode.LIGHT,homeCard=card)
    ZiyuAlertDialog(onDismissRequest={if(!busy)onDismiss()},title={ZiyuText("首页卡片")},text={
        Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(adaptive) FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                ZiyuTextButton(onClick={dark=false;stickerIndex=0},enabled=!busy) {ZiyuText(if(!dark)"✓ 浅色" else "浅色")}
                ZiyuTextButton(onClick={dark=true;stickerIndex=0},enabled=!busy) {ZiyuText(if(dark)"✓ 深色" else "深色")}
            }
            ThemeAppearancePreview(preview)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                ZiyuButton(onClick={pick(false)},enabled=!busy) {ZiyuText("选择背景")}
                if(card.backgroundPath.isNotBlank()) ZiyuTextButton(onClick={change(card.copy(backgroundPath=""))},enabled=!busy) {ZiyuText("移除背景")}
                ZiyuOutlinedButton(onClick={pick(true)},enabled=!busy && card.stickers.size<CardSticker.MAX_COUNT) {ZiyuText("添加贴纸")}
            }
            if(card.backgroundPath.isNotBlank()) {
                CardNumber("背景不透明度",card.imageAlpha,0..100,!busy) {change(card.copy(imageAlpha=it))}
                CardNumber("背景遮罩",card.scrim,0..100,!busy) {change(card.copy(scrim=it))}
                if(android.os.Build.VERSION.SDK_INT>=31) CardNumber("背景模糊",card.blur,0..100,!busy) {change(card.copy(blur=it))}
                CardNumber("背景缩放",card.zoom,100..300,!busy) {change(card.copy(zoom=it))}
                CardNumber("背景左右位置",card.offsetX,-100..100,!busy) {change(card.copy(offsetX=it))}
                CardNumber("背景上下位置",card.offsetY,-100..100,!busy) {change(card.copy(offsetY=it))}
            }
            if(card.stickers.isNotEmpty()) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {card.stickers.indices.forEach {i ->
                    ZiyuTextButton(onClick={stickerIndex=i},enabled=!busy) {ZiyuText(if(i==stickerIndex)"✓ 贴纸 ${i+1}" else "贴纸 ${i+1}")}
                }}
                val index=stickerIndex.coerceIn(card.stickers.indices)
                val sticker=card.stickers[index]
                fun update(value: CardSticker) {change(card.copy(stickers=card.stickers.toMutableList().apply {set(index,value)}))}
                CardNumber("左右位置",(sticker.offsetX*100).roundToInt(),-50..50,!busy) {update(sticker.copy(offsetX=it/100f))}
                CardNumber("上下位置",(sticker.offsetY*100).roundToInt(),-50..50,!busy) {update(sticker.copy(offsetY=it/100f))}
                CardNumber("贴纸大小",(sticker.size*100).roundToInt(),12..35,!busy) {update(sticker.copy(size=it/100f))}
                CardNumber("旋转角度",sticker.rotation.roundToInt(),-180..180,!busy) {update(sticker.copy(rotation=it.toFloat()))}
                ZiyuTextButton(onClick={change(card.copy(stickers=card.stickers.filterIndexed {i,_->i!=index}));stickerIndex=0},enabled=!busy) {ZiyuText("移除贴纸")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {ZiyuText("显示卡片装饰");ZiyuSwitch(!card.hideSample,{change(card.copy(hideSample=!it))},enabled=!busy)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {ZiyuText("显示卡片标题");ZiyuSwitch(!card.hideLabel,{change(card.copy(hideLabel=!it))},enabled=!busy)}
            ZiyuTextButton(onClick={change(HomeCardDecoration())},enabled=!busy) {ZiyuText("恢复此卡片默认")}
            if(error.isNotBlank())ZiyuText(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={ZiyuButton(onClick={scope.launch {
        busy=true;error=""
        try {
            repository.setHomeCards(lightCard,if(adaptive)darkCard else null)
            onDismiss()
        } catch(c:CancellationException){throw c} catch(e:Exception){error=e.message ?: "保存失败"} finally {busy=false}
    }},enabled=!busy) {ZiyuText(if(busy)"处理中…" else "保存")}},dismissButton={ZiyuTextButton(onClick=onDismiss,enabled=!busy) {ZiyuText("取消")}})
}

@Composable
private fun CardNumber(label: String,value: Int,range: IntRange,enabled: Boolean,onChange: (Int)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        ZiyuText("$label  $value",style=MaterialTheme.typography.bodySmall)
        ZiyuSlider(value.toFloat(),{onChange(it.roundToInt())},valueRange=range.first.toFloat()..range.last.toFloat(),enabled=enabled)
    }
}
