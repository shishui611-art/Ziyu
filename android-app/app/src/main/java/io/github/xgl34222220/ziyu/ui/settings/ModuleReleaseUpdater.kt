package io.github.xgl34222220.ziyu.ui.settings

import android.content.Context
import io.github.xgl34222220.ziyu.BuildConfig
import io.github.xgl34222220.ziyu.RootShell
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ModuleUpdateState(
    val busy: Boolean = false,
    val message: String = "",
    val error: String = "",
    val restartRequired: Boolean = false,
)

private data class InstalledModule(val version: String, val code: Int)

/** Checks and installs official module ZIPs. An APK version is never an update baseline. */
internal class ModuleReleaseUpdater(private val context: Context) {
    private val repository = "https://github.com/shishui611-art/Ziyu"

    private fun properties(raw: String): Map<String, String> = raw.lineSequence()
        .filter { '=' in it && !it.trimStart().startsWith('#') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    private fun identity(raw: String): InstalledModule {
        val prop = properties(raw)
        require(prop["id"] == "LuoShu") { "模块 ID 不匹配" }
        val version = prop["version"].orEmpty()
        val code = prop["versionCode"]?.toIntOrNull() ?: 0
        require(version.matches(Regex("v?\\d+\\.\\d+\\.\\d+(?:[-+].*)?")) && code > 0) { "模块版本信息无效" }
        return InstalledModule(version, code)
    }

    private suspend fun installed(pending: Boolean = false): InstalledModule? {
        val path = if (pending) "/data/adb/modules_update/LuoShu/module.prop" else "/data/adb/modules/LuoShu/module.prop"
        val result = RootShell.exec("if [ -f ${RootShell.quote(path)} ]; then cat ${RootShell.quote(path)}; else exit 3; fi", 12_000L)
        if (pending && result.code == 3) return null
        require(result.code == 0) { result.stderr.ifBlank { "无法读取已安装模块，请确认 Root 授权和模块安装状态" } }
        return identity(result.stdout)
    }

    suspend fun check(): OnlineUpdateInfo = withContext(Dispatchers.IO) {
        val current = requireNotNull(installed())
        val pending = installed(pending = true)
        val release = JSONObject(text("https://api.github.com/repos/shishui611-art/Ziyu/releases/latest", "application/vnd.github+json"))
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "没有可用的正式 Release" }
        val tag = release.getString("tag_name")
        val version = Regex("^ziyu-(v\\d+\\.\\d+\\.\\d+)$").matchEntire(tag)?.groupValues?.get(1)
            ?: error("正式 Release 的版本标签不受支持")
        val filename = "Ziyu-$version.zip"
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject = (0 until assets.length()).map { assets.getJSONObject(it) }
            .singleOrNull { it.optString("name") == name } ?: error("正式 Release 缺少 $name")
        val zipAsset = asset(filename)
        val zipUrl = zipAsset.getString("browser_download_url")
        val expected = "$repository/releases/download/$tag/$filename"
        require(zipUrl == expected && zipAsset.getLong("size") in 1..MAX_ZIP_BYTES) { "正式模块下载地址或大小无效" }
        val shaUrl = asset("$filename.sha256").getString("browser_download_url")
        require(shaUrl == "$expected.sha256") { "模块校验文件地址无效" }
        val sha = text(shaUrl).trim().split(Regex("\\s+"), limit = 2).first().lowercase()
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "正式模块 SHA-256 无效" }
        val remote = identity(text("https://raw.githubusercontent.com/shishui611-art/Ziyu/$tag/module.prop"))
        require(remote.version.removePrefix("v") == version.removePrefix("v")) { "Release 与模块版本不一致" }
        OnlineUpdateInfo(
            version = version, versionCode = remote.code, zipUrl = zipUrl,
            changelogUrl = "$repository/releases/tag/$tag", sha256 = sha,
            currentVersion = current.version, currentVersionCode = current.code,
            pendingVersion = pending?.version.orEmpty(), pendingVersionCode = pending?.code ?: 0,
            zipBytes = zipAsset.getLong("size"),
        )
    }

    suspend fun install(info: OnlineUpdateInfo, progress: suspend (String) -> Unit) {
        require(info.hasUpdate) { "没有需要安装的正式模块更新" }
        require(info.zipUrl.matches(Regex("https://github\\.com/shishui611-art/Ziyu/releases/download/ziyu-v\\d+\\.\\d+\\.\\d+/Ziyu-v\\d+\\.\\d+\\.\\d+\\.zip"))) { "模块下载地址无效" }
        val current = requireNotNull(installed())
        require(isNewerZiyuVersion(info.version, info.versionCode, current.version, current.code)) { "模块版本已变化，请重新检查更新" }
        val pending = installed(pending = true)
        require(pending == null) { "已有模块更新等待重启，请先完整重启再检查更新" }
        val directory = File(context.cacheDir, "module-updates").apply { mkdirs() }
        val token = UUID.randomUUID().toString()
        val archive = File(directory, "$token.zip")
        try {
            withContext(Dispatchers.IO) {
                val connection = connection(info.zipUrl).apply { readTimeout = 30_000 }
                try {
                    require(connection.responseCode in 200..299) { "模块下载失败：HTTP ${connection.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastPercent = -1
                    val started = System.nanoTime()
                    connection.inputStream.use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_ZIP_BYTES && total <= info.zipBytes) { "模块下载大小超出正式 Release 记录" }
                            require(System.nanoTime() - started < 600_000_000_000L) { "模块下载超时，请重试" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val percent = (total * 100 / info.zipBytes).toInt()
                            if (percent != lastPercent) { progress("正在下载模块… $percent%"); lastPercent = percent }
                        }
                    } }
                    require(total == info.zipBytes) { "模块下载不完整" }
                    val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(sha == info.sha256) { "模块 SHA-256 不匹配，已停止安装" }
                    ZipFile(archive).use { zip ->
                        val entries = zip.entries().asSequence().filter { it.name == "module.prop" }.toList()
                        require(entries.size == 1 && entries.single().size in 1..16_384) { "下载文件不是有效的字域模块 ZIP" }
                        val prop = zip.getInputStream(entries.single()).use { String(it.readBytesLimited(16_384), Charsets.UTF_8) }
                        val module = identity(prop)
                        require(module.version.removePrefix("v") == info.version.removePrefix("v") && module.code == info.versionCode) { "ZIP 模块版本与正式 Release 不一致" }
                        require(zip.getEntry("customize.sh") != null && zip.getEntry("bundled/Ziyu-App.apk") != null) { "正式模块缺少安装脚本或内置 App" }
                    }
                } finally { connection.disconnect() }
            }
            progress("校验通过，正在安装模块，请等待完成…")
            // Once the manager starts its transaction, do not cancel it on a UI scope cancellation.
            withContext(NonCancellable) {
                val rootZip = "/data/local/tmp/ziyu-module-update-$token.zip"
                try {
                    val result = RootShell.exec(installCommand(archive, rootZip, info.sha256), timeoutMs = 600_000L)
                    withContext(Dispatchers.IO) {
                        File(context.filesDir, "module-update").apply { mkdirs() }
                            .resolve("latest.log").writeText("exit=${result.code}\n${result.stdout}\n${result.stderr}")
                    }
                    require(result.code == 0) { result.stderr.ifBlank { result.stdout }.takeLast(1800).ifBlank { "Root 管理器安装模块失败（${result.code}）" } }
                    val staged = installed(pending = true) ?: installed()
                    require(staged?.version?.removePrefix("v") == info.version.removePrefix("v") && staged?.code == info.versionCode) { "管理器未确认新模块已写入，请查看模块管理器与安装日志" }
                } finally { RootShell.exec("rm -f ${RootShell.quote(rootZip)}", 8_000L) }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { archive.delete() } }
    }

    private fun installCommand(archive: File, rootZip: String, sha: String): String = """
        set -e
        MODDIR=/data/adb/modules/LuoShu
        [ ! -e "${'$'}MODDIR/remove" ] && [ ! -e "${'$'}MODDIR/disable" ] || { echo '请先启用模块并完整重启' >&2; exit 1; }
        [ ! -f /data/adb/modules_update/LuoShu/module.prop ] || { echo '已有模块等待重启' >&2; exit 1; }
        for state in "${'$'}MODDIR/config/switch_task.conf" "${'$'}MODDIR/config/axes_task.conf"; do
            if grep -Eq '^state=(queued|running)${'$'}' "${'$'}state" 2>/dev/null; then echo '请等待字体任务结束后更新模块' >&2; exit 1; fi
        done
        if [ -f "${'$'}MODDIR/common/root_manager_detection.sh" ]; then
            . "${'$'}MODDIR/common/root_manager_detection.sh"
            luoshu_detect_root_manager >/dev/null
        else
            root_su_version=${'$'}(su -v 2>/dev/null)
            case "${'$'}root_su_version" in
                *SukiSU*|*sukisu*) ROOT_MANAGER='SukiSU Ultra' ;;
                *KernelSU*|*kernelsu*) ROOT_MANAGER=KernelSU ;;
                *Magisk*|*MAGISK*|*magisk*) ROOT_MANAGER=Magisk ;;
                *APatch*|*apatch*) ROOT_MANAGER=APatch ;;
                *) ROOT_MANAGER=unknown ;;
            esac
        fi
        set -e
        export ZIYU_DEFER_APP_INSTALL=1
        installer=
        case "${'$'}ROOT_MANAGER" in
            Magisk) candidates="/data/adb/magisk/magisk ${'$'}(command -v magisk 2>/dev/null || true)"; kind=magisk ;;
            KernelSU|'SukiSU Ultra') candidates="/data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ksu/ksud ${'$'}(command -v ksud 2>/dev/null || true)"; kind=ksu ;;
            APatch) candidates="/data/adb/ap/bin/apd /data/adb/apd ${'$'}(command -v apd 2>/dev/null || true)"; kind=apatch ;;
            *) echo '无法确认当前 Root 管理器，未安装模块' >&2; exit 1 ;;
        esac
        for candidate in ${'$'}candidates; do [ ! -x "${'$'}candidate" ] || { installer=${'$'}candidate; break; }; done
        [ -n "${'$'}installer" ] || { echo '未找到当前 Root 管理器的模块安装命令' >&2; exit 1; }
        cp ${RootShell.quote(archive.absolutePath)} ${RootShell.quote(rootZip)}
        chmod 600 ${RootShell.quote(rootZip)}
        actual=${'$'}(sha256sum ${RootShell.quote(rootZip)} | cut -d ' ' -f 1)
        [ "${'$'}actual" = ${RootShell.quote(sha)} ] || { echo 'Root 临时模块文件校验失败' >&2; exit 1; }
        case "${'$'}kind" in
            magisk) "${'$'}installer" --install-module ${RootShell.quote(rootZip)} ;;
            *) "${'$'}installer" module install ${RootShell.quote(rootZip)} ;;
        esac
    """.trimIndent()

    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Ziyu/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Cache-Control", "no-cache")
    }

    private fun text(url: String, accept: String = "text/plain"): String {
        val connection = connection(url).apply { setRequestProperty("Accept", accept) }
        try {
            require(connection.responseCode in 200..299) { "更新服务器返回 HTTP ${connection.responseCode}" }
            return connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(1_048_576)
                String(bytes, Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { "更新元数据过大" }
            output.write(buffer, 0, count)
        }
    }

    private companion object { const val MAX_ZIP_BYTES = 128L * 1024 * 1024 }
}
