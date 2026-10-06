package io.github.xgl34222220.luoshu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitializationFlowModelTest {
    @Test
    fun parsesEachProbeFieldFromItsOwnLine() {
        val fields = parseProbeFields(
            """uid=0
                |root_manager=KernelSU
                |module_installed=true
                |module_version=2.0.0
                |bridge_responding=true
            """.trimMargin(),
        )

        assertEquals("0", fields["uid"])
        assertEquals("KernelSU", fields["root_manager"])
        assertEquals("true", fields["module_installed"])
        assertEquals("2.0.0", fields["module_version"])
        assertEquals("true", fields["bridge_responding"])
    }

    @Test
    fun setupCompletionMigratesExistingInstallationsWithoutSkippingNewInstallSetup() {
        assertFalse(shouldRequireSetup(storedSetupCompleted = null, hasLegacyPreferences = true))
        assertTrue(shouldRequireSetup(storedSetupCompleted = null, hasLegacyPreferences = false))
        assertFalse(
            shouldRequireSetup(
                storedSetupCompleted = null,
                hasLegacyPreferences = false,
                isUpgradeInstall = true,
            ),
        )
        assertFalse(shouldRequireSetup(storedSetupCompleted = true, hasLegacyPreferences = false))
        assertTrue(shouldRequireSetup(storedSetupCompleted = false, hasLegacyPreferences = true))
    }

    @Test
    fun setupCanFinishOnlyWhenRootAndModuleAreReady() {
        assertTrue(
            SetupEnvironmentState(
                root = RootProbeState.AUTHORIZED,
                module = ModuleProbeState.READY,
            ).canStart,
        )
        assertFalse(
            SetupEnvironmentState(
                root = RootProbeState.DENIED,
                module = ModuleProbeState.READY,
            ).canStart,
        )
        assertFalse(
            SetupEnvironmentState(
                root = RootProbeState.AUTHORIZED,
                module = ModuleProbeState.DISABLED,
            ).canStart,
        )
    }

    @Test
    fun rootProbeRequiresTheSuCommandToReturnUidZero() {
        assertEquals(
            RootProbeState.AUTHORIZED,
            classifyRootProbe(0, "uid=0\nroot_manager=Magisk", ""),
        )
        assertEquals(
            RootProbeState.DENIED,
            classifyRootProbe(0, "uid=2000(shell)", ""),
        )
    }

    @Test
    fun rootProbeDistinguishesMissingSuPendingAuthorizationAndDenial() {
        assertEquals(
            RootProbeState.SU_MISSING,
            classifyRootProbe(127, "", "未找到 Root 命令 su"),
        )
        assertEquals(
            RootProbeState.WAITING_FOR_AUTHORIZATION,
            classifyRootProbe(124, "", "命令执行超时"),
        )
        assertEquals(RootProbeState.DENIED, classifyRootProbe(1, "", "Permission denied"))
    }

    @Test
    fun moduleMustBeInstalledEnabledCompatibleAndResponding() {
        assertEquals(
            ModuleProbeState.READY,
            classifyModuleProbe(
                installed = true,
                pendingUpdate = false,
                disabled = false,
                removePending = false,
                idMatches = true,
                bridgeResponding = true,
            ),
        )
        assertEquals(
            ModuleProbeState.BRIDGE_UNAVAILABLE,
            classifyModuleProbe(
                installed = true,
                pendingUpdate = false,
                disabled = false,
                removePending = false,
                idMatches = true,
                bridgeResponding = false,
            ),
        )
    }

    @Test
    fun pendingUpdateDisabledOrRemovalNeverCountsAsReady() {
        assertEquals(
            ModuleProbeState.PENDING_REBOOT,
            classifyModuleProbe(installed = false, pendingUpdate = true, disabled = false, removePending = false, idMatches = true, bridgeResponding = false),
        )
        assertEquals(
            ModuleProbeState.DISABLED,
            classifyModuleProbe(installed = true, pendingUpdate = false, disabled = true, removePending = false, idMatches = true, bridgeResponding = true),
        )
        assertEquals(
            ModuleProbeState.REMOVE_PENDING,
            classifyModuleProbe(installed = true, pendingUpdate = false, disabled = false, removePending = true, idMatches = true, bridgeResponding = true),
        )
    }
}
