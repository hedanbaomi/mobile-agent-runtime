// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.CjkLexical
import runtime.mobileagent.knowledge.SearchHit
import runtime.mobileagent.knowledge.StaleVectorBuildException
import runtime.mobileagent.knowledge.VectorIndexCache

/** Bounded retrieval projections; consent, paid query receipts and generation pins remain with the repository. */
internal class KnowledgeRetrievalStore(private val db: SqlConnection, private val invalidate: (String) -> Unit) {
    private data class Members(val revision: Long, val ids: Set<String>)
    private val lock = Any()
    private val snapshots = LinkedHashMap<VectorIndexCache.Key, Members>(4, 0.75f, true)

    fun memberIds(key: VectorIndexCache.Key): Set<String> {
        repeat(2) {
            val revision = EmbeddingInputRevision.current(db, key.knowledgeBaseId)
            val previous = synchronized(lock) { snapshots[key] }
            if (previous?.revision == revision) return previous.ids
            if (previous != null) invalidate(key.knowledgeBaseId)
            val ids = db.query("""
                SELECT chunks.id AS chunk_id
                FROM generation_members
                JOIN chunks ON chunks.id = generation_members.chunk_id
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE generation_members.generation_id = ? AND documents.kb_id = ? AND documents.deleted_at IS NULL
            """.trimIndent(), listOf(key.generationId, key.knowledgeBaseId)).mapTo(linkedSetOf()) { row -> row.string("chunk_id") }
            if (EmbeddingInputRevision.current(db, key.knowledgeBaseId) != revision) return@repeat
            synchronized(lock) {
                snapshots[key] = Members(revision, ids)
                while (snapshots.size > 4) snapshots.remove(snapshots.keys.first())
            }
            return ids
        }
        throw StaleVectorBuildException(key.knowledgeBaseId)
    }

    fun lexicalHits(kbId: String, query: String, topK: Int, generation: String): List<SearchHit> {
        val tokenized = CjkLexical.indexText(query)
        val fts = runCatching {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id,
                       chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
                FROM chunks_fts
                JOIN chunks ON chunks.rowid = chunks_fts.rowid
                JOIN generation_members ON generation_members.chunk_id = chunks.id AND generation_members.generation_id = ?
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE documents.kb_id = ? AND documents.deleted_at IS NULL AND chunks_fts MATCH ?
                ORDER BY bm25(chunks_fts) ASC, chunks.id ASC
                LIMIT ?
                """.trimIndent(),
                listOf(generation, kbId, quoteFts(tokenized.ifBlank { query }), topK),
            )
        }.getOrDefault(emptyList())
        val rows = fts.ifEmpty {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id,
                       chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
                FROM chunks
                JOIN generation_members ON generation_members.chunk_id = chunks.id AND generation_members.generation_id = ?
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE documents.kb_id = ? AND documents.deleted_at IS NULL AND chunks.text LIKE ?
                ORDER BY chunks.id ASC
                LIMIT ?
                """.trimIndent(),
                listOf(generation, kbId, "%$query%", topK),
            )
        }
        return rows.mapIndexed { index, row ->
            SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 1.0 / (index + 1),
                knowledgeBaseId = kbId,
                documentVersionId = row.string("version_id"),
                assetId = sourceAssetForHit(row),
                page = row.string("page").toIntOrNull(),
                sourceSpan = row.string("source_span").ifBlank { null },
            )
        }
    }

    companion object {
        fun isPageContextSpan(span: String): Boolean = runCatching {
            runtime.mobileagent.knowledge.decodeSourceSpan(span)?.isPageContext ?: span.startsWith("v2|")
        }.getOrDefault(true)

        fun sourceAssetForHit(row: SqlRow): String? =
            if (isPageContextSpan(row.string("source_span"))) null
            else row.string("asset_ids").split(',').firstOrNull { it.isNotBlank() }

        private fun quoteFts(query: String): String {
            val cleaned = query.replace("\"", " ").trim()
            if (cleaned.isEmpty()) return "\"\""
            // Every token is a literal, including single words such as OR or foo-bar.
            return cleaned.split(Regex("""\s+""")).joinToString(" OR ") { "\"$it\"" }
        }
    }
}
