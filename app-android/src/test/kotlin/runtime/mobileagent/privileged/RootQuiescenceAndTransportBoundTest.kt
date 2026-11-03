// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootReceiptRecoveryCoordinator
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootQuiescenceQueryRequest
import runtime.mobileagent.domain.RootRemoteCancelStage
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellLimits
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext

/**
 * W1 verification suite (`ROOT06/07/08/16`):
 * - Remote execution leases (worker thread + PFD/stream lease + retained FD + shell descendants)
 * - Two-stage cancellation (`INTERRUPT_SENT`/`CANCEL_RECORDED` vs `QUIESCENT_CONFIRMED`)
 * - Bounded Binder/pipe RPC timeouts and non-deadlocking `close()`
 * - Recovery watermark (`recoveryAckEpoch`) gated on verified remote quiescence proof
 */
class RootQuiescenceAndTransportBoundTest {
    @Test
    fun interruptIgnoringWorkerAndHeldPfdLeaseBlockRecoveryUntilBothRelease() = withSeam { s ->
        val started = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val heldPfdLease = AtomicReference<AutoCloseable?>()
        s.afterWrite = {
            heldPfdLease.set(s.engine.acquireExecutionLeaseHandle("inv-held-pfd"))
            started.countDown()
            while (releaseWorker.count > 0) {
                try {
                    releaseWorker.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Simulate interrupt-ignoring kernel/PFD operation.
                }
            }
        }
        val backend = RootRuntimeToolBackend(s.db, s.coordinator, s.context("run-w1-lease"), s.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val call = ToolCall("inv-held-pfd", "file_write_text", s.writeArgs("lease.txt", "data"))
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        s.recordCall("run-w1-lease", call.callId, "model-held-pfd", call.name, call.argumentsJson)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<ToolResult> { runBlocking { executor.approve(call.callId) } }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(runBlocking { executor.cancel(call.callId) }, "Interrupt-ignoring worker is not confirmed stopped")

            val snapWhileRunning = s.transport.queryQuiescence(
                RootQuiescenceQueryRequest(
                    targetAuthority = s.execution.identity.authority,
                    targetServiceInstanceId = s.execution.identity.serviceInstanceId,
                    targetSessionId = s.execution.identity.sessionId,
                    targetIdentityEpoch = s.execution.identity.identityEpoch,
                    invocationId = call.callId,
                ),
            )!!
            assertTrue(snapWhileRunning.accepted)
            assertFalse(snapWhileRunning.quiescent)
            assertEquals(RootRemoteCancelStage.INTERRUPT_SENT, snapWhileRunning.cancelStage)
            assertTrue(snapWhileRunning.activeWorkerCount >= 1)
            assertTrue(snapWhileRunning.activeStreamLeaseCount >= 2)

            // Recovery acknowledgement is strictly blocked while worker is running
            assertNull(s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true))
            val stateDuringRun = RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT)
            assertTrue(stateDuringRun.degraded)
            assertEquals(0L, stateDuringRun.recoveryAckEpoch)
            assertThrows(IllegalArgumentException::class.java) {
                RootReceiptRecoveryCoordinator(s.db).acknowledgeRecovery(
                    Authority.ROOT_DIRECT,
                    stateDuringRun.revision,
                    stateDuringRun.identityEpoch + 1,
                    true,
                )
            }

            // Release the worker thread, but keep the PFD/stream lease handle open!
            releaseWorker.countDown()
            assertTrue(future.get(5, TimeUnit.SECONDS) is ToolResult.UnknownOutcome)

            val receiptWhilePfdOpen = s.db.query("SELECT stage,outcome,closed FROM root_dispatch_receipts").single()
            assertEquals("TERMINAL", receiptWhilePfdOpen.string("stage"))
            assertEquals("UNKNOWN_OUTCOME", receiptWhilePfdOpen.string("outcome"))
            assertEquals(0L, receiptWhilePfdOpen.long("closed"), "Receipt must stay unclosed while PFD lease is still open")
            assertNull(
                s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true),
                "Held PFD/stream lease must block recovery watermark advancement even after worker thread returns",
            )

            // Now close the held PFD/stream lease handle and verify quiescence + recovery succeed
            heldPfdLease.getAndSet(null)?.close()
            val snapAfterClose = s.transport.queryQuiescence(
                RootQuiescenceQueryRequest(
                    targetAuthority = s.execution.identity.authority,
                    targetServiceInstanceId = s.execution.identity.serviceInstanceId,
                    targetSessionId = s.execution.identity.sessionId,
                    targetIdentityEpoch = s.execution.identity.identityEpoch,
                    invocationId = call.callId,
                ),
            )!!
            assertTrue(snapAfterClose.quiescent)
            assertEquals(RootRemoteCancelStage.QUIESCENT_CONFIRMED, snapAfterClose.cancelStage)

            val recovered = s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true)
            assertNotNull(recovered)
            assertFalse(recovered!!.degraded)
            assertEquals(1L, recovered.recoveryAckEpoch)
            assertEquals(1L, s.db.query("SELECT closed FROM root_dispatch_receipts").single().long("closed"))
        } finally {
            heldPfdLease.getAndSet(null)?.close()
            releaseWorker.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun unconfirmedShellDescendantBlocksQuiescenceAndRecoveryUntilProcessTreeDrains() = withSeam { s ->
        val leakedIds = ConcurrentHashMap.newKeySet<String>()
        s.shellRunnerOverride = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                leakedIds += request.requestId
                leakedIds += request.callId
                return RootShellRunOutcome(
                    status = ShellExecutionStatus.TIMED_OUT,
                    timedOut = true,
                    terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                    requiresDegradedEpoch = true,
                )
            }

            override fun cancel(requestId: String): Boolean = false

            override fun unconfirmedRequestIds(): Set<String> = leakedIds.toSet()
        }

        val shellGrant = s.grants.first { it.capability.value == "shell.execute" }
        val req = ShellExecRequest.fromRuntime(
            callId = "call-leaked-child",
            command = "sleep 60 &",
            cwd = "/data/local/root-workspace",
            limits = ShellLimits(1_000L, 4096L),
            agentId = "agent",
            snapshotId = s.snapshot.id,
            selectedAuthority = Authority.ROOT_DIRECT,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            policyVersion = s.execution.policyVersion,
            configSnapshotHash = "hash",
            sessionIdentity = "conversation",
            toolSchemaVersion = 1,
        )
        assertNotNull(
            approveFixtureShell(
                s.db,
                s.coordinator,
                Authority.ROOT_DIRECT,
                "run-shell-leak",
                req,
                shellGrant.grantId,
                s.workspace.id,
            ),
        )

        val res = s.coordinator.executeControlledShell(Authority.ROOT_DIRECT, req)
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, res.status)
        assertTrue(RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT).degraded)
        assertEquals(0L, s.db.query("SELECT closed FROM root_dispatch_receipts").single().long("closed"))
        assertNull(
            s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true),
            "Surviving unconfirmed shell descendant must block recovery acknowledgement",
        )

        // Once the descendant process is confirmed gone, recovery succeeds
        leakedIds.clear()
        val recovered = s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true)
        assertNotNull(recovered)
        assertFalse(recovered!!.degraded)
        assertEquals(1L, s.db.query("SELECT closed FROM root_dispatch_receipts").single().long("closed"))
    }

    /**
     * N1: a rebuilt controller has empty in-memory session maps. The durable drain obligation
     * recorded when the dispatch degraded the authority must keep the recovery watermark blocked
     * until the leaked session proves quiescence; a missing map is never evidence.
     */
    @Test
    fun rebuiltControllerMustNotAcknowledgeWithoutProofForTheLeakedSession() = withSeam { s ->
        val leakedIds = ConcurrentHashMap.newKeySet<String>()
        s.shellRunnerOverride = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                leakedIds += request.requestId
                leakedIds += request.callId
                return RootShellRunOutcome(
                    status = ShellExecutionStatus.TIMED_OUT,
                    timedOut = true,
                    terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                    requiresDegradedEpoch = true,
                )
            }

            override fun cancel(requestId: String): Boolean = false

            override fun unconfirmedRequestIds(): Set<String> = leakedIds.toSet()
        }

        val shellGrant = s.grants.first { it.capability.value == "shell.execute" }
        val req = ShellExecRequest.fromRuntime(
            callId = "call-leaked-child",
            command = "sleep 60 &",
            cwd = "/data/local/root-workspace",
            limits = ShellLimits(1_000L, 4096L),
            agentId = "agent",
            snapshotId = s.snapshot.id,
            selectedAuthority = Authority.ROOT_DIRECT,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            policyVersion = s.execution.policyVersion,
            configSnapshotHash = "hash",
            sessionIdentity = "conversation",
            toolSchemaVersion = 1,
        )
        assertNotNull(
            approveFixtureShell(
                s.db,
                s.coordinator,
                Authority.ROOT_DIRECT,
                "run-shell-leak",
                req,
                shellGrant.grantId,
                s.workspace.id,
            ),
        )
        val res = s.coordinator.executeControlledShell(Authority.ROOT_DIRECT, req)
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, res.status)
        assertTrue(RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT).degraded)

        // The old process is gone: its in-memory active/retiring sessions and verified snapshots
        // do not exist in the rebuilt controller.
        RootCoordinatorQuiescenceSupport.clearVerifiedSnapshot(Authority.ROOT_DIRECT)
        val restartDir = Files.createTempDirectory("root-recovery-continuity")
        val restarted = RootRuntimeCoordinator(
            FakeTestContext(restartDir.toFile()), s.db,
            fdOps = s.fd,
            sessionConnector = object : RootSessionConnector {
                override fun tryAcquireSession(
                    authority: Authority,
                    identityEpoch: Long,
                    appInstanceId: String,
                    shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
                ): RootAcquiredSession? = null
            },
            controlPlaneBoundaryOverride = s.boundary,
            locatorCipher = TestRootLocatorCipher(),
        )
        try {
            restarted.reconcileStartupRecovery()
            assertNull(
                restarted.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true),
                "A rebuilt controller with no old transport and no proof must stay degraded",
            )
            val state = RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT)
            assertTrue(state.degraded)
            assertEquals(0L, state.recoveryAckEpoch)
            // The second public recovery API must not treat the missing session as no obligation.
            assertThrows(IllegalArgumentException::class.java) {
                RootReceiptRecoveryCoordinator(s.db).acknowledgeRecovery(
                    Authority.ROOT_DIRECT,
                    state.revision,
                    state.identityEpoch,
                    userConfirmed = true,
                )
            }
            assertTrue(RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT).degraded)
        } finally {
            runCatching { restarted.close() }
            restartDir.toFile().deleteRecursively()
        }

        // Only a real drain of the leaked session lets the original controller recover.
        leakedIds.clear()
        val recovered = s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true)
        assertNotNull(recovered)
        assertFalse(recovered!!.degraded)
        assertEquals(1L, s.db.query("SELECT closed FROM root_dispatch_receipts").single().long("closed"))
    }

    @Test
    fun nonReturningBinderCallAndBlockedPipeWriteTimeoutCleanlyAndCloseNeverDeadlocks() {
        val hangLatch = CountDownLatch(1)
        val hungStub = object : IRootPrivilegedService {
            override fun asBinder(): android.os.IBinder? = null
            override fun handshake(requestJson: String): String {
                hangLatch.await(30, TimeUnit.SECONDS)
                return "{}"
            }
            override fun bindWorkspace(requestJson: String): String = "{}"
            override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): String = "{}"
            override fun executeAccepted(requestId: String, payloadBytes: ByteArray?): String {
                hangLatch.await(30, TimeUnit.SECONDS)
                return "{}"
            }
            override fun submitAndExecute(controlSequence: Long, rawEnvelopeJson: String, payloadBytes: ByteArray?): String = "{}"
            override fun cancelCommand(requestId: String): Boolean = false
            override fun cancelInvocation(requestJson: String): Boolean = false
            override fun queryQuiescence(requestJson: String): String = "{}"
            override fun revokeAndShutdown(controlSequence: Long, reason: String): String = "DISCONNECTED"
            override fun destroy() {
                hangLatch.await(30, TimeUnit.SECONDS)
            }
        }
        var unbindCalled = false
        val binderTransport = ShizukuRootBinderTransport(
            service = hungStub,
            controlTimeoutMs = 120L,
            executeTimeoutMs = 120L,
            closeTimeoutMs = 120L,
            onUnbind = { unbindCalled = true },
        )
        val t0 = System.nanoTime()
        assertNull(binderTransport.executeAccepted("req-hung"))
        val elapsedExecMs = (System.nanoTime() - t0) / 1_000_000L
        assertTrue(elapsedExecMs < 1_500L, "Hung Binder executeAccepted must timeout within budget, took ${elapsedExecMs}ms")

        val tClose = System.nanoTime()
        binderTransport.close()
        val elapsedCloseMs = (System.nanoTime() - tClose) / 1_000_000L
        assertTrue(elapsedCloseMs < 1_500L, "Hung Binder destroy() must not deadlock close(), took ${elapsedCloseMs}ms")
        assertTrue(unbindCalled, "onUnbind() must run even when service.destroy() hangs")
        hangLatch.countDown()

        // Blocked pipe write on SuRootDaemonTransport
        val writeHang = CountDownLatch(1)
        val blockedOut = object : OutputStream() {
            override fun write(b: Int) {
                writeHang.await(30, TimeUnit.SECONDS)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                writeHang.await(30, TimeUnit.SECONDS)
            }
            override fun close() {
                writeHang.await(30, TimeUnit.SECONDS)
            }
        }
        val pipeOut = PipedOutputStream()
        var inputClosed = false
        val pipeIn = object : PipedInputStream(pipeOut) {
            override fun close() {
                inputClosed = true
                super.close()
            }
        }
        val suTransport = SuRootDaemonTransport(
            process = null,
            input = DataInputStream(pipeIn),
            output = DataOutputStream(blockedOut),
            controlTimeoutMs = 120L,
            executeTimeoutMs = 120L,
            writeTimeoutMs = 120L,
            closeTimeoutMs = 120L,
        )
        val tPipe = System.nanoTime()
        assertNull(suTransport.submitEnvelope(1L, "{}"))
        val pipeElapsedMs = (System.nanoTime() - tPipe) / 1_000_000L
        assertTrue(pipeElapsedMs < 1_500L, "Blocked pipe write must timeout within budget, took ${pipeElapsedMs}ms")

        val tPipeClose = System.nanoTime()
        suTransport.close()
        val pipeCloseMs = (System.nanoTime() - tPipeClose) / 1_000_000L
        assertTrue(pipeCloseMs < 1_500L, "Blocked pipe close() must return within budget, took ${pipeCloseMs}ms")
        assertTrue(inputClosed, "input.close() must run even when output.close() hangs")
        writeHang.countDown()
        pipeOut.close()
    }

    @Test
    fun preSubmitRevocationAndPostRevokeFileWorkerSettleDeterministically() = withSeam { s ->
        // 1. Pre-submit local cancellation settles as CANCELLED_BEFORE_ATTEMPT without degrading authority
        val backend = RootRuntimeToolBackend(s.db, s.coordinator, s.context("run-pre-cancel"), s.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val preCall = ToolCall("inv-pre-cancel", "file_write_text", s.writeArgs("pre.txt", "data"))
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(preCall) })
        s.recordCall("run-pre-cancel", preCall.callId, "model-pre-cancel", preCall.name, preCall.argumentsJson)
        s.db.execute("UPDATE tool_invocations SET state='PENDING' WHERE invocation_id=?", listOf(preCall.callId))
        val typedPre = requireNotNull(RootToolArguments.normalize(preCall.name, preCall.argumentsJson)).typed
        val grant = s.grants.first { it.capability.value == "file.write_text" }
        assertNotNull(
            s.coordinator.approveFileInvocation(
                Authority.ROOT_DIRECT,
                "run-pre-cancel",
                preCall.callId,
                "model-pre-cancel",
                s.snapshot.id,
                grant.grantId,
                typedPre,
                configSnapshotHash = "config",
                sessionIdentity = "conversation",
            ),
        )
        val prepared = requireNotNull(
            s.coordinator.dispatchBridge.claimApprovedFileDispatch(
                s.execution,
                s.transport,
                typedPre,
                runtime.mobileagent.domain.RootExecutionBudget(),
                preCall.callId,
            ),
        )
        s.coordinator.rootDispatchRepository.cancelUnconfirmedInvocation(s.execution, preCall.callId)
        val gate = runtime.mobileagent.domain.RootHostControlGate(s.execution.identity)
        var degraded = false
        val preRes = s.coordinator.dispatchBridge.submitAndSettle(
            prepared = prepared,
            hostGate = gate,
            transport = s.transport,
            liveIdentity = s.execution.identity,
            payloadBytes = "data".toByteArray(),
            onDegradeRequired = { degraded = true },
        )
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, preRes?.terminalOutcome)
        assertFalse(degraded, "Pre-submit cancellation must not degrade authority")
        val preReceipt = s.coordinator.rootDispatchRepository.getReceipt(prepared.permit.requestId)!!
        assertEquals(runtime.mobileagent.domain.RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT, preReceipt.outcome)
        assertTrue(preReceipt.closed)

        // 2. In-flight file worker finishing after detachSession/revokeAndShutdown reports UNKNOWN_OUTCOME and recovers after drain
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        s.afterWrite = {
            workerStarted.countDown()
            while (releaseWorker.count > 0) {
                try {
                    releaseWorker.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
            }
        }
        val backend2 = RootRuntimeToolBackend(s.db, s.coordinator, s.context("run-revoke-file"), s.execution)
        val executor2 = RootToolExecutor(RootToolArguments.specs(), backend2)
        val call2 = ToolCall("inv-revoke-file", "file_write_text", s.writeArgs("revoke.txt", "data"))
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor2.invoke(call2) })
        s.recordCall("run-revoke-file", call2.callId, "model-revoke-file", call2.name, call2.argumentsJson)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<ToolResult> { runBlocking { executor2.approve(call2.callId) } }
        try {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
            s.coordinator.detachSession(Authority.ROOT_DIRECT, "test_revoke_during_file_op")
            assertNull(
                s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true),
                "Recovery must remain blocked while the file worker is held",
            )
            releaseWorker.countDown()
            assertTrue(future.get(5, TimeUnit.SECONDS) is ToolResult.UnknownOutcome)
            assertTrue(RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT).degraded)
            // UNKNOWN may reach the caller before the transport worker and retire thread finish.
            // Recovery is eventual after verified drain, not synchronous with the caller's result.
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var recovered = s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true)
            while (recovered == null && System.nanoTime() < drainDeadline) {
                Thread.sleep(10L)
                recovered = s.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true)
            }
            assertNotNull(recovered, "Released file worker must drain and permit recovery within the budget")
            assertFalse(recovered!!.degraded)
        } finally {
            releaseWorker.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun withSeam(block: (Seam) -> Unit) {
        val dir = Files.createTempDirectory("root-w1-quiescence")
        InMemoryRootSqlConnection().use { db ->
            val seam = Seam(db, dir.toFile())
            try {
                block(seam)
            } finally {
                // The original controller may represent a dead process after a rebuild test; its
                // best-effort cleanup must not mask the actual assertion.
                runCatching { seam.coordinator.close() }
            }
        }
        dir.toFile().deleteRecursively()
    }

    private class Seam(val db: SqlConnection, dir: java.io.File) {
        val signing = "a".repeat(64)
        val boundary = RootControlPlaneBoundary(
            "/data/user/0/runtime.mobileagent",
            node(9001),
            "/data/user_de/0/runtime.mobileagent",
            node(9002),
        )
        val fd = InMemoryServiceFdOps("/data/local/root-workspace")
        var afterWrite: () -> Unit = {}
        var shellRunnerOverride: RootShellCommandRunner? = null
        private val workerFd = object : PosixFdOperations by fd {
            override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
                this@Seam.fd.writeAllAndFsync(fd, bytes)
                afterWrite()
            }
        }
        private val delegatingShellRunner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome =
                shellRunnerOverride?.runCommand(authority, request)
                    ?: RootShellRunOutcome(ShellExecutionStatus.FAILED, terminalOutcome = RootServiceTerminalOutcome.FAILED)

            override fun cancel(requestId: String): Boolean =
                shellRunnerOverride?.cancel(requestId) ?: false

            override fun activeRequestIds(): Set<String> =
                shellRunnerOverride?.activeRequestIds().orEmpty()

            override fun unconfirmedRequestIds(): Set<String> =
                shellRunnerOverride?.unconfirmedRequestIds().orEmpty()
        }
        val engine = RootPrivilegedServiceEngine(
            fdOps = workerFd,
            shellRunner = delegatingShellRunner,
            serviceUidProvider = { 0 },
            controlPlaneOverride = boundary,
            managerAuthorizationOverride = { managerAuthorization },
        ).pinTestHost(boundary, signing)
        val transport = EngineRootChannelTransport(engine, 10148)
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        /** Production-shaped connector over the in-memory engine transport. */
        /** Test-controlled manager observation used by the engine override below. */
        @Volatile
        var managerAuthorization: RootManagerAuthorization = RootManagerAuthorization.GRANTED

        /** Acquisition/ownership counters for the extraction regressions. */
        @Volatile
        var acquisitions: Int = 0
        @Volatile
        var completions: Int = 0
        val connector = object : RootSessionConnector {
            override fun completeAcquisition(authority: Authority, attemptToken: Long): Boolean {
                completions++
                return true
            }

            override fun tryAcquireSession(
                authority: Authority,
                identityEpoch: Long,
                appInstanceId: String,
                shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
            ): RootAcquiredSession? {
                val req = RootServiceHandshakeRequest(
                    authority, identityEpoch, 10148, 0, appInstanceId,
                    "runtime.mobileagent", signing, signing,
                    boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir,
                )
                val id = transport.handshake(req)?.identity ?: return null
                acquisitions++
                return RootAcquiredSession(id, RootCallerObservation(10148, 0, "runtime.mobileagent", 0, signing), transport, boundary)
            }
        }
        val coordinator: RootRuntimeCoordinator
        val execution: runtime.mobileagent.domain.RootExecutionStamp
        val workspace: Workspace
        val grants: List<CapabilityGrant>
        val snapshot: AgentSnapshot

        init {
            Migrations.apply(db)
            AuthorityPolicyRepository(db).updatePolicy(0, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            val profiles = ProfileRepository(db)
            profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16384, outputLimit = 512, revision = 1))
            AgentRepository(db).saveWithPrompt(AgentProfile("agent", "Root Agent", "pending", "model", revision = 0), "Synthetic prompt")
            coordinator = RootRuntimeCoordinator(
                FakeTestContext(dir),
                db,
                fdOps = fd,
                sessionConnector = connector,
                shellRunner = delegatingShellRunner,
                controlPlaneBoundaryOverride = boundary,
                locatorCipher = TestRootLocatorCipher(),
            )
            coordinator.setUserIntent(Authority.ROOT_DIRECT, true, manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
            coordinator.setRootShellConsent(Authority.ROOT_DIRECT, enabled = true, userConfirmed = true, authorityManager = manager)
            execution = requireNotNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = true))
            val selected = RootWorkspaceUiController(db, coordinator).attach(
                Authority.ROOT_DIRECT, "/data/local/root-workspace", true, true, true, WorkspacePickerTarget("agent"),
            )
            assertTrue(selected is WorkspaceAccessResult.Success)
            workspace = requireNotNull(WorkspaceRepository(db).get((selected as WorkspaceAccessResult.Success).workspace.workspaceId))
            grants = CapabilityGrantRepository(db).forAgent("agent", includeRevoked = false)
            snapshot = coordinator.sealNewSnapshot(Authority.ROOT_DIRECT, "snapshot-w1", "agent", grants.mapTo(mutableSetOf()) { it.grantId }) {
                AgentRepository(db).createSnapshot("agent", "snapshot-w1")
            }
            val now = Utc.nowIso()
            db.execute(
                "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                listOf("conversation", snapshot.id, snapshot.id, "Fixture", now, now),
            )
        }

        fun context(runId: String) = ToolExecutionContext(
            "agent", snapshot.id, "model-context", "conversation", runId,
            "config", execution.policyVersion, canonicalGrants = grants,
            snapshotGrantBindings = CapabilityGrantRepository(db).listSnapshotBindings(snapshot.id),
        )

        fun recordCall(run: String, invocation: String, model: String, tool: String, raw: String) {
            val runs = RunRepository(db)
            RunCoordinator(runs, coordinator::admitNewRun).prepare(RunRecord(run, snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
            db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, run))
            runs.addInvocation(ToolInvocation(invocation, run, model, tool, raw, state = "WAITING_APPROVAL", permissionDecision = "PENDING", createdAt = Utc.nowIso()))
        }

        fun writeArgs(path: String, text: String) = buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", path)
            put("text", text)
        }.toString()

        companion object {
            fun node(inode: Long) = RootFdNodeIdentity(1, inode, 77, true, false, false, 2, 448, 10148, 10148, "label")
        }
    }

    /**
     * F5/F7: the coordinator retires the live session when the bound manager observation reports
     * a revocation, and a slow answer never touches a replacement session (identity fence).
     */
    @Test
    fun revokedManagerObservationRetiresTheLiveSessionAndBlocksDispatch() = withSeam { s ->
        s.coordinator.managerObservationIntervalMs = 100L
        assertTrue(
            s.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT),
            "a bound GRANTED observation must allow dispatch",
        )
        s.managerAuthorization = RootManagerAuthorization.REVOKED
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && s.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT)) {
            Thread.sleep(50L)
        }
        assertFalse(
            s.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT),
            "a bound REVOKED observation must retire the live session",
        )
        assertTrue(
            RootAuthorityRepository(s.db).get(Authority.ROOT_DIRECT).degraded,
            "revocation must degrade the authority",
        )
    }



    /**
     * Extraction regression: a second foreground request while a ready session exists must reuse
     * it (the original returned `isReady()` immediately) and ownership must be completed exactly
     * once per acquisition.
     */
    @Test
    fun secondForegroundRequestReusesTheReadySessionWithoutANewAcquisition() = withSeam { s ->
        val acquisitionsAfterSeam = s.acquisitions
        val completionsAfterSeam = s.completions
        assertTrue(acquisitionsAfterSeam >= 1, "the seam must have acquired one session")
        assertTrue(s.coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null))
        assertEquals(
            acquisitionsAfterSeam,
            s.acquisitions,
            "an existing ready session must not launch another su acquisition",
        )
        assertEquals(
            completionsAfterSeam,
            s.completions,
            "ownership must not be completed twice for one acquisition",
        )
    }

}
