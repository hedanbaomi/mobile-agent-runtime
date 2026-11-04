// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

/**
 * Kernel-backed subreaper shell runner (`PR_SET_CHILD_SUBREAPER` + `PR_SET_PDEATHSIG` + `setsid` +
 * `waitpid(-1)` until `ECHILD`).
 *
 * When the kernel/runtime cannot prove subreaper support prior to spawn, [execute] fails closed
 * before spawning any process (`status = FAILED`, `requiresDegradedEpoch = false`).
 */
internal class RootSupervisedShellRunner(
    private val backend: RootNativeSupervisorBackend = JniRootNativeSupervisorBackend,
    private val signals: RootProcessSignals = AndroidRootProcessSignals,
    private val defaultGraceMs: Long = 300L,
) {
    private class ActiveCommand(
        val requestId: String,
        val callId: String,
        val cancelHandle: RootNativeSupervisorHandle,
    ) {
        val finishedLatch = CountDownLatch(1)
        @Volatile var settledAfterFinish: Boolean = false
    }

    private data class UnsettledRecord(
        val requestId: String,
        val callId: String,
        val processGroupId: Int,
        val unreapedPid: Int,
        val unreapedStartTicks: Long,
    )

    private val running = ConcurrentHashMap<String, ActiveCommand>()
    private val unconfirmed = ConcurrentHashMap<String, UnsettledRecord>()

    fun isKernelSubreaperSupported(): Boolean = backend.isKernelSubreaperSupported()

    fun execute(request: ShellExecRequest): RootShellRunOutcome {
        if (!backend.isKernelSubreaperSupported()) {
            return preSpawnRejectedOutcome()
        }
        val handle = backend.createCancelHandle() ?: return preSpawnRejectedOutcome()
        val active = ActiveCommand(request.requestId, request.callId, handle)
        running[request.requestId] = active
        running[request.callId] = active
        val maxBytes = request.maxOutputBytes.toInt().coerceIn(1024, 262_144)
        val spec = RootSupervisedCommandSpec(
            command = request.command,
            cwd = request.cwd?.takeIf { it.isNotBlank() },
            environment = defaultSanitizedEnvironment(),
            timeoutMs = request.timeoutMs.coerceAtLeast(50L),
            graceMs = defaultGraceMs,
            maxOutputBytes = maxBytes,
        )
        return try {
            val report = backend.runSupervised(spec, handle)
            if (report.startStatus < 0 && report.startStatus != -3) {
                active.settledAfterFinish = true
                return preSpawnRejectedOutcome()
            }
            active.settledAfterFinish = report.settled
            if (!report.settled) {
                val record = UnsettledRecord(
                    requestId = request.requestId,
                    callId = request.callId,
                    processGroupId = report.processGroupId,
                    unreapedPid = report.unreapedPid,
                    unreapedStartTicks = report.unreapedStartTicks,
                )
                unconfirmed[request.requestId] = record
                unconfirmed[request.callId] = record
            }
            classifyRootShellOutcome(
                RootProcessGroupEvidence(
                    leaderExited = report.leaderExited,
                    exitCode = report.exitCode,
                    timedOut = report.timedOut,
                    cancelled = report.cancelled,
                    leakedDescendants = report.leakedDescendants,
                    settled = report.settled,
                ),
            ).copy(
                stdout = String(report.stdout, StandardCharsets.UTF_8),
                stderr = String(report.stderr, StandardCharsets.UTF_8),
                stdoutTruncated = report.stdoutTruncated,
                stderrTruncated = report.stderrTruncated,
            )
        } finally {
            running.remove(request.requestId, active)
            running.remove(request.callId, active)
            active.finishedLatch.countDown()
            handle.close()
        }
    }

    fun cancel(requestId: String): Boolean {
        val active = running[requestId] ?: return false
        return active.cancelHandle.requestCancel()
    }

    fun terminateAll(graceMs: Long): Set<String> {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs.coerceAtLeast(0L))
        val confirmed = LinkedHashSet<String>()
        val snapshot = running.values.distinct()
        snapshot.forEach { it.cancelHandle.requestCancel() }
        snapshot.forEach { active ->
            val remNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            val finished = runCatching { active.finishedLatch.await(remNanos, TimeUnit.NANOSECONDS) }.getOrDefault(false)
            if (finished && active.settledAfterFinish) {
                unconfirmed.remove(active.requestId)
                unconfirmed.remove(active.callId)
                confirmed += active.requestId
                confirmed += active.callId
            }
        }
        return confirmed
    }

    fun activeRequestIds(): Set<String> = running.keys.toSet()

    fun unconfirmedRequestIds(): Set<String> {
        unconfirmed.entries.toList().forEach { (id, rec) ->
            if (isRecordNowSettled(rec)) {
                unconfirmed.remove(id, rec)
            }
        }
        return unconfirmed.keys.toSet()
    }

    private fun isRecordNowSettled(record: UnsettledRecord): Boolean {
        if (record.unreapedPid <= 0) return false
        val groupAlive = record.processGroupId > 0 && signals.groupMembers(record.processGroupId).isNotEmpty()
        val pidAlive = signals.stat(record.unreapedPid)?.let {
            (record.unreapedStartTicks <= 0L || it.startTime == record.unreapedStartTicks) && !it.isDead
        } == true
        return !groupAlive && !pidAlive
    }

    private fun preSpawnRejectedOutcome(): RootShellRunOutcome =
        RootShellRunOutcome(
            status = ShellExecutionStatus.FAILED,
            terminalOutcome = RootServiceTerminalOutcome.FAILED,
            requiresDegradedEpoch = false,
        )

    private fun defaultSanitizedEnvironment(): Map<String, String> = rootShellEnvironment(System.getenv())
}

/** Keep Android's runtime setup, without inheriting daemon CLASSPATH or loader overrides. */
internal fun rootShellEnvironment(inherited: Map<String, String>): Map<String, String> =
    inherited.filterKeys { it in ROOT_ANDROID_ENVIRONMENT_KEYS } + mapOf(
        "PATH" to "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/vendor/bin:/odm/bin",
        "HOME" to "/data/local/tmp",
        "TMPDIR" to "/data/local/tmp",
        "LANG" to "C.UTF-8",
    )

private val ROOT_ANDROID_ENVIRONMENT_KEYS = setOf(
    "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "STANDALONE_SYSTEMSERVER_JARS",
    "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE", "ANDROID_RUNTIME_ROOT",
    "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "EXTERNAL_STORAGE",
)

data class RootShellRunOutcome(
    val status: ShellExecutionStatus,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val terminalOutcome: RootServiceTerminalOutcome,
    val requiresDegradedEpoch: Boolean = false,
) {
    override fun toString(): String =
        "RootShellRunOutcome(status=$status,exitCode=$exitCode,terminalOutcome=$terminalOutcome,redacted=true)"
}

interface RootShellCommandRunner {
    fun runCommand(
        authority: Authority,
        request: ShellExecRequest,
    ): RootShellRunOutcome

    fun cancel(requestId: String): Boolean

    /**
     * Terminates every in-flight process group and returns the request ids whose whole group
     * (and observed escapees) were confirmed gone within the budget. Unconfirmed ids stay
     * unconfirmed so the forced shutdown reports `UNKNOWN_OUTCOME`/`DEGRADED`.
     */
    fun terminateAll(deadlineMs: Long): Set<String> = emptySet()

    fun activeRequestIds(): Set<String> = emptySet()

    fun unconfirmedRequestIds(): Set<String> = emptySet()
}

/**
 * Direct `UID 0` service shell entry backed by [RootSupervisedShellRunner] (`PR_SET_CHILD_SUBREAPER`
 * + `PR_SET_PDEATHSIG` + `setsid` + `waitpid(-1)` until `ECHILD`).
 *
 * When constructed with only an unproven sampled [RootShellGroupRunner] or when the kernel/runtime
 * cannot prove subreaper support prior to spawn, [runCommand] fails closed before spawning any
 * process (`status = FAILED`, `requiresDegradedEpoch = false`).
 */
class Uid0ProcessGroupShellRunner internal constructor(
    private val groupRunner: RootShellGroupRunner,
    private val supervisedRunner: RootSupervisedShellRunner? = null,
) : RootShellCommandRunner {
    constructor() : this(
        groupRunner = RootShellGroupRunner(),
        supervisedRunner = RootSupervisedShellRunner(),
    )

    internal constructor(supervisedRunner: RootSupervisedShellRunner) : this(
        groupRunner = RootShellGroupRunner(),
        supervisedRunner = supervisedRunner,
    )

    override fun runCommand(
        authority: Authority,
        request: ShellExecRequest,
    ): RootShellRunOutcome {
        if (authority != Authority.ROOT_DIRECT && authority != Authority.SHIZUKU_ROOT) {
            return RootShellRunOutcome(
                status = ShellExecutionStatus.FAILED,
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
                requiresDegradedEpoch = false,
            )
        }
        val supervisor = supervisedRunner ?: return RootShellRunOutcome(
            status = ShellExecutionStatus.FAILED,
            terminalOutcome = RootServiceTerminalOutcome.FAILED,
            requiresDegradedEpoch = false,
        )
        return supervisor.execute(request)
    }

    override fun cancel(requestId: String): Boolean =
        supervisedRunner?.cancel(requestId) ?: groupRunner.cancel(requestId)

    override fun terminateAll(deadlineMs: Long): Set<String> =
        supervisedRunner?.terminateAll(deadlineMs) ?: groupRunner.terminateAll(deadlineMs)

    override fun activeRequestIds(): Set<String> =
        supervisedRunner?.activeRequestIds() ?: groupRunner.activeRequestIds()

    override fun unconfirmedRequestIds(): Set<String> =
        supervisedRunner?.unconfirmedRequestIds() ?: groupRunner.unconfirmedRequestIds()
}
