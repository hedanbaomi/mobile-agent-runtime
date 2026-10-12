// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/** Exercise the public decoder with malformed, portable conversation image manifests. */
class ChatImageTransferCodecTest {
    private val hash = "0".repeat(64)
    private fun transfer() = ConversationTransfer(
        conversation = Conversation("conversation", "snapshot", "Synthetic", "now", "now"),
        snapshot = AgentSnapshot("snapshot", SchemaVersion.CURRENT, "agent", "prompt", "model", 1,
            emptyList(), emptyList(), "now", providerId = "provider"),
        messages = listOf(Message("message", "conversation", role = MessageRole.USER, status = "COMPLETE",
            createdAt = "now", parts = listOf(ImagePart("chat-upload:one", "image/png", hash)))),
        contentIncluded = true,
        uploadedImages = listOf(BlobTransfer(hash, 100, "image/png")),
    )

    @Test fun explicitImageManifestRoundTripsAndLegacyEmptyFieldRemainsAbsent() {
        val input = transfer()
        assertEquals(input, TransferCodec.decodeConversation(TransferCodec.encodeConversation(input)))
        val legacy = input.copy(messages = emptyList(), uploadedImages = emptyList(), contentIncluded = false)
        val encoded = TransferCodec.encodeConversation(legacy)
        assertFalse(encoded.contains("uploadedImages"))
        assertEquals(legacy, TransferCodec.decodeConversation(encoded))
    }

    @Test fun decoderRejectsDuplicateOversizeUnsupportedAndUnlistedImageMetadata() {
        val raw = TransferCodec.encodeConversation(transfer())
        val entry = "{\"hash\":\"$hash\",\"byteLength\":100,\"mediaType\":\"image/png\",\"relativePath\":\"\"}"
        assertTrue(raw.contains(entry), "mutations must target the actual serialized manifest")
        listOf(
            raw.replace("[$entry]", "[$entry,$entry]"),
            raw.replace("\"byteLength\":100", "\"byteLength\":2097153"),
            raw.replace("\"byteLength\":100", "\"byteLength\":0"),
            raw.replace("image/png", "image/heic"),
            raw.replace("\"relativePath\":\"\"", "\"relativePath\":\"device.png\""),
            raw.replace("\"contentIncluded\":true", "\"contentIncluded\":false"),
            raw.replace("[$entry]", "[]"),
            raw.replace(entry, entry.replace(hash, "1".repeat(64))),
            raw.replace(entry, entry.replace("image/png", "image/jpeg")),
        ).forEach { malformed ->
            assertNotEquals(raw, malformed)
            val failure = assertThrows(AppException::class.java) { TransferCodec.decodeConversation(malformed) }
            assertEquals(ErrorCode.TRANSFER_INVALID, failure.error.code)
        }
    }
}
