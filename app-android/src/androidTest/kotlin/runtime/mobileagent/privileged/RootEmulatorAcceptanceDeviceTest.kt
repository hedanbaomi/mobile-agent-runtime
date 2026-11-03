// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.RunProductivityTools
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.shizuku.ShizukuShellRequest
import runtime.mobileagent.shizuku.ShizukuShellResult
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext

/**
 * Emulator acceptance suite for R51 (`ROOT01`–`ROOT18`, `ROOT20`) under `ADR-0054`.
 */
@RunWith(AndroidJUnit4::class)
class RootEmulatorAcceptanceDeviceTest {
    @Test
    fun directSuNegativeControlRefusesStockUnprivilegedAppWithoutBackgroundAutoRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        val db = app.container.db
        val coordinator = RootRuntimeCoordinator(context, db)
        try {
            assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
            assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.SHIZUKU_ROOT))
            val connector = AndroidRootSessionConnector(context)
            val acquired = connector.tryAcquireSession(
                authority = Authority.ROOT_DIRECT,
                identityEpoch = 1L,
                appInstanceId = "emu-direct-neg-${UUID.randomUUID()}",
                shizukuState = null,
            )
            assertNull("Unprivileged app without su manager grant must fail closed", acquired)
            assertFalse(coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun nonRootShizukuUid2000AndHostUidRefuseRootChannelAndForeign0700Fixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requireUid2000 = args.getString("expectShizukuServerUid") == "2000"
        val foreignDir = args.getString("foreignFixtureDir").orEmpty()
        if (!requireUid2000 && foreignDir.isBlank()) {
            assumeTrue("Skipped unless expectShizukuServerUid=2000 or foreignFixtureDir is provided", false)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (foreignDir.isNotBlank()) {
            val payloadPath = "$foreignDir/payload"
            val hostStatDenied = try {
                Os.stat(payloadPath)
                false
            } catch (err: ErrnoException) {
                err.errno == OsConstants.EACCES || err.errno == OsConstants.ENOENT
            }
            assertTrue("Host app UID (${Process.myUid()}) must be denied by 0700 foreign directory (EACCES/ENOENT)", hostStatDenied)
            val hostOpenDenied = try {
                val fd = Os.open(payloadPath, OsConstants.O_RDONLY, 0)
                Os.close(fd)
                false
            } catch (err: ErrnoException) {
                err.errno == OsConstants.EACCES || err.errno == OsConstants.ENOENT
            }
            assertTrue("Host app UID (${Process.myUid()}) must not open foreign 0600 file", hostOpenDenied)
        }
        if (requireUid2000) {
            val bridge = ShizukuAuthorityBridge(context)
            try {
                assertTrue("UID 2000 Shizuku server must be reachable", waitForShizukuBinderAndPermission())
                assertEquals("Expected non-root UID 2000 Shizuku server", 2000, Shizuku.getUid())
                assertTrue("UID 2000 UserService must bind", bridge.bindUserService() && waitForBridgeReady(bridge))
                val state2000 = bridge.refresh()
                assertTrue("UID 2000 bridge must be alive and granted", state2000.binderAlive && state2000.permissionGranted && !state2000.preV11)
                assertEquals("Observed serverUid must be 2000 without copy()", 2000, state2000.serverUid)
                assertEquals("Observed userServiceUid must be 2000 without copy()", 2000, state2000.userServiceUid)
                val connector = AndroidRootSessionConnector(context)
                val rootAttempt = connector.tryAcquireSession(
                    authority = Authority.SHIZUKU_ROOT,
                    identityEpoch = 1L,
                    appInstanceId = "emu-uid2000-neg-${UUID.randomUUID()}",
                    shizukuState = state2000,
                )
                assertNull("SHIZUKU_ROOT must reject UID 2000 Shizuku server without fallback", rootAttempt)
                val failureReason = connector.lastAcquireFailureReason.orEmpty()
                assertTrue(
                    "Expected shizuku_gate_denied / shizuku_running_as_shell_uid_2000, got '$failureReason'",
                    failureReason.contains("shizuku_running_as_shell_uid_2000") || failureReason.startsWith("shizuku_gate_denied:"),
                )
                if (foreignDir.isNotBlank()) {
                    val shellRes = bridge.executeShell(
                        ShizukuShellRequest(
                            callId = "uid2000-cat-${UUID.randomUUID()}",
                            command = "cat '$foreignDir/payload'",
                            timeoutMs = 5_000L,
                            maxStdoutBytes = 4096,
                            maxStderrBytes = 4096,
                        ),
                    )
                    assertEquals(ShizukuShellResult.State.COMPLETED, shellRes.state)
                    assertTrue("UID 2000 shell must fail on 0700 foreign app directory", (shellRes.exitCode ?: 0) != 0)
                    assertFalse("UID 2000 stdout must never contain foreign payload", String(shellRes.stdout).contains("FD_PROBE"))
                }
            } finally {
                bridge.close()
            }
        }
    }

    @Test
    fun liveRootShizukuUid0ExecutesFullRootMatrixOnForeign0700Fixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requireRootShizuku = args.getString("requireRootShizuku").equals("true", ignoreCase = true)
        val foreignDir = args.getString("foreignFixtureDir").orEmpty()
        if (!requireRootShizuku) {
            assumeTrue("Skipped unless -e requireRootShizuku true is passed", false)
        }
        assertTrue("foreignFixtureDir argument is required for live Root Shizuku matrix", foreignDir.isNotBlank())
        assertTrue("Root Shizuku binder and permission must be ready", waitForShizukuBinderAndPermission())
        assertEquals("Shizuku server must run as UID 0 for SHIZUKU_ROOT", 0, Shizuku.getUid())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        val db = app.container.db
        val connector = AndroidRootSessionConnector(context)
        val coordinator = RootRuntimeCoordinator(context, db, sessionConnector = connector)
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        val bridge = ShizukuAuthorityBridge(context)
        val shizukuRootState = bridge.refresh()
        assertTrue("Observed Shizuku binder must be alive and granted", shizukuRootState.binderAlive && shizukuRootState.permissionGranted && !shizukuRootState.preV11)
        assertEquals("Observed Shizuku serverUid must be 0 without copy()", 0, shizukuRootState.serverUid)
        assertNull("Legacy shell bridge must leave userServiceUid null on UID 0 server", shizukuRootState.userServiceUid)
        assertEquals("Legacy shell bridge must reject UID 0 server as SERVER_UID_UNTRUSTED", ShizukuAuthorityBridge.SERVER_UID_UNTRUSTED, shizukuRootState.errorCode)
        try {
            // Fixture precondition supplied out-of-test: the专用 emulator must be reset to a
            // fresh install for this authority. Authorization facts are never deleted here.
            val initialRecord = runtime.mobileagent.data.RootAuthorityRepository(db).get(Authority.SHIZUKU_ROOT)
            assertFalse("fresh-install precondition violated for SHIZUKU_ROOT: consent already enabled", initialRecord.consent.enabled)
            assertFalse("fresh-install precondition violated for SHIZUKU_ROOT: shell consent already enabled", initialRecord.consent.shellEnabled)
            assertFalse("fresh-install precondition violated for SHIZUKU_ROOT: degraded", initialRecord.degraded)
            assertEquals("fresh-install precondition violated for SHIZUKU_ROOT: identityEpoch", 0L, initialRecord.identityEpoch)
            executeLiveRootMatrixForAuthority(
                context = context,
                app = app,
                db = db,
                coordinator = coordinator,
                connector = connector,
                manager = manager,
                authority = Authority.SHIZUKU_ROOT,
                shizukuState = shizukuRootState,
                foreignDir = foreignDir,
            )
        } finally {
            bridge.close()
            coordinator.close()
        }
    }

    @Test
    fun liveRootDirectDaemonExecutesUid0PipeProtocolAndNativeSubreaperWhenBootstrapped() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val tcpPort = args.getString("rootDirectTcpPort")?.toIntOrNull() ?: 0
        val fifoDirPath = args.getString("rootDirectFifoDir").orEmpty()
        val foreignDir = args.getString("foreignFixtureDir").orEmpty()
        if (tcpPort <= 0 && fifoDirPath.isBlank()) {
            assumeTrue("Skipped unless -e rootDirectTcpPort or -e rootDirectFifoDir is passed", false)
        }
        assertTrue("foreignFixtureDir argument is required", foreignDir.isNotBlank())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as MobileAgentApp
        app.ensureHostInitialized()
        val db = app.container.db
        var activeSocket: java.net.Socket? = null
        val connector = AndroidRootSessionConnector(
            context = context,
            directTransportFactory = { _, _ ->
                if (tcpPort > 0) {
                    val sock = java.net.Socket("127.0.0.1", tcpPort)
                    activeSocket = sock
                    SuRootDaemonTransport(
                        process = null,
                        input = DataInputStream(sock.getInputStream()),
                        output = DataOutputStream(sock.getOutputStream()),
                    )
                } else {
                    val fifoDir = File(fifoDirPath)
                    val c2sFile = File(fifoDir, "c2s.pipe")
                    val s2cFile = File(fifoDir, "s2c.pipe")
                    SuRootDaemonTransport(
                        process = null,
                        input = DataInputStream(FileInputStream(s2cFile)),
                        output = DataOutputStream(FileOutputStream(c2sFile)),
                    )
                }
            },
        )
        val coordinator = RootRuntimeCoordinator(context, db, sessionConnector = connector)
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        try {
            // Fixture precondition supplied out-of-test: the专用 emulator must be reset to a
            // fresh install for this authority. Authorization facts are never deleted here.
            assertFalse(
                "fresh-install precondition violated for ROOT_DIRECT: consent already enabled",
                runtime.mobileagent.data.RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).consent.enabled,
            )
            executeLiveRootMatrixForAuthority(
                context = context,
                app = app,
                db = db,
                coordinator = coordinator,
                connector = connector,
                manager = manager,
                authority = Authority.ROOT_DIRECT,
                shizukuState = null,
                foreignDir = foreignDir,
            )
        } finally {
            coordinator.close()
            runCatching { activeSocket?.close() }
        }
    }

    private suspend fun executeLiveRootMatrixForAuthority(
        context: Context,
        app: MobileAgentApp,
        db: SqlConnection,
        coordinator: RootRuntimeCoordinator,
        connector: AndroidRootSessionConnector,
        manager: AuthorityManager,
        authority: Authority,
        shizukuState: ShizukuAuthorityState?,
        foreignDir: String,
    ) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val agentId = "agent-root-$suffix"
        val providerId = "provider-root-$suffix"
        val modelId = "model-root-$suffix"
        val snapshotId = "snapshot-root-$suffix"
        val convId = "conv-root-$suffix"

        val policyRepo = AuthorityPolicyRepository(db)
        policyRepo.updatePolicy(
            expectedPolicyVersion = policyRepo.getPolicy().policyVersion,
            selectedAuthority = authority,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
        )
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile(providerId, "Root Emu Provider", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
        profiles.createModel(ModelProfile(modelId, providerId, ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16384, outputLimit = 512, revision = 1))
        AgentRepository(db).saveWithPrompt(AgentProfile(agentId, "Root Emu Agent", "pending", modelId, revision = 0), "Synthetic prompt")

        // 1. Verify foreground session acquisition & identity pinning
        coordinator.setUserIntent(
            authority = authority,
            enabled = true,
            authorityManager = manager,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            shizukuState = shizukuState,
        )
        assertTrue(
            "Authority $authority must be ready after foreground session acquisition " +
                "(connectorFailure=${connector.lastAcquireFailureReason}, " +
                "boundary=${coordinator.observeControlPlaneBoundary() != null}, " +
                "record=${RootAuthorityRepository(db).get(authority)})",
            coordinator.isAuthorityReadyForDispatch(authority),
        )
        val fileExecution = requireNotNull(coordinator.currentExecutionStamp(authority, requireShellConsent = false))
        assertEquals(0, fileExecution.identity.serviceUid)
        assertEquals(Process.myUid(), fileExecution.identity.appUid)
        assertEquals(authority, fileExecution.identity.authority)
        assertEquals(PrivilegeTier.ROOT, fileExecution.identity.privilegeTier)

        // DangerousMode alone without shellConsentGranted must NOT expose shell execution stamp
        assertNull("Shell consent must be separate from DangerousMode", coordinator.currentExecutionStamp(authority, requireShellConsent = true))

        // Grant shell consent explicitly for this Root channel
        coordinator.setRootShellConsent(
            authority = authority,
            enabled = true,
            userConfirmed = true,
            authorityManager = manager,
            shizukuState = shizukuState,
        )
        val fullExecution = requireNotNull(coordinator.currentExecutionStamp(authority, requireShellConsent = true))
        val otherRootAuthority = if (authority == Authority.SHIZUKU_ROOT) Authority.ROOT_DIRECT else Authority.SHIZUKU_ROOT
        val otherRecord = RootAuthorityRepository(db).get(otherRootAuthority)
        assertFalse("Enabling $authority must not enable $otherRootAuthority consent", otherRecord.consent.enabled)
        assertFalse("Enabling $authority shell consent must not enable $otherRootAuthority shell consent", otherRecord.consent.shellEnabled)
        assertNull("Unenabled channel $otherRootAuthority must not have an execution stamp", coordinator.currentExecutionStamp(otherRootAuthority, requireShellConsent = false))

        // 2. Verify control-plane self-modification refusal (ROOT10)
        val selfAttach = RootWorkspaceUiController(db, coordinator).attach(
            authority = authority,
            path = context.filesDir.absolutePath,
            writable = true,
            metadata = false,
            shell = false,
            target = WorkspacePickerTarget(agentId),
        )
        assertTrue("Attaching app's own control-plane filesDir must be refused", selfAttach is WorkspaceAccessResult.Failure)

        // 3. Attach foreign 0700 non-debuggable app private directory (ROOT09 / ROOT13)
        val attached = RootWorkspaceUiController(db, coordinator).attach(
            authority = authority,
            path = foreignDir,
            writable = true,
            metadata = true,
            shell = true,
            target = WorkspacePickerTarget(agentId),
        )
        assertTrue("Attaching foreign 0700 private directory must succeed via UID 0 service: $attached", attached is WorkspaceAccessResult.Success)
        val workspaceId = (attached as WorkspaceAccessResult.Success).workspace.workspaceId
        val workspace = requireNotNull(WorkspaceRepository(db).get(workspaceId))
        val grants = CapabilityGrantRepository(db).forAgent(agentId, includeRevoked = false)
        val snapshot = coordinator.sealNewSnapshot(
            authority = authority,
            snapshotId = snapshotId,
            agentId = agentId,
            grantIds = grants.mapTo(mutableSetOf()) { it.grantId },
        ) {
            AgentRepository(db).createSnapshot(agentId, snapshotId)
        }
        val now = Utc.nowIso()
        db.execute(
            "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            listOf(convId, snapshot.id, snapshot.id, "Root Emu", now, now),
        )

        val runId = "run-root-$suffix"
        val runs = RunRepository(db)
        RunCoordinator(runs, coordinator::admitNewRun).prepare(RunRecord(runId, snapshot.id, convId, createdAt = now), "owner")
        db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))

        val execContext = ToolExecutionContext(
            agentId,
            snapshot.id,
            "model-context",
            convId,
            runId,
            "cfg-$suffix",
            fullExecution.policyVersion,
            canonicalGrants = grants,
            snapshotGrantBindings = CapabilityGrantRepository(db).listSnapshotBindings(snapshot.id),
        )
        val backend = RootRuntimeToolBackend(db, coordinator, execContext, fullExecution)
        val rootExecutor = RootToolExecutor(RootToolArguments.specs(), backend)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val productivity = RunProductivityTools(
            core = rootExecutor,
            scope = scope,
            liveContext = { true },
            reserveCalls = { it },
        )

        fun recordAndApprove(baseCallId: String, toolName: String, argsJson: String): ToolResult = runBlocking {
            val callId = "$baseCallId-$suffix"
            val call = ToolCall(callId, toolName, argsJson)
            val first = productivity.executor.invoke(call)
            assertEquals("Expected NeedsApproval for $toolName ($callId), got $first", ToolResult.NeedsApproval, first)
            val runtimeId = productivity.runtimeInvocationId(callId) ?: callId
            db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
            runs.addInvocation(
                ToolInvocation(
                    invocationId = runtimeId,
                    runId = runId,
                    callId = callId,
                    name = toolName,
                    argumentsJson = argsJson,
                    state = "WAITING_APPROVAL",
                    permissionDecision = "PENDING",
                    createdAt = Utc.nowIso(),
                ),
            )
            val approved = productivity.executor.approve(callId)
            val existing = runs.invocations(runId).single { it.invocationId == runtimeId }
            val resultJson = (approved as? ToolResult.Value)?.json
            runs.observeRuntimeInvocation(
                existing.copy(
                    state = if (approved is ToolResult.Value) "SUCCEEDED" else "FAILED",
                    permissionDecision = "APPROVED",
                    resultJson = resultJson,
                ),
            )
            approved
        }

        // 4. Verify file_stat, file_get_metadata, and file_read_text on foreign 0700/0600 fixture payload
        val statPayloadArgs = buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", "payload")
        }.toString()
        val statRes = recordAndApprove("stat-payload", "file_stat", statPayloadArgs)
        assertTrue("file_stat on foreign payload must succeed: $statRes", statRes is ToolResult.Value)
        assertFalse("Tool output must never leak real absolute path", (statRes as ToolResult.Value).json.contains(foreignDir))

        val metaRes = recordAndApprove("meta-payload", "file_get_metadata", statPayloadArgs)
        assertTrue("file_get_metadata on foreign payload must succeed: $metaRes", metaRes is ToolResult.Value)
        val metaJson = Json.parseToJsonElement((metaRes as ToolResult.Value).json).jsonObject
        val foreignOwnerUid = metaJson["owner_uid"]!!.jsonPrimitive.content.toInt()
        val initialVersion = metaJson["version"]!!.jsonPrimitive.content
        assertTrue("Foreign fixture owner UID ($foreignOwnerUid) must not be 0 or host UID (${Process.myUid()})",
            foreignOwnerUid >= 10000 && foreignOwnerUid != Process.myUid())

        val readPayloadArgs = buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", "payload")
            put("max_bytes", 1024)
        }.toString()
        val readRes = recordAndApprove("read-payload", "file_read_text", readPayloadArgs)
        assertTrue("file_read_text on foreign payload must succeed: $readRes", readRes is ToolResult.Value)
        assertTrue((readRes as ToolResult.Value).json.contains("FD_PROBE_V1"))

        // 5. Conditional writes are refused by contract (N2/ADR-0032 revised): in a non-UID0
        // exclusive domain a valid `expected_version` patch must be rejected *before* writing and
        // the payload/version must stay unchanged. The old expectation (patch succeeds) predates
        // that revision.
        val conditionalPatchArgs = buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", "payload")
            put("expected_version", initialVersion)
            put("format", "search_replace")
            put("patch", buildJsonObject {
                put("search", "FD_PROBE_V1")
                put("replace", "MUST_NOT_APPLY")
            }.toString())
        }.toString()
        val conditionalRes = recordAndApprove("patch-conditional", "file_apply_patch", conditionalPatchArgs)
        assertTrue(
            "conditional patch outside the UID0-exclusive domain must fail: $conditionalRes",
            conditionalRes is ToolResult.Failure,
        )
        assertEquals(
            "conditional write must report the version-unsupported contract code",
            ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED,
            (conditionalRes as ToolResult.Failure).error.code,
        )

        // Content and version must be byte-identical after the refusal.
        val afterReadRes = recordAndApprove("read-after-refusal", "file_read_text", readPayloadArgs)
        assertTrue("payload must still be readable after a refusal: $afterReadRes", afterReadRes is ToolResult.Value)
        assertTrue(
            "payload content must be unchanged after a refused conditional write",
            (afterReadRes as ToolResult.Value).json.contains("FD_PROBE_V1") &&
                !afterReadRes.json.contains("MUST_NOT_APPLY"),
        )
        val afterMetaRes = recordAndApprove("meta-after-refusal", "file_get_metadata", statPayloadArgs)
        assertTrue("metadata must still be readable: $afterMetaRes", afterMetaRes is ToolResult.Value)
        val afterMetaJson = Json.parseToJsonElement((afterMetaRes as ToolResult.Value).json).jsonObject
        assertEquals(
            "version must be unchanged after a refused conditional write",
            initialVersion,
            afterMetaJson["version"]!!.jsonPrimitive.content,
        )

        // 6. Verify CRUD (file_create_directory, file_write_text, file_list, file_move, file_delete) & file_glob / file_grep
        assertTrue(recordAndApprove("mkdir-sub", "file_create_directory", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub")
        }.toString()) is ToolResult.Value)
        assertTrue(recordAndApprove("write-sub", "file_write_text", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub/note.txt"); put("text", "alpha-beta-gamma")
        }.toString()) is ToolResult.Value)
        val listSubRes = recordAndApprove("list-sub", "file_list", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub")
        }.toString())
        assertTrue("file_list must succeed: $listSubRes", listSubRes is ToolResult.Value && listSubRes.json.contains("note.txt"))
        val globRes = recordAndApprove("glob-sub", "file_glob", buildJsonObject {
            put("workspace_id", workspace.id); put("pattern", "**/*.txt")
        }.toString())
        assertTrue("file_glob must succeed: $globRes", globRes is ToolResult.Value && globRes.json.contains("emu-sub/note.txt"))
        val grepRes = recordAndApprove("grep-sub", "file_grep", buildJsonObject {
            put("workspace_id", workspace.id); put("query", "alpha-beta")
        }.toString())
        assertTrue("file_grep must succeed: $grepRes", grepRes is ToolResult.Value && grepRes.json.contains("emu-sub/note.txt"))

        // 7. Verify W3 Native Supervisor (libmar_root_supervisor.so) via shell_exec in UID 0 service
        val shellUidRes = recordAndApprove("shell-uid", "shell_exec", buildJsonObject {
            put("workspace_id", workspace.id)
            put("command", "id -u")
        }.toString())
        assertTrue("shell_exec id -u must succeed: $shellUidRes", shellUidRes is ToolResult.Value)
        val shellUidJson = Json.parseToJsonElement((shellUidRes as ToolResult.Value).json).jsonObject
        assertEquals("0", shellUidJson["stdout"]!!.jsonPrimitive.content.trim())
        assertEquals(0, shellUidJson["exit_code"]!!.jsonPrimitive.content.toInt())

        // Create >8 MiB (9 MiB) file via shell_exec dd, then test streaming file_stat & windowed file_read_text (W5)
        val createLargeRes = recordAndApprove("shell-dd-9m", "shell_exec", buildJsonObject {
            put("workspace_id", workspace.id)
            put("command", "dd if=/dev/zero of=emu-sub/large9m.bin bs=1048576 count=9 && printf 'TAIL_OK' >> emu-sub/large9m.bin")
        }.toString())
        assertTrue("Creating >8 MiB file must succeed: $createLargeRes", createLargeRes is ToolResult.Value)
        val largeStatRes = recordAndApprove("stat-large", "file_stat", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub/large9m.bin")
        }.toString())
        assertTrue("Streaming file_stat on >8 MiB file must succeed: $largeStatRes", largeStatRes is ToolResult.Value)
        val largeReadRes = recordAndApprove("read-large-tail", "file_read_text", buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", "emu-sub/large9m.bin")
            put("offset_bytes", 9 * 1024 * 1024)
            put("max_bytes", 64)
        }.toString())
        assertTrue("Windowed file_read_text at 9 MiB offset must succeed: $largeReadRes",
            largeReadRes is ToolResult.Value && largeReadRes.json.contains("TAIL_OK"))

        // Verify symlink & hardlink confinement refusal over real UID 0 POSIX FDs (W4)
        recordAndApprove("shell-mk-links", "shell_exec", buildJsonObject {
            put("workspace_id", workspace.id)
            put("command", "ln -s /system/build.prop emu-sub/sym.txt && ln payload emu-sub/hard.txt")
        }.toString())
        val symReadRes = recordAndApprove("read-sym", "file_read_text", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub/sym.txt")
        }.toString())
        assertFalse("Symlink escape must be rejected", symReadRes is ToolResult.Value)
        val hardReadRes = recordAndApprove("read-hard", "file_read_text", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub/hard.txt")
        }.toString())
        assertFalse("Multi-hardlink file must be rejected", hardReadRes is ToolResult.Value)

        // Cleanup links & large file
        recordAndApprove("shell-rm-links", "shell_exec", buildJsonObject {
            put("workspace_id", workspace.id)
            put("command", "rm -f emu-sub/sym.txt emu-sub/hard.txt emu-sub/large9m.bin")
        }.toString())

        // Verify invalid cwd_relative_path fails closed pre-spawn without degrading epoch (W3)
        val badCwdRes = recordAndApprove("shell-bad-cwd", "shell_exec", buildJsonObject {
            put("workspace_id", workspace.id)
            put("command", "id -u")
            put("cwd_relative_path", "nonexistent-cwd-dir")
        }.toString())
        assertFalse("Invalid cwd must not succeed", badCwdRes is ToolResult.Value && badCwdRes.json.contains("\"exit_code\":0"))
        assertTrue("Pre-spawn invalid cwd rejection must not degrade epoch", coordinator.isAuthorityReadyForDispatch(authority))

        // 8. Verify W6 read_batch independent child invocations & two-stage job_start / job_output disclosure
        val batchArgs = buildJsonObject {
            put("calls", buildJsonArray {
                add(buildJsonObject {
                    put("call_id", "batch-c1-$suffix")
                    put("tool", "file_read_text")
                    put("arguments", buildJsonObject {
                        put("workspace_id", workspace.id); put("relative_path", "payload"); put("max_bytes", 128)
                    })
                })
                add(buildJsonObject {
                    put("call_id", "batch-c2-$suffix")
                    put("tool", "file_read_text")
                    put("arguments", buildJsonObject {
                        put("workspace_id", workspace.id); put("relative_path", "emu-sub/note.txt"); put("max_bytes", 128)
                    })
                })
            })
        }.toString()
        val batchRes = recordAndApprove("batch-read-1", "read_batch", batchArgs)
        assertTrue("Root read_batch must succeed: $batchRes", batchRes is ToolResult.Value)
        assertTrue((batchRes as ToolResult.Value).json.contains("FD_PROBE_V1"))
        assertTrue(batchRes.json.contains("alpha-beta-gamma"))

        val jobStartArgs = buildJsonObject {
            put("tool", "file_read_text")
            put("arguments", buildJsonObject {
                put("workspace_id", workspace.id); put("relative_path", "emu-sub/note.txt"); put("max_bytes", 128)
            })
        }.toString()
        val jobStartRes = recordAndApprove("job-start-1", "job_start", jobStartArgs)
        assertTrue("Root job_start must succeed: $jobStartRes", jobStartRes is ToolResult.Value)
        val jobId = Json.parseToJsonElement((jobStartRes as ToolResult.Value).json).jsonObject["job_id"]!!.jsonPrimitive.content
        val jobOutRes = productivity.executor.invoke(
            ToolCall("job-out-1-$suffix", "job_output", buildJsonObject {
                put("job_id", jobId); put("wait_ms", 5000)
            }.toString()),
        )
        assertTrue("Root job_output must disclose completed child result: $jobOutRes",
            jobOutRes is ToolResult.Value && jobOutRes.json.contains("alpha-beta-gamma"))

        // Clean up emu-sub
        recordAndApprove("del-note", "file_delete", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub/note.txt")
        }.toString())
        recordAndApprove("del-sub", "file_delete", buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", "emu-sub")
        }.toString())

        // 9. Verify W2 private storage & export isolation
        val leakedResultRows = db.query(
            "SELECT invocation_id FROM tool_invocations WHERE run_id=? AND result_json IS NOT NULL",
            listOf(runId),
        )
        assertTrue("No Root invocation in a Root run may persist result_json in tool_invocations", leakedResultRows.isEmpty())

        val exportFile = File(context.cacheDir, "root-export-check-$suffix.zip")
        try {
            FileOutputStream(exportFile).use { out -> app.container.transfer.exportArchive(agentId = agentId, output = out) }
            ZipInputStream(FileInputStream(exportFile)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val text = zis.readBytes().toString(Charsets.UTF_8)
                    assertFalse("Portable export must never contain foreign fixture path", text.contains(foreignDir))
                    assertFalse("Portable export must never contain Root locator ciphertext", text.contains("root_workspace_bindings"))
                }
            }
        } finally {
            exportFile.delete()
        }

        // 10. Verify W1 quiescence & session detach
        productivity.executor.close()
        coordinator.detachSession(authority, "acceptance_complete")
        assertFalse("Authority must be detached after detachSession", coordinator.isAuthorityReadyForDispatch(authority))
    }

    private fun waitForShizukuBinderAndPermission(timeoutMs: Long = 10_000L): Boolean {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var launchedActivity = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (!alive && !launchedActivity) {
                launchedActivity = true
                runCatching {
                    context.startActivity(
                        android.content.Intent(context, runtime.mobileagent.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            val granted = alive && runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (alive && granted) return true
            Thread.sleep(100L)
        }
        return false
    }

    private fun waitForBridgeReady(bridge: ShizukuAuthorityBridge, timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (bridge.refresh().ready) return true
            Thread.sleep(100L)
        }
        return false
    }
}
