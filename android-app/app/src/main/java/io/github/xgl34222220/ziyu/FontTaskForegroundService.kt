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
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FONT_TASK_CHANNEL = "font_tasks"
private const val FONT_TASK_NOTIFICATION_ID = 14332

internal data class FontTaskNotificationSpec(
    val kind: String,
    val state: String,
    val message: String,
    val progress: Int? = null,
) {
    val ongoing: Boolean get() = state == "queued" || state == "running"
    val title: String get() = when (kind) {
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
    fun start(context: Context) {
        ensureChannel(context)
        ContextCompat.startForegroundService(context, Intent(context, FontTaskForegroundService::class.java))
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(FONT_TASK_CHANNEL, "字体处理进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示字体生成与应用的进度"
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    fun build(context: Context, spec: FontTaskNotificationSpec): Notification {
        val openTasks = PendingIntent.getActivity(
            context,
            FONT_TASK_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_TASK_CENTER, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, FONT_TASK_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
                        else setProgress(spec.progress.coerceIn(0, 100))
                    },
                )
                builder.addExtras(Bundle().apply {
                    putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
                })
            } else {
                builder.setProgress(100, spec.progress?.coerceIn(0, 100) ?: 0, spec.progress == null)
            }
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(spec.message))
        }
        return builder.build()
    }

    fun notify(context: Context, spec: FontTaskNotificationSpec) {
        context.getSystemService(NotificationManager::class.java)
            .notify(FONT_TASK_NOTIFICATION_ID, build(context, spec))
    }
}

internal class FontTaskForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val model: ZiyuViewModel get() = (application as ZiyuApplication).ziyuViewModel

    override fun onCreate() {
        super.onCreate()
        FontTaskNotificationController.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = model.fontTaskNotification
            ?: FontTaskNotificationSpec("switch", "queued", "正在准备字体任务")
        val notification = FontTaskNotificationController.build(this, current)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FONT_TASK_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FONT_TASK_NOTIFICATION_ID, notification)
        }
        observer?.cancel()
        observer = scope.launch {
            snapshotFlow { model.fontTaskNotification }
                .distinctUntilChanged()
                .collect { spec ->
                    if (spec == null) return@collect
                    FontTaskNotificationController.notify(this@FontTaskForegroundService, spec)
                    if (!spec.ongoing) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
