// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import runtime.mobileagent.feature.agents.*
import runtime.mobileagent.feature.chat.*

class ContextCompactionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun summaryDialogCanJumpToAnOriginalMessage() {
        val messages = (0..25).map { ChatMessageUi("m$it", "user", "Original full message $it") }
        val record = ChatCompactionUi("summary", "SUCCEEDED", listOf("m0", "m1"),
            "{\"goals\":[\"Keep evidence\"]}", "fixture", "history-messages", 4000, 2000, 200, 40,
            "a".repeat(64), "2026-09-11T00:00:00Z")
        compose.setContent { MaterialTheme { ConversationScreen(ChatUiState(
            sessions = listOf(ChatSessionUi("c", "Context fixture")), selectedSessionId = "c",
            messages = messages, compactions = listOf(record), language = "zh-CN",
        )) } }
        compose.onNodeWithTag("conversation.compaction.open").performClick()
        compose.onNodeWithTag("conversation.compaction.summary.summary").performScrollTo().assertIsDisplayed()
        screenshot("context-summary.png")
        compose.onNodeWithText("查看覆盖的原始消息").performScrollTo().performClick()
        compose.onNodeWithTag("conversation.compaction.source.m0").performScrollTo().performClick()
        compose.onNodeWithTag("conversation.compaction.history").assertDoesNotExist()
        compose.onNodeWithText("Original full message 0").assertIsDisplayed()
        compose.onNodeWithTag("conversation.summarized.m0").assertIsDisplayed()
    }

    @Test fun smallScreenPolicyNumbersCanBeClearedAndAdvancedLimitsAreReachable() {
        var editor by mutableStateOf(AgentEditorUi(name = "Fixture"))
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.3f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(640.dp)) {
                    AgentsScreen(AgentsUiState(editor = editor, editorOpen = true, language = "zh-CN"),
                        AgentsActions(onEditorChange = { editor = it }), renderEditorAsPage = true)
                } }
            }
        }
        compose.onNodeWithTag(AgentTestTags.CONTEXT_POLICY_HISTORY_MESSAGES).performScrollTo().performTextClearance()
        compose.runOnIdle { assertEquals("", editor.contextPolicyDraft.maxHistoryMessages) }
        compose.onNodeWithTag(AgentTestTags.CONTEXT_POLICY_HISTORY_MESSAGES).performTextInput("24")
        compose.onNodeWithTag(AgentTestTags.CONTEXT_POLICY_ADVANCED).performScrollTo().performClick()
        compose.onNodeWithTag(AgentTestTags.CONTEXT_POLICY_MAX_COMPACTIONS).performScrollTo().assertIsDisplayed()
        screenshot("context-settings.png")
        compose.runOnIdle { assertEquals("24", editor.contextPolicyDraft.maxHistoryMessages) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Android window/dialog transitions run outside the Compose test clock.
        Thread.sleep(350)
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
