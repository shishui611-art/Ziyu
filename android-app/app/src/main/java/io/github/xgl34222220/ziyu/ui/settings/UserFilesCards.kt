package io.github.xgl34222220.ziyu.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.appearance.*
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundImageCard(
    settings: AppearanceSettings,
    onPath: (String) -> Unit,
    onDynamicWallpaper: (String, DynamicWallpaperTransform, Boolean, Int, Float) -> Unit = { _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var openDynamic by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = ""
            runCatching { importBackground(context, uri) }
                .onSuccess(onPath).onFailure { error = it.message ?: "无法解码该图片" }
            busy = false
        }
    }
    SettingCard("背景与动态壁纸") {
        ZiyuText("选择静态图片作为背景，或设置 MP4 / WebP 动态壁纸。", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ZiyuButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy) { ZiyuText(if (busy) "正在导入…" else "导入静态图片") }
            if (settings.backgroundPath.isNotBlank()) {
                ZiyuOutlinedButton(onClick = { onPath("") }, enabled = !busy) { ZiyuText("移除静态图") }
            }
        }
        ZiyuHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        val hasLive = settings.dynamicWallpaperPath.isNotBlank()
        ZiyuText(
            if (hasLive) "动态壁纸：已启用（${settings.dynamicWallpaperTransform.fit.label}）" else "动态壁纸：未设置",
            style = MaterialTheme.typography.bodySmall,
            color = LocalZiyuPaletteTokens.current.textSecondary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ZiyuOutlinedButton(onClick = { openDynamic = true }, enabled = !busy) {
                ZiyuText(if (hasLive) "调整动态壁纸" else "设置动态壁纸")
            }
            if (hasLive) {
                ZiyuTextButton(
                    onClick = {
                        onDynamicWallpaper("", DynamicWallpaperTransform(), settings.dynamicWallpaperMuted, settings.dynamicWallpaperFps, settings.dynamicWallpaperSpeed)
                    },
                    enabled = !busy,
                ) { ZiyuText("移除动态壁纸") }
            }
        }
        if (error.isNotBlank()) ZiyuText(error, color = MaterialTheme.colorScheme.error)
    }
    if (openDynamic) {
        DynamicWallpaperEditorDialog(
            settings = settings,
            onSave = { path, transform, muted, fps, speed ->
                onDynamicWallpaper(path, transform, muted, fps, speed)
                openDynamic = false
            },
            onDismiss = { openDynamic = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DynamicWallpaperEditorDialog(
    settings: AppearanceSettings,
    onSave: (String, DynamicWallpaperTransform, Boolean, Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember(settings.dynamicWallpaperPath) { mutableStateOf(settings.dynamicWallpaperPath) }
    var transform by remember(settings.dynamicWallpaperTransform) { mutableStateOf(settings.dynamicWallpaperTransform.sanitized()) }
    var muted by remember(settings.dynamicWallpaperMuted) { mutableStateOf(settings.dynamicWallpaperMuted) }
    var fps by remember(settings.dynamicWallpaperFps) { mutableIntStateOf(settings.dynamicWallpaperFps) }
    var speed by remember(settings.dynamicWallpaperSpeed) { mutableFloatStateOf(settings.dynamicWallpaperSpeed) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = ""
            try {
                path = stageDynamicWallpaperPreview(context, uri)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                error = e.message ?: "无法导入动态壁纸"
            } finally {
                busy = false
            }
        }
    }

    ZiyuAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { ZiyuText("动态壁纸") },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZiyuText("支持 MP4 视频和动图 WebP，背景遮罩与模糊沿用「视觉微调」中的设置。", style = MaterialTheme.typography.bodySmall, color = LocalZiyuPaletteTokens.current.textSecondary)

                if (path.isNotBlank()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        DynamicWallpaperBackground(
                            path = path,
                            muted = muted,
                            transform = transform,
                            fps = fps,
                            speed = speed,
                            blurPercent = settings.backgroundBlur,
                            modifier = Modifier.matchParentSize(),
                            onError = { reason -> error = "预览加载失败（$reason）" },
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZiyuButton(
                        onClick = { picker.launch(arrayOf("video/mp4", "image/webp", "video/*", "image/*")) },
                        enabled = !busy,
                    ) { ZiyuText(if (busy) "正在读取…" else if (path.isBlank()) "选择 MP4 / WebP" else "更换文件") }
                    if (path.isNotBlank()) {
                        ZiyuOutlinedButton(onClick = { path = ""; error = "" }, enabled = !busy) { ZiyuText("移除") }
                    }
                }

                if (path.isNotBlank()) {
                    ZiyuText("画面适配", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DynamicWallpaperFit.entries.forEach { fit ->
                            ThemeStoreChip(fit.label, transform.fit == fit) {
                                transform = transform.copy(fit = fit).sanitized()
                            }
                        }
                    }

                    if (transform.fit == DynamicWallpaperFit.CUSTOM) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ZiyuText("缩放  ${(transform.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                            ZiyuSlider(transform.zoom, { transform = transform.copy(zoom = it).sanitized() }, valueRange = 1f..4f, enabled = !busy)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ZiyuText("左右偏移  ${(transform.offsetX * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                            ZiyuSlider(transform.offsetX, { transform = transform.copy(offsetX = it).sanitized() }, valueRange = -1f..1f, enabled = !busy)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ZiyuText("上下偏移  ${(transform.offsetY * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                            ZiyuSlider(transform.offsetY, { transform = transform.copy(offsetY = it).sanitized() }, valueRange = -1f..1f, enabled = !busy)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        ZiyuText("静音播放", style = MaterialTheme.typography.bodyMedium)
                        ZiyuSwitch(muted, { muted = it }, enabled = !busy)
                    }

                    ZiyuText("播放速度", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f to "0.5×", 1f to "1.0×", 1.5f to "1.5×", 2f to "2.0×").forEach { (value, label) ->
                            ThemeStoreChip(label, kotlin.math.abs(speed - value) < 0.05f) { speed = value }
                        }
                    }

                    ZiyuText("帧率上限", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "自动", 30 to "30 FPS", 60 to "60 FPS").forEach { (value, label) ->
                            ThemeStoreChip(label, fps == value) { fps = value }
                        }
                    }
                }

                if (error.isNotBlank()) ZiyuText(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            ZiyuButton(
                onClick = { onSave(path, transform.sanitized(), muted, fps, speed) },
                enabled = !busy,
            ) { ZiyuText("保存") }
        },
        dismissButton = {
            ZiyuTextButton(onClick = onDismiss, enabled = !busy) { ZiyuText("取消") }
        },
    )
}

@Composable
internal fun UserStorageCard() {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    suspend fun command(action: String) {
        busy = true
        try {
            val result = RootShell.exec("sh /data/adb/modules/ziyu/common/app_bridge.sh storage $action", 15_000L)
            val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            require(result.code == 0 && json?.optString("status") == "ok") { json?.optString("message")?.takeIf { it.isNotBlank() } ?: result.stderr.ifBlank { "无法读取用户目录" } }
            val data = json!!.getJSONObject("data")
            current = data.optString("activePath"); pending = data.optString("pendingPath")
            path = pending.ifBlank { current }; error = false
            message = if (pending.isNotBlank()) "已保存，完整重启后迁移。" else ""
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Throwable) { error = true; message = failure.message ?: "无法读取用户目录" }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { command("status") }
    SettingCard("字体与文件") {
        ZiyuOutlinedTextField(
            value = path, onValueChange = { path = it; message = "" },
            placeholder = { ZiyuText(if (busy) "读取中…" else "保存路径") },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        if (message.isBlank() || error) ZiyuText("更改后，完整重启迁移文件。", style = MaterialTheme.typography.bodySmall)
        ZiyuButton(
            onClick = { scope.launch { command("request ${RootShell.quote(path.trim())}") } },
            enabled = !busy && path.isNotBlank() && path.trim() != pending.ifBlank { current }, modifier = Modifier.fillMaxWidth(),
        ) { ZiyuText(if (busy) "处理中…" else "保存") }
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (error) ZiyuTextButton(onClick = { scope.launch { command("status") } }, enabled = !busy) { ZiyuText("重新读取") }
    }
}

@Composable
internal fun DisplayConfigStorageCard() {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var defaultPath by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    suspend fun command(action: String) {
        busy = true
        try {
            val result = RootShell.exec(
                "sh /data/adb/modules/ziyu/common/app_bridge.sh display_config_storage $action", 15_000L,
            )
            val json = runCatching { JSONObject(result.stdout.trim()) }.getOrNull()
            require(result.code == 0 && json?.optString("status") == "ok") {
                json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: result.stderr.ifBlank { "无法读取配置保存位置" }
            }
            val data = json!!.getJSONObject("data")
            current = data.optString("path"); defaultPath = data.optString("defaultPath")
            path = current; message = if (action.startsWith("set ")) "已保存" else ""; error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            message = failure.message ?: "无法读取配置保存位置"; error = true
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) { command("status") }
    SettingCard("DPI 配置") {
        ZiyuOutlinedTextField(
            value = path, onValueChange = { path = it; message = "" },
            placeholder = { ZiyuText(if (busy) "读取中…" else "保存路径") },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        if (message.isBlank() || error) ZiyuText("保存后使用新目录。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
            ZiyuButton(
                onClick = { scope.launch { command("set ${RootShell.quote(path.trim())}") } },
                enabled = !busy && path.isNotBlank() && path.trim() != current, modifier = Modifier.weight(1f),
            ) { ZiyuText(if (busy) "保存中…" else "保存") }
            ZiyuOutlinedButton(
                onClick = { scope.launch { command("set ${RootShell.quote(defaultPath)}") } },
                enabled = !busy && defaultPath.isNotBlank() && current != defaultPath, modifier = Modifier.weight(1f),
            ) { ZiyuText("恢复默认") }
        }
        if (message.isNotBlank()) ZiyuText(message, style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (error) ZiyuTextButton(onClick = { scope.launch { command("status") } }, enabled = !busy) { ZiyuText("重新读取") }
    }
}
