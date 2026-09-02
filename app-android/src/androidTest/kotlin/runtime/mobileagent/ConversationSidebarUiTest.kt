// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatAgentOptionUi
import runtime.mobileagent.feature.chat.ChatScreen
import runtime.mobileagent.feature.chat.ChatSessionUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ChatWorkspaceAccessUi

@RunWith(AndroidJUnit4::class)
class ConversationSidebarUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun compactDrawerGroupsSessionsAndOffersAgentPicker() {
        val selectedAgents = mutableListOf<String>()
        val selectedSessions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    ChatScreen(
                        state = ChatUiState(
                            agents = listOf(
                                ChatAgentOptionUi("agent-a", "研究助手"),
                                ChatAgentOptionUi("agent-b", "写作助手"),
                            ),
                            sessions = listOf(
                                ChatSessionUi("session-a", "今日研究", agentName = "研究助手", agentId = "agent-a"),
                                ChatSessionUi("session-b", "昨天草稿", agentName = "写作助手", agentId = "agent-b"),
                            ),
                            selectedSessionId = "session-a",
                            selectedAgentId = "agent-a",
                        ),
                        actions = ChatActions(
                            onSelectAgent = { selectedAgents += it },
                            onSelectSession = { selectedSessions += it },
                            onNewSessionForAgent = { selectedAgents += "new:$it" },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithTag("chat.sidebar.open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat.sidebar").assertIsDisplayed()
        compose.onNodeWithTag("chat.sidebar.agent.agent-a", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat.sidebar.session.session-a", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat.sidebar.new").performClick()
        compose.onNodeWithTag("chat.sidebar.new.agent.agent-b", useUnmergedTree = true).performClick()
        assertEquals(listOf("new:agent-b"), selectedAgents)
    }

    @Test
    fun wideLayoutKeepsSidebarVisibleAndSessionWorkspaceEntryReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(700.dp).height(640.dp)) {
                        ChatScreen(
                            state = ChatUiState(
                                agents = listOf(ChatAgentOptionUi("agent-a", "研究助手")),
                                sessions = listOf(ChatSessionUi("session-a", "今日研究", agentName = "研究助手", agentId = "agent-a")),
                                selectedSessionId = "session-a",
                                selectedAgentId = "agent-a",
                            ),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("chat.sidebar").assertIsDisplayed()
        compose.onNodeWithTag("chat.sidebar.session.session-a", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat.sidebar.sessionWorkspace").assertIsDisplayed()
    }

    @Test
    fun workspaceSheetShowsAgentSummaryAndOpensAgentSettings() {
        val settingsTargets = mutableListOf<String?>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    ChatScreen(
                        state = ChatUiState(
                            agents = listOf(ChatAgentOptionUi("agent-a", "研究助手")),
                            sessions = listOf(ChatSessionUi("session-a", "今日研究", agentName = "研究助手", agentId = "agent-a")),
                            selectedSessionId = "session-a",
                            selectedAgentId = "agent-a",
                            workspaceAccess = ChatWorkspaceAccessUi(
                                agentLabel = "研究助手",
                                workspaceSummary = "研究资料",
                                permissionLabel = "读写",
                            ),
                        ),
                        actions = ChatActions(onOpenAgentSettings = { settingsTargets += it }),
                    )
                }
            }
        }

        compose.onNodeWithTag("chat.workspace.open").performClick()
        compose.onNodeWithTag("chat.workspace.sheet", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("当前工作区：研究资料", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat.workspace.agentSettings").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("agent-a"), settingsTargets)
        compose.onAllNodesWithText("content://", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun systemBackClosesWorkspaceSheetThenDrawer() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    ChatScreen(
                        state = ChatUiState(
                            agents = listOf(ChatAgentOptionUi("agent-a", "研究助手")),
                            sessions = listOf(ChatSessionUi("session-a", "今日研究", agentName = "研究助手", agentId = "agent-a")),
                            selectedSessionId = "session-a",
                            selectedAgentId = "agent-a",
                        ),
                    )
                }
            }
        }

        compose.onNodeWithTag("chat.workspace.open").performClick()
        compose.onNodeWithTag("chat.workspace.sheet", useUnmergedTree = true).assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat.workspace.sheet", useUnmergedTree = true).assertCountEquals(0)

        compose.onNodeWithTag("chat.sidebar.open").performClick()
        compose.onNodeWithTag("chat.sidebar").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("chat.sidebar", useUnmergedTree = true).assertIsNotDisplayed()
    }
}
