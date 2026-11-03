// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

/** Evidence collected for one shell process group before a terminal state is reported. */
internal data class RootProcessGroupEvidence(
    val leaderExited: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val leakedDescendants: Boolean,
    val settled: Boolean,
)

/**
 * A direct child exiting is not completion. Any leaked group member, an observed `setsid`/
 * `setpgid` escapee, or a group census that could not be verified keeps the call in
 * `UNKNOWN_OUTCOME` with `requiresDegradedEpoch`, so the session refuses new dispatch.
 */
internal fun classifyRootShellOutcome(evidence: RootProcessGroupEvidence): RootShellRunOutcome {
    if (evidence.timedOut) {
        return RootShellRunOutcome(
            status = ShellExecutionStatus.TIMED_OUT,
            exitCode = evidence.exitCode,
            timedOut = true,
            terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
            requiresDegradedEpoch = true,
        )
    }
    if (!evidence.settled || evidence.leakedDescendants || !evidence.leaderExited) {
        return RootShellRunOutcome(
            status = ShellExecutionStatus.UNKNOWN_OUTCOME,
            exitCode = evidence.exitCode,
            cancelled = evidence.cancelled,
            terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
            requiresDegradedEpoch = true,
        )
    }
    return when {
        evidence.cancelled -> RootShellRunOutcome(
            status = ShellExecutionStatus.CANCELLED,
            exitCode = evidence.exitCode,
            cancelled = true,
            terminalOutcome = RootServiceTerminalOutcome.CANCELLED,
        )
        evidence.exitCode == 0 -> RootShellRunOutcome(
            status = ShellExecutionStatus.SUCCEEDED,
            exitCode = 0,
            terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
        )
        else -> RootShellRunOutcome(
            status = ShellExecutionStatus.FAILED,
            exitCode = evidence.exitCode,
            terminalOutcome = RootServiceTerminalOutcome.FAILED,
        )
    }
}

/**
 * Production group-confirmed shell runner used inside the `UID 0` service.
 *
 * Each command runs in its own session/process group. While it runs, descendants are sampled so
 * an active `setsid`/`setpgid` escape is noticed; on exit, timeout or cancel the whole group and
 * every observed escapee are terminated and the group census must become empty before the call
 * is allowed to report success.
 */
class RootShellGroupRunner(
    private val signals: RootProcessSignals = AndroidRootProcessSignals,
    private val killGroup: (Int) -> Unit = { groupId ->
        runCatching { Os.kill(-groupId, OsConstants.SIGKILL) }
    },
    private val killPid: (Int) -> Unit = { pid ->
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
    },
    private val settleBudgetMs: Long = 2_000L,
    private val sampleEveryMs: Long = 100L,
    /** Test seam: bind the direct child pid where `Process.pid()` is unavailable. */
    private val childPidResolver: ((Process) -> Int)? = null,
) {
    private class Running(val process: Process, val pid: Int, val requestId: String, val callId: String) {
        @Volatile var groupId: Int = 0
        @Volatile var groupObserved: Boolean = false
        @Volatile var cancelRequested: Boolean = false
        val escaped = ConcurrentHashMap<Int, Long>()
    }

    private val running = ConcurrentHashMap<String, Running>()
    private val unconfirmed = ConcurrentHashMap<String, Running>()
    private val spawnLock = Any()

    fun execute(builder: ProcessBuilder, request: ShellExecRequest): RootShellRunOutcome {
        val spawned = spawn(builder) ?: return RootShellRunOutcome(
            status = ShellExecutionStatus.FAILED,
            terminalOutcome = RootServiceTerminalOutcome.FAILED,
        )
        val process = spawned.first
        val command = Running(process, spawned.second, request.requestId, request.callId)
        running[request.requestId] = command
        running[request.callId] = command
        val maxBytes = request.maxOutputBytes.toInt().coerceIn(1024, 262_144)
        val outBuffer = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val errBuffer = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val outTruncated = AtomicBoolean(false)
        val errTruncated = AtomicBoolean(false)
        val outReader = startDrainThread("mar-root-stdout", process.inputStream, maxBytes, outBuffer, outTruncated)
        val errReader = startDrainThread("mar-root-stderr", process.errorStream, maxBytes, errBuffer, errTruncated)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs)
        var leaderExited = false
        val leaked = AtomicBoolean(false)
        try {
            while (true) {
                if (process.waitFor(minOf(sampleEveryMs, 50L), TimeUnit.MILLISECONDS)) {
                    leaderExited = true
                    break
                }
                observe(command, leaked)
                if (command.cancelRequested) break
                if (System.nanoTime() >= deadlineNanos) break
            }
            observe(command, leaked)
            if (!leaderExited) terminateGroupAndEscapees(command)
            val settled = awaitSettled(command)
            if (!settled || leaked.get() || !leaderExited || !command.groupObserved) {
                unconfirmed[request.requestId] = command
                unconfirmed[request.callId] = command
            }
            runCatching { process.waitFor(500L, TimeUnit.MILLISECONDS) }
            outReader.join(500L)
            errReader.join(500L)
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            return classifyRootShellOutcome(
                RootProcessGroupEvidence(
                    leaderExited = leaderExited,
                    exitCode = exitCode,
                    timedOut = !leaderExited && !command.cancelRequested,
                    cancelled = command.cancelRequested,
                    leakedDescendants = leaked.get(),
                    settled = settled && command.groupObserved,
                ),
            ).withOutput(outBuffer, errBuffer, outTruncated.get(), errTruncated.get())
        } finally {
            running.remove(request.requestId)
            running.remove(request.callId)
        }
    }

    fun cancel(requestId: String): Boolean {
        val command = running[requestId] ?: return false
        command.cancelRequested = true
        runCatching { observe(command, AtomicBoolean(false)) }
        terminateGroupAndEscapees(command)
        return true
    }

    fun terminateAll(graceMs: Long): Set<String> {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs.coerceAtLeast(0L))
        val confirmed = LinkedHashSet<String>()
        running.values.distinct().forEach { command ->
            command.cancelRequested = true
            runCatching { observe(command, AtomicBoolean(false)) }
            terminateGroupAndEscapees(command)
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (awaitSettled(command, remainingMs.coerceAtLeast(0L))) {
                unconfirmed.remove(command.requestId)
                unconfirmed.remove(command.callId)
                confirmed += command.requestId
            }
        }
        return confirmed
    }

    fun activeRequestIds(): Set<String> = running.keys.toSet()

    fun unconfirmedRequestIds(): Set<String> {
        unconfirmed.entries.toList().forEach { (id, command) ->
            if (awaitSettled(command, 0L)) unconfirmed.remove(id, command)
        }
        return unconfirmed.keys.toSet()
    }

    private fun observe(command: Running, leaked: AtomicBoolean) {
        val pid = command.pid
        val leader = signals.stat(pid) ?: return
        if (leader.pgrp <= 0) return
        command.groupId = leader.pgrp
        command.groupObserved = true
        val parents = ArrayList<Int>()
        parents += pid
        signals.groupMembers(leader.pgrp).forEach { parents += it.pid }
        parents.forEach { parent ->
            signals.childrenOf(parent).forEach { childPid ->
                val child = signals.stat(childPid) ?: return@forEach
                if (child.pgrp != leader.pgrp) {
                    command.escaped[childPid] = child.startTime
                    leaked.set(true)
                }
            }
        }
        if (signals.groupMembers(leader.pgrp).isNotEmpty() && !command.process.isAlive) leaked.set(true)
    }

    /** Spawn and bind the direct child identity without relying on `Process.pid()`. */
    private fun spawn(builder: ProcessBuilder): Pair<Process, Int>? = synchronized(spawnLock) {
        val childrenBefore = signals.selfChildren()
        val started = runCatching { builder.start() }.getOrNull() ?: return@synchronized null
        val childrenAfter = signals.selfChildren()
        val pid = (childPidResolver?.invoke(started) ?: childPidOf(started)).takeIf { it > 0 }
            ?: (childrenAfter - childrenBefore).singleOrNull()
            ?: -1
        started to pid
    }

    /** `java.lang.Process.pid()` is not available at this compileSdk; read the same value reflectively. */
    private fun childPidOf(process: Process): Int = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrDefault(-1)

    private fun terminateGroupAndEscapees(command: Running) {
        if (command.groupId > 0) killGroup(command.groupId)
        command.escaped.forEach { (pid, startTime) ->
            if (signals.stat(pid)?.startTime == startTime) killPid(pid)
        }
    }

    private fun awaitSettled(command: Running, budgetMs: Long = settleBudgetMs): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        while (true) {
            val members = if (command.groupId > 0) signals.groupMembers(command.groupId) else emptyList()
            val escapeesAlive = command.escaped.any { (pid, startTime) ->
                signals.stat(pid)?.let { it.startTime == startTime && !it.isDead } == true
            }
            if (members.isEmpty() && !escapeesAlive && !command.process.isAlive) return true
            if (System.nanoTime() >= deadlineNanos) return false
            terminateGroupAndEscapees(command)
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    private fun startDrainThread(
        name: String,
        stream: InputStream,
        maxBytes: Int,
        sink: ByteArrayOutputStream,
        truncated: AtomicBoolean,
    ): Thread = Thread({
        val chunk = ByteArray(8192)
        try {
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                val remaining = maxBytes - sink.size()
                if (remaining > 0) {
                    val toKeep = minOf(read, remaining)
                    sink.write(chunk, 0, toKeep)
                    if (read > toKeep) truncated.set(true)
                } else {
                    truncated.set(true)
                }
            }
        } catch (_: Throwable) {
            // Stream closed on process termination.
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    private fun RootShellRunOutcome.withOutput(
        out: ByteArrayOutputStream,
        err: ByteArrayOutputStream,
        outTruncated: Boolean,
        errTruncated: Boolean,
    ): RootShellRunOutcome = copy(
        stdout = String(out.toByteArray(), StandardCharsets.UTF_8),
        stderr = String(err.toByteArray(), StandardCharsets.UTF_8),
        stdoutTruncated = outTruncated,
        stderrTruncated = errTruncated,
    )
}

/**
 * Legacy bounded direct-child runner kept for the plain `ProcessBuilder` seam used by existing
 * regression tests. It does not claim process-group confirmation; production Root shell paths
 * use [RootShellGroupRunner].
 */
internal object BoundedProcessGroupRunner {
    fun executeProcess(
        builder: ProcessBuilder,
        request: ShellExecRequest,
        inFlightProcesses: ConcurrentHashMap<String, java.lang.Process>,
    ): RootShellRunOutcome {
        request.cwd?.takeIf { it.isNotBlank() }?.let { cwdPath ->
            val dir = File(cwdPath)
            if (dir.isDirectory) builder.directory(dir)
        }
        val process = try {
            builder.start()
        } catch (_: Throwable) {
            return RootShellRunOutcome(
                status = ShellExecutionStatus.FAILED,
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
            )
        }
        inFlightProcesses[request.requestId] = process
        inFlightProcesses[request.callId] = process
        val maxBytes = request.maxOutputBytes.toInt().coerceIn(1024, 262_144)
        val outBuf = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val errBuf = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val outTrunc = AtomicBoolean(false)
        val errTrunc = AtomicBoolean(false)
        val outReader = drain("mar-root-stdout", process.inputStream, maxBytes, outBuf, outTrunc)
        val errReader = drain("mar-root-stderr", process.errorStream, maxBytes, errBuf, errTrunc)
        return try {
            val finished = process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outReader.join(200)
                errReader.join(200)
                return RootShellRunOutcome(
                    status = ShellExecutionStatus.TIMED_OUT,
                    stdout = String(outBuf.toByteArray(), StandardCharsets.UTF_8),
                    stderr = String(errBuf.toByteArray(), StandardCharsets.UTF_8),
                    stdoutTruncated = outTrunc.get(),
                    stderrTruncated = errTrunc.get(),
                    timedOut = true,
                    terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                    requiresDegradedEpoch = true,
                )
            }
            outReader.join(500)
            errReader.join(500)
            val exit = process.exitValue()
            RootShellRunOutcome(
                status = if (exit == 0) ShellExecutionStatus.SUCCEEDED else ShellExecutionStatus.FAILED,
                exitCode = exit,
                stdout = String(outBuf.toByteArray(), StandardCharsets.UTF_8),
                stderr = String(errBuf.toByteArray(), StandardCharsets.UTF_8),
                stdoutTruncated = outTrunc.get(),
                stderrTruncated = errTrunc.get(),
                terminalOutcome = if (exit == 0) {
                    RootServiceTerminalOutcome.SUCCEEDED
                } else {
                    RootServiceTerminalOutcome.FAILED
                },
            )
        } catch (_: Throwable) {
            process.destroyForcibly()
            RootShellRunOutcome(
                status = ShellExecutionStatus.UNKNOWN_OUTCOME,
                terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                requiresDegradedEpoch = true,
            )
        } finally {
            inFlightProcesses.remove(request.requestId)
            inFlightProcesses.remove(request.callId)
        }
    }

    private fun drain(
        name: String,
        stream: InputStream,
        maxBytes: Int,
        sink: ByteArrayOutputStream,
        truncated: AtomicBoolean,
    ): Thread = Thread({
        val chunk = ByteArray(8192)
        try {
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                val remaining = maxBytes - sink.size()
                if (remaining > 0) {
                    val toKeep = minOf(read, remaining)
                    sink.write(chunk, 0, toKeep)
                    if (read > toKeep) truncated.set(true)
                } else {
                    truncated.set(true)
                }
            }
        } catch (_: Throwable) {
            // Stream closed on process termination.
        }
    }, name).apply {
        isDaemon = true
        start()
    }
}
