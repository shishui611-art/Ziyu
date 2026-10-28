package io.github.xgl34222220.ziyu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImportNotificationSpecTest {
    @Test
    fun terminalHistoryDoesNotNotifyAgainAfterUpgradeOrRestart() {
        for (phase in listOf(NativeImportPhase.SUCCESS, NativeImportPhase.FAILED, NativeImportPhase.CANCELLED)) {
            val restored = NativeImportState(phase = phase, restoredFromDisk = true)
            assertFalse(shouldPublishNativeImportNotification(restored))
            assertTrue(shouldPublishNativeImportNotification(restored.copy(restoredFromDisk = false)))
        }
    }

    @Test
    fun restoredUnfinishedQueuesKeepTheirProgressAndResumeNotifications() {
        for (phase in listOf(NativeImportPhase.QUEUED, NativeImportPhase.RUNNING, NativeImportPhase.PAUSED)) {
            assertTrue(shouldPublishNativeImportNotification(NativeImportState(phase = phase, restoredFromDisk = true)))
        }
    }

    @Test
    fun runningNotificationOffersPauseAndCancel() {
        val spec = nativeImportNotificationSpec(
            NativeImportState(
                phase = NativeImportPhase.RUNNING,
                total = 4,
                processed = 1,
                message = "正在导入 2/4：font.ttf",
            ),
        )

        assertTrue(spec.ongoing)
        assertEquals(4, spec.total)
        assertEquals(1, spec.processed)
        assertEquals(
            listOf(NativeImportNotificationAction.PAUSE, NativeImportNotificationAction.CANCEL),
            spec.actions,
        )
    }

    @Test
    fun pausedNotificationOffersResumeAndCancel() {
        val spec = nativeImportNotificationSpec(
            NativeImportState(
                phase = NativeImportPhase.PAUSED,
                total = 3,
                processed = 1,
                message = "导入已暂停",
            ),
        )

        assertFalse(spec.ongoing)
        assertEquals(
            listOf(NativeImportNotificationAction.RESUME, NativeImportNotificationAction.CANCEL),
            spec.actions,
        )
    }

    @Test
    fun failedNotificationOffersRetryAndClear() {
        val spec = nativeImportNotificationSpec(
            NativeImportState(
                phase = NativeImportPhase.FAILED,
                total = 2,
                processed = 2,
                failed = listOf("broken.ttf：字体文件不可用"),
                message = "字体导入失败",
            ),
        )

        assertEquals(
            listOf(NativeImportNotificationAction.RETRY, NativeImportNotificationAction.CLEAR),
            spec.actions,
        )
    }

    @Test
    fun successfulNotificationOnlyOffersClear() {
        val spec = nativeImportNotificationSpec(
            NativeImportState(
                phase = NativeImportPhase.SUCCESS,
                total = 1,
                processed = 1,
                imported = 1,
                message = "字体导入完成",
            ),
        )

        assertEquals(listOf(NativeImportNotificationAction.CLEAR), spec.actions)
    }

    @Test
    fun timeoutNoticeRetainsTheRunningQueueAndReportsItsSavedProgress() {
        val running = NativeImportState(
            taskId = "import-1",
            phase = NativeImportPhase.RUNNING,
            total = 5,
            processed = 2,
            message = "正在导入",
        )

        val notice = nativeImportTimeoutNotice(running)

        assertEquals(running.taskId, notice.taskId)
        assertEquals(running.phase, notice.phase)
        assertEquals(running.processed, notice.processed)
        assertTrue(notice.message.contains("2/5"))
        assertTrue(notice.message.contains("当前文件完成后暂停"))
    }

    @Test
    fun deniedBackgroundStartExplainsThatAResumeMustHappenInTheApp() {
        val paused = NativeImportState(
            phase = NativeImportPhase.PAUSED,
            total = 3,
            processed = 1,
        )

        val notice = nativeImportDetachedNotice(
            paused,
            NativeImportDetachReason.FOREGROUND_START_REJECTED,
        )

        assertEquals(paused.phase, notice.phase)
        assertTrue(notice.message.contains("未能启动后台导入服务"))
        assertTrue(notice.message.contains("打开字域后可继续导入"))
    }
}
