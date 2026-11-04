// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ToolCallPart

class ChatModelHistoryTest {
    @Test fun orphanUserTurnIsNotSentAsModelHistory() {
        val selection = selectBoundedTurns(
            listOf(user("u1", "same request")),
            100,
        )
        assertTrue(selection.messages.isEmpty())
    }

    @Test fun failedPreDispatchTurnDoesNotBecomeASecondUserMessageOnRetry() {
        val selection = selectBoundedTurns(
            listOf(
                user("u1", "same request"),
                assistant("a1", "", status = "FAILED", parts = listOf(ErrorPart(MessageErrorCode.WORKSPACE_UNAVAILABLE, "offline"))),
                user("u2", "same request"),
                assistant("a2", "answer"),
            ),
            100,
        )
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), selection.messages.map { it.role })
        assertEquals(listOf("u2", "a2"), selection.messages.map { it.id })
    }

    @Test fun repeatedFailedRetriesNeverBecomeConsecutiveUserMessages() {
        val failed = listOf(
            user("u1", "same request"),
            assistant("a1", "", status = "FAILED", parts = listOf(ErrorPart(MessageErrorCode.WORKSPACE_UNAVAILABLE, "offline"))),
            user("u2", "same request"),
            assistant("a2", "", status = "FAILED", parts = listOf(ErrorPart(MessageErrorCode.WORKSPACE_UNAVAILABLE, "offline"))),
        )
        assertTrue(selectBoundedTurns(failed, 100).messages.isEmpty())
    }

    @Test fun partialAssistantStillMakesItsUserTurnContextual() {
        val selection = selectBoundedTurns(
            listOf(
                user("u1", "request"),
                assistant("a1", "partial answer", status = "STREAMING"),
            ),
            100,
        )
        assertEquals(listOf("u1", "a1"), selection.messages.map { it.id })
    }

    @Test fun unresolvedToolExchangeIsNotProjected() {
        val selection = selectBoundedTurns(
            listOf(
                user("u1", "request"),
                assistant("a1", "", parts = listOf(ToolCallPart("call-1", "file_read_text", "{}"))),
            ),
            100,
        )
        assertTrue(selection.messages.isEmpty())
    }

    @Test fun completedTurnsKeepTheirTurnIdsAndRespectTheBudget() {
        val selection = selectBoundedTurns(
            listOf(
                user("u1", "one"), assistant("a1", "first"),
                user("u2", "two"), assistant("a2", "second"),
            ),
            2,
        )
        assertEquals(listOf("u2", "a2"), selection.messages.map { it.id })
        assertEquals("u2", selection.turnIds["u2"])
        assertEquals("u2", selection.turnIds["a2"])
    }

    private fun user(id: String, text: String) = Message(
        id = id,
        conversationId = "conversation",
        role = MessageRole.USER,
        text = text,
        status = "COMPLETE",
        createdAt = AT,
    )

    private fun assistant(
        id: String,
        text: String,
        status: String = "COMPLETE",
        parts: List<MessagePart> = emptyList(),
    ) = Message(
        id = id,
        conversationId = "conversation",
        role = MessageRole.ASSISTANT,
        text = text,
        status = status,
        createdAt = AT,
        parts = parts,
    )

    companion object {
        private const val AT = "2026-11-04T00:00:00Z"
    }
}
