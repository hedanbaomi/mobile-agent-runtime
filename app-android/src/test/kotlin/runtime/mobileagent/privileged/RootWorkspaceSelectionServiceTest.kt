// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootRecoveryLocatorCodec
import runtime.mobileagent.domain.RootRecoveryLocatorPayload
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.RootWorkspaceRootKind
import runtime.mobileagent.domain.canonicalSha256
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

class RootWorkspaceSelectionServiceTest {
    private val signing = "a".repeat(64)
    private val packageName = "runtime.mobileagent"
    private val ceDir = "/data/user/0/$packageName"
    private val deDir = "/data/user_de/0/$packageName"
    private val workspacePath = "/workspace"
    private val boundaryNodeCe = RootFdNodeIdentity(
        1L, 9001L, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0",
    )
    private val boundaryNodeDe = RootFdNodeIdentity(
        1L, 9002L, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0",
    )
    private val workspaceNode = RootFdNodeIdentity(
        1L, 2000L, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0",
    )
    private val boundary = RootControlPlaneBoundary(ceDir, boundaryNodeCe, deDir, boundaryNodeDe)
    private val origin = RootWorkspaceOrigin(
        workspaceId = "workspace-0000000001",
        authority = Authority.SHIZUKU_ROOT,
        appInstanceId = "app-instance-0001",
        packageName = packageName,
        appUid = 10148,
        androidUserId = 0,
        signingSha256 = signing,
        generationId = "workspace-generation-0001",
    )

    private class FakeFd(override val fdNumber: Int) : PosixRetainedFd {
        @Volatile
        var closed = false

        override val isOpen: Boolean get() = !closed

        override fun close() {
            closed = true
        }
    }

    private var observedIdentity: RootFdNodeIdentity = workspaceNode
    private val opened = mutableListOf<FakeFd>()

    private fun fdOps(): PosixFdOperations = java.lang.reflect.Proxy.newProxyInstance(
        PosixFdOperations::class.java.classLoader, arrayOf(PosixFdOperations::class.java),
    ) { _, method, args ->
        when (method.name) {
            "openDirectoryAbsoluteNoFollow" ->
                if (args!![0] == workspacePath) FakeFd(opened.size + 11).also { opened += it } else null
            "fstatIdentity" -> observedIdentity
            "inspectDirectoryIdentityNoFollow" ->
                if ((args!![0] as java.nio.file.Path).toString().replace('\\', '/') == workspacePath) {
                    observedIdentity
                } else {
                    null
                }
            else -> throw UnsupportedOperationException(method.name)
        }
    } as PosixFdOperations

    private fun engine(): RootPrivilegedServiceEngine {
        val engine = RootPrivilegedServiceEngine(
            managerAuthorizationOverride = { RootManagerAuthorization.GRANTED },
            fdOps = fdOps(),
            shellRunner = object : RootShellCommandRunner {
                override fun runCommand(authority: Authority, request: ShellExecRequest) = RootShellRunOutcome(
                    ShellExecutionStatus.SUCCEEDED, exitCode = 0, terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
                )

                override fun cancel(requestId: String) = false
            },
            serviceUidProvider = { 0 },
            controlPlaneOverride = boundary,
        )
        engine.pinHostBootstrap(
            RootServiceHostBootstrap(10148, 0, packageName, signing, signing, ceDir, deDir),
        )
        return engine
    }

    private fun handshake(engine: RootPrivilegedServiceEngine) {
        val request = RootServiceHandshakeRequest(
            Authority.SHIZUKU_ROOT, 1L, 10148, 0, "app-instance-0001", packageName,
            signing, signing, ceDir, deDir,
        )
        val response = RootPrivilegedServiceEngine.json.decodeFromString<RootServiceHandshakeResponse>(
            engine.handshake(RootPrivilegedServiceEngine.json.encodeToString(request), 10148),
        )
        assertTrue(response.accepted, response.errorReason)
    }

    private fun dispatch(
        engine: RootPrivilegedServiceEngine,
        request: RootServiceWorkspaceSelectionRequest,
    ): RootServiceBindWorkspaceResponse = RootPrivilegedServiceEngine.json.decodeFromString(
        engine.bindWorkspace(RootPrivilegedServiceEngine.json.encodeToString(request), 10148),
    )

    private fun request(
        phase: String,
        provisional: String = "",
        handle: String = "",
        workspaceId: String = "",
        generationId: String = "",
        token: String = "",
        originValue: RootWorkspaceOrigin? = origin,
    ) = RootServiceWorkspaceSelectionRequest(
        phase = phase,
        targetServiceInstanceId = engineIdentity!!.serviceInstanceId,
        targetSessionId = engineIdentity!!.sessionId,
        targetIdentityEpoch = engineIdentity!!.identityEpoch,
        workspaceRootPath = workspacePath,
        rootKindName = "DIRECTORY",
        provisionalHandle = provisional,
        workspaceSessionHandle = handle,
        workspaceId = workspaceId,
        workspaceGenerationId = generationId,
        stagedToken = token,
        origin = originValue,
    )

    private var engineIdentity: runtime.mobileagent.domain.PrivilegedSessionIdentity? = null

    @Test
    fun proposeStageConfirmReleaseBindsUserSelectedDirectory() {
        engine().use { engine ->
            handshake(engine)
            engineIdentity = engine.activeSession!!.identity

            val proposed = dispatch(engine, request(RootServiceWorkspaceSelection.PHASE_PROPOSE))
            assertTrue(proposed.accepted, proposed.errorReason ?: "")
            val provisional = proposed.provisionalHandle!!
            assertTrue(provisional.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            assertEquals(2000L, proposed.rootInode!!)
            assertEquals(77L, proposed.rootMountId!!)
            assertEquals(10148, proposed.rootOwnerUid!!)

            assertEquals(
                "origin_mismatch",
                dispatch(
                    engine,
                    request(
                        RootServiceWorkspaceSelection.PHASE_STAGE,
                        provisional = provisional,
                        originValue = origin.copy(packageName = "foreign.package"),
                    ),
                ).errorReason,
            )

            val staged = dispatch(engine, request(RootServiceWorkspaceSelection.PHASE_STAGE, provisional = provisional))
            assertTrue(staged.accepted, staged.errorReason ?: "")
            val handle = staged.workspaceSessionHandle!!
            assertEquals(origin.canonicalSha256(), staged.stagedToken)
            assertNotNull(staged.locatorBytes)

            assertEquals(
                "staged_handle_invalid",
                dispatch(
                    engine,
                    request(
                        RootServiceWorkspaceSelection.PHASE_CONFIRM,
                        handle = handle,
                        workspaceId = origin.workspaceId,
                        generationId = origin.generationId,
                        token = "b".repeat(64),
                    ),
                ).errorReason,
            )
            val confirmed = dispatch(
                engine,
                request(
                    RootServiceWorkspaceSelection.PHASE_CONFIRM,
                    handle = handle,
                    workspaceId = origin.workspaceId,
                    generationId = origin.generationId,
                    token = staged.stagedToken!!,
                ),
            )
            assertTrue(confirmed.accepted, confirmed.errorReason ?: "")
            assertEquals(RootServiceWorkspaceSelection.PHASE_CONFIRM, confirmed.phase)

            val released = dispatch(
                engine,
                request(RootServiceWorkspaceSelection.PHASE_RELEASE, handle = handle),
            )
            assertTrue(released.accepted)
            assertEquals(RootServiceWorkspaceSelection.PHASE_RELEASE, released.phase)
        }
    }

    @Test
    fun stageRequiresObservedProvisionalAndConfirmRejectsChangedRoot() {
        engine().use { engine ->
            handshake(engine)
            engineIdentity = engine.activeSession!!.identity
            assertEquals(
                "provisional_handle_invalid",
                dispatch(
                    engine,
                    request(RootServiceWorkspaceSelection.PHASE_STAGE, provisional = "ws-prov-0000000000000000"),
                ).errorReason,
            )
            val provisional = dispatch(
                engine,
                request(RootServiceWorkspaceSelection.PHASE_PROPOSE),
            ).provisionalHandle!!
            val staged = dispatch(engine, request(RootServiceWorkspaceSelection.PHASE_STAGE, provisional = provisional))
            assertTrue(staged.accepted, staged.errorReason ?: "")
            observedIdentity = workspaceNode.copy(inode = 2001L, mountId = 78L)
            assertEquals(
                "workspace_root_changed",
                dispatch(
                    engine,
                    request(
                        RootServiceWorkspaceSelection.PHASE_CONFIRM,
                        handle = staged.workspaceSessionHandle!!,
                        workspaceId = origin.workspaceId,
                        generationId = origin.generationId,
                        token = staged.stagedToken!!,
                    ),
                ).errorReason,
            )
        }
    }

    @Test
    fun legacyPathOnlyRequestNeverBecomesInitialBinding() {
        engine().use { engine ->
            handshake(engine)
            val identity = engine.activeSession!!.identity
            val legacy = RootServiceBindWorkspaceRequest(
                identity.serviceInstanceId, identity.sessionId, identity.identityEpoch,
                origin.workspaceId, origin.generationId, "workspace-handle-0000009", workspacePath,
                expectedRootDeviceId = 1L, expectedRootInode = 2000L, expectedRootMountId = 77L,
            )
            val response = RootPrivilegedServiceEngine.json.decodeFromString<RootServiceBindWorkspaceResponse>(
                engine.bindWorkspace(RootPrivilegedServiceEngine.json.encodeToString(legacy), 10148),
            )
            assertFalse(response.accepted)
            assertEquals("workspace_binding_protocol_unsupported", response.errorReason)
            assertEquals(RootServiceWorkspaceSelection.PHASE_REATTACH, response.phase)
        }
    }

    @Test
    fun foreignCallerPhaseRequestIsRejected() {
        engine().use { engine ->
            handshake(engine)
            engineIdentity = engine.activeSession!!.identity
            val raw = RootPrivilegedServiceEngine.json.encodeToString(
                request(RootServiceWorkspaceSelection.PHASE_PROPOSE),
            )
            val response = RootPrivilegedServiceEngine.json.decodeFromString<RootServiceBindWorkspaceResponse>(
                engine.bindWorkspace(raw, 10149),
            )
            assertFalse(response.accepted)
            assertEquals("session_inactive", response.errorReason)
        }
    }

    @Test
    fun confinementRejectionsNeverCreateProvisionalHandle() {
        engine().use { engine ->
            handshake(engine)
            engineIdentity = engine.activeSession!!.identity
            val controlPlanePath = request(RootServiceWorkspaceSelection.PHASE_PROPOSE)
                .copy(workspaceRootPath = ceDir)
            val confinement = dispatch(engine, controlPlanePath)
            assertFalse(confinement.accepted)
            assertNull(confinement.provisionalHandle)
            val traversing = request(RootServiceWorkspaceSelection.PHASE_PROPOSE)
                .copy(workspaceRootPath = "/workspace/../etc")
            assertNull(dispatch(engine, traversing).provisionalHandle)
        }
    }

    @Test
    fun checkValidatesConfirmedAndReattachedBindingsAndRejectsStaleHandles() {
        engine().use { engine ->
            handshake(engine)
            engineIdentity = engine.activeSession!!.identity
            val proposed = dispatch(engine, request(RootServiceWorkspaceSelection.PHASE_PROPOSE))
            val staged = dispatch(engine, request(RootServiceWorkspaceSelection.PHASE_STAGE, provisional = proposed.provisionalHandle!!))
            val confirmed = dispatch(
                engine,
                request(
                    RootServiceWorkspaceSelection.PHASE_CONFIRM,
                    handle = staged.workspaceSessionHandle!!,
                    workspaceId = origin.workspaceId,
                    generationId = origin.generationId,
                    token = staged.stagedToken!!,
                ),
            )
            assertTrue(confirmed.accepted, confirmed.errorReason ?: "")
            val handle = confirmed.workspaceSessionHandle!!
            val checked = dispatch(
                engine,
                request(
                    RootServiceWorkspaceSelection.PHASE_CHECK,
                    handle = handle,
                    workspaceId = origin.workspaceId,
                    generationId = origin.generationId,
                ),
            )
            assertTrue(checked.accepted, checked.errorReason ?: "")
            assertEquals(RootServiceWorkspaceSelection.PHASE_CHECK, checked.phase)
            assertEquals(origin.workspaceId, checked.workspaceId)

            // Random recovery handles and a different generation must never be accepted.
            val random = dispatch(
                engine,
                request(
                    RootServiceWorkspaceSelection.PHASE_CHECK,
                    handle = "ws-recover-0123456789abcdef",
                    workspaceId = origin.workspaceId,
                    generationId = origin.generationId,
                ),
            )
            assertFalse(random.accepted)
            assertEquals("workspace_binding_inactive", random.errorReason)
            val wrongGeneration = dispatch(
                engine,
                request(
                    RootServiceWorkspaceSelection.PHASE_CHECK,
                    handle = handle,
                    workspaceId = origin.workspaceId,
                    generationId = "workspace-generation-9999",
                ),
            )
            assertFalse(wrongGeneration.accepted)

            // Legacy locator reattach registers a real active binding and is CHECK-able too.
            val recoveryOrigin = origin.copy(workspaceId = "workspace-recovered", generationId = "workspace-generation-0003")
            val identity = engine.activeSession!!.identity
            val locator = RootRecoveryLocatorCodec.encode(
                RootRecoveryLocatorPayload(
                    originSha256 = recoveryOrigin.canonicalSha256(),
                    workspaceId = recoveryOrigin.workspaceId,
                    workspaceGenerationId = recoveryOrigin.generationId,
                    authority = recoveryOrigin.authority,
                    rootKind = RootWorkspaceRootKind.DIRECTORY,
                    absoluteRootPath = workspacePath,
                    rootDeviceId = 1L,
                    rootInode = 2000L,
                    rootMountId = 77L,
                ),
            )
            val reattachHandle = "workspace-handle-reattach-01"
            val reattached = RootPrivilegedServiceEngine.json.decodeFromString<RootServiceBindWorkspaceResponse>(
                engine.bindWorkspace(
                    RootPrivilegedServiceEngine.json.encodeToString(
                        RootServiceBindWorkspaceRequest(
                            identity.serviceInstanceId, identity.sessionId, identity.identityEpoch,
                            recoveryOrigin.workspaceId, recoveryOrigin.generationId, reattachHandle, workspacePath,
                            expectedRootDeviceId = 1L, expectedRootInode = 2000L, expectedRootMountId = 77L,
                            origin = recoveryOrigin, recoveryLocator = locator,
                        ),
                    ),
                    10148,
                ),
            )
            assertTrue(reattached.accepted, reattached.errorReason ?: "")
            val reattachCheck = dispatch(
                engine,
                request(
                    RootServiceWorkspaceSelection.PHASE_CHECK,
                    handle = reattachHandle,
                    workspaceId = recoveryOrigin.workspaceId,
                    generationId = recoveryOrigin.generationId,
                ),
            )
            assertTrue(reattachCheck.accepted, reattachCheck.errorReason ?: "")
        }
    }
}
