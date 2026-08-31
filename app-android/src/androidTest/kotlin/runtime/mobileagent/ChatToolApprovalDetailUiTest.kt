// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class ChatToolApprovalDetailUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun shellApprovalShowsStructuredDetailsAndKeepsFixedActions() {
        val choices = mutableListOf<ToolApprovalChoice>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        sessions = listOf(ChatSessionUi("session", "Tool confirmation")),
                        selectedSessionId = "session",
                        pendingTool = ChatToolApprovalUi(
                            id = "call-1",
                            name = "shell_exec",
                            summary = (1..80).joinToString("\n") { "additional bounded detail line $it" },
                            command = "cat /tmp/example.txt",
                            cwd = "/tmp",
                            authority = "SHIZUKU (revalidated at approval)",
                            dangerousMode = "ENABLED_CONFIRM_HIGH_RISK (revalidated at approval)",
                            highRisk = true,
                        ),
                        language = "en-US",
                    ),
                    actions = ChatActions(onToolApproval = { choices += it }),
                )
            }
        }

        val details = compose.onNodeWithTag("chat.approval.details")
        details.assert(hasScrollAction())
        compose.onNodeWithText("cat /tmp/example.txt", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("/tmp", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SHIZUKU (revalidated at approval)", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ENABLED_CONFIRM_HIGH_RISK (revalidated at approval)", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("High risk: reconfirmation required", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        details.performTouchInput { swipeUp() }
        compose.onNodeWithTag("chat.approval.approve").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat.approval.reject").assertIsDisplayed().performClick()
        assertEquals(listOf(ToolApprovalChoice.APPROVE, ToolApprovalChoice.REJECT), choices)
    }

    @Test
    fun shellApprovalActionsRemainReachableAtLargeFontAndImeLikeViewport() {
        val choices = mutableListOf<ToolApprovalChoice>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(Modifier.width(320.dp).height(260.dp)) {
                    MaterialTheme {
                        ChatScreen(
                            state = ChatUiState(
                                sessions = listOf(ChatSessionUi("session", "Tool confirmation")),
                                selectedSessionId = "session",
                                pendingTool = ChatToolApprovalUi(
                                    id = "call-1",
                                    name = "shell_exec",
                                    summary = (1..80).joinToString("\n") { "additional bounded detail line $it" },
                                    command = "cat /tmp/example.txt",
                                    cwd = "/tmp",
                                    authority = "SHIZUKU (revalidated at approval)",
                                    dangerousMode = "ENABLED_CONFIRM_HIGH_RISK (revalidated at approval)",
                                    highRisk = true,
                                ),
                                language = "en-US",
                            ),
                            actions = ChatActions(onToolApproval = { choices += it }),
                        )
                    }
                }
            }
        }

        val details = compose.onNodeWithTag("chat.approval.details")
        details.assert(hasScrollAction())
        compose.onNodeWithText("cat /tmp/example.txt", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("/tmp", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SHIZUKU (revalidated at approval)", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ENABLED_CONFIRM_HIGH_RISK (revalidated at approval)", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("High risk: reconfirmation required", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat.approval.approve").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat.approval.reject").assertIsDisplayed().performClick()
        assertEquals(listOf(ToolApprovalChoice.APPROVE, ToolApprovalChoice.REJECT), choices)
    }
}
