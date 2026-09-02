// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection

/**
 * Live proof for the complete selected Shizuku -> durable grant -> snapshot -> model-facing
 * workspace tool chain.  The test intentionally invokes the same RuntimeIntegration factory used
 * by Chat and asserts that an already-authorized workspace operation does not ask for a second
 * process-local approval.
 *
 * Ordinary CI devices may skip this optional provider scenario.  A release/device verification
 * run can pass `-e requireShizuku true` to make missing Shizuku authority a hard failure.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeShizukuToolExposureDeviceTest {
    @Test
    fun selectedAuthorizedShizukuExposesAndExecutesWorkspaceToolsWithoutPerCallApproval() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requireShizuku = InstrumentationRegistry.getArguments()
            .getString("requireShizuku")
            .equals("true", ignoreCase = true)
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container

        val ready = waitUntilReady(container)
        if (requireShizuku) {
            assertTrue("Shizuku must be selected, granted, and connected", ready)
        } else {
            assumeTrue("Shizuku is not selected, granted, and connected", ready)
        }
        val workspace = container.runtimeIntegration.grants.listWorkspaces()
            .firstOrNull { it.id == SHIZUKU_WORKSPACE_ID && it.enabled && it.readable && it.writable }
        if (requireShizuku) {
            assertTrue("The Shizuku workspace must be registered", workspace != null)
        } else {
            assumeTrue("The Shizuku workspace is unavailable", workspace != null)
        }

        val suffix = UUID.randomUUID().toString().replace("-", "")
        val providerId = "provider.shizuku-e2e.$suffix"
        val modelId = "model.shizuku-e2e.$suffix"
        val agentId = "agent.shizuku-e2e.$suffix"
        val proofPath = "runtime-e2e-$suffix.txt"
        val proofText = "runtime-shizuku-e2e-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Shizuku E2E fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-shizuku-e2e-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-shizuku-e2e",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Shizuku E2E fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Use the selected Shizuku workspace.",
        )

        val policyVersion = container.runtimeIntegration.grants.currentPolicyVersion()
        WORKSPACE_CAPABILITIES.forEach { capability ->
            container.runtimeIntegration.grants.saveGrant(
                CapabilityGrant(
                    grantId = EntityId.random().value,
                    agentId = agentId,
                    capability = capability,
                    workspaceId = SHIZUKU_WORKSPACE_ID,
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = policyVersion,
                    createdAt = Utc.nowIso(),
                ),
            )
        }
        val snapshot = container.runtimeIntegration.createSnapshotWithCurrentGrants(agentId)
        val context = container.runtimeIntegration.createToolExecutionContext(
            snapshot = snapshot,
            modelCallId = "model-call-shizuku-e2e-$suffix",
            sessionIdentity = "session-shizuku-e2e-$suffix",
            taskIdentity = "task-shizuku-e2e-$suffix",
            configSnapshotHash = "config-shizuku-e2e-$suffix",
        )
        val factory = container.runtimeIntegration.createToolExecutorFactory(context)
        val exposedNames = factory.toolingSpecs.map { it.name }.toSet()
        val exposure = container.runtimeIntegration.toolExposureDiagnostics(context)
        val safeExposure = "names=$exposedNames,summary=${factory.exposureSummary},inputs=$exposure," +
            "settings=${container.runtimeIntegration.snapshot()}"
        assertTrue("workspace_list was not exposed: $safeExposure", "workspace_list" in exposedNames)
        assertTrue("file_write_text was not exposed: $safeExposure", "file_write_text" in exposedNames)
        assertTrue("file_read_text was not exposed: $safeExposure", "file_read_text" in exposedNames)
        assertEquals(DiagnosticAuthority.SHIZUKU, exposure.selectedAuthority)
        assertTrue("Selected Shizuku was not ready in safe diagnostics", exposure.selectedAuthorityReady)
        assertEquals(1, exposure.grantedWorkspaceCount)
        assertEquals(1, exposure.boundWorkspaceCount)
        assertEquals(1, exposure.registeredGrantedWorkspaceCount)

        var created = false
        try {
            val listed = factory.invoke(ToolCall("shizuku-list-$suffix", "workspace_list", "{}"))
            assertTrue("workspace_list unexpectedly requested approval or failed: $listed", listed is ToolResult.Value)
            val entries = factory.invoke(
                ToolCall(
                    "shizuku-file-list-$suffix",
                    "file_list",
                    """{"workspaceId":"$SHIZUKU_WORKSPACE_ID","maxEntries":64}""",
                ),
            )
            assertTrue("file_list unexpectedly requested approval or failed: $entries", entries is ToolResult.Value)
            val written = factory.invoke(
                ToolCall(
                    "shizuku-write-$suffix",
                    "file_write_text",
                    """{"workspaceId":"$SHIZUKU_WORKSPACE_ID","relativePath":"$proofPath","text":"$proofText","replace":false}""",
                ),
            )
            assertTrue("file_write_text unexpectedly requested approval or failed: $written", written is ToolResult.Value)
            created = true
            val read = factory.invoke(
                ToolCall(
                    "shizuku-read-$suffix",
                    "file_read_text",
                    """{"workspaceId":"$SHIZUKU_WORKSPACE_ID","relativePath":"$proofPath","maxBytes":4096}""",
                ),
            )
            assertTrue("file_read_text unexpectedly requested approval or failed: $read", read is ToolResult.Value)
            assertTrue((read as ToolResult.Value).json.contains(proofText))
        } finally {
            if (created) {
                val deleted = factory.invoke(
                    ToolCall(
                        "shizuku-delete-$suffix",
                        "file_delete",
                        """{"workspaceId":"$SHIZUKU_WORKSPACE_ID","relativePath":"$proofPath"}""",
                    ),
                )
                assertTrue("file_delete unexpectedly requested approval or failed: $deleted", deleted is ToolResult.Value)
            }
        }
        assertEquals(8, container.runtimeIntegration.grants.listSnapshotBindings(snapshot.id).size)
    }

    private suspend fun waitUntilReady(container: AppContainer): Boolean {
        var consecutiveReadySamples = 0
        repeat(100) {
            val snapshot = container.runtimeIntegration.refresh()
            if (snapshot.selectedAuthority == Authority.SHIZUKU &&
                snapshot.shizuku.availability == Availability.READY &&
                snapshot.shizuku.connection == Connection.CONNECTED
            ) {
                consecutiveReadySamples++
                if (consecutiveReadySamples >= 5) return true
            } else {
                consecutiveReadySamples = 0
            }
            delay(50)
        }
        return false
    }

    private companion object {
        const val SHIZUKU_WORKSPACE_ID = "shizuku"
        val WORKSPACE_CAPABILITIES = listOf(
            CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
            CapabilityId(CapabilityId.FILE_LIST),
            CapabilityId(CapabilityId.FILE_STAT),
            CapabilityId(CapabilityId.FILE_READ_TEXT),
            CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
            CapabilityId(CapabilityId.FILE_MOVE),
            CapabilityId(CapabilityId.FILE_DELETE),
        )
    }
}
