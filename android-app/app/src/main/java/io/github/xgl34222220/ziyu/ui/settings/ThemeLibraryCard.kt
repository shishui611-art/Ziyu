package io.github.xgl34222220.ziyu.ui.settings

import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.rememberAppearancePreviewColors
import io.github.xgl34222220.ziyu.ui.theme.appearanceCardBackground
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction
import java.io.File
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ThemeLibraryCard(settings: AppearanceSettings) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingCard("主题商店") {
        ZiyuText("下载主题，或导入、分享自己的外观。", style=MaterialTheme.typography.bodyMedium)
        if(settings.themeName.isNotBlank()) ZiyuText(settings.themeName,
            style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
        ZiyuButton(onClick={open=true}) { ZiyuText("打开主题商店") }
    }
    if(open) ThemeLibrarySheet(settings) { open=false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeLibrarySheet(settings: AppearanceSettings, onDismiss:()->Unit) {
    val model: ThemeLibraryViewModel=viewModel(key="ziyu-theme-library")
    val state by model.state.collectAsStateWithLifecycle()
    var sourceDialog by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<StoreTheme?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var actionsMenu by remember { mutableStateOf(false) }
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var aspects by remember(state.source.url) { mutableStateOf(emptyMap<String, Float>()) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.import(it,settings.uiStyle) } }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { model.export(settings,it) } }
    LaunchedEffect(Unit) { if(state.accepted)model.refresh() }
    DisposableEffect(model) { onDispose { model.close() } }
    val tags=remember(state.catalog) {
        val counts=state.catalog.flatMap { it.tags }.map(::themeTagKey).filter { it.isNotBlank() && it!=ADAPTIVE_THEME_TAG }
            .groupingBy { it }.eachCount()
        listOf(ADAPTIVE_THEME_TAG)+counts.keys.sortedWith(compareByDescending<String> { counts[it] }.thenBy { it }).take(17)
    }
    val visible=remember(state.catalog,query,sort,selectedTags) {
        val needle=themeSearchKey(query)
        state.catalog.filter { theme ->
            (needle.isBlank() || themeSearchKey(theme.name+" "+theme.author+" "+theme.tags.joinToString()).contains(needle)) &&
                (selectedTags.isEmpty() || selectedTags.any { selected ->
                    if(selected==ADAPTIVE_THEME_TAG) theme.isAdaptiveTheme() else theme.tags.any { themeTagKey(it)==selected }
                })
        }.let { themes -> when(sort) {
            1 -> themes.sortedWith(compareByDescending<StoreTheme> { it.heat ?: -1 }.thenByDescending { it.downloads })
            2 -> themes.sortedByDescending { it.downloads }
            3 -> themes.sortedByDescending { it.updated }
            4 -> themes.sortedBy { themeSearchKey(it.name) }
            5 -> themes.sortedByDescending { it.size }
            else -> themes
        } }
    }
    val palette=LocalZiyuPaletteTokens.current
    val scheme=MaterialTheme.colorScheme
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,dismissOnClickOutside=false)) {
        Column(Modifier.fillMaxSize().background(scheme.background).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ZiyuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack,"返回",onDismiss,palette.cardBackground,contentColor=palette.textPrimary)
                Spacer(Modifier.weight(1f))
                ZiyuHeaderAction(Icons.Rounded.Refresh,"刷新",model::refresh,palette.cardBackground,enabled=state.accepted && !state.busy,contentColor=palette.textPrimary)
                ZiyuHeaderAction(if(grid)Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,if(grid)"切换单列" else "切换双列",
                    {grid=!grid},palette.cardBackground,contentColor=palette.textPrimary)
                Box {
                    ZiyuHeaderAction(Icons.Rounded.MoreVert,"更多",{actionsMenu=true},palette.cardBackground,contentColor=palette.textPrimary)
                    DropdownMenu(expanded=actionsMenu,onDismissRequest={actionsMenu=false}) {
                        DropdownMenuItem(text={ZiyuText("导出当前主题")},enabled=!state.busy,onClick={actionsMenu=false;exporter.launch("字域主题.ziyu-theme")})
                        DropdownMenuItem(text={ZiyuText("主题来源")},enabled=!state.busy,onClick={actionsMenu=false;sourceDialog=true})
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val twoColumns=grid && maxWidth>=360.dp && LocalConfiguration.current.fontScale<=1.3f
                LazyVerticalGrid(columns=GridCells.Fixed(if(twoColumns)2 else 1),modifier=Modifier.fillMaxSize(),
                    horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(14.dp),
                    contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=112.dp)) {
                    item(key="store-title",span={GridItemSpan(maxLineSpan)}) {
                        ZiyuText(resolveThemeText(settings.customTexts,ThemeTextKey.STORE),Modifier.padding(bottom=12.dp),
                            fontSize=40.sp,lineHeight=48.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    item(key="store-search",span={GridItemSpan(maxLineSpan)}) {
                        ZiyuOutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),singleLine=true,
                            shape=RoundedCornerShape(32.dp),placeholder={ZiyuText("搜索主题或作者")},
                            leadingIcon={ZiyuSearchIconSlot { ZiyuIcon(Icons.Rounded.Search,null) }},
                            colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=palette.cardBackground,unfocusedContainerColor=palette.cardBackground,
                                unfocusedBorderColor=Color.Transparent,focusedBorderColor=scheme.primary))
                    }
                    if(!state.accepted) item(key="store-consent",span={GridItemSpan(maxLineSpan)}) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.cardBackground).padding(20.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            ZiyuText("在线主题来自字域主题商店；连接后，提供方会收到你的 IP 和浏览、下载请求。",style=MaterialTheme.typography.bodyMedium)
                            ZiyuButton(onClick=model::accept,enabled=!state.busy) { ZiyuText("同意并连接") }
                        }
                    }
                    if(state.accepted) {
                        item(key="store-tags",span={GridItemSpan(maxLineSpan)}) {
                            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                ZiyuText("主题标签",style=MaterialTheme.typography.labelLarge)
                                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    item { ThemeStoreChip("全部",selectedTags.isEmpty()) { selectedTags=emptyList() } }
                                    rowItems(tags,key={it}) { tag -> ThemeStoreChip(if(tag==ADAPTIVE_THEME_TAG)"自适应主题" else tag,tag in selectedTags) {
                                        selectedTags=if(tag in selectedTags)selectedTags-tag else selectedTags+tag
                                    } }
                                }
                            }
                        }
                        item(key="store-sorts",span={GridItemSpan(maxLineSpan)}) {
                            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                rowItems(listOf(0,1,2,3,4,5).filter { it!=1 || state.catalog.any { theme -> theme.heat!=null } }) { option ->
                                    ThemeStoreChip(listOf("默认","热度","下载量","最新","名称","大小")[option],sort==option) { sort=option }
                                }
                            }
                        }
                    }
                    if(state.busy) item(key="store-progress",span={GridItemSpan(maxLineSpan)}) {
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            val transfer=state.transfer
                            if(transfer!=null) {
                                LinearProgressIndicator(progress={if(transfer.total>0)(transfer.done.toFloat()/transfer.total).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth())
                                ZiyuText("${themeBytes(transfer.done)} / ${themeBytes(transfer.total)}"+
                                    if(transfer.bytesPerSecond>0)" · ${themeBytes(transfer.bytesPerSecond)}/秒" else "",style=MaterialTheme.typography.bodySmall)
                            } else { LinearProgressIndicator(Modifier.fillMaxWidth()); ZiyuText("处理中…",style=MaterialTheme.typography.bodySmall) }
                            ZiyuTextButton(onClick=model::close) { ZiyuText("取消") }
                        }
                    }
                    if(state.message.isNotBlank()) item(key="store-message",span={GridItemSpan(maxLineSpan)}) {
                        ZiyuText(state.message,style=MaterialTheme.typography.bodySmall,color=scheme.primary)
                    }
                    if(state.accepted && !state.busy && visible.isEmpty() && state.catalog.isNotEmpty()) item(key="store-empty",span={GridItemSpan(maxLineSpan)}) {
                        ZiyuText("没有找到主题",Modifier.padding(vertical=24.dp),color=palette.textSecondary)
                    }
                    items(visible,key={"theme-${it.id}"},span={ theme -> GridItemSpan(if(twoColumns && (aspects[theme.id] ?: 0f)>1f)maxLineSpan else 1) }) { theme ->
                        val compact=twoColumns && (aspects[theme.id] ?: .65f)<=1f
                        ThemeStorePreviewCard(theme,model,state.source,compact,!state.busy,
                            onDetails={detail=theme},onDownload={model.download(theme,settings.uiStyle)},
                            onAspect={ratio -> if(aspects[theme.id]!=ratio)aspects=aspects+(theme.id to ratio)})
                    }
                }
                ZiyuButton(onClick={importer.launch(arrayOf("*/*"))},enabled=!state.busy,
                    modifier=Modifier.align(Alignment.BottomEnd).padding(16.dp).heightIn(min=56.dp),shape=RoundedCornerShape(24.dp),
                    contentPadding=PaddingValues(horizontal=22.dp,vertical=16.dp)) {
                    ZiyuIcon(Icons.Rounded.FileOpen,null)
                    Spacer(Modifier.width(10.dp));ZiyuText("从文件导入")
                }
            }
        }
    detail?.let { theme ->
        AlertDialog(onDismissRequest={detail=null},title={ZiyuText("主题详情")},text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ZiyuText(theme.name,style=MaterialTheme.typography.titleSmall)
                ZiyuText(theme.author.ifBlank { "未署名" },style=MaterialTheme.typography.bodyMedium)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    rowItems(theme.screenshots.ifEmpty { listOf(theme.preview) }.filter { it.isNotBlank() }) { path ->
                        RemoteThemeImage(model,state.source,path,Modifier.width(164.dp).height(260.dp))
                    }
                }
                if(theme.description.isNotBlank())ZiyuText(theme.description,style=MaterialTheme.typography.bodyMedium)
                ZiyuText(themeBytes(theme.size),style=MaterialTheme.typography.bodySmall)
                if(theme.license.isNotBlank())ZiyuText("许可：${theme.license}",style=MaterialTheme.typography.bodySmall)
                if(theme.format !in 1..4)ZiyuText("暂不支持此主题版本",color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={ZiyuButton(onClick={detail=null;model.download(theme,settings.uiStyle)},enabled=!state.busy && theme.format in 1..4) { ZiyuText("下载") }},
            dismissButton={ZiyuTextButton(onClick={detail=null}) { ZiyuText("关闭") }})
    }
    state.pending?.let { prepared ->
        var includeTexts by remember(prepared) { mutableStateOf(true) }
        var appearanceMode by remember(prepared) { mutableStateOf(prepared.settings.themeMode) }
        AlertDialog(onDismissRequest=model::discard,title={ZiyuText("应用主题")},text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ZiyuText(prepared.settings.themeName,style=MaterialTheme.typography.titleSmall)
                if(prepared.settings.themeAuthor.isNotBlank()) ZiyuText(prepared.settings.themeAuthor,style=MaterialTheme.typography.bodySmall)
                ThemeAppearancePreview(prepared.settings.copy(themeMode=appearanceMode,
                    customTexts=if(includeTexts)prepared.settings.customTexts else settings.customTexts))
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    rowItems(ThemeMode.entries) { mode -> ThemeStoreChip(mode.label,appearanceMode==mode) { appearanceMode=mode } }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    ZiyuText("应用主题文字")
                    ZiyuSwitch(includeTexts,{includeTexts=it},enabled=!state.busy)
                }
                prepared.warnings.forEach { ZiyuText(it,style=MaterialTheme.typography.bodySmall) }
                if(prepared.settings.themeLicense.isNotBlank())ZiyuText("许可：${prepared.settings.themeLicense}",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={ZiyuButton(onClick={model.apply(includeTexts,appearanceMode)},enabled=!state.busy) { ZiyuText("应用") }},
            dismissButton={ZiyuTextButton(onClick=model::discard,enabled=!state.busy) { ZiyuText("取消") }})
    }
    if(sourceDialog) {
        var url by remember { mutableStateOf(state.source.url) }
        var code by remember { mutableStateOf(state.source.accessCode) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest={sourceDialog=false},title={ZiyuText("主题来源")},text={
            Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ZiyuText("默认来源自动连接，自定义来源可填写访问码。",style=MaterialTheme.typography.bodySmall)
                ZiyuOutlinedTextField(url,{url=it},label={ZiyuText("HTTPS 地址")},singleLine=true,modifier=Modifier.fillMaxWidth())
                ZiyuOutlinedTextField(code,{code=it},label={ZiyuText("访问码（可选）")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank())ZiyuText(error,color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={ZiyuButton(onClick={runCatching { ThemeStoreRepository.normalize(url) }
            .onSuccess { sourceDialog=false;model.saveSource(url,code) }.onFailure { error=it.message.orEmpty() }}) { ZiyuText("连接") }},
            dismissButton={ZiyuTextButton(onClick={sourceDialog=false}) { ZiyuText("取消") }})
    }
    }
}

private const val ADAPTIVE_THEME_TAG="adaptive"
private fun themeTagKey(tag: String): String {
    val key=tag.trim().lowercase(java.util.Locale.ROOT)
    return if(key in setOf("adaptive","auto","automatic","lightdark","light/dark","自适应","自适应主题","自适应主题包")) ADAPTIVE_THEME_TAG else key
}
private fun StoreTheme.isAdaptiveTheme()=adaptive || tags.any { themeTagKey(it)==ADAPTIVE_THEME_TAG }

@Composable
private fun ThemeStoreChip(label: String,selected: Boolean,onClick:()->Unit) {
    val color=if(selected)MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    FilterChip(selected=selected,onClick=onClick,label={ZiyuText(label,color=color)},shape=RoundedCornerShape(24.dp),
        modifier=Modifier.heightIn(min=44.dp),leadingIcon=if(selected) { {ZiyuIcon(Icons.Rounded.Check,null,Modifier.size(18.dp),tint=color)} } else null)
}

/** Banner layout references the pinned SukiSU theme store; network and apply flow stay Ziyu's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeStorePreviewCard(theme: StoreTheme,model: ThemeLibraryViewModel,source: ThemeSource,compact: Boolean,enabled: Boolean,
                                  onDetails:()->Unit,onDownload:()->Unit,onAspect:(Float)->Unit) {
    val fontScale=LocalConfiguration.current.fontScale.coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().heightIn(min=(200*fontScale).dp).aspectRatio(if(compact).65f else 1.65f)
        .clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(enabled=enabled,role=Role.Button,onClick=onDetails)) {
        RemoteThemeImage(model,source,theme.preview.ifBlank { theme.screenshots.firstOrNull().orEmpty() },Modifier.matchParentSize(),ContentScale.Crop,onAspect)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(.30f to Color.Transparent,.66f to Color.Black.copy(alpha=.32f),1f to Color.Black.copy(alpha=.88f))))
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                theme.heat?.let { ThemeStoreMetric(Icons.Rounded.FlashOn,it,"热度") }
                ThemeStoreMetric(Icons.Rounded.FileDownload,theme.downloads,"下载量")
            }
            if(theme.isAdaptiveTheme()) Box(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal=10.dp,vertical=5.dp)) {
                ZiyuText(if(compact)"自适应" else "自适应主题",color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                ZiyuText(theme.name,color=Color.White,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                val date=theme.updated.substringBefore('T').replace('-','/')
                ZiyuText(listOf(theme.author.ifBlank { "未署名" },date).filter { it.isNotBlank() }.joinToString(" · "),
                    color=Color.White.copy(alpha=.9f),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(compact) ZiyuIconButton(onClick=onDownload,enabled=enabled && theme.format in 1..4,
                colors=IconButtonDefaults.iconButtonColors(containerColor=Color.White.copy(alpha=.25f),contentColor=Color.White)) {
                ZiyuIcon(Icons.Rounded.FileDownload,"下载 ${theme.name}")
            } else ZiyuButton(onClick=onDownload,enabled=enabled && theme.format in 1..4,
                colors=ButtonDefaults.buttonColors(containerColor=Color.White.copy(alpha=.22f),contentColor=Color.White),
                border=BorderStroke(1.dp,Color.White.copy(alpha=.4f)),shape=RoundedCornerShape(24.dp)) {
                ZiyuIcon(Icons.Rounded.FileDownload,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));ZiyuText("下载")
            }
        }
    }
}

@Composable
private fun ThemeStoreMetric(icon: ImageVector,value: Long,label: String) {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha=.48f)).padding(horizontal=8.dp,vertical=5.dp),
        horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
        ZiyuIcon(icon,label,Modifier.size(14.dp),tint=Color.White)
        val text=if(value>=1000)String.format(java.util.Locale.ROOT,"%.1fk",value/1000.0) else value.toString()
        ZiyuText(text,color=Color.White,style=MaterialTheme.typography.labelSmall,maxLines=1)
    }
}

@Composable
internal fun ThemeAppearancePreview(settings: AppearanceSettings) {
    val systemDark=isSystemInDarkTheme()
    val selectedDark=when(settings.themeMode) { ThemeMode.SYSTEM->systemDark; ThemeMode.LIGHT->false; ThemeMode.DARK->true }
    val current=settings.forDark(selectedDark)
    val dark=when(current.themeMode) { ThemeMode.SYSTEM->systemDark; ThemeMode.LIGHT->false; ThemeMode.DARK->true }
    val colors=rememberAppearancePreviewColors(current,dark)
    val hero=homeHeroColors(current,colors,dark)
    val card=appearanceCardBackground(current,colors,dark)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.background)) {
        val titleScale=((maxWidth-24.dp)/(LocalConfiguration.current.screenWidthDp-32).coerceAtLeast(240).dp).coerceAtMost(1f)
        ThemeFileImage(current.backgroundPath,Modifier.matchParentSize(),current.overrides.effects,current.backgroundBlur,ContentScale.Crop,placeholder=false)
        Box(Modifier.matchParentSize().background(backgroundScrimColor(current,colors.background)))
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ZiyuText(resolveThemeText(current.customTexts,ThemeTextKey.HOME_TITLE),color=colors.onSurface,fontSize=40.sp*titleScale,lineHeight=48.sp*titleScale,maxLines=1,overflow=TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(hero.background.copy(alpha=hero.background.alpha*(1f-current.cardTransparency/100f)))) {
                HomeHeroFace(current,hero,"模块已连接",io.github.xgl34222220.ziyu.BuildConfig.VERSION_NAME,preview=true)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ThemeStatPreview(resolveThemeText(current.customTexts,ThemeTextKey.STAT_FONT),"字体名称",colors,card,Modifier.weight(1f),current.cardTransparency)
                ThemeStatPreview(resolveThemeText(current.customTexts,ThemeTextKey.STAT_LIBRARY),"—",colors,card,Modifier.weight(1f),current.cardTransparency)
            }
        }
    }
}

@Composable
private fun ThemeStatPreview(title: String,value: String,colors: ColorScheme,card: Color,modifier: Modifier,transparency: Int) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(card.copy(alpha=card.alpha*(1f-transparency/100f)))
        .padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        ZiyuText(value,color=colors.onSurface,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        ZiyuText(title,color=colors.onSurfaceVariant,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun RemoteThemeImage(model: ThemeLibraryViewModel,source: ThemeSource,path: String,modifier: Modifier,contentScale: ContentScale=ContentScale.Fit,onAspect:(Float)->Unit={}) {
    val file by produceState<File?>(null,source,path) {
        try { value=model.preview(source,path) } catch(c:CancellationException) { throw c } catch(_:Exception) { value=null }
    }
    ThemeFileImage(file?.absolutePath.orEmpty(),modifier,contentScale=contentScale,onAspect=onAspect)
}

@Composable
private fun ThemeFileImage(path: String,modifier: Modifier,effects: BackgroundEffects=BackgroundEffects(),blur: Int=0,
                           contentScale: ContentScale=ContentScale.Fit,onAspect:(Float)->Unit={},placeholder: Boolean=true) {
    val bitmap by produceState<ImageBitmap?>(null,path) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val file=File(path); if(path.isBlank() || !file.isFile)return@runCatching null
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,info,_ ->
                    val scale=minOf(1.0,768.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    LaunchedEffect(bitmap) { bitmap?.let { onAspect(it.width.toFloat()/it.height.coerceAtLeast(1)) } }
    Box(modifier.clip(RoundedCornerShape(14.dp)).then(if(placeholder)Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)) {
        bitmap?.let { Image(it,null,Modifier.fillMaxSize().backgroundEffects(effects)
            .then(if(android.os.Build.VERSION.SDK_INT>=31 && blur>0)Modifier.blur((blur*.32f).dp) else Modifier),contentScale=contentScale) }
    }
}

private fun themeSearchKey(value: String)=Normalizer.normalize(value,Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT)
    .filter { Character.getType(it) !in setOf(Character.FORMAT.toInt(),Character.NON_SPACING_MARK.toInt()) }
private fun themeBytes(value: Long)=if(value>=1024*1024)String.format(java.util.Locale.ROOT,"%.1f MB",value/1048576.0) else "${value/1024} KB"
