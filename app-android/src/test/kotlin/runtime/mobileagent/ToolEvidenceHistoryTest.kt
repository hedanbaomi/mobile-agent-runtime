// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class ToolEvidenceHistoryTest {
    private fun message(id: String, role: MessageRole, parts: List<MessagePart> = emptyList(), metadata: String = "{}") =
        Message(id, "c", role = role, text = id, status = "COMPLETE", createdAt = "2026-10-06T00:00:00Z", parts = parts, metadataJson = metadata)
    private fun assistant() = message("calls", MessageRole.ASSISTANT, listOf(ToolCallPart("a", "search", "{}"), ToolCallPart("b", "workspace_list", "{}"), ReasoningPart("declared reasoning")))
    private fun result(id: String) = message(id, MessageRole.TOOL, listOf(ToolResultPart(id, "{}", "SUCCEEDED")))
    private fun evidence() = message("images", MessageRole.USER, listOf(ImagePart("asset", "image/png")), "{\"toolEvidence\":true}")

    @Test fun repairsLegacyCompleteExchangeWithoutChangingRecordsOrRealUserInput() {
        val rows = listOf(message("user", MessageRole.USER), assistant(), result("a"), evidence(), result("b"), message("retry", MessageRole.USER))
        val projected = orderedToolEvidenceHistory(rows)
        assertEquals(listOf("user", "calls", "a", "b", "images", "retry"), projected.map { it.id })
        assertEquals(rows.toSet(), projected.toSet(), "No content, identity, timestamp or evidence is lost")
        assertEquals(listOf("user", "calls", "a", "images", "b", "retry"), rows.map { it.id }, "No durable mutation")
        assertEquals(projected, orderedToolEvidenceHistory(projected), "Projection is idempotent")
        assertEquals("declared reasoning", replayableReasoning(projected[1]))
    }

    @Test fun incompleteDuplicateOrRealUserInterruptedExchangesAreNotReordered() {
        listOf(
            listOf(assistant(), result("a"), evidence()),
            listOf(assistant(), result("a"), evidence(), result("a")),
            listOf(assistant(), result("a"), message("real-user", MessageRole.USER), result("b")),
        ).forEach { assertEquals(it, orderedToolEvidenceHistory(it)) }
    }

    @Test fun neverInfersReasoningFromAnswerAndRejectsPossiblyClippedLegacyParts() {
        assertNull(replayableReasoning(message("answer", MessageRole.ASSISTANT, listOf(TextPart("thinking about answer")))))
        assertNull(replayableReasoning(message("tool", MessageRole.TOOL, listOf(ReasoningPart("untrusted")))))
        assertThrows(IllegalArgumentException::class.java) {
            replayableReasoning(message("clipped", MessageRole.ASSISTANT, listOf(ReasoningPart("x".repeat(MessagePartLimits.MAX_REASONING_CHARS)))))
        }
        assertNull(replayableReasoning(message("clipped-response", MessageRole.ASSISTANT, listOf(ReasoningPart("x".repeat(MessagePartLimits.MAX_REASONING_CHARS)))), enabled = false))
    }

    @Test fun declaredReasoningCollectorPreservesWhitespaceAndRedactsAcrossChunks() {
        val accumulated = listOf("alpha", " ", "beta", "\n").fold("") { current, delta -> appendDeclaredReasoning(current, delta, emptyList()) }
        val persisted = message("assistant", MessageRole.ASSISTANT, listOf(ReasoningPart(accumulated)))
        assertEquals("alpha beta\n", replayableReasoning(persisted))
        assertEquals(" \n", replayableReasoning(message("white", MessageRole.ASSISTANT, listOf(ReasoningPart(" \n")))))
        val safe = listOf("key secret-", "value end").fold("") { current, delta -> appendDeclaredReasoning(current, delta, listOf("secret-value")) }
        assertFalse(safe.contains("secret-value"))
    }
}
