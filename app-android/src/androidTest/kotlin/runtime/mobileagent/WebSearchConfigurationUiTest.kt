// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.agents.AgentEditorUi
import runtime.mobileagent.feature.agents.AgentsActions
import runtime.mobileagent.feature.agents.AgentsScreen
import runtime.mobileagent.feature.agents.AgentsUiState
import runtime.mobileagent.feature.settings.SettingsActions
import runtime.mobileagent.feature.settings.SettingsScreen
import runtime.mobileagent.feature.settings.SettingsUiState

class WebSearchConfigurationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun changingProviderClearsUnsavedKeyAndSavesOnlyTheSelectedService() {
        val state = mutableStateOf(SettingsUiState())
        val saved = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(state.value, SettingsActions(
                    onWebSearchProvider = { state.value = state.value.copy(webSearchProviderId = it) },
                    onSaveWebSearch = { provider, key -> saved += provider to key },
                ))
            }
        }
        compose.onNodeWithTag("settings.web_search.key").performScrollTo().performTextInput("discarded-draft")
        compose.onNodeWithText("Brave").performScrollTo().performClick()
        compose.onNodeWithTag("settings.web_search.provider.tavily").performClick()
        compose.onNodeWithTag("settings.web_search.key").assertTextEquals("Tavily API Key", "")
        compose.onNodeWithTag("settings.web_search.key").performTextInput("fixture-tavily")
        compose.onNodeWithText("保存并启用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("tavily" to "fixture-tavily"), saved) }
        compose.runOnUiThread { state.value = state.value.copy(language = "en-US") }
        compose.onNodeWithText("Tavily").performScrollTo().performClick()
        compose.onNodeWithTag("settings.web_search.provider.exa").performClick()
        compose.onNodeWithTag("settings.web_search.key").performTextInput("fixture-exa")
        compose.onNodeWithText("Save and enable").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("exa" to "fixture-exa", saved.last()) }
    }

    @Test fun agentSearchOptInIsSeparateFromSkippingOtherToolConfirmations() {
        val state = mutableStateOf(AgentsUiState(editor = AgentEditorUi(name = "Fixture"), editorOpen = true))
        compose.setContent {
            MaterialTheme {
                AgentsScreen(state.value, AgentsActions(onEditorChange = {
                    state.value = state.value.copy(editor = it)
                }), renderEditorAsPage = true)
            }
        }
        compose.onNodeWithTag("agents.editor.web_search").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertFalse(state.value.editor!!.skipToolConfirmations) }
        compose.runOnUiThread { state.value = state.value.copy(language = "en-US") }
        compose.onNodeWithText("Allow web search").assertExists()
        compose.onNodeWithTag("agents.editor.web_search").performScrollTo().performClick().assertIsOff()
    }
}
