// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.feature.chat.isToolEventRow
import runtime.mobileagent.feature.chat.secondaryNoticeOf

/**
 * R3 QA P3: a non-blank event summary used to turn an assistant message into a compact tool
 * row that displayed the summary INSTEAD of the answer, so a real reply looked like a bare
 * retrieval-coverage notice card.  These are the headless guards for that rendering rule;
 * the call-site guard is the device test
 * `GlobalConversationUiTest.retrievalCoverageNoticeNeverReplacesTheAssistantAnswer`.
 */
class AssistantAnswerRenderingTest {
    @Test
    fun onlyToolMessagesRenderAsEventRows() {
        assertFalse(isToolEventRow("assistant"))
        assertFalse(isToolEventRow("user"))
        assertTrue(isToolEventRow("tool"))
        assertTrue(isToolEventRow("TOOL"))
    }

    @Test
    fun coverageNoticeAccompaniesTheAnswerInsteadOfReplacingIt() {
        val notice = "本次检索有 1/1 个知识库未参与（原因：kb: RETRIEVAL_DISABLED）。"
        val message = ChatMessageUi(
            id = "m",
            role = "assistant",
            text = "图表显示季度增长，照片里是一台设备。",
            eventSummary = notice,
        )

        assertEquals(notice, secondaryNoticeOf(message))
        assertTrue(message.text.isNotBlank(), "the answer text stays part of the bubble")
    }

    @Test
    fun noticeIsOmittedWhenItAddsNothing() {
        assertNull(secondaryNoticeOf(ChatMessageUi(id = "m1", role = "assistant", text = "答案正文")))
        // A terminal error message is already part of the answer text: never printed twice.
        assertNull(
            secondaryNoticeOf(
                ChatMessageUi(
                    id = "m2",
                    role = "assistant",
                    text = "已完成。运行时发生内部错误。",
                    eventSummary = "运行时发生内部错误。",
                ),
            ),
        )
    }
}
