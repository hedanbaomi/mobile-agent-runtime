// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.ui.AppRoutes
import runtime.mobileagent.ui.MainApp

/** Real app navigation, shell-owned ChatViewModel and SQLite; no Send or provider request. */
class ArchiveNavigationDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun settingsArchiveViewRemainsReadOnlyAndRestoreReturnsConversationToActiveDrawer() {
        val app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString()
        val originalLocale = container.settings.locale()
        val previousConversation = container.uiPreferences.getString("selected-conversation", null)
        val previousAgent = container.uiPreferences.getString("selected-agent", null)
        val provider = ProviderProfile("archive-nav-provider-$suffix", "Archive navigation fixture",
            ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "absent-archive-$suffix", revision = 1)
        container.profiles.createProvider(provider)
        val model = ModelProfile("archive-nav-model-$suffix", provider.id, ModelRole.CHAT, "fixture",
            setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1)
        container.profiles.createModel(model)
        val agent = container.agents.saveWithPrompt(AgentProfile("archive-nav-agent-$suffix", "Archive navigation fixture",
            "pending", model.id, revision = 0), "Synthetic navigation fixture.")
        val snapshot = container.agents.createSnapshot(agent.id)
        val active = container.conversations.create(snapshot.id, "Active fixture $suffix")
        val archived = container.conversations.create(snapshot.id, "Archived fixture $suffix")
        val message = container.conversations.append(archived.id, MessageRole.USER, "Retain this archived transcript.")
        assertTrue(container.conversations.setArchived(archived.id, true))
        try {
            container.settings.setLocale(LocalePreference.ZH_CN)
            container.uiPreferences.edit().putString("selected-conversation", active.id).putString("selected-agent", agent.id).apply()
            compose.runOnUiThread {
                ViewModelProvider(compose.activity)[ShellViewModel::class.java].setRoute(AppRoutes.SETTINGS)
            }
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.width(360.dp).fillMaxHeight()) { MainApp() }
                }
            }
            compose.onNodeWithTag("settings.archived_conversations").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("settings.archive.list")
                .performScrollToNode(hasTestTag("settings.archive.view.${archived.id}"))
            compose.onNodeWithTag("settings.archive.view.${archived.id}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation.composer.input").assertIsNotEnabled()
            compose.onNodeWithTag("conversation.composer.send").assertIsNotEnabled()
            assertTrue(container.conversations.get(archived.id)!!.archived)
            assertEquals(listOf(message), container.conversations.messages(archived.id))
            compose.onNodeWithTag("conversation.archive.restore").assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation.composer.input").assertIsEnabled()
            assertFalse(container.conversations.get(archived.id)!!.archived)
            assertTrue(container.conversations.listActive().any { it.id == archived.id })
            assertFalse(container.conversations.listArchived().any { it.id == archived.id })
            assertEquals(listOf(message), container.conversations.messages(archived.id))
            compose.onNodeWithTag("global.shell.navigation.menu").performClick()
            compose.onNodeWithTag("global.drawer")
                .performScrollToNode(hasTestTag("global.drawer.session.${archived.id}"))
            compose.onNodeWithTag("global.drawer.session.${archived.id}").assertIsDisplayed()
        } finally {
            container.settings.setLocale(originalLocale)
            container.uiPreferences.edit().putString("selected-conversation", previousConversation).putString("selected-agent", previousAgent).apply()
        }
    }
}
