// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.worldbook.WorldBookConversationConfig

/** One ordered transcript entry in the checkpoint payload; never the message full text. */
@Serializable
internal data class TrpgCheckpointMessage(
    val messageId: String,
    val createdAt: String,
    val contentHash: String,
)

/**
 * The only journal data a checkpoint keeps: its bounded prefix boundary. The append-only journal
 * itself is stored once per conversation and verified against this digest on branch/restore.
 */
@Serializable
internal data class TrpgCheckpointJournalBoundary(
    val eventCount: Int,
    val lastEventId: String,
    val prefixHash: String,
)

/**
 * Internal checkpoint payload. It contains only historical data: replaying or executing any part
 * of it is never an allowed operation.
 */
@Serializable
internal data class TrpgCheckpointPayload(
    val version: Int = 1,
    val revision: Long,
    val conversationId: String,
    val state: TrpgState,
    val messages: List<TrpgCheckpointMessage>,
    val prefixHash: String,
    val worldBooks: WorldBookConversationConfig,
    val summaries: List<ContextCompactionRecord> = emptyList(),
    val journal: TrpgCheckpointJournalBoundary,
)

/** Strict codec for the durable payload text; malformed or forged text fails closed. */
internal class TrpgCheckpointPayloadCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }

    fun encode(payload: TrpgCheckpointPayload): String = json.encodeToString(payload)

    fun encodeIds(ids: List<String>): String = json.encodeToString(ids)

    fun decode(payloadJson: String): TrpgCheckpointPayload {
        if (payloadJson.isBlank() || payloadJson.toByteArray(Charsets.UTF_8).size > TrpgCheckpointGuard.MAX_PAYLOAD_BYTES) {
            TrpgCheckpointGuard.fail("TRPG checkpoint payload has an invalid size")
        }
        val payload = try {
            json.decodeFromString<TrpgCheckpointPayload>(payloadJson)
        } catch (error: Exception) {
            TrpgCheckpointGuard.fail("TRPG checkpoint payload is corrupt")
        }
        if (payload.version != TrpgCheckpointGuard.PAYLOAD_VERSION) TrpgCheckpointGuard.fail("Unsupported TRPG checkpoint payload version")
        if (payload.revision < 0) TrpgCheckpointGuard.fail("TRPG checkpoint revision is invalid")
        if (payload.messages.map { it.messageId }.distinct().size != payload.messages.size) {
            TrpgCheckpointGuard.fail("TRPG checkpoint repeats a message identity")
        }
        payload.messages.forEach { message ->
            if (message.messageId.isBlank() || !TrpgCheckpointGuard.HEX64.matches(message.contentHash)) {
                TrpgCheckpointGuard.fail("TRPG checkpoint message digest is invalid")
            }
        }
        if (payload.journal.eventCount < 1 || payload.journal.lastEventId.isBlank() ||
            payload.journal.lastEventId.length > 256 || !TrpgCheckpointGuard.HEX64.matches(payload.journal.prefixHash)
        ) {
            TrpgCheckpointGuard.fail("TRPG checkpoint journal boundary is invalid")
        }
        return payload
    }

    /** Cheap revision read for listings; the payload's leading fields are deterministic. */
    fun quickRevision(payloadJson: String): Long? =
        TrpgCheckpointGuard.REVISION_PREFIX.find(payloadJson)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }
}
