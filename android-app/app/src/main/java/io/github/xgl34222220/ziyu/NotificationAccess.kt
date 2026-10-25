package io.github.xgl34222220.ziyu

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.xgl34222220.ziyu.ui.design.*
import io.github.xgl34222220.ziyu.ui.settings.SettingCard

internal const val FONT_TASK_CHANNEL_ID = "font_tasks"

private data class NotificationAccess(
    val runtime: Boolean,
    val app: Boolean,
    val channelImportance: Int?,
    val groupBlocked: Boolean,
    val promoted: Boolean?,
) {
    val notifications: Boolean get() = runtime && app
    val channel: Boolean get() = channelImportance != NotificationManager.IMPORTANCE_NONE && !groupBlocked
    val progress: Boolean get() = notifications && channel
    val promotionChannel: Boolean get() = channelImportance == null || channelImportance >= NotificationManager.IMPORTANCE_LOW
}

private fun readNotificationAccess(context: Context): NotificationAccess {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(FONT_TASK_CHANNEL_ID)
    return NotificationAccess(
        runtime = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        app = manager.areNotificationsEnabled(),
        channelImportance = channel?.importance,
        groupBlocked = channel?.group?.takeIf { it.isNotBlank() }?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true,
        promoted = if (Build.VERSION.SDK_INT >= 36) runCatching { manager.canPostPromotedNotifications() }.getOrNull() else null,
    )
}

private tailrec fun Context.notificationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.notificationActivity()
    else -> null
}

private fun openNotificationSettings(context: Context, promoted: Boolean = false, channel: Boolean = false): Boolean {
    val candidates = buildList {
        if (promoted && Build.VERSION.SDK_INT >= 36) {
            add(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        if (channel) add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, FONT_TASK_CHANNEL_ID))
        add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    return candidates.any { intent -> runCatching { context.startActivity(intent); true }.getOrDefault(false) }
}

@Composable
private fun observeNotificationAccess(onChanged: (NotificationAccess) -> Unit = {}): State<NotificationAccess> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val changed by rememberUpdatedState(onChanged)
    val state = remember(context) {
        runCatching { FontTaskNotificationController.ensureChannel(context) }
        mutableStateOf(readNotificationAccess(context))
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val next = readNotificationAccess(context)
                if (next != state.value) {
                    state.value = next
                    FontTaskNotificationController.republish(context)
                }
                changed(next)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Automatic permission request once; denied or deferred choices stay with the user. */
@Composable
internal fun NotificationPermissionOnboarding() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("notification_access", Context.MODE_PRIVATE) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var requestInFlight by rememberSaveable { mutableStateOf(false) }
    fun offer(access: NotificationAccess) {
        if (!requestInFlight && access.progress && access.promotionChannel && access.promoted == false && !prefs.getBoolean("promotion_guide_shown", false)) {
            prefs.edit().putBoolean("promotion_guide_shown", true).apply()
            showGuide = true
        }
    }
    val access by observeNotificationAccess(::offer)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestInFlight = false
        offer(readNotificationAccess(context))
        FontTaskNotificationController.republish(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !access.runtime && !prefs.getBoolean("notification_requested", false)) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            requestInFlight = true
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else offer(access)
    }
    if (showGuide) ZiyuAlertDialog(
        onDismissRequest = { showGuide = false },
        title = { ZiyuText("在桌面查看字体进度") },
        text = { ZiyuText("任务通知已开启。你还可以在系统中允许实时活动，以便在状态栏或锁屏查看进度。") },
        confirmButton = { ZiyuButton(onClick = { showGuide = false; openNotificationSettings(context, promoted = true) }) { ZiyuText("前往设置") } },
        dismissButton = { ZiyuTextButton(onClick = { showGuide = false }) { ZiyuText("稍后") } },
    )
}

@Composable
internal fun NotificationAccessCard() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("notification_access", Context.MODE_PRIVATE) }
    val observed by observeNotificationAccess()
    var accessOverride by remember { mutableStateOf<NotificationAccess?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var requesting by rememberSaveable { mutableStateOf(false) }
    val access = accessOverride ?: observed
    LaunchedEffect(observed) { accessOverride = null }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requesting = false
        accessOverride = readNotificationAccess(context)
        message = if (granted) "通知已允许，可以继续设置实时活动。" else "暂未允许通知，可随时在这里重新开启。"
        FontTaskNotificationController.republish(context)
    }
    fun settings(promoted: Boolean = false, channel: Boolean = false) {
        message = if (openNotificationSettings(context, promoted, channel)) "返回后会自动更新授权状态。" else "无法打开系统设置，请从系统的应用管理进入字域通知设置。"
    }
    fun allowNotifications() {
        val activity = context.notificationActivity()
        if (Build.VERSION.SDK_INT >= 33 && !access.runtime &&
            (!prefs.getBoolean("notification_requested", false) || activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            requesting = true
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else settings()
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingCard("字体进度显示") {
            ZiyuText(when {
                !access.notifications -> "先开启任务通知"
                !access.channel -> "开启字体处理进度类别"
                access.promoted == false -> "任务通知已就绪，可继续开启实时活动"
                else -> "任务通知已就绪"
            }, style = MaterialTheme.typography.titleMedium)
            ZiyuText("普通通知、通知类别和实时活动分别由系统管理，完成一项后再继续下一项。", style = MaterialTheme.typography.bodyMedium)
            if (message.isNotBlank()) ZiyuText(message, color = MaterialTheme.colorScheme.primary)
        }
        SettingCard("1 · 任务通知") {
            ZiyuText(if (access.notifications) "已允许" else "未开启", color = if (access.notifications) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            ZiyuText("应用、组合和删除字体时显示进度与结果。")
            ZiyuButton(onClick = { if (access.notifications) settings() else allowNotifications() }, enabled = !requesting) {
                ZiyuText(if (requesting) "等待系统授权…" else if (access.notifications) "管理任务通知" else "允许通知")
            }
        }
        SettingCard("2 · 字体处理进度类别") {
            ZiyuText(when { !access.notifications -> "需先开启任务通知"; !access.channel -> "已被关闭"; !access.promotionChannel -> "当前通知级别较低"; else -> "已开启" })
            ZiyuText("这个类别被关闭时，即使应用通知已允许，也看不到字体进度。")
            ZiyuOutlinedButton(onClick = { settings(channel = true) }, enabled = access.notifications) { ZiyuText("管理通知类别") }
        }
        SettingCard("3 · 实时活动") {
            ZiyuText(when {
                !access.progress -> "需先完成前两项"
                !access.promotionChannel -> "需提高字体处理进度的通知级别"
                access.promoted == true -> "系统已允许"
                access.promoted == false -> "未开启"
                else -> "请在系统设置中确认"
            })
            ZiyuText("支持时可在状态栏、桌面顶部或锁屏查看进度。显示位置和是否展示由系统决定。")
            ZiyuButton(onClick = { if (access.promotionChannel) settings(promoted = true) else settings(channel = true) }, enabled = access.progress) {
                ZiyuText(if (!access.promotionChannel) "调整通知级别" else if (access.promoted == true) "管理实时活动" else "设置实时活动")
            }
        }
        SettingCard("进度预览") {
            ZiyuText("发送一条 10 秒后自动消失的示例通知，查看这台手机的实际显示效果。")
            ZiyuOutlinedButton(onClick = { message = FontTaskNotificationController.preview(context) }, enabled = access.progress) { ZiyuText("预览进度通知") }
        }
    }
}
