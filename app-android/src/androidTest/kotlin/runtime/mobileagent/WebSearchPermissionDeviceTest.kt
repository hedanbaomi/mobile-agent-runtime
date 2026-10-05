// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSearchPermission
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.WebSearchProvider
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/** Tests host authorization against real SQLite/Keystore without dispatching a search request. */
class WebSearchPermissionDeviceTest {
    @Test fun staleProviderCallbacksCannotSaveDisableOrDeleteAnotherServicesKey() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val oldProvider = container.settings.webSearchProvider() ?: WebSearchProvider.BRAVE
        val oldRef = container.settings.webSearchSecretRef(WebSearchProvider.TAVILY)
        val oldEnabled = container.settings.webSearchEnabled(WebSearchProvider.TAVILY)
        val ref = "search:tavily:device:${UUID.randomUUID()}"
        val owner = ViewModelStore()
        try {
            container.secrets.put(ref, "fixture-not-a-real-api-key".toCharArray())
            container.settings.setWebSearch(ref, true, WebSearchProvider.TAVILY)
            instrumentation.runOnMainSync {
                val vm = SettingsViewModel(app)
                owner.put("settings", vm)
                vm.selectWebSearchProvider("tavily")
                // Label changes synchronously, before the IO-backed facts refresh can complete.
                assertEquals("tavily", vm.uiState(0).webSearchProviderId)
                vm.saveWebSearch("brave", "must-not-be-stored-under-tavily")
                vm.setWebSearchEnabled("brave", false)
                vm.clearWebSearch("brave")
                assertEquals(ref, container.settings.webSearchSecretRef(WebSearchProvider.TAVILY))
                assertTrue(container.settings.webSearchEnabled(WebSearchProvider.TAVILY))
                assertTrue(vm.error.value != null)
            }
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            container.settings.setWebSearch(oldRef, oldEnabled, WebSearchProvider.TAVILY)
            container.settings.selectWebSearchProvider(oldProvider)
            container.secrets.inventory().retireIfUnreferenced(ref)
        }
    }

    @Test fun frozenOptInAndLiveRevocationGateTheActualHostExecutor() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val oldProvider = container.settings.webSearchProvider() ?: WebSearchProvider.BRAVE
        val oldRef = container.settings.webSearchSecretRef(WebSearchProvider.BRAVE)
        val oldEnabled = container.settings.webSearchEnabled(WebSearchProvider.BRAVE)
        val id = UUID.randomUUID().toString()
        val ref = "search:brave:device:$id"
        try {
            container.secrets.put(ref, "fixture-not-a-real-api-key".toCharArray())
            container.settings.selectWebSearchProvider(WebSearchProvider.BRAVE)
            container.settings.setWebSearch(ref, true)
            container.profiles.createProvider(ProviderProfile(
                id = "provider.$id", name = "Search fixture", apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1", secretRef = "fixture:$id", revision = 1,
            ))
            container.profiles.createModel(ModelProfile(
                id = "model.$id", providerId = "provider.$id", role = ModelRole.CHAT,
                modelId = "fixture", capabilities = setOf("tools"), contextLimit = 4096,
                outputLimit = 512, revision = 1,
            ))
            var agent = container.agents.saveWithPrompt(AgentProfile(
                id = "agent.$id", name = "Search fixture", promptRevisionId = "pending",
                chatProfileId = "model.$id", revision = 0,
            ), "Fixture")
            val offSnapshot = container.agents.createSnapshot(agent.id)
            assertTrue(webSearchTools(container, offSnapshot).specs.isEmpty())
            agent = container.agents.saveWithPrompt(agent.copy(
                permissionSettingsJson = AgentSearchPermission.update(agent.permissionSettingsJson, true),
            ), "Fixture")
            // Enabling never broadens an old frozen snapshot.
            assertTrue(webSearchTools(container, offSnapshot).specs.isEmpty())
            val onSnapshot = container.agents.createSnapshot(agent.id)
            val executor = webSearchTools(container, onSnapshot)
            assertEquals(listOf("web_search"), executor.specs.map { it.name })
            agent = container.agents.saveWithPrompt(agent.copy(
                permissionSettingsJson = AgentSearchPermission.update(agent.permissionSettingsJson, false),
            ), "Fixture")
            assertTrue(executor.invoke(ToolCall("revoked", "web_search", """{"query":"fixture"}""")) is ToolResult.Denied)
            agent = container.agents.saveWithPrompt(agent.copy(
                permissionSettingsJson = AgentSearchPermission.update(agent.permissionSettingsJson, true),
            ), "Fixture")
            // Disable/re-enable is a new grant; the old executor cannot resume.
            assertTrue(executor.invoke(ToolCall("old-grant", "web_search", """{"query":"fixture"}""")) is ToolResult.Denied)
            val fresh = webSearchTools(container, container.agents.createSnapshot(agent.id))
            container.settings.selectWebSearchProvider(WebSearchProvider.TAVILY)
            container.settings.selectWebSearchProvider(WebSearchProvider.BRAVE)
            assertTrue(fresh.invoke(ToolCall("old-config", "web_search", """{"query":"fixture"}""")) is ToolResult.Denied)
        } finally {
            container.settings.setWebSearch(oldRef, oldEnabled, WebSearchProvider.BRAVE)
            container.settings.selectWebSearchProvider(oldProvider)
            container.secrets.inventory().retireIfUnreferenced(ref)
        }
    }
}
