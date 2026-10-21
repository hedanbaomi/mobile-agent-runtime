// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferOptions
import runtime.mobileagent.serialization.WorldBookConversationTransfer
import runtime.mobileagent.serialization.WorldBookTransfer
import runtime.mobileagent.serialization.worldbook.WorldBookCodec

internal class WorldBookTransferSupport(private val db: SqlConnection, clock: () -> String) {
    private val books = WorldBookRepository(db, clock)
    fun matchesEnvelope(full: runtime.mobileagent.serialization.ConversationTransfer, manifest: runtime.mobileagent.serialization.ConversationTransfer): Boolean =
        full.contentEntry == manifest.contentEntry && full.worldBooks == manifest.worldBooks

    fun export(agentId: String, options: TransferOptions): List<WorldBookTransfer> {
        if (!options.includeWorldBookContent) return emptyList()
        val raw = db.query("SELECT world_book_ids FROM agent_profiles WHERE id=?", listOf(agentId)).single().string("world_book_ids")
        val ids = Json.decodeFromString<List<String>>(raw).toMutableSet()
        if (options.includeConversations) {
            db.query("SELECT c.id,s.world_book_ids FROM conversations c JOIN agent_snapshots s ON s.id=c.snapshot_id WHERE s.agent_id=?", listOf(agentId))
                .forEach {
                    ids += Json.decodeFromString<List<String>>(it.string("world_book_ids"))
                    ids += books.conversationConfig(it.string("id")).bookIds
                    db.query("SELECT payload_json FROM trpg_checkpoints WHERE conversation_id=?", listOf(it.string("id")))
                        .forEach { row ->
                            val historicalIds = TrpgCheckpointPayloadCodec().decode(row.string("payload_json")).worldBooks.bookIds
                            require(historicalIds.all { id -> books.get(id) != null }) {
                                "A historical checkpoint world book is unavailable; the full backup cannot preserve it"
                            }
                            ids += historicalIds
                        }
                }
        }
        return ids.mapNotNull { id -> books.get(id)?.let { WorldBookTransfer(id, WorldBookCodec.encodeNative(it.document), it.summary.enabled) } }
    }

    fun preflight(incoming: List<WorldBookTransfer>, policy: TransferConflictPolicy) {
        incoming.forEach { transfer ->
            val document = WorldBookCodec.decode(transfer.documentJson).document
            val existing = books.get(transfer.id)
            require(existing == null || policy == TransferConflictPolicy.KEEP_EXISTING || existing.document == document) {
                "World book conflicts with local content"
            }
        }
    }

    fun import(incoming: List<WorldBookTransfer>, policy: TransferConflictPolicy, warnings: MutableList<String>) {
        preflight(incoming, policy)
        incoming.forEach { transfer ->
            val existing = books.get(transfer.id)
            if (existing == null) books.save(WorldBookCodec.decode(transfer.documentJson).document, transfer.id, enabled = transfer.enabled)
            else if (policy == TransferConflictPolicy.KEEP_EXISTING) warnings += "Existing world book kept; imported content was not applied"
        }
    }

    fun available(ids: List<String>, warnings: MutableList<String>): List<String> = ids.filter { id ->
        val available = books.get(id) != null
        if (!available) warnings += "A world book binding was not restored because its content was not included or is unavailable"
        available
    }

    fun exportConversation(conversationId: String, includeContent: Boolean): WorldBookConversationTransfer {
        val config = books.conversationConfig(conversationId)
        return WorldBookConversationTransfer(if (includeContent) config else config.copy(context = runtime.mobileagent.domain.worldbook.WorldBookContext()))
    }

    fun restoreConversation(binding: WorldBookConversationTransfer?, conversationId: String, warnings: MutableList<String>) {
        books.initializeConversation(conversationId)
        if (binding == null) return
        require(binding.config.conversationId == conversationId) { "World book conversation mismatch" }
        val current = books.conversationConfig(conversationId)
        books.restoreConversationConfig(binding.config.copy(bookIds = available(binding.config.bookIds, warnings)), current.revision)
        warnings += "World book bindings restored; timed effects and random activation state will be recomputed locally"
    }
}
