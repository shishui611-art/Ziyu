package io.github.xgl34222220.ziyu.ui.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import io.github.xgl34222220.ziyu.BuildConfig
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuAlertDialog
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuText
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Compare actual installed module metadata; Debug suffixes are not a mismatch. */
@Composable
internal fun ModuleVersionNotice(state: HomeUiState) {
    if (!state.moduleInstalled || !state.rootGranted || state.versionCode <= 0 ||
        state.versionCode == BuildConfig.VERSION_CODE) return
    val context = LocalContext.current
    val pair = "${BuildConfig.VERSION_CODE}:${state.versionCode}"
    // Dismissing lasts for this page instance; a new module/App pair reminds again.
    var shown by rememberSaveable(pair) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = "正在导出…"
            var staged: File? = null
            try {
                val prepared = stageModuleApp(context, state.versionCode)
                staged = prepared
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        prepared.inputStream().use { input ->
                            check(input.copyTo(output) == prepared.length()) { "安装包未完整写入，请重新导出" }
                        }
                    } ?: error("无法写入所选位置")
                }
                message = "已导出，请打开安装包安装。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "导出失败，请重试"
            } finally {
                staged?.delete()
                busy = false
            }
        }
    }
    if (shown) ZiyuAlertDialog(
        onDismissRequest = { if (!busy) shown = false },
        title = { ZiyuText("App 与模块版本不一致") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZiyuText("App ${BuildConfig.VERSION_NAME}\n模块 ${state.version}")
                ZiyuText("若刚更新模块，请先重启；否则检查是否有两个字域，或导出配套 App 重新安装。")
                if (state.versionCode < BuildConfig.VERSION_CODE) ZiyuText("模块版本较旧，建议先更新模块。")
                ZiyuTextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
                        .onFailure { message = "无法打开应用管理，请到手机设置中检查" }
                }, enabled = !busy) { ZiyuText("检查已安装的 App") }
                if (message.isNotBlank()) ZiyuText(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            ZiyuButton(onClick = {
                val version = state.version.replace(Regex("[^A-Za-z0-9.-]"), "")
                save.launch("Ziyu-$version.apk")
            }, enabled = !busy) { ZiyuText(if (busy) "正在导出…" else "导出配套 App") }
        },
        dismissButton = {
            ZiyuTextButton(onClick = { shown = false }, enabled = !busy) { ZiyuText("稍后处理") }
        },
    )
}

/** Export works with older modules too: it needs no newly introduced bridge verb. */
private suspend fun stageModuleApp(context: Context, moduleCode: Int): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "module-app-export").apply { check(mkdirs() || isDirectory) }
    val output = File.createTempFile("bundle-", ".apk", directory)
    try {
        val command = """
            set -eu
            mod=/data/adb/modules/ziyu
            apk="${'$'}mod/bundled/Ziyu-App.apk"
            meta="${'$'}mod/bundled/app.prop"
            out=${RootShell.quote(output.absolutePath)}
            [ -f "${'$'}apk" ] && [ ! -L "${'$'}apk" ] && [ -f "${'$'}meta" ] && [ ! -L "${'$'}meta" ] || { echo '模块未包含配套 App，请重新下载完整模块包' >&2; exit 1; }
            [ -f "${'$'}out" ] && [ ! -L "${'$'}out" ] || exit 1
            code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}mod/module.prop" | tr -d '\r')
            bundled=${'$'}(sed -n 's/^versionCode=//p' "${'$'}meta" | tr -d '\r')
            [ "${'$'}code" = "$moduleCode" ] && [ "${'$'}bundled" = "$moduleCode" ] || { echo '模块版本已变化或内置 App 不匹配，请刷新后重试' >&2; exit 1; }
            digest=${'$'}(sed -n 's/^sha256=//p' "${'$'}meta" | tr -d '\r')
            [ "${'$'}{#digest}" = 64 ] || { echo '模块内安装包信息不完整，请重新下载模块' >&2; exit 1; }
            case "${'$'}digest" in *[!0-9a-f]*) echo '模块内安装包摘要无效，请重新下载模块' >&2; exit 1 ;; esac
            before=${'$'}(stat -c '%d:%i:%s:%Y:%Z' "${'$'}apk")
            cp "${'$'}apk" "${'$'}out"
            [ "${'$'}before" = "${'$'}(stat -c '%d:%i:%s:%Y:%Z' "${'$'}apk")" ] || exit 1
            actual=${'$'}(sha256sum "${'$'}out"); actual=${'$'}{actual%% *}
            [ "${'$'}digest" = "${'$'}actual" ] || { echo '模块内安装包校验失败，请重新下载模块' >&2; exit 1; }
            chown ${context.applicationInfo.uid}:${context.applicationInfo.uid} "${'$'}out"
            chmod 600 "${'$'}out"
            printf '%s\n' "${'$'}digest"
        """.trimIndent()
        val result = RootShell.exec(command, timeoutMs = 45_000L)
        check(result.code == 0) { result.stderr.trim().ifBlank { "无法读取模块内的 App，请检查 Root 授权" } }
        val expected = result.stdout.lineSequence().lastOrNull { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("安装包校验结果缺失")
        check(output.length() in 1..(128L * 1024 * 1024)) { "模块内安装包大小无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        output.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) { "安装包复制校验失败" }
        val info = context.packageManager.getPackageArchiveInfo(output.absolutePath, 0)
            ?: error("模块内安装包无法读取")
        check(info.packageName in setOf("io.github.shishui611_art.ziyu", "io.github.shishui611_art.ziyu.debug") &&
            PackageInfoCompat.getLongVersionCode(info) == moduleCode.toLong()) { "安装包与模块版本不匹配" }
        output
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}
