// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.feature.chat.hasContextOverflowCode

class ChatMessageProjectionCopyTest {
    @Test
    fun localizedProjectionCopyFormatsVisualToolIncompleteAndPartialCoverageSummaries() {
        val copy = ChatMessageProjectionCopy(
            visualBatchAnalyzed = { "EN_BATCH:$it" },
            toolAttachedImages = { "EN_TOOL_IMAGES:$it" },
            turnIncomplete = { "EN_INCOMPLETE:$it" },
            partialRetrievalCoverage = "EN_PARTIAL_COVERAGE",
        )
        val image = ImagePart("asset-1", "image/png", "sha256")
        val batchMessage = Message(
            id = "m-batch",
            conversationId = "c-1",
            role = MessageRole.USER,
            text = "",
            parts = listOf(image, image),
            metadataJson = """{"visualBatchAnalysis":true}""",
            status = "COMPLETE",
            createdAt = "2026-11-11T10:00:00Z",
        )
        assertEquals("EN_BATCH:2", projectChatMessage(batchMessage, streaming = false, copy = copy).eventSummary)

        val toolEvidence = batchMessage.copy(id = "m-tool", metadataJson = """{"toolEvidence":true}""")
        assertEquals("EN_TOOL_IMAGES:2", projectChatMessage(toolEvidence, streaming = false, copy = copy).eventSummary)

        val incompleteAssistant = Message(
            id = "m-assistant",
            conversationId = "c-1",
            role = MessageRole.ASSISTANT,
            text = "partial",
            parts = listOf(TextPart("partial")),
            metadataJson = """{"retrievalCoverage":{"partial":true}}""",
            status = "STREAMING",
            createdAt = "2026-11-11T10:01:00Z",
        )
        assertEquals(
            "EN_PARTIAL_COVERAGE EN_INCOMPLETE:STREAMING",
            projectChatMessage(incompleteAssistant, streaming = false, copy = copy).eventSummary,
        )
    }

    @Test
    fun persistedContextOverflowSummaryMatchesStableCodeAfterTransientStatusClears() {
        val overflowMessage = Message(
            id = "m-overflow",
            conversationId = "c-1",
            role = MessageRole.ASSISTANT,
            text = "",
            parts = listOf(ErrorPart(MessageErrorCode.CONTEXT_OVERFLOW, "CONTEXT_OVERFLOW · Input budget exceeded")),
            status = "FAILED",
            createdAt = "2026-11-11T10:02:00Z",
        )
        val projected = projectChatMessage(overflowMessage, streaming = false)
        assertTrue(hasContextOverflowCode("Using configuration snapshot", null, projected.eventSummary))
        assertFalse(hasContextOverflowCode("Using configuration snapshot", null, "Unrelated failure"))
    }
}
