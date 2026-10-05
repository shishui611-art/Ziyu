package io.github.xgl34222220.ziyu.ui.logs

import org.junit.Assert.*
import org.junit.Test

class FlashProgressModelTest {
    @Test fun jsonMessageIsReadableWithoutLosingFailureState() {
        val raw = """{"status":"error","message":"字体处理失败","pipeline":"next-boot-stage"}"""
        assertEquals("字体处理失败", taskDisplayMessage(raw))
        assertEquals(TaskPhase.FAILED, taskPhaseFor("", raw))
    }
    @Test fun stage100DoesNotTurnFailureIntoSuccess() {
        assertEquals("HyperOS 字体处理失败", taskDisplayMessage("stage=100 message=HyperOS 字体处理失败"))
        assertEquals(TaskPhase.FAILED, taskPhaseFor("", "stage=100 message=字体处理失败"))
    }
    @Test fun terminalSnapshotWinsOverStaleStage() {
        assertEquals(TaskPhase.SUCCESS, taskPhaseFor("", "正在生成字体", "success"))
        assertEquals(TaskPhase.FAILED, taskPhaseFor("", "字体已完成", "failed"))
        assertEquals(TaskPhase.RUNNING, taskPhaseFor("", "检查上次错误", "running"))
    }
    @Test fun preparedStillNeedsReboot() {
        assertEquals(TaskPhase.WAITING_REBOOT, taskPhaseFor("", "文件准备完成", "prepared"))
        assertEquals(TaskPhase.WAITING_REBOOT, taskPhaseFor("", "重启后生效", "success"))
    }
    @Test fun oldTelemetryDoesNotClaimLiveProcesses() {
        assertTrue(parseTaskLogItems("[now] [SAFE-SWITCH] stage=76 message=正在切换字体").none { it.active })
    }
    @Test fun failurePrioritizedOverOldSuccess() {
        val old = TaskCenterItem("old",TaskKind.IMPORT,TaskPhase.SUCCESS,"","导入完成")
        val failed=old.copy(id="current",kind=TaskKind.APPLY,phase=TaskPhase.FAILED,current=true)
        assertEquals(failed,primaryFlashTask(listOf(old,failed)))
    }
    @Test fun unknownTextAndMalformedJsonRemainAvailable() {
        assertEquals("{broken",taskDisplayMessage("{broken"))
        assertEquals("进程退出检查",taskDisplayMessage("进程退出检查"))
    }
    @Test fun duplicateStructuredMessagesAreCollapsed() {
        val current = TaskCenterItem("current",TaskKind.APPLY,TaskPhase.FAILED,"","字体处理失败",current=true)
        val old=current.copy(id="old",current=false,message="""{"status":"error","message":"字体处理失败"}""")
        assertEquals(1,mergeTaskItems(listOf(current),listOf(old)).size)
    }
}
