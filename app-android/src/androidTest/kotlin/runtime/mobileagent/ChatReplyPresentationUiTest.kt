// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.ui.AppThemeMode
import runtime.mobileagent.ui.MobileAgentTheme

/** Real Compose interaction: links, terminal transition, secondary page and Android clipboard. */
class ChatReplyPresentationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private val first = ChatCitationUi("call-one-0", "课程笔记.docx", "doc", "第一段对应原文", "第 3 段", true,
        knowledgeBaseId = "kb", documentVersionId = "v1", chunkId = "chunk-1")
    private val repeated = first.copy(id = "call-two-0")
    private val second = first.copy(id = "call-two-1", excerpt = "第二段对应原文", chunkId = "chunk-2")

    private fun tapCitation(text: String, number: Int, last: Boolean = false) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val index = if (last) text.lastIndexOf("[$number]") else text.indexOf("[$number]")
        val offset = layouts.single().getBoundingBox(index + 1).center
        // Text layouts retain the whole long answer; use its unclipped origin
        // when mapping the visible glyph into root touch coordinates.
        val position = node.fetchSemanticsNode().positionInRoot + offset
        assertTrue(compose.onRoot().fetchSemanticsNode().boundsInRoot.contains(position))
        compose.onRoot().performTouchInput { click(position) }
    }

    @Test fun inlineNumbersDeduplicateAndOpenTheMatchingDocumentExcerpt() {
        val state = mutableStateOf(ChatUiState(selectedSessionId = "s", messages = listOf(
            ChatMessageUi("a", "assistant", "答案【call-one-0】及【call-two-0】、[citation:call-two-1]",
                citationIds = listOf(first.id, repeated.id, second.id))), citations = listOf(first, repeated, second)))
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { ConversationScreen(state.value, ChatActions(
            onOpenCitation = { opened += it; state.value = state.value.copy(selectedCitationId = it) },
            onCloseCitation = { state.value = state.value.copy(selectedCitationId = null) },
        )) } }
        compose.onNodeWithText("答案[1]及[1]、[2]", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("来源：课程笔记.docx").assertCountEquals(0)
        compose.onAllNodesWithText("call-one-0", substring = true).assertCountEquals(0)
        tapCitation("答案[1]及[1]、[2]", 2)
        assertEquals(listOf(second.id), opened)
        compose.onNodeWithText("课程笔记.docx").assertIsDisplayed()
        compose.onNodeWithTag("conversation.citation.excerpt", useUnmergedTree = true).assertTextEquals("第二段对应原文")
        compose.onNodeWithText("关闭").performClick()
        tapCitation("答案[1]及[1]、[2]", 1)
        assertEquals(listOf(second.id, first.id), opened)
        compose.onNodeWithTag("conversation.citation.excerpt", useUnmergedTree = true).assertTextEquals("第一段对应原文")
    }

    @Test fun copyIncludesTheEntireReplyAndExcludesToolsReasoningAndRepeatedFooter() {
        val text = "答复[citation:call-one-0]\n\n## 来源\n- [call-one-0]"
        val state = ChatUiState(language = "en-US", selectedSessionId = "s", messages = listOf(
            ChatMessageUi("a", "assistant", "前文", reasoning = "private reasoning"),
            ChatMessageUi("t", "tool", "TOOL_PAYLOAD"),
            ChatMessageUi("final", "assistant", text, citationIds = listOf(first.id))), citations = listOf(first))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onNodeWithTag("conversation.copy.a").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("前文\n\n答复[1]", clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString())
        }
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onAllNodesWithText("TOOL_PAYLOAD").assertCountEquals(0)
    }

    @Test fun runningToolsStayVisibleThenMoveToASecondaryPageWithoutChangingMessages() {
        val messages = listOf(ChatMessageUi("a", "assistant", "计划"),
            ChatMessageUi("t1", "tool", "FIRST_RESULT"), ChatMessageUi("t2", "tool", "SECOND_RESULT"),
            ChatMessageUi("final", "assistant", "最终答案"))
        val state = mutableStateOf(ChatUiState(selectedSessionId = "s", messages = messages, streaming = true))
        compose.setContent { MaterialTheme { ConversationScreen(state.value) } }
        // No assistant chunk is streaming while waiting on a tool; run activity still keeps rows visible.
        compose.onNodeWithTag("conversation.tool.t1").assertIsDisplayed()
        compose.onNodeWithTag("conversation.tool.t2").assertIsDisplayed()
        compose.onAllNodesWithTag("conversation.copy.a").assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(streaming = false) }
        compose.onAllNodesWithTag("conversation.tool.t1").assertCountEquals(0)
        compose.onAllNodesWithText("FIRST_RESULT").assertCountEquals(0)
        compose.onNodeWithTag("conversation.tools.open.a").performClick()
        compose.onNodeWithTag("conversation.tools.detail").assertIsDisplayed()
        compose.onNodeWithTag("conversation.tool.t1").assertIsDisplayed()
        compose.onNodeWithTag("conversation.tool.t2").assertIsDisplayed()
        compose.onAllNodesWithText("查看并复制工具结果")[0].performClick()
        compose.onAllNodesWithText("FIRST_RESULT").assertCountEquals(2)
        compose.onNodeWithTag("conversation.tools.close").performClick()
        compose.onAllNodesWithTag("conversation.tools.detail").assertCountEquals(0)
        compose.onNodeWithTag("conversation.copy.a").assertIsDisplayed()
        assertEquals(messages, state.value.messages)
    }

    @Test fun aNewRunningTurnDoesNotReopenHistoricalToolRows() {
        val state = ChatUiState(selectedSessionId = "s", streaming = true, messages = listOf(
            ChatMessageUi("old-a", "assistant", "旧答案"), ChatMessageUi("old-t", "tool", "OLD_RESULT"),
            ChatMessageUi("user", "user", "新问题"), ChatMessageUi("new-a", "assistant", "新计划"),
            ChatMessageUi("new-t", "tool", "NEW_RESULT")))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onAllNodesWithTag("conversation.tool.old-t").assertCountEquals(0)
        compose.onNodeWithTag("conversation.tools.open.old-a").assertIsDisplayed()
        compose.onNodeWithTag("conversation.tool.new-t").assertIsDisplayed()
    }

    @Test fun unavailableCitationKeepsItsNumberAndShowsTheUnavailableSourceInsteadOfAnotherDocument() {
        val missing = first.copy(verified = false, excerpt = "来源已移除或授权失效。")
        val state = mutableStateOf(ChatUiState(selectedSessionId = "s", messages = listOf(
            ChatMessageUi("a", "assistant", "答案[call-one-0]", citationIds = listOf(first.id))), citations = listOf(missing)))
        compose.setContent { MaterialTheme { ConversationScreen(state.value, ChatActions(
            onOpenCitation = { state.value = state.value.copy(selectedCitationId = it) })) } }
        tapCitation("答案[1]", 1)
        compose.onNodeWithText("来源已移除或授权失效。", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("证据状态不可用").assertIsDisplayed()
    }

    @Test fun collapseDoesNotHideUnknownOutcomeOrCoverageNotices() {
        val state = ChatUiState(selectedSessionId = "s", status = "请求结果未知，不会自动重试。",
            messages = listOf(ChatMessageUi("a", "assistant", "已保留输出。", eventSummary = "本次检索部分来源未参与。"),
                ChatMessageUi("t", "tool", "TOOL_RESULT")))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onNodeWithText("请求结果未知，不会自动重试。").assertIsDisplayed()
        compose.onNodeWithTag("conversation.notice.a").assertIsDisplayed()
        compose.onNodeWithTag("conversation.tools.open.a").assertIsDisplayed()
        compose.onAllNodesWithText("TOOL_RESULT").assertCountEquals(0)
    }

    @Test fun pendingApprovalKeepsToolRowsVisibleEvenWithoutStreamingChunks() {
        val state = ChatUiState(selectedSessionId = "s", streaming = false,
            pendingTool = ChatToolApprovalUi("pending", "file_write", "写入自造测试文件"), messages = listOf(
                ChatMessageUi("a", "assistant", "计划"), ChatMessageUi("t", "tool", "RESULT_BEFORE_APPROVAL")))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onNodeWithTag("conversation.tool.t").assertExists()
        compose.onAllNodesWithTag("conversation.tools.open.a").assertCountEquals(0)
        compose.onAllNodesWithTag("conversation.copy.a").assertCountEquals(0)
        compose.onNodeWithTag("chat.approval.approve").assertIsDisplayed()
        compose.onNodeWithTag("chat.approval.reject").assertIsDisplayed()
    }

    @Test fun anEmptyInitialAssistantStillHasOneBubbleWithCopyBelowTheFinalAnswer() {
        val state = ChatUiState(selectedSessionId = "s", messages = listOf(
            ChatMessageUi("first", "assistant", "", reasoning = "思考"), ChatMessageUi("t", "tool", "RESULT"),
            ChatMessageUi("last", "assistant", "最终答复")))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onAllNodesWithTag("conversation.message.first").assertCountEquals(1)
        compose.onAllNodesWithTag("conversation.message.last").assertCountEquals(0)
        val bubble = hasAnyAncestor(hasTestTag("conversation.message.first"))
        val answer = compose.onNode(hasText("最终答复") and bubble, useUnmergedTree = true)
        val copy = compose.onNode(hasTestTag("conversation.copy.first") and bubble, useUnmergedTree = true)
        answer.assertIsDisplayed()
        copy.assertIsDisplayed()
        assertTrue(copy.fetchSemanticsNode().boundsInRoot.top >= answer.fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test fun citationLikeCodeAndOrdinaryUserTextAreStillLiteralAndCopyable() {
        val state = ChatUiState(language = "en-US", selectedSessionId = "s", messages = listOf(
            ChatMessageUi("u", "user", "Tool visual evidence: a genuine user message"),
            ChatMessageUi("a", "assistant", "Code: `[citation:call-one-0]`\n~~~\n[call-one-0]\n~~~",
                citationIds = listOf(first.id))), citations = listOf(first))
        compose.setContent { MaterialTheme { ConversationScreen(state) } }
        compose.onNodeWithText("Tool visual evidence: a genuine user message").assertIsDisplayed()
        compose.onNodeWithText("Code: [citation:call-one-0]").assertIsDisplayed()
        compose.onNodeWithText("[call-one-0]").assertIsDisplayed()
        compose.onNodeWithTag("conversation.copy.a").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(state.messages.last().text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun longReplyRemainsCopyableAcrossThemesAndLargeFontWithOneToolEntry() {
        val mode = mutableStateOf(AppThemeMode.LIGHT)
        val selectedCitation = mutableStateOf<String?>(null)
        val answer = (1..24).joinToString("\n\n") { "第 $it 段：这是用于检查长回复滚动和引用入口的自造内容。【call-one-0】" }
        val messages = listOf(ChatMessageUi("u", "user", "请解释知识库内容。"),
            ChatMessageUi("a", "assistant", "", reasoning = "思考过程保留在独立折叠入口。")) +
            (1..40).map { ChatMessageUi("tool-$it", "tool", "SYNTHETIC_TOOL_RESULT_$it") } +
            ChatMessageUi("final", "assistant", answer, citationIds = listOf(first.id))
        val state = ChatUiState(selectedSessionId = "s", messages = messages, citations = listOf(first))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.8f)) {
                MobileAgentTheme(mode.value) { ConversationScreen(state.copy(selectedCitationId = selectedCitation.value), ChatActions(
                    onOpenCitation = { selectedCitation.value = it }, onCloseCitation = { selectedCitation.value = null },
                )) }
            }
        }
        fun capture(name: String, tag: String? = null) {
            val node = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
            val bitmap = node.captureToImage().asAndroidBitmap()
            val folder = File(compose.activity.getExternalFilesDir(null), "chat-preview-1151").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.CC66FF).forEach { theme ->
            compose.runOnIdle {
                mode.value = theme
                val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("test", "clipboard sentinel for $theme"))
            }
            val copy = compose.onNodeWithTag("conversation.copy.a").performScrollTo().assertIsDisplayed()
            val position = copy.fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { click(position) }
            compose.onNodeWithText("已复制").assertIsDisplayed()
            compose.runOnIdle {
                val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(answer.replace("【call-one-0】", "[1]"), clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            compose.onAllNodesWithTag("conversation.tools.open.a").assertCountEquals(1)
            compose.onAllNodesWithTag("conversation.tool.tool-1").assertCountEquals(0)
            compose.onAllNodesWithText("来源：课程笔记.docx").assertCountEquals(0)
            capture("${theme.name.lowercase()}-reply-bottom")
        }
        val tools = compose.onNodeWithTag("conversation.tools.open.a").performScrollTo()
        val position = tools.fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(position) }
        capture("tool-secondary-page", "conversation.tools.detail")
        compose.onNodeWithTag("conversation.tool.tool-1").assertIsDisplayed()
        compose.onNodeWithTag("conversation.tools.close").performClick()
        tapCitation(answer.replace("【call-one-0】", "[1]"), 1, last = true)
        compose.onNodeWithText("课程笔记.docx").assertIsDisplayed()
        compose.onNodeWithTag("conversation.citation.excerpt", useUnmergedTree = true).assertTextEquals(first.excerpt)
    }
}
