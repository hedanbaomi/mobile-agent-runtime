// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.trpg.TrpgEvent

/**
 * Deterministic digests for checkpoint identity.
 *
 * Services values are length-prefixed so user text, ids and JSON cannot collide through a
 * different field split; the scheme tags keep a future layout change from being silently accepted.
 */
internal object TrpgCheckpointDigest {
    private val eventJson = Json { encodeDefaults = true; explicitNulls = false }
    fun messageFingerprint(message: Message): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "trpg.checkpoint.message.v1")
        update(digest, message.role.name)
        update(digest, message.text)
        update(digest, message.status)
        update(digest, message.createdAt)
        update(digest, messageJson(message.parts))
        update(digest, message.metadataJson)
        return hex(digest.digest())
    }

    fun prefixHash(entries: List<TrpgCheckpointMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "trpg.checkpoint.message.prefix.v1")
        update(digest, entries.size.toString())
        entries.forEach {
            update(digest, it.messageId)
            update(digest, it.contentHash)
        }
        return hex(digest.digest())
    }

    fun journalPrefixHash(events: List<TrpgEvent>): String =
        MessageDigest.getInstance("SHA-256").digest(eventJson.encodeToString(events).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun messageJson(parts: List<MessagePart>): String = eventJson.encodeToString(parts)

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
