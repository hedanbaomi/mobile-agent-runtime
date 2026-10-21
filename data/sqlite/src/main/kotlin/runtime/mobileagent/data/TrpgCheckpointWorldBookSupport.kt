// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.worldbook.WorldBookConversationConfig

/** Restores the checkpoint-time world-book binding; never substitutes the current default. */
internal class TrpgCheckpointWorldBookSupport(private val db: SqlConnection, private val clock: () -> String) {
    fun restore(conversationId: String, saved: WorldBookConversationConfig) {
        val books = WorldBookRepository(db, clock)
        saved.bookIds.forEach { id ->
            if (books.get(id) == null) TrpgCheckpointGuard.fail("TRPG checkpoint world book $id is not available on this device")
        }
        val current = books.conversationConfig(conversationId)
        books.restoreConversationConfig(saved.copy(conversationId = conversationId), current.revision)
    }
}
