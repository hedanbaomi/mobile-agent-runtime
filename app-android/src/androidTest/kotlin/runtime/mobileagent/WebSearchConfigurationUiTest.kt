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
            // The production shell resolves string resources against the SettingsUiState
            // language; a direct SettingsScreen call must keep the same contract.
            runtime.mobileagent.ui.AppLocalizedResources(state.value.language) {
                MaterialTheme {
                    SettingsScreen(state.value, SettingsActions(
                        onWebSearchProvider = { state.value = state.value.copy(webSearchProviderId = it) },
                        onSaveWebSearch = { provider, key -> saved += provider to key },
                    ))
                }
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
        compose.onNodeWithText("Exa").performScrollTo().performClick()
        compose.onNodeWithTag("settings.web_search.provider.deepseek").performClick()
        compose.onNodeWithTag("settings.web_search.key").assertTextEquals("DeepSeek API Key", "")
        compose.onNodeWithText("DeepSeek supports Search and Fetch with one API key. The model chooses whether to search or read a public page. Queries and page URLs are sent to DeepSeek; both operations may incur charges.").assertExists()
        compose.onNodeWithTag("settings.web_search.key").performTextInput("fixture-deepseek")
        compose.onNodeWithText("Save and enable").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("deepseek" to "fixture-deepseek", saved.last()) }
        compose.runOnUiThread { state.value = state.value.copy(language = "zh-CN") }
        compose.onNodeWithText("DeepSeek 的 Search 和 Fetch 共用一个 API Key，模型可按需搜索或读取公开网页。查询词和网页地址会发送给 DeepSeek，两种操作都可能收费。").assertExists()
    }

    @Test fun agentSearchOptInIsSeparateFromSkippingOtherToolConfirmations() {
        val state = mutableStateOf(AgentsUiState(editor = AgentEditorUi(name = "Fixture"), editorOpen = true))
        compose.setContent {
            runtime.mobileagent.ui.AppLocalizedResources(state.value.language) {
                MaterialTheme {
                    AgentsScreen(state.value, AgentsActions(onEditorChange = {
                        state.value = state.value.copy(editor = it)
                    }), renderEditorAsPage = true)
                }
            }
        }
        compose.onNodeWithTag("agents.editor.web_search").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertFalse(state.value.editor!!.skipToolConfirmations) }
        compose.runOnUiThread { state.value = state.value.copy(language = "en-US") }
        compose.onNodeWithText("Allow web search").assertExists()
        compose.onNodeWithTag("agents.editor.web_search").performScrollTo().performClick().assertIsOff()
    }
}
