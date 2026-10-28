package io.github.xgl34222220.ziyu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontTaskForegroundServiceSpecTest {
    @Test
    fun promotedOngoingApiIsGatedAtAndroid16MinorReleaseOne() {
        assertFalse(supportsPromotedOngoingApi(Build.VERSION_CODES_FULL.BAKLAVA))
        assertTrue(supportsPromotedOngoingApi(Build.VERSION_CODES_FULL.BAKLAVA_1))
    }

    @Test
    fun detachedOngoingTaskKeepsItsProgressAndExplainsThatPollingContinues() {
        val running = FontTaskNotificationSpec("mix", "running", "正在生成组合字体", 42)

        val notice = fontTaskDetachedNotice(running, "系统已结束字体后台进度服务")

        assertTrue(notice.ongoing)
        assertEquals(running.progress, notice.progress)
        assertTrue(notice.message.contains("系统已结束字体后台进度服务"))
        assertTrue(notice.message.contains(running.message))
    }

    @Test
    fun terminalTaskNotificationDoesNotRemainOngoingAfterServiceDetaches() {
        val success = FontTaskNotificationSpec("switch", "success", "字体应用完成", 100)

        val notice = fontTaskDetachedNotice(success, "系统已结束字体后台进度服务")

        assertFalse(notice.ongoing)
        assertEquals(success.message, notice.message)
    }
}
