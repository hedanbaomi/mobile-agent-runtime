// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatScreen
import runtime.mobileagent.feature.chat.ChatSessionUi
import runtime.mobileagent.feature.chat.ChatToolApprovalUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ToolApprovalChoice

@RunWith(AndroidJUnit4::class)
class ChatApprovalCardUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun longChineseDetailsScroll_whileApprovalActionsRemainReachable() {
        val choices = mutableListOf<ToolApprovalChoice>()
        val summary = (1..80).joinToString(separator = "\n") { index ->
            "请求参数详情第 $index 行：该工具可能读取并发送受限数据。"
        }
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = approvalState(summary = summary, language = "zh-CN", externalEffect = true),
                    actions = ChatActions(onToolApproval = { choices += it }),
                )
            }
        }

        val details = compose.onNodeWithTag("chat.approval.details")
        details.assert(hasScrollAction()).performTouchInput { swipeUp() }
        compose.onNodeWithTag("chat.approval.approve").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat.approval.reject").assertIsDisplayed().performClick()

        assertEquals(listOf(ToolApprovalChoice.APPROVE, ToolApprovalChoice.REJECT), choices)
        compose.onNodeWithText("此请求可能离开设备。", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun shortEnglishDetailsKeepSecurityCopyAndActionsVisible() {
        val choices = mutableListOf<ToolApprovalChoice>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = approvalState(
                        summary = "The tool will inspect the selected document.",
                        language = "en-US",
                        externalEffect = true,
                    ),
                    actions = ChatActions(onToolApproval = { choices += it }),
                )
            }
        }

        compose.onNodeWithText("Confirmation required", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("This request may leave the device.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat.approval.approve").assertIsDisplayed()
        compose.onNodeWithTag("chat.approval.reject").assertIsDisplayed().performClick()

        assertEquals(listOf(ToolApprovalChoice.REJECT), choices)
    }

    private fun approvalState(summary: String, language: String, externalEffect: Boolean): ChatUiState =
        ChatUiState(
            sessions = listOf(ChatSessionUi(id = "session", title = "Tool confirmation")),
            selectedSessionId = "session",
            pendingTool = ChatToolApprovalUi(
                id = "call-1",
                name = "document_export",
                summary = summary,
                externalEffect = externalEffect,
            ),
            language = language,
        )
}
