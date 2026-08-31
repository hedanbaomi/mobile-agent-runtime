// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability
import runtime.mobileagent.feature.chat.ChatScreen
import runtime.mobileagent.feature.chat.ChatSessionUi
import runtime.mobileagent.feature.chat.ChatToolApprovalUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.RequestInspectorScreen

@RunWith(AndroidJUnit4::class)
class ChatRequestInspectorUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun inspectorEntryRemainsAvailableBeforePreview() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        sessions = listOf(ChatSessionUi("session", "Inspector")),
                        selectedSessionId = "session",
                        requestPreview = null,
                        requestInspectorAvailability = ChatRequestInspectorAvailability.NOT_PREPARED,
                    ),
                    actions = ChatActions(onOpenRequestInspector = { opened += 1 }),
                )
            }
        }

        compose.onNodeWithTag("chat.requestInspector.open").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun inspectorExplainsAllUnavailableStatesWithoutSensitiveFields() {
        val states = listOf(
            ChatRequestInspectorAvailability.DISABLED to "请求检查器已关闭，请到设置开启。",
            ChatRequestInspectorAvailability.NOT_PREPARED to "请求尚未准备。发送消息并完成请求准备后，这里会显示脱敏请求。",
            ChatRequestInspectorAvailability.CONTEXT_LOST to "请求检查器上下文已丢失，请返回对话后重试。",
            ChatRequestInspectorAvailability.READY to "POST https://api.example.invalid/v1/chat",
        )
        val availability = mutableStateOf(states.first().first)
        compose.setContent {
            MaterialTheme {
                RequestInspectorScreen(
                    request = if (availability.value == ChatRequestInspectorAvailability.READY) {
                        runtime.mobileagent.feature.chat.ChatRequestPreviewUi(
                            method = "POST",
                            url = "https://api.example.invalid/v1/chat",
                            headers = "Authorization: [REDACTED]",
                            body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                        )
                    } else {
                        null
                    },
                    layers = emptyList(),
                    onClose = {},
                    zh = true,
                    availability = availability.value,
                )
            }
        }

        states.forEachIndexed { index, (nextAvailability, expected) ->
            if (index > 0) {
                compose.runOnUiThread { availability.value = nextAvailability }
                compose.waitForIdle()
            }
            if (nextAvailability == ChatRequestInspectorAvailability.READY) {
                compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("敏感请求头与密钥已遮盖；以下内容仅来自脱敏请求检查数据。", useUnmergedTree = true).assertIsDisplayed()
            } else {
                compose.onNodeWithTag("chat.requestInspector.state").assertIsDisplayed()
                compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
            }
        }
    }

    @Test
    fun disabledInspectorDoesNotRenderStalePreviewOrPromptLayers() {
        val stalePreview = runtime.mobileagent.feature.chat.ChatRequestPreviewUi(
            method = "POST",
            url = "https://api.example.invalid/v1/stale-preview",
            headers = "Authorization: Bearer stale-secret",
            body = "{\"staleBodyMarker\":\"stale-body\"}",
        )
        compose.setContent {
            MaterialTheme {
                RequestInspectorScreen(
                    request = stalePreview,
                    layers = listOf(runtime.mobileagent.feature.chat.ChatPromptLayerUi("system", "stale-prompt-marker")),
                    onClose = {},
                    zh = true,
                    availability = ChatRequestInspectorAvailability.DISABLED,
                )
            }
        }

        compose.onNodeWithTag("chat.requestInspector.state").assertIsDisplayed()
        compose.onNodeWithText("请求检查器已关闭，请到设置开启。", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("POST https://api.example.invalid/v1/stale-preview", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Authorization: Bearer stale-secret", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("{\"staleBodyMarker\":\"stale-body\"}", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("stale-prompt-marker", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun disabledAvailabilityWinsOverPreviewAndPersistedHint() {
        assertEquals(
            ChatRequestInspectorAvailability.DISABLED,
            resolveRequestInspectorAvailability(
                inspectorEnabled = false,
                previewAvailable = true,
                persistedPreviewHint = true,
            ),
        )
    }

    @Test
    fun approvalCardOffersOnlyOneInvocationApproval() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        sessions = listOf(ChatSessionUi("session", "Approval")),
                        selectedSessionId = "session",
                        pendingTool = ChatToolApprovalUi(
                            id = "request",
                            name = "shell_exec",
                            summary = "A bounded command requires confirmation.",
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithText("允许一次", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("仅允许本次调用，不会创建会话或持久权限。", useUnmergedTree = true).assertIsDisplayed()
    }
}
