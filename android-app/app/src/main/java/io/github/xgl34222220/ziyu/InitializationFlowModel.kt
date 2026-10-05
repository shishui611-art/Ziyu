package io.github.xgl34222220.ziyu

internal enum class RootProbeState {
    CHECKING,
    SU_MISSING,
    WAITING_FOR_AUTHORIZATION,
    AUTHORIZED,
    DENIED,
    ERROR,
}

internal enum class ModuleProbeState {
    CHECKING,
    NOT_INSTALLED,
    PENDING_REBOOT,
    DISABLED,
    REMOVE_PENDING,
    INVALID_MODULE,
    BRIDGE_UNAVAILABLE,
    READY,
}

internal data class SetupEnvironmentState(
    val root: RootProbeState = RootProbeState.CHECKING,
    val module: ModuleProbeState = ModuleProbeState.CHECKING,
) {
    val canStart: Boolean
        get() = root == RootProbeState.AUTHORIZED && module == ModuleProbeState.READY
}

internal fun parseProbeFields(stdout: String): Map<String, String> = stdout.lineSequence()
    .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }
    .toMap()

internal fun classifyRootProbe(exitCode: Int, stdout: String, stderr: String): RootProbeState {
    if (exitCode == 0) {
        val uid = parseProbeFields(stdout)["uid"]
        return if (uid == "0") RootProbeState.AUTHORIZED else RootProbeState.DENIED
    }

    val message = stderr.lowercase()
    if (exitCode == 124 || message.contains("超时") || message.contains("timed out")) {
        return RootProbeState.WAITING_FOR_AUTHORIZATION
    }
    if (
        message.contains("未找到 root 命令 su") ||
        message.contains("cannot run program \"su\"") ||
        message.contains("no such file or directory") && exitCode == 127
    ) {
        return RootProbeState.SU_MISSING
    }
    if (
        exitCode == 1 ||
        message.contains("permission denied") ||
        message.contains("access denied") ||
        message.contains("root denied")
    ) {
        return RootProbeState.DENIED
    }
    return RootProbeState.ERROR
}

internal fun classifyModuleProbe(
    installed: Boolean,
    pendingUpdate: Boolean,
    disabled: Boolean,
    removePending: Boolean,
    idMatches: Boolean,
    bridgeResponding: Boolean,
): ModuleProbeState = when {
    pendingUpdate -> ModuleProbeState.PENDING_REBOOT
    !installed -> ModuleProbeState.NOT_INSTALLED
    removePending -> ModuleProbeState.REMOVE_PENDING
    disabled -> ModuleProbeState.DISABLED
    !idMatches -> ModuleProbeState.INVALID_MODULE
    !bridgeResponding -> ModuleProbeState.BRIDGE_UNAVAILABLE
    else -> ModuleProbeState.READY
}

internal fun shouldRequireSetup(
    storedSetupCompleted: Boolean?,
    hasLegacyPreferences: Boolean,
    isUpgradeInstall: Boolean = false,
): Boolean = storedSetupCompleted?.not() ?: !(hasLegacyPreferences || isUpgradeInstall)
