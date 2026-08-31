// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.ExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import runtime.mobileagent.bridge.BridgeProtocol

class AdbConfiguration private constructor(
    adbPath: Path,
    val serial: String,
    val devicePort: Int,
    val hostPort: Int,
) {
    val adbPath: Path = adbPath.toAbsolutePath().normalize()
    val canonicalAdbPath: Path = adbPath

    init {
        require(serial.isNotBlank() && serial.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' }) {
            "ADB serial is invalid"
        }
        require(devicePort in 1..65_535 && hostPort in 1..65_535)
        require(canonicalAdbPath.isAbsolute)
        require(canonicalAdbPath.fileName.toString().equals("adb.exe", ignoreCase = true)) {
            "companion requires official adb.exe"
        }
    }

    companion object {
        fun create(
            adbPath: Path,
            serial: String,
            devicePort: Int,
            hostPort: Int,
        ): AdbConfiguration {
            val absolute = adbPath.toAbsolutePath().normalize()
            require(absolute.fileName.toString().equals("adb.exe", ignoreCase = true)) {
                "companion requires official adb.exe"
            }
            require(!Files.isSymbolicLink(absolute)) { "configured adb.exe may not be a symlink" }
            val canonical = absolute.toRealPath()
            require(!Files.isSymbolicLink(canonical)) { "configured adb.exe may not be a symlink" }
            require(Files.isRegularFile(canonical, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "configured adb.exe does not exist"
            }
            return AdbConfiguration(canonical, serial, devicePort, hostPort)
        }
    }
}

enum class ProcessOutcome {
    COMPLETE,
    FAILED,
    UNKNOWN_OUTCOME,
}

data class ProcessRequest(
    val argv: List<String>,
    val stdin: ByteArray? = null,
    val timeoutMs: Long = 30_000,
    val stdoutCapBytes: Int = 1 * 1024 * 1024,
    val stderrCapBytes: Int = 1 * 1024 * 1024,
    val cancelRequested: () -> Boolean = { false },
) {
    init {
        require(argv.isNotEmpty())
        require(argv.all { it.isNotEmpty() && !it.contains('\u0000') })
        require(timeoutMs > 0)
        require(stdoutCapBytes >= 0 && stderrCapBytes >= 0)
    }
}

data class ProcessCapture(
    val outcome: ProcessOutcome,
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
)

fun interface ProcessRunner {
    fun run(request: ProcessRequest): ProcessCapture
}

/**
 * ProcessRunner uses the ProcessBuilder(List<String>) API exclusively.  The
 * two output pipes are drained concurrently and remain drained after a cap is
 * reached, preventing a child from deadlocking on a full pipe.
 */
class ProcessBuilderRunner(
    private val executor: ExecutorService = boundedProcessExecutor(),
) : ProcessRunner, AutoCloseable {
    override fun run(request: ProcessRequest): ProcessCapture {
        val startedAt = System.nanoTime()
        val process = try {
            ProcessBuilder(request.argv)
                .redirectErrorStream(false)
                .start()
        } catch (error: Exception) {
            return ProcessCapture(
                ProcessOutcome.FAILED,
                null,
                ByteArray(0),
                error.message.orEmpty().toByteArray(StandardCharsets.UTF_8),
                false,
                false,
                elapsedMillis(startedAt),
            )
        }

        val outTask = java.util.concurrent.Callable<CappedOutput> {
            drain(process.inputStream, request.stdoutCapBytes)
        }
        val errTask = java.util.concurrent.Callable<CappedOutput> {
            drain(process.errorStream, request.stderrCapBytes)
        }
        var outFuture: Future<CappedOutput>? = null
        var errFuture: Future<CappedOutput>? = null
        var stdinFuture: Future<*>? = null
        try {
            outFuture = executor.submit(outTask)
            errFuture = executor.submit(errTask)
            stdinFuture = executor.submit {
                try {
                    process.outputStream.use { output ->
                        request.stdin?.let { output.write(it) }
                        output.flush()
                    }
                } catch (_: Exception) {
                    // Child termination and a closed stdin are expected during timeout/cancel.
                }
            }
        } catch (_: RejectedExecutionException) {
            // The bounded worker pool is saturated.  Do not leave a child
            // alive with undrained pipes or a half-submitted stdin writer.
            terminate(process)
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            outFuture?.cancel(true)
            errFuture?.cancel(true)
            stdinFuture?.cancel(true)
            return ProcessCapture(
                outcome = ProcessOutcome.UNKNOWN_OUTCOME,
                exitCode = null,
                stdout = ByteArray(0),
                stderr = "process executor capacity is exhausted".toByteArray(StandardCharsets.UTF_8),
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = elapsedMillis(startedAt),
            )
        }

        var timedOut = false
        var cancelled = false
        var exited = false
        while (!exited) {
            try {
                exited = process.waitFor(25, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelled = true
                break
            }
            if (!exited && request.cancelRequested()) {
                cancelled = true
                break
            }
            if (!exited && elapsedMillis(startedAt) >= request.timeoutMs) {
                timedOut = true
                break
            }
        }
        if (!exited) terminate(process)
        runCatching { process.outputStream.close() }
        awaitCompletion(stdinFuture!!, 1_000)
        val stdout = await(outFuture!!, 2_000) ?: CappedOutput(ByteArray(0), false)
        val stderr = await(errFuture!!, 2_000) ?: CappedOutput(ByteArray(0), false)
        val exitCode = if (exited) runCatching { process.exitValue() }.getOrNull() else null
        return ProcessCapture(
            outcome = when {
                timedOut || cancelled -> ProcessOutcome.UNKNOWN_OUTCOME
                exited && exitCode == 0 -> ProcessOutcome.COMPLETE
                exited -> ProcessOutcome.FAILED
                else -> ProcessOutcome.UNKNOWN_OUTCOME
            },
            exitCode = exitCode,
            stdout = stdout.bytes,
            stderr = stderr.bytes,
            stdoutTruncated = stdout.truncated,
            stderrTruncated = stderr.truncated,
            durationMs = elapsedMillis(startedAt),
            timedOut = timedOut,
            cancelled = cancelled,
        )
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun terminate(process: Process) {
        val descendants = process.toHandle().descendants().toList()
        descendants.forEach { it.destroy() }
        process.destroy()
        runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        descendants.forEach { if (it.isAlive) it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
    }

    private fun await(future: Future<CappedOutput>, timeoutMs: Long): CappedOutput? = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: TimeoutException) {
        future.cancel(true)
        null
    }

    private fun awaitCompletion(future: Future<*>, timeoutMs: Long) {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: ExecutionException) {
            // Child termination and a closed stdin are expected on timeout.
        } catch (_: TimeoutException) {
            future.cancel(true)
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))

    private data class CappedOutput(val bytes: ByteArray, val truncated: Boolean)

    private fun drain(input: java.io.InputStream, cap: Int): CappedOutput {
        val output = ByteArrayOutputStream(minOf(cap, 8192))
        val buffer = ByteArray(8192)
        var truncated = false
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = cap - output.size()
                if (remaining > 0) {
                    output.write(buffer, 0, minOf(count, remaining))
                }
                if (count > remaining) truncated = true
            }
        }
        return CappedOutput(output.toByteArray(), truncated)
    }
}

private fun boundedProcessExecutor(): ExecutorService = ThreadPoolExecutor(
    4,
    16,
    30,
    TimeUnit.SECONDS,
    ArrayBlockingQueue(64),
    DaemonThreadFactory("mar-bridge-process"),
    ThreadPoolExecutor.AbortPolicy(),
)

private class DaemonThreadFactory(private val prefix: String) : java.util.concurrent.ThreadFactory {
    private val counter = AtomicInteger()
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
        isDaemon = true
    }
}

enum class AdbDeviceState {
    DEVICE,
    UNAUTHORIZED,
    OFFLINE,
    BOOTLOADER,
    NO_PERMISSIONS,
    UNKNOWN,
}

data class AdbDevice(
    val serial: String,
    val state: AdbDeviceState,
    val attributes: Map<String, String>,
)

object AdbDevicesParser {
    fun parse(output: String): List<AdbDevice> {
        val lines = output.split("\r\n", "\n", "\r")
        require(lines.firstOrNull()?.trim() == "List of devices attached") {
            "adb devices output header is invalid"
        }
        val devices = mutableListOf<AdbDevice>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val fields = line.trim().split(Regex("\\s+"))
            require(fields.size >= 2) { "malformed adb devices line" }
            val serial = fields[0]
            require(serial.isNotEmpty() && serial.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' }) {
                "invalid adb serial"
            }
            val (stateToken, attributeStart) = if (fields[1] == "no" && fields.getOrNull(2) == "permissions") {
                "no permissions" to 3
            } else {
                fields[1] to 2
            }
            val state = when (stateToken) {
                "device" -> AdbDeviceState.DEVICE
                "unauthorized" -> AdbDeviceState.UNAUTHORIZED
                "offline" -> AdbDeviceState.OFFLINE
                "bootloader" -> AdbDeviceState.BOOTLOADER
                "no permissions" -> AdbDeviceState.NO_PERMISSIONS
                else -> AdbDeviceState.UNKNOWN
            }
            val attributes = linkedMapOf<String, String>()
            fields.drop(attributeStart).forEach { pair ->
                val separator = pair.indexOf(':')
                require(separator in 1 until pair.lastIndex) { "malformed adb device attribute" }
                val key = pair.substring(0, separator)
                val value = pair.substring(separator + 1)
                require(key.all { it.isLetterOrDigit() || it == '_' || it == '-' })
                attributes[key] = value
            }
            devices += AdbDevice(serial, state, attributes)
        }
        return devices
    }

    fun selectExplicit(devices: List<AdbDevice>, serial: String): AdbDevice {
        require(serial.isNotBlank() && serial.none { it.isWhitespace() })
        val matching = devices.filter { it.serial == serial }
        require(matching.size == 1) { "configured serial is not uniquely present" }
        val selected = matching.single()
        require(selected.state == AdbDeviceState.DEVICE) {
            "configured serial is not authorized/online: ${selected.state}"
        }
        return selected
    }
}

enum class AdbOperation {
    VERSION,
    DEVICES,
    FEATURES,
    REVERSE_ENSURE,
    REVERSE_LIST,
    REVERSE_REMOVE,
    SHELL,
    TYPED_FILES,
}

data class AdbResult(
    val operation: AdbOperation,
    val process: ProcessCapture,
    val stderrMayContainAdbDiagnostics: Boolean = false,
)

/** Internal ADB command builder; request payloads never provide adb path or serial. */
class AdbProcessManager private constructor(
    val configuration: AdbConfiguration,
    private val runner: ProcessRunner,
    private val executableGuard: AdbExecutableGuard,
) {
    fun version(timeoutMs: Long = 10_000): AdbResult = run(
        AdbOperation.VERSION,
        listOf(configuration.adbPath.toString(), "version"),
        timeoutMs,
    )

    fun devices(timeoutMs: Long = 10_000): AdbResult = run(
        AdbOperation.DEVICES,
        listOf(configuration.adbPath.toString(), "devices", "-l"),
        timeoutMs,
    )

    fun features(timeoutMs: Long = 10_000): AdbResult = run(
        AdbOperation.FEATURES,
        serialArgs("features"),
        timeoutMs,
    )

    fun reverseEnsure(timeoutMs: Long = 10_000): AdbResult = run(
        AdbOperation.REVERSE_ENSURE,
        serialArgs(
            "reverse", "--no-rebind",
            "tcp:${configuration.devicePort}",
            "tcp:${configuration.hostPort}",
        ),
        timeoutMs,
    )

    fun reverseList(timeoutMs: Long = 10_000): AdbResult = run(
        AdbOperation.REVERSE_LIST,
        serialArgs("reverse", "--list"),
        timeoutMs,
    )

    fun reverseRemove(timeoutMs: Long = 10_000): AdbResult = run(
        AdbOperation.REVERSE_REMOVE,
        serialArgs("reverse", "--remove", "tcp:${configuration.devicePort}"),
        timeoutMs,
    )

    fun runShell(
        script: ByteArray,
        timeoutMs: Long,
        maxOutputBytes: Int,
        cancelRequested: () -> Boolean = { false },
    ): AdbResult = run(
        AdbOperation.SHELL,
        serialArgs("shell", "-T", "sh", "-s"),
        timeoutMs,
        stdin = script,
        stdoutCapBytes = maxOutputBytes,
        stderrCapBytes = maxOutputBytes,
        cancelRequested = cancelRequested,
        stderrMayContainAdbDiagnostics = true,
    )

    /**
     * Runs the fixed shell-UID typed-file helper.  The helper command is a
     * constant; request data is sent only as a length-delimited JSON frame on
     * stdin.  In particular, no request value can become shell syntax.
     */
    fun runTypedFiles(
        frame: ByteArray,
        timeoutMs: Long = WIRED_ADB_FILE_DEADLINE_MS,
        stdoutCapBytes: Int = BridgeProtocol.MAX_FRAME_BYTES,
        stderrCapBytes: Int = 64 * 1024,
        cancelRequested: () -> Boolean = { false },
    ): AdbResult {
        require(frame.size in 5..BridgeProtocol.MAX_FRAME_BYTES)
        require(timeoutMs in 1..5 * 60 * 1_000L)
        require(stdoutCapBytes in 1..BridgeProtocol.MAX_FRAME_BYTES)
        require(stderrCapBytes in 0..BridgeProtocol.MAX_FRAME_BYTES)
        return run(
            AdbOperation.TYPED_FILES,
            serialArgs("shell", "-T", "sh", "-c", WIRED_ADB_TYPED_HELPER_COMMAND),
            timeoutMs,
            stdin = frame,
            stdoutCapBytes = stdoutCapBytes,
            stderrCapBytes = stderrCapBytes,
            cancelRequested = cancelRequested,
            stderrMayContainAdbDiagnostics = true,
        )
    }

    private fun serialArgs(vararg args: String): List<String> =
        buildList {
            add(configuration.adbPath.toString())
            add("-s")
            add(configuration.serial)
            addAll(args)
        }

    private fun run(
        operation: AdbOperation,
        argv: List<String>,
        timeoutMs: Long,
        stdin: ByteArray? = null,
        stdoutCapBytes: Int = BridgeProtocol.MAX_FRAME_BYTES,
        stderrCapBytes: Int = BridgeProtocol.MAX_FRAME_BYTES,
        cancelRequested: () -> Boolean = { false },
        stderrMayContainAdbDiagnostics: Boolean = false,
    ): AdbResult {
        executableGuard.verifyBeforeSpawn()
        val result = runner.run(
            ProcessRequest(argv, stdin, timeoutMs, stdoutCapBytes, stderrCapBytes, cancelRequested),
        )
        return AdbResult(operation, result, stderrMayContainAdbDiagnostics)
    }

    companion object {
        internal fun validated(
            configuration: AdbConfiguration,
            runner: ProcessRunner,
            report: AdbDoctorReport,
            verifier: WinTrustVerifier = JnaWinTrustVerifier(),
        ): AdbProcessManager {
            require(report.canonicalPath == configuration.canonicalAdbPath) {
                "ADB validation path does not match configuration"
            }
            return AdbProcessManager(configuration, runner, AdbExecutableGuard(report, verifier))
        }

    }
}

data class AdbReverseMapping(
    val serial: String,
    val devicePort: Int,
    val hostPort: Int,
)

class AdbReverseManager(private val adb: AdbProcessManager) {
    fun ensure(): AdbResult {
        val result = adb.reverseEnsure()
        require(result.process.outcome == ProcessOutcome.COMPLETE) { "adb reverse ensure failed" }
        require(listOwn().any { it.devicePort == adb.configuration.devicePort && it.hostPort == adb.configuration.hostPort }) {
            "adb reverse mapping was not established"
        }
        return result
    }

    fun listOwn(): List<AdbReverseMapping> {
        val result = adb.reverseList()
        require(result.process.outcome == ProcessOutcome.COMPLETE) { "adb reverse list failed" }
        return parse(result.process.stdout.toUtf8Strict()).filter { it.serial == adb.configuration.serial }
    }

    /** Removes only this companion's exact mapping, never all reverse mappings. */
    fun removeOwn(): Boolean {
        val own = listOwn().filter {
            it.devicePort == adb.configuration.devicePort && it.hostPort == adb.configuration.hostPort
        }
        if (own.isEmpty()) return false
        val result = adb.reverseRemove()
        require(result.process.outcome == ProcessOutcome.COMPLETE) { "adb reverse remove failed" }
        return true
    }

    private fun parse(output: String): List<AdbReverseMapping> = output.lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.trim().split(Regex("\\s+"))
            require(fields.size == 3) { "malformed adb reverse mapping" }
            val serial = fields[0]
            val devicePort = parseTcpPort(fields[1])
            val hostPort = parseTcpPort(fields[2])
            AdbReverseMapping(serial, devicePort, hostPort)
        }.toList()

    private fun parseTcpPort(value: String): Int {
        require(value.startsWith("tcp:"))
        return value.substringAfter("tcp:").toInt().also { require(it in 1..65_535) }
    }
}

data class WiredAdbShellRequest(
    val command: String,
    val cwd: String? = null,
    val timeoutMs: Long = 30_000,
    val maxOutputBytes: Int = 1 * 1024 * 1024,
) {
    init {
        val commandBytes = command.toUtf8StrictBytes("command")
        require(command.isNotEmpty() && !command.contains('\u0000'))
        require(commandBytes.size <= BridgeProtocol.MAX_COMMAND_BYTES)
        require(cwd == null || (!cwd.contains('\u0000') && cwd.toUtf8StrictBytes("cwd").size <= 4 * 1024))
        require(timeoutMs in 1..5 * 60 * 1_000L)
        require(maxOutputBytes in 1..BridgeProtocol.MAX_FRAME_BYTES)
    }
}

data class WiredAdbShellResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val outcome: ProcessOutcome,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val stderrMayContainAdbDiagnostics: Boolean,
    val durationMs: Long,
) {
    fun stdoutCopy(): ByteArray = stdout.copyOf()
    fun stderrCopy(): ByteArray = stderr.copyOf()

    /**
     * Return only the stderr bytes that are safe to cross the authenticated
     * Companion boundary.  shell-v2 exposes the adb client's stderr through
     * the same host pipe as the remote shell diagnostics, so a marked stream
     * may contain the configured serial, adb path, platform-tools details, or
     * raw process diagnostics.  Once marked, the complete stream is discarded
     * rather than attempting to classify individual lines.  The caller keeps
     * the existing truncation flag and terminal status as safe metadata.
     */
    internal fun stderrForBridge(): ByteArray =
        if (stderrMayContainAdbDiagnostics) ByteArray(0) else stderrCopy()
}

/** One-shot shell executor; no host shell is involved in command execution. */
class WiredAdbShellExecutor(
    private val adb: AdbProcessManager,
) {
    @Volatile private var shellV2Confirmed = false

    fun execute(request: WiredAdbShellRequest, cancelRequested: () -> Boolean = { false }): WiredAdbShellResult {
        if (!shellV2Confirmed) {
            val features = adb.features()
            require(features.process.outcome == ProcessOutcome.COMPLETE) { "cannot verify adb shell_v2" }
            val advertised = (features.process.stdout + features.process.stderr).toUtf8Strict()
            require(advertised.lineSequence().flatMap { it.split(Regex("[ ,\\t]+")) }.any { it == "shell_v2" }) {
                "adb shell_v2 is not available"
            }
            shellV2Confirmed = true
        }
        val script = buildScript(request)
        val result = try {
            adb.runShell(script, request.timeoutMs, request.maxOutputBytes, cancelRequested)
        } finally {
            Arrays.fill(script, 0)
        }
        return WiredAdbShellResult(
            result.process.exitCode,
            result.process.stdout,
            result.process.stderr,
            result.process.outcome,
            result.process.timedOut,
            result.process.cancelled,
            result.process.stdoutTruncated,
            result.process.stderrTruncated,
            stderrMayContainAdbDiagnostics = true,
            result.process.durationMs,
        )
    }

    private fun buildScript(request: WiredAdbShellRequest): ByteArray {
        val script = buildString {
            request.cwd?.let { append("cd -- ").append(posixSingleQuote(it)).append('\n') }
            append(request.command)
            append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
        require(script.size <= BridgeProtocol.MAX_COMMAND_BYTES + 4 * 1024 + 1)
        return script
    }

    private fun posixSingleQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

internal fun ByteArray.toUtf8Strict(): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(java.nio.ByteBuffer.wrap(this))
    .toString()

private fun String.toUtf8StrictBytes(field: String): ByteArray {
    require(!contains('\u0000')) { "$field contains NUL" }
    val bytes = toByteArray(StandardCharsets.UTF_8)
    require(runCatching { bytes.toUtf8Strict() == this }.getOrDefault(false)) {
        "$field is not valid UTF-8"
    }
    return bytes
}
