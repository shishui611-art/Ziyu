package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog as AlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
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
                    resultMessage = "保护已解除，请启用模块并完整重启。"
                    state = read("status")
                } else if (action == "rescan") {
                    resultMessage = "正在扫描原厂字体，可稍后查看。"
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
                    } else "扫描完成，可以重新应用字体。"
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
    pageList {
        item {
            SettingCard("开机保护") {
                Text(
                    when { busy && state.isEmpty() -> "正在读取…"; state.isEmpty() -> "尚未读取保护状态"; safe -> "开机保护已启用"; otaPending && nextReady -> "字体已准备，请完整重启"; otaPending -> "需要扫描原厂字体"; else -> "字体加载正常" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (safe) "字体加载已暂停。建议先恢复默认字体，再解除保护。"
                    else if (otaPending && nextReady) "新系统字体已准备，请完整重启。"
                    else if (otaPending) "请扫描原厂字体，再重新应用字体。"
                    else "连续两次开机未完成时，自动暂停字体加载。",
                    color = palette.textSecondary,
                )
                state["unfinished_boots"]?.toIntOrNull()?.let { Text("未完成开机记录：$it 次", color = palette.textSecondary) }
                if (otaPending || state["stock_scan_pending"] == "1" || state["scan_state"] in setOf("queued", "running")) Text(
                    if (nextReady) "重启后自动检查。"
                    else if (state["scan_state"] in setOf("queued", "running")) "原厂字体正在后台扫描核验。"
                    else if (state["scan_complete"] == "1") "扫描完成，请重新应用字体。"
                    else "原厂字体尚未扫描。",
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
    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text("解除开机保护") },
        text = { Text("请先确认已恢复默认字体，或已完成原厂字体扫描。解除后需完整重启。") },
        confirmButton = { TextButton(onClick = { confirmRecovery = false; perform("recover") }) { Text("解除保护") } },
        dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("取消") } },
    )
}
