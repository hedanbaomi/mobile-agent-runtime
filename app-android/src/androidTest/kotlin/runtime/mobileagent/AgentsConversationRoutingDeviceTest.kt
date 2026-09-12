// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile

class AgentsConversationRoutingDeviceTest {
    @Test
    fun startConversationFromAgentDetailSelectsTheNewSessionInChat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString()
        val providerId = "route-provider-$suffix"
        val modelId = "route-model-$suffix"
        val firstAgentId = "route-agent-a-$suffix"
        val secondAgentId = "route-agent-b-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId, name = "Route fixture", apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1", secretRef = "absent-route-$suffix", revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId, providerId = providerId, role = ModelRole.CHAT, modelId = "route-fixture",
                capabilities = setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(id = firstAgentId, name = "Route A", promptRevisionId = "pending", chatProfileId = modelId, revision = 0),
            "Agent A",
        )
        container.agents.saveWithPrompt(
            AgentProfile(id = secondAgentId, name = "Route B", promptRevisionId = "pending", chatProfileId = modelId, revision = 0),
            "Agent B",
        )
        lateinit var chat: ChatViewModel
        lateinit var agents: AgentsViewModel
        instrumentation.runOnMainSync {
            chat = ChatViewModel(app, SavedStateHandle())
            agents = AgentsViewModel(app, SavedStateHandle())
            chat.selectAgent(firstAgentId)
        }
        val firstSession = checkNotNull(chat.newSession())
        assertEquals(firstAgentId, chat.state.value.selectedAgentId)
        assertEquals(firstSession, chat.state.value.selectedSessionId)
        instrumentation.runOnMainSync {
            agents.select(secondAgentId)
        }
        val created = checkNotNull(agents.createConversation())
        assertNotEquals(firstSession, created)
        instrumentation.runOnMainSync {
            chat.selectSession(created)
        }
        assertEquals(created, chat.state.value.selectedSessionId)
        assertEquals(secondAgentId, chat.state.value.selectedAgentId)
        assertEquals(created, container.uiPreferences.getString("selected-conversation", null))
        assertNotNull(container.conversations.get(created))
    }
}
