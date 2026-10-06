// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.chat.*

@OptIn(ExperimentalTestApi::class)
class ChatImeResponsivenessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun typingInLongConversationDoesNotInvalidateTheShellOrLoseChineseText() {
        val table = "| 项目 | 说明 | 结果 |\n|---|---|---|\n" +
            (1..40).joinToString("\n") { "| **项目$it** | 长表格中的中文说明与可追溯证据 | 保留原文 |" }
        val source = mutableStateOf(ChatUiState(selectedSessionId = "ime-fixture", language = "zh-CN",
            messages = listOf(ChatMessageUi("goal", "user", "阅读文件并保留记录"),
                ChatMessageUi("long", "assistant", table + "\n" + "长消息说明。\n".repeat(400)))))
        var shellCompositions = 0
        compose.setContent {
            val presentation by rememberConversationPresentation(source)
            val shellState = presentation
            SideEffect { shellCompositions++ }
            MaterialTheme { ConversationScreen(shellState,
                actions = ChatActions(onInput = { source.value = source.value.copy(input = it) },
                    onSend = { source.value = source.value.copy(input = "") }),
                input = { source.value.input }) }
        }
        compose.onNodeWithTag("conversation.composer.input").performClick()
        compose.waitForIdle()
        val before = shellCompositions
        listOf("你", "好", "，", "请", "读", "文", "件").forEach {
            compose.onNodeWithTag("conversation.composer.input").performTextInput(it)
        }
        compose.onNodeWithTag("conversation.composer.input").assertTextEquals("你好，请读文件")
        compose.onNodeWithTag("conversation.composer.input").performTextInputSelection(TextRange(2))
        compose.onNodeWithTag("conversation.composer.input").performTextInput("！")
        compose.onNodeWithTag("conversation.composer.input").assertTextEquals("你好！，请读文件")
        compose.runOnIdle { assertEquals("Typing must only invalidate the composer", before, shellCompositions) }
        compose.onNodeWithTag("conversation.composer.send").performClick()
        compose.onNodeWithTag("conversation.composer.input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { source.value = source.value.copy(status = "更新后的任务状态") }
        compose.waitForIdle()
        assertTrue("Actual task changes must still refresh the shell", shellCompositions > before)
    }

    @Test fun androidInputConnectionKeepsChineseCompositionAcrossTaskUpdates() {
        val source = mutableStateOf(ChatUiState(selectedSessionId = "composition", language = "zh-CN"))
        compose.setContent {
            val presentation by rememberConversationPresentation(source)
            MaterialTheme { ConversationScreen(presentation,
                actions = ChatActions(onInput = { source.value = source.value.copy(input = it) }),
                input = { source.value.input }) }
        }
        val field = compose.onNodeWithTag("conversation.composer.input")
        field.performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle {
            connection = requireNotNull(compose.activity.window.decorView.findFocus().onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText("ni", 1))
        }
        field.assertTextEquals("ni")
        compose.runOnIdle { source.value = source.value.copy(status = "任务状态更新") }
        compose.runOnIdle { assertTrue(connection.setComposingText("你", 1)) }
        field.assertTextEquals("你")
        compose.runOnIdle { assertTrue(connection.setComposingText("你好", 1)) }
        field.assertTextEquals("你好")
        compose.runOnIdle { assertTrue(connection.finishComposingText()) }
        field.performTextInput("！")
        field.assertTextEquals("你好！")
    }
}
