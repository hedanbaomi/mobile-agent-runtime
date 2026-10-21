// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.Message

/** Re-verifies that a checkpoint prefix is still the durable head of the conversation. */
internal class TrpgCheckpointPrefixSupport(private val conversations: ConversationRepository) {
    fun requirePrefix(conversationId: String, payload: TrpgCheckpointPayload): List<Message> {
        val messages = conversations.messages(conversationId)
        if (payload.messages.size > messages.size) TrpgCheckpointGuard.fail("TRPG checkpoint message prefix is longer than the conversation")
        payload.messages.forEachIndexed { index, entry ->
            val actual = messages[index]
            if (actual.id != entry.messageId) TrpgCheckpointGuard.fail("TRPG checkpoint message prefix no longer matches the conversation")
            if (TrpgCheckpointDigest.messageFingerprint(actual) != entry.contentHash) {
                TrpgCheckpointGuard.fail("TRPG checkpoint message content changed: ${entry.messageId}")
            }
        }
        if (TrpgCheckpointDigest.prefixHash(payload.messages) != payload.prefixHash) {
            TrpgCheckpointGuard.fail("TRPG checkpoint message prefix digest is corrupt")
        }
        return messages.take(payload.messages.size)
    }
}
