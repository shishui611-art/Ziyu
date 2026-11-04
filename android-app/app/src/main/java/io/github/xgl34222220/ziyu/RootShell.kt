package io.github.xgl34222220.ziyu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
)

internal object RootShell {
    @Volatile private var moduleRoot: String? = null
    fun rememberModule(path: String) {
        if (path in setOf("/data/adb/modules/ziyu", "/data/adb/modules/LuoShu")) moduleRoot = path
    }

    private fun resolveCommand(command: String): String {
        val primary = "/data/adb/modules/ziyu"
        if (!command.contains(primary)) return command
        moduleRoot?.let { return command.replace(primary, it) }
        // Resolve in the same su session. The connection request validates the
        // module identity; legacy fallback retains its explicit identity check.
        return "if [ -f '$primary/common/app_bridge.sh' ]; then\n$command\n" +
            "elif [ -f /data/adb/modules/LuoShu/common/app_bridge.sh ] && " +
            "sed -n 's/^id=//p' /data/adb/modules/LuoShu/module.prop | tr -d '\\r' | grep -Eq '^(ziyu|LuoShu)\$'; then\n" +
            command.replace(primary, "/data/adb/modules/LuoShu") + "\nelse\n$command\nfi"
    }

    suspend fun exec(command: String, timeoutMs: Long = 600_000L): ShellResult = withContext(Dispatchers.IO) {
        try {
            val frame = RootReplyFrame()
            val body = LegacyIdentityCompatibility.commandFallbacks(command) + resolveCommand(command)
            executeProcess(listOf("su", "-c", frame.wrap(body)), timeoutMs, frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw CancellationException("Command cancelled").also { it.initCause(interrupted) }
        } catch (error: Throwable) {
            val raw = error.message.orEmpty()
            if (
                error is IOException &&
                raw.contains("interrupted by close", ignoreCase = true)
            ) {
                return@withContext ShellResult(75, "", "Root 输出暂时中断，请重试")
            }
            val message = if (
                error is IOException &&
                (raw.contains("Cannot run program \"su\"") || raw.contains("No such file or directory"))
            ) {
                "未找到 Root 命令 su。请先在 Root 管理器中完成待生效变更并完整重启，然后为字域授予 Root 权限。"
            } else {
                raw.ifBlank { error.javaClass.simpleName }
            }
            ShellResult(127, "", message)
        }
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** Owns only the request process. Module workers are detached by app_bridge.sh. */
internal suspend fun executeProcess(command: List<String>, timeoutMs: Long): ShellResult =
    executeProcess(command, timeoutMs, null)

private suspend fun executeProcess(command: List<String>, timeoutMs: Long, frame: RootReplyFrame?): ShellResult =
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        val startedAt = System.nanoTime()
        var exitedAt: Long? = null
        try {
            // No command accepts interactive input. Closing stdin also lets commands waiting
            // for EOF finish normally. Descendants can inherit pipes, so a framed
            // request completes on both footer records rather than waiting for EOF.
            process.outputStream.close()
            while (true) {
                currentCoroutineContext().ensureActive()
                val outputBytes = drainAvailable(process.inputStream, stdout, buffer, frame, 0) +
                    drainAvailable(process.errorStream, stderr, buffer, frame, 1)
                if (frame?.complete == true) {
                    return@withContext ShellResult(frame.exitCode, frame.clean(stdout), frame.clean(stderr))
                }
                if (!process.isAlive) {
                    if (frame == null) {
                        drainAvailable(process.inputStream, stdout, buffer)
                        drainAvailable(process.errorStream, stderr, buffer)
                        return@withContext ShellResult(process.exitValue(), stdout.toString("UTF-8"), stderr.toString("UTF-8"))
                    }
                    // available()==0 is not proof that the reply is complete.
                    // Keep draining across process exit until the footer arrives,
                    // bounded by a short grace period and the request deadline.
                    if (exitedAt == null || outputBytes > 0) exitedAt = System.nanoTime()
                    if ((System.nanoTime() - exitedAt) / 1_000_000L >= 300L) {
                        val code = process.exitValue().takeIf { it != 0 } ?: 74
                        return@withContext ShellResult(code, frame.clean(stdout),
                            listOf("Root 回复不完整（退出码 ${process.exitValue()}）", frame.clean(stderr)).filter { it.isNotBlank() }.joinToString("\n"))
                    }
                }
                val remaining = timeoutMs - (System.nanoTime() - startedAt) / 1_000_000L
                if (remaining <= 0L) {
                    return@withContext ShellResult(124, frame?.clean(stdout) ?: stdout.toString("UTF-8"), "命令执行超时\n${frame?.clean(stderr) ?: stderr.toString("UTF-8")}".trim())
                }
                // Suspending checks avoid uninterruptible waitFor/readText workers. Both pipes
                // are drained together so a full stderr pipe cannot stall a stdout reader.
                delay(minOf(when {
                    outputBytes > 0 -> 1L
                    exitedAt != null -> 10L
                    else -> 50L
                }, remaining))
            }
            @Suppress("UNREACHABLE_CODE")
            error("Process loop ended unexpectedly")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

private fun drainAvailable(stream: InputStream, target: ByteArrayOutputStream, buffer: ByteArray,
                           frame: RootReplyFrame? = null, channel: Int = 0, maxBytes: Int = 65_536): Int {
    // A bounded pass prevents an endlessly verbose process from starving cancellation/the deadline.
    var readBytes = 0
    while (readBytes < maxBytes) {
        val available = stream.available()
        if (available <= 0) break
        val count = stream.read(buffer, 0, minOf(available, buffer.size, maxBytes - readBytes))
        if (count <= 0) break
        if (target.size().toLong() + count > 32L * 1024 * 1024) throw IOException("Root 回复过大，已停止读取")
        target.write(buffer, 0, count)
        frame?.accept(channel, buffer, count)
        readBytes += count
    }
    return readBytes
}

/** Nonce-scoped framing also tolerates su implementations that merge the pipes. */
private class RootReplyFrame {
    private val token = "__ZIYU_REPLY_${UUID.randomUUID().toString().replace("-", "")}__"
    private val tails = arrayOf("", "")
    private val beginnings = booleanArrayOf(false, false)
    private val codes = arrayOfNulls<Int>(2)
    val complete: Boolean get() = beginnings.all { it } && codes[0] != null && codes[0] == codes[1]
    val exitCode: Int get() = checkNotNull(codes[0])

    fun wrap(command: String): String = "set +e\n" +
        "printf '\\n$token:begin:out\\n'\n" +
        "printf '\\n$token:begin:err\\n' >&2\n(\n$command\n)\n" +
        "_ziyu_reply_code=\$?\n" +
        "printf '\\n$token:out=%s\\n' \"\$_ziyu_reply_code\"\n" +
        "printf '\\n$token:err=%s\\n' \"\$_ziyu_reply_code\" >&2\n" +
        "exit \"\$_ziyu_reply_code\""

    fun accept(channel: Int, bytes: ByteArray, count: Int) {
        // Latin-1 is a byte-preserving view for matching the ASCII footer; UTF-8
        // content is decoded only once, after the complete reply is collected.
        val recent = tails[channel] + String(bytes, 0, count, Charsets.ISO_8859_1)
        for ((index, name) in listOf("out", "err").withIndex()) {
            if (!beginnings[index] && (recent.contains("\n$token:begin:$name\n") ||
                        recent.contains("\n$token:begin:$name\r\n"))) beginnings[index] = true
            if (codes[index] != null) continue
            val prefix = "\n$token:$name="
            val start = recent.indexOf(prefix)
            if (start < 0) continue
            val end = recent.indexOf('\n', start + prefix.length)
            if (end < 0) continue
            val code = recent.substring(start + prefix.length, end).trimEnd('\r').toIntOrNull()
            if (code != null && code in 0..255) codes[index] = code
        }
        tails[channel] = recent.takeLast(token.length + 24)
    }

    fun clean(bytes: ByteArrayOutputStream): String {
        var text = bytes.toString("UTF-8")
        val start = listOf("out", "err").flatMap { name -> listOf("\n$token:begin:$name\n", "\n$token:begin:$name\r\n") }
            .mapNotNull { marker -> text.indexOf(marker).takeIf { it >= 0 }?.let { it to marker.length } }
            .minByOrNull { it.first }
        if (start != null) text = text.substring(start.first + start.second)
        // When su merges pipes, preserve both bodies through the last logical
        // footer. Drop unrelated descendant output beyond the reply boundary.
        val end = listOf("out", "err").flatMapIndexed { index, name ->
            codes[index]?.let { code -> listOf("\n$token:$name=$code\n", "\n$token:$name=$code\r\n") }.orEmpty()
        }.mapNotNull { marker -> text.indexOf(marker).takeIf { it >= 0 } }.maxOrNull()
        if (end != null) text = text.substring(0, end)
        for ((index, name) in listOf("out", "err").withIndex()) {
            text = text.replace("\n$token:begin:$name\n", "")
                .replace("\n$token:begin:$name\r\n", "")
            codes[index]?.let { code ->
                text = text.replace("\n$token:$name=$code\n", "").replace("\n$token:$name=$code\r\n", "")
            }
        }
        return text
    }
}
