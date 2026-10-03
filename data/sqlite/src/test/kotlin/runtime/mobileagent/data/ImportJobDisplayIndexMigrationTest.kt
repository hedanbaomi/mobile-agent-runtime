// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink

class ImportJobDisplayIndexMigrationTest {
    @Test
    fun populatedV28UpgradePreservesRowsAndEqualTimestampOrderWhileUsingTheIndex() {
        v28Fixture { db ->
            db.transaction {
                repeat(2_000) { job(db, "other-$it", "other", "2026-10-03T08:00:00Z") }
                job(db, "selected-z", "selected", "2026-10-03T09:00:00Z")
                job(db, "selected-a", "selected", "2026-10-03T09:00:00Z")
                job(db, "selected-old", "selected", "2026-10-03T07:00:00Z")
            }
            val before = db.query("SELECT * FROM import_jobs ORDER BY rowid")
            val repo = KnowledgeRepository(db, MemoryBlobSink())
            val expected = repo.listJobs().filter { it.first.knowledgeBaseId == "selected" }
            assertEquals(listOf("selected-z", "selected-a", "selected-old"), expected.map { it.first.id })
            val oldPlan = plan(db)
            assertTrue(oldPlan.any { it.contains("SCAN import_jobs") }, oldPlan.toString())

            Migrations.apply(db)

            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(before, db.query("SELECT * FROM import_jobs ORDER BY rowid"))
            assertEquals(expected, repo.listJobs("selected"))
            assertScopedPlan(db)
        }
    }

    @Test
    fun emptyAndRepeatedApplyKeepASingleNonUniqueScopedIndex() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val first = indexSchema(db)
            assertEquals(1, first.size)
            assertEquals(0L, db.query("PRAGMA index_list(import_jobs)")
                .single { it.string("name") == INDEX_NAME }.long("unique"))
            assertScopedPlan(db)
            Migrations.apply(db)
            assertEquals(first, indexSchema(db))
            assertScopedPlan(db)
        }
    }

    @Test
    fun failureAfterIndexDdlRollsBackIndexVersionAndJobRowsAndAllowsRetry() {
        v28Fixture { db ->
            job(db, "uncertain", "selected", "2026-10-03T09:00:00Z")
            val before = db.query("SELECT * FROM import_jobs")
            val fault = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    db.execute(sql, args)
                    if (sql.startsWith("CREATE INDEX IF NOT EXISTS " + INDEX_NAME)) {
                        throw IllegalStateException("fixture failure after index DDL")
                    }
                }
            }

            val failure = assertThrows(IllegalStateException::class.java) { Migrations.apply(fault) }

            assertEquals("fixture failure after index DDL", failure.message)
            assertEquals(28L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertTrue(indexSchema(db).isEmpty())
            assertEquals(before, db.query("SELECT * FROM import_jobs"))
            Migrations.apply(db)
            assertEquals(before, db.query("SELECT * FROM import_jobs"))
            assertScopedPlan(db)
        }
    }

    // v29 only adds this index. Rewind the migrated schema's index/version to
    // construct a synthetic v28 fixture; this is not a captured user's database.
    private fun v28Fixture(test: (JdbcSqlConnection) -> Unit) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("DROP INDEX " + INDEX_NAME)
            db.execute("UPDATE schema_version SET version = 28")
            test(db)
        }
    }

    private fun job(db: SqlConnection, id: String, kbId: String, updated: String) {
        db.execute(
            "INSERT INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,error,updated_at," +
                "vision_consent,embedding_is_api,embedding_consent,vision_binding_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(id, kbId, "document-$id", id, ImportStage.READY.name, 1, "UNKNOWN_OUTCOME",
                updated, 1, 1, 1, "fixture-binding"),
        )
    }

    private fun plan(db: SqlConnection) =
        db.query("EXPLAIN QUERY PLAN SELECT * FROM import_jobs WHERE kb_id=? ORDER BY updated_at DESC", listOf("selected"))
            .map { it.string("detail") }

    private fun assertScopedPlan(db: SqlConnection) {
        val plan = plan(db)
        assertTrue(plan.any { it.contains("SEARCH import_jobs USING INDEX " + INDEX_NAME) }, plan.toString())
        assertFalse(plan.any { it.contains("SCAN import_jobs") || it.contains("TEMP B-TREE") }, plan.toString())
    }

    private fun indexSchema(db: SqlConnection) =
        db.query("SELECT name,sql FROM sqlite_master WHERE type='index' AND name=?", listOf(INDEX_NAME))

    private companion object {
        const val INDEX_NAME = "idx_import_jobs_kb_updated"
    }
}
