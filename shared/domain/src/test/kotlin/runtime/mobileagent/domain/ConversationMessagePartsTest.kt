// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConversationMessagePartsTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    @Test
    fun newPartsRoundTripWithTypedDiscriminator() {
        val parts: List<MessagePart> = listOf(
            TextPart("answer"),
            ReasoningPart("provider returned reasoning", streaming = true),
            DiffPart(
                summary = "Updated two files",
                patchPreview = "diff --git a/src/Main.kt b/src/Main.kt\n+added line",
                changedFiles = 2,
            ),
            ErrorPart(MessageErrorCode.NETWORK_UNAVAILABLE, "The provider is unavailable", retryable = true),
            CitationPart("citation.one"),
        )

        val decoded = json.decodeFromString<List<MessagePart>>(json.encodeToString(parts))

        assertEquals(parts, decoded)
        assertEquals("provider returned reasoning", (decoded[1] as ReasoningPart).value)
        assertEquals("diff --git a/src/Main.kt b/src/Main.kt\n+added line", (decoded[2] as DiffPart).patch)
    }

    @Test
    fun legacyTextPartJsonRemainsReadable() {
        val decoded = json.decodeFromString<MessagePart>("""{"type":"text","value":"legacy"}""")

        assertEquals(TextPart("legacy"), decoded)
    }

    @Test
    fun boundedPartsRejectBlankOrAbsoluteContent() {
        assertThrows(IllegalArgumentException::class.java) { ReasoningPart(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            DiffPart("changed", patchPreview = "/private/project/Main.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ErrorPart(MessageErrorCode.INTERNAL, "x".repeat(MessagePartLimits.MAX_ERROR_MESSAGE_CHARS + 1))
        }
    }
}
