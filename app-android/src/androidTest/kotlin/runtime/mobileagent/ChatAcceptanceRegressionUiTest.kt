// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ConversationScreen

class ChatAcceptanceRegressionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun assistantMarkdownShowsHeadingAndUntruncatedCode() {
        val state = ChatUiState(language = "en-US", selectedSessionId = "session",
            messages = listOf(ChatMessageUi("answer", "assistant", "# Heading\n**Bold answer**\n```kotlin\nval answer = 42\n```")))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onNodeWithText("Heading").assertIsDisplayed()
        compose.onNodeWithText("Bold answer").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertIsDisplayed()
    }

    @Test fun emptyInterruptedAnswerAndCompactionFailureOfferHonestRecovery() {
        var newSessions = 0
        val state = ChatUiState(language = "en-US", selectedSessionId = "session",
            status = "上下文压缩失败，原始消息已保留；本次不会自动重试。",
            messages = listOf(ChatMessageUi("answer", "assistant", "")))
        compose.setContent { MaterialTheme {
            ConversationScreen(state, ChatActions(onNewSession = { newSessions++ }))
        } }
        compose.onNodeWithTag("conversation.emptyAnswer.answer").assertIsDisplayed()
        compose.onNodeWithTag("conversation.contextRecovery.new").performClick()
        assertEquals(1, newSessions)
    }
}
