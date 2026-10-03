package io.github.xgl34222220.luoshu.ui.home

import io.github.xgl34222220.luoshu.ModuleSnapshot
import io.github.xgl34222220.luoshu.SystemWeightState
import io.github.xgl34222220.luoshu.snapSystemWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContractTest {
    @Test
    fun globalWeightSnapsToTenAndClampsToTheSupportedRange() {
        assertEquals(300, snapSystemWeight(100))
        assertEquals(550, snapSystemWeight(554))
        assertEquals(560, snapSystemWeight(555))
        assertEquals(700, snapSystemWeight(900))
    }

    @Test
    fun globalWeightControlsAreMappedIntoTheHomeState() {
        val state = ModuleSnapshot(installed = true, rootGranted = true).toHomeUiState(
            SystemWeightState(
                loading = false,
                supported = true,
                weight = 560,
                min = 300,
                max = 700,
                step = 10,
                message = "系统粗细已更新",
            ),
        )

        assertTrue(state.systemWeight.supported)
        assertEquals(560, state.systemWeight.weight)
        assertEquals(10, state.systemWeight.step)
        assertEquals("系统粗细已更新", state.systemWeight.message)
    }

    @Test
    fun failedMountShowsSystemFontInsteadOfConfiguredFontAsEffective() {
        val state = ModuleSnapshot(
            loading = false,
            installed = true,
            rootGranted = true,
            activeFont = "DemoFont",
            effectiveFont = "default",
            fontEffectState = "failed",
            verificationReason = "self-mount-not-visible",
            mountState = "failed",
            taskState = "success",
            taskMessage = "字体已准备",
        ).toHomeUiState()

        assertEquals("系统默认字体（DemoFont未生效）", state.currentFont)
        assertEquals("字体未生效", state.taskTitle)
        assertTrue(state.taskMessage.contains("默认字体"))
        assertFalse(state.mountHealthy)
    }

    @Test
    fun verifiedMountShowsConfiguredFontAsEffective() {
        val state = ModuleSnapshot(
            loading = false,
            installed = true,
            rootGranted = true,
            activeFont = "DemoFont",
            effectiveFont = "DemoFont",
            fontEffectState = "verified",
            mountState = "mounted",
        ).toHomeUiState()

        assertEquals("DemoFont", state.currentFont)
        assertEquals("字体引擎已就绪", state.taskTitle)
        assertTrue(state.mountHealthy)
    }

    @Test
    fun pendingRebootDoesNotPretendTheFontIsAlreadyEffective() {
        val state = ModuleSnapshot(
            loading = false,
            activeFont = "DemoFont",
            effectiveFont = "unknown",
            fontEffectState = "pending-reboot",
            rebootRequired = true,
        ).toHomeUiState()

        assertEquals("DemoFont（等待完整重启）", state.currentFont)
    }

    @Test
    fun rollbackPendingShowsRecoveryTargetWithoutPretendingRollbackAlreadyHappened() {
        val state = ModuleSnapshot(
            loading = false,
            installed = true,
            rootGranted = true,
            activeFont = "BadUniversal",
            effectiveFont = "unknown",
            fontEffectState = "rollback-pending",
            verificationGrade = "FAIL",
            verificationReason = "coverage-digits-missing",
            mountState = "mounted",
            rollbackState = "staged",
            rollbackPending = true,
            rollbackTargetFont = "OldFont",
            rollbackTargetMode = "legacy",
            rebootRequired = true,
        ).toHomeUiState()

        assertEquals("BadUniversal（验证失败，待重启恢复 OldFont）", state.currentFont)
        assertEquals("正在等待安全回退", state.taskTitle)
        assertTrue(state.taskMessage.contains("OldFont"))
        assertTrue(state.taskMessage.contains("完整重启"))
        assertFalse(state.mountHealthy)
        assertTrue(state.rebootRequired)
    }

    @Test
    fun unresolvedUniversalFailureDoesNotPretendSystemDefaultIsAlreadyActive() {
        val state = ModuleSnapshot(
            loading = false,
            installed = true,
            rootGranted = true,
            activeFont = "BadUniversal",
            effectiveFont = "unknown",
            fontEffectState = "failed",
            verificationGrade = "FAIL",
            verificationMode = "universal-fail",
            verificationReason = "required-axis-missing",
            mountState = "mounted",
        ).toHomeUiState()

        assertEquals("BadUniversal（运行验证失败）", state.currentFont)
        assertEquals("字体未生效", state.taskTitle)
        assertTrue(state.taskMessage.contains("无法安全确认"))
        assertFalse(state.mountHealthy)
    }

    @Test
    fun dynamicConfigFailureProvidesAnActionableReason() {
        val state = ModuleSnapshot(
            loading = false,
            activeFont = "DemoFont",
            effectiveFont = "default",
            fontEffectState = "failed",
            verificationReason = "dynamic-config-overridden",
            mountState = "mounted",
        ).toHomeUiState()

        assertTrue(state.taskMessage.contains("动态字体配置"))
        assertTrue(state.taskMessage.contains("系统字体"))
    }
}
