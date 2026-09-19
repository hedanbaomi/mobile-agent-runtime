// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink

class KnowledgeIndexBoundedReadTest {
    @Test fun vectorBlobsAreReadInBoundedBatchesAndWarmQueriesReadNone() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val embedder = HashingTextEmbedder(dimension = 8)
            val imported = KnowledgeRepository(db, blobs, embedder).importBytes(
                "large.txt", "text/plain", "bounded evidence ".repeat(65_000).toByteArray(), false,
            )
            assertEquals(ImportStage.READY, imported.stage)
            val count = db.query("SELECT COUNT(*) AS n FROM embeddings").single().long("n").toInt()
            assertTrue(count > 512, "fixture must cross a complete batch")
            val pages = mutableListOf<Int>()
            val seen = mutableSetOf<String>()
            val observed = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> = db.query(sql, args).also { rows ->
                    if (sql.contains("FROM generation_members") && sql.contains("chunks.text")) {
                        assertTrue(sql.contains("chunks.id IN"), "body reads must be limited to native matches")
                        assertTrue(rows.size <= 40, "never materialize all chunk bodies for vector retrieval")
                    }
                    if (sql.contains("embeddings.vector_blob") && sql.contains("FROM generation_members")) {
                        pages += rows.size
                        seen += rows.map { it.string("chunk_id") }
                    }
                }
            }
            val repository = KnowledgeRepository(observed, blobs, embedder)
            repository.search("bounded")
            assertTrue(pages.size >= 4, "both validation and construction must walk multiple batches")
            assertTrue(pages.all { it <= 512 }, "no query may retain the full vector set")
            assertEquals(count, seen.size)
            assertEquals(1L, repository.vectorIndexStats().builds)
            val reads = pages.size
            repository.search("evidence")
            assertEquals(reads, pages.size, "warm index queries must not reread vector blobs")
            repository.closeVectorIndexes()

            // An invalid row beyond the first batch must prevent publication.
            val last = db.query("SELECT chunk_id FROM embeddings ORDER BY chunk_id DESC LIMIT 1").single().string("chunk_id")
            db.execute("UPDATE embeddings SET vector_blob=? WHERE chunk_id=?", listOf(byteArrayOf(0), last))
            val invalid = KnowledgeRepository(observed, blobs, embedder)
            assertThrows(IllegalStateException::class.java) { invalid.search("bounded") }
            assertEquals(0L, invalid.vectorIndexStats().builds)
            assertTrue(invalid.readDocumentText(imported.documentId, 100).contains("bounded"))
        }
    }
}
