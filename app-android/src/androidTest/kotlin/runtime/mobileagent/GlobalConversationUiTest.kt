// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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
import runtime.mobileagent.feature.chat.ChatDrawerDestinationUi
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.feature.chat.ChatSessionUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ChatWorkspaceUi
import runtime.mobileagent.feature.chat.ConversationScreen
import runtime.mobileagent.feature.chat.GlobalDrawerContent
import runtime.mobileagent.ui.GlobalDrawerShell

@RunWith(AndroidJUnit4::class)
class GlobalConversationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun compactDrawerKeepsWorkspaceSessionsAndNavigationReachable() {
        val selected = mutableListOf<String>()
        var drawerOpen by mutableStateOf(true)
        val state = drawerState()
        compose.setContent {
            MaterialTheme {
                GlobalDrawerShell(
                    selectedRoute = "chat",
                    destinations = emptyList(),
                    onRouteSelected = {},
                    modifier = Modifier.width(320.dp).height(640.dp),
                    drawerOpen = drawerOpen,
                    onDrawerOpenChange = { drawerOpen = it },
                    drawerContent = { close ->
                        GlobalDrawerContent(
                            state = state,
                            actions = ChatActions(
                                onSelectWorkspace = { selected += "workspace:$it" },
                                onSelectSession = { selected += "session:$it" },
                            ),
                            destinations = listOf(
                                ChatDrawerDestinationUi("agents", "智能体"),
                                ChatDrawerDestinationUi("settings", "设置"),
                            ),
                            selectedRoute = "chat",
                            onNavigate = { selected += "route:$it" },
                            onClose = close,
                        )
                    },
                    content = { Box(Modifier.width(320.dp).height(640.dp)) },
                )
            }
        }

        compose.onNodeWithTag("global.drawer.modal").assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.workspace.workspace-a").assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.agent.workspace.agent-a").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.session.session-a").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.navigation.agents").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.close").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, drawerOpen)
    }

    @Test
    fun openWorkspaceRequiresASelectedAgent() {
        var openRequests = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    GlobalDrawerContent(
                        state = ChatUiState(),
                        actions = ChatActions(onOpenWorkspacePicker = { openRequests += 1 }),
                    )
                }
            }
        }

        compose.onNodeWithTag("global.drawer.workspace.open")
            .assertIsNotEnabled()
            .performClick()
        compose.onNodeWithTag("global.drawer.workspace.requires-agent").assertIsDisplayed()
        assertEquals(0, openRequests)
    }

    @Test
    fun wideShellUsesPermanentDrawerAndConversationHasNoSecondDrawer() {
        val state = drawerState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    GlobalDrawerShell(
                        selectedRoute = "chat",
                        destinations = listOf(ChatDrawerDestinationUi("chat", "对话")).map {
                            runtime.mobileagent.ui.AppNavigationDestination(it.route, it.label, Icons.Outlined.Chat)
                        },
                        onRouteSelected = {},
                        drawerOpen = false,
                        modifier = Modifier.width(700.dp).height(640.dp),
                        content = { ConversationScreen(state = state) },
                    )
                }
            }
        }

        compose.onNodeWithTag("global.shell.wide").assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.permanent").assertIsDisplayed()
        compose.onNodeWithTag("global.drawer.navigation.chat").assertIsDisplayed()
        compose.onNodeWithTag("conversation.drawer.open").assertIsDisplayed()
    }

    @Test
    fun reasoningIsAbsentWhenEmptyAndCollapsedWhenProviderReturnsIt() {
        val state = ChatUiState(
            agents = listOf(ChatAgentOptionUi("agent-a", "研究助手")),
            sessions = listOf(ChatSessionUi("session-a", "当前对话", agentName = "研究助手", agentId = "agent-a")),
            selectedAgentId = "agent-a",
            selectedSessionId = "session-a",
            messages = listOf(
                ChatMessageUi(
                    id = "assistant-a",
                    role = "assistant",
                    text = "结论正文",
                    reasoning = "这里是模型真实返回的思考内容。",
                ),
            ),
        )
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        ConversationScreen(state = state)
                    }
                }
            }
        }

        compose.onNodeWithText("结论正文").assertIsDisplayed()
        compose.onNodeWithText("显示思考").assertIsDisplayed().performClick()
        compose.onNodeWithText("这里是模型真实返回的思考内容。", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithTag("conversation.reasoning.body.missing", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun composerAcceptsImeTextAndSystemBackClosesCompactDrawer() {
        var input by mutableStateOf("")
        var sent = 0
        var drawerOpen by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val state = drawerState().copy(input = input, messages = emptyList())
                GlobalDrawerShell(
                    selectedRoute = "chat",
                    destinations = emptyList(),
                    onRouteSelected = {},
                    drawerOpen = drawerOpen,
                    onDrawerOpenChange = { drawerOpen = it },
                    modifier = Modifier.width(320.dp).height(640.dp),
                    drawerContent = { close ->
                        GlobalDrawerContent(
                            state = state,
                            actions = ChatActions(),
                            onClose = close,
                        )
                    },
                    content = {
                        ConversationScreen(
                            state = state,
                            actions = ChatActions(
                                onInput = { input = it },
                                onSend = { sent += 1 },
                            ),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("global.drawer.modal").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(false, drawerOpen)

        compose.onNodeWithTag("conversation.composer.input")
            .performTextInput("测试消息")
        compose.onNodeWithTag("conversation.composer.send")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, sent)
    }

    @Test
    fun conversationTopBarUsesIconButtonsOnNarrowScreen() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        ConversationScreen(
                            state = drawerState().copy(
                                workspaceAccess = runtime.mobileagent.feature.chat.ChatWorkspaceAccessUi(
                                    workspaceSummary = "/storage/emulated/0/Download",
                                    systemAccessLabel = "Shizuku",
                                ),
                            ),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("conversation.topBar").assertIsDisplayed()
        compose.onNodeWithTag("conversation.drawer.open").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开菜单").assertIsDisplayed()
        compose.onNodeWithTag("conversation.more").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多选项").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("菜单", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("更多", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("conversation.context").assertIsDisplayed()
    }

    @Test
    fun compactNonChatShellUsesMenuIconWithoutVisibleMenuText() {
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                MaterialTheme {
                    GlobalDrawerShell(
                        selectedRoute = "providers",
                        destinations = listOf(
                            runtime.mobileagent.ui.AppNavigationDestination(
                                "providers",
                                "服务商",
                                Icons.Outlined.Tune,
                            ),
                            runtime.mobileagent.ui.AppNavigationDestination(
                                "settings",
                                "设置",
                                Icons.Outlined.Settings,
                            ),
                        ),
                        onRouteSelected = {},
                        showCompactOpenButton = true,
                        compactOpenButtonLabel = "打开菜单",
                        modifier = Modifier.width(320.dp).height(640.dp),
                        onDrawerOpenChange = { if (it) opened += 1 },
                        content = { _ ->
                            Box(Modifier.width(320.dp).height(640.dp))
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("global.drawer.open").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开菜单").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("菜单", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("global.drawer.open").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun landscapeNarrowHeightKeepsMenuIconReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                MaterialTheme {
                    Box(Modifier.width(640.dp).height(320.dp)) {
                        ConversationScreen(state = drawerState())
                    }
                }
            }
        }
        compose.onNodeWithTag("conversation.drawer.open").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开菜单").assertIsDisplayed()
        compose.onNodeWithTag("conversation.topBar").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("菜单", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun wideLayoutHidesCompactMenuOpener() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                MaterialTheme {
                    GlobalDrawerShell(
                        selectedRoute = "settings",
                        destinations = listOf(
                            runtime.mobileagent.ui.AppNavigationDestination(
                                "settings",
                                "设置",
                                Icons.Outlined.Settings,
                            ),
                            runtime.mobileagent.ui.AppNavigationDestination(
                                "providers",
                                "服务商",
                                Icons.Outlined.Tune,
                            ),
                        ),
                        onRouteSelected = {},
                        showCompactOpenButton = true,
                        compactOpenButtonLabel = "打开菜单",
                        modifier = Modifier.width(640.dp).height(320.dp),
                        content = { _ ->
                            Box(Modifier.width(640.dp).height(320.dp))
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("global.shell.wide").assertIsDisplayed()
        compose.onAllNodesWithTag("global.drawer.open").assertCountEquals(0)
        assertEquals(0, compose.onAllNodesWithText("菜单", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun drawerState(): ChatUiState = ChatUiState(
        agents = listOf(ChatAgentOptionUi("agent-a", "研究助手")),
        sessions = listOf(
            ChatSessionUi(
                id = "session-a",
                title = "整理工作区",
                preview = "最近一次任务",
                agentName = "研究助手",
                agentId = "agent-a",
            ),
        ),
        selectedSessionId = "session-a",
        selectedAgentId = "agent-a",
        workspaces = listOf(ChatWorkspaceUi("workspace-a", "mobileAgentRuntime", "已连接", "ADB 级访问")),
        selectedWorkspaceId = "workspace-a",
        currentAuthorityLabel = "已连接",
    )
}
