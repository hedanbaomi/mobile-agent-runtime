// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.*

class ChatInputBudgetDeviceTest {
    @Test
    fun oversizedInstructionsFailAsContextOverflowBeforeResolvingCredentials() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString()
        val providerId = "budget-provider-$suffix"
        val modelId = "budget-model-$suffix"
        val agentId = "budget-agent-$suffix"
        container.profiles.createProvider(ProviderProfile(
            id = providerId, name = "Budget fixture", apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1", secretRef = "absent-budget-credential-$suffix", revision = 1,
        ))
        container.profiles.createModel(ModelProfile(
            id = modelId, providerId = providerId, role = ModelRole.CHAT, modelId = "budget-fixture",
            capabilities = setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1,
        ))
        container.agents.saveWithPrompt(AgentProfile(
            id = agentId, name = "Budget fixture", promptRevisionId = "pending", chatProfileId = modelId, revision = 0,
        ), "Long local instructions. ".repeat(1400))
        lateinit var viewModel: ChatViewModel
        instrumentation.runOnMainSync {
            viewModel = ChatViewModel(app, SavedStateHandle())
            viewModel.selectAgent(agentId)
            viewModel.input("Please answer.")
            viewModel.send()
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (viewModel.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("Local validation must terminalize without a network request", viewModel.state.value.streaming)
        val sessionId = checkNotNull(viewModel.state.value.selectedSessionId)
        val run = container.runs.list(sessionId).single()
        assertEquals(RunStatus.FAILED, run.state)
        val errors = container.conversations.messages(sessionId).flatMap { it.parts }.filterIsInstance<ErrorPart>()
        assertEquals(MessageErrorCode.CONTEXT_OVERFLOW, errors.single().code)
        assertEquals("CONTEXT_OVERFLOW", run.errorCode)
        assertTrue(container.runs.invocations(run.runId).isEmpty())
    }
}
