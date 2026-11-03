// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootServiceSessionTest {
    @Test
    fun blockedSubmitDoesNotHoldHostAdmissionMonitor() {
        val gate = RootHostControlGate(identity())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val submit = pool.submit { gate.submitUnderLock { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val observe = pool.submit<Boolean> { gate.isRevoked() }
            assertFalse(observe.get(1, java.util.concurrent.TimeUnit.SECONDS), "A slow RPC must not own the admission monitor")
        } finally {
            release.countDown(); submit.get(5, java.util.concurrent.TimeUnit.SECONDS)
            pool.shutdownNow(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private val workspaceGenerationId = "workspace-gen-0001"
    private val workspaceHandle = "workspace-handle-0001"

    private fun identity(
        epoch: Long = 1L,
        serviceInstanceId: String = "service-instance-001",
        sessionId: String = "session-instance-001",
    ) = PrivilegedSessionIdentity(
        authority = Authority.ROOT_DIRECT,
        privilegeTier = PrivilegeTier.ROOT,
        serviceUid = 0,
        appUid = 10148,
        androidUserId = 0,
        appInstanceId = "app-instance-000001",
        packageName = "runtime.mobileagent",
        signingSha256 = "a".repeat(64),
        identityEpoch = epoch,
        serviceInstanceId = serviceInstanceId,
        sessionId = sessionId,
        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )

    private fun caller(
        callingUid: Int = 10148,
        observedServiceUid: Int = 0,
        packageName: String = "runtime.mobileagent",
        androidUserId: Int = 0,
        signingSha256: String = "a".repeat(64),
        binderAlive: Boolean = true,
    ) = RootCallerObservation(
        callingUid = callingUid,
        observedServiceUid = observedServiceUid,
        packageName = packageName,
        androidUserId = androidUserId,
        signingSha256 = signingSha256,
        binderAlive = binderAlive,
    )

    private fun newSession(
        id: PrivilegedSessionIdentity = identity(),
        parameterPolicyValidator: (RootControlEnvelope) -> Boolean = { true },
        onCancelInFlight: (String) -> Unit = {},
    ): RootServiceSession = RootServiceSession(id, parameterPolicyValidator, onCancelInFlight).also { session ->
        assertTrue(session.bindWorkspaceHandle(caller(), "root-workspace", workspaceGenerationId, workspaceHandle))
    }

    private fun envelope(
        requestId: String,
        id: PrivilegedSessionIdentity = identity(),
        relativePath: String = "private-scope/file.txt",
        handle: String = workspaceHandle,
    ): RootControlEnvelope {
        val args = RootTypedArguments.FileReadText(
            workspaceId = "root-workspace",
            relativePath = relativePath,
            offsetBytes = 0L,
            maxBytes = 4096,
        )
        return RootControlEnvelope(
            requestId = requestId,
            runId = "run-001",
            invocationId = "inv-$requestId",
            snapshotId = "snap-001",
            grantId = "grant-001",
            grantBeforeRevision = 1L,
            grantAfterRevision = 2L,
            once = true,
            grantConsumedAt = "2026-10-30T00:00:00Z",
            execution = RootExecutionStamp(id, consentRevision = 1L, shellConsentRevision = 1L, policyVersion = 1L),
            capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            toolName = "file_read_text",
            workspaceId = "root-workspace",
            workspaceGenerationId = workspaceGenerationId,
            workspaceSessionHandle = handle,
            relativePathScope = "private-scope",
            arguments = args,
            rawArgumentsSha256 = "b".repeat(64),
            normalizedArgumentsSha256 = args.canonicalSha256(),
        )
    }

    private fun submit(
        session: RootServiceSession,
        seq: Long,
        env: RootControlEnvelope,
        obs: RootCallerObservation = caller(),
        targetId: PrivilegedSessionIdentity = session.identity,
    ): RootControlSubmitResult = session.submit(
        caller = obs,
        targetServiceInstanceId = targetId.serviceInstanceId,
        targetSessionId = targetId.sessionId,
        targetIdentityEpoch = targetId.identityEpoch,
        controlSequence = seq,
        rawEnvelopeJson = env.canonicalJson(),
    )

    @Test
    fun strictControlSequenceRejectsReplayAndPermanentlyClosesSessionOnAnyGap() {
        val cancelled = mutableListOf<String>()
        val session = newSession(onCancelInFlight = cancelled::add)
        val env1 = envelope("req-1")
        val env2 = envelope("req-2")
        val env3 = envelope("req-3")

        val zeroSeq = submit(session, 0L, env1)
        assertEquals(
            RootControlSubmitResult.SequenceRejected(
                RootSequenceRejection.REPLAY_OR_REGRESSION,
                lastAcceptedSequence = 0L,
                sessionState = RootServiceSessionState.ACTIVE,
            ),
            zeroSeq,
        )
        assertEquals(0, session.ledger.acceptedCount())

        val accepted1 = submit(session, 1L, env1) as RootControlSubmitResult.Accepted
        assertEquals(1L, session.ledger.lastAcceptedSequence())
        assertTrue(session.ledger.beginExecution(accepted1.ticket))
        var streamClosed = false
        val stream = session.openContentStream(accepted1.ticket) { streamClosed = true }
        assertNotNull(stream)
        assertEquals(1, session.openContentStreamCount())

        val duplicateSeq1 = submit(session, 1L, env2)
        assertEquals(
            RootControlSubmitResult.SequenceRejected(
                RootSequenceRejection.REPLAY_OR_REGRESSION,
                lastAcceptedSequence = 1L,
                sessionState = RootServiceSessionState.ACTIVE,
            ),
            duplicateSeq1,
        )
        assertNull(session.ledger.observe("req-2"))

        // Binder reordering / gap: seq 3 arrives before seq 2
        val gapSeq3 = submit(session, 3L, env3)
        assertEquals(
            RootControlSubmitResult.SequenceRejected(
                RootSequenceRejection.GAP_SESSION_CLOSED,
                lastAcceptedSequence = 1L,
                sessionState = RootServiceSessionState.SEQUENCE_FAULTED,
            ),
            gapSeq3,
        )
        assertEquals(RootServiceSessionState.SEQUENCE_FAULTED, session.ledger.sessionState())
        assertTrue(streamClosed)
        assertEquals(0, session.openContentStreamCount())
        assertEquals(listOf("req-1"), cancelled)
        assertFalse(session.canRotate())

        // Missing seq 2 arriving late cannot patch the gap or execute
        val delayedSeq2 = submit(session, 2L, env2)
        assertEquals(
            RootControlSubmitResult.SequenceRejected(
                RootSequenceRejection.SESSION_CLOSED,
                lastAcceptedSequence = 1L,
                sessionState = RootServiceSessionState.SEQUENCE_FAULTED,
            ),
            delayedSeq2,
        )
        assertNull(session.ledger.observe("req-2"))
    }

    @Test
    fun duplicateRequestIdsReturnStatusOnlyForSameDigestAndRejectDifferentDigest() {
        val session = newSession()
        val env = envelope("req-dup", relativePath = "private-scope/a.txt")
        val accepted = submit(session, 1L, env) as RootControlSubmitResult.Accepted
        assertEquals(RootDuplicateStatus.ALREADY_ACCEPTED, accepted.status.duplicateStatus)

        val dupWhileAccepted = submit(session, 2L, env) as RootControlSubmitResult.DuplicateSameDigest
        assertEquals(RootDuplicateStatus.ALREADY_ACCEPTED, dupWhileAccepted.status.duplicateStatus)
        assertEquals(1, session.ledger.acceptedCount())

        assertTrue(session.ledger.beginExecution(accepted.ticket))
        val dupWhileRunning = submit(session, 3L, env) as RootControlSubmitResult.DuplicateSameDigest
        assertEquals(RootDuplicateStatus.ALREADY_RUNNING, dupWhileRunning.status.duplicateStatus)

        val resultDigest = "e".repeat(64)
        session.ledger.completeExecution(accepted.ticket, RootServiceTerminalOutcome.SUCCEEDED, resultDigest)
        val dupAfterCompleted = submit(session, 4L, env) as RootControlSubmitResult.DuplicateSameDigest
        assertEquals(RootDuplicateStatus.ALREADY_COMPLETED, dupAfterCompleted.status.duplicateStatus)
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, dupAfterCompleted.status.terminalOutcome)
        assertEquals(resultDigest, dupAfterCompleted.status.resultDigestSha256)
        assertEquals(1, session.ledger.acceptedCount())

        val modifiedEnv = envelope("req-dup", relativePath = "private-scope/b.txt")
        val mismatch = submit(session, 5L, modifiedEnv)
        assertEquals(
            RootControlSubmitResult.ReplayBindingMismatch(
                requestId = "req-dup",
                existingStage = RootServiceExecutionStage.TERMINAL,
                controlSequence = 5L,
            ),
            mismatch,
        )
    }

    @Test
    fun enforcesCumulative256UniqueRequestIdLimitWithoutEvictionEvenAfterCompletion() {
        val session = newSession()
        for (i in 1..RootControlLedger.MAX_SESSION_REQUESTS) {
            val env = envelope("req-$i")
            val accepted = submit(session, i.toLong(), env) as RootControlSubmitResult.Accepted
            assertTrue(session.ledger.beginExecution(accepted.ticket))
            session.ledger.completeExecution(accepted.ticket, RootServiceTerminalOutcome.SUCCEEDED)
        }
        assertEquals(256, session.ledger.acceptedCount())
        assertTrue(session.canRotate())

        // Status observations do not consume sequence or capacity
        val observedFirst = session.observeStatus(
            caller(),
            session.identity.serviceInstanceId,
            session.identity.sessionId,
            session.identity.identityEpoch,
            "req-1",
        ) as RootControlObserveResult.Found
        assertEquals(RootDuplicateStatus.ALREADY_COMPLETED, observedFirst.status.duplicateStatus)
        assertEquals(256L, session.ledger.lastAcceptedSequence())

        // 257th unique requestId is rejected before acceptance without evicting any completed entry
        val env257 = envelope("req-257")
        val rejected257 = submit(session, 257L, env257) as RootControlSubmitResult.RejectedBeforeAccepted
        assertEquals(RootNonAcceptanceReason.CAPACITY_EXHAUSTED, rejected257.proof.reason)
        assertTrue(RootNonAcceptanceVerifier.verify(rejected257.proof, env257, session.identity))
        assertEquals(256, session.ledger.acceptedCount())
        assertNull(session.ledger.observe("req-257"))

        // Existing IDs remain in the non-evicted table
        val dup1 = submit(session, 258L, envelope("req-1")) as RootControlSubmitResult.DuplicateSameDigest
        assertEquals(RootDuplicateStatus.ALREADY_COMPLETED, dup1.status.duplicateStatus)
        val mismatch1 = submit(session, 259L, envelope("req-1", relativePath = "private-scope/other.txt"))
        assertTrue(mismatch1 is RootControlSubmitResult.ReplayBindingMismatch)

        // New epoch session starts fresh and rejects old session envelopes without advancing sequence or faulting
        val rotatedIdentity = identity(
            epoch = 2L,
            serviceInstanceId = "service-instance-002",
            sessionId = "session-instance-002",
        )
        val rotatedSession = newSession(rotatedIdentity)
        assertEquals(RootControlSubmitResult.IdentityMismatch, submit(rotatedSession, 1L, env257))
        assertEquals(RootControlSubmitResult.IdentityMismatch, submit(rotatedSession, 257L, env257))
        assertEquals(0L, rotatedSession.ledger.lastAcceptedSequence())
        assertEquals(RootServiceSessionState.ACTIVE, rotatedSession.ledger.sessionState())
        val rotatedEnv = envelope("req-257", id = rotatedIdentity)
        assertTrue(submit(rotatedSession, 1L, rotatedEnv) is RootControlSubmitResult.Accepted)
    }

    @Test
    fun ordersExecutionAndRevocationInSingleSchedulingGateAndCoordinatesHostLock() {
        // Case 1: Revoke first (seq 1), then Submit (seq 2) -> RejectedBeforeAccepted(SESSION_REVOKED)
        val sessionRevokedFirst = newSession()
        val revokeRes = sessionRevokedFirst.revoke(
            caller = caller(),
            targetServiceInstanceId = sessionRevokedFirst.identity.serviceInstanceId,
            targetSessionId = sessionRevokedFirst.identity.sessionId,
            targetIdentityEpoch = sessionRevokedFirst.identity.identityEpoch,
            controlSequence = 1L,
            reason = "consent_revoked",
        ) as RootControlRevokeResult.Revoked
        assertTrue(revokeRes.inFlightRequestIds.isEmpty())

        val postRevokeEnv = envelope("req-after-revoke")
        val postRevokeSubmit = submit(sessionRevokedFirst, 2L, postRevokeEnv) as RootControlSubmitResult.RejectedBeforeAccepted
        assertEquals(RootNonAcceptanceReason.SESSION_REVOKED, postRevokeSubmit.proof.reason)
        assertTrue(RootNonAcceptanceVerifier.verify(postRevokeSubmit.proof, postRevokeEnv, sessionRevokedFirst.identity))

        // Case 2: Submit first (seq 1), then Revoke (seq 2) -> unstarted ACCEPTED entry immediately cancelled, stream closed, replay still never claims non-acceptance
        val cancelledIds = mutableListOf<String>()
        val sessionAcceptedFirst = newSession(onCancelInFlight = cancelledIds::add)
        val inFlightEnv = envelope("req-in-flight")
        val accepted = submit(sessionAcceptedFirst, 1L, inFlightEnv) as RootControlSubmitResult.Accepted
        var pipeClosed = false
        sessionAcceptedFirst.openContentStream(accepted.ticket) { pipeClosed = true }
        assertFalse(sessionAcceptedFirst.canRotate())

        val revoke2 = sessionAcceptedFirst.revoke(
            caller = caller(),
            targetServiceInstanceId = sessionAcceptedFirst.identity.serviceInstanceId,
            targetSessionId = sessionAcceptedFirst.identity.sessionId,
            targetIdentityEpoch = sessionAcceptedFirst.identity.identityEpoch,
            controlSequence = 2L,
        ) as RootControlRevokeResult.Revoked
        assertEquals(listOf("req-in-flight"), revoke2.inFlightRequestIds)
        assertEquals(listOf("req-in-flight"), cancelledIds)
        assertTrue(pipeClosed)
        // Even before beginExecution is called, unstarted ACCEPTED entry is already TERMINAL(CANCELLED)
        assertEquals(
            RootServiceTerminalOutcome.CANCELLED,
            sessionAcceptedFirst.ledger.observe("req-in-flight")?.terminalOutcome,
        )
        assertTrue(sessionAcceptedFirst.ledger.inFlightRequestIds().isEmpty())
        assertFalse(sessionAcceptedFirst.ledger.beginExecution(accepted.ticket))
        assertFalse(sessionAcceptedFirst.canRotate())

        // Repeating the already-accepted requestId after revocation returns DuplicateSameDigest, NOT RejectedBeforeAccepted
        val repeatAfterRevoke = submit(sessionAcceptedFirst, 3L, inFlightEnv)
        assertTrue(repeatAfterRevoke is RootControlSubmitResult.DuplicateSameDigest)

        // Host gate coordination lock
        val hostGate = RootHostControlGate(sessionAcceptedFirst.identity)
        assertEquals(1L, hostGate.submitUnderLock { it })
        assertEquals(2L, hostGate.revokeUnderLock { it })
        assertTrue(hostGate.isRevoked())
        assertNull(hostGate.submitUnderLock { it })
        assertEquals(2L, hostGate.lastIssuedSequence())
    }

    @Test
    fun untrustedCallersAndMismatchedIdentitiesCannotAdvanceSequenceOrObtainNonAcceptanceProofs() {
        val session = newSession()
        val env = envelope("req-auth")

        val badCallers = listOf(
            caller(callingUid = 10149),
            caller(observedServiceUid = 2000),
            caller(packageName = "com.attacker.app"),
            caller(androidUserId = 10, callingUid = 1_010_148),
            caller(signingSha256 = "f".repeat(64)),
            caller(binderAlive = false),
        )
        badCallers.forEach { bad ->
            assertEquals(RootControlSubmitResult.UntrustedCaller, submit(session, 1L, env, obs = bad))
            assertEquals(
                RootControlRevokeResult.UntrustedCaller,
                session.revoke(
                    bad,
                    session.identity.serviceInstanceId,
                    session.identity.sessionId,
                    session.identity.identityEpoch,
                    1L,
                ),
            )
            assertEquals(
                RootControlObserveResult.UntrustedCaller,
                session.observeStatus(
                    bad,
                    session.identity.serviceInstanceId,
                    session.identity.sessionId,
                    session.identity.identityEpoch,
                    "req-auth",
                ),
            )
        }
        assertEquals(0L, session.ledger.lastAcceptedSequence())
        assertEquals(RootServiceSessionState.ACTIVE, session.ledger.sessionState())

        val wrongTarget = identity(sessionId = "session-instance-999")
        assertEquals(RootControlSubmitResult.IdentityMismatch, submit(session, 1L, env, targetId = wrongTarget))
        assertEquals(0L, session.ledger.lastAcceptedSequence())

        // Valid caller afterwards still succeeds at sequence 1
        assertTrue(submit(session, 1L, env) is RootControlSubmitResult.Accepted)
    }
}
