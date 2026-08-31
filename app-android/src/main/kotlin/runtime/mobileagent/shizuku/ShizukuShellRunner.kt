// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Request passed from the session-authenticated UserService to the runner. */
internal data class ShizukuShellRunnerRequest(
    val callId: String,
    val command: String,
    val cwd: String?,
    val timeoutMs: Long,
    val maxStdoutBytes: Int,
    val maxStderrBytes: Int,
)

/**
 * Injectable process boundary.  The UserService uses the production
 * implementation below; tests can inject a fake without requiring Shizuku to
 * be installed or a real device shell.
 */
internal interface ShizukuShellRunner : AutoCloseable {
    fun start(request: ShizukuShellRunnerRequest): ShizukuShellResponse

    fun cancel(callId: String): Boolean

    override fun close()
}

/**
 * One-shot Android shell runner.
 *
 * The command is written only to stdin of `/system/bin/sh -s`; it is never
 * appended to an argv string or interpreted by a host shell.  Each accepted
 * call owns three independent Binder pipes (stdout, stderr and a small JSON
 * completion envelope).  Reader threads continue draining process streams
 * after the per-stream cap is reached, so a verbose command cannot deadlock
 * the shell while the caller is waiting for its terminal state.
 *
 * Android's Java Process API does not prove that a shell's pipeline/background
 * descendants are gone.  A dispatched timeout or cancellation is therefore
 * reported as UNKNOWN_OUTCOME even when the shell process itself exits.
 */
internal class ProcessShizukuShellRunner : ShizukuShellRunner {
    private val lock = Any()
    private val active = ConcurrentHashMap<String, RunningShell>()
    private val seenCallIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val workerPool: ExecutorService = Executors.newFixedThreadPool(
        ShizukuShellLimits.MAX_GLOBAL_CONCURRENCY,
    )
    private val pumpPool: ExecutorService = Executors.newFixedThreadPool(
        ShizukuShellLimits.MAX_GLOBAL_CONCURRENCY * 2,
    )
    private val closed = AtomicBoolean(false)

    override fun start(request: ShizukuShellRunnerRequest): ShizukuShellResponse {
        if (closed.get()) return ShizukuShellResponse.rejected(ShizukuShellLimits.UNAVAILABLE)
        // This runner is constructed and invoked by ShizukuUserService.  The
        // directory check therefore executes under the UserService/shell UID,
        // after the app-side bridge has performed syntax-only validation.
        if (request.cwd != null && !isValidCwd(request.cwd)) {
            return ShizukuShellResponse.rejected(ShizukuShellLimits.INVALID_CWD)
        }
        val normalized = normalize(request) ?: return ShizukuShellResponse.rejected(
            ShizukuShellLimits.INVALID_REQUEST,
        )
        synchronized(lock) {
            if (seenCallIds.contains(normalized.callId)) {
                return ShizukuShellResponse.rejected(ShizukuShellLimits.REPLAY_DENIED)
            }
            if (seenCallIds.size >= MAX_RETAINED_CALL_IDS) {
                return ShizukuShellResponse.rejected(ShizukuShellLimits.CONCURRENCY_LIMIT)
            }
            if (active.size >= ShizukuShellLimits.MAX_GLOBAL_CONCURRENCY) {
                return ShizukuShellResponse.rejected(ShizukuShellLimits.CONCURRENCY_LIMIT)
            }
            seenCallIds += normalized.callId

            val pipes = try {
                PipeSet.create()
            } catch (_: IOException) {
                return ShizukuShellResponse.rejected(ShizukuShellLimits.UNAVAILABLE)
            }
            val session = RunningShell(normalized, pipes)
            active[normalized.callId] = session
            try {
                workerPool.execute { runSession(session) }
            } catch (_: RuntimeException) {
                active.remove(normalized.callId)
                pipes.closeAll()
                return ShizukuShellResponse.rejected(ShizukuShellLimits.UNAVAILABLE)
            }
            return ShizukuShellResponse.accepted(
                stdoutFd = pipes.stdoutRead,
                stderrFd = pipes.stderrRead,
                resultFd = pipes.resultRead,
            )
        }
    }

    override fun cancel(callId: String): Boolean {
        val session = active[callId] ?: return false
        val accepted = synchronized(session.outcomeLock) {
            if (session.processFinishedNormally) {
                false
            } else {
                session.cancelRequested.set(true)
                true
            }
        }
        if (!accepted) return false
        session.process.get()?.let { process -> runCatching { process.destroy() } }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.values.forEach { session ->
            synchronized(session.outcomeLock) {
                if (!session.processFinishedNormally) session.cancelRequested.set(true)
            }
            session.process.get()?.let { process -> runCatching { process.destroy() } }
        }
        workerPool.shutdownNow()
        pumpPool.shutdownNow()
        active.clear()
    }

    private fun runSession(session: RunningShell) {
        val startedAt = System.nanoTime()
        var process: Process? = null
        var stdoutStats = PumpStats.EMPTY
        var stderrStats = PumpStats.EMPTY
        var stdoutFuture: Future<PumpStats>? = null
        var stderrFuture: Future<PumpStats>? = null
        var exitCode: Int? = null
        var timedOut = false
        var cancelled = false
        var terminated = true
        var errorCode: String? = null
        var processStarted = false
        var processFinishedNormally = false

        try {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                val builder = ProcessBuilder(listOf("/system/bin/sh", "-s"))
                    .redirectErrorStream(false)
                session.request.cwd?.let { cwd -> builder.directory(File(cwd)) }
                process = builder.start()
                processStarted = true
                session.process.set(process)

                stdoutFuture = pumpPool.submit<PumpStats> {
                    pump(process!!.inputStream, session.pipes.stdoutWrite, session.request.maxStdoutBytes)
                }
                stderrFuture = pumpPool.submit<PumpStats> {
                    pump(process!!.errorStream, session.pipes.stderrWrite, session.request.maxStderrBytes)
                }

                if (session.cancelRequested.get()) {
                    cancelled = true
                    terminated = terminate(process)
                } else {
                    try {
                        process.outputStream.use { stdin ->
                            val command = strictUtf8(session.request.command)
                            stdin.write(command)
                            stdin.flush()
                        }
                    } catch (_: IOException) {
                        if (session.cancelRequested.get()) {
                            cancelled = true
                        } else {
                            errorCode = ShizukuShellLimits.EXECUTION_FAILED
                        }
                        terminated = terminate(process)
                    }

                    if (errorCode == null && process.isAlive) {
                        val finished = try {
                            process.waitFor(session.request.timeoutMs, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            false
                        }
                        if (!finished) {
                            timedOut = !session.cancelRequested.get()
                            cancelled = session.cancelRequested.get()
                            terminated = terminate(process)
                        } else {
                            synchronized(session.outcomeLock) {
                                if (session.cancelRequested.get()) {
                                    cancelled = true
                                } else {
                                    processFinishedNormally = true
                                    session.processFinishedNormally = true
                                }
                            }
                            terminated = !process.isAlive
                        }
                    } else if (process != null && !process.isAlive) {
                        synchronized(session.outcomeLock) {
                            if (session.cancelRequested.get()) {
                                cancelled = true
                            } else {
                                processFinishedNormally = true
                                session.processFinishedNormally = true
                            }
                        }
                        terminated = true
                    }
                }
                if (process != null && !process.isAlive) {
                    exitCode = runCatching { process.exitValue() }.getOrNull()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelled = session.cancelRequested.get()
            errorCode = if (cancelled) ShizukuShellLimits.CANCELLED else ShizukuShellLimits.EXECUTION_FAILED
            process?.let { terminated = terminate(it) }
        } catch (_: IOException) {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                errorCode = ShizukuShellLimits.EXECUTION_FAILED
            }
            process?.let { terminated = if (it.isAlive) terminate(it) else true }
        } catch (_: SecurityException) {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                errorCode = ShizukuShellLimits.EXECUTION_FAILED
            }
            process?.let { terminated = if (it.isAlive) terminate(it) else true }
        } catch (_: RuntimeException) {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                errorCode = ShizukuShellLimits.EXECUTION_FAILED
            }
            process?.let { terminated = if (it.isAlive) terminate(it) else true }
        } finally {
            session.process.set(null)
            if (session.cancelRequested.get() && !timedOut && errorCode == null && !processFinishedNormally) {
                cancelled = true
            }
            stdoutStats = awaitPump(stdoutFuture, session.pipes.stdoutWrite)
            stderrStats = awaitPump(stderrFuture, session.pipes.stderrWrite)
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
            if (cancelled && errorCode == null) errorCode = ShizukuShellLimits.CANCELLED
            if (timedOut && errorCode == null) errorCode = ShizukuShellLimits.TIMED_OUT
            if (!processStarted && cancelled) terminated = true

            // Java Process can report the shell itself exited while a pipeline
            // or background child survives.  This runner has no portable
            // process-group wait/kill primitive on API 26+, so a dispatched
            // timeout/cancel is deliberately UNKNOWN even after the shell
            // process was destroyed.  Callers must never replay it.
            val remoteTerminationUnproven = processStarted && (timedOut || cancelled)
            val state = when {
                !terminated -> "UNKNOWN"
                remoteTerminationUnproven -> "UNKNOWN"
                timedOut -> "TIMED_OUT"
                cancelled -> "CANCELLED"
                errorCode != null -> "FAILED"
                else -> "COMPLETED"
            }
            val unknownOutcome = !terminated || remoteTerminationUnproven
            val envelope = JSONObject()
                .put("callId", session.request.callId)
                .put("ok", state == "COMPLETED" && exitCode == 0)
                .put("state", state)
                .put("exitCode", exitCode)
                .put("timedOut", timedOut)
                .put("cancelled", cancelled)
                .put("terminated", terminated)
                .put("unknownOutcome", unknownOutcome)
                .put("stdoutBytes", stdoutStats.totalBytes)
                .put("stderrBytes", stderrStats.totalBytes)
                .put("stdoutTruncated", stdoutStats.truncated)
                .put("stderrTruncated", stderrStats.truncated)
                .put("durationMs", (System.nanoTime() - startedAt) / 1_000_000L)
            errorCode?.let { envelope.put("errorCode", it) }
            writeEnvelope(session.pipes.resultWrite, envelope.toString())
            session.pipes.closeWriters()
            active.remove(session.request.callId, session)
        }
    }

    private fun awaitPump(future: Future<PumpStats>?, output: ParcelFileDescriptor): PumpStats {
        if (future == null) return PumpStats.EMPTY
        return try {
            future.get(ShizukuShellLimits.IPC_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Closing the output descriptor unblocks a reader whose child process
            // inherited the pipe.  The process itself has already been asked to
            // terminate; an incomplete result is therefore marked unknown.
            runCatching { output.close() }
            future.cancel(true)
            PumpStats.EMPTY.copy(truncated = true)
        }
    }

    private fun terminate(process: Process): Boolean {
        runCatching { process.destroy() }
        val exited = runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)
        if (exited || !process.isAlive) return true
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        return !process.isAlive
    }

    private fun pump(input: InputStream, descriptor: ParcelFileDescriptor, maximum: Int): PumpStats {
        var total = 0L
        var written = 0
        var truncated = false
        var output: OutputStream? = null
        try {
            output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (count < 0) break
                if (count == 0) continue
                total += count
                val remaining = maximum - written
                if (remaining > 0) {
                    val toWrite = minOf(remaining, count)
                    try {
                        output?.write(buffer, 0, toWrite)
                        output?.flush()
                        written += toWrite
                    } catch (_: IOException) {
                        // The caller may have cancelled/closed its read end.
                        // Continue draining the process input without buffering.
                        runCatching { output?.close() }
                        output = null
                        written = maximum
                    }
                }
                if (count > remaining) truncated = true
            }
        } finally {
            runCatching { input.close() }
            runCatching { output?.close() }
        }
        return PumpStats(total, truncated)
    }

    private fun writeEnvelope(descriptor: ParcelFileDescriptor, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes, 0, minOf(bytes.size, MAX_ENVELOPE_BYTES))
                output.flush()
            }
        }
    }

    private fun normalize(request: ShizukuShellRunnerRequest): ShizukuShellRunnerRequest? {
        if (request.callId.isBlank()) return null
        val callId = strictUtf8OrNull(request.callId) ?: return null
        if (callId.size > ShizukuShellLimits.MAX_CALL_ID_BYTES) return null
        if (request.command.isBlank()) return null
        val command = strictUtf8OrNull(request.command) ?: return null
        if (command.size > ShizukuShellLimits.MAX_COMMAND_BYTES) return null

        val cwd = request.cwd?.let { raw ->
            val bytes = strictUtf8OrNull(raw) ?: return null
            if (bytes.isEmpty() || bytes.size > ShizukuShellLimits.MAX_CWD_BYTES ||
                !raw.startsWith('/') || raw.contains('\\') || raw.any { it.isISOControl() }
            ) return null
            // normalize() is a second check at the same service boundary;
            // never move this existence check into the app-side bridge.
            val directory = runCatching { File(raw) }.getOrNull() ?: return null
            if (!directory.isDirectory) return null
            raw
        }
        val timeoutMs = when {
            request.timeoutMs < 0L -> return null
            request.timeoutMs == 0L -> ShizukuShellLimits.DEFAULT_TIMEOUT_MS
            else -> minOf(request.timeoutMs, ShizukuShellLimits.MAX_TIMEOUT_MS)
        }
        val stdout = normalizeOutputLimit(request.maxStdoutBytes) ?: return null
        val stderr = normalizeOutputLimit(request.maxStderrBytes) ?: return null
        return request.copy(cwd = cwd, timeoutMs = timeoutMs, maxStdoutBytes = stdout, maxStderrBytes = stderr)
    }

    private fun isValidCwd(raw: String): Boolean {
        val bytes = strictUtf8OrNull(raw) ?: return false
        if (bytes.isEmpty() || bytes.size > ShizukuShellLimits.MAX_CWD_BYTES ||
            !raw.startsWith('/') || raw.contains('\\') || raw.any { it.isISOControl() }
        ) return false
        // Called only after the Binder session has entered this UserService;
        // File.isDirectory is intentionally not used by the app bridge.
        return runCatching { File(raw).isDirectory }.getOrDefault(false)
    }

    private fun normalizeOutputLimit(requested: Int): Int? = when {
        requested < 0 -> null
        requested == 0 -> ShizukuShellLimits.DEFAULT_OUTPUT_BYTES
        else -> minOf(requested, ShizukuShellLimits.MAX_OUTPUT_BYTES)
    }

    private fun strictUtf8OrNull(value: String): ByteArray? = runCatching { strictUtf8(value) }.getOrNull()

    private fun strictUtf8(value: String): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private data class PumpStats(
        val totalBytes: Long,
        val truncated: Boolean,
    ) {
        companion object {
            val EMPTY = PumpStats(0L, false)
        }
    }

    private data class RunningShell(
        val request: ShizukuShellRunnerRequest,
        val pipes: PipeSet,
        val process: AtomicReference<Process?> = AtomicReference(null),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val outcomeLock: Any = Any(),
        var processFinishedNormally: Boolean = false,
    )

    private data class PipeSet(
        val stdoutRead: ParcelFileDescriptor,
        val stdoutWrite: ParcelFileDescriptor,
        val stderrRead: ParcelFileDescriptor,
        val stderrWrite: ParcelFileDescriptor,
        val resultRead: ParcelFileDescriptor,
        val resultWrite: ParcelFileDescriptor,
    ) {
        fun closeWriters() {
            runCatching { stdoutWrite.close() }
            runCatching { stderrWrite.close() }
            runCatching { resultWrite.close() }
        }

        fun closeAll() {
            runCatching { stdoutRead.close() }
            runCatching { stderrRead.close() }
            runCatching { resultRead.close() }
            closeWriters()
        }

        companion object {
            fun create(): PipeSet {
                val stdout = ParcelFileDescriptor.createPipe()
                try {
                    val stderr = ParcelFileDescriptor.createPipe()
                    try {
                        val result = ParcelFileDescriptor.createPipe()
                        return PipeSet(stdout[0], stdout[1], stderr[0], stderr[1], result[0], result[1])
                    } catch (error: Throwable) {
                        stderr.forEach { descriptor -> runCatching { descriptor.close() } }
                        throw error
                    }
                } catch (error: Throwable) {
                    stdout.forEach { descriptor -> runCatching { descriptor.close() } }
                    throw error
                }
            }
        }
    }

    private companion object {
        const val MAX_RETAINED_CALL_IDS = 2048
        const val DEFAULT_BUFFER_BYTES = 16 * 1024
        const val MAX_ENVELOPE_BYTES = 16 * 1024
    }
}
