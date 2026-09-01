// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import java.util.UUID

/**
 * Optional end-to-end proof against a real Shizuku installation.
 *
 * Ordinary device suites skip this test when Shizuku is absent.  Release
 * verification can pass `-e requireShizuku true`; in that mode missing
 * authority, permission, or UserService readiness is a hard failure.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuLiveDeviceTest {
    @Test
    fun approvalGatedTypedWorkspaceRoundTripUsesRealUserService() = runBlocking {
        val requireShizuku = InstrumentationRegistry.getArguments()
            .getString("requireShizuku")
            .equals("true", ignoreCase = true)
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        val snapshot = AgentSnapshot(
            id = "shizuku-live-snapshot",
            schemaVersion = 11,
            agentId = "shizuku-live-agent",
            promptRevisionId = "prompt",
            chatModelId = "model",
            providerRevision = 1,
            knowledgeBaseIds = emptyList(),
            skillIds = emptyList(),
            createdAt = "2026-08-30T00:00:00Z",
        )
        val directory = "instrumentation-${UUID.randomUUID()}"
        val file = "$directory/proof.txt"
        val movedFile = "$directory/moved-proof.txt"
        try {
            val serverReady = bridge.refresh().permissionGranted && bridge.bindUserService()
            if (requireShizuku) {
                assertTrue("Shizuku server and permission must be available", serverReady)
            } else {
                assumeTrue("Shizuku server or permission is unavailable", serverReady)
            }
            val ready = waitUntilReady(bridge)
            if (requireShizuku) {
                assertTrue("Shizuku UserService did not become ready", ready)
            } else {
                assumeTrue("Shizuku UserService did not become ready", ready)
            }

            val executor = bridge.createToolExecutor(snapshot, { true }, { true })
            assertEquals(5, executor.specs.size)

            val workspaceBackend = ShizukuBackendFactory.createWorkspaceBackend(bridge)
            val rootListing = workspaceBackend.list(
                WorkspaceListRequest(
                    workspaceId = ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
                    relativePath = null,
                ),
            )
            assertTrue("An omitted list path must address the workspace root", rootListing is WorkspaceResult.Success)
            approve(executor, "root-list", "shizuku_workspace_list", "{}")
                .requireSuccess("list")

            approve(executor, "mkdir", "shizuku_workspace_mkdir", "{\"path\":\"$directory\"}")
                .requireSuccess("mkdir")
            approve(
                executor,
                "write",
                "shizuku_workspace_write",
                "{\"path\":\"$file\",\"text\":\"Shizuku typed round trip\",\"replaceExisting\":false}",
            ).requireSuccess("write")
            val read = approve(
                executor,
                "read",
                "shizuku_workspace_read",
                "{\"path\":\"$file\",\"maxBytes\":1024}",
            ).requireSuccess("read")
            assertEquals("Shizuku typed round trip", read.getString("text"))
            val stat = workspaceBackend.stat(
                WorkspaceStatRequest(ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID, file),
            )
            assertTrue(stat is WorkspaceResult.Success)
            assertEquals(WorkspaceEntryType.FILE, (stat as WorkspaceResult.Success).value.type)
            val moved = workspaceBackend.move(
                WorkspaceMoveRequest(
                    ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
                    file,
                    movedFile,
                ),
            )
            assertTrue(moved is WorkspaceResult.Success)
            assertEquals(movedFile, (moved as WorkspaceResult.Success).value.relativePath)
            val listed = approve(
                executor,
                "list",
                "shizuku_workspace_list",
                "{\"path\":\"$directory\"}",
            ).requireSuccess("list")
            assertEquals("moved-proof.txt", listed.getJSONArray("entries").getJSONObject(0).getString("path").substringAfterLast('/'))
            approve(executor, "delete-file", "shizuku_workspace_delete", "{\"path\":\"$movedFile\"}")
                .requireSuccess("delete")
            approve(executor, "delete-dir", "shizuku_workspace_delete", "{\"path\":\"$directory\"}")
                .requireSuccess("delete")
        } finally {
            // The public test path is unique; best-effort cleanup does not retry an
            // unknown write outcome and cannot recursively delete anything.
            runCatching { bridge.dispatchDelete(file) }
            runCatching { bridge.dispatchDelete(movedFile) }
            runCatching { bridge.dispatchDelete(directory) }
            bridge.close()
        }
        Unit
    }

    @Test
    fun oneShotShellUsesRealShellUserServiceWhenExplicitlyAvailable() = runBlocking {
        val requireShizuku = InstrumentationRegistry.getArguments()
            .getString("requireShizuku")
            .equals("true", ignoreCase = true)
        val bridge = ShizukuAuthorityBridge(ApplicationProvider.getApplicationContext<Context>())
        try {
            val serverReady = bridge.refresh().permissionGranted && bridge.bindUserService()
            if (requireShizuku) {
                assertTrue("Only a shell-backed Shizuku server may run this test", serverReady)
            } else {
                assumeTrue("Shizuku shell authority is unavailable", serverReady)
            }
            val ready = waitUntilReady(bridge)
            if (requireShizuku) {
                assertTrue("Shizuku shell UserService did not become ready", ready)
            } else {
                assumeTrue("Shizuku shell UserService is unavailable", ready)
            }

            val result = bridge.executeShell(
                ShizukuShellRequest(
                    callId = "shizuku-live-shell-${UUID.randomUUID()}",
                    command = "printf '%s' 'stdout'; printf '%s' 'stderr' >&2; exit 3",
                    timeoutMs = 5_000L,
                    maxStdoutBytes = 4 * 1024,
                    maxStderrBytes = 4 * 1024,
                ),
            )
            assertEquals(ShizukuShellResult.State.COMPLETED, result.state)
            assertEquals(3, result.exitCode)
            assertEquals("stdout", String(result.stdout, Charsets.UTF_8))
            assertEquals("stderr", String(result.stderr, Charsets.UTF_8))
            assertFalse(result.unknownOutcome)
        } finally {
            bridge.close()
        }
    }

    private suspend fun approve(
        executor: ToolExecutor,
        callSuffix: String,
        name: String,
        argumentsJson: String,
    ): ToolResult {
        val callId = "shizuku-live-$callSuffix-${UUID.randomUUID()}"
        assertEquals(ToolResult.NeedsApproval, executor.invoke(ToolCall(callId, name, argumentsJson)))
        return executor.approve(callId)
    }

    private fun ToolResult.requireSuccess(operation: String): JSONObject {
        assertTrue("Expected successful $operation, got $this", this is ToolResult.Value)
        return JSONObject((this as ToolResult.Value).json).also { payload ->
            assertTrue("Expected ok=true for $operation: $payload", payload.getBoolean("ok"))
            assertEquals(operation, payload.getString("operation"))
        }
    }

    private suspend fun waitUntilReady(bridge: ShizukuAuthorityBridge): Boolean {
        repeat(100) {
            if (bridge.refresh().ready) return true
            kotlinx.coroutines.delay(50)
        }
        return bridge.refresh().ready
    }
}
