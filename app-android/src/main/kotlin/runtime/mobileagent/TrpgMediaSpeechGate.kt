// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * One in-flight recognition request at a time.
 *
 * A late result is only accepted for the exact conversation and token that
 * launched it, so switching conversations cannot paste another conversation's
 * words into the composer. Cancelled or empty results never become draft text.
 */
class TrpgMediaSpeechGate {
    private data class Pending(val token: Long, val conversationId: String)

    private var pending: Pending? = null
    private var sequence = 0L

    val pendingConversationId: String?
        get() = pending?.conversationId

    fun begin(conversationId: String): Long? {
        if (!TrpgMediaPolicy.conversationIdIsValid(conversationId)) return null
        if (pending != null) return null
        sequence += 1
        pending = Pending(sequence, conversationId)
        return sequence
    }

    fun cancel() {
        pending = null
    }

    fun cancel(conversationId: String) {
        if (pending?.conversationId == conversationId) pending = null
    }

    fun accept(conversationId: String, token: Long, candidates: List<String>?): String? {
        val active = pending ?: return null
        if (active.token != token || active.conversationId != conversationId) return null
        pending = null
        val text = candidates?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.take(TrpgMediaPolicy.MAX_SPEECH_DRAFT_CHARS)
    }
}
