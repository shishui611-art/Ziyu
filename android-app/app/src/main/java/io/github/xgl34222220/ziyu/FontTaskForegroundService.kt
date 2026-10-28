package io.github.xgl34222220.ziyu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FONT_TASK_CHANNEL = FONT_TASK_CHANNEL_ID
private const val FONT_TASK_NOTIFICATION_ID = 14332
private const val FONT_TASK_START_REJECTED_NOTICE =
    "系统未能启动字体后台进度服务；任务仍继续，打开字域查看进度。"
private const val FONT_TASK_DATA_SYNC_TIMEOUT_NOTICE =
    "系统已结束字体后台进度服务；任务仍继续，打开字域查看进度。"

internal fun fontTaskDetachedNotice(
    spec: FontTaskNotificationSpec,
    reason: String,
): FontTaskNotificationSpec = if (spec.ongoing) {
    spec.copy(message = "$reason\n${spec.message}")
} else {
    spec
}

internal data class FontTaskNotificationSpec(
    val kind: String,
    val state: String,
    val message: String,
    val progress: Int? = null,
) {
    val ongoing: Boolean get() = state == "queued" || state == "running"
    val chipText: String get() {
        val action = when (kind) { "mix" -> "组合"; "delete" -> "删除"; else -> "应用" }
        return if (state == "running" && progress != null) "$action${progress.coerceIn(0, 100)}%" else "${action}中"
    }
    val title: String get() = when (kind) {
        "delete" -> when (state) {
            "success" -> "字体已删除"
            "failed" -> "字体删除失败"
            "cancelled" -> "字体删除已中止"
            else -> "正在后台删除字体"
        }
        "mix" -> when (state) {
            "success" -> "组合字体已生成"
            "failed" -> "组合字体生成失败"
            "cancelled" -> "组合字体生成已取消"
            else -> "正在生成组合字体"
        }
        else -> when (state) {
            "success" -> "字体已准备完成"
            "failed" -> "字体应用失败"
            "cancelled" -> "字体应用已取消"
            else -> "正在应用字体"
        }
    }
}

internal object FontTaskNotificationController {
    @Volatile private var serviceExpected = false
    @Volatile private var detachedReason: String? = null
    @Volatile private var latest: FontTaskNotificationSpec? = null
    private var lastPosted: FontTaskNotificationSpec? = null

    fun start(context: Context): Boolean {
        // Every new task needs its own startId, even while the previous task's
        // service is stopping. stopSelfResult(oldId) then cannot stop the new task.
        return try {
            ensureChannel(context)
            ContextCompat.startForegroundService(context, Intent(context, FontTaskForegroundService::class.java))
            markServiceAttached()
            true
        } catch (error: RuntimeException) {
            markServiceDetached(FONT_TASK_START_REJECTED_NOTICE)
            Log.w("ZiyuFontTask", "System rejected the font task foreground service", error)
            false
        }
    }

    fun markServiceAttached() {
        detachedReason = null
        serviceExpected = true
    }

    fun markServiceDetached(reason: String) {
        detachedReason = reason
        serviceExpected = false
    }

    fun markServiceStopped() {
        serviceExpected = false
    }

    fun isServiceExpected(): Boolean = serviceExpected

    private fun forDisplay(spec: FontTaskNotificationSpec): FontTaskNotificationSpec =
        detachedReason?.let { fontTaskDetachedNotice(spec, it) } ?: spec

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(FONT_TASK_CHANNEL, "字体处理进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示字体生成、应用与删除的进度"
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    fun build(context: Context, spec: FontTaskNotificationSpec): Notification {
        // Tapping a font-task notification or its live-update chip opens the home
        // screen, not the task/log page; the task center stays reachable from home.
        val openTasks = PendingIntent.getActivity(
            context,
            FONT_TASK_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, FONT_TASK_CHANNEL)
            .setSmallIcon(R.drawable.ic_font_task)
            .setContentTitle(spec.title)
            .setContentText(spec.message)
            .setContentIntent(openTasks)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            .setShowWhen(false)

        if (spec.ongoing) {
            if (Build.VERSION.SDK_INT >= 36) {
                builder.setStyle(
                    Notification.ProgressStyle().apply {
                        if (spec.progress == null) setProgressIndeterminate(true)
                        else {
                            setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
                            setProgress(spec.progress.coerceIn(0, 100))
                        }
                    },
                )
                builder.addExtras(Bundle().apply {
                    putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
                })
                // A few OEM API 36 builds expose ProgressStyle before the promotion methods.
                runCatching {
                    builder.setRequestPromotedOngoing(true).setShortCriticalText(spec.chipText)
                }.onFailure { Log.d("ZiyuFontTask", "Compact live-update API unavailable", it) }
            } else {
                builder.setProgress(100, spec.progress?.coerceIn(0, 100) ?: 0, spec.progress == null)
            }
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(spec.message))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    @Synchronized
    fun notify(context: Context, spec: FontTaskNotificationSpec, force: Boolean = false) {
        latest = spec
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val displayed = forDisplay(spec)
        if (!force && lastPosted == displayed) return
        try {
            manager.notify(FONT_TASK_NOTIFICATION_ID, build(context, displayed))
            lastPosted = displayed
        } catch (error: SecurityException) {
            // Notification permission must never abort the actual font task.
            Log.w("ZiyuFontTask", "Notification permission changed", error)
        }
    }

    fun republish(context: Context) { latest?.takeIf { it.ongoing }?.let { notify(context, it, force = true) } }

    fun preview(context: Context): String {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "请先允许任务通知。"
        if (manager.getNotificationChannel(FONT_TASK_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return "请先开启字体处理进度类别。"
        return try {
            val previewId = FONT_TASK_NOTIFICATION_ID + 1
            val openApp = PendingIntent.getActivity(context, previewId,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder.recoverBuilder(context,
                build(context, FontTaskNotificationSpec("preview", "running", "进度显示预览 · 10 秒后自动消失", 50)))
                .setContentTitle("字域 · 进度预览").setContentIntent(openApp).setTimeoutAfter(10_000L)
            if (Build.VERSION.SDK_INT >= 36) runCatching { builder.setShortCriticalText("预览50%") }
            // Separate ID; never replace a real task notification or start a font job.
            manager.notify(previewId, builder.build())
            "已发送预览，实时活动是否展示以系统实际效果为准。"
        } catch (error: RuntimeException) {
            "预览未发送，请检查系统通知设置。"
        }
    }
}

internal class FontTaskForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var taskWakeLock: PowerManager.WakeLock? = null

    private fun holdTaskCpu() {
        if (taskWakeLock?.isHeld == true) return
        runCatching {
            taskWakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ziyu:FontTask")
                .apply {
                    setReferenceCounted(false)
                    // Finite fallback if state polling stops; normal completion
                    // releases immediately. Does not keep the display on.
                    acquire(16 * 60 * 1_000L)
                }
        }.onFailure { Log.w("ZiyuFontTask", "Unable to keep font task CPU awake", it) }
    }

    private fun releaseTaskCpu() {
        taskWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        taskWakeLock = null
    }
    private val model: ZiyuViewModel get() = (application as ZiyuApplication).ziyuViewModel

    override fun onCreate() {
        super.onCreate()
        FontTaskNotificationController.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = model.fontTaskNotification
            ?: FontTaskNotificationSpec("switch", "queued", "正在准备字体任务")
        val notification = FontTaskNotificationController.build(this, current)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FONT_TASK_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(FONT_TASK_NOTIFICATION_ID, notification)
            }
        } catch (error: RuntimeException) {
            FontTaskNotificationController.markServiceDetached(FONT_TASK_START_REJECTED_NOTICE)
            FontTaskNotificationController.notify(this, current)
            Log.w("ZiyuFontTask", "System rejected foreground promotion for font task", error)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (current.ongoing) holdTaskCpu()
        FontTaskNotificationController.markServiceAttached()
        observer?.cancel()
        observer = scope.launch {
            snapshotFlow { model.fontTaskNotification }
                .distinctUntilChanged()
                .collect { spec ->
                    if (spec == null) {
                        releaseTaskCpu()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                        return@collect
                    }
                    FontTaskNotificationController.notify(this@FontTaskForegroundService, spec)
                    if (!spec.ongoing) {
                        releaseTaskCpu()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        releaseTaskCpu()
        observer?.cancel()
        FontTaskNotificationController.markServiceDetached(FONT_TASK_DATA_SYNC_TIMEOUT_NOTICE)
        model.fontTaskNotification?.let { FontTaskNotificationController.notify(this, it) }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        releaseTaskCpu()
        observer?.cancel()
        scope.cancel()
        FontTaskNotificationController.markServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
