// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

class RootServiceReviewRegressionTest {
    private val signing = "a".repeat(64)
    private val boundary = RootControlPlaneBoundary(
        "/data/user/0/runtime.mobileagent", node(9001L),
        "/data/user_de/0/runtime.mobileagent", node(9002L),
    )
    private val request = RootServiceHandshakeRequest(
        Authority.SHIZUKU_ROOT, 1L, 10148, 0, "app-instance-0001", "runtime.mobileagent",
        signing, signing, boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir,
    )
    private val bootstrap = RootServiceHostBootstrap(
        request.appUid, request.androidUserId, request.packageName, request.signingSha256,
        request.apkSha256, request.ceDataDir, request.deDataDir,
    )
    private val json get() = RootPrivilegedServiceEngine.json

    private fun node(inode: Long) = RootFdNodeIdentity(
        1L, inode, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0",
    )

    private fun engine(
        fdOps: PosixFdOperations = AndroidOsPosixFdOperations(),
        run: () -> Unit = {},
        onStop: () -> Unit = {},
    ) = RootPrivilegedServiceEngine(
        managerAuthorizationOverride = { RootManagerAuthorization.GRANTED },
        fdOps = fdOps,
        shellRunner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                run()
                return RootShellRunOutcome(
                    ShellExecutionStatus.SUCCEEDED, exitCode = 0, terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
                )
            }
            override fun cancel(requestId: String) = false
        },
        serviceUidProvider = { 0 }, // Explicit simulated service identity; this is not an OS verification test.
        controlPlaneOverride = boundary,
        onStopService = onStop,
    )

    private fun handshake(engine: RootPrivilegedServiceEngine, req: RootServiceHandshakeRequest = request) =
        json.decodeFromString<RootServiceHandshakeResponse>(engine.handshake(json.encodeToString(req), req.appUid))

    private fun directoryOps(rootIdentity: RootFdNodeIdentity): PosixFdOperations =
        java.lang.reflect.Proxy.newProxyInstance(
            PosixFdOperations::class.java.classLoader, arrayOf(PosixFdOperations::class.java),
        ) { _, method, _ ->
            check(method.name == "inspectDirectoryIdentityNoFollow") { "Unexpected FD operation ${method.name}" }
            rootIdentity
        } as PosixFdOperations

    private fun bind(service: RootPrivilegedServiceEngine, request: RootServiceBindWorkspaceRequest) =
        json.decodeFromString<RootServiceBindWorkspaceResponse>(service.bindWorkspace(json.encodeToString(request), 10148))

    @Test
    fun cancelInvocationFrameAuthenticatesExactTargetBeforeRecordingTombstone() {
        engine().use { service ->
            service.pinHostBootstrap(bootstrap)
            val id = handshake(service).identity!!
            fun frame(epoch: Long = id.identityEpoch, version: Int = 1) = RootPipeRpcFrame(
                method = "cancelInvocation",
                requestJson = """{"schemaVersion":$version,"targetServiceInstanceId":"${id.serviceInstanceId}","targetSessionId":"${id.sessionId}","targetIdentityEpoch":$epoch,"invocationId":"inv-frame"}""",
            )
            assertEquals("false", RootPrivilegedProcessMain.dispatchFrame(service, frame(id.identityEpoch + 1), 10148))
            assertEquals("false", RootPrivilegedProcessMain.dispatchFrame(service, frame(version = 2), 10148))
            assertEquals("false", RootPrivilegedProcessMain.dispatchFrame(service, frame(), 20148))
            assertEquals("true", RootPrivilegedProcessMain.dispatchFrame(service, frame(), 10148))
            assertEquals("true", RootPrivilegedProcessMain.dispatchFrame(service, frame(), 10148))
        }
    }

    @Test
    fun handshakeWithoutExactCancellationProtocolIsRejected() {
        engine().use { service ->
            service.pinHostBootstrap(bootstrap)
            val current = kotlinx.serialization.json.Json.parseToJsonElement(json.encodeToString(request)) as kotlinx.serialization.json.JsonObject
            for (version in listOf<Int?>(null, 0, 2)) {
                val fields = current.toMutableMap()
                if (version == null) fields.remove("cancellationProtocolVersion")
                else fields["cancellationProtocolVersion"] = kotlinx.serialization.json.JsonPrimitive(version)
                val response = json.decodeFromString<RootServiceHandshakeResponse>(service.handshake(kotlinx.serialization.json.JsonObject(fields).toString(), 10148))
                assertFalse(response.accepted, "An unknown or missing cancel protocol must not establish an execution session")
            }
            assertTrue(handshake(service).accepted)
        }
    }

    @Test
    fun bindRequiresExactSessionTarget() {
        engine(directoryOps(node(1000L))).use { service ->
            service.pinHostBootstrap(bootstrap)
            val id = handshake(service).identity!!
            val bind = RootServiceBindWorkspaceRequest(
                id.serviceInstanceId, id.sessionId, id.identityEpoch,
                "workspace-1", "workspace-generation-0001", "workspace-handle-0000001", "/workspace",
            )
            listOf(
                bind.copy(targetServiceInstanceId = "other-service"),
                bind.copy(targetSessionId = "other-session"),
                bind.copy(targetIdentityEpoch = id.identityEpoch + 1L),
            ).forEach { wrong ->
                assertEquals("identity_mismatch", bind(service, wrong).errorReason)
            }
        }
    }

    @Test
    fun bindRejectsControlPlaneRoot() {
        engine(directoryOps(node(9001L))).use { service ->
            service.pinHostBootstrap(bootstrap)
            val id = handshake(service).identity!!
            val bind = RootServiceBindWorkspaceRequest(
                id.serviceInstanceId, id.sessionId, id.identityEpoch,
                "workspace-1", "workspace-generation-0001", "workspace-handle-0000001", boundary.credentialEncryptedDataDir,
            )
            assertEquals("control_plane_protected", bind(service, bind).errorReason)
        }
    }

    @Test
    fun bindRequiresKernelRootIdentityAndThreePhaseProtocol() {
        engine(directoryOps(node(1000L))).use { service ->
            service.pinHostBootstrap(bootstrap)
            val id = handshake(service).identity!!
            val bind = RootServiceBindWorkspaceRequest(
                id.serviceInstanceId, id.sessionId, id.identityEpoch,
                "workspace-1", "workspace-generation-0001", "workspace-handle-0000001", "/workspace",
            )
            assertEquals("workspace_root_identity_mismatch", bind(service, bind).errorReason)
            val expected = bind.copy(expectedRootDeviceId = 1L, expectedRootInode = 1000L, expectedRootMountId = 77L)
            listOf(
                expected.copy(expectedRootDeviceId = 2L), expected.copy(expectedRootInode = 1001L),
                expected.copy(expectedRootMountId = 78L),
            ).forEach { wrong -> assertEquals("workspace_root_identity_mismatch", bind(service, wrong).errorReason) }
            assertEquals("workspace_binding_protocol_unsupported", bind(service, expected).errorReason)
            assertEquals("workspace_binding_protocol_unsupported", bind(service, expected.copy(workspaceRootPath = "/other")).errorReason)
        }
    }

    private fun acceptedShell(service: RootPrivilegedServiceEngine): RootServiceSession {
        service.pinHostBootstrap(bootstrap)
        val id = handshake(service).identity!!
        val session = service.activeSession!!
        val caller = RootCallerObservation(10148, 0, request.packageName, 0, signing, true)
        // Internal domain fixture only: the production bind RPC must not bypass staging.
        assertTrue(session.bindWorkspaceHandle(caller, "workspace-1", "workspace-generation-0001", "workspace-handle-0000001"))
        val args = RootTypedArguments.ShellExec(workspaceId = "workspace-1", command = "fixture", cwdRelativePath = null)
        val envelope = RootControlEnvelope(
            requestId = "request-1", runId = "run-1", invocationId = "invocation-1", snapshotId = "snapshot-1",
            grantId = "grant-1", grantBeforeRevision = 1L, grantAfterRevision = 2L, once = true,
            grantConsumedAt = "2026-10-30T00:00:00Z", execution = RootExecutionStamp(id, 1L, 1L, 1L),
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE), toolName = "shell_exec", workspaceId = "workspace-1",
            workspaceGenerationId = "workspace-generation-0001", workspaceSessionHandle = "workspace-handle-0000001",
            relativePathScope = null, arguments = args, rawArgumentsSha256 = "b".repeat(64),
            normalizedArgumentsSha256 = args.canonicalSha256(), commandHash = RootPrivilegedServiceEngine.sha256Hex(args.command),
        )
        val result = json.decodeFromString<RootServiceExecuteResponse>(service.submitEnvelope(1L, envelope.canonicalJson(), 10148))
        assertEquals("ACCEPTED", result.submitStage, result.errorReason)
        return session
    }

    @Test
    fun cancelAcceptedTicketPreventsLaterExecution() {
        val starts = java.util.concurrent.atomic.AtomicInteger()
        engine(run = { starts.incrementAndGet() }).use { service ->
            val session = acceptedShell(service)
            service.cancelCommand("invocation-1", 10148)
            service.executeAccepted("request-1", ByteArray(0), 10148)
            assertEquals(0, starts.get())
            assertEquals(RootServiceTerminalOutcome.CANCELLED, session.ledger.observe("request-1")?.terminalOutcome)
        }
    }

    @Test
    fun revokedBeforeContentLeaseNeverStartsRunner() {
        val starts = java.util.concurrent.atomic.AtomicInteger()
        engine(run = { starts.incrementAndGet() }).use { service ->
            val session = acceptedShell(service)
            val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val worker = Thread {
                try { service.executeAccepted("request-1", ByteArray(0), 10148) } catch (failure: Throwable) { result.set(failure) }
            }
            try {
                synchronized(session) {
                    worker.start()
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2L)
                    while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, worker.state)
                    // Reentrant session revocation deterministically wins before openContentStream.
                    session.closeSession()
                }
                worker.join(2_000L)
                assertFalse(worker.isAlive)
                assertNull(result.get())
                assertEquals(0, starts.get())
            } finally {
                worker.interrupt()
                worker.join(2_000L)
            }
        }
    }

    @Test
    fun reattachValidatesOriginLocatorAndRejectsReplacementOrUnboundMounts() {
        engine(directoryOps(node(1000L))).use { service ->
            service.pinHostBootstrap(bootstrap)
            val id = handshake(service).identity!!
            val origin = RootWorkspaceOrigin("workspace-1", id.authority, id.appInstanceId, id.packageName,
                id.appUid, id.androidUserId, id.signingSha256, "workspace-generation-0001")
            val locator = RootRecoveryLocatorCodec.encode(RootRecoveryLocatorPayload(
                originSha256 = origin.canonicalSha256(), workspaceId = origin.workspaceId,
                workspaceGenerationId = origin.generationId, authority = origin.authority,
                rootKind = RootWorkspaceRootKind.DIRECTORY, absoluteRootPath = "/workspace",
                rootDeviceId = 1L, rootInode = 1000L, rootMountId = 77L,
            ))
            val bind = RootServiceBindWorkspaceRequest(
                id.serviceInstanceId, id.sessionId, id.identityEpoch, origin.workspaceId, origin.generationId,
                "workspace-handle-0000001", "/workspace", expectedRootDeviceId = 1L,
                expectedRootInode = 1000L, expectedRootMountId = 77L, origin = origin, recoveryLocator = locator,
            )
            assertFalse(bind(service, bind.copy(recoveryLocator = byteArrayOf(1))).accepted)
            assertFalse(bind(service, bind.copy(origin = origin.copy(generationId = "workspace-generation-0002"))).accepted)
            assertFalse(bind(service, bind.copy(confirmedMountIds = setOf(77L))).accepted)
            assertTrue(bind(service, bind).accepted)
            assertEquals("workspace_already_bound", bind(service, bind.copy(workspaceSessionHandle = "workspace-handle-0000002")).errorReason)
        }
    }

    @Test
    fun shutdownTimerStopsServiceWithUnresponsiveInFlightRunner() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val stopCount = java.util.concurrent.atomic.AtomicInteger()
        engine(run = {
            started.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* Simulate uninterruptible I/O. */ }
            }
        }, onStop = { stopCount.incrementAndGet(); stopped.countDown() }).use { service ->
            acceptedShell(service)
            val worker = Thread { service.executeAccepted("request-1", ByteArray(0), 10148) }
            try {
                worker.start()
                assertTrue(started.await(2L, java.util.concurrent.TimeUnit.SECONDS))
                val before = System.nanoTime()
                service.revokeAndShutdown(2L, "test_revoke", 10148)
                assertTrue(stopped.await(6L, java.util.concurrent.TimeUnit.SECONDS), "Five-second shutdown handle was never settled")
                val elapsedMs = (System.nanoTime() - before) / 1_000_000L
                assertTrue(elapsedMs >= 4_500L, "Must retain the grace period for unconfirmed work")
                assertEquals(1, stopCount.get())
                service.close()
                assertEquals(1, stopCount.get())
            } finally {
                release.countDown()
                worker.interrupt()
                worker.join(2_000L)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test
    fun repeatedHandshakeCannotReplaceSessionOrReviveClosedEngine() {
        engine().use { service ->
            service.pinHostBootstrap(bootstrap)
            assertTrue(handshake(service).accepted)
            val original = service.activeSession
            assertFalse(handshake(service, request.copy(identityEpoch = 2L)).accepted)
            assertSame(original, service.activeSession)
            service.close()
            assertFalse(handshake(service, request.copy(identityEpoch = 3L)).accepted)
        }
    }

    @Test
    fun unpinnedEngineRejectsSelfConsistentFirstCaller() {
        engine().use { service ->
            assertFalse(handshake(service).accepted)
            assertNull(service.activeSession)
        }
    }

    @Test
    fun bootstrapPinCannotBeReplacedBeforeHandshake() {
        engine().use { service ->
            service.pinHostBootstrap(bootstrap)
            assertThrows(IllegalStateException::class.java) {
                service.pinHostBootstrap(bootstrap.copy(expectedSigningSha256 = "b".repeat(64)))
            }
            assertFalse(handshake(service, request.copy(signingSha256 = "b".repeat(64))).accepted)
            assertTrue(handshake(service).accepted)
        }
    }
}
