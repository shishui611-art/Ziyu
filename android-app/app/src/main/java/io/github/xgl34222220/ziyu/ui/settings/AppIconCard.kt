package io.github.xgl34222220.ziyu.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.xgl34222220.ziyu.R
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppIconCustomizationCard(
    settings: AppearanceSettings,
    actions: AppearanceActions,
) {
    var openAppIcon by rememberSaveable { mutableStateOf(false) }
    var openBottomIcons by rememberSaveable { mutableStateOf(false) }
    val currentIcon = remember(settings.appIconId) { BuiltinAppIcons.find(settings.appIconId) }
    val customBottomCount = settings.bottomBarIcons.count { it.value.isNotBlank() }

    SettingCard("应用与底栏图标") {
        ZiyuText("切换桌面启动图标、添加自选快捷图标，或自定义底部导航栏图标。", style = MaterialTheme.typography.bodyMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BuiltinIconPreview(currentIcon, Modifier.size(40.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZiyuText(
                    "桌面图标：${currentIcon.label}" + if (settings.appIconDynamicColor && Build.VERSION.SDK_INT >= 33) "（动态取色）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ZiyuText(
                    if (customBottomCount > 0) "底栏图标：已自定义 $customBottomCount 项" else "底栏图标：默认",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZiyuPaletteTokens.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZiyuButton(onClick = { openAppIcon = true }) { ZiyuText("桌面图标") }
            ZiyuOutlinedButton(onClick = { openBottomIcons = true }) { ZiyuText("底栏图标") }
        }
    }

    if (openAppIcon) {
        AppIconPickerDialog(
            settings = settings,
            onApplyBuiltin = actions.setAppIcon,
            onDismiss = { openAppIcon = false },
        )
    }
    if (openBottomIcons) {
        BottomBarIconsDialog(
            settings = settings,
            onSave = actions.setBottomBarIcons,
            onDismiss = { openBottomIcons = false },
        )
    }
}

@Composable
private fun BuiltinIconPreview(icon: BuiltinAppIcon, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, icon.previewRes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = ContextCompat.getDrawable(context, icon.previewRes) ?: return@runCatching null
                val size = 144
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = icon.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppIconPickerDialog(
    settings: AppearanceSettings,
    onApplyBuiltin: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val palette = LocalZiyuPaletteTokens.current

    var selectedId by remember(settings.appIconId) { mutableStateOf(settings.appIconId) }
    var dynamicColor by remember(settings.appIconDynamicColor) { mutableStateOf(settings.appIconDynamicColor) }
    var packs by remember { mutableStateOf<List<AppIconPack>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var needSettingsButton by remember { mutableStateOf(false) }
    var pinCandidate by remember { mutableStateOf<Pair<AppIconPack, AppIconEntry>?>(null) }
    var deletePackTarget by remember { mutableStateOf<AppIconPack?>(null) }

    suspend fun reloadPacks() {
        packs = AppIconPackStorage.load(context)
    }

    LaunchedEffect(Unit) { reloadPacks() }

    val pickSingleImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; message = ""; needSettingsButton = false
            AppIconPackStorage.importSingleImage(context, uri)
                .onSuccess { entry ->
                    reloadPacks()
                    message = "已导入「${entry.name}」，点击图标可添加到桌面。"
                }
                .onFailure { message = it.message ?: "导入图片失败" }
            busy = false
        }
    }

    val pickZipPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; message = ""; needSettingsButton = false
            AppIconPackStorage.importZipPack(context, uri)
                .onSuccess { count ->
                    reloadPacks()
                    message = "已导入图标包，共 $count 个可用图标。"
                }
                .onFailure { message = it.message ?: "导入图标包失败" }
            busy = false
        }
    }

    ZiyuAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { ZiyuText("桌面应用图标") },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ZiyuText("内置图标", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ZiyuText("点击直接切换桌面启动图标，部分桌面需几秒刷新。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuiltinAppIcons.all.forEach { icon ->
                        val active = selectedId == icon.id
                        Surface(
                            onClick = {
                                selectedId = icon.id
                                onApplyBuiltin(icon.id, dynamicColor && Build.VERSION.SDK_INT >= 33)
                                message = "已切换为「${icon.label}」"
                                needSettingsButton = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = if (active) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHigh,
                            border = if (active) BorderStroke(1.5.dp, scheme.primary) else null,
                        ) {
                            Column(
                                Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BuiltinIconPreview(icon, Modifier.size(48.dp))
                                ZiyuText(
                                    icon.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) scheme.primary else palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                val supportsDynamic = Build.VERSION.SDK_INT >= 33
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ZiyuText("图标动态取色", style = MaterialTheme.typography.bodyMedium)
                        ZiyuText(
                            if (supportsDynamic) "配合支持主题图标的桌面自动取色" else "需要 Android 13 及以上系统",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    ZiyuSwitch(
                        checked = dynamicColor && supportsDynamic,
                        onCheckedChange = { enabled ->
                            dynamicColor = enabled
                            onApplyBuiltin(selectedId, enabled)
                            message = if (enabled) "已开启图标动态取色" else "已切换为静态配色图标"
                            needSettingsButton = false
                        },
                        enabled = supportsDynamic && !busy,
                    )
                }

                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))

                ZiyuText("自定义图片与图标包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ZiyuText("导入任意图片或 ZIP 图标包后，点击图标固定为桌面快捷方式。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZiyuButton(onClick = { pickSingleImage.launch(arrayOf("image/*")) }, enabled = !busy) {
                        ZiyuText("导入单图")
                    }
                    ZiyuOutlinedButton(onClick = { pickZipPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) {
                        ZiyuText("导入 ZIP 图标包")
                    }
                }

                if (message.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ZiyuText(message, style = MaterialTheme.typography.bodySmall, color = scheme.primary)
                        if (needSettingsButton) {
                            ZiyuOutlinedButton(onClick = { AppIconShortcutHelper.openAppSettings(context) }) {
                                ZiyuText("打开应用权限设置")
                            }
                        }
                    }
                }

                if (packs.isEmpty()) {
                    ZiyuText("暂未导入自定义图标。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                } else {
                    packs.forEach { pack ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(scheme.surfaceContainerHigh)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                ZiyuText(
                                    "${pack.name}（${pack.icons.size}）",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                ZiyuTextButton(onClick = { deletePackTarget = pack }, enabled = !busy) {
                                    ZiyuText("移除")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                pack.icons.forEach { entry ->
                                    val filePath = remember(pack.id, entry.fileName) {
                                        AppIconPackStorage.iconFile(context, pack, entry).absolutePath
                                    }
                                    Column(
                                        modifier = Modifier
                                            .width(64.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(enabled = !busy) { pinCandidate = pack to entry }
                                            .padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        ThemeFileImage(
                                            path = filePath,
                                            modifier = Modifier.size(50.dp),
                                            contentScale = ContentScale.Crop,
                                            maxDimension = 192,
                                        )
                                        ZiyuText(
                                            entry.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ZiyuButton(onClick = onDismiss, enabled = !busy) { ZiyuText("完成") }
        },
    )

    pinCandidate?.let { (pack, entry) ->
        var label by remember(entry.id) { mutableStateOf(context.getString(R.string.app_name)) }
        ZiyuAlertDialog(
            onDismissRequest = { pinCandidate = null },
            title = { ZiyuText("添加到桌面") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ThemeFileImage(
                            path = AppIconPackStorage.iconFile(context, pack, entry).absolutePath,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Crop,
                            maxDimension = 192,
                        )
                        ZiyuText(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    ZiyuOutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(24) },
                        label = { ZiyuText("桌面显示名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                ZiyuButton(onClick = {
                    pinCandidate = null
                    scope.launch {
                        busy = true
                        val bmp = AppIconPackStorage.loadBitmap(context, pack, entry)
                        if (bmp == null) {
                            message = "无法读取图标文件"
                            needSettingsButton = false
                        } else {
                            try {
                                when (val result = AppIconShortcutHelper.pinCustomIcon(context, "${pack.id}_${entry.id}", label.trim(), bmp)) {
                                    PinShortcutResult.Pinned -> {
                                        message = "已请求添加到桌面，请在桌面弹窗中确认。"
                                        needSettingsButton = false
                                    }
                                    PinShortcutResult.Updated -> {
                                        message = "已更新桌面上的快捷方式图标。"
                                        needSettingsButton = false
                                    }
                                    is PinShortcutResult.PermissionRequired -> {
                                        message = result.message
                                        needSettingsButton = true
                                    }
                                    is PinShortcutResult.Failed -> {
                                        message = result.message
                                        needSettingsButton = false
                                    }
                                }
                            } finally {
                                bmp.recycle()
                            }
                        }
                        busy = false
                    }
                }) { ZiyuText("添加到桌面") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZiyuTextButton(onClick = {
                        pinCandidate = null
                        scope.launch {
                            AppIconPackStorage.deleteIcon(context, pack.id, entry.id)
                            reloadPacks()
                            message = "已删除「${entry.name}」"
                        }
                    }) { ZiyuText("删除") }
                    ZiyuTextButton(onClick = { pinCandidate = null }) { ZiyuText("取消") }
                }
            },
        )
    }

    deletePackTarget?.let { pack ->
        ZiyuAlertDialog(
            onDismissRequest = { deletePackTarget = null },
            title = { ZiyuText("移除图标包？") },
            text = { ZiyuText("将删除已导入的「${pack.name}」及其中 ${pack.icons.size} 个图标，不影响已添加到桌面的快捷方式。") },
            confirmButton = {
                ZiyuButton(onClick = {
                    val id = pack.id
                    deletePackTarget = null
                    scope.launch {
                        AppIconPackStorage.deletePack(context, id)
                        reloadPacks()
                        message = "已移除图标包"
                    }
                }) { ZiyuText("移除") }
            },
            dismissButton = { ZiyuTextButton(onClick = { deletePackTarget = null }) { ZiyuText("取消") } },
        )
    }
}

@Composable
private fun BottomBarIconsDialog(
    settings: AppearanceSettings,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalZiyuPaletteTokens.current
    val scheme = MaterialTheme.colorScheme

    var draft by remember(settings.bottomBarIcons) { mutableStateOf(settings.bottomBarIcons) }
    var targetSlot by remember { mutableStateOf<BottomBarIconSlot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = targetSlot ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            busy = true; error = ""
            try {
                val staged = importBottomBarIcon(context, uri, slot)
                draft = draft + (slot.key to staged)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                error = e.message ?: "无法读取所选图标"
            } finally {
                busy = false
            }
        }
    }

    ZiyuAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { ZiyuText("底部导航栏图标") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZiyuText("为底部导航栏各页面设置自定义 PNG/WebP 图标，导出主题时会自动打包。", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)

                BottomBarIconSlot.entries.forEach { slot ->
                    val currentPath = draft[slot.key].orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(scheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (currentPath.isNotBlank()) {
                                ThemeFileImage(
                                    path = currentPath,
                                    modifier = Modifier.size(30.dp),
                                    contentScale = ContentScale.Fit,
                                    maxDimension = 128,
                                    placeholder = false,
                                )
                            } else {
                                val fallback = when (slot) {
                                    BottomBarIconSlot.HOME -> Icons.Rounded.Home
                                    BottomBarIconSlot.LIBRARY -> Icons.Rounded.ListAlt
                                    BottomBarIconSlot.STUDIO -> Icons.Rounded.Layers
                                    BottomBarIconSlot.SETTINGS -> Icons.Rounded.Settings
                                }
                                ZiyuIcon(fallback, null, Modifier.size(22.dp), tint = palette.textSecondary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ZiyuText(slot.label, style = MaterialTheme.typography.titleSmall)
                            ZiyuText(
                                if (currentPath.isNotBlank()) "已自定义" else "默认图标",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                            )
                        }
                        if (currentPath.isNotBlank()) {
                            ZiyuTextButton(
                                onClick = { draft = draft - slot.key },
                                enabled = !busy,
                            ) { ZiyuText("还原") }
                        }
                        ZiyuOutlinedButton(
                            onClick = {
                                targetSlot = slot
                                picker.launch(arrayOf("image/*"))
                            },
                            enabled = !busy,
                        ) { ZiyuText("选择") }
                    }
                }

                if (draft.isNotEmpty()) {
                    ZiyuTextButton(onClick = { draft = emptyMap() }, enabled = !busy) {
                        ZiyuText("全部恢复默认图标")
                    }
                }
                if (error.isNotBlank()) {
                    ZiyuText(error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
            }
        },
        confirmButton = {
            ZiyuButton(
                onClick = {
                    onSave(draft)
                    onDismiss()
                },
                enabled = !busy,
            ) { ZiyuText("保存") }
        },
        dismissButton = {
            ZiyuTextButton(onClick = onDismiss, enabled = !busy) { ZiyuText("取消") }
        },
    )
}
