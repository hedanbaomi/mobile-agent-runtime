// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.AgentRuntime
import runtime.mobileagent.agent.EffectivePrompt
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.integration.WorkspaceAccessGrantTarget
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.integration.WorkspaceAccessStatus

/**
 * Live Android DocumentsProvider proof for the complete SAF -> grant -> snapshot -> model tool
 * exposure chain. The host must first grant a directory containing `seed.txt` through Settings.
 * CI devices without that explicit platform grant skip this live-provider scenario; deterministic
 * backend and authorization rules remain covered by ToolingOrchestrationTest.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeSafToolExposureDeviceTest {
    @Test
    fun internalWorkspaceEnumerationAndRoundTripPassesDurableAuditWithoutPlatformGrant() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val internalWorkspace = container.runtimeIntegration.grants.listWorkspaces()
            .firstOrNull { it.id == INTERNAL_WORKSPACE_ID && it.enabled && it.readable && it.writable }
        assertTrue("The deterministic application workspace must be registered", internalWorkspace != null)

        val suffix = UUID.randomUUID().toString().replace("-", "")
        val providerId = "provider.internal-e2e.$suffix"
        val modelId = "model.internal-e2e.$suffix"
        val agentId = "agent.internal-e2e.$suffix"
        val proofPath = "runtime-e2e-$suffix.txt"
        val proofText = "runtime-internal-e2e-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Internal workspace E2E fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-internal-e2e-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-internal-e2e",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Internal workspace E2E fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Use the application workspace.",
        )
        val policyVersion = container.runtimeIntegration.grants.currentPolicyVersion()
        WORKSPACE_CAPABILITIES.forEach { capability ->
            container.runtimeIntegration.grants.saveGrant(
                CapabilityGrant(
                    grantId = EntityId.random().value,
                    agentId = agentId,
                    capability = capability,
                    workspaceId = INTERNAL_WORKSPACE_ID,
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = policyVersion,
                    createdAt = Utc.nowIso(),
                ),
            )
        }
        val snapshot = container.runtimeIntegration.createSnapshotWithCurrentGrants(agentId)
        val context = container.runtimeIntegration.createToolExecutionContext(
            snapshot = snapshot,
            modelCallId = "model-call-internal-e2e-$suffix",
            sessionIdentity = "session-internal-e2e-$suffix",
            taskIdentity = "task-internal-e2e-$suffix",
            configSnapshotHash = "config-internal-e2e-$suffix",
        )
        val factory = container.runtimeIntegration.createToolExecutorFactory(context)

        val modelRequests = mutableListOf<ModelRequest>()
        var modelRound = 0
        val adapter = object : ModelAdapter {
            override suspend fun probe(profile: ModelProfile): CapabilityReport = error("not used")

            override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                modelRequests += request
                if (modelRound++ == 0) {
                    emit(ModelEvent.ToolCallDelta("internal-model-list-$suffix", "workspace_list", "{}"))
                } else {
                    emit(ModelEvent.TextDelta("workspace tool completed"))
                }
                emit(ModelEvent.Completed)
            }

            override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("not used")
        }
        val agentRun = AgentRun("runtime-agent-e2e-$suffix", snapshot.id, "conversation-e2e-$suffix")
        val modelEvents = kotlinx.coroutines.runBlocking {
            AgentRuntime(adapter, executor = factory.createLegacyExecutor()).run(
                agentRun,
                EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "list the workspace"),
                modelId,
                charArrayOf(),
                toolsEnabled = true,
            ).toList()
        }
        assertEquals(RunState.COMPLETED, agentRun.state)
        assertTrue(modelEvents.any { it is ModelEvent.TextDelta && it.text == "workspace tool completed" })
        assertTrue(modelRequests.first().tools.any { it["name"] == "workspace_list" })
        assertTrue(modelRequests.last().messages.any { it.role == "tool" && it.toolCallId == "internal-model-list-$suffix" })

        val listed = kotlinx.coroutines.runBlocking {
            factory.invoke(ToolCall("internal-list-$suffix", "workspace_list", "{}"))
        }
        assertTrue("workspace_list failed its durable audit boundary: $listed", listed is ToolResult.Value)
        try {
            val written = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "internal-write-$suffix",
                        "file_write_text",
                        """{"workspaceId":"$INTERNAL_WORKSPACE_ID","relativePath":"$proofPath","text":"$proofText","replace":false}""",
                    ),
                )
            }
            assertTrue("file_write_text unexpectedly requested approval or failed: $written", written is ToolResult.Value)
            val read = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "internal-read-$suffix",
                        "file_read_text",
                        """{"workspaceId":"$INTERNAL_WORKSPACE_ID","relativePath":"$proofPath","maxBytes":4096}""",
                    ),
                )
            }
            assertTrue("file_read_text unexpectedly requested approval or failed: $read", read is ToolResult.Value)
            assertTrue((read as ToolResult.Value).json.contains(proofText))
        } finally {
            val deleted = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "internal-delete-$suffix",
                        "file_delete",
                        """{"workspaceId":"$INTERNAL_WORKSPACE_ID","relativePath":"$proofPath"}""",
                    ),
                )
            }
            assertTrue("file_delete unexpectedly requested approval or failed: $deleted", deleted is ToolResult.Value)
        }
    }

    @Test
    fun reattachingPersistedSafTreeReturnsTheCommittedAgentWorkspace() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val persisted = app.contentResolver.persistedUriPermissions.firstOrNull()
        assumeTrue("Grant a SAF directory before this live-provider scenario", persisted != null)
        val agent = app.container.agents.list().firstOrNull()
        assumeTrue("Create an Agent before this live-provider scenario", agent != null)
        val flags =
            (if (requireNotNull(persisted).isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (persisted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

        val result = app.container.runtimeIntegration.workspaceAccessPort.attachSaf(
            uri = persisted.uri,
            resultFlags = flags,
            grant = WorkspaceAccessGrantTarget(agentId = requireNotNull(agent).id),
        )

        assertTrue("A committed SAF attachment was reported as a failure: $result", result is WorkspaceAccessResult.Success)
        val success = result as WorkspaceAccessResult.Success
        assertEquals(WorkspaceAccessStatus.ACTIVE, success.workspace.status)
        assertTrue(success.workspace.readable)
        assertTrue(success.workspace.durablyAuthorized)
        assertTrue(success.grants.isNotEmpty())
        val listed = app.container.runtimeIntegration.workspaceAccessPort.listWorkspaces(agent.id)
            .single { it.workspaceId == success.workspace.workspaceId }
        assertEquals(WorkspaceAccessStatus.ACTIVE, listed.status)
        assertTrue(listed.durablyAuthorized)
    }

    @Test
    fun persistedSafGrantExposesAndExecutesReadWriteToolsForNewSnapshot() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val safWorkspace = container.runtimeIntegration.grants.listWorkspaces()
            .firstOrNull {
                it.backendType == runtime.mobileagent.domain.WorkspaceBackendType.SAF_TREE &&
                    it.enabled && it.readable && it.writable
            }
        assumeTrue("Grant a readable and writable SAF directory through Settings before this live test", safWorkspace != null)
        val safWorkspaceId = requireNotNull(safWorkspace).id

        val suffix = UUID.randomUUID().toString().replace("-", "")
        val providerId = "provider.saf-e2e.$suffix"
        val modelId = "model.saf-e2e.$suffix"
        val agentId = "agent.saf-e2e.$suffix"
        val proofPath = "runtime-saf-e2e-$suffix.txt"
        val proofText = "runtime-saf-e2e-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "SAF E2E fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-saf-e2e-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-saf-e2e",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "SAF E2E fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Read the authorized SAF workspace.",
        )

        val policyVersion = container.runtimeIntegration.grants.currentPolicyVersion()
        WORKSPACE_CAPABILITIES.forEach { capability ->
            container.runtimeIntegration.grants.saveGrant(
                CapabilityGrant(
                    grantId = EntityId.random().value,
                    agentId = agentId,
                    capability = capability,
                    workspaceId = safWorkspaceId,
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = policyVersion,
                    createdAt = Utc.nowIso(),
                ),
            )
        }
        val snapshot = container.runtimeIntegration.createSnapshotWithCurrentGrants(agentId)
        val context = container.runtimeIntegration.createToolExecutionContext(
            snapshot = snapshot,
            modelCallId = "model-call-saf-e2e-$suffix",
            sessionIdentity = "session-saf-e2e-$suffix",
            taskIdentity = "task-saf-e2e-$suffix",
            configSnapshotHash = "config-saf-e2e-$suffix",
        )
        val factory = container.runtimeIntegration.createToolExecutorFactory(context)
        val exposedNames = factory.toolingSpecs.map { it.name }.toSet()
        val exposure = container.runtimeIntegration.toolExposureDiagnostics(context)

        assertTrue("No workspace tools were exposed: ${factory.exposureSummary}", factory.exposureSummary.totalTools > 0)
        assertTrue("workspace_list was not exposed: $exposedNames", "workspace_list" in exposedNames)
        assertTrue("file_list was not exposed: $exposedNames", "file_list" in exposedNames)
        assertTrue("file_read_text was not exposed: $exposedNames", "file_read_text" in exposedNames)
        assertEquals(1, exposure.grantedWorkspaceCount)
        assertEquals(1, exposure.boundWorkspaceCount)
        assertEquals(1, exposure.registeredGrantedWorkspaceCount)
        assertTrue("The persisted SAF grant was not reflected in safe diagnostics", exposure.safGrantActive)
        assertTrue("The SAF backend was not reflected in safe diagnostics", exposure.safBackendRegistered)
        assertTrue(
            "SAF diagnostics must not invent an elevated authority",
            exposure.selectedAuthority in setOf(DiagnosticAuthority.NONE, DiagnosticAuthority.SHIZUKU, DiagnosticAuthority.WIRED_ADB),
        )

        val listed = kotlinx.coroutines.runBlocking {
            factory.invoke(ToolCall("saf-list-$suffix", "workspace_list", "{}"))
        }
        assertTrue("workspace_list failed: $listed", listed is ToolResult.Value)
        val entries = kotlinx.coroutines.runBlocking {
            factory.invoke(
                ToolCall(
                    "saf-file-list-$suffix",
                    "file_list",
                    """{"workspaceId":"$safWorkspaceId","maxEntries":64}""",
                ),
            )
        }
        assertTrue("file_list failed: $entries", entries is ToolResult.Value)
        val read = kotlinx.coroutines.runBlocking {
            factory.invoke(
                ToolCall(
                    "saf-read-$suffix",
                    "file_read_text",
                    """{"workspaceId":"$safWorkspaceId","relativePath":"seed.txt","maxBytes":4096}""",
                ),
            )
        }
        assertTrue("file_read_text failed: $read", read is ToolResult.Value)
        assertTrue((read as ToolResult.Value).json.contains("workspace-e2e-seed"))
        var created = false
        try {
            val written = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "saf-write-$suffix",
                        "file_write_text",
                        """{"workspaceId":"$safWorkspaceId","relativePath":"$proofPath","text":"$proofText","replace":false}""",
                    ),
                )
            }
            assertTrue("file_write_text unexpectedly requested approval or failed: $written", written is ToolResult.Value)
            created = true
            val proofRead = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "saf-proof-read-$suffix",
                        "file_read_text",
                        """{"workspaceId":"$safWorkspaceId","relativePath":"$proofPath","maxBytes":4096}""",
                    ),
                )
            }
            assertTrue("SAF proof read failed: $proofRead", proofRead is ToolResult.Value)
            assertTrue((proofRead as ToolResult.Value).json.contains(proofText))
            val unsafeReplace = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "saf-replace-$suffix",
                        "file_write_text",
                        """{"workspaceId":"$safWorkspaceId","relativePath":"$proofPath","text":"must-not-replace","replace":true}""",
                    ),
                )
            }
            assertTrue(
                "SAF existing-file replacement must remain fail-closed: $unsafeReplace",
                unsafeReplace is ToolResult.Denied,
            )
            val unchanged = kotlinx.coroutines.runBlocking {
                factory.invoke(
                    ToolCall(
                        "saf-unchanged-$suffix",
                        "file_read_text",
                        """{"workspaceId":"$safWorkspaceId","relativePath":"$proofPath","maxBytes":4096}""",
                    ),
                )
            }
            assertTrue("SAF proof reread failed: $unchanged", unchanged is ToolResult.Value)
            assertTrue((unchanged as ToolResult.Value).json.contains(proofText))
            assertTrue(!unchanged.json.contains("must-not-replace"))
        } finally {
            if (created) {
                val deleted = kotlinx.coroutines.runBlocking {
                    factory.invoke(
                        ToolCall(
                            "saf-delete-$suffix",
                            "file_delete",
                            """{"workspaceId":"$safWorkspaceId","relativePath":"$proofPath"}""",
                        ),
                    )
                }
                assertTrue("file_delete unexpectedly requested approval or failed: $deleted", deleted is ToolResult.Value)
            }
        }
        assertEquals(8, container.runtimeIntegration.grants.listSnapshotBindings(snapshot.id).size)
    }

    private companion object {
        const val INTERNAL_WORKSPACE_ID = "internal"
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
