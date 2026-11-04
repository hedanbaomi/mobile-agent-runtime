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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
 * Independent production coordinator/backend seam. Platform state and engine are synthetic;
 * this is NOT real Shizuku/emulator evidence. A live explicit platform denial must invalidate
 * an old approval even if the old UID0 service remains responsive (ADR0053).
 */
class RootShizukuRevocationRegressionTest {
    private fun liveRootState() = runtime.mobileagent.shizuku.ShizukuAuthorityState(
        installedHint = true, binderAlive = true, permissionGranted = true,
        apiVersion = 13, serverUid = 0, userServiceAlive = false,
        ready = false, errorCode = "SERVER_UID_UNTRUSTED", userServiceUid = null,
    )

    private fun approveAfterObservation(s: Seam, deny: Boolean): ToolResult {
        val granted = liveRootState()
        s.coordinator.refreshLiveSessions(granted)
        s.coordinator.syncAuthorityManagerStates(s.manager, granted)
        assertTrue(s.coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT),
            "a consented, bound UID0 service must be ready before the observation")
        val args = s.writeArgs("revocation-probe.txt", "must-not-write-after-platform-denial")
        s.recordCall("run-shizuku-review", "call-shizuku-review", "call-shizuku-review", "file_write_text", args)
        val executor = RootToolExecutor(RootToolArguments.specs(), RootRuntimeToolBackend(
            s.db, s.coordinator, s.context("run-shizuku-review"), s.execution,
        ))
        assertEquals(ToolResult.NeedsApproval, runBlocking {
            executor.invoke(ToolCall("call-shizuku-review", "file_write_text", args))
        })
        if (deny) {
            val denied = granted.copy(permissionGranted = false, serverUid = null,
                errorCode = "PERMISSION_DENIED")
            s.coordinator.refreshLiveSessions(denied)
            s.coordinator.syncAuthorityManagerStates(s.manager, denied)
        }
        return runBlocking { executor.approve("call-shizuku-review") }
    }

    @Test
    fun independentShizukuUnrevokedOldApprovalPositiveControl() = withSeam { s ->
        val result = approveAfterObservation(s, deny = false)
        assertTrue(result is ToolResult.Value, "positive control must execute the real typed write: $result")
    }

    @Test
    fun independentLiveShizukuPermissionDenialMustBlockOldApproval() = withSeam { s ->
        val result = approveAfterObservation(s, deny = true)
        assertFalse(result is ToolResult.Value,
            "explicit live platform permission denial must block an old typed approval; actual=$result")
        assertFalse(s.coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
        assertNull(s.coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, false))
    }

    @Test
    fun independentClosedShizukuCoordinatorIgnoresLatePlatformDenial() = withSeam { s ->
        s.coordinator.close()
        val repository = RootAuthorityRepository(s.db)
        val closedState = repository.get(Authority.SHIZUKU_ROOT)
        s.coordinator.refreshLiveSessions(liveRootState().copy(
            permissionGranted = false, serverUid = null, errorCode = "PERMISSION_DENIED",
        ))
        assertEquals(closedState, repository.get(Authority.SHIZUKU_ROOT),
            "a platform observation delivered after close must not mutate durable authorization")
    }

    @Test
    fun independentLiveShizukuUid2000SwitchMustBlockRootDispatch() = withSeam { s ->
        assertTrue(s.coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
        s.coordinator.refreshLiveSessions(liveRootState().copy(
            serverUid = 2000, userServiceUid = 2000, userServiceAlive = true,
            ready = true, errorCode = null,
        ))
        assertFalse(s.coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT),
            "a real platform UID switch to shell must retire the old Root authorization")
        assertNull(s.coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, false))
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

    private class Seam(val db: SqlConnection, val dir: java.io.File) {
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
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },

            fdOps = workerFd,
            shellRunner = delegatingShellRunner,
            serviceUidProvider = { 0 },
            controlPlaneOverride = boundary,
        ).pinTestHost(boundary, signing)
        private val engineTransport = EngineRootChannelTransport(engine, 10148)
        var rawObservationOverride: ((String) -> String?)? = null
        val transport = object : RootChannelTransport by engineTransport {
            override fun queryManagerAuthorization(requestJson: String): String? {
                val override = rawObservationOverride
                if (override != null) return override(requestJson)
                val result = authorizationProbe()
                val request = RootPrivilegedServiceEngine.json.decodeFromString<RootManagerAuthorizationRequest>(requestJson)
                return RootPrivilegedServiceEngine.json.encodeToString(RootManagerAuthorizationResponse(
                    provider = "magisk", result = result.name,
                    serviceInstanceId = request.serviceInstanceId, sessionId = request.sessionId,
                    identityEpoch = request.identityEpoch, reason = "review-fixture",
                ))
            }
        }
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        /** Production-shaped connector over the in-memory engine transport. */
        @Volatile
        var authorizationProbe: () -> RootManagerAuthorization = { RootManagerAuthorization.GRANTED }
        var acquisitionCount = 0
        var completionCount = 0
        val connector = object : RootSessionConnector {

            override fun tryAcquireSession(
                authority: Authority,
                identityEpoch: Long,
                appInstanceId: String,
                shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
            ): RootAcquiredSession? {
                acquisitionCount++
                val req = RootServiceHandshakeRequest(
                    authority, identityEpoch, 10148, 0, appInstanceId,
                    "runtime.mobileagent", signing, signing,
                    boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir,
                )
                val id = transport.handshake(req)?.identity ?: return null
                return RootAcquiredSession(id, RootCallerObservation(10148, 0, "runtime.mobileagent", 0, signing), transport, boundary)
            }

            override fun completeAcquisition(authority: Authority, attemptToken: Long): Boolean {
                completionCount++
                return true
            }
        }
        val coordinator: RootRuntimeCoordinator
        val execution: runtime.mobileagent.domain.RootExecutionStamp
        val workspace: Workspace
        val grants: List<CapabilityGrant>
        val snapshot: AgentSnapshot

        init {
            Migrations.apply(db)
            AuthorityPolicyRepository(db).updatePolicy(0, Authority.SHIZUKU_ROOT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            val profiles = ProfileRepository(db)
            profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16384, outputLimit = 512, revision = 1))
            AgentRepository(db).saveWithPrompt(AgentProfile("agent", "Root Agent", "pending", "model", revision = 0), "Synthetic prompt")
            coordinator = RootRuntimeCoordinator(
                FakeTestContext(dir),
                db,
                fdOps = fd,
                sessionConnector = connector,

                controlPlaneBoundaryOverride = boundary,
                locatorCipher = TestRootLocatorCipher(),
            )
            coordinator.setUserIntent(Authority.SHIZUKU_ROOT, true, manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
            coordinator.setRootShellConsent(Authority.SHIZUKU_ROOT, enabled = true, userConfirmed = true, authorityManager = manager)
            execution = requireNotNull(coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT, requireShellConsent = true))
            val selected = RootWorkspaceUiController(db, coordinator).attach(
                Authority.SHIZUKU_ROOT, "/data/local/root-workspace", true, true, true, WorkspacePickerTarget("agent"),
            )
            assertTrue(selected is WorkspaceAccessResult.Success)
            workspace = requireNotNull(WorkspaceRepository(db).get((selected as WorkspaceAccessResult.Success).workspace.workspaceId))
            grants = CapabilityGrantRepository(db).forAgent("agent", includeRevoked = false)
            snapshot = coordinator.sealNewSnapshot(Authority.SHIZUKU_ROOT, "snapshot-w1", "agent", grants.mapTo(mutableSetOf()) { it.grantId }) {
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

}
