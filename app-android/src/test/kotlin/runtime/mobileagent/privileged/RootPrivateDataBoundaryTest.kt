// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
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
import runtime.mobileagent.AgentToolConfirmation
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuditRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RootRunManifest
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.TransferRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticBuildInfo
import runtime.mobileagent.diagnostics.DiagnosticLevel
import runtime.mobileagent.diagnostics.DiagnosticOperation
import runtime.mobileagent.diagnostics.DiagnosticOperationState
import runtime.mobileagent.diagnostics.DiagnosticPreferenceStore
import runtime.mobileagent.diagnostics.DiagnosticToolCapability
import runtime.mobileagent.diagnostics.RollingDiagnosticLogStore
import runtime.mobileagent.diagnostics.WorkspaceOperationStateRecord
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootRecoveryLocatorPayload
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.RootWorkspaceRootKind
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.SqliteRuntimeAuditSink
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.integration.toDiagnosticOperationState
import runtime.mobileagent.integration.toDiagnosticToolCapability
import runtime.mobileagent.serialization.TransferOptions
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ShellAuditEvent
import runtime.mobileagent.skills.tooling.ShellAuditPhase
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.WorkspaceAuditBackendType
import runtime.mobileagent.tooling.WorkspaceAuditEvent
import runtime.mobileagent.tooling.WorkspaceAuditOperation
import runtime.mobileagent.tooling.WorkspaceAuditPhase

/**
 * W2 verification suite (`ROOT05/06/11/12/16/18`):
 * - Private authorization data boundary across Host SQLite, chat history, diagnostics, and portable transfer
 * - `toString()` redaction on Root workspace/shell/service DTOs
 * - AES-256-GCM AAD binding of `origin`, `locator_version`, `key_version`, and `aad_version`
 * - Explicit user approval enforcement (`skipToolConfirmations` blocked on Root authorities, workspaces, runs, and tools)
 */
class RootPrivateDataBoundaryTest {
    private val secretRootPath = "/data/user/0/com.victim.secret/files"
    private val secretRelPath = "canary.key"
    private val secretFullPath = "$secretRootPath/$secretRelPath"
    private val secretCommand = "cat $secretFullPath"
    private val canaryValue = "ROOT_SECRET_CANARY_98765"

    @Test
    fun dtoToStringRedactsAllRootPathsHandlesTokensPayloadsAndOutputs() {
        val origin = RootWorkspaceOrigin(
            workspaceId = "ws-secret-id",
            authority = Authority.ROOT_DIRECT,
            appInstanceId = "app-instance-secret",
            packageName = "runtime.mobileagent",
            appUid = 10148,
            androidUserId = 0,
            signingSha256 = "a".repeat(64),
            generationId = "generation-secret-0001",
        )
        val identity = PrivilegedSessionIdentity(
            Authority.ROOT_DIRECT, PrivilegeTier.ROOT, 0, 10148, 0,
            "app-instance-secret", "runtime.mobileagent", "a".repeat(64), 1,
            "service-secret-0001", "session-secret-0001", 1,
        )
        val selection = RootWorkspaceSelection("Victim", secretRootPath, writable = true)
        val rootIdentity = RootServiceRootIdentity(1L, 2L, 3L, 10200, RootWorkspaceRootKind.DIRECTORY)
        val proposal = RootWorkspaceProposal(
            provisionalHandle = "prov-handle-secret",
            selection = selection,
            root = rootIdentity,
            identity = identity,
        )
        val admitted = RootAdmittedWorkspace(
            workspaceId = "ws-secret-id",
            workspaceGenerationId = "generation-secret-0001",
            workspaceSessionHandle = "sess-handle-secret",
            root = rootIdentity,
            keyVersion = 1,
            origin = origin,
        )
        val locatorPayload = RootRecoveryLocatorPayload(
            originSha256 = "b".repeat(64),
            workspaceId = "ws-secret-id",
            workspaceGenerationId = "generation-secret-0001",
            authority = Authority.ROOT_DIRECT,
            rootKind = RootWorkspaceRootKind.DIRECTORY,
            absoluteRootPath = secretRootPath,
            rootDeviceId = 1L,
            rootInode = 2L,
            rootMountId = 3L,
        )
        val bindReq = RootServiceBindWorkspaceRequest(
            targetServiceInstanceId = "service-secret-0001",
            targetSessionId = "session-secret-0001",
            targetIdentityEpoch = 1L,
            workspaceId = "ws-secret-id",
            workspaceGenerationId = "generation-secret-0001",
            workspaceSessionHandle = "sess-handle-secret",
            workspaceRootPath = secretRootPath,
            origin = origin,
            recoveryLocator = secretRootPath.encodeToByteArray(),
        )
        val bindResp = RootServiceBindWorkspaceResponse(
            accepted = true,
            phase = "STAGE",
            provisionalHandle = "prov-handle-secret",
            workspaceSessionHandle = "sess-handle-secret",
            stagedToken = "staged-token-secret",
            locatorBytes = secretRootPath.encodeToByteArray(),
        )
        val selReq = RootServiceWorkspaceSelectionRequest(
            phase = "PROPOSE",
            targetServiceInstanceId = "service-secret",
            targetSessionId = "session-secret",
            targetIdentityEpoch = 1L,
            workspaceRootPath = secretRootPath,
            provisionalHandle = "prov-handle-secret",
            workspaceSessionHandle = "sess-handle-secret",
        )
        val shellPayload = RootShellOutcomePayload(
            statusName = ShellExecutionStatus.SUCCEEDED.name,
            exitCode = 0,
            stdout = canaryValue,
            stderr = secretCommand,
            terminalOutcomeName = RootServiceTerminalOutcome.SUCCEEDED.name,
        )
        val shellOutcome = RootShellRunOutcome(
            status = ShellExecutionStatus.SUCCEEDED,
            exitCode = 0,
            stdout = canaryValue,
            stderr = secretCommand,
            terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
        )
        val execResponse = RootServiceExecuteResponse(
            submitStage = "ACCEPTED",
            terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
            fileResultJson = """{"text":"$canaryValue","path":"$secretRootPath"}""",
            shellOutcome = shellPayload,
            errorReason = secretRootPath,
        )
        val handshakeReq = RootServiceHandshakeRequest(
            authority = Authority.ROOT_DIRECT,
            identityEpoch = 1L,
            appUid = 10148,
            androidUserId = 0,
            appInstanceId = "app-instance-secret",
            packageName = "runtime.mobileagent",
            signingSha256 = "a".repeat(64),
            apkSha256 = "b".repeat(64),
            ceDataDir = secretRootPath,
            deDataDir = secretRootPath,
        )
        val hostBootstrap = RootServiceHostBootstrap(
            expectedAppUid = 10148,
            expectedAndroidUserId = 0,
            expectedPackageName = "runtime.mobileagent",
            expectedSigningSha256 = "a".repeat(64),
            expectedApkSha256 = "b".repeat(64),
            expectedCeDataDir = secretRootPath,
            expectedDeDataDir = secretRootPath,
        )

        val strings = listOf(
            origin.toString(),
            selection.toString(),
            proposal.toString(),
            admitted.toString(),
            locatorPayload.toString(),
            bindReq.toString(),
            bindResp.toString(),
            selReq.toString(),
            shellPayload.toString(),
            shellOutcome.toString(),
            execResponse.toString(),
            handshakeReq.toString(),
            hostBootstrap.toString(),
        )
        strings.forEach { rendered ->
            assertFalse(rendered.contains(secretRootPath), rendered)
            assertFalse(rendered.contains(secretCommand), rendered)
            assertFalse(rendered.contains(canaryValue), rendered)
            assertFalse(rendered.contains("prov-handle-secret"), rendered)
            assertFalse(rendered.contains("sess-handle-secret"), rendered)
            assertFalse(rendered.contains("staged-token-secret"), rendered)
            assertFalse(rendered.contains("app-instance-secret"), rendered)
        }
    }

    @Test
    fun locatorAadBindsIdentityAndVersionColumnsAndRejectsCrossContextOrTamperedOpen() {
        val cipher = TestRootLocatorCipher()
        val baseOrigin = RootWorkspaceOrigin(
            workspaceId = "ws-aad-1",
            authority = Authority.ROOT_DIRECT,
            appInstanceId = "app-instance-000001",
            packageName = "runtime.mobileagent",
            appUid = 10148,
            androidUserId = 0,
            signingSha256 = "a".repeat(64),
            generationId = "generation-aad-000001",
        )
        val plaintext = secretRootPath.encodeToByteArray()
        val sealed = requireNotNull(cipher.seal(baseOrigin, plaintext, locatorVersion = 1, keyVersion = 1, aadVersion = 2))
        assertFalse(sealed.decodeToString().contains(secretRootPath))
        assertNotNull(cipher.open(baseOrigin, sealed, locatorVersion = 1, keyVersion = 1, aadVersion = 2))

        val mismatchedOrigins = listOf(
            baseOrigin.copy(authority = Authority.SHIZUKU_ROOT),
            baseOrigin.copy(androidUserId = 10, appUid = 1_010_148),
            baseOrigin.copy(appUid = 10999),
            baseOrigin.copy(appInstanceId = "app-instance-000002"),
            baseOrigin.copy(packageName = "runtime.mobileagent.clone"),
            baseOrigin.copy(signingSha256 = "b".repeat(64)),
            baseOrigin.copy(workspaceId = "ws-aad-2"),
            baseOrigin.copy(generationId = "generation-aad-000002"),
        )
        mismatchedOrigins.forEach { wrongOrigin ->
            assertNull(cipher.open(wrongOrigin, sealed, locatorVersion = 1, keyVersion = 1, aadVersion = 2))
        }
        assertNull(cipher.open(baseOrigin, sealed, locatorVersion = 2, keyVersion = 1, aadVersion = 2))
        assertNull(cipher.open(baseOrigin, sealed, locatorVersion = 1, keyVersion = 2, aadVersion = 2))
        assertNull(cipher.open(baseOrigin, sealed, locatorVersion = 1, keyVersion = 1, aadVersion = 1))
        assertNull(TestRootLocatorCipher().open(baseOrigin, sealed, locatorVersion = 1, keyVersion = 1, aadVersion = 2))

        // Verify SQLite trigger blocks direct UPDATE on root_workspace_locators, and
        // RootCoordinatorDispatchBridge rejects mismatched version columns vs sealed AAD
        withSeam(secretRootPath) { s ->
            val backend = RootRuntimeToolBackend(s.db, s.coordinator, s.context("run-w2-tamper"), s.execution)
            val executor = RootToolExecutor(RootToolArguments.specs(), backend)
            val call = ToolCall("inv-w2-tamper", "file_write_text", s.writeArgs(secretRelPath, canaryValue))
            assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
            s.recordCall("run-w2-tamper", call.callId, "model-tamper", call.name, call.argumentsJson)
            assertThrows(Exception::class.java) {
                s.db.execute(
                    "UPDATE root_workspace_locators SET key_version = 2 WHERE workspace_id = ?",
                    listOf(s.workspace.id),
                )
            }
            val origRow = s.db.query(
                "SELECT * FROM root_workspace_locators WHERE workspace_id = ?",
                listOf(s.workspace.id),
            ).single()
            s.db.execute("DELETE FROM root_workspace_locators WHERE workspace_id = ?", listOf(s.workspace.id))
            s.db.execute(
                "INSERT INTO root_workspace_locators(workspace_id,generation_id,encrypted_envelope,locator_version,key_version,aad_version) VALUES(?,?,?,?,?,?)",
                listOf(
                    origRow.string("workspace_id"),
                    origRow.string("generation_id"),
                    requireNotNull(origRow.columns["encrypted_envelope"] as? ByteArray),
                    origRow.long("locator_version"),
                    2L,
                    origRow.long("aad_version"),
                ),
            )
            val result = runBlocking { executor.approve(call.callId) }
            assertFalse(result is ToolResult.Value, "Tampered key_version must fail AAD authentication and block dispatch")
        }
    }

    @Test
    fun agentToolConfirmationNeverAutoSkipsExplicitApprovalForRootAuthorityWorkspaceRunOrTool() {
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "NONE",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "shell_exec",
                selectedAuthority = Authority.ROOT_DIRECT.name,
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "shell_exec",
                selectedAuthority = Authority.SHIZUKU_ROOT.name,
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )
        assertFalse(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = Authority.ROOT_DIRECT.name,
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = false,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "NONE",
                workspaceAuthority = DiagnosticAuthority.ROOT_DIRECT,
                rootRun = false,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "NONE",
                workspaceAuthority = DiagnosticAuthority.SHIZUKU_ROOT,
                rootRun = false,
            ),
        )
        assertTrue(
            AgentToolConfirmation.requiresExplicitApproval(
                toolName = "file_write_text",
                selectedAuthority = "NONE",
                workspaceAuthority = DiagnosticAuthority.NONE,
                rootRun = true,
            ),
        )
        listOf("file_get_metadata", "file_set_metadata", "file_apply_patch", "job_start").forEach { rootOrJobTool ->
            assertTrue(
                AgentToolConfirmation.requiresExplicitApproval(
                    toolName = rootOrJobTool,
                    selectedAuthority = "NONE",
                    workspaceAuthority = DiagnosticAuthority.NONE,
                    rootRun = false,
                ),
            )
        }
    }

    @Test
    fun endToEndPrivateDataBoundaryIsolatesSqliteDiagnosticsAndPortableExportFromRootSecrets() = withSeam(secretRootPath) { s ->
        val diagDir = Files.createTempDirectory("root-w2-diag").toFile()
        try {
            val store = RollingDiagnosticLogStore(
                rootDirectory = diagDir,
                preferences = object : DiagnosticPreferenceStore {
                    override fun isEnabled(): Boolean = true
                    override fun setEnabled(enabled: Boolean) = Unit
                    override fun logLevel(): DiagnosticLevel = DiagnosticLevel.DEBUG
                },
                buildInfo = DiagnosticBuildInfo("test-rev", false, 1, "2026-10-31T00:00:00Z", "test-fp"),
            )
            val logger = AndroidDiagnosticLogger(store)
            val auditSink = SqliteRuntimeAuditSink(
                repository = AuditRepository(s.db),
                diagnostics = logger,
            )
            val backend = RootRuntimeToolBackend(s.db, s.coordinator, s.context("run-w2-secret"), s.execution)
            val executor = RootToolExecutor(RootToolArguments.specs(), backend)

            // 1. Write & read synthetic private file
            val writeCall = ToolCall("inv-w2-write", "file_write_text", s.writeArgs(secretRelPath, canaryValue))
            assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(writeCall) })
            s.recordCall("run-w2-secret", writeCall.callId, "model-w2-1", writeCall.name, writeCall.argumentsJson)
            val writeResult = runBlocking { executor.approve(writeCall.callId) }
            assertTrue(writeResult is ToolResult.Value)
            runBlocking {
                auditSink.record(
                    WorkspaceAuditEvent(
                        requestId = writeCall.callId,
                        agentId = "agent",
                        workspaceId = s.workspace.id,
                        relativePathSha256 = RootPrivilegedServiceEngine.sha256Hex(secretRelPath),
                        capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
                        operation = WorkspaceAuditOperation.WRITE,
                        phase = WorkspaceAuditPhase.COMPLETED,
                        resultCode = "SUCCEEDED",
                        backendType = WorkspaceAuditBackendType.UNKNOWN,
                    ),
                )
            }

            val readCall = ToolCall("inv-w2-read", "file_read_text", s.readArgs(secretRelPath))
            assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(readCall) })
            s.recordCall("run-w2-secret", readCall.callId, "model-w2-2", readCall.name, readCall.argumentsJson)
            val readResult = runBlocking { executor.approve(readCall.callId) }
            assertTrue(readResult is ToolResult.Value && (readResult as ToolResult.Value).json.contains(canaryValue))

            // 2. Execute synthetic private shell command
            val shellCmdInWorkspace = "cat ./$secretRelPath --token=ROOT_SHELL_CMD_CANARY_98765"
            val shellOutputSecret = "ROOT_PRIVILEGED_STDOUT_SECRET_98765"
            s.shellOutput = shellOutputSecret
            val shellCall = ToolCall("inv-w2-shell", "shell_exec", s.shellArgs(shellCmdInWorkspace))
            assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(shellCall) })
            s.recordCall("run-w2-secret", shellCall.callId, "model-w2-3", shellCall.name, shellCall.argumentsJson)
            val shellResult = runBlocking { executor.approve(shellCall.callId) }
            assertTrue(shellResult is ToolResult.Value && (shellResult as ToolResult.Value).json.contains(shellOutputSecret))
            val runs = RunRepository(s.db)
            runs.invocations("run-w2-secret").forEach { inv ->
                runs.observeRuntimeInvocation(inv.copy(resultJson = """{"leaked":"$shellOutputSecret","canary":"$canaryValue"}"""))
            }
            runBlocking {
                auditSink.recordCompleted(
                    ShellAuditEvent(
                        requestId = shellCall.callId,
                        callId = "model-w2-3",
                        agentId = "agent",
                        skillId = null,
                        authority = Authority.ROOT_DIRECT,
                        dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                        phase = ShellAuditPhase.COMPLETED,
                        status = ShellExecutionStatus.SUCCEEDED,
                        commandSha256 = RootPrivilegedServiceEngine.sha256Hex(shellCmdInWorkspace),
                        cwdSha256 = RootPrivilegedServiceEngine.sha256Hex(secretRootPath),
                        exitCode = 0,
                        outputBytes = shellOutputSecret.length.toLong(),
                        stdoutBytes = shellOutputSecret.length.toLong(),
                        stderrBytes = 0L,
                        durationMs = 5L,
                    ),
                )
            }

            // 3. Record closed Root error codes into RollingDiagnosticLogStore
            val rootDenialCodes = listOf(
                "root_authorization_required", "root_shell_consent_required", "root_request_denied",
                "privilege_mismatch", "identity_changed", "selinux_denied", "hardlink_forbidden",
                "mount_boundary_crossed", "control_plane_protected", "degraded_session_requires_ack",
            )
            rootDenialCodes.forEach { code ->
                val event = WorkspaceAuditEvent(
                    requestId = "req-$code",
                    agentId = "agent",
                    workspaceId = s.workspace.id,
                    relativePathSha256 = RootPrivilegedServiceEngine.sha256Hex(secretRelPath),
                    capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
                    operation = WorkspaceAuditOperation.WRITE,
                    phase = WorkspaceAuditPhase.COMPLETED,
                    resultCode = code.uppercase(),
                    backendType = WorkspaceAuditBackendType.UNKNOWN,
                )
                assertEquals(DiagnosticOperationState.DENIED, event.toDiagnosticOperationState(), code)
                assertTrue(
                    logger.recordWorkspaceOperationState(
                        WorkspaceOperationStateRecord(
                            workspaceId = s.workspace.id,
                            operation = DiagnosticOperation.WRITE,
                            state = DiagnosticOperationState.DENIED,
                            count = 1,
                            requestRef = "req-$code",
                            errorCode = code,
                        ),
                    ),
                    "Closed Root error code $code must be accepted by diagnostics schema",
                )
            }
            val cancelledEvent = WorkspaceAuditEvent(
                requestId = "req-cancel",
                agentId = "agent",
                workspaceId = s.workspace.id,
                relativePathSha256 = RootPrivilegedServiceEngine.sha256Hex(secretRelPath),
                capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
                operation = WorkspaceAuditOperation.WRITE,
                phase = WorkspaceAuditPhase.COMPLETED,
                resultCode = "CANCELLED_BEFORE_ATTEMPT",
                backendType = WorkspaceAuditBackendType.UNKNOWN,
            )
            assertEquals(DiagnosticOperationState.CANCELLED, cancelledEvent.toDiagnosticOperationState())
            assertEquals(DiagnosticToolCapability.WORKSPACE_READ, CapabilityId(CapabilityId.FILE_METADATA_READ).toDiagnosticToolCapability())
            assertEquals(DiagnosticToolCapability.WORKSPACE_WRITE, CapabilityId(CapabilityId.FILE_METADATA_WRITE).toDiagnosticToolCapability())
            assertEquals(DiagnosticToolCapability.SEARCH, CapabilityId("search").toDiagnosticToolCapability())

            // 4. Verify Host SQLite tables never store plaintext root path or privileged output,
            // and authorization/workspace/grant/audit tables never store relative path, command, or canary
            val authAndAuditTables = listOf(
                "workspaces", "root_workspace_origins", "root_workspace_locators",
                "capability_grants", "root_grant_bindings", "root_snapshot_seals",
                "approval_records", "root_approval_bindings", "root_dispatch_receipts",
                "audit_events", "tool_audit_details", "root_audit_bindings",
            )
            authAndAuditTables.forEach { table ->
                val rows = s.db.query("SELECT * FROM $table").toString()
                assertFalse(rows.contains(secretRootPath), "Table $table leaked secretRootPath")
                assertFalse(rows.contains(secretRelPath), "Table $table leaked secretRelPath")
                assertFalse(rows.contains(shellCmdInWorkspace), "Table $table leaked shellCmdInWorkspace")
                assertFalse(rows.contains(canaryValue), "Table $table leaked canaryValue")
                assertFalse(rows.contains(shellOutputSecret), "Table $table leaked shellOutputSecret")
            }
            val invocationRows = s.db.query("SELECT * FROM tool_invocations").toString()
            assertFalse(invocationRows.contains(secretRootPath), "Table tool_invocations leaked secretRootPath")
            assertFalse(invocationRows.contains(shellOutputSecret), "Table tool_invocations leaked shellOutputSecret")
            runs.invocations("run-w2-secret").forEach { inv ->
                assertNull(inv.resultJson, "Root tool_invocations row ${inv.invocationId} must not persist resultJson")
            }

            // 5. Verify Diagnostics export never contains plaintext path, command, or canary
            val currentNdjson = store.readFile(RollingDiagnosticLogStore.CURRENT_FILE_NAME).toString(Charsets.UTF_8)
            assertFalse(currentNdjson.contains(secretRootPath))
            assertFalse(currentNdjson.contains(secretRelPath))
            assertFalse(currentNdjson.contains(shellCmdInWorkspace))
            assertFalse(currentNdjson.contains(canaryValue))
            assertFalse(currentNdjson.contains(shellOutputSecret))
            assertTrue(currentNdjson.contains("selinux_denied"))
            assertTrue(currentNdjson.contains("hardlink_forbidden"))

            // 6. Verify Portable Transfer archive never contains plaintext path, command, canary, or Root stamps
            val runRecord = requireNotNull(RunRepository(s.db).get("run-w2-secret"))
            assertTrue(RootRunManifest.hasRootStamp(runRecord.manifestJson))
            val archiveOut = ByteArrayOutputStream()
            TransferRepository(s.db).exportArchive("agent", TransferOptions(includeConversations = true), archiveOut)
            ZipInputStream(archiveOut.toByteArray().inputStream()).use { zip ->
                while (zip.nextEntry != null) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    assertFalse(text.contains(secretRootPath))
                    assertFalse(text.contains(secretRelPath))
                    assertFalse(text.contains(shellCmdInWorkspace))
                    assertFalse(text.contains(canaryValue))
                    assertFalse(text.contains(shellOutputSecret))
                    assertFalse(text.contains("rootExecutionStamp"))
                    zip.closeEntry()
                }
            }
        } finally {
            diagDir.deleteRecursively()
        }
    }

    private fun withSeam(rootPath: String, block: (Seam) -> Unit) {
        val dir = Files.createTempDirectory("root-w2-privacy")
        InMemoryRootSqlConnection().use { db ->
            val seam = Seam(db, dir.toFile(), rootPath, canaryValue)
            try {
                block(seam)
            } finally {
                seam.coordinator.close()
            }
        }
        dir.toFile().deleteRecursively()
    }

    private class Seam(val db: SqlConnection, dir: File, rootPath: String, canary: String) {
        val signing = "a".repeat(64)
        val boundary = RootControlPlaneBoundary(
            "/data/user/0/runtime.mobileagent",
            node(9001),
            "/data/user_de/0/runtime.mobileagent",
            node(9002),
        )
        var shellOutput: String = canary
        val fd = InMemoryServiceFdOps(rootPath)
        val shellRunner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome =
                RootShellRunOutcome(
                    status = ShellExecutionStatus.SUCCEEDED,
                    exitCode = 0,
                    stdout = shellOutput,
                    stderr = "",
                    terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
                )

            override fun cancel(requestId: String): Boolean = true
        }
        val engine = RootPrivilegedServiceEngine(
            managerAuthorizationOverride = { RootManagerAuthorization.GRANTED },
            fdOps = fd,
            shellRunner = shellRunner,
            serviceUidProvider = { 0 },
            controlPlaneOverride = boundary,
        ).pinTestHost(boundary, signing)
        val transport = EngineRootChannelTransport(engine, 10148)
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        val coordinator: RootRuntimeCoordinator
        val execution: RootExecutionStamp
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
                sessionConnector = object : RootSessionConnector {
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
                        return RootAcquiredSession(id, RootCallerObservation(10148, 0, "runtime.mobileagent", 0, signing), transport, boundary)
                    }
                },
                shellRunner = shellRunner,
                controlPlaneBoundaryOverride = boundary,
                locatorCipher = TestRootLocatorCipher(),
            )
            coordinator.setUserIntent(Authority.ROOT_DIRECT, true, manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
            coordinator.setRootShellConsent(Authority.ROOT_DIRECT, enabled = true, userConfirmed = true, authorityManager = manager)
            execution = requireNotNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, requireShellConsent = true))
            val selected = RootWorkspaceUiController(db, coordinator).attach(
                Authority.ROOT_DIRECT, rootPath, true, true, true, WorkspacePickerTarget("agent"),
            )
            assertTrue(selected is WorkspaceAccessResult.Success)
            workspace = requireNotNull(WorkspaceRepository(db).get((selected as WorkspaceAccessResult.Success).workspace.workspaceId))
            grants = CapabilityGrantRepository(db).forAgent("agent", includeRevoked = false)
            snapshot = coordinator.sealNewSnapshot(Authority.ROOT_DIRECT, "snapshot-w2", "agent", grants.mapTo(mutableSetOf()) { it.grantId }) {
                AgentRepository(db).createSnapshot("agent", "snapshot-w2")
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
            if (runs.get(run) == null) {
                RunCoordinator(runs, coordinator::admitNewRun).prepare(RunRecord(run, snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
            }
            db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, run))
            runs.addInvocation(ToolInvocation(invocation, run, model, tool, raw, state = "WAITING_APPROVAL", permissionDecision = "PENDING", createdAt = Utc.nowIso()))
        }

        fun writeArgs(path: String, text: String) = buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", path)
            put("text", text)
        }.toString()

        fun readArgs(path: String) = buildJsonObject {
            put("workspace_id", workspace.id)
            put("relative_path", path)
        }.toString()

        fun shellArgs(command: String) = buildJsonObject {
            put("workspace_id", workspace.id)
            put("command", command)
        }.toString()

        companion object {
            fun node(inode: Long) = RootFdNodeIdentity(1, inode, 77, true, false, false, 2, 448, 10148, 10148, "label")
        }
    }
}
