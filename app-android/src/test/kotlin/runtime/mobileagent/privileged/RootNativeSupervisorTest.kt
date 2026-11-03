// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellLimits

class RootNativeSupervisorTest {

    private data class SimProcess(
        val pid: Int,
        @Volatile var ppid: Int,
        @Volatile var pgid: Int,
        @Volatile var sid: Int,
        val startTicks: Long,
        @Volatile var state: Char = 'S', // 'S' running, 'D' uninterruptible, 'Z' zombie, 'X' reaped
        @Volatile var exitCode: Int = 0,
        @Volatile var isSubreaper: Boolean = false,
        @Volatile var pdeathSig: Int = 0,
        val killedSignals: MutableList<Int> = mutableListOf(),
    )

    /**
     * Deterministic model of Linux `PR_SET_CHILD_SUBREAPER` + `PR_SET_PDEATHSIG` + `setsid` +
     * `waitpid(-1)` + `/proc/<pid>/stat` startTicks verification implemented in `mar_root_supervisor.c`.
     */
    private class SimulatedLinuxSubreaperKernel(
        var kernelSupported: Boolean = true,
    ) : RootNativeSupervisorBackend, RootProcessSignals {
        private val nextPid = AtomicInteger(2000)
        private var nextTicks = 10_000L
        val procs = linkedMapOf<Int, SimProcess>()
        val spawnCount = AtomicInteger(0)
        var scriptedScenario: ((SimKernelSession) -> Unit)? = null

        inner class SimCancelHandle : RootNativeSupervisorHandle {
            val cancelled = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            override fun requestCancel(): Boolean {
                if (closed.get()) return false
                cancelled.set(true)
                latch.countDown()
                return true
            }

            override fun close() {
                closed.set(true)
                latch.countDown()
            }
        }

        inner class SimKernelSession(
            val serviceProc: SimProcess,
            val supProc: SimProcess,
            val cmdProc: SimProcess,
            val spec: RootSupervisedCommandSpec,
            val cancelHandle: SimCancelHandle,
        ) {
            private val stdoutPipe = ByteArrayOutputStream()
            private val stderrPipe = ByteArrayOutputStream()
            var stdoutTruncated = false
            var stderrTruncated = false

            @Synchronized
            fun forkChild(parentPid: Int, newSession: Boolean = false, dState: Boolean = false): SimProcess {
                val p = procs[parentPid] ?: error("Parent $parentPid missing")
                val pid = nextPid.incrementAndGet()
                val ticks = ++nextTicks
                val pgid = if (newSession) pid else p.pgid
                val sid = if (newSession) pid else p.sid
                val child = SimProcess(
                    pid = pid,
                    ppid = parentPid,
                    pgid = pgid,
                    sid = sid,
                    startTicks = ticks,
                    state = if (dState) 'D' else 'S',
                )
                procs[pid] = child
                return child
            }

            /**
             * Models Linux `exit_notify` -> `find_new_reaper`: when [pid] exits without reaping its
             * children, every child of [pid] is atomically reparented to the nearest ancestor with
             * `isSubreaper == true` (which is [supProc]), never escaping to PID 1.
             */
            @Synchronized
            fun exitProcess(pid: Int, code: Int = 0) {
                val proc = procs[pid] ?: return
                if (proc.state == 'Z' || proc.state == 'X') return
                proc.state = 'Z'
                proc.exitCode = code
                val reaperPid = findNearestSubreaperAncestor(proc.ppid)
                procs.values.filter { it.ppid == pid && it.state != 'X' }.forEach { child ->
                    child.ppid = reaperPid
                    if (child.pdeathSig != 0 && child.state == 'S') {
                        child.killedSignals += child.pdeathSig
                        exitProcess(child.pid, 128 + child.pdeathSig)
                    }
                }
            }

            /** Models `kill -9 <service_pid>`: closes `ctrl_pipe` and fires `PR_SET_PDEATHSIG` on `sup_pid`. */
            @Synchronized
            fun killServiceProcess() {
                serviceProc.state = 'X'
                if (supProc.pdeathSig != 0) {
                    supProc.killedSignals += supProc.pdeathSig
                }
                cancelHandle.requestCancel()
                supervisorTerminateAndReapAll(skipGrace = true)
                supProc.state = 'X'
            }

            fun writeStdout(bytes: ByteArray) {
                val rem = (spec.maxOutputBytes - stdoutPipe.size()).coerceAtLeast(0)
                if (rem > 0) {
                    val toWrite = minOf(bytes.size, rem)
                    stdoutPipe.write(bytes, 0, toWrite)
                    if (bytes.size > toWrite) stdoutTruncated = true
                } else if (bytes.isNotEmpty()) {
                    stdoutTruncated = true
                }
            }

            fun writeStderr(bytes: ByteArray) {
                val rem = (spec.maxOutputBytes - stderrPipe.size()).coerceAtLeast(0)
                if (rem > 0) {
                    val toWrite = minOf(bytes.size, rem)
                    stderrPipe.write(bytes, 0, toWrite)
                    if (bytes.size > toWrite) stderrTruncated = true
                } else if (bytes.isNotEmpty()) {
                    stderrTruncated = true
                }
            }

            @Synchronized
            private fun findNearestSubreaperAncestor(startPpid: Int): Int {
                var cur = startPpid
                while (cur > 1) {
                    val p = procs[cur] ?: return 1
                    if (p.isSubreaper && p.state != 'X') return p.pid
                    cur = p.ppid
                }
                return 1
            }

            @Synchronized
            fun supervisorSignalTree(sig: Int, pidSnapshot: Map<Int, Long>? = null) {
                val targets = procs.values.filter { p ->
                    p.pid != supProc.pid && p.pid != serviceProc.pid && p.state != 'X' &&
                        isDescendantOfSup(p.pid)
                }
                targets.forEach { target ->
                    val expectedTicks = pidSnapshot?.get(target.pid) ?: target.startTicks
                    val current = procs[target.pid] ?: return@forEach
                    if (current.startTicks != expectedTicks || current.state == 'X') {
                        return@forEach // PID reuse protection: startTicks mismatch refuses signal!
                    }
                    current.killedSignals += sig
                    if (current.state == 'S') {
                        exitProcess(current.pid, 128 + sig)
                    }
                }
            }

            @Synchronized
            private fun isDescendantOfSup(pid: Int): Boolean {
                var cur = procs[pid] ?: return false
                if (cur.pgid == cmdProc.pgid) return true
                var depth = 0
                while (cur.ppid > 1 && depth++ < 32) {
                    if (cur.ppid == supProc.pid) return true
                    cur = procs[cur.ppid] ?: break
                }
                return false
            }

            @Synchronized
            fun supervisorReapAvailable(
                onLeaderReaped: (Int) -> Unit,
                onReparentedReaped: (Int) -> Unit,
            ): Boolean {
                val zombies = procs.values.filter { it.ppid == supProc.pid && it.state == 'Z' }
                zombies.forEach { z ->
                    z.state = 'X'
                    if (z.pid == cmdProc.pid) {
                        onLeaderReaped(z.exitCode)
                    } else {
                        onReparentedReaped(z.pid)
                    }
                }
                val remaining = procs.values.any {
                    it.pid != supProc.pid && it.state != 'X' && isDescendantOfSup(it.pid)
                }
                return !remaining // true == ECHILD (subreaper tree completely empty)
            }

            @Synchronized
            fun supervisorTerminateAndReapAll(skipGrace: Boolean): Boolean {
                if (!skipGrace) {
                    supervisorSignalTree(15) // SIGTERM
                }
                supervisorSignalTree(9) // SIGKILL
                return supervisorReapAvailable({}, {})
            }

            fun stdoutBytes(): ByteArray = stdoutPipe.toByteArray()
            fun stderrBytes(): ByteArray = stderrPipe.toByteArray()
        }

        override fun isKernelSubreaperSupported(): Boolean = kernelSupported

        override fun createCancelHandle(): RootNativeSupervisorHandle? =
            if (kernelSupported) SimCancelHandle() else null

        override fun runSupervised(
            spec: RootSupervisedCommandSpec,
            cancelHandle: RootNativeSupervisorHandle,
        ): RootSupervisedCommandReport {
            if (!kernelSupported) {
                return RootSupervisedCommandReport(
                    startStatus = -1, leaderExited = false, exitCode = null, timedOut = false,
                    cancelled = false, leakedDescendants = false, settled = true,
                    stdout = ByteArray(0), stderr = ByteArray(0),
                    stdoutTruncated = false, stderrTruncated = false,
                )
            }
            if (spec.cwd == "/nonexistent/invalid/cwd") {
                return RootSupervisedCommandReport(
                    startStatus = -2, leaderExited = false, exitCode = null, timedOut = false,
                    cancelled = false, leakedDescendants = false, settled = true,
                    stdout = ByteArray(0), stderr = ByteArray(0),
                    stdoutTruncated = false, stderrTruncated = false,
                )
            }
            spawnCount.incrementAndGet()
            val serviceProc = SimProcess(1000, 1, 1000, 1000, ++nextTicks)
            val supPid = nextPid.incrementAndGet()
            val supProc = SimProcess(
                pid = supPid, ppid = serviceProc.pid, pgid = serviceProc.pgid, sid = serviceProc.sid,
                startTicks = ++nextTicks, isSubreaper = true, pdeathSig = 15,
            )
            val cmdPid = nextPid.incrementAndGet()
            val cmdProc = SimProcess(
                pid = cmdPid, ppid = supPid, pgid = cmdPid, sid = cmdPid,
                startTicks = ++nextTicks, pdeathSig = 9,
            )
            synchronized(this) {
                procs[serviceProc.pid] = serviceProc
                procs[supPid] = supProc
                procs[cmdPid] = cmdProc
            }
            val simCancel = cancelHandle as SimCancelHandle
            val session = SimKernelSession(serviceProc, supProc, cmdProc, spec, simCancel)
            scriptedScenario?.invoke(session)

            var leaderExited = false
            var leaderExitCode: Int? = null
            var leakedDescendants = false
            var timedOut = false
            var cancelled = simCancel.cancelled.get()

            var empty = session.supervisorReapAvailable(
                onLeaderReaped = { code ->
                    leaderExited = true
                    leaderExitCode = code
                },
                onReparentedReaped = { leakedDescendants = true },
            )

            if (!leaderExited && !cancelled) {
                val signaled = simCancel.latch.await(spec.timeoutMs, TimeUnit.MILLISECONDS)
                if (signaled && simCancel.cancelled.get()) {
                    cancelled = true
                } else {
                    timedOut = true
                }
            }

            empty = session.supervisorReapAvailable(
                onLeaderReaped = { code ->
                    leaderExited = true
                    leaderExitCode = code
                },
                onReparentedReaped = { leakedDescendants = true },
            )

            if (leaderExited && !timedOut && !cancelled && !empty) {
                leakedDescendants = true
            }

            if (!empty) {
                session.supervisorSignalTree(15)
                session.supervisorSignalTree(9)
                empty = session.supervisorReapAvailable(
                    onLeaderReaped = { code ->
                        if (!leaderExited) {
                            leaderExited = true
                            leaderExitCode = code
                        }
                    },
                    onReparentedReaped = { leakedDescendants = true },
                )
            }

            val survivors = synchronized(this) {
                procs.values.filter { it.ppid == supPid && it.state != 'Z' && it.state != 'X' }
            }
            val singleSurvivor = survivors.singleOrNull()
            return RootSupervisedCommandReport(
                startStatus = 0,
                leaderExited = leaderExited,
                exitCode = leaderExitCode,
                timedOut = timedOut,
                cancelled = cancelled,
                leakedDescendants = leakedDescendants,
                settled = empty,
                stdout = session.stdoutBytes(),
                stderr = session.stderrBytes(),
                stdoutTruncated = session.stdoutTruncated,
                stderrTruncated = session.stderrTruncated,
                processGroupId = cmdPid,
                unreapedPid = singleSurvivor?.pid ?: 0,
                unreapedStartTicks = singleSurvivor?.startTicks ?: 0L,
            )
        }

        override fun stat(pid: Int): RootProcStat? = synchronized(this) {
            val p = procs[pid] ?: return null
            if (p.state == 'X') return null
            RootProcStat(p.pid, p.state, p.ppid, p.pgid, p.sid, p.startTicks)
        }

        override fun oomScoreAdj(pid: Int): Int? = 0

        override fun groupMembers(pgrp: Int): List<RootProcStat> = synchronized(this) {
            procs.values.filter { it.pgid == pgrp && it.state != 'X' }.map {
                RootProcStat(it.pid, it.state, it.ppid, it.pgid, it.sid, it.startTicks)
            }
        }

        override fun childrenOf(pid: Int): List<Int> = synchronized(this) {
            procs.values.filter { it.ppid == pid && it.state != 'X' }.map { it.pid }
        }

        override fun selfChildren(): Set<Int> = emptySet()
    }

    private fun makeRequest(
        callId: String = "call-w3",
        command: String = "echo ok",
        cwd: String? = null,
        timeoutMs: Long = 1_000L,
        maxOutputBytes: Long = 4_096L,
    ): ShellExecRequest = ShellExecRequest.fromRuntime(
        callId = callId,
        command = command,
        cwd = cwd,
        limits = ShellLimits(timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes),
        agentId = "agent",
        snapshotId = "snap",
        selectedAuthority = Authority.ROOT_DIRECT,
        dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
        policyVersion = 1L,
        configSnapshotHash = "a".repeat(64),
        sessionIdentity = "ses",
        toolSchemaVersion = 1,
    )

    @Test
    fun kernelUnsupportedAndInvalidCwdRejectBeforeSpawnWithoutDegradingEpoch() {
        val kernel = SimulatedLinuxSubreaperKernel(kernelSupported = false)
        val runner = RootSupervisedShellRunner(backend = kernel, signals = kernel)
        val outcome = runner.execute(makeRequest(command = "id"))
        assertEquals(0, kernel.spawnCount.get())
        assertEquals(ShellExecutionStatus.FAILED, outcome.status)
        assertEquals(RootServiceTerminalOutcome.FAILED, outcome.terminalOutcome)
        assertFalse(outcome.requiresDegradedEpoch)

        // Default JNI backend on host JVM also fails closed pre-spawn without throwing.
        val hostRunner = RootSupervisedShellRunner()
        val hostOutcome = hostRunner.execute(makeRequest(command = "id"))
        assertEquals(ShellExecutionStatus.FAILED, hostOutcome.status)
        assertEquals(RootServiceTerminalOutcome.FAILED, hostOutcome.terminalOutcome)
        assertFalse(hostOutcome.requiresDegradedEpoch)

        // Invalid cwd rejected pre-spawn without degrading epoch.
        kernel.kernelSupported = true
        val badCwdOutcome = runner.execute(makeRequest(command = "pwd", cwd = "/nonexistent/invalid/cwd"))
        assertEquals(0, kernel.spawnCount.get())
        assertEquals(ShellExecutionStatus.FAILED, badCwdOutcome.status)
        assertFalse(badCwdOutcome.requiresDegradedEpoch)
    }

    @Test
    fun fastSetsidDoubleForkAndBackgroundChildAreCaughtBySubreaperAndNeverReportSuccess() {
        val kernel = SimulatedLinuxSubreaperKernel(kernelSupported = true)
        val runner = RootSupervisedShellRunner(backend = kernel, signals = kernel)

        // Counterexample 1: Fast setsid double-fork where grandchild exits BEFORE leader exits.
        // Sampling /proc misses this completely, but PR_SET_CHILD_SUBREAPER reparents grandchild to sup_pid.
        kernel.scriptedScenario = { session ->
            val subshell = session.forkChild(session.cmdProc.pid, newSession = false)
            val setsidGrandchild = session.forkChild(subshell.pid, newSession = true)
            session.exitProcess(subshell.pid, 0) // intermediate parent exits -> grandchild reparented to sup_pid
            assertEquals(session.supProc.pid, setsidGrandchild.ppid)
            session.exitProcess(setsidGrandchild.pid, 0) // grandchild exits in 0ms before leader
            session.exitProcess(session.cmdProc.pid, 0)
        }
        val fastDoubleFork = runner.execute(makeRequest(command = "(setsid sh -c 'exit 0' &); exit 0"))
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, fastDoubleFork.status)
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, fastDoubleFork.terminalOutcome)
        assertTrue(fastDoubleFork.requiresDegradedEpoch)
        assertTrue(runner.unconfirmedRequestIds().isEmpty(), "All descendants were reaped (ECHILD)")

        // Counterexample 2: Background child / setsid survivor still alive when leader exits.
        var survivorRef: SimProcess? = null
        kernel.scriptedScenario = { session ->
            val subshell = session.forkChild(session.cmdProc.pid, newSession = false)
            val daemon = session.forkChild(subshell.pid, newSession = true)
            survivorRef = daemon
            session.exitProcess(subshell.pid, 0) // reparents daemon to sup_pid
            session.exitProcess(session.cmdProc.pid, 0) // leader exits 0 while daemon is still running
        }
        val survivingDaemon = runner.execute(makeRequest(command = "(setsid sh -c 'sleep 60' &); exit 0"))
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, survivingDaemon.status)
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, survivingDaemon.terminalOutcome)
        assertTrue(survivingDaemon.requiresDegradedEpoch)
        assertEquals('X', survivorRef!!.state, "Supervisor must terminate and reap the setsid escapee")
        assertTrue(survivorRef!!.killedSignals.contains(15))
    }

    @Test
    fun serviceKillTriggersPdeathSigAndReapsEntireProcessGroupAndSetsidDescendants() {
        val kernel = SimulatedLinuxSubreaperKernel(kernelSupported = true)
        var cmdRef: SimProcess? = null
        var setsidEscapeeRef: SimProcess? = null
        kernel.scriptedScenario = { session ->
            cmdRef = session.cmdProc
            val intermediate = session.forkChild(session.cmdProc.pid, newSession = false)
            val escapee = session.forkChild(intermediate.pid, newSession = true)
            setsidEscapeeRef = escapee
            session.exitProcess(intermediate.pid, 0) // reparented to sup_pid
            // Simulate kill -9 <service_pid> while command and setsid escapee are running!
            session.killServiceProcess()
        }
        val runner = RootSupervisedShellRunner(backend = kernel, signals = kernel)
        val outcome = runner.execute(makeRequest(command = "(setsid sleep 60 &); sleep 60"))
        assertEquals('X', cmdRef!!.state)
        assertEquals('X', setsidEscapeeRef!!.state)
        assertTrue(setsidEscapeeRef!!.killedSignals.contains(9), "Service death must SIGKILL reparented setsid tree")
        assertTrue(outcome.requiresDegradedEpoch)
    }

    @Test
    fun pipeBackpressureAndOutputTruncationCompleteWithoutDeadlock() {
        val kernel = SimulatedLinuxSubreaperKernel(kernelSupported = true)
        kernel.scriptedScenario = { session ->
            // Write 256 KiB stdout and 128 KiB stderr (exceeding 64 KiB pipe buffer and 2048-byte limit)
            val bigOut = ByteArray(256 * 1024) { 'A'.code.toByte() }
            val bigErr = ByteArray(128 * 1024) { 'E'.code.toByte() }
            session.writeStdout(bigOut)
            session.writeStderr(bigErr)
            session.exitProcess(session.cmdProc.pid, 0)
        }
        val runner = RootSupervisedShellRunner(backend = kernel, signals = kernel)
        val outcome = runner.execute(makeRequest(command = "yes", maxOutputBytes = 2048L))
        assertEquals(ShellExecutionStatus.SUCCEEDED, outcome.status)
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, outcome.terminalOutcome)
        assertFalse(outcome.requiresDegradedEpoch)
        assertEquals(2048, outcome.stdout.length)
        assertEquals(2048, outcome.stderr.length)
        assertTrue(outcome.stdoutTruncated)
        assertTrue(outcome.stderrTruncated)
    }

    @Test
    fun timeoutCancelDStateTrackingAndPidReuseProtectionBehaveDeterministically() {
        val kernel = SimulatedLinuxSubreaperKernel(kernelSupported = true)
        val runner = RootSupervisedShellRunner(backend = kernel, signals = kernel)

        // 1. Timeout kills tree and reports TIMED_OUT + requiresDegradedEpoch = true.
        kernel.scriptedScenario = { _ -> /* leader stays alive until timeout */ }
        val timedOut = runner.execute(makeRequest(callId = "call-timeout", timeoutMs = 60L))
        assertEquals(ShellExecutionStatus.TIMED_OUT, timedOut.status)
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, timedOut.terminalOutcome)
        assertTrue(timedOut.requiresDegradedEpoch)

        // 2. Cancel via terminateAll settles cleanly and returns both requestId and callId.
        val started = CountDownLatch(1)
        kernel.scriptedScenario = { _ -> started.countDown() }
        val cancelReq = makeRequest(callId = "call-cancel", timeoutMs = 5_000L)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<RootShellRunOutcome> {
                runner.execute(cancelReq)
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(runner.activeRequestIds().containsAll(setOf(cancelReq.requestId, "call-cancel")))
            val confirmed = runner.terminateAll(1_000L)
            assertTrue(confirmed.containsAll(setOf(cancelReq.requestId, "call-cancel")))
            val cancelledOutcome = future.get(1, TimeUnit.SECONDS)
            assertEquals(ShellExecutionStatus.CANCELLED, cancelledOutcome.status)
            assertEquals(RootServiceTerminalOutcome.CANCELLED, cancelledOutcome.terminalOutcome)
            assertFalse(cancelledOutcome.requiresDegradedEpoch)
        } finally {
            pool.shutdownNow()
        }

        // 3. Unkillable D-state descendant keeps settled = false and remains in unconfirmedRequestIds
        // until the D-state process finally disappears from /proc; PID reuse with different startTicks is ignored.
        var dStateChild: SimProcess? = null
        var unrelatedReusedProc: SimProcess? = null
        kernel.scriptedScenario = { session ->
            dStateChild = session.forkChild(session.cmdProc.pid, newSession = true, dState = true)
            val normalChild = session.forkChild(session.cmdProc.pid, newSession = true, dState = false)
            val originalTicks = normalChild.startTicks
            // Simulate normalChild exiting and its numeric PID being reused by an unrelated process with new startTicks:
            unrelatedReusedProc = SimProcess(
                pid = normalChild.pid, ppid = 1, pgid = normalChild.pid, sid = normalChild.pid,
                startTicks = originalTicks + 999L, state = 'S',
            )
            kernel.procs[normalChild.pid] = unrelatedReusedProc!!
            session.supervisorSignalTree(9, mapOf(normalChild.pid to originalTicks))
            session.exitProcess(session.cmdProc.pid, 0)
        }
        val dStateReq = makeRequest(callId = "call-dstate")
        val dStateOutcome = runner.execute(dStateReq)
        assertTrue(unrelatedReusedProc!!.killedSignals.isEmpty(), "Reused PID with different startTicks must not be signaled")
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, dStateOutcome.status)
        assertTrue(dStateOutcome.requiresDegradedEpoch)
        assertEquals(setOf(dStateReq.requestId, "call-dstate"), runner.unconfirmedRequestIds())

        // Once the D-state process finally exits on the OS, unconfirmedRequestIds clears.
        dStateChild!!.state = 'X'
        assertTrue(runner.unconfirmedRequestIds().isEmpty())

        // 4. Multiple surviving D-state setsid descendants (unreapedPid == 0) stay fail-closed in unconfirmedRequestIds
        // even if the first one exits while the second one remains alive outside cmd_pgid.
        var multiD1: SimProcess? = null
        var multiD2: SimProcess? = null
        kernel.scriptedScenario = { session ->
            multiD1 = session.forkChild(session.cmdProc.pid, newSession = true, dState = true)
            multiD2 = session.forkChild(session.cmdProc.pid, newSession = true, dState = true)
            session.exitProcess(session.cmdProc.pid, 0)
        }
        val multiReq = makeRequest(callId = "call-multi-dstate")
        val multiOutcome = runner.execute(multiReq)
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, multiOutcome.status)
        assertTrue(multiOutcome.requiresDegradedEpoch)
        multiD1!!.state = 'X' // First D-state exits, second is still alive in its own setsid group
        assertEquals(
            setOf(multiReq.requestId, "call-multi-dstate"),
            runner.unconfirmedRequestIds(),
            "Multiple setsid survivors must never be cleared prematurely when one exits",
        )
        multiD2!!.state = 'X'
    }

    @Test
    fun uid0ProcessGroupShellRunnerExecutesThroughVerifiedSupervisorAndEnforcesAuthorityAndPreSpawnGuards() {
        val kernel = SimulatedLinuxSubreaperKernel(kernelSupported = true)
        kernel.scriptedScenario = { session ->
            session.writeStdout("uid=0(root)\n".toByteArray(StandardCharsets.UTF_8))
            session.exitProcess(session.cmdProc.pid, 0)
        }
        val uid0Runner = Uid0ProcessGroupShellRunner(RootSupervisedShellRunner(backend = kernel, signals = kernel))

        // 1. Non-root authority rejected before spawn without degrading epoch.
        val nonRoot = uid0Runner.runCommand(Authority.NONE, makeRequest(command = "id"))
        assertEquals(0, kernel.spawnCount.get())
        assertEquals(ShellExecutionStatus.FAILED, nonRoot.status)
        assertFalse(nonRoot.requiresDegradedEpoch)

        // 2. ROOT_DIRECT and SHIZUKU_ROOT execute through the verified supervisor.
        val directOut = uid0Runner.runCommand(Authority.ROOT_DIRECT, makeRequest(command = "id"))
        assertEquals(1, kernel.spawnCount.get())
        assertEquals(ShellExecutionStatus.SUCCEEDED, directOut.status)
        assertEquals("uid=0(root)\n", directOut.stdout)
        assertFalse(directOut.requiresDegradedEpoch)

        val shizukuOut = uid0Runner.runCommand(Authority.SHIZUKU_ROOT, makeRequest(command = "id"))
        assertEquals(2, kernel.spawnCount.get())
        assertEquals(ShellExecutionStatus.SUCCEEDED, shizukuOut.status)
        assertEquals("uid=0(root)\n", shizukuOut.stdout)

        // 3. When kernel support is absent, Uid0ProcessGroupShellRunner rejects before spawn.
        kernel.kernelSupported = false
        val unsupported = uid0Runner.runCommand(Authority.ROOT_DIRECT, makeRequest(command = "id"))
        assertEquals(2, kernel.spawnCount.get(), "Must not spawn when kernel subreaper probe fails")
        assertEquals(ShellExecutionStatus.FAILED, unsupported.status)
        assertFalse(unsupported.requiresDegradedEpoch)
    }
}
