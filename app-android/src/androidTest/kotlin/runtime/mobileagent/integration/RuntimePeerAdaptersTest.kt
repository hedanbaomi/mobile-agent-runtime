// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.diagnostics.DiagnosticOperation
import runtime.mobileagent.diagnostics.DiagnosticOperationState
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.tooling.WorkspaceAuditEvent
import runtime.mobileagent.tooling.WorkspaceAuditOperation
import runtime.mobileagent.tooling.WorkspaceAuditOutcome
import runtime.mobileagent.tooling.WorkspaceAuditPhase
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbConnectionState
import runtime.mobileagent.wired.WiredAdbErrorCode
import runtime.mobileagent.wired.WiredAdbFileOperation
import runtime.mobileagent.wired.WiredAdbFileRequest
import runtime.mobileagent.wired.WiredAdbFileResult
import runtime.mobileagent.wired.WiredAdbLifecycleState
import runtime.mobileagent.wired.WiredAdbPairingPrompt
import runtime.mobileagent.wired.WiredAdbPlatformGrant
import runtime.mobileagent.wired.WiredAdbRequestId
import runtime.mobileagent.wired.WiredAdbResult
import runtime.mobileagent.wired.WiredAdbShellRequest
import runtime.mobileagent.wired.WiredAdbShellResult
import runtime.mobileagent.wired.WiredAdbStatus
import runtime.mobileagent.wired.WiredAdbTrustRecord
import runtime.mobileagent.wired.WiredAdbUserIntent
import runtime.mobileagent.wired.WiredAdbAvailability
import runtime.mobileagent.wired.WiredAdbWorkspacePort
import runtime.mobileagent.wired.WiredAdbShellPort

/** Adapter-only tests: all low-level calls are served by an in-process fake. */
class RuntimePeerAdaptersTest {
    @Test
    fun shellAdapterUsesWiredPortAndMapsSuccessfulResult() = runBlocking {
        val fake = FakeWiredAuthority()
        val result = WiredShellExecutor(fake).execute(shellRequest(Authority.WIRED_ADB))

        assertEquals(ShellExecutionStatus.SUCCEEDED, result.status)
        assertEquals(0, result.exitCode)
        assertEquals("wired output", result.stdout)
        assertEquals(1, fake.shellCalls.get())
        assertEquals("printf wired", fake.lastShellCommand)
    }

    @Test
    fun shellAdapterDoesNotDispatchAnotherAuthority() = runBlocking {
        val fake = FakeWiredAuthority()
        val result = WiredShellExecutor(fake).execute(shellRequest(Authority.SHIZUKU))

        assertEquals(ShellExecutionStatus.FAILED, result.status)
        assertEquals(ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED, result.error?.code)
        assertEquals(0, fake.shellCalls.get())
    }

    @Test
    fun shellBridgeDisconnectIsUnknownAndNeverReplayed() = runBlocking {
        val fake = FakeWiredAuthority(shellResult = WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_DISCONNECTED))
        val result = WiredShellExecutor(fake).execute(shellRequest(Authority.WIRED_ADB))

        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, result.status)
        assertFalse(result.automaticReplayAllowed)
        assertEquals(1, fake.shellCalls.get())
    }

    @Test
    fun workspaceAdapterMapsTypedListAndReadWithoutRootLeak() = runBlocking {
        val fake = FakeWiredAuthority()
        val backend = WiredWorkspaceBackend(fake)

        assertEquals("", backend.descriptor.rootReference)
        val listed = backend.list(
            WorkspaceListRequest(
                workspaceId = backend.descriptor.id,
                maxEntries = 10,
            ),
        )
        val read = backend.readText(WorkspaceReadTextRequest(backend.descriptor.id, "notes.txt", 1024))

        assertTrue(listed is WorkspaceResult.Success)
        assertEquals(".", (listed as WorkspaceResult.Success).value.relativePath)
        assertEquals(1, (listed as WorkspaceResult.Success).value.entries.size)
        assertEquals(WorkspaceEntryType.FILE, listed.value.entries.single().type)
        assertTrue(read is WorkspaceResult.Success)
        assertEquals("hello from wired", (read as WorkspaceResult.Success).value.text)
        assertEquals(listOf(WiredAdbFileOperation.LIST, WiredAdbFileOperation.READ_TEXT), fake.fileOperations)
    }

    @Test
    fun workspaceAdapterSendsWriteBytesOnlyToWiredPort() = runBlocking {
        val fake = FakeWiredAuthority()
        val backend = WiredWorkspaceBackend(fake)
        val result = backend.writeText(
            WorkspaceWriteTextRequest(backend.descriptor.id, "notes.txt", "new text", replace = true),
        )

        assertTrue(result is WorkspaceResult.Success)
        assertEquals(WiredAdbFileOperation.WRITE_TEXT, fake.fileOperations.single())
        assertEquals("new text", fake.lastFileContent)
    }

    @Test
    fun workspaceAuditMapsEachTerminalResultCodeWithoutPromotingFailureToSuccess() {
        val cases = listOf(
            "SUCCEEDED" to DiagnosticOperationState.SUCCEEDED,
            "FAILED" to DiagnosticOperationState.FAILED,
            "APPROVAL_DENIED" to DiagnosticOperationState.DENIED,
            "CANCELLED" to DiagnosticOperationState.CANCELLED,
            "UNKNOWN_OUTCOME" to DiagnosticOperationState.UNKNOWN,
        )

        cases.forEach { (resultCode, expected) ->
            assertEquals(expected, audit(resultCode).toDiagnosticOperationState())
        }
        assertEquals(DiagnosticOperationState.STARTED, audit(null, WorkspaceAuditPhase.STARTED).toDiagnosticOperationState())
        assertEquals(DiagnosticOperation.READ, audit("SUCCEEDED").toDiagnosticOperation())
        assertEquals(DiagnosticOperation.DELETE, audit("SUCCEEDED", operation = WorkspaceAuditOperation.DELETE).toDiagnosticOperation())
    }

    private fun audit(
        resultCode: String?,
        phase: WorkspaceAuditPhase = WorkspaceAuditPhase.TERMINAL,
        operation: WorkspaceAuditOperation = WorkspaceAuditOperation.READ,
    ) = WorkspaceAuditEvent(
        phase = phase,
        requestId = "request-1",
        agentId = "agent-1",
        capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
        workspaceId = "workspace-1",
        relativePathSha256 = "0".repeat(64),
        resultCode = resultCode,
        operation = operation,
        outcome = resultCode?.let { runCatching { WorkspaceAuditOutcome.valueOf(it) }.getOrNull() },
    )

    private fun shellRequest(authority: Authority) = ShellExecRequest.fromRuntime(
        callId = "call-1",
        command = "printf wired",
        agentId = "agent-1",
        snapshotId = "snapshot-1",
        selectedAuthority = authority,
        dangerousMode = DangerousMode.DISABLED,
        policyVersion = 1L,
        configSnapshotHash = "config-hash",
        sessionIdentity = "session-1",
    )

    private class FakeWiredAuthority(
        private val shellResult: WiredAdbResult<WiredAdbShellResult> = WiredAdbResult.Success(
            WiredAdbShellResult(
                exitCode = 0,
                stdout = "wired output".toByteArray(),
                stderr = ByteArray(0),
                timedOut = false,
                cancelled = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = 3,
            ),
        ),
    ) : WiredAdbAuthorityPort {
        private val mutableStatus = MutableStateFlow(
            WiredAdbStatus(
                state = WiredAdbLifecycleState.READY,
                userIntent = WiredAdbUserIntent.ENABLED,
                platformGrant = WiredAdbPlatformGrant.GRANTED,
                availability = WiredAdbAvailability.READY,
                connection = WiredAdbConnectionState.CONNECTED,
                trusted = true,
            ),
        )
        override val status: StateFlow<WiredAdbStatus> = mutableStatus
        val shellCalls = AtomicInteger(0)
        val fileOperations = mutableListOf<WiredAdbFileOperation>()
        var lastShellCommand: String? = null
            private set
        var lastFileContent: String? = null
            private set

        override val shell = object : WiredAdbShellPort {
            override suspend fun executeShell(request: WiredAdbShellRequest): WiredAdbResult<WiredAdbShellResult> {
                shellCalls.incrementAndGet()
                lastShellCommand = request.command
                return shellResult
            }

            override suspend fun cancel(requestId: WiredAdbRequestId): WiredAdbResult<Unit> =
                WiredAdbResult.Success(Unit)
        }

        override val workspace = object : WiredAdbWorkspacePort {
            override suspend fun executeFile(request: WiredAdbFileRequest): WiredAdbResult<WiredAdbFileResult> {
                fileOperations += request.operation
                lastFileContent = request.contentCopy()?.toString(Charsets.UTF_8)
                return when (request.operation) {
                    WiredAdbFileOperation.LIST -> WiredAdbResult.Success(
                        WiredAdbFileResult(
                            operation = request.operation,
                            relativePath = request.relativePath,
                            entries = listOf(
                                runtime.mobileagent.wired.WiredAdbFileEntry(
                                    relativePath = "notes.txt",
                                    type = runtime.mobileagent.wired.WiredAdbEntryType.FILE,
                                    bytes = 17,
                                ),
                            ),
                        ),
                    )
                    WiredAdbFileOperation.READ_TEXT -> WiredAdbResult.Success(
                        WiredAdbFileResult(
                            request.operation,
                            request.relativePath,
                            text = "hello from wired",
                            bytes = "hello from wired".toByteArray(Charsets.UTF_8).size.toLong(),
                        ),
                    )
                    WiredAdbFileOperation.WRITE_TEXT -> WiredAdbResult.Success(
                        WiredAdbFileResult(request.operation, request.relativePath, bytes = request.contentUtf8?.size?.toLong()),
                    )
                    else -> WiredAdbResult.Success(WiredAdbFileResult(request.operation, request.relativePath))
                }
            }
        }

        override fun setUserIntent(enabled: Boolean) = Unit
        override fun requestPairingFromForeground(replaceExistingTrust: Boolean): WiredAdbResult<WiredAdbPairingPrompt> =
            WiredAdbResult.Failure(WiredAdbErrorCode.PAIRING_REQUIRED)
        override suspend fun pair(): WiredAdbResult<WiredAdbTrustRecord> =
            WiredAdbResult.Failure(WiredAdbErrorCode.PAIRING_REQUIRED)
        override suspend fun connect(): WiredAdbResult<Unit> = WiredAdbResult.Success(Unit)
        override fun disconnect() = Unit
        override suspend fun forget() = Unit
        override fun newFileRequest(
            operation: WiredAdbFileOperation,
            relativePath: String?,
            destinationRelativePath: String?,
            contentUtf8: ByteArray?,
            replaceExisting: Boolean,
            maxBytes: Int,
        ): WiredAdbFileRequest = WiredAdbFileRequest(
            requestId = WiredAdbRequestId("file-${fileOperations.size}"),
            operation = operation,
            relativePath = relativePath,
            destinationRelativePath = destinationRelativePath,
            contentUtf8 = contentUtf8,
            replaceExisting = replaceExisting,
            maxBytes = maxBytes,
        )

        override fun newShellRequest(
            command: String,
            cwd: String?,
            timeoutMs: Long,
            maxOutputBytes: Long,
        ): WiredAdbShellRequest = WiredAdbShellRequest(
            requestId = WiredAdbRequestId("shell-${shellCalls.get()}"),
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes,
        )

        override fun close() = Unit
    }
}
