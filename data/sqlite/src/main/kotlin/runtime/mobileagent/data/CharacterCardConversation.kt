// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.serialization.CharacterCardSupport

/** Called only for a new live conversation, inside its creation transaction; never on restore. */
internal object CharacterCardConversation {
    fun initialize(db: SqlConnection, snapshot: AgentSnapshot, conversation: Conversation) {
        val manifest = Json.parseToJsonElement(snapshot.bindingManifestJson) as? JsonObject ?: return
        val card = CharacterCardSupport.snapshotFromManifest(manifest) ?: return
        val books = WorldBookRepository(db)
        val existing = books.conversationConfig(conversation.id)
        books.saveConversationConfig(existing.copy(context = card.context), existing.revision)
        card.greeting?.takeIf { it.isNotBlank() }?.let { greeting ->
            ConversationRepository(db).append(conversation.id, MessageRole.ASSISTANT, greeting,
                createdAt = conversation.createdAt, metadataJson = "{\"source\":\"character-card-greeting\"}")
        }
    }
}
