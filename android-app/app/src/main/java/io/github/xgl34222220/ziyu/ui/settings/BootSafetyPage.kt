package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuShapeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BOOT_SAFETY_BRIDGE = "/data/adb/modules/ziyu/common/app_bridge.sh"

private fun scanFailure(values: Map<String, String>): String =
    values["scan_message"].orEmpty().ifBlank { values["message"].orEmpty() }.ifBlank {
        val reason = values["scan_reason"].orEmpty().ifBlank { values["reason"].orEmpty() }
        when (reason) {
            "boot-not-completed" -> "系统尚未完成开机，请完整进入桌面后再扫描。"
            "scan-request-busy" -> "扫描请求正在处理，请稍后重试。"
            "rescan-lock-cleanup-unconfirmed" -> "旧扫描任务的退出或锁归属尚未确认，请导出诊断日志。"
            "task-supervisor-unavailable", "helper-missing", "font-manager-unavailable" ->
                "模块扫描组件不完整，请检查模块安装是否完成，并使用配套版本 App。"
            "default-ota-finalize-failed" -> "原厂字体已扫描，但恢复默认字体后的核验收尾失败，请导出诊断日志。"
            "rescan-launch-failed" -> "原厂字体扫描任务未能启动，请导出诊断日志。"
            else -> "原厂字体扫描未完成，请导出诊断日志。" + if (reason.isBlank()) "" else "（$reason）"
        }
    }

@Composable
internal fun BootSafetyPage() {
    val scope = rememberCoroutineScope()
    val palette = LocalZiyuPaletteTokens.current
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(emptyMap<String, String>()) }
    var errorMessage by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }
    var confirmRecovery by remember { mutableStateOf(false) }

    suspend fun read(action: String): Map<String, String> {
        val response = RootShell.exec(
            "sh ${RootShell.quote(BOOT_SAFETY_BRIDGE)} boot_safety $action",
            timeoutMs = 15_000L,
        )
        val values = linkedMapOf<String, String>()
        response.stdout.lineSequence().take(64).forEach { raw ->
            val line = raw.trimEnd('\r')
            val split = line.indexOf('=')
            if (split > 0) values.putIfAbsent(line.substring(0, split), line.substring(split + 1).take(512))
        }
        check(response.code == 0 && values["status"] != "error") {
            response.stderr.take(180).ifBlank { scanFailure(values) }
        }
        return values
    }

    fun perform(action: String) {
        if (busy) return
        scope.launch {
            busy = true
            errorMessage = ""
            resultMessage = ""
            try {
                val response = read(action)
                if (action == "recover") {
                    resultMessage = "已处理解除保护请求。请先核对 Root 管理器中的模块启用状态，再完整重启。"
                    state = read("status")
                } else if (action == "rescan") {
                    resultMessage = "正在核验原厂字体，可离开此页，任务会在后台继续。"
                    state = read("status")
                    // Poll only after an explicit manual request; status checks
                    // never start a scan and normal font switching is unchanged.
                    var polls = 0
                    while (state["scan_state"] in setOf("queued", "running") && polls < 320) {
                        delay(2_000L)
                        state = read("status")
                        polls++
                    }
                    check(state["scan_state"] == "complete" && state["scan_complete"] == "1") { scanFailure(state) }
                    resultMessage = if (state["ota_pending"] == "1") {
                        "原厂字体核验完成，可以回到字体库重新应用字体。"
                    } else "原厂字体与拓扑核验已完成，可以重新应用字体。"
                } else state = response
                check(state["safe_mode"] in setOf("0", "1")) { "保护状态格式无效，请确认模块与 App 版本一致。" }
                if (state["scan_state"] == "error") errorMessage = scanFailure(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                resultMessage = ""
                errorMessage = failure.message ?: "无法读取开机保护状态"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { perform("status") }
    val safe = state["safe_mode"] == "1"
    val otaPending = state["ota_pending"] == "1"
    val nextReady = state["next_generation_ready"] == "1"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, maxOf(LocalDockContentPadding.current, 24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(shape = ZiyuShapeTokens.Card, color = palette.cardBackground, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when { busy && state.isEmpty() -> "正在读取…"; state.isEmpty() -> "尚未读取保护状态"; safe -> "开机保护已启用"; otaPending && nextReady -> "新字体负载已准备，待完整重启"; otaPending -> "系统字体基准待核验"; else -> "当前未进入开机保护" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (safe) "字域已暂停字体加载，字体文件和选择仍保留。建议先恢复默认字体，再解除保护。"
                        else if (otaPending && nextReady) "已按新系统原厂字体生成并校验下一启动负载。请完整重启，重启后的实际挂载验证通过后再解除系统更新保护。"
                        else if (otaPending) "旧字体负载暂缓加载，字体文件和选择仍保留。完成原厂字体重新扫描后，请在字体库重新应用字体，或恢复默认字体。"
                        else "连续两次未完成开机后，字域会在下一次启动挂载前进入保护。系统已完成开机后的临时 Root 加载与软重启不计入失败。",
                        color = palette.textSecondary,
                    )
                    state["unfinished_boots"]?.toIntOrNull()?.let { Text("未完成开机记录：$it 次", color = palette.textSecondary) }
                    if (otaPending || state["stock_scan_pending"] == "1" || state["scan_state"] in setOf("queued", "running")) Text(
                        if (nextReady) "下一启动负载已准备；完整重启后自动核对。"
                        else if (state["scan_state"] in setOf("queued", "running")) "原厂字体正在后台扫描核验。"
                        else if (state["scan_complete"] == "1") "原厂字体扫描已完成，可以在字体库重新应用字体。"
                        else "原厂字体重新扫描尚未完成；可请求重新扫描，稍后再检测。",
                        color = palette.textSecondary,
                    )
                    if (errorMessage.isNotBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    if (resultMessage.isNotBlank()) Text(resultMessage, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { perform("status") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新检测") }
                    if (!nextReady) OutlinedButton(onClick = { perform("rescan") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新扫描原厂字体") }
                    Button(onClick = { confirmRecovery = true }, enabled = !busy && safe, modifier = Modifier.fillMaxWidth()) { Text("解除开机保护") }
                }
            }
        }
    }
    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text("解除开机保护") },
        text = { Text("若此前发生卡开机，建议先在字体库恢复默认字体。解除保护后请完整重启；系统更新后的字体仍需完成原厂重扫并重新应用。是否继续？") },
        confirmButton = { TextButton(onClick = { confirmRecovery = false; perform("recover") }) { Text("解除保护") } },
        dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("取消") } },
    )
}
