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
 * reported as UNKNOWN_OUTCOME even when the shell process itself exits.  The
 * runner still sweeps the remote /proc tree on terminate — descendants have
 * been observed to outlive destroy() and keep running against the recorded
 * UNKNOWN outcome.
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
    // Serializes the spawn-time /proc/self children diff so concurrent
    // sessions can never attribute another spawn's child to their own shell.
    private val spawnIdentityLock = Any()

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
        terminate(session)
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.values.forEach { session ->
            synchronized(session.outcomeLock) {
                if (!session.processFinishedNormally) session.cancelRequested.set(true)
            }
            terminate(session)
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
                process = synchronized(spawnIdentityLock) {
                    val childrenBefore = ownChildren()
                    val spawned = builder.start()
                    session.remoteIdentity.set(boundRemoteIdentity(childrenBefore, ownChildren()))
                    spawned
                }
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
                    terminated = terminate(session)
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
                        terminated = terminate(session)
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
                            terminated = terminate(session)
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
            run { terminated = terminate(session) }
        } catch (_: IOException) {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                errorCode = ShizukuShellLimits.EXECUTION_FAILED
            }
            run { terminated = if (process?.isAlive == true) terminate(session) else true }
        } catch (_: SecurityException) {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                errorCode = ShizukuShellLimits.EXECUTION_FAILED
            }
            run { terminated = if (process?.isAlive == true) terminate(session) else true }
        } catch (_: RuntimeException) {
            if (session.cancelRequested.get()) {
                cancelled = true
            } else {
                errorCode = ShizukuShellLimits.EXECUTION_FAILED
            }
            run { terminated = if (process?.isAlive == true) terminate(session) else true }
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
            // or background child survives.  The /proc sweep on terminate is a
            // best effort — it cannot prove the whole remote tree is gone — so
            // a dispatched timeout/cancel is deliberately UNKNOWN even after
            // the shell process was destroyed.  Callers must never replay it.
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

    private fun terminate(session: RunningShell): Boolean {
        // destroy() only signals the direct child and has been observed to
        // leave the remote shell and its descendants running after a timeout.
        // The tree is enumerated BEFORE the root dies — its children reparent
        // to init and become undiscoverable — and every (pid, start-time) pair
        // is captured at discovery, so a pid recycled between enumeration and
        // the kill compares against the discovery snapshot, not against
        // itself.  The root identity comes from the spawn-time binding, never
        // re-derived, so an already-exited or recycled root selects no tree at
        // all.  Only processes this run spawned are in scope.  The whole sweep
        // is serialized per session so racing cancel/timeout/close callers
        // cannot interleave a second enumeration between snapshot and kill.
        synchronized(session.terminateLock) {
            val process = session.process.get() ?: return true
            val identity = session.remoteIdentity.get()
            val captured = if (identity != null) {
                remoteDescendantsOf(identity, ::remoteChildrenOf, ::remoteStat)
            } else {
                emptyList()
            }
            runCatching { process.destroy() }
            val exited = runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited && process.isAlive) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            }
            if (identity != null) {
                sweepRemoteTree(captured, identity, ::remoteChildrenOf, ::remoteStat, signal = { pid ->
                    runCatching { android.os.Process.killProcess(pid) }
                })
            }
            if (process.isAlive && identity != null &&
                remoteStat(identity.pid)?.second == identity.startTime
            ) {
                runCatching { android.os.Process.killProcess(identity.pid) }
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            }
            return !process.isAlive
        }
    }

    /**
     * Bind the spawned direct child by diffing this process's own /proc
     * children across the fork.  Exactly one new child is expected; zero
     * (already exited) or several (concurrent unrelated spawns) degrade to
     * null, which skips the remote sweep rather than guessing an identity.
     */
    private fun boundRemoteIdentity(before: Set<Int>, after: Set<Int>): RemoteIdentity? {
        val pid = (after - before).singleOrNull() ?: return null
        val startTime = remoteStat(pid)?.second ?: return null
        return RemoteIdentity(pid, startTime)
    }

    private fun ownChildren(): Set<Int> {
        val tasks = runCatching { File("/proc/self/task").listFiles() }.getOrNull() ?: return emptySet()
        val pids = HashSet<Int>()
        tasks.asSequence().filter { it.isDirectory }.forEach { task ->
            runCatching {
                File(task, "children").readText().split(' ').forEach { token ->
                    token.trim().toIntOrNull()?.let(pids::add)
                }
            }
        }
        return pids
    }

    /**
     * One atomic /proc/<pid>/stat read → (ppid, start-time).  Pairing both
     * fields from a single read is what makes lineage checks meaningful: a
     * recycled pid holder reports a different start-time, and a foreign
     * process reports a ppid that does not match the verified parent.
     */
    private fun remoteStat(pid: Int): Pair<Int, Long>? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        // Fields after the closing parenthesis of comm: state(3) ppid(4) ...
        // starttime(22) → indices 0,1,...,19 relative to the split.
        val fields = stat.substringAfterLast(')').trim().split(' ')
        fields[1].toInt() to fields[19].toLong()
    }.getOrNull()

    /**
     * Direct child pids of [pid] — bare pids only.  Callers read each child's
     * own stat atomically and require its ppid to equal the verified parent
     * pid, so a pid recycled between the children listing and the stat read
     * is rejected instead of being paired with a foreign process's
     * start-time.
     */
    private fun remoteChildrenOf(pid: Int): Sequence<Int> {
        val tasks = runCatching { File("/proc/$pid/task").listFiles() }.getOrNull()
            ?: return emptySequence()
        return tasks.asSequence()
            .filter { it.isDirectory }
            .flatMap { task ->
                runCatching {
                    File(task, "children").readText()
                        .split(' ')
                        .asSequence()
                        .mapNotNull { it.trim().toIntOrNull() }
                }.getOrDefault(emptySequence())
            }
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
        // Remote identity of the spawned shell, bound at spawn time from the
        // /proc/self children diff — never re-derived later, so a recycled pid
        // can never stand in for the process this run actually owns.  Written
        // before `process` is published so a cancel/close racing spawn sees
        // both or neither.
        val remoteIdentity: AtomicReference<RemoteIdentity?> = AtomicReference(null),
        val terminateLock: Any = Any(),
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

/**
 * Spawn-time identity of a remote process: pid plus the /proc start-time
 * captured in the same moment, so later checks compare against the identity
 * this run actually created — not whatever happens to hold the pid now. */
internal data class RemoteIdentity(val pid: Int, val startTime: Long)

/**
 * A verified descendant node captured at discovery: its own atomic
 * (ppid, start-time) stat snapshot plus the parent's identity it was
 * enumerated under.  The recorded ppid is lineage evidence — the node only
 * entered the tree because its own stat claimed this parent.
 */
internal data class RemoteNode(
    val pid: Int,
    val startTime: Long,
    val ppid: Int,
    val parentStartTime: Long,
)

/**
 * Descendants of [root] in post-order (deepest first), verified per level.
 * [childrenOf] supplies bare child pids; [statOf] reads one proc stat as
 * (ppid, start-time).  A child is collected only when its own atomic stat
 * reports ppid == the parent's pid — a pid recycled between the children
 * listing and the stat read belongs to a foreign process and is rejected —
 * AND while the parent still holds its recorded start-time, so a recycled
 * parent's children listing is never trusted and its whole branch is
 * dropped.  The root itself is not returned; callers signal it separately.
 */
internal fun remoteDescendantsOf(
    root: RemoteIdentity,
    childrenOf: (Int) -> Sequence<Int>,
    statOf: (Int) -> Pair<Int, Long>?,
): List<RemoteNode> {
    val visited = HashSet<Int>()
    visited.add(root.pid)
    val order = ArrayList<RemoteNode>()
    fun visit(parent: RemoteNode) {
        if (statOf(parent.pid)?.second != parent.startTime) return
        childrenOf(parent.pid).forEach { childPid ->
            if (childPid in visited) return@forEach
            val (ppid, start) = statOf(childPid) ?: return@forEach
            if (ppid != parent.pid) return@forEach
            if (!visited.add(childPid)) return@forEach
            val node = RemoteNode(childPid, start, parent.pid, parent.startTime)
            visit(node)
            order.add(node)
        }
    }
    visit(RemoteNode(root.pid, root.startTime, ppid = -1, parentStartTime = -1L))
    return order
}

/**
 * Signal every captured node whose identity still verifies: its own atomic
 * stat must show the recorded start-time (a recycled pid fails) and its ppid
 * must equal the recorded parent — or init (1), the normal reparent target
 * for our tree's orphans.  Ancestors are re-verified up to [root]; an
 * ancestor that still exists but reports a different start-time (recycled)
 * vetoes the whole descendant branch, while a dead ancestor doesn't — its
 * reparented children are legitimately ours.  Survivors are re-enumerated
 * per pass for [maxPasses] rounds so descendants forked during the sweep
 * window are still collected; the sweep remains best-effort, which is why
 * callers keep reporting UNKNOWN_OUTCOME rather than claiming the remote
 * tree is gone.
 */
internal fun sweepRemoteTree(
    captured: List<RemoteNode>,
    root: RemoteIdentity,
    childrenOf: (Int) -> Sequence<Int>,
    statOf: (Int) -> Pair<Int, Long>?,
    signal: (Int) -> Unit,
    maxPasses: Int = 2,
) {
    val byPid = HashMap<Int, RemoteNode>()
    captured.forEach { byPid[it.pid] = it }
    val seen = HashSet(byPid.keys)
    var frontier = captured
    var pass = 0
    while (frontier.isNotEmpty() && pass < maxPasses) {
        pass++
        for (node in frontier) {
            val stat = statOf(node.pid) ?: continue
            if (stat.second != node.startTime) continue
            if (stat.first != node.ppid && stat.first != 1) continue
            var vetoed = false
            var ancestor = node.ppid
            var hops = 0
            while (hops++ < 64) {
                if (ancestor == root.pid) {
                    vetoed = statOf(root.pid)?.second != root.startTime
                    break
                }
                val parentNode = byPid[ancestor] ?: break
                val parentStat = statOf(ancestor)
                if (parentStat != null && parentStat.second != parentNode.startTime) {
                    vetoed = true
                    break
                }
                ancestor = parentNode.ppid
            }
            if (!vetoed) signal(node.pid)
        }
        val discovered = ArrayList<RemoteNode>()
        for (node in frontier) {
            if (statOf(node.pid)?.second != node.startTime) continue
            for (childPid in childrenOf(node.pid)) {
                if (childPid in seen) continue
                val (ppid, start) = statOf(childPid) ?: continue
                if (ppid != node.pid) continue
                if (!seen.add(childPid)) continue
                val child = RemoteNode(childPid, start, node.pid, node.startTime)
                byPid[childPid] = child
                discovered.add(child)
            }
        }
        frontier = discovered
    }
}
