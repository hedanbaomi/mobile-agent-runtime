// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class KnowledgeRecoveryReviewRegressionTest {
    @Test
    fun stagingRetryRetainsSuccessfulVectorsAndCleansOnlySupersededChunks() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val hashing = HashingTextEmbedder(dimension = 8)
            var calls = 0
            val embedder = object : TextEmbedder by hashing {
                override fun embed(text: String): FloatArray {
                    calls++
                    return hashing.embed(text)
                }
            }
            var failReady = false
            val connection = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    if (failReady && sql.startsWith("UPDATE document_versions SET status") && args.firstOrNull() == "READY") {
                        failReady = false
                        error("synthetic READY publication failure")
                    }
                    db.execute(sql, args)
                }
            }
            val repo = KnowledgeRepository(connection, MemoryBlobSink(), embedder)
            val seed = repo.importBytes("history.txt", "text/plain", "historical cobalt evidence".toByteArray(), false)
            assertEquals(ImportStage.READY, seed.stage, seed.error)
            val citation = repo.retrieve("historical-run", "cobalt", 1).citations.single()
            val historicalVector = db.query("SELECT vector_blob FROM embeddings WHERE chunk_id=?", listOf(citation.chunkId)).single().columns["vector_blob"] as ByteArray
            failReady = true
            val failed = repo.importBytes("retry.txt", "text/plain", "retry blossom evidence".toByteArray(), false, seed.knowledgeBaseId)
            assertEquals(ImportStage.FAILED, failed.stage)
            val staged = db.query("SELECT id FROM document_versions WHERE document_id=? AND status='STAGING'", listOf(failed.documentId)).single().string("id")
            val stagedChunk = db.query("SELECT id FROM chunks WHERE document_version_id=?", listOf(staged)).single().string("id")
            val stagedVector = db.query("SELECT vector_blob FROM embeddings WHERE chunk_id=?", listOf(stagedChunk)).single().columns["vector_blob"] as ByteArray
            // Model an older cited READY version of this very document, as well
            // as the separate seed document. Cleanup must preserve both.
            db.execute("INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) SELECT ?,?,parser_fingerprint,content_hash,'READY',created_at FROM document_versions WHERE id=?",
                listOf("historical-ready", failed.documentId, citation.documentVersionId))
            db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page,text_utf16_length) SELECT ?,?,ordinal,text,content_hash,source_span,asset_ids,page,text_utf16_length FROM chunks WHERE id=?",
                listOf("historical-chunk", "historical-ready", citation.chunkId))
            db.execute("INSERT INTO embeddings(chunk_id,space_id,vector_blob,content_hash) SELECT ?,space_id,vector_blob,content_hash FROM embeddings WHERE chunk_id=?",
                listOf("historical-chunk", citation.chunkId))
            val oldVersionCitation = citation.copy(documentId = failed.documentId,
                documentVersionId = "historical-ready", chunkId = "historical-chunk")
            // A superseded staged chunk tests the explicit cleanup branch;
            // moving the retained chunk tests ordinal reassignment without a
            // UNIQUE collision. Unchanged content must keep its vector.
            val obsoleteHash = sha256Hex("obsolete".toByteArray())
            db.execute("UPDATE chunks SET ordinal=99 WHERE id=?", listOf(stagedChunk))
            db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",
                listOf("obsolete-staged", staged, 0, "obsolete", obsoleteHash, 8))
            db.execute("INSERT INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES(?,?,?,?)",
                listOf("obsolete-staged", hashing.spaceId, stagedVector, obsoleteHash))
            val beforeRetry = calls
            val retried = repo.resumeImport(failed.id, visionConfigured = false)
            assertEquals(ImportStage.READY, retried.stage, retried.error)
            assertEquals(beforeRetry, calls, "successful vectors must not be recomputed")
            assertEquals(stagedChunk, db.query("SELECT id FROM chunks WHERE document_version_id=?", listOf(staged)).single().string("id"))
            assertArrayEquals(stagedVector, db.query("SELECT vector_blob FROM embeddings WHERE chunk_id=?", listOf(stagedChunk)).single().columns["vector_blob"] as ByteArray)
            assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM embeddings e LEFT JOIN chunks c ON c.id=e.chunk_id WHERE c.id IS NULL").single().long("n"))
            assertEquals(3L, db.query("SELECT COUNT(*) AS n FROM embeddings").single().long("n"))
            assertArrayEquals(historicalVector, db.query("SELECT vector_blob FROM embeddings WHERE chunk_id=?", listOf(citation.chunkId)).single().columns["vector_blob"] as ByteArray)
            assertFalse(repo.locateCitation(citation).removed, "old cited evidence must remain available")
            assertArrayEquals(historicalVector, db.query("SELECT vector_blob FROM embeddings WHERE chunk_id='historical-chunk'").single().columns["vector_blob"] as ByteArray)
            assertFalse(repo.locateCitation(oldVersionCitation).removed, "a historical version of the retried document must remain available")
            repo.closeVectorIndexes()
        }
    }

    @Test
    fun retrieveFiltersHeadingsBeforeCutoffAndRefillsFromBoundedCandidates() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val seedRepo = KnowledgeRepository(db, blobs)
            val seed = seedRepo.importBytes("seed.txt", "text/plain", "seed evidence".toByteArray(), false)
            assertEquals(ImportStage.READY, seed.stage, seed.error)
            fun candidate(id: String, document: String, text: String, span: String? = null) = SqlRow(mapOf(
                "chunk_id" to id, "document_id" to document, "text" to text, "version_id" to "fixture-version",
                "source_span" to span,
            ))
            val candidates = listOf(
                candidate("heading", "same-document", "# Cobalt", "heading"),
                candidate("body", "same-document", "Cobalt has detailed claim-supporting evidence. ".repeat(3)),
                candidate("refill", "another-document", "Additional independent evidence. ".repeat(3)),
            )
            // Fix the two channel rankings to isolate the repository's final
            // candidate filtering order from BM25 and native ANN scoring.
            val connection = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> = when {
                    sql.contains("chunks_fts MATCH") -> {
                        assertEquals(40, args.last(), "lexical candidate pool remains bounded")
                        candidates
                    }
                    sql.contains("SELECT chunks.id AS chunk_id") && sql.contains("FROM generation_members") -> emptyList()
                    else -> db.query(sql, args)
                }
            }
            val repo = KnowledgeRepository(connection, blobs)
            assertEquals(listOf("body"), repo.retrieve("cutoff", "cobalt", 1).hits.map { it.chunkId })
            val refill = repo.retrieve("refill", "cobalt", 2)
            assertEquals(listOf("body", "refill"), refill.hits.map { it.chunkId })
            assertEquals(2, refill.citations.size)
            assertEquals(2, repo.retrieve("maximum", "cobalt", 10).hits.size)
            repo.closeVectorIndexes()
            seedRepo.closeVectorIndexes()
        }
    }
}
