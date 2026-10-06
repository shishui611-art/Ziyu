package io.github.xgl34222220.ziyu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class InitializationUiState(
    val environment: SetupEnvironmentState = SetupEnvironmentState(),
    val rootManager: String = "",
    val moduleVersion: String = "",
    val detail: String = "",
)

internal class InitializationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(InitializationUiState())
    val state: StateFlow<InitializationUiState> = mutableState.asStateFlow()

    private var checkJob: Job? = null

    fun checkEnvironment() {
        if (checkJob?.isActive == true) return
        mutableState.value = InitializationUiState(
            environment = SetupEnvironmentState(
                root = RootProbeState.CHECKING,
                module = ModuleProbeState.CHECKING,
            ),
        )
        checkJob = viewModelScope.launch {
            val result = RootShell.exec(ROOT_AND_MODULE_PROBE, timeoutMs = 60_000L)
            val rootState = classifyRootProbe(result.code, result.stdout, result.stderr)
            val fields = parseProbeFields(result.stdout)
            val manager = fields["root_manager"].orEmpty()

            if (rootState != RootProbeState.AUTHORIZED) {
                mutableState.value = InitializationUiState(
                    environment = SetupEnvironmentState(
                        root = rootState,
                        module = ModuleProbeState.CHECKING,
                    ),
                    rootManager = manager,
                    detail = result.stderr.ifBlank { "请允许 Root 管理器弹窗，然后重新检查。" },
                )
                return@launch
            }

            val moduleState = classifyModuleProbe(
                installed = fields["module_installed"] == "true",
                pendingUpdate = fields["module_pending_update"] == "true",
                disabled = fields["module_disabled"] == "true",
                removePending = fields["module_remove_pending"] == "true",
                idMatches = fields["module_id_matches"] == "true",
                bridgeResponding = fields["bridge_responding"] == "true",
            )
            mutableState.value = InitializationUiState(
                environment = SetupEnvironmentState(root = rootState, module = moduleState),
                rootManager = manager,
                moduleVersion = fields["module_version"].orEmpty(),
                detail = result.stderr,
            )
        }
    }
}

private val ROOT_AND_MODULE_PROBE = """
    uid=${'$'}(id -u 2>/dev/null)
    printf 'uid=%s\n' "${'$'}uid"
    if [ "${'$'}uid" != 0 ]; then exit 1; fi

    module_dir=/data/adb/modules/LuoShu
    if [ -f "${'$'}module_dir/common/root_manager_detection.sh" ]; then
        . "${'$'}module_dir/common/root_manager_detection.sh"
        luoshu_detect_root_manager >/dev/null 2>&1 || true
        root_manager="${'$'}{ROOT_MANAGER:-unknown}"
    else
        # First install has no module copy of the shared detector yet. Mirror its
        # env-first ordering; the shared /data/adb/ksu directory is not enough to
        # distinguish KernelSU from a fork or stale installation.
        suki_env="${'$'}(printf '%s' "${'$'}{SUKISU:-}" | tr '[:upper:]' '[:lower:]')"
        suki_flag="${'$'}(printf '%s' "${'$'}{KSU_SUKISU:-}" | tr '[:upper:]' '[:lower:]')"
        apatch_env="${'$'}(printf '%s' "${'$'}{APATCH:-}" | tr '[:upper:]' '[:lower:]')"
        ksu_env="${'$'}(printf '%s' "${'$'}{KSU:-}" | tr '[:upper:]' '[:lower:]')"
        if [ "${'$'}suki_flag" = 1 ] || [ "${'$'}suki_flag" = true ] || [ "${'$'}suki_flag" = yes ] || [ -n "${'$'}{SUKISU_VER:-}${'$'}{SUKISU_VER_CODE:-}" ] || { [ -n "${'$'}suki_env" ] && [ "${'$'}suki_env" != 0 ] && [ "${'$'}suki_env" != false ] && [ "${'$'}suki_env" != no ] && [ "${'$'}suki_env" != off ]; }; then
            root_manager='SukiSU Ultra'
        elif [ -n "${'$'}{APATCH_VER:-}${'$'}{APATCH_VER_CODE:-}" ] || { [ -n "${'$'}apatch_env" ] && [ "${'$'}apatch_env" != 0 ] && [ "${'$'}apatch_env" != false ] && [ "${'$'}apatch_env" != no ] && [ "${'$'}apatch_env" != off ]; }; then
            root_manager=APatch
        elif [ -n "${'$'}{KSU_VER_CODE:-}${'$'}{KSU_KERNEL_VER_CODE:-}" ] || { [ -n "${'$'}ksu_env" ] && [ "${'$'}ksu_env" != 0 ] && [ "${'$'}ksu_env" != false ] && [ "${'$'}ksu_env" != no ] && [ "${'$'}ksu_env" != off ]; }; then
            root_manager=KernelSU
        elif [ -n "${'$'}{MAGISK_VER:-}${'$'}{MAGISK_VER_CODE:-}" ]; then
            root_manager=Magisk
        elif command -v apd >/dev/null 2>&1 || [ -d /data/adb/ap ] || [ -d /data/adb/apatch ]; then
            root_manager=APatch
        elif command -v ksud >/dev/null 2>&1 || [ -x /data/adb/ksu/ksud ] || [ -x /data/adb/ksu/bin/ksud ]; then
            # Shared KSU paths do not identify the exact manager.
            root_manager='其他兼容 su 环境'
        elif command -v magisk >/dev/null 2>&1 || [ -d /data/adb/magisk ]; then
            root_manager=Magisk
        else
            root_manager='其他兼容 su 环境'
        fi
    fi
    printf 'root_manager=%s\n' "${'$'}root_manager"

    update_dir=/data/adb/modules_update/LuoShu
    module_installed=false
    module_pending_update=false
    module_disabled=false
    module_remove_pending=false
    module_id_matches=false
    bridge_responding=false
    module_version=

    [ -f "${'$'}update_dir/module.prop" ] && module_pending_update=true
    if [ -f "${'$'}module_dir/module.prop" ]; then
        module_installed=true
        module_id="${'$'}(sed -n 's/^id=//p' "${'$'}module_dir/module.prop" 2>/dev/null | head -n1 | tr -d '\\r\\n')"
        [ "${'$'}module_id" = LuoShu ] && module_id_matches=true
        module_version="${'$'}(sed -n 's/^version=//p' "${'$'}module_dir/module.prop" 2>/dev/null | head -n1 | tr -cd 'A-Za-z0-9._+-')"
        [ -e "${'$'}module_dir/disable" ] && module_disabled=true
        [ -e "${'$'}module_dir/remove" ] && module_remove_pending=true
        if [ "${'$'}module_id_matches" = true ] && [ -f "${'$'}module_dir/common/app_bridge.sh" ]; then
            bridge_status="${'$'}(MODDIR="${'$'}module_dir" sh "${'$'}module_dir/common/app_bridge.sh" status 2>/dev/null)"
            case "${'$'}bridge_status" in
                *'"status":"ok"'*'"root":true'*'"installed":true'*) bridge_responding=true ;;
            esac
        fi
    elif [ -f "${'$'}update_dir/module.prop" ]; then
        module_version="${'$'}(sed -n 's/^version=//p' "${'$'}update_dir/module.prop" 2>/dev/null | head -n1 | tr -cd 'A-Za-z0-9._+-')"
    fi

    printf 'module_installed=%s\n' "${'$'}module_installed"
    printf 'module_pending_update=%s\n' "${'$'}module_pending_update"
    printf 'module_disabled=%s\n' "${'$'}module_disabled"
    printf 'module_remove_pending=%s\n' "${'$'}module_remove_pending"
    printf 'module_id_matches=%s\n' "${'$'}module_id_matches"
    printf 'module_version=%s\n' "${'$'}module_version"
    printf 'bridge_responding=%s\n' "${'$'}bridge_responding"
""".trimIndent()
