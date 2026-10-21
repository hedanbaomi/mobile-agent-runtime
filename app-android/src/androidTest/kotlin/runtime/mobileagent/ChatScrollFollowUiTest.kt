// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ConversationScreen

/**
 * Compose regression coverage for "read history without being pulled to the bottom".
 * Device tests are executed by the main Agent; this file only has to compile here.
 */
class ChatScrollFollowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private var messages by mutableStateOf<List<ChatMessageUi>>(emptyList())

    private fun longAssistant(id: String, lines: Int) = ChatMessageUi(
        id = id,
        role = "assistant",
        text = (1..lines).joinToString("\n") { "第 $it 行：守密人描述了旅馆走廊与门后的声响。" },
    )

    private fun content() {
        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    state = ChatUiState(
                        selectedSessionId = "session-1",
                        messages = messages,
                        language = "zh-CN",
                    ),
                    actions = ChatActions(),
                    showGlobalMenu = false,
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversation.list").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun tallLastMessageScrolledUpStaysPutWhenNewContentArrives() {
        messages = listOf(
            ChatMessageUi(id = "u1", role = "user", text = "我推门进入。"),
            longAssistant("a1", 90),
        )
        content()
        // Start at the bottom, then drag the (taller-than-viewport) last message upward:
        // the last visible index is still lastIndex, but its bottom edge is no longer visible.
        compose.onNodeWithTag("conversation.list").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversation.scroll.latest").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("conversation.scroll.latest").assertIsDisplayed()
        compose.waitForIdle()
        val before = compose.onNodeWithTag("conversation.list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

        // New message + streaming-sized growth must not yank the reader to the bottom.
        compose.runOnIdle {
            messages = messages + ChatMessageUi(id = "u2", role = "user", text = "我继续往里走。")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation.scroll.latest").assertIsDisplayed()
        val after = compose.onNodeWithTag("conversation.list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("New content moved the reading position", before, after, 0.1f)
    }

    @Test fun followingKeepsTallLastMessageBottomPinnedWhileTextGrows() {
        messages = listOf(
            ChatMessageUi(id = "u1", role = "user", text = "我推门进入。"),
            longAssistant("a1", 90),
        )
        content()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversation.scroll.latest").fetchSemanticsNodes().isEmpty()
        }
        repeat(5) { index ->
            compose.runOnIdle {
                messages = messages.dropLast(1) + messages.last().copy(
                    text = messages.last().text + "\n第 ${index + 91} 行：门后传来新的脚步声。",
                )
            }
            compose.waitForIdle()
            // A growing message must keep the viewport at its bottom instead of exposing the
            // "jump to latest" affordance.
            compose.onNodeWithTag("conversation.scroll.latest").assertDoesNotExist()
        }
    }
}
