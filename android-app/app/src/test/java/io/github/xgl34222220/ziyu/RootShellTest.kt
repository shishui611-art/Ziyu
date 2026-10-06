package io.github.xgl34222220.ziyu

import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class RootShellTest {
    // Exercise a single native request process on each host. MSYS `exec`
    // emulation creates another Windows process that inherits its pipe.
    private val windowsHost = System.getProperty("os.name").startsWith("Windows")
    private fun sleepingRequest(pidFile: java.io.File, output: Boolean = false): List<String> {
        if (windowsHost) {
            val path = pidFile.path.replace("'", "''")
            val print = if (output) "[Console]::Write('started');" else ""
            return listOf("powershell.exe", "-NoProfile", "-Command", "[IO.File]::WriteAllText('$path', \$PID.ToString()); $print Start-Sleep -Seconds 30")
        }
        val print = if (output) "printf started;" else ""
        return listOf("sh", "-c", "printf '%s' \"\$\$\" > ${RootShell.quote(pidFile.path)}; $print exec sleep 30")
    }
    @Test
    fun preservesExitCodeAndBothOutputStreams() = runBlocking {
        val result = executeProcess(listOf("sh", "-c", "printf '字体正常'; printf 'warning' >&2; exit 7"), 2_000L)
        assertEquals(7, result.code)
        assertEquals("字体正常", result.stdout)
        assertEquals("warning", result.stderr)
    }

    @Test
    fun drainsLargeStdoutAndStderrWithoutAFullPipeDeadlock() = runBlocking {
        val result = withTimeout(5_000L) {
            executeProcess(
                listOf("sh", "-c", "head -c 262144 /dev/zero; head -c 262144 /dev/zero >&2"),
                4_000L,
            )
        }
        assertEquals(0, result.code)
        assertEquals(262_144, result.stdout.length)
        assertEquals(262_144, result.stderr.length)
    }

    @Test
    fun closesStdinSoNonInteractiveRequestsCanFinish() = runBlocking {
        val result = executeProcess(listOf("sh", "-c", "cat; printf done"), 2_000L)
        assertEquals(0, result.code)
        assertEquals("done", result.stdout)
    }

    @Test
    fun cancellationReapsTheRequestInsteadOfWaitingForItsTimeout() = runBlocking {
        val directory = Files.createTempDirectory("luoshu-cancel").toFile()
        val pidFile = directory.resolve("request.pid")
        var pid = 0L
        try {
            val job = launch {
                executeProcess(sleepingRequest(pidFile), 30_000L)
            }
            withTimeout(3_000L) {
                while (!pidFile.exists() || pidFile.length() == 0L) delay(10L)
                pid = pidFile.readText().toLong()
                job.cancelAndJoin()
                while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10L)
            }
            assertTrue(job.isCancelled)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            if (pid > 0) ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun timeoutReturnsPartialOutputAndTerminatesTheRequest() = runBlocking {
        val directory = Files.createTempDirectory("luoshu-timeout").toFile()
        val pidFile = directory.resolve("request.pid")
        try {
            val result = withTimeout(3_000L) {
                executeProcess(
                    sleepingRequest(pidFile, output = true),
                    if (windowsHost) 1_000L else 200L,
                )
            }
            assertEquals(124, result.code)
            assertEquals("started", result.stdout)
            assertTrue(result.stderr.contains("超时"))
            withTimeout(2_000L) {
                val pid = pidFile.readText().toLong()
                while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10L)
            }
        } finally {
            if (pidFile.exists()) pidFile.readText().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellingARequestDoesNotKillItsDetachedFontWorker() = runBlocking {
        assumeFalse("Requires Android/Linux detached-shell process semantics", windowsHost)
        val directory = Files.createTempDirectory("luoshu-detached").toFile()
        val ready = directory.resolve("ready")
        val finished = directory.resolve("finished")
        try {
            val workerCommand = "sleep 0.3; printf done > ${RootShell.quote(finished.path)}"
            val job = launch {
                executeProcess(
                    listOf("sh", "-c", "sh -c ${RootShell.quote(workerCommand)} </dev/null >/dev/null 2>&1 & printf ready > ${RootShell.quote(ready.path)}; exec sleep 30"),
                    30_000L,
                )
            }
            withTimeout(3_000L) {
                while (!ready.exists()) delay(10L)
                job.cancelAndJoin()
                while (!finished.exists() || finished.length() == 0L) delay(10L)
            }
            assertEquals("done", finished.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aDescendantHoldingThePipeDoesNotExtendTheRequestLifetime() = runBlocking {
        // Windows process/pipe exit notifications differ from Android/Linux,
        // even with a native JVM parent/child. Keep this Linux CI assertion;
        // explicitly report it skipped locally instead of widening its deadline.
        assumeFalse("Requires Android/Linux process and inherited-pipe semantics", windowsHost)
        val result = withTimeout(1_500L) {
            executeProcess(listOf("sh", "-c", "sleep 2 & printf submitted"), 5_000L)
        }
        assertEquals(0, result.code)
        assertEquals("submitted", result.stdout)
    }
}
