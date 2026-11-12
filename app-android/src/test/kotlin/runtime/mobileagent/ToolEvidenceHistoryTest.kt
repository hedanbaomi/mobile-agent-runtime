// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.provider.openai.StreamingSecretRedactor

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

    @Test fun declaredReasoningCollectorPreservesWhitespaceAndRedactsAcrossChunksIncrementally() {
        val plainTarget = StringBuilder()
        val plainRedactor = StreamingSecretRedactor()
        listOf("alpha", " ", "beta", "\n").forEach { delta ->
            appendDeclaredReasoning(plainTarget, plainRedactor, delta)
        }
        val accumulated = finishDeclaredReasoning(plainTarget, plainRedactor)
        val persisted = message("assistant", MessageRole.ASSISTANT, listOf(ReasoningPart(accumulated)))
        assertEquals("alpha beta\n", replayableReasoning(persisted))
        assertEquals(" \n", replayableReasoning(message("white", MessageRole.ASSISTANT, listOf(ReasoningPart(" \n")))))

        val secretTarget = StringBuilder()
        val secretRedactor = StreamingSecretRedactor()
        val secrets = listOf("secret-value")
        listOf("key secret-", "value and Cook", "ie: sid=synthetic_123\n", "sk", "-synthetic1234567890 end").forEach { delta ->
            appendDeclaredReasoning(secretTarget, secretRedactor, delta, secrets)
        }
        val safe = finishDeclaredReasoning(secretTarget, secretRedactor)
        assertFalse(safe.contains("secret-value"))
        assertFalse(safe.contains("synthetic_123"))
        assertFalse(safe.contains("synthetic1234567890"))
        assertEquals("key *** and Cookie: ***\n*** end", safe)
    }

    @Test fun declaredReasoningCollectorDiscardsUnconfirmedPrefixOnAbortAndCapsLength() {
        val secretRef = "synthetic-reasoning-secret-7788"
        val secretHalf = "synthetic-reasoning-"
        val abortedTarget = StringBuilder()
        val abortedRedactor = StreamingSecretRedactor()
        appendDeclaredReasoning(abortedTarget, abortedRedactor, "Confirmed step. Leaking: $secretHalf", listOf(secretRef))
        val discarded = discardDeclaredReasoning(abortedTarget, abortedRedactor)
        assertEquals("Confirmed step. Leaking: ", discarded)
        assertFalse(discarded.contains(secretHalf))
        assertTrue(abortedRedactor.finish().isEmpty())

        val boundedTarget = StringBuilder("x".repeat(MessagePartLimits.MAX_REASONING_CHARS - 4))
        val boundedRedactor = StreamingSecretRedactor()
        appendDeclaredReasoning(boundedTarget, boundedRedactor, "123456789")
        val capped = finishDeclaredReasoning(boundedTarget, boundedRedactor)
        assertEquals(MessagePartLimits.MAX_REASONING_CHARS, capped.length)
        assertTrue(capped.endsWith("1234"))
    }

    @Test fun declaredReasoningStreams1024ChunksWithBoundedTailAndExactRedaction() {
        val target = StringBuilder()
        val redactor = StreamingSecretRedactor()
        val secret = "synthetic-reasoning-long-secret-9001"
        val full = buildString {
            while (length < 16_384) {
                if (length in 4_000..4_020) {
                    append(" token=$secret ")
                } else {
                    append("step-analysis-01")
                }
            }
        }.substring(0, 16_384)

        var idx = 0
        while (idx < full.length) {
            val next = (idx + 16).coerceAtMost(full.length)
            appendDeclaredReasoning(target, redactor, full.substring(idx, next), listOf(secret))
            assertTrue(redactor.pendingLength() <= 64)
            idx = next
        }
        val settled = finishDeclaredReasoning(target, redactor)
        assertFalse(settled.contains(secret))
        assertTrue(settled.contains("token=***"))
        assertEquals(0, redactor.pendingLength())
    }
}
