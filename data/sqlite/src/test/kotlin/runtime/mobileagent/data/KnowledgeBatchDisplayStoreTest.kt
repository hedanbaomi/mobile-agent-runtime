// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchItemView
import runtime.mobileagent.knowledge.PipelinePolicy

class KnowledgeBatchDisplayStoreTest {
    private class Counted(private val db: SqlConnection) : SqlConnection by db {
        var queries = 0
        var depth = 0
        var snapshotRequired = false
        override fun <T> transaction(block: () -> T): T {
            depth++
            try { return db.transaction(block) } finally { depth-- }
        }
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            queries++
            if (snapshotRequired) assertTrue(depth > 0)
            assertTrue(args.size <= 801)
            return db.query(sql, args)
        }
    }

    private fun schema(db: SqlConnection) {
        db.execute("CREATE TABLE import_batches(id TEXT PRIMARY KEY,kb_id TEXT,staging_complete INTEGER)")
        db.execute("CREATE TABLE import_jobs(id TEXT PRIMARY KEY,display_name TEXT)")
        db.execute("CREATE TABLE import_items(batch_id TEXT,job_id TEXT,relative_path TEXT,state TEXT,error TEXT)")
        db.execute("CREATE INDEX items_batch ON import_items(batch_id)")
        db.execute("CREATE TABLE pipeline_policies(batch_id TEXT PRIMARY KEY,max_concurrency INTEGER,failure_limit INTEGER,token_ceiling INTEGER,reservation_tokens INTEGER)")
    }

    /** Independent copy of the previous per-batch display read, including the extra resume lookup. */
    private fun previous(db: SqlConnection, id: String): KnowledgeBatchDisplay {
        val staged = db.query("SELECT staging_complete FROM import_batches WHERE id=?", listOf(id)).singleOrNull()?.long("staging_complete")
        val items = db.query("SELECT i.job_id,COALESCE(NULLIF(j.display_name,''),i.relative_path) AS display_name,i.state,i.error FROM import_items i LEFT JOIN import_jobs j ON j.id=i.job_id WHERE i.batch_id=? ORDER BY i.rowid", listOf(id)).map { row ->
            val job = row.string("job_id").ifBlank { null }
            val state = row.string("state")
            ImportBatchItemView(job, row.string("display_name"),
                if (staged == 1L && job != null && state in setOf("PENDING", "COPYING")) "QUEUED" else state,
                row.string("error").ifBlank { null })
        }
        val row = db.query("SELECT * FROM pipeline_policies WHERE batch_id=?", listOf(id)).singleOrNull()
        val policy = if (row == null) PipelinePolicy() else PipelinePolicy(row.long("max_concurrency").toInt(),
            row.long("failure_limit").toInt(),row.longOrNull("token_ceiling"),row.longOrNull("reservation_tokens"))
        assertEquals(staged, db.query("SELECT staging_complete FROM import_batches WHERE id=?", listOf(id)).singleOrNull()?.long("staging_complete"))
        return KnowledgeBatchDisplay(staged, items, policy)
    }

    @Test fun oneHundredBatchesKeepExactOrderingPolicyAndStagingWithTwoReads() {
        JdbcSqlConnection().use { raw ->
            schema(raw)
            raw.transaction {
                repeat(100) { i ->
                    val id = "batch-$i"
                    raw.execute("INSERT INTO import_batches VALUES(?,?,?)", listOf(id, "kb", i % 2))
                    raw.execute("INSERT INTO import_jobs VALUES(?,?)", listOf("job-$i", if (i % 2 == 0) "Named $i" else ""))
                    raw.execute("INSERT INTO import_items VALUES(?,?,?,?,?)", listOf(id, "job-$i", "relative-$i", "COPYING", null))
                    raw.execute("INSERT INTO import_items VALUES(?,?,?,?,?)", listOf(id, null, "waiting-$i", "WAITING", "UNKNOWN_OUTCOME preserved"))
                    raw.execute("INSERT INTO import_items VALUES(?,?,?,?,?)", listOf(id, "job-$i", "cancelled-$i", "CANCELLED", ""))
                    if (i % 2 == 0) raw.execute("INSERT INTO pipeline_policies VALUES(?,?,?,?,?)", listOf(id, 6, 5, 10000, 1000))
                }
                raw.execute("INSERT INTO import_items VALUES('batch-1',NULL,'unlinked pending','PENDING',NULL)")
                raw.execute("INSERT INTO import_items VALUES('batch-1','missing-job','fallback name','PENDING',NULL)")
            }
            val ids = (0..99).map { "batch-$it" }
            val counted = Counted(raw)
            val expected = ids.associateWith { previous(counted, it) }
            assertEquals(400, counted.queries)
            counted.queries = 0; counted.snapshotRequired = true
            assertEquals(expected, KnowledgeBatchDisplayStore(counted).read("kb", ids))
            assertEquals(2, counted.queries)
            println("KNOWLEDGE_BATCH_DISPLAY batches=100 old_queries=400 new_queries=2")
            raw.execute("UPDATE import_batches SET staging_complete=1 WHERE id='batch-0'")
            assertEquals("QUEUED", KnowledgeBatchDisplayStore(counted).read("kb", listOf("batch-0")).getValue("batch-0").items.first().state)
        }
    }

    @Test fun emptyMissingForeignAndLargeIdListsStayIsolatedAndComplete() {
        JdbcSqlConnection().use { raw ->
            schema(raw)
            raw.transaction { repeat(1601) { raw.execute("INSERT INTO import_batches VALUES(?,?,0)", listOf("batch-$it", "kb")) } }
            raw.execute("INSERT INTO import_batches VALUES('foreign','other',1)")
            raw.execute("INSERT INTO import_items VALUES('missing',NULL,'orphan detail','COPYING',NULL)")
            raw.execute("INSERT INTO pipeline_policies VALUES('missing',2,3,NULL,NULL)")
            val db = Counted(raw)
            val store = KnowledgeBatchDisplayStore(db)
            assertTrue(store.read("kb", emptyList()).isEmpty())
            assertTrue(store.read(null, listOf("batch-0")).isEmpty())
            assertEquals(0, db.queries)
            assertEquals(previous(raw, "missing"), store.read("kb", listOf("missing")).getValue("missing"))
            assertFalse(store.read("kb", listOf("foreign")).containsKey("foreign"))
            db.queries = 0
            val ids = (0..1600).map { "batch-$it" }
            val rows = store.read("kb", ids + ids.take(5))
            assertEquals(ids.toSet(), rows.keys)
            assertTrue(rows.values.all { it.items.isEmpty() && it.policy == PipelinePolicy() && it.stagingComplete == 0L })
            assertEquals(6, db.queries)
        }
    }

    @Test fun malformedStoredPolicyStillFailsInsteadOfBecomingADefault() {
        JdbcSqlConnection().use { db ->
            schema(db)
            db.execute("INSERT INTO import_batches VALUES('batch','kb',0)")
            db.execute("INSERT INTO pipeline_policies VALUES('batch',0,0,NULL,NULL)")
            assertThrows(IllegalArgumentException::class.java) { previous(db, "batch") }
            assertThrows(IllegalArgumentException::class.java) { KnowledgeBatchDisplayStore(db).read("kb", listOf("batch")) }
        }
    }
}
