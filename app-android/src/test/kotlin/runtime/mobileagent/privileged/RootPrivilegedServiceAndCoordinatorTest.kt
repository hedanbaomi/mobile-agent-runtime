// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellLimits
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore

class RootPrivilegedServiceAndCoordinatorTest {
    private val signing = "a".repeat(64)
    private val ceNode = RootFdNodeIdentity(1L, 9001L, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0")
    private val deNode = RootFdNodeIdentity(1L, 9002L, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0")
    private val boundary = RootControlPlaneBoundary(
        credentialEncryptedDataDir = "/data/user/0/runtime.mobileagent",
        credentialEncryptedIdentity = ceNode,
        deviceProtectedDataDir = "/data/user_de/0/runtime.mobileagent",
        deviceProtectedIdentity = deNode,
    )

    @Test
    fun dangerousModeDoesNotAutoGrantRootShellConsentAndReceiptedServiceDispatchExecutesInUid0() {
        val tempDir = Files.createTempDirectory("mar-root-coord-test")
        try {
            val wsDir = Files.createDirectories(tempDir.resolve("workspace"))
            val wsPosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(wsDir)
            val serviceFdOps = InMemoryServiceFdOps(wsPosix)
            var shizukuShellCalls = 0
            var simulateUnknownOutput = false
            val shizukuServiceRunner = object : RootShellCommandRunner {
                override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                    shizukuShellCalls += 1
                    if (simulateUnknownOutput) return RootShellRunOutcome(
                        status = ShellExecutionStatus.UNKNOWN_OUTCOME,
                        stdout = "private-output-must-be-withheld",
                        terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                        requiresDegradedEpoch = true,
                    )
                    return RootShellRunOutcome(
                        status = ShellExecutionStatus.SUCCEEDED,
                        exitCode = 0,
                        stdout = "uid=0(${authority.name}):${request.command}",
                        terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
                    )
                }

                override fun cancel(requestId: String): Boolean = true
            }
            val serviceEngine = RootPrivilegedServiceEngine(
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },

                managerAuthorizationOverride = { RootManagerAuthorization.GRANTED },
                fdOps = serviceFdOps,
                shellRunner = shizukuServiceRunner,
                serviceUidProvider = { PrivilegeTier.ROOT.uid },
                controlPlaneOverride = boundary,
            ).pinTestHost(boundary, signing)
            val binderStub = object : IRootPrivilegedService {
                override fun asBinder(): android.os.IBinder = java.lang.reflect.Proxy.newProxyInstance(
                    android.os.IBinder::class.java.classLoader, arrayOf(android.os.IBinder::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "isBinderAlive", "pingBinder" -> true
                        else -> throw UnsupportedOperationException(method.name)
                    }
                } as android.os.IBinder

                override fun handshake(requestJson: String): String =
                    serviceEngine.handshake(requestJson, 10148)

                override fun bindWorkspace(requestJson: String): String =
                    serviceEngine.bindWorkspace(requestJson, 10148)

                override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): String =
                    serviceEngine.submitEnvelope(controlSequence, rawEnvelopeJson, 10148)

                override fun executeAccepted(rawEnvelopeJson: String, payloadBytes: ByteArray?): String =
                    serviceEngine.executeAccepted(rawEnvelopeJson, payloadBytes ?: ByteArray(0), 10148)

                override fun submitAndExecute(
                    controlSequence: Long,
                    rawEnvelopeJson: String,
                    payloadBytes: ByteArray?,
                ): String = serviceEngine.submitAndExecute(
                    controlSequence,
                    rawEnvelopeJson,
                    payloadBytes ?: ByteArray(0),
                    10148,
                )

                override fun cancelInvocation(requestJson: String): Boolean = serviceEngine.cancelInvocation(requestJson, 10148)
                override fun queryQuiescence(requestJson: String): String = serviceEngine.queryQuiescence(requestJson, 10148)
                override fun cancelCommand(requestId: String): Boolean =
                    serviceEngine.cancelCommand(requestId, 10148)

                override fun revokeAndShutdown(controlSequence: Long, reason: String): String =
                    serviceEngine.revokeAndShutdown(controlSequence, reason, 10148)

                override fun destroy() = serviceEngine.close()
            }
            val shizukuTransport = ShizukuRootBinderTransport(binderStub)
            val caller = RootCallerObservation(
                callingUid = 10148,
                observedServiceUid = 0,
                packageName = "runtime.mobileagent",
                androidUserId = 0,
                signingSha256 = signing,
                binderAlive = true,
            )
            val hsResp = shizukuTransport.handshake(
                RootServiceHandshakeRequest(
                    authority = Authority.SHIZUKU_ROOT,
                    identityEpoch = 1L,
                    appUid = 10148,
                    androidUserId = 0,
                    appInstanceId = "app-instance-0001",
                    packageName = "runtime.mobileagent",
                    signingSha256 = signing,
                    apkSha256 = signing,
                    ceDataDir = boundary.credentialEncryptedDataDir,
                    deDataDir = boundary.deviceProtectedDataDir,
                ),
            )!!
            assertTrue(hsResp.accepted)
            val serviceIdentity = hsResp.identity!!

            InMemoryRootSqlConnection().use { db ->
                Migrations.apply(db)
                val policyRepo = AuthorityPolicyRepository(db)
                policyRepo.updatePolicy(0, Authority.SHIZUKU_ROOT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
                val authorityManager = AuthorityManager(InMemoryAuthorityStateStore())
                val fakeContext = FakeTestContext(tempDir.toFile())
                val connector = object : RootSessionConnector {
                    override fun tryAcquireSession(
                        authority: Authority,
                        identityEpoch: Long,
                        appInstanceId: String,
                        shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
                    ): RootAcquiredSession? = if (authority == Authority.SHIZUKU_ROOT) {
                        RootAcquiredSession(serviceIdentity, caller, shizukuTransport, boundary)
                    } else {
                        null
                    }
                }
                val locatorCipher = TestRootLocatorCipher()
                val coordinator = RootRuntimeCoordinator(
                    context = fakeContext,
                    db = db,
                    fdOps = serviceFdOps,
                    sessionConnector = connector,

                    controlPlaneBoundaryOverride = boundary,
                    locatorCipher = locatorCipher,
                )

                // Issue 3 verification: enabling SHIZUKU_ROOT while DangerousMode is ENABLED_CONFIRM_HIGH_RISK
                // and calling onDangerousModeUpdated MUST NOT auto-grant shellEnabled!
                coordinator.setUserIntent(
                    authority = Authority.SHIZUKU_ROOT,
                    enabled = true,
                    authorityManager = authorityManager,
                    dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                    shizukuState = null,
                )
                coordinator.onDangerousModeUpdated(
                    mode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                    authorityManager = authorityManager,
                    shizukuState = null,
                )
                val recordBeforeShellConfirm = coordinator.rootAuthorityRepository.get(Authority.SHIZUKU_ROOT)
                assertTrue(recordBeforeShellConfirm.consent.enabled)
                assertFalse(recordBeforeShellConfirm.consent.shellEnabled)
                assertNull(coordinator.setRootShellConsent(Authority.SHIZUKU_ROOT, enabled = true, userConfirmed = false))

                // Shell execution before explicit per-channel Root Shell confirmation is rejected!
                val shellReq = ShellExecRequest.fromRuntime(
                    callId = "call-sh-1",
                    command = "id -u",
                    cwd = null, // The selected workspace root, expressed by the typed protocol.
                    limits = ShellLimits(2000L, 4096L),
                    agentId = "agent-1",
                    snapshotId = "snap-1",
                    selectedAuthority = Authority.SHIZUKU_ROOT,
                    dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                    policyVersion = policyRepo.getPolicy().policyVersion,
                    configSnapshotHash = "hash",
                    sessionIdentity = "ses-1",
                    toolSchemaVersion = 1,
                )
                val deniedShell = executeFixtureShell(coordinator, Authority.SHIZUKU_ROOT, shellReq, "shizuku-root-ws")
                assertNull(deniedShell)
                assertEquals(0, shizukuShellCalls)

                // Explicit per-channel Root Shell consent grants SHIZUKU_ROOT shell without touching ROOT_DIRECT
                val confirmedShell = coordinator.setRootShellConsent(
                    authority = Authority.SHIZUKU_ROOT,
                    enabled = true,
                    userConfirmed = true,
                    authorityManager = authorityManager,
                )
                assertNotNull(confirmedShell)
                assertTrue(confirmedShell!!.consent.shellEnabled)
                assertFalse(coordinator.rootAuthorityRepository.get(Authority.ROOT_DIRECT).consent.shellEnabled)

                // Round 4 Finding 1 verification: unapproved or nonexistent agent/snapshot shell call fails closed
                // and mints 0 capability grants, snapshots, workspaces, or approvals!
                val unapprovedShell = executeFixtureShell(coordinator, Authority.SHIZUKU_ROOT, shellReq, "shizuku-root-ws")
                assertNull(unapprovedShell)
                assertEquals(0L, db.query("SELECT COUNT(*) AS c FROM capability_grants").single().long("c"))
                assertEquals(0L, db.query("SELECT COUNT(*) AS c FROM root_approval_bindings").single().singleCount())
                assertEquals(0, shizukuShellCalls)

                // Legitimate provisioning outside the execution path:
                provisionFixtureWorkspaceAndSnapshot(
                    db = db,
                    coordinator = coordinator,
                    fdOps = serviceFdOps,
                    locatorCipher = locatorCipher,
                    authority = Authority.SHIZUKU_ROOT,
                    workspaceId = "shizuku-root-ws",
                    workspaceRootPath = wsDir,
                    agentId = "agent-1",
                    snapshotId = "snap-1",
                    capabilities = setOf(
                        runtime.mobileagent.domain.CapabilityId(runtime.mobileagent.domain.CapabilityId.FILE_METADATA_READ),
                        runtime.mobileagent.domain.CapabilityId(runtime.mobileagent.domain.CapabilityId.FILE_WRITE_TEXT),
                        runtime.mobileagent.domain.CapabilityId(runtime.mobileagent.domain.CapabilityId.SHELL_EXECUTE),
                    ),
                )

                val locatorBytes = runtime.mobileagent.data.RootWorkspaceLocatorRepository(db)
                    .getCurrent("shizuku-root-ws", coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT)!!)!!
                    .encryptedEnvelopeCopy()
                assertNull(runtime.mobileagent.domain.RootRecoveryLocatorCodec.decode(locatorBytes),
                    "Persisted locator must be ciphertext, never a plaintext path record")

                // Issue 1, 4, 5, 6 verification: file write/stat route through the UID 0 service
                // with an exact Runtime invocation id; the legacy no-id adapter path must fail closed.
                val writeGrantId = "grant-snap-1-file-write_text"
                val statGrantId = "grant-snap-1-file-metadata-read"
                val shellGrantId = "grant-snap-1-shell-execute"

                val writeV1Args = runtime.mobileagent.domain.RootTypedArguments.FileWriteText(
                    workspaceId = "shizuku-root-ws",
                    relativePath = "config.ini",
                    contentSha256 = RootPrivilegedServiceEngine.sha256Hex("v=1"),
                    contentByteLength = 3,
                    replaceExisting = false,
                    expectedVersion = null,
                )
                assertNotNull(
                    approveFixtureFile(db, coordinator,
                        authority = Authority.SHIZUKU_ROOT, runId = "run-w1", invocationId = "inv-w1",
                        callId = "call-w1", snapshotId = "snap-1", grantId = writeGrantId,
                        typedArgs = writeV1Args,
                    ),
                )
                val v1 = when (val result = requireNotNull(
                    coordinator.executeFileInvocation(
                        Authority.SHIZUKU_ROOT, "inv-w1", writeV1Args, "v=1".toByteArray(),
                    ),
                ).toFileResult()) {
                    is runtime.mobileagent.domain.RootFileOperationResult.Succeeded -> result.value.version
                    else -> error("expected receipted write success: $result")
                }
                assertEquals("v=1", serviceFdOps.readText("config.ini"))

                // The retired no-id entrypoint must not reach the service.
                val legacyNoId = coordinator.executeFileInvocation(
                    Authority.SHIZUKU_ROOT, "", writeV1Args.copy(
                        contentSha256 = RootPrivilegedServiceEngine.sha256Hex("v=bad"), contentByteLength = 5,
                    ), "v=bad".toByteArray(),
                )
                assertNull(legacyNoId, "no-id entrypoint must fail closed")
                assertEquals("v=1", serviceFdOps.readText("config.ini"))

                // Stale expectedVersion is rejected before any mutation.
                val staleArgs = writeV1Args.copy(
                    contentSha256 = RootPrivilegedServiceEngine.sha256Hex("v=stale"),
                    replaceExisting = true,
                    expectedVersion = "f".repeat(64),
                )
                assertNotNull(
                    approveFixtureFile(db, coordinator,
                        authority = Authority.SHIZUKU_ROOT, runId = "run-w-stale", invocationId = "inv-w-stale",
                        callId = "call-w-stale", snapshotId = "snap-1", grantId = writeGrantId,
                        typedArgs = staleArgs,
                    ),
                )
                val staleResult = coordinator.executeFileInvocation(
                    Authority.SHIZUKU_ROOT, "inv-w-stale", staleArgs, "v=stale".toByteArray(),
                )?.toFileResult()
                assertFalse(staleResult is runtime.mobileagent.domain.RootFileOperationResult.Succeeded)
                assertEquals("v=1", serviceFdOps.readText("config.ini"))

                // Matching expectedVersion succeeds and updates the file in the UID 0 service.
                val writeV2Args = writeV1Args.copy(
                    contentSha256 = RootPrivilegedServiceEngine.sha256Hex("v=2"),
                    replaceExisting = true,
                    expectedVersion = v1,
                )
                approveFixtureFile(db, coordinator,
                    authority = Authority.SHIZUKU_ROOT, runId = "run-w2", invocationId = "inv-w2",
                    callId = "call-w2", snapshotId = "snap-1", grantId = writeGrantId,
                    typedArgs = writeV2Args,
                )
                val v2 = when (val result = requireNotNull(
                    coordinator.executeFileInvocation(
                        Authority.SHIZUKU_ROOT, "inv-w2", writeV2Args, "v=2".toByteArray(),
                    ),
                ).toFileResult()) {
                    is runtime.mobileagent.domain.RootFileOperationResult.Succeeded -> result.value.version
                    else -> error("expected receipted replace success: $result")
                }
                assertEquals("v=2", serviceFdOps.readText("config.ini"))

                val statArgs = runtime.mobileagent.domain.RootTypedArguments.FileGetMetadata(
                    workspaceId = "shizuku-root-ws",
                    relativePath = "config.ini",
                )
                approveFixtureFile(db, coordinator,
                    authority = Authority.SHIZUKU_ROOT, runId = "run-stat-1", invocationId = "inv-stat-1",
                    callId = "call-stat-1", snapshotId = "snap-1", grantId = statGrantId,
                    typedArgs = statArgs,
                )
                val statSnapshot = when (val result = requireNotNull(
                    coordinator.executeFileInvocation(Authority.SHIZUKU_ROOT, "inv-stat-1", statArgs),
                ).toFileResult()) {
                    is runtime.mobileagent.domain.RootFileOperationResult.Succeeded -> result.value
                    else -> error("expected receipted stat success: $result")
                }
                assertEquals(v2, statSnapshot.version)

                // Approve the shell invocation explicitly; execution now succeeds over ShizukuRootBinderTransport
                approveFixtureShell(db, coordinator,
                    authority = Authority.SHIZUKU_ROOT,
                    runId = "run-sh-1",
                    request = shellReq,
                    grantId = shellGrantId,
                    workspaceId = "shizuku-root-ws",
                )
                val reusedModelCallId = ShellExecRequest.fromRuntime(
                    callId = shellReq.callId, command = shellReq.command, cwd = shellReq.cwd,
                    limits = shellReq.limits, agentId = shellReq.agentId, snapshotId = shellReq.snapshotId,
                    selectedAuthority = shellReq.selectedAuthority, dangerousMode = shellReq.dangerousMode,
                    policyVersion = shellReq.policyVersion, configSnapshotHash = shellReq.configSnapshotHash,
                    sessionIdentity = shellReq.sessionIdentity,
                )
                val deniedReuse = executeFixtureShell(coordinator, Authority.SHIZUKU_ROOT, reusedModelCallId, "shizuku-root-ws")
                assertNull(deniedReuse, "A reused model call ID cannot consume another invocation's approval")
                assertEquals(0, shizukuShellCalls)
                val allowedShell = executeFixtureShell(coordinator, Authority.SHIZUKU_ROOT, shellReq, "shizuku-root-ws")
                assertEquals(runtime.mobileagent.domain.RootServiceTerminalOutcome.SUCCEEDED, allowedShell?.terminalOutcome)
                assertEquals("uid=0(SHIZUKU_ROOT):id -u", allowedShell?.shellOutcome?.stdout)
                assertEquals(1, shizukuShellCalls)

                simulateUnknownOutput = true
                assertNotNull(approveFixtureShell(db, coordinator,
                    Authority.SHIZUKU_ROOT, "run-unknown", reusedModelCallId, shellGrantId, "shizuku-root-ws",
                ))
                val unknown = executeFixtureShell(coordinator, Authority.SHIZUKU_ROOT, reusedModelCallId, "shizuku-root-ws")
                assertEquals(runtime.mobileagent.domain.RootServiceTerminalOutcome.UNKNOWN_OUTCOME, unknown?.terminalOutcome)
                assertEquals("", unknown?.shellOutcome?.stdout.orEmpty(), "A withheld UNKNOWN receipt cannot disclose private output")
                assertTrue(coordinator.rootAuthorityRepository.get(Authority.SHIZUKU_ROOT).degraded)

                val receiptRows = db.query("SELECT stage,outcome,closed FROM root_dispatch_receipts")
                assertTrue(receiptRows.size >= 3)
                assertTrue(receiptRows.all { it.string("stage") == "TERMINAL" && it.long("closed") == 1L })
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun suRootDaemonTransportCommunicatesWithRootPrivilegedProcessMainStreamLoop() {
        val tempDir = Files.createTempDirectory("mar-root-pipe-test")
        try {
            val wsDir = Files.createDirectories(tempDir.resolve("workspace"))
            val wsPosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(wsDir)
            val serviceFdOps = InMemoryServiceFdOps(wsPosix)
            val engine = RootPrivilegedServiceEngine(
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },

                fdOps = serviceFdOps,
                serviceUidProvider = { PrivilegeTier.ROOT.uid },
                controlPlaneOverride = boundary,
            ).pinTestHost(boundary, signing)
            val clientToServerOut = java.io.PipedOutputStream()
            val serverIn = java.io.PipedInputStream(clientToServerOut, 65_536)
            val serverToClientOut = java.io.PipedOutputStream()
            val clientIn = java.io.PipedInputStream(serverToClientOut, 65_536)
            val serverThread = Thread {
                RootPrivilegedProcessMain.serveStreamLoop(
                    engine = engine,
                    input = java.io.DataInputStream(serverIn),
                    output = java.io.DataOutputStream(serverToClientOut),
                    parentUid = 10148,
                )
            }.apply {
                isDaemon = true
                start()
            }
            val transport = SuRootDaemonTransport(
                process = null,
                input = java.io.DataInputStream(clientIn),
                output = java.io.DataOutputStream(clientToServerOut),
            )
            val hs = transport.handshake(
                RootServiceHandshakeRequest(
                    authority = Authority.ROOT_DIRECT,
                    identityEpoch = 2L,
                    appUid = 10148,
                    androidUserId = 0,
                    appInstanceId = "app-instance-0001",
                    packageName = "runtime.mobileagent",
                    signingSha256 = signing,
                    apkSha256 = signing,
                    ceDataDir = boundary.credentialEncryptedDataDir,
                    deDataDir = boundary.deviceProtectedDataDir,
                ),
            )!!
            assertTrue(hs.accepted)
            assertEquals(Authority.ROOT_DIRECT, hs.identity!!.authority)
            assertEquals(2L, hs.identity!!.identityEpoch)
            val bind = transport.bindWorkspace(
                RootServiceBindWorkspaceRequest(
                    targetServiceInstanceId = hs.identity!!.serviceInstanceId,
                    targetSessionId = hs.identity!!.sessionId,
                    targetIdentityEpoch = 2L,
                    workspaceId = "root-direct-ws",
                    workspaceGenerationId = "gen-0001-0002-0003",
                    workspaceSessionHandle = "handle-0001-0002-0003",
                    workspaceRootPath = wsPosix,
                    expectedRootDeviceId = 1L,
                    expectedRootInode = 1000L,
                    expectedRootMountId = 77L,
                ),
            )!!
            assertFalse(bind.accepted)
            assertEquals("workspace_binding_protocol_unsupported", bind.errorReason)
            val revoked = transport.revokeAndShutdown(1L, "test_complete")
            assertEquals("REVOKED:1", revoked)
            transport.close()
            serverThread.join(2000)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun round4ReproForeignHandshakeCancelAndNonBlockingDetachDuringSlowCommand() {
        val tempDir = Files.createTempDirectory("mar-root-round4-repro")
        try {
            val wsDir = Files.createDirectories(tempDir.resolve("workspace"))
            val wsPosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(wsDir)
            val serviceFdOps = InMemoryServiceFdOps(wsPosix)
            val startedLatch = java.util.concurrent.CountDownLatch(1)
            val cancelObserved = java.util.concurrent.atomic.AtomicBoolean(false)
            val slowRunner = object : RootShellCommandRunner {
                override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome {
                    startedLatch.countDown()
                    return try {
                        Thread.sleep(5_000L)
                        RootShellRunOutcome(
                            status = ShellExecutionStatus.SUCCEEDED,
                            exitCode = 0,
                            stdout = "done",
                            terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
                        )
                    } catch (_: InterruptedException) {
                        RootShellRunOutcome(
                            status = ShellExecutionStatus.CANCELLED,
                            terminalOutcome = RootServiceTerminalOutcome.FAILED,
                        )
                    }
                }

                override fun cancel(requestId: String): Boolean {
                    cancelObserved.set(true)
                    return true
                }
            }
            val serviceEngine = RootPrivilegedServiceEngine(
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },

                fdOps = serviceFdOps,
                shellRunner = slowRunner,
                serviceUidProvider = { PrivilegeTier.ROOT.uid },
                controlPlaneOverride = boundary,
            ).pinTestHost(boundary, signing)
            val binderStub = object : IRootPrivilegedService {
                override fun asBinder(): android.os.IBinder = java.lang.reflect.Proxy.newProxyInstance(
                    android.os.IBinder::class.java.classLoader, arrayOf(android.os.IBinder::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "isBinderAlive", "pingBinder" -> true
                        else -> throw UnsupportedOperationException(method.name)
                    }
                } as android.os.IBinder
                override fun handshake(requestJson: String): String = serviceEngine.handshake(requestJson, 10148)
                override fun bindWorkspace(requestJson: String): String = serviceEngine.bindWorkspace(requestJson, 10148)
                override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): String =
                    serviceEngine.submitEnvelope(controlSequence, rawEnvelopeJson, 10148)
                override fun executeAccepted(rawEnvelopeJson: String, payloadBytes: ByteArray?): String =
                    serviceEngine.executeAccepted(rawEnvelopeJson, payloadBytes ?: ByteArray(0), 10148)
                override fun submitAndExecute(controlSequence: Long, rawEnvelopeJson: String, payloadBytes: ByteArray?): String =
                    serviceEngine.submitAndExecute(controlSequence, rawEnvelopeJson, payloadBytes ?: ByteArray(0), 10148)
                override fun cancelInvocation(requestJson: String): Boolean = serviceEngine.cancelInvocation(requestJson, 10148)
                override fun queryQuiescence(requestJson: String): String = serviceEngine.queryQuiescence(requestJson, 10148)
                override fun cancelCommand(requestId: String): Boolean = serviceEngine.cancelCommand(requestId, 10148)
                override fun revokeAndShutdown(controlSequence: Long, reason: String): String =
                    serviceEngine.revokeAndShutdown(controlSequence, reason, 10148)
                override fun destroy() = serviceEngine.close()
            }
            val transport = ShizukuRootBinderTransport(binderStub)
            val validHs = transport.handshake(
                RootServiceHandshakeRequest(
                    authority = Authority.SHIZUKU_ROOT,
                    identityEpoch = 1L,
                    appUid = 10148,
                    androidUserId = 0,
                    appInstanceId = "app-instance-0001",
                    packageName = "runtime.mobileagent",
                    signingSha256 = signing,
                    apkSha256 = signing,
                    ceDataDir = boundary.credentialEncryptedDataDir,
                    deDataDir = boundary.deviceProtectedDataDir,
                ),
            )!!
            assertTrue(validHs.accepted)

            // Finding 2: Foreign UID/package/hash handshake is rejected and does NOT replace activeSession
            val wireJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val foreignRaw = serviceEngine.handshake(
                wireJson.encodeToString(
                    RootServiceHandshakeRequest.serializer(),
                    RootServiceHandshakeRequest(
                        authority = Authority.SHIZUKU_ROOT,
                        identityEpoch = 99L,
                        appUid = 42424,
                        androidUserId = 0,
                        appInstanceId = "foreign-instance",
                        packageName = "unrelated.fake.package",
                        signingSha256 = "f".repeat(64),
                        apkSha256 = "e".repeat(64),
                        ceDataDir = boundary.credentialEncryptedDataDir,
                        deDataDir = boundary.deviceProtectedDataDir,
                    ),
                ),
                42424,
            )
            val foreignResp = wireJson.decodeFromString(RootServiceHandshakeResponse.serializer(), foreignRaw)
            assertFalse(foreignResp.accepted)
            assertEquals(10148, serviceEngine.activeSession?.identity?.appUid)

            InMemoryRootSqlConnection().use { db ->
                Migrations.apply(db)
                val policyRepo = AuthorityPolicyRepository(db)
                policyRepo.updatePolicy(0, Authority.SHIZUKU_ROOT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
                val authorityManager = AuthorityManager(InMemoryAuthorityStateStore())
                val caller = RootCallerObservation(
                    callingUid = 10148,
                    observedServiceUid = 0,
                    packageName = "runtime.mobileagent",
                    androidUserId = 0,
                    signingSha256 = signing,
                    binderAlive = true,
                )
                val locatorCipher = TestRootLocatorCipher()
                val coordinator = RootRuntimeCoordinator(
                    context = FakeTestContext(tempDir.toFile()),
                    db = db,
                    fdOps = serviceFdOps,
                    sessionConnector = object : RootSessionConnector {
                        override fun tryAcquireSession(
                            authority: Authority,
                            identityEpoch: Long,
                            appInstanceId: String,
                            shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
                        ): RootAcquiredSession? = RootAcquiredSession(validHs.identity!!, caller, transport, boundary)
                    },

                    controlPlaneBoundaryOverride = boundary,
                    locatorCipher = locatorCipher,
                )
                coordinator.setUserIntent(
                    authority = Authority.SHIZUKU_ROOT,
                    enabled = true,
                    authorityManager = authorityManager,
                    dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                    shizukuState = null,
                )
                coordinator.setRootShellConsent(Authority.SHIZUKU_ROOT, enabled = true, userConfirmed = true, authorityManager = authorityManager)
                provisionFixtureWorkspaceAndSnapshot(
                    db = db,
                    coordinator = coordinator,
                    fdOps = serviceFdOps,
                    locatorCipher = locatorCipher,
                    authority = Authority.SHIZUKU_ROOT,
                    workspaceId = "ws-slow",
                    workspaceRootPath = wsDir,
                    agentId = "agent-1",
                    snapshotId = "snap-1",
                    capabilities = setOf(
                        runtime.mobileagent.domain.CapabilityId(runtime.mobileagent.domain.CapabilityId.SHELL_EXECUTE),
                    ),
                )
                val slowReq = ShellExecRequest.fromRuntime(
                    callId = "call-slow-1",
                    command = "sleep 5",
                    cwd = null, // Workspace-relative default; no discarded legacy absolute cwd.
                    limits = ShellLimits(10_000L, 4096L),
                    agentId = "agent-1",
                    snapshotId = "snap-1",
                    selectedAuthority = Authority.SHIZUKU_ROOT,
                    dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                    policyVersion = policyRepo.getPolicy().policyVersion,
                    configSnapshotHash = "hash",
                    sessionIdentity = "ses-1",
                    toolSchemaVersion = 1,
                )
                approveFixtureShell(db, coordinator,
                    authority = Authority.SHIZUKU_ROOT,
                    runId = "run-slow-1",
                    request = slowReq,
                    grantId = "grant-snap-1-shell-execute",
                    workspaceId = "ws-slow",
                )

                val resultHolder = java.util.concurrent.atomic.AtomicReference<RootServiceExecuteResponse>()
                val worker = Thread {
                    resultHolder.set(executeFixtureShell(coordinator, Authority.SHIZUKU_ROOT, slowReq, "ws-slow"))
                }.apply { start() }

                assertTrue(startedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))

                // Delivery is observed below; signalling alone is not termination confirmation.
                assertFalse(coordinator.cancellationRoute(requireNotNull(coordinator.currentExecutionStamp(Authority.SHIZUKU_ROOT)))(slowReq.requestId))
                assertTrue(cancelObserved.get())

                // Finding 3b: detachSession completes immediately (<1000ms) without blocking on 5s command
                // and settles in-flight command as UNKNOWN_OUTCOME (never SUCCEEDED).
                val t0 = System.nanoTime()
                coordinator.detachSession(Authority.SHIZUKU_ROOT, "user_revoked")
                val detachElapsedMs = (System.nanoTime() - t0) / 1_000_000L
                assertTrue(detachElapsedMs < 1_000L, "detachSession took ${detachElapsedMs}ms")

                worker.join(2_000L)
                val settled = resultHolder.get()
                assertNotNull(settled)
                assertEquals(runtime.mobileagent.domain.RootServiceTerminalOutcome.UNKNOWN_OUTCOME, settled!!.terminalOutcome)

                // Finding 6: independent per-channel rootShellConsentEnabled exposed in Settings provider state
                assertTrue(coordinator.buildSettingsProviderState(Authority.SHIZUKU_ROOT, authorityManager).rootShellConsentEnabled)
                assertFalse(coordinator.buildSettingsProviderState(Authority.ROOT_DIRECT, authorityManager).rootShellConsentEnabled)
                coordinator.setRootShellConsent(
                    authority = Authority.SHIZUKU_ROOT,
                    enabled = false,
                    userConfirmed = true,
                    authorityManager = authorityManager,
                    shizukuState = null,
                )
                assertFalse(coordinator.buildSettingsProviderState(Authority.SHIZUKU_ROOT, authorityManager).rootShellConsentEnabled)
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}

internal fun RootPrivilegedServiceEngine.pinTestHost(boundary: RootControlPlaneBoundary, signing: String) = apply {
    pinHostBootstrap(RootServiceHostBootstrap(10148, 0, "runtime.mobileagent", signing, signing,
        boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir))
}

private fun SqlRow.singleCount(): Long = long("c") ?: 0L

internal class FakeTestContext(private val baseDir: File) : ContextWrapper(null) {
    override fun getApplicationContext() = this
    override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
    override fun getDataDir(): File = baseDir
    override fun createDeviceProtectedStorageContext() = this
}

internal class InMemoryRootSqlConnection : SqlConnection, AutoCloseable {
    private val connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @Synchronized
    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { stmt ->
            args.forEachIndexed { idx, value -> stmt.setObject(idx + 1, value) }
            stmt.execute()
        }
    }

    @Synchronized
    override fun query(sql: String, args: List<Any?>): List<SqlRow> =
        connection.prepareStatement(sql).use { stmt ->
            args.forEachIndexed { idx, value -> stmt.setObject(idx + 1, value) }
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SqlRow(
                                (1..rs.metaData.columnCount).associate { col ->
                                    rs.metaData.getColumnLabel(col) to rs.getObject(col)
                                },
                            ),
                        )
                    }
                }
            }
        }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()
}

internal class InMemoryServiceFdOps(private val rootPath: String) : PosixFdOperations {
    private var nextInode = 3000L
    private var nextFd = 10
    private var nextTick = 1L
    private val rootNode = EntryNode(1000L, true, 448, 0, 0, "u:object_r:app_data_file:s0", 2L)
    private val entries = LinkedHashMap<String, EntryNode>()

    data class EntryNode(
        var inode: Long,
        val isDir: Boolean,
        var mode: Int,
        var uid: Int,
        var gid: Int,
        var selinux: String,
        var nlink: Long = 1L,
        var bytes: ByteArray = ByteArray(0),
        var tick: Long = 1L,
    )

    private class Handle(
        override val fdNumber: Int,
        val node: EntryNode,
        val resolvedPath: String,
    ) : PosixRetainedFd {
        override var isOpen: Boolean = true
        override fun close() {
            isOpen = false
        }
    }

    fun readText(name: String): String? = entries[name]?.bytes?.toString(Charsets.UTF_8)

    fun seedText(name: String, text: String) {
        entries[name] = EntryNode(nextInode++, false, 384, 0, 0, "u:object_r:app_data_file:s0", 1L, text.toByteArray(Charsets.UTF_8), nextTick++)
    }

    override fun openDirectoryAbsoluteNoFollow(absolutePath: String): PosixRetainedFd? =
        if (absolutePath == rootPath) Handle(nextFd++, rootNode, rootPath) else null

    override fun openChildDirectoryAtNoFollow(parentFd: PosixRetainedFd, childName: String): PosixRetainedFd? = null

    override fun openTargetAt(
        parentFd: PosixRetainedFd,
        childName: String,
        followSymlink: Boolean,
        writable: Boolean,
    ): PosixRetainedFd {
        val node = entries[childName] ?: throw PosixOpException("ENOENT")
        return Handle(nextFd++, node, "$rootPath/$childName")
    }

    override fun statAtNoFollow(parentFd: PosixRetainedFd, childName: String): RootFdNodeIdentity? =
        entries[childName]?.let(::toId)

    override fun fstatIdentity(fd: PosixRetainedFd): RootFdNodeIdentity? =
        (fd as? Handle)?.takeIf { it.isOpen }?.node?.let(::toId)

    override fun fstatSizeBytes(fd: PosixRetainedFd): Long? =
        (fd as? Handle)?.node?.bytes?.size?.toLong()

    override fun fstatVersionStamp(fd: PosixRetainedFd): String =
        (fd as? Handle)?.node?.let { "${it.tick}:${it.bytes.contentHashCode()}" }.orEmpty()

    override fun readFdRange(fd: PosixRetainedFd, offsetBytes: Long, maxBytes: Int): ByteArray {
        val handle = fd as? Handle ?: throw PosixOpException("EBADF")
        if (!handle.isOpen) throw PosixOpException("EBADF")
        if (handle.node.isDir) throw PosixOpException("EISDIR")
        if (offsetBytes < 0L || maxBytes < 0) throw PosixOpException("EINVAL")
        val bytes = handle.node.bytes
        if (offsetBytes >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offsetBytes + maxBytes).toInt()
        return bytes.copyOfRange(offsetBytes.toInt(), end)
    }

    override fun fstatContentDigest(fd: PosixRetainedFd): String? {
        val handle = fd as? Handle ?: return null
        if (!handle.isOpen || handle.node.isDir) return null
        return java.security.MessageDigest.getInstance("SHA-256").digest(handle.node.bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun listChildNames(dirFd: PosixRetainedFd): List<String> {
        val handle = dirFd as? Handle ?: throw PosixOpException("EBADF")
        if (!handle.isOpen) throw PosixOpException("EBADF")
        if (!handle.node.isDir) throw PosixOpException("ENOTDIR")
        return entries.keys.toList()
    }

    override fun readFdResolvedPath(fd: PosixRetainedFd): String? = (fd as? Handle)?.resolvedPath

    override fun fchmod(fd: PosixRetainedFd, modeOctal: Int) {
        val h = fd as? Handle ?: throw PosixOpException("EBADF")
        h.node.mode = modeOctal and 0b111_111_111
        h.node.tick = nextTick++
    }

    override fun fchown(fd: PosixRetainedFd, ownerUid: Int, ownerGid: Int) {
        val h = fd as? Handle ?: throw PosixOpException("EBADF")
        h.node.uid = ownerUid
        h.node.gid = ownerGid
        h.node.tick = nextTick++
    }

    override fun fsetSelinuxLabel(fd: PosixRetainedFd, selinuxLabel: String) {
        val h = fd as? Handle ?: throw PosixOpException("EBADF")
        h.node.selinux = selinuxLabel
        h.node.tick = nextTick++
    }

    override fun createSiblingTempAt(parentFd: PosixRetainedFd, tempName: String, modeOctal: Int): PosixRetainedFd {
        if (entries.containsKey(tempName)) throw PosixOpException("EEXIST")
        val n = EntryNode(nextInode++, false, modeOctal and 0b111_111_111, 0, 0, "u:object_r:app_data_file:s0", 1L, ByteArray(0), nextTick++)
        entries[tempName] = n
        return Handle(nextFd++, n, "$rootPath/$tempName")
    }

    override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
        val h = fd as? Handle ?: throw PosixOpException("EBADF")
        h.node.bytes = bytes.copyOf()
        h.node.tick = nextTick++
    }

    override fun fsyncDirectory(dirFd: PosixRetainedFd) = Unit

    override fun renameReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        val temp = entries.remove(tempName) ?: throw PosixOpException("ENOENT")
        entries[targetName] = temp
    }

    override fun linkNoReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        if (entries.containsKey(targetName)) throw PosixOpException("EEXIST")
        val temp = entries[tempName] ?: throw PosixOpException("ENOENT")
        entries[targetName] = temp
    }

    override fun unlinkAt(parentFd: PosixRetainedFd, childName: String): Boolean =
        entries.remove(childName) != null

    private fun toId(n: EntryNode) = RootFdNodeIdentity(
        deviceId = 1L,
        inode = n.inode,
        mountId = 77L,
        isDirectory = n.isDir,
        isRegularFile = !n.isDir,
        isSymlink = false,
        hardlinkCount = n.nlink,
        modeOctal = n.mode,
        ownerUid = n.uid,
        ownerGid = n.gid,
        selinuxLabel = n.selinux,
    )
}
