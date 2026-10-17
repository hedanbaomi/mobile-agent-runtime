// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** Bounded membership SQL and canonical lexical publication for an already locked transaction. */
internal class KnowledgeGenerationMemberStore(private val db: SqlConnection, private val lexical: KnowledgeLexicalIndexStore) {
    data class Input(val id: String, val text: String, val contentHash: String)

    fun append(
        generation: String,
        version: String,
        batch: List<Input>,
        space: String,
        repairLexical: Boolean,
        onMissingEmbedding: (String) -> Nothing,
        validateVector: (SqlRow) -> Unit,
    ): Int {
        require(batch.size in 1..128)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Generation publication interrupted")
        val storedById = db.query(
            "SELECT c.id AS chunk_id, c.rowid AS rid, e.chunk_id AS embedding_chunk_id, e.content_hash, e.vector_blob " +
                "FROM chunks c LEFT JOIN embeddings e ON e.chunk_id=c.id AND e.space_id=? " +
                "WHERE c.document_version_id=? AND c.id IN (${batch.joinToString(",") { "?" }})",
            listOf(space, version) + batch.map { it.id },
        ).associateBy { it.string("chunk_id") }
        batch.forEach { chunk ->
            val stored = checkNotNull(storedById[chunk.id]) { "Chunk source disappeared while deriving a generation" }
            if (stored.string("embedding_chunk_id").isBlank()) onMissingEmbedding(chunk.id)
            check(stored.string("content_hash") == chunk.contentHash) { "embedding content hash changed for chunk ${chunk.id}" }
            validateVector(stored)
        }
        val lexicalRows = batch.map { KnowledgeLexicalIndexStore.Source(storedById.getValue(it.id).long("rid"), it.text) }
        if (repairLexical) lexical.replace(lexicalRows) else lexical.insertMissing(lexicalRows)
        db.execute(
            "INSERT OR IGNORE INTO generation_members(generation_id,chunk_id,space_id,document_version_id) VALUES " +
                batch.joinToString(",") { "(?,?,?,?)" },
            batch.flatMap { listOf(generation, it.id, space, version) },
        )
        return batch.size
    }
}
