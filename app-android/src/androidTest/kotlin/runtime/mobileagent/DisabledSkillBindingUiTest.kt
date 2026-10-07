// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.agents.AgentEditorUi
import runtime.mobileagent.feature.agents.AgentResourceBindingUi
import runtime.mobileagent.feature.agents.AgentsActions
import runtime.mobileagent.feature.agents.AgentsScreen
import runtime.mobileagent.feature.agents.AgentsUiState

class DisabledSkillBindingUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun pausedAssociationIsUncheckedAndCanBeUnlinkedWithoutEnabling() {
        var editor by mutableStateOf(AgentEditorUi(
            id = "agent.paused", name = "Paused skill agent",
            resourceBindings = listOf(AgentResourceBindingUi(
                id = "skill.paused", name = "Paused skill", type = "skill", enabled = true,
                available = false, selectable = true,
                permissionSummary = "已关联，禁用期间暂停；启用后新会话恢复",
            )),
        ))
        composeRule.setContent {
            MaterialTheme {
                AgentsScreen(
                    state = AgentsUiState(editor = editor, editorOpen = true),
                    actions = AgentsActions(onToggleResource = { id, enabled ->
                        editor = editor.copy(resourceBindings = editor.resourceBindings.map {
                            if (it.id == id) it.withAssociation(enabled) else it
                        })
                    }),
                    renderEditorAsPage = true,
                )
            }
        }
        composeRule.onNodeWithTag("agents.resource.checkbox.skill.paused")
            .performScrollTo().assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithTag("agents.resource.unbind.skill.paused").performClick()
        composeRule.runOnIdle { assertFalse(editor.resourceBindings.single().enabled) }
        composeRule.onNodeWithTag("agents.resource.unbind.skill.paused").assertDoesNotExist()
        composeRule.onNodeWithTag("agents.resource.checkbox.skill.paused").assertIsOff().assertIsNotEnabled()
    }
}
