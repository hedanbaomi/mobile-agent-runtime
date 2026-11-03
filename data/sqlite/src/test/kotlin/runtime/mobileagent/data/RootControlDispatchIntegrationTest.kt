// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootControlDispatchIntegrationTest {
    private val fixture = RootApprovalFixture()
    private val sessionHandle = "workspace-handle-0001"
    private val typedArgs = RootTypedArguments.FileReadText(
        workspaceId = "root-workspace",
        relativePath = "private-scope-marker/file.txt",
        offsetBytes = 0L,
        maxBytes = 4096,
    )
    private val normalizedJson = typedArgs.canonicalJson()

    private data class Context(
        val repo: RootDispatchRepository,
        val permit: RootDispatchPermit,
        val transport: RootTransportInvocation,
        val envelope: RootControlEnvelope,
        val session: RootServiceSession,
        val caller: RootCallerObservation,
    )

    private fun setupContext(db: SqlConnection, bindWorkspaceInService: Boolean = true): Context {
        fixture.setup(db, "private-scope-marker", GrantLifetime.ONCE)
        db.execute("UPDATE tool_invocations SET arguments_json=?", listOf(normalizedJson))
        val approvals = RootApprovalRepository(db, clock = { fixture.now })
        val request = fixture.request().copy(normalizedArgumentsSha256 = typedArgs.canonicalSha256())
        val approved = approvals.approve(approvals.createPending(request).approval.approvalId)
        val repo = RootDispatchRepository(db) { fixture.now }
        val permit = repo.claim(approved.approval.approvalId, approved.request)
        val ticket = repo.beginAttempt(permit)
        val transport = repo.takeAttempt(ticket, normalizedJson)
        val envelope = transport.toControlEnvelope(sessionHandle, typedArgs)
        val id = fixture.execution().identity
        val caller = RootCallerObservation(
            callingUid = id.appUid,
            observedServiceUid = id.serviceUid,
            packageName = id.packageName,
            androidUserId = id.androidUserId,
            signingSha256 = id.signingSha256,
            binderAlive = true,
        )
        val session = RootServiceSession(id)
        if (bindWorkspaceInService) {
            assertTrue(
                session.bindWorkspaceHandle(
                    caller,
                    "root-workspace",
                    transport.snapshot.root.workspaceGenerationId,
                    sessionHandle,
                ),
            )
        }
        return Context(repo, permit, transport, envelope, session, caller)
    }

    @Test
    fun endToEndControlGateAcceptsExecutesAndCoordinatesRotationAcrossHostAndService() {
        JdbcSqlConnection().use { db ->
            val ctx = setupContext(db)
            val id = ctx.session.identity
            val hostGate = RootHostControlGate(id)
            val submitRes = hostGate.submitUnderLock { seq ->
                ctx.session.submit(
                    caller = ctx.caller,
                    targetServiceInstanceId = id.serviceInstanceId,
                    targetSessionId = id.sessionId,
                    targetIdentityEpoch = id.identityEpoch,
                    controlSequence = seq,
                    rawEnvelopeJson = ctx.envelope.canonicalJson(),
                )
            } as RootControlSubmitResult.Accepted

            ctx.repo.acknowledge(ctx.permit)
            assertFalse(ctx.repo.canRotateSession(ctx.session))
            assertTrue(ctx.session.ledger.beginExecution(submitRes.ticket))
            val stream = ctx.session.openContentStream(submitRes.ticket)
            assertNotNull(stream)
            assertFalse(ctx.repo.canRotateSession(ctx.session))

            val digest = "c".repeat(64)
            ctx.session.ledger.completeExecution(submitRes.ticket, RootServiceTerminalOutcome.SUCCEEDED, digest)
            // Stream still open and permit not closed -> cannot rotate yet
            assertFalse(ctx.repo.canRotateSession(ctx.session))
            stream!!.close()
            assertFalse(ctx.repo.canRotateSession(ctx.session))

            ctx.repo.finish(ctx.permit, RootDispatchOutcome.SUCCEEDED, digest)
            assertTrue(ctx.repo.authorizeDisclosure(ctx.permit))
            ctx.repo.closePermit(ctx.permit, disclosed = true)
            assertTrue(ctx.repo.canRotateSession(ctx.session))
        }
    }

    @Test
    fun verifiedServiceNonAcceptanceSettlesWithoutDegradingWhileTamperedProofsFailClosed() {
        JdbcSqlConnection().use { db ->
            val ctx = setupContext(db, bindWorkspaceInService = false)
            val id = ctx.session.identity
            val rejected = ctx.session.submit(
                caller = ctx.caller,
                targetServiceInstanceId = id.serviceInstanceId,
                targetSessionId = id.sessionId,
                targetIdentityEpoch = id.identityEpoch,
                controlSequence = 1L,
                rawEnvelopeJson = ctx.envelope.canonicalJson(),
            ) as RootControlSubmitResult.RejectedBeforeAccepted
            assertEquals(RootNonAcceptanceReason.WORKSPACE_HANDLE_INVALID, rejected.proof.reason)

            // Forged / mismatched proofs are rejected before settling
            assertThrows(IllegalArgumentException::class.java) {
                ctx.repo.finishWithNonAcceptanceProof(
                    ctx.permit,
                    ctx.envelope,
                    rejected.proof.copy(envelopeSha256 = "f".repeat(64)),
                    id,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                ctx.repo.finishWithNonAcceptanceProof(
                    ctx.permit,
                    ctx.envelope,
                    rejected.proof,
                    id.copy(sessionId = "session-instance-999"),
                )
            }
            // P2-1 regression: proof for an envelope with tampered workspaceGenerationId/scope must be rejected
            val tamperedGenEnvelope = ctx.envelope.copy(workspaceGenerationId = "workspace-gen-9999")
            val tamperedGenProof = (ctx.session.submit(
                caller = ctx.caller,
                targetServiceInstanceId = id.serviceInstanceId,
                targetSessionId = id.sessionId,
                targetIdentityEpoch = id.identityEpoch,
                controlSequence = 2L,
                rawEnvelopeJson = tamperedGenEnvelope.canonicalJson(),
            ) as RootControlSubmitResult.RejectedBeforeAccepted).proof
            assertThrows(IllegalArgumentException::class.java) {
                ctx.repo.finishWithNonAcceptanceProof(ctx.permit, tamperedGenEnvelope, tamperedGenProof, id)
            }

            // Authentic proof settles as REJECTED_BEFORE_ACCEPTED and does not degrade the authority
            val settled = ctx.repo.finishWithNonAcceptanceProof(ctx.permit, ctx.envelope, rejected.proof, id)
            assertEquals(RootDispatchOutcome.REJECTED_BEFORE_ACCEPTED, settled.outcome)
            assertFalse(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
            assertFalse(ctx.repo.authorizeDisclosure(ctx.permit))
            ctx.repo.closePermit(ctx.permit, disclosed = false)
            assertEquals("FAILED", RunRepository(db).invocations("run-root").single().state)
        }
    }

    @Test
    fun provisionalAttachPersistenceAndReattachEnforceConfinementAndReleaseOnFailure() {
        JdbcSqlConnection().use { db ->
            val ctx = setupContext(db, bindWorkspaceInService = false)
            val id = ctx.session.identity
            val exec = fixture.execution()
            val controlPlane = RootControlPlaneBoundary(
                credentialEncryptedDataDir = "/data/user/0/${id.packageName}",
                credentialEncryptedIdentity = RootFdNodeIdentity(1L, 9001L, 100L, true, false, false, 2L, 448, id.appUid, id.appUid),
                deviceProtectedDataDir = "/data/user_de/0/${id.packageName}",
                deviceProtectedIdentity = RootFdNodeIdentity(1L, 9002L, 100L, true, false, false, 2L, 448, id.appUid, id.appUid),
            )
            assertTrue(
                ctx.session.configureControlPlane(
                    ctx.caller,
                    id.serviceInstanceId,
                    id.sessionId,
                    id.identityEpoch,
                    controlPlane,
                ),
            )
            val wsRepo = RootWorkspaceRepository(db) { "ws-provisional-1" }
            val locRepo = RootWorkspaceLocatorRepository(db)
            wsRepo.createNew("Provisional Root WS", true, exec)
            val freshOrigin = requireNotNull(wsRepo.getOrigin("ws-provisional-1"))
            val dirNode = RootFdNodeIdentity(1L, 7001L, 100L, true, false, false, 2L, 448, 10250, 10250)

            // 1. Simulate persistence failure (sealLocator throws) -> staged handle & FD must be released immediately.
            var failedFdClosed = 0
            assertNull(
                ctx.session.stageProvisionalDirectory(
                    ctx.caller,
                    id.serviceInstanceId,
                    id.sessionId,
                    id.identityEpoch,
                    "prov-fail-0001-aaaa",
                    "/data/user/0/com.example.other/files",
                    RootWorkspaceRootKind.DIRECTORY,
                    dirNode,
                    onCloseFd = { failedFdClosed += 1 },
                ),
            )
            assertThrows(IllegalStateException::class.java) {
                locRepo.attachProvisionalAndPersist(
                    serviceSession = ctx.session,
                    caller = ctx.caller,
                    provisionalHandle = "prov-fail-0001-aaaa",
                    workspaceSessionHandle = "ws-session-fail-0001",
                    origin = freshOrigin,
                    execution = exec,
                    sealLocator = { _, _, _, _ -> error("Simulated Keystore seal failure") },
                )
            }
            assertEquals(1, failedFdClosed)
            assertNull(locRepo.getCurrent(freshOrigin.workspaceId, exec))

            // 2. Successful provisional stage -> attach -> persist -> confirm activates workspace handle,
            // and reattach on a new session verifies decoded locator against FD inode/device/mountId.
            var activeFdClosed = 0
            assertNull(
                ctx.session.stageProvisionalDirectory(
                    ctx.caller,
                    id.serviceInstanceId,
                    id.sessionId,
                    id.identityEpoch,
                    "prov-ok-0002-bbbb",
                    "/data/user/0/com.example.other/files",
                    RootWorkspaceRootKind.DIRECTORY,
                    dirNode,
                    onCloseFd = { activeFdClosed += 1 },
                ),
            )
            val persisted = locRepo.attachProvisionalAndPersist(
                serviceSession = ctx.session,
                caller = ctx.caller,
                provisionalHandle = "prov-ok-0002-bbbb",
                workspaceSessionHandle = sessionHandle,
                origin = freshOrigin,
                execution = exec,
                sealLocator = { _, _, _, plain -> plain.reversedArray() },
            )
            assertEquals(freshOrigin, persisted.origin)

            // Reattach on a new session with mismatched inode is rejected and closes reopened FD.
            val id2 = id.copy(
                serviceInstanceId = "service-instance-0002",
                sessionId = "session-instance-0002",
                identityEpoch = 2L,
            )
            val authorities = RootAuthorityRepository(db)
            val curAuth = authorities.get(Authority.ROOT_DIRECT)
            authorities.advanceIdentityEpoch(Authority.ROOT_DIRECT, curAuth.revision)
            val exec2 = exec.copy(identity = id2)
            val session2 = RootServiceSession(identity = id2, controlPlane = controlPlane)
            var badReopenClosed = 0
            val badReattach = locRepo.reattachPersistedToSession(
                serviceSession = session2,
                caller = ctx.caller,
                workspaceId = freshOrigin.workspaceId,
                workspaceSessionHandle = "ws-reattached-bad-0001",
                execution = exec2,
                reopenedRootPath = "/data/user/0/com.example.other/files",
                reopenedIdentity = dirNode.copy(inode = 7999L),
                onCloseFd = { badReopenClosed += 1 },
                openLocator = { _, _, _, _, cipher -> cipher.reversedArray() },
            )
            assertEquals(
                RootWorkspaceReattachResult.Rejected(RootConfinementRejection.IDENTITY_MISMATCH),
                badReattach,
            )
            assertEquals(1, badReopenClosed)

            // Reattach with matching FD identity succeeds.
            var goodReopenClosed = 0
            val goodReattach = locRepo.reattachPersistedToSession(
                serviceSession = session2,
                caller = ctx.caller,
                workspaceId = freshOrigin.workspaceId,
                workspaceSessionHandle = "ws-reattached-good-0002",
                execution = exec2,
                reopenedRootPath = "/data/user/0/com.example.other/files",
                reopenedIdentity = dirNode,
                onCloseFd = { goodReopenClosed += 1 },
                openLocator = { _, _, _, _, cipher -> cipher.reversedArray() },
            )
            assertEquals(
                RootWorkspaceReattachResult.Reattached(
                    workspaceId = freshOrigin.workspaceId,
                    workspaceGenerationId = freshOrigin.generationId,
                    workspaceSessionHandle = "ws-reattached-good-0002",
                    rootKind = RootWorkspaceRootKind.DIRECTORY,
                ),
                goodReattach,
            )
            assertEquals(0, goodReopenClosed)
            session2.closeSession()
            assertEquals(1, goodReopenClosed)
        }
    }

    @Test
    fun fiveWayToolExposureGateEnforcesWorkspaceReadWriteLocatorAndShellRevocationWithoutResurrection() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val exec = fixture.execution()
            val id = exec.identity
            val gate = RootToolExposureGate(db) { fixture.now }

            // Before attaching a RootWorkspaceLocator to root-workspace, exposure must be empty.
            val beforeLocator = gate.evaluateExposure(
                snapshotId = "snapshot-root",
                grantIds = listOf("root-grant"),
                execution = exec,
            )
            assertTrue(beforeLocator.visibleTools.isEmpty())
            assertFalse(beforeLocator.shellExposed)

            // Attach encrypted locator to root-workspace -> file_read_text becomes visible, shell_exec stays false.
            val origin = requireNotNull(RootWorkspaceRepository(db).getOrigin("root-workspace"))
            RootWorkspaceLocatorRepository(db).attachNew(
                RootWorkspaceLocator(origin, byteArrayOf(9, 8, 7)),
                exec,
            )
            val readOnlyExposure = gate.evaluateExposure(
                snapshotId = "snapshot-root",
                grantIds = listOf("root-grant"),
                execution = exec,
            )
            assertEquals(setOf("file_read_text"), readOnlyExposure.visibleToolNames)
            assertFalse(readOnlyExposure.shellExposed)

            // Enable 5-way Root Shell gate (Policy DangerousMode + Root consent + Root Shell consent + writable workspace + snapshot).
            val authorities = RootAuthorityRepository(db)
            AuthorityPolicyRepository(db).updatePolicy(1, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            val cur = authorities.get(Authority.ROOT_DIRECT)
            val rootConfirmed = authorities.confirmRoot(Authority.ROOT_DIRECT, cur.revision, 2)!!
            val shellConfirmed = authorities.setShellConsent(Authority.ROOT_DIRECT, rootConfirmed.revision, true, 2)!!
            val epochAdvanced = authorities.advanceIdentityEpoch(Authority.ROOT_DIRECT, shellConfirmed.revision)!!
            val shellExecStamp = RootExecutionStamp(
                identity = id.copy(identityEpoch = epochAdvanced.identityEpoch),
                consentRevision = epochAdvanced.consent.revision,
                shellConsentRevision = epochAdvanced.consent.shellRevision,
                policyVersion = 2L,
            )
            RootWorkspaceRepository(db) { "ws-shell-rw" }.createNew("Writable Root Shell WS", true, shellExecStamp)
            val wsShellOrigin = requireNotNull(RootWorkspaceRepository(db).getOrigin("ws-shell-rw"))
            RootWorkspaceLocatorRepository(db).attachNew(
                RootWorkspaceLocator(wsShellOrigin, byteArrayOf(1, 2, 3)),
                shellExecStamp,
            )
            val grantRepo = RootGrantRepository(db) { fixture.now }
            grantRepo.create(
                CapabilityGrant(
                    "grant-meta-w",
                    "agent-root",
                    CapabilityId(CapabilityId.FILE_METADATA_WRITE),
                    workspaceId = "ws-shell-rw",
                    policyVersion = 2L,
                ),
                shellExecStamp,
            )
            grantRepo.create(
                CapabilityGrant(
                    "grant-shell",
                    "agent-root",
                    CapabilityId(CapabilityId.SHELL_EXECUTE),
                    workspaceId = "ws-shell-rw",
                    policyVersion = 2L,
                ),
                shellExecStamp,
            )
            RootSnapshotGrantRepository(db) { fixture.now }.createNewSnapshot(
                snapshotId = "snapshot-shell-rw",
                agentId = "agent-root",
                grantIds = setOf("grant-meta-w", "grant-shell"),
                execution = shellExecStamp,
            ) {
                db.execute(
                    "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf("snapshot-shell-rw", 1, "agent-root", "prompt-1", "model-1", 1, "[]", "[]", fixture.now, "provider-1", 1, "{}", "{}", "{}", "{}", "{}"),
                )
            }
            val fullExposure = gate.evaluateExposure(
                snapshotId = "snapshot-shell-rw",
                grantIds = listOf("grant-meta-w", "grant-shell"),
                execution = shellExecStamp,
            )
            assertEquals(setOf("file_set_metadata", "shell_exec"), fullExposure.visibleToolNames)
            assertTrue(fullExposure.shellExposed)

            // Turn Root Shell consent OFF and back ON -> shellRevision advances -> old shellExecStamp NEVER resurrects shell_exec!
            val shellOff = authorities.setShellConsent(Authority.ROOT_DIRECT, epochAdvanced.revision, false, 2)!!
            val afterOff = gate.evaluateExposure(
                snapshotId = "snapshot-shell-rw",
                grantIds = listOf("grant-meta-w", "grant-shell"),
                execution = shellExecStamp,
            )
            assertEquals(setOf("file_set_metadata"), afterOff.visibleToolNames)
            assertFalse(afterOff.shellExposed)

            authorities.setShellConsent(Authority.ROOT_DIRECT, shellOff.revision, true, 2)!!
            val afterReEnable = gate.evaluateExposure(
                snapshotId = "snapshot-shell-rw",
                grantIds = listOf("grant-meta-w", "grant-shell"),
                execution = shellExecStamp,
            )
            assertEquals(setOf("file_set_metadata"), afterReEnable.visibleToolNames)
            assertFalse(afterReEnable.shellExposed)

            // Composite file_grep exposure requires both FILE_READ_TEXT and FILE_LIST in the same snapshot.
            val currentAuth = authorities.get(Authority.ROOT_DIRECT)
            val searchStamp = RootExecutionStamp(
                identity = id.copy(identityEpoch = currentAuth.identityEpoch),
                consentRevision = currentAuth.consent.revision,
                shellConsentRevision = currentAuth.consent.shellRevision,
                policyVersion = 2L,
            )
            RootWorkspaceRepository(db) { "ws-search" }.createNew("Search Root WS", false, searchStamp)
            val wsSearchOrigin = requireNotNull(RootWorkspaceRepository(db).getOrigin("ws-search"))
            RootWorkspaceLocatorRepository(db).attachNew(
                RootWorkspaceLocator(wsSearchOrigin, byteArrayOf(4, 5, 6)),
                searchStamp,
            )
            grantRepo.create(
                CapabilityGrant("grant-list", "agent-root", CapabilityId(CapabilityId.FILE_LIST), workspaceId = "ws-search", policyVersion = 2L),
                searchStamp,
            )
            grantRepo.create(
                CapabilityGrant("grant-read", "agent-root", CapabilityId(CapabilityId.FILE_READ_TEXT), workspaceId = "ws-search", policyVersion = 2L),
                searchStamp,
            )
            RootSnapshotGrantRepository(db) { fixture.now }.createNewSnapshot(
                snapshotId = "snapshot-search",
                agentId = "agent-root",
                grantIds = setOf("grant-list", "grant-read"),
                execution = searchStamp,
            ) {
                db.execute(
                    "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf("snapshot-search", 1, "agent-root", "prompt-1", "model-1", 1, "[]", "[]", fixture.now, "provider-1", 1, "{}", "{}", "{}", "{}", "{}"),
                )
            }
            val searchExposure = gate.evaluateExposure(
                snapshotId = "snapshot-search",
                grantIds = listOf("grant-list", "grant-read"),
                execution = searchStamp,
            )
            assertEquals(setOf("file_list", "file_glob", "file_read_text", "file_grep"), searchExposure.visibleToolNames)
        }
    }
}
