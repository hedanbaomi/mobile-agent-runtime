// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.trpg.TrpgEvent
import runtime.mobileagent.domain.trpg.TrpgJournal
import runtime.mobileagent.domain.trpg.TrpgJournalValidation

/**
 * The journal lives once in the conversation entry. A checkpoint stores only its bounded
 * boundary, so branch/restore re-read the live append-only journal and verify the prefix digest
 * instead of trusting (or duplicating) a second copy.
 */
internal class TrpgCheckpointJournalSupport(private val trpg: TrpgRepository) {
    fun prefix(conversationId: String, revision: Long): List<TrpgEvent> {
        val events = trpg.exportJournal(conversationId).events.filter { it.revision <= revision }
        if (events.isEmpty() || events.last().revision != revision) {
            TrpgCheckpointGuard.fail("TRPG checkpoint journal boundary is missing")
        }
        return events
    }

    fun requireBoundary(conversationId: String, payload: TrpgCheckpointPayload): List<TrpgEvent> {
        val events = prefix(conversationId, payload.revision)
        val boundary = payload.journal
        if (events.size != boundary.eventCount || events.last().id != boundary.lastEventId ||
            TrpgCheckpointDigest.journalPrefixHash(events) != boundary.prefixHash
        ) {
            TrpgCheckpointGuard.fail("TRPG checkpoint journal boundary changed")
        }
        validate(TrpgJournal(events, undo = null), payload.revision)
        return events
    }

    private fun validate(journal: TrpgJournal, revision: Long) {
        try {
            TrpgJournalValidation.validate(journal, revision)
        } catch (error: IllegalArgumentException) {
            TrpgCheckpointGuard.fail(error.message ?: "Invalid TRPG checkpoint journal")
        }
    }
}
