// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.Conversation

/** All callbacks publish only into [db]; selection and external I/O happen after this unit. */
class CreateConversationUseCase(private val db: SqlConnection, private val conversations: ConversationRepository) {
    fun create(
        title: String,
        snapshot: () -> AgentSnapshot,
        capturePins: (AgentSnapshot) -> Unit = {},
        bindWorkspace: (Conversation) -> Unit = {},
    ): Conversation = db.transaction {
        val frozen = snapshot()
        capturePins(frozen)
        val conversation = conversations.create(frozen.id, title)
        bindWorkspace(conversation)
        conversation
    }
}
