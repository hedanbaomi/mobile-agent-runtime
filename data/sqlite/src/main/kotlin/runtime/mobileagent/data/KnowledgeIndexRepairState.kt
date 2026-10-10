// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** Persisted scan checkpoints, invalidated by the same transactional source revisions as retrieval. */
internal class KnowledgeIndexRepairState(private val db: SqlConnection) {
    fun candidates(localSpace: String, onlyChanged: Boolean): Set<String> = db.query("""
        SELECT k.id FROM knowledge_bases k
        JOIN embedding_input_revisions r ON r.kb_id=k.id
        LEFT JOIN app_prefs p ON p.key='knowledge_index_repair:' || k.id
        LEFT JOIN index_generations g ON g.id=k.active_generation_id
        WHERE k.deleted_at IS NULL AND (?=0 OR COALESCE(p.value,'') <> ? || ':' || r.revision
            OR (k.active_generation_id IS NOT NULL AND (g.id IS NULL OR g.state<>'READY' OR g.space_id IS NOT k.embedding_space_id))
            OR (k.embedding_space_id<>? AND EXISTS(SELECT 1 FROM generation_members m JOIN chunks c ON c.id=m.chunk_id
                LEFT JOIN embeddings e ON e.chunk_id=m.chunk_id AND e.space_id=m.space_id
                WHERE m.generation_id=k.active_generation_id AND
                    (e.chunk_id IS NULL OR e.vector_blob IS NULL OR e.content_hash IS NOT c.content_hash)))
            OR EXISTS(SELECT 1 FROM embedding_operations o WHERE o.kb_id=k.id AND
                o.state IN('PREPARED','DISPATCHED','CACHE_READY','UNKNOWN')))
    """.trimIndent(), listOf(if (onlyChanged) 1 else 0, version(localSpace), localSpace)).mapTo(linkedSetOf()) { it.string("id") }

    fun completed(kbId: String, localSpace: String, checkedRevision: Long) = db.transaction {
        // A source change after the scan must remain a candidate on the next startup.
        if (EmbeddingInputRevision.current(db, kbId) != checkedRevision) return@transaction
        db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            listOf("knowledge_index_repair:$kbId", "${version(localSpace)}:$checkedRevision"))
    }

    private fun version(localSpace: String): String = "repair-v1:$localSpace"
}
