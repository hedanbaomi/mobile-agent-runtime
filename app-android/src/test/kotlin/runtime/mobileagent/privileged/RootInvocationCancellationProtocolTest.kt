// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

/** Actual pipe client/server/engine/session; synthetic OS identity and runner, not device evidence. */
class RootInvocationCancellationProtocolTest {
    @Test
    fun pipeCancellationDoesNotConfirmAnInterruptIgnoringWorker() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                started.countDown()
                while (release.count > 0) {
                    try { check(release.await(5, TimeUnit.SECONDS)) }
                    catch (_: InterruptedException) { interrupted.countDown() }
                }
                return RootShellRunOutcome(ShellExecutionStatus.SUCCEEDED, exitCode = 0,
                    terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED)
            }
            override fun cancel(requestId: String) = false
        }
        Fixture(runner).use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val envelope = f.envelope("ignores-interrupt")
            assertEquals("ACCEPTED", f.transport.submitEnvelope(1, envelope.canonicalJson())?.submitStage)
            val work = pool.submit<RootServiceExecuteResponse?> { f.transport.executeAccepted(envelope.requestId) }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFalse(f.transport.cancelInvocation(f.cancel(envelope.invocationId)),
                    "Recording intent and sending interrupt must not confirm a still-running worker")
                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                assertFalse(work.isDone)
                assertEquals(RootServiceExecutionStage.RUNNING, f.engine.activeSession!!.ledger.observe(envelope.requestId)?.stage)
                release.countDown()
                assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, work.get(5, TimeUnit.SECONDS)?.terminalOutcome)
            } finally {
                release.countDown(); pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun pipeCancellationWhileWorkerResponseIsBlockedStopsOnlyExactInvocation() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val starts = AtomicInteger()
        val runner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                starts.incrementAndGet()
                if (request.callId == "inv-slow") {
                    started.countDown()
                    try { releaseResponse.await() } catch (_: InterruptedException) {
                        stopped.countDown()
                        check(releaseResponse.await(5, TimeUnit.SECONDS))
                    }
                }
                return RootShellRunOutcome(ShellExecutionStatus.SUCCEEDED, exitCode = 0,
                    stdout = "private-late-value", terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED)
            }
            override fun cancel(requestId: String) = false // Interrupt is observed by the independent service worker.
        }
        Fixture(runner).use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val slow = f.envelope("slow")
            assertEquals("ACCEPTED", f.transport.submitEnvelope(1, slow.canonicalJson())?.submitStage)
            val response = pool.submit<RootServiceExecuteResponse?> { f.transport.executeAccepted(slow.requestId) }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFalse(f.transport.cancelInvocation(f.cancel("inv-slow")), "Response-blocked worker has not confirmed termination")
                assertTrue(stopped.await(5, TimeUnit.SECONDS), "Remote worker must stop while its response remains blocked")
                val other = f.envelope("other")
                assertEquals("ACCEPTED", f.transport.submitEnvelope(2, other.canonicalJson())?.submitStage)
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, f.transport.executeAccepted(other.requestId)?.terminalOutcome)
                releaseResponse.countDown()
                val result = response.get(5, TimeUnit.SECONDS)
                assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, result?.terminalOutcome)
                assertNull(result?.shellOutcome, "Unconfirmed cancellation must withhold late successful content")
                assertEquals(2, starts.get())
            } finally {
                releaseResponse.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun acceptedButUnstartedCancellationHasZeroRunnerStarts() {
        val starts = AtomicInteger()
        Fixture(successRunner(starts)).use { f ->
            val envelope = f.envelope("unstarted")
            assertEquals("ACCEPTED", f.transport.submitEnvelope(1, envelope.canonicalJson())?.submitStage)
            assertTrue(f.transport.cancelInvocation(f.cancel(envelope.invocationId)))
            f.transport.executeAccepted(envelope.requestId)
            assertEquals(0, starts.get())
            assertEquals(RootServiceTerminalOutcome.CANCELLED, f.engine.activeSession!!.ledger.observe(envelope.requestId)?.terminalOutcome)
            assertEquals("DUPLICATE_SAME_DIGEST", f.transport.submitEnvelope(2, envelope.canonicalJson())?.submitStage)
        }
    }

    @Test
    fun acceptanceAndAliasPublicationOwnTheSameCancellationGate() {
        val starts = AtomicInteger()
        Fixture(successRunner(starts)).use { f ->
            val envelope = f.envelope("atomic")
            f.engine.afterAcceptedBeforePublish = {
                assertTrue(Thread.holdsLock(f.engine.activeSession!!), "ACCEPT and alias publication must share the cancellation gate")
                // Reentrant boundary injection: later publication must not revive the ticket.
                assertTrue(f.engine.cancelInvocation(RootPrivilegedServiceEngine.json.encodeToString(f.cancel(envelope.invocationId)), 10148))
            }
            assertEquals("ACCEPTED", f.transport.submitEnvelope(1, envelope.canonicalJson())?.submitStage)
            f.transport.executeAccepted(envelope.requestId)
            assertEquals(0, starts.get())
            assertEquals(RootServiceTerminalOutcome.CANCELLED, f.engine.activeSession!!.ledger.observe(envelope.requestId)?.terminalOutcome)
        }
    }

    @Test
    fun tombstonesRetainCancelledIdsAndRefuseNewAdmissionAtCapacity() {
        Fixture(successRunner(AtomicInteger())).use { f ->
            repeat(RootControlLedger.MAX_SESSION_REQUESTS) { assertTrue(f.transport.cancelInvocation(f.cancel("inv-$it"))) }
            assertFalse(f.transport.cancelInvocation(f.cancel("inv-overflow")))
            val first = f.envelope("0")
            val rejected = f.transport.submitEnvelope(1, first.canonicalJson())!!
            assertEquals("REJECTED_BEFORE_ACCEPTED", rejected.submitStage)
            assertEquals(RootNonAcceptanceReason.INVOCATION_CANCELLED, rejected.rejectedProof?.reason)
            assertTrue(RootNonAcceptanceVerifier.verify(rejected.rejectedProof!!, first, f.identity))
            val fresh = f.envelope("fresh")
            assertEquals(RootNonAcceptanceReason.CAPACITY_EXHAUSTED,
                f.transport.submitEnvelope(2, fresh.canonicalJson())?.rejectedProof?.reason)
            assertEquals(0, f.engine.activeSession!!.ledger.acceptedCount())
        }
    }

    @Test
    fun fullTombstoneCapacityStillCancelsAlreadyAcceptedInvocation() {
        val starts = AtomicInteger()
        Fixture(successRunner(starts)).use { f ->
            val accepted = f.envelope("accepted-full")
            assertEquals("ACCEPTED", f.transport.submitEnvelope(1, accepted.canonicalJson())?.submitStage)
            repeat(RootControlLedger.MAX_SESSION_REQUESTS) { assertTrue(f.transport.cancelInvocation(f.cancel("inv-$it"))) }
            assertTrue(f.transport.cancelInvocation(f.cancel(accepted.invocationId)))
            f.transport.executeAccepted(accepted.requestId)
            assertEquals(0, starts.get())
        }
    }

    @Test
    fun staleCancellationCannotInterruptTheNextTaskOnTheSameWorker() {
        val aStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val cancelLookedUpA = CountDownLatch(1)
        val resumeCancel = CountDownLatch(1)
        val bInterrupted = java.util.concurrent.atomic.AtomicBoolean()
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val runner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                threads += Thread.currentThread()
                if (request.callId == "inv-reuse-a") {
                    aStarted.countDown(); check(releaseA.await(5, TimeUnit.SECONDS))
                } else {
                    bStarted.countDown()
                    try { check(releaseB.await(5, TimeUnit.SECONDS)) }
                    catch (_: InterruptedException) { bInterrupted.set(true) }
                }
                return RootShellRunOutcome(ShellExecutionStatus.SUCCEEDED, exitCode = 0,
                    terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED)
            }
            override fun cancel(requestId: String) = false
        }
        Fixture(runner).use { f ->
            // A single real pool worker makes reuse deterministic; engine lifecycle is unchanged.
            val worker = Executors.newSingleThreadExecutor()
            val canceller = Executors.newSingleThreadExecutor()
            val a = f.envelope("reuse-a")
            val b = f.envelope("reuse-b")
            f.transport.submitEnvelope(1, a.canonicalJson())
            f.transport.submitEnvelope(2, b.canonicalJson())
            f.engine.afterCancelWorkerLookup = { id ->
                if (id == a.requestId) {
                    cancelLookedUpA.countDown(); check(resumeCancel.await(5, TimeUnit.SECONDS))
                }
            }
            val aResult = worker.submit<RootServiceExecuteResponse> {
                RootPrivilegedServiceEngine.json.decodeFromString(f.engine.executeAccepted(a.requestId, ByteArray(0), 10148))
            }
            try {
                assertTrue(aStarted.await(5, TimeUnit.SECONDS))
                val cancel = canceller.submit<Boolean> { f.transport.cancelInvocation(f.cancel(a.invocationId)) }
                assertTrue(cancelLookedUpA.await(5, TimeUnit.SECONDS))
                releaseA.countDown()
                assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, aResult.get(5, TimeUnit.SECONDS).terminalOutcome)
                val bResult = worker.submit<RootServiceExecuteResponse> {
                    RootPrivilegedServiceEngine.json.decodeFromString(f.engine.executeAccepted(b.requestId, ByteArray(0), 10148))
                }
                assertTrue(bStarted.await(5, TimeUnit.SECONDS))
                assertSame(threads[0], threads[1], "The production engine must protect actual thread reuse")
                resumeCancel.countDown()
                assertFalse(cancel.get(5, TimeUnit.SECONDS), "A still has an unknown outcome")
                releaseB.countDown()
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, bResult.get(5, TimeUnit.SECONDS).terminalOutcome)
                assertFalse(bInterrupted.get(), "Cancellation of A must not signal B's reused worker")
            } finally {
                releaseA.countDown(); releaseB.countDown(); resumeCancel.countDown()
                worker.shutdownNow(); canceller.shutdownNow()
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(canceller.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun successRunner(starts: AtomicInteger) = object : RootShellCommandRunner {
        override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
            starts.incrementAndGet()
            return RootShellRunOutcome(ShellExecutionStatus.SUCCEEDED, exitCode = 0,
                terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED)
        }
        override fun cancel(requestId: String) = false
    }

    private class Fixture(runner: RootShellCommandRunner) : AutoCloseable {
        private val signing = "a".repeat(64)
        private fun node(inode: Long) = RootFdNodeIdentity(1, inode, 77, true, false, false, 2, 448, 10148, 10148, "label")
        private val boundary = RootControlPlaneBoundary("/data/user/0/runtime.mobileagent", node(9001),
            "/data/user_de/0/runtime.mobileagent", node(9002))
        val engine = RootPrivilegedServiceEngine(shellRunner = runner, serviceUidProvider = { 0 },
            controlPlaneOverride = boundary).pinTestHost(boundary, signing)
        private val clientOutput = PipedOutputStream()
        private val serverInput = PipedInputStream(clientOutput, 65_536)
        private val serverOutput = PipedOutputStream()
        private val clientInput = PipedInputStream(serverOutput, 65_536)
        private val server = Thread {
            RootPrivilegedProcessMain.serveStreamLoop(engine, DataInputStream(serverInput), DataOutputStream(serverOutput), 10148)
        }.apply { isDaemon = true; start() }
        val transport = SuRootDaemonTransport(input = DataInputStream(clientInput), output = DataOutputStream(clientOutput))
        val identity = requireNotNull(transport.handshake(RootServiceHandshakeRequest(Authority.ROOT_DIRECT, 1, 10148, 0,
            "app-instance-0001", "runtime.mobileagent", signing, signing,
            boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir))?.identity)
        init {
            assertTrue(engine.activeSession!!.bindWorkspaceHandle(RootCallerObservation(10148, 0, "runtime.mobileagent", 0, signing),
                "workspace", "workspace-generation-0001", "workspace-handle-0001"))
        }
        fun cancel(invocation: String) = RootCancelInvocationRequest(targetServiceInstanceId = identity.serviceInstanceId,
            targetSessionId = identity.sessionId, targetIdentityEpoch = identity.identityEpoch, invocationId = invocation)
        fun envelope(suffix: String): RootControlEnvelope {
            val args = RootTypedArguments.ShellExec("workspace", "fixture", null)
            return RootControlEnvelope(requestId = "request-$suffix", runId = "run-$suffix", invocationId = "inv-$suffix",
                snapshotId = "snapshot", grantId = "grant", grantBeforeRevision = 1, grantAfterRevision = 2,
                once = true, grantConsumedAt = "2026-10-30T00:00:00Z", execution = RootExecutionStamp(identity, 1, 1, 1),
                capability = CapabilityId(CapabilityId.SHELL_EXECUTE), toolName = "shell_exec", workspaceId = "workspace",
                workspaceGenerationId = "workspace-generation-0001", workspaceSessionHandle = "workspace-handle-0001",
                relativePathScope = null, arguments = args, rawArgumentsSha256 = "b".repeat(64),
                normalizedArgumentsSha256 = args.canonicalSha256(), commandHash = RootPrivilegedServiceEngine.sha256Hex(args.command))
        }
        override fun close() {
            transport.close()
            server.join(5_000)
            assertFalse(server.isAlive, "Pipe server leaked")
            engine.close()
        }
    }
}
