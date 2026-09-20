// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

/** Real repository/SQLite, deterministic local API stand-in. No network or paid calls. */
class EmbeddingLinearDispatchTest {
    private fun binding() = ApiEmbeddingBinding(
        providerId = "kr05-fixture", endpoint = "https://example.invalid/embeddings", providerRevision = 1,
        modelId = "fixture", modelRevision = 1, dimension = 8, dataScope = "synthetic test text",
    )

    private class Meter(private val delegate: SqlConnection) : SqlConnection by delegate {
        var enabled = false
        var bodyRows = 0L
        var allRows = 0L
        var maxBodyBatch = 0
        override fun query(sql: String, args: List<Any?>): List<SqlRow> = delegate.query(sql, args).also { rows ->
            if (enabled) {
                allRows += rows.size
                val bodies = rows.count { it.columns.containsKey("text") }
                bodyRows += bodies
                maxBodyBatch = maxOf(maxBodyBatch, bodies)
            }
        }
    }

    @Test fun apiRebindReadsLinearTotalInputAt1k10kAnd50k() {
        for (count in listOf(1_000, 10_000, 50_000)) JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val meter = Meter(db)
            val target = binding()
            var calls = 0
            var sent = 0
            var previousBodyRows = 0L
            var maxBetweenCalls = 0L
            val api = object : TextEmbedder, BatchTextEmbedder {
                override val spaceId = target.spaceId
                override val dimension = 8
                override fun embed(text: String): FloatArray = error("Batch API is required")
                override fun embedBatch(texts: List<String>): List<FloatArray> {
                    assertTrue(texts.size in 1..128)
                    if (calls > 0) {
                        val added = meter.bodyRows - previousBodyRows
                        maxBetweenCalls = maxOf(maxBetweenCalls, added)
                        assertTrue(added <= 128, "a later batch must not rescan the operation: $added/$count")
                    }
                    previousBodyRows = meter.bodyRows
                    calls++
                    sent += texts.size
                    return texts.map { floatArrayOf(1f,0f,0f,0f,0f,0f,0f,0f) }
                }
            }
            val repo = KnowledgeRepository(meter, MemoryBlobSink(), HashingTextEmbedder(dimension = 8), apiEmbedder = api)
            val seed = repo.importBytes("seed.txt", "text/plain", "one seed".toByteArray(), false)
            assertEquals(ImportStage.READY, seed.stage)
            val version = db.query("SELECT active_version_id FROM documents WHERE id=?", listOf(seed.documentId)).single().string("active_version_id")
            // Index-input fixture, not a document-import or provider-latency benchmark.
            // The operation under test is the public rebind API and its real finalize.
            db.transaction {
                for (i in 1 until count) {
                    val text = "synthetic block $i " + "evidence ".repeat(20)
                    db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",
                        listOf("kr05-%08d".format(i),version,i,text,sha256Hex(text.toByteArray()),text.length))
                }
            }
            meter.enabled = true
            repo.rebindApiKnowledgeBase(seed.knowledgeBaseId, target, true)
            assertEquals(count, sent)
            assertEquals((count + 127) / 128, calls)
            assertTrue(meter.maxBodyBatch <= 128)
            assertTrue(meter.bodyRows <= 10L * count, "total body work must be linear: ${meter.bodyRows}/$count")
            assertTrue(meter.allRows <= 30L * count, "metadata work must not substitute another quadratic scan")
            val generation = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id=?", listOf(seed.knowledgeBaseId)).single().string("active_generation_id")
            assertEquals(count.toLong(), db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(generation)).single().long("n"))
            val bodyWork = meter.bodyRows
            val rowWork = meter.allRows
            val before = calls
            repo.rebuildIndex(seed.knowledgeBaseId)
            assertEquals(before, calls, "a fully cached rebuild must not call the provider")
            println("KR05_SCALE members=$count batches=$calls maxBetweenCalls=$maxBetweenCalls maxBodyBatch=${meter.maxBodyBatch} totalBodyRows=$bodyWork totalRows=$rowWork")
            repo.closeVectorIndexes()
        }
    }

    @Test fun everyBatchStillRejectsMutationsRevocationAndCancellation() {
        val cases = listOf("chunk-text", "chunk-insert", "chunk-delete", "version", "pointer", "deleted", "binding", "consent", "cancel")
        for (case in cases) JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val target = binding()
            var calls = 0
            val api = object : TextEmbedder, BatchTextEmbedder {
                override val spaceId = target.spaceId
                override val dimension = 8
                override fun embed(text: String) = FloatArray(8) { if (it == 0) 1f else 0f }
                override fun embedBatch(texts: List<String>): List<FloatArray> {
                    calls++
                    if (calls == 1) {
                        val op = db.query("SELECT * FROM embedding_operations WHERE state='DISPATCHED'").single()
                        val job = op.string("job_id")
                        val doc = op.string("document_id")
                        val version = op.string("document_version_id")
                        val kb = op.string("kb_id")
                        val last = db.query("SELECT id FROM chunks WHERE document_version_id=? ORDER BY id DESC LIMIT 1", listOf(version)).single().string("id")
                        when (case) {
                            // Even a same-hash edit or an ABA edit must invalidate validation.
                            "chunk-text" -> db.execute("UPDATE chunks SET text=text || ' changed' WHERE id=?", listOf(last))
                            "chunk-insert" -> db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",
                                listOf("later-input",version,100000,"added",sha256Hex("added".toByteArray()),5))
                            "chunk-delete" -> db.execute("DELETE FROM chunks WHERE id=?", listOf(last))
                            "version" -> db.execute("UPDATE document_versions SET content_hash='changed' WHERE id=?", listOf(version))
                            "pointer" -> db.execute("UPDATE documents SET active_version_id=? WHERE id=?", listOf(version,doc))
                            "deleted" -> db.execute("UPDATE documents SET deleted_at='revoked' WHERE id=?", listOf(doc))
                            "binding" -> db.execute("UPDATE knowledge_bases SET embedding_space_id='changed' WHERE id=?", listOf(kb))
                            "consent" -> db.execute("UPDATE import_jobs SET embedding_consent=0 WHERE id=?", listOf(job))
                            "cancel" -> db.execute("UPDATE embedding_operations SET cancel_requested=1,state='UNKNOWN' WHERE token=?", listOf(op.string("token")))
                        }
                    }
                    return texts.map(::embed)
                }
            }
            val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
            val kb = repo.createApiKnowledgeBase("KR05 $case", target)
            val text = (0 until 350).joinToString("\n") { "unique-$it " + "段落x".repeat(590) }
            val job = repo.importBytes("source.txt", "text/plain", text.toByteArray(), false, kb, embeddingIsApi=true, embeddingConsent=true)
            assertEquals(1, calls, "$case must stop before the second provider batch")
            assertNotEquals(ImportStage.READY, job.stage, case)
            assertEquals(128L, db.query("SELECT COUNT(*) AS n FROM embeddings WHERE space_id=?", listOf(target.spaceId)).single().long("n"), "$case must retain the completed batch")
            assertEquals("UNKNOWN", db.query("SELECT state FROM embedding_operations ORDER BY created_at DESC LIMIT 1").single().string("state"))
            repo.closeVectorIndexes()
        }
    }

    @Test fun revisionsAreTransactionalCrossConnectionAndIgnoreCacheOrOtherKnowledgeBases() {
        val file = java.io.File.createTempFile("kr05-revisions", ".sqlite")
        try {
            JdbcSqlConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                Migrations.apply(db)
                val repo = KnowledgeRepository(db, MemoryBlobSink())
                val first = repo.importBytes("a.txt", "text/plain", "first".toByteArray(), false)
                val otherKb = repo.createKnowledgeBase("other")
                val other = repo.importBytes("b.txt", "text/plain", "second".toByteArray(), false, otherKb)
                val chunk = db.query("SELECT c.id FROM chunks c JOIN documents d ON d.active_version_id=c.document_version_id WHERE d.id=?", listOf(first.documentId)).single().string("id")
                val start = EmbeddingInputRevision.current(db, first.knowledgeBaseId)
                assertThrows(IllegalStateException::class.java) {
                    db.transaction {
                        db.execute("UPDATE chunks SET text='rolled back' WHERE id=?", listOf(chunk))
                        assertTrue(EmbeddingInputRevision.current(db, first.knowledgeBaseId) > start)
                        error("rollback")
                    }
                }
                assertEquals(start, EmbeddingInputRevision.current(db, first.knowledgeBaseId))
                db.execute("UPDATE embeddings SET vector_blob=vector_blob WHERE chunk_id=?", listOf(chunk))
                db.execute("UPDATE documents SET display_name='renamed' WHERE id=?", listOf(first.documentId))
                db.execute("UPDATE chunks SET text=text WHERE id=?", listOf(chunk))
                db.execute("UPDATE documents SET deleted_at='gone' WHERE id=?", listOf(other.documentId))
                assertEquals(start, EmbeddingInputRevision.current(db, first.knowledgeBaseId))
                JdbcSqlConnection("jdbc:sqlite:${file.absolutePath}").use { second ->
                    second.execute("UPDATE chunks SET text='external' WHERE id=?", listOf(chunk))
                }
                assertTrue(EmbeddingInputRevision.current(db, first.knowledgeBaseId) > start)
                val changed = EmbeddingInputRevision.current(db, first.knowledgeBaseId)
                Migrations.apply(db)
                assertEquals(changed, EmbeddingInputRevision.current(db, first.knowledgeBaseId))
                repo.closeVectorIndexes()
            }
        } finally { file.delete() }
    }

    @Test fun replaceCannotResetRevisionOrForgetTheDisplacedOwner() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink())
            val a = repo.importBytes("a.txt", "text/plain", "a".toByteArray(), false)
            val b = repo.importBytes("b.txt", "text/plain", "b".toByteArray(), false, repo.createKnowledgeBase("b"))
            val chunk = db.query("SELECT c.* FROM chunks c JOIN documents d ON d.active_version_id=c.document_version_id WHERE d.id=?", listOf(a.documentId)).single()
            val bVersion = db.query("SELECT active_version_id FROM documents WHERE id=?", listOf(b.documentId)).single().string("active_version_id")
            var prior = EmbeddingInputRevision.current(db, a.knowledgeBaseId)
            repeat(3) {
                db.execute("INSERT OR REPLACE INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",
                    listOf(chunk.string("id"),chunk.string("document_version_id"),chunk.long("ordinal"),"replacement", "same",11))
                val current = EmbeddingInputRevision.current(db, a.knowledgeBaseId)
                assertTrue(current > prior)
                prior = current
            }
            val bPrior = EmbeddingInputRevision.current(db, b.knowledgeBaseId)
            db.execute("INSERT OR REPLACE INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",
                listOf(chunk.string("id"),bVersion,999,"moved", "same",5))
            assertTrue(EmbeddingInputRevision.current(db, a.knowledgeBaseId) > prior)
            assertTrue(EmbeddingInputRevision.current(db, b.knowledgeBaseId) > bPrior)
            repo.closeVectorIndexes()
        }
    }
}
