// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink

class KnowledgeDisplayQueryTest {
    @Test
    fun emptyLibrarySummaryIsEmptyWithOneQuery() = fixture { db ->
        val counted = CountingConnection(db)
        assertTrue(KnowledgeRepository(counted, MemoryBlobSink()).listKnowledgeBaseDisplaySummaries().isEmpty())
        assertEquals(1, counted.queries.size)
    }

    @Test
    fun summariesPreserveCreationOrderAndCountOnlyLiveDocumentsAndBases() = fixture { db ->
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        listOf("empty", "live", "removed").forEachIndexed { index, id ->
            repo.createKnowledgeBase("Same name", id)
            db.execute("UPDATE knowledge_bases SET created_at=? WHERE id=?", listOf(index.toString(), id))
        }
        document(db, "live.1", "live")
        document(db, "live.2", "live")
        document(db, "live.deleted", "live", deleted = true)
        document(db, "empty.deleted", "empty", deleted = true)
        document(db, "removed.1", "removed")
        db.execute("UPDATE knowledge_bases SET deleted_at=? WHERE id=?", listOf("2026-10-03", "removed"))
        val expected = legacySummaries(repo, db)
        val counted = CountingConnection(db)
        val actual = KnowledgeRepository(counted, MemoryBlobSink()).listKnowledgeBaseDisplaySummaries()
        assertEquals(expected, actual)
        assertEquals(listOf("empty", "live"), actual.map { it.id })
        assertEquals(listOf(0, 2), actual.map { it.documentCount })
        assertEquals(1, counted.queries.size)
        assertTrue(counted.columns.all { it == setOf("id", "name", "document_count") })
    }

    @Test
    fun manyBasesReplacePerBaseCountQueriesWithOneRead() = fixture { db ->
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        db.transaction {
            repeat(256) { index ->
                val id = "kb.$index"
                repo.createKnowledgeBase(id, id)
                db.execute("UPDATE knowledge_bases SET created_at=? WHERE id=?", listOf(index.toString().padStart(4, '0'), id))
                repeat(index % 4) { document(db, "$id.document.$it", id) }
            }
        }
        val counted = CountingConnection(db)
        val measured = KnowledgeRepository(counted, MemoryBlobSink())
        val expected = legacySummaries(measured, counted)
        assertEquals(257, counted.queries.size)
        counted.clear()
        assertEquals(expected, measured.listKnowledgeBaseDisplaySummaries())
        assertEquals(1, counted.queries.size)
        assertEquals(256, counted.rowCounts.single())
    }

    @Test
    fun scopedJobsMatchGlobalFilterIncludingConsentUnknownOutcomeAndOrdering() = fixture { db ->
        job(db, "first", "selected", ImportStage.AWAITING_UPLOAD_CONSENT.name, "3", "UNKNOWN_OUTCOME:vision")
        job(db, "other", "other", ImportStage.READY.name, "4")
        job(db, "second", "selected", ImportStage.READY_WITH_VISUAL_GAPS.name, "1")
        job(db, "third", "selected", ImportStage.AWAITING_EMBEDDING_CONSENT.name, "2")
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val actual = repo.listJobs("selected")
        assertEquals(repo.listJobs().filter { it.first.knowledgeBaseId == "selected" }, actual)
        assertEquals(listOf("first", "third", "second"), actual.map { it.first.id })
        assertTrue(actual.first().first.hasImages)
        assertTrue(actual.first().first.visionConsent)
        assertTrue(actual.first().first.embeddingIsApi)
        assertTrue(actual.first().first.embeddingConsent)
        assertEquals("fixture-vision-binding", actual.first().first.consentedVisionFingerprint)
        assertEquals("UNKNOWN_OUTCOME:vision", actual.first().first.error)
        assertTrue(actual.last().first.visualGapsAccepted)
    }

    @Test
    fun largeScopedListsDoNotMaterializeUnselectedJobRows() = fixture { db ->
        db.transaction {
            repeat(2_000) { job(db, "other.$it", "other", ImportStage.READY.name, it.toString().padStart(4, '0')) }
            repeat(3) { job(db, "selected.$it", "selected", ImportStage.READY.name, it.toString().padStart(4, '0')) }
        }
        val counted = CountingConnection(db)
        val repo = KnowledgeRepository(counted, MemoryBlobSink())
        val expected = repo.listJobs().filter { it.first.knowledgeBaseId == "selected" }
        assertEquals(2_003, counted.rowCounts.single())
        counted.clear()
        assertEquals(expected, repo.listJobs("selected"))
        assertEquals(3, counted.rowCounts.single())
        assertEquals(listOf("selected"), counted.queries.single().second)
    }

    @Test
    fun scopeIsolatesUnselectedCorruptStagesButSelectedAndGlobalReadsStillRejectThem() = fixture { db ->
        job(db, "valid", "selected", ImportStage.READY.name, "1")
        job(db, "corrupt", "other", "invalid-stage", "2")
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        assertEquals(listOf("valid"), repo.listJobs("selected").map { it.first.id })
        assertThrows(IllegalArgumentException::class.java) { repo.listJobs("other") }
        assertThrows(IllegalArgumentException::class.java) { repo.listJobs() }
    }

    @Test
    fun unknownAndSqlLikeScopesRemainBoundData() = fixture { db ->
        job(db, "valid", "selected", ImportStage.READY.name, "1")
        val counted = CountingConnection(db)
        val repo = KnowledgeRepository(counted, MemoryBlobSink())
        val sqlLike = "selected' OR 1=1 --"
        assertTrue(repo.listJobs(sqlLike).isEmpty())
        assertEquals(listOf(sqlLike), counted.queries.single().second)
        assertTrue(!counted.queries.single().first.contains(sqlLike))
        assertTrue(repo.listJobs("missing").isEmpty())
        assertEquals(1, repo.listJobs().size)
    }

    private fun fixture(test: (SqlConnection) -> Unit) {
        JdbcSqlConnection().use { db -> Migrations.apply(db); test(db) }
    }

    private fun legacySummaries(repo: KnowledgeRepository, db: SqlConnection) =
        repo.listKnowledgeBases().map { (id, name) ->
            KnowledgeBaseDisplaySummary(id, name, db.query(
                "SELECT COUNT(*) AS n FROM documents WHERE kb_id=? AND deleted_at IS NULL", listOf(id),
            ).single().long("n").toInt())
        }

    private fun document(db: SqlConnection, id: String, kb: String, deleted: Boolean = false) {
        db.execute(
            "INSERT INTO documents(id,kb_id,blob_hash,display_name,format,deleted_at) VALUES(?,?,?,?,?,?)",
            listOf(id, kb, "hash.$id", id, "TXT", if (deleted) "2026-10-03" else null),
        )
    }

    private fun job(db: SqlConnection, id: String, kb: String, stage: String, updated: String, error: String? = null) {
        db.execute(
            "INSERT INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,error,updated_at," +
                "vision_consent,embedding_is_api,embedding_consent,vision_binding_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(id, kb, "document.$id", "Name $id", stage, 1, error, updated, 1, 1, 1, "fixture-vision-binding"),
        )
    }

    private class CountingConnection(private val delegate: SqlConnection) : SqlConnection by delegate {
        val queries = mutableListOf<Pair<String, List<Any?>>>()
        val rowCounts = mutableListOf<Int>()
        val columns = mutableListOf<Set<String>>()
        override fun query(sql: String, args: List<Any?>): List<SqlRow> =
            delegate.query(sql, args).also { rows ->
                queries += sql to args.toList()
                rowCounts += rows.size
                columns += rows.map { it.columns.keys }
            }
        fun clear() { queries.clear(); rowCounts.clear(); columns.clear() }
    }
}
