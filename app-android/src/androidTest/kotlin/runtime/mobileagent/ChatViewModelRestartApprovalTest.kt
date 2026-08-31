// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability

/** Restart safety proof for Chat's process-local approval lifecycle. */
@RunWith(AndroidJUnit4::class)
class ChatViewModelRestartApprovalTest {
    @Test
    fun orphanedWaitingApprovalIsInvalidatedWithoutReplay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val providerId = "provider.chat-restart.$suffix"
        val modelId = "model.chat-restart.$suffix"
        val agentId = "agent.chat-restart.$suffix"
        val runId = "run.chat-restart.$suffix"

        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Chat restart fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-chat-restart-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-chat-restart",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Chat restart fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Restart approval fixture.",
        )
        val snapshot = container.agents.createSnapshot(agentId)
        val conversation = container.conversations.create(snapshot.id, "Chat restart fixture")
        val now = Utc.nowIso()
        container.runs.save(
            RunRecord(
                runId = runId,
                snapshotId = snapshot.id,
                conversationId = conversation.id,
                state = RunStatus.WAITING_TOOL_APPROVAL,
                budgetJson = "{\"maxRuntimeMs\":60000}",
                startedAt = now,
                createdAt = now,
            ),
        )
        container.runs.recordInvocation(
            ToolInvocation(
                invocationId = "invocation.chat-restart.$suffix",
                runId = runId,
                callId = "model-call.chat-restart.$suffix",
                name = "shell_exec",
                argumentsJson = "{}",
                state = "WAITING_APPROVAL",
                createdAt = now,
            ),
        )

        val viewModel = ChatViewModel(app, SavedStateHandle())
        val closed = container.runs.get(runId)
        val invocation = container.runs.invocations(runId).single()
        assertEquals(RunStatus.CANCELLED, closed?.state)
        assertEquals("APPROVAL_INVALIDATED", closed?.errorCode)
        assertEquals("CANCELLED", invocation.state)
        assertEquals("DENIED", invocation.permissionDecision)
        assertEquals("APPROVAL_INVALIDATED", invocation.errorCode)
        assertTrue(invocation.resultJson?.contains("\"status\":\"CANCELLED\"") == true)
        assertTrue(invocation.resultJson?.contains("\"errorCode\":\"APPROVAL_INVALIDATED\"") == true)
        assertFalse(viewModel.state.value.streaming)
        assertTrue(viewModel.state.value.status.contains("不会自动批准或重放"))
    }

    @Test
    fun reloadDuringStreamingDoesNotInvalidateSameProcessRun() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val providerId = "provider.chat-streaming.$suffix"
        val modelId = "model.chat-streaming.$suffix"
        val agentId = "agent.chat-streaming.$suffix"
        val runId = "run.chat-streaming.$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Chat streaming fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-chat-streaming-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-chat-streaming",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Chat streaming fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Streaming approval fixture.",
        )
        val snapshot = container.agents.createSnapshot(agentId)
        val conversation = container.conversations.create(snapshot.id, "Chat streaming fixture")
        val now = Utc.nowIso()
        val viewModel = ChatViewModel(app, SavedStateHandle())
        container.runs.save(
            RunRecord(
                runId = runId,
                snapshotId = snapshot.id,
                conversationId = conversation.id,
                state = RunStatus.WAITING_TOOL_APPROVAL,
                budgetJson = "{\"maxRuntimeMs\":60000}",
                startedAt = now,
                createdAt = now,
            ),
        )
        viewModel.state.value = viewModel.state.value.copy(streaming = true)
        viewModel.reload()
        assertEquals(RunStatus.WAITING_TOOL_APPROVAL, container.runs.get(runId)?.state)
        viewModel.state.value = viewModel.state.value.copy(streaming = false)
        viewModel.reload()
        assertEquals(RunStatus.CANCELLED, container.runs.get(runId)?.state)
    }

    @Test
    fun requestInspectorAvailabilityDistinguishesDisabledPreparationLossAndReady() {
        assertEquals(
            ChatRequestInspectorAvailability.DISABLED,
            resolveRequestInspectorAvailability(
                inspectorEnabled = false,
                previewAvailable = false,
                persistedPreviewHint = false,
            ),
        )
        assertEquals(
            ChatRequestInspectorAvailability.NOT_PREPARED,
            resolveRequestInspectorAvailability(
                inspectorEnabled = true,
                previewAvailable = false,
                persistedPreviewHint = false,
            ),
        )
        assertEquals(
            ChatRequestInspectorAvailability.CONTEXT_LOST,
            resolveRequestInspectorAvailability(
                inspectorEnabled = true,
                previewAvailable = false,
                persistedPreviewHint = true,
            ),
        )
        assertEquals(
            ChatRequestInspectorAvailability.READY,
            resolveRequestInspectorAvailability(
                inspectorEnabled = true,
                previewAvailable = true,
                persistedPreviewHint = true,
            ),
        )
    }
}
