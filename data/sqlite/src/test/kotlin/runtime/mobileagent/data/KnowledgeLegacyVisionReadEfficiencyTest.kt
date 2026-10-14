// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class KnowledgeLegacyVisionReadEfficiencyTest {
    private class Observed(private val delegate: SqlConnection) : SqlConnection by delegate {
        var reads = 0
        var legacyReads = 0
        val plans = mutableListOf<String>()
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            reads++
            if (sql.contains("SELECT DISTINCT a.page")) {
                legacyReads++
                plans += delegate.query("EXPLAIN QUERY PLAN $sql", args).map { it.string("detail") }
            }
            return delegate.query(sql, args)
        }
        fun reset() { reads = 0; legacyReads = 0; plans.clear() }
    }

    @Test fun aFiveHundredPagePreviewMatchesTheOriginalLegacyBarriersWithOneRead() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val setup = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val batch = setup.beginBatch(setup.ensureDefaultBase(), ImportBatchKind.FILES, "legacy preview")
            val job = setup.importBytes("preview.txt", "text/plain", "preview source".toByteArray(), false, pauseAt = ImportStage.COPYING)
            setup.bindJobToBatch(batch, job, "preview.txt")
            db.execute("UPDATE import_jobs SET vision_binding_json='original-target' WHERE id=?", listOf(job.id))
            val base = DocumentUnitPlanner().plan("synthetic", listOf(PlanningPage(1, "", true))).single()
            val units = (1..500).map { base.copy(unitId = "unit-$it", page = it) }
            val pipeline = DocumentPipelineStore(db)
            pipeline.materialize(job.id, "synthetic", base.plannerVersion, units)
            db.transaction {
                units.forEach { unit ->
                    val index = unit.page
                    val target = when (index % 6) { 0 -> "new-target"; 1 -> "legacy-target"; 2 -> "original-target"; else -> "unrelated-target" }
                    insertAssetAndResult(db, job.documentId, "own-$index", index, target, if (index % 6 == 5) "SUCCESS" else "UNKNOWN_OUTCOME")
                    if (index % 6 == 3 || index % 6 == 4) insertAttempt(db,
                        if (index % 6 == 3) job.id else "other-job", "own-$index", "unrelated-target")
                }
                repeat(10_000) { insertAssetAndResult(db, "unrelated-document", "history-$it", it, "new-target", "UNKNOWN_OUTCOME") }
            }
            val observed = Observed(db)
            val repo = KnowledgeRepository(observed, MemoryBlobSink(), automaticStorageMaintenance = false,
                legacyVisionCacheTarget = { LegacyVisionCacheTarget("legacy-target", unambiguous = false) })
            listOf(false, true).forEach { allPages ->
                if (allPages) insertAssetAndResult(db, job.documentId, "null-page", null, "new-target", "UNKNOWN_OUTCOME")
                val oldObserved = Observed(db)
                val unknown = units.filter { oldUnknown(oldObserved, job.id, job.documentId, it.page, "new-target", "legacy-target") }
                assertEquals(500, oldObserved.reads)
                val reused = pipeline.reuse(job.id, units - unknown.toSet(), "new-target", PIPELINE_CHUNK_VERSION)
                val expected = reused.copy(unknown = reused.unknown + unknown.size,
                    plannerVersion = DocumentUnitPlanner.VERSION, chunkVersion = PIPELINE_CHUNK_VERSION)
                observed.reset()
                assertEquals(expected, repo.batchReuseSummary(batch, "new-target", refreshPlan = false))
                assertEquals(1, observed.legacyReads)
                assertEquals(7, observed.reads, "batch members + units + one legacy projection + four pipeline fact reads")
                assertIndexedLegacyProjection(observed.plans)
                println("LEGACY_REUSE pages=500 history=10000 nullPage=$allPages old_legacy_reads=${oldObserved.reads} new_legacy_reads=${observed.legacyReads} total_reads=${observed.reads} plan=${observed.plans}")
                if (allPages) assertEquals(500, expected.unknown)
            }
            // Text-only previews do not even inspect legacy image receipts.
            // Unit identities are immutable: a different input needs a distinct plan identity,
            // otherwise INSERT OR IGNORE correctly retains the original visual unit JSON.
            pipeline.materialize(job.id, "synthetic-text", base.plannerVersion,
                units.map { it.copy(unitId = "text-${it.unitId}", documentContentHash = "synthetic-text", requiresVision = false) })
            observed.reset()
            assertEquals(0, repo.batchReuseSummary(batch, "new-target", refreshPlan = false).unknown)
            assertEquals(0, observed.legacyReads)
            assertEquals(6, observed.reads)
        }
    }

    @Test fun nullLegacyAliasAndContextAndDocumentIsolationKeepTheOriginalPredicate() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            // Exact original predicates need no parser/provider work.
            insertAssetAndResult(db, "document", "target", 1, "target", "UNKNOWN_OUTCOME")
            insertAssetAndResult(db, "document", "success", 2, "target", "SUCCESS")
            insertAssetAndResult(db, "other-document", "other", 3, "target", "UNKNOWN_OUTCOME")
            db.execute("INSERT INTO assets(id,document_id,blob_hash,page,kind,surrounding_text_hash) VALUES('wrong-context','document','hash-other',3,'IMAGE','wrong-context')")
            val observed = Observed(db)
            val pages = readLegacyVisionUnknownPages(observed, "missing-job", "document", "target", null)
            assertEquals(setOf<Long?>(1L), pages)
            (0..10).forEach { page -> assertEquals(oldUnknown(db, "missing-job", "document", page, "target", null), page.toLong() in pages) }
            assertEquals(1, observed.legacyReads)
        }
    }

    @Test fun currentSchemaReinstallsMissingDerivedIndexesWithoutChangingReceipts() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val job = repo.importBytes("receipts.txt", "text/plain", "retained local receipt source".toByteArray(), false, pauseAt = ImportStage.COPYING)
            insertAssetAndResult(db, job.documentId, "receipt", null, "target", "UNKNOWN_OUTCOME")
            insertAttempt(db, job.id, "receipt", "target")
            val beforeAssets = db.query("SELECT * FROM assets ORDER BY id")
            val beforeResults = db.query("SELECT * FROM vision_results ORDER BY cache_key")
            val beforeAttempts = db.query("SELECT * FROM vision_attempts ORDER BY request_id")
            db.execute("DROP INDEX assets_document_vision_lookup")
            db.execute("DROP INDEX vision_result_asset_lookup")
            assertEquals(0, db.query("SELECT name FROM sqlite_master WHERE type='index' AND name IN (?,?)",
                listOf("assets_document_vision_lookup", "vision_result_asset_lookup")).size)
            Migrations.apply(db)
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(2, db.query("SELECT name FROM sqlite_master WHERE type='index' AND name IN (?,?)",
                listOf("assets_document_vision_lookup", "vision_result_asset_lookup")).size)
            assertEquals(beforeAssets, db.query("SELECT * FROM assets ORDER BY id"))
            assertEquals(beforeResults, db.query("SELECT * FROM vision_results ORDER BY cache_key"))
            assertEquals(beforeAttempts, db.query("SELECT * FROM vision_attempts ORDER BY request_id"))
            val observed = Observed(db)
            assertEquals(setOf<Long?>(null), readLegacyVisionUnknownPages(observed, job.id, job.documentId, "target", null))
            assertIndexedLegacyProjection(observed.plans)
        }
    }

    private fun assertIndexedLegacyProjection(plans: List<String>) {
        assertTrue(plans.any { it.contains("SEARCH a USING COVERING INDEX assets_document_vision_lookup") }, plans.toString())
        assertTrue(plans.any { it.contains("SEARCH v USING INDEX vision_result_asset_lookup") }, plans.toString())
        assertFalse(plans.any { it.contains("SCAN a") || it.contains("SCAN v") || it.contains("SCAN old") }, plans.toString())
    }

    private fun oldUnknown(db: SqlConnection, job: String, document: String, page: Int, target: String, alias: String?): Boolean = db.query(
        "SELECT v.cache_key FROM vision_results v JOIN assets a ON a.blob_hash=v.asset_hash AND a.surrounding_text_hash=v.context_hash WHERE a.document_id=? AND (a.page=? OR a.page IS NULL) AND v.status='UNKNOWN_OUTCOME' AND (v.model_fingerprint=? OR v.model_fingerprint=? OR v.model_fingerprint=(SELECT vision_binding_json FROM import_jobs WHERE id=?) OR EXISTS(SELECT 1 FROM vision_attempts old WHERE old.job_id=? AND old.cache_key=v.cache_key)) LIMIT 1",
        listOf(document, page, target, alias, job, job),
    ).isNotEmpty()

    private fun insertAssetAndResult(db: SqlConnection, document: String, id: String, page: Int?, target: String, status: String) {
        db.execute("INSERT INTO assets(id,document_id,blob_hash,page,kind,surrounding_text_hash) VALUES(?,?,?,?,?,?)",
            listOf(id, document, "hash-$id", page, "IMAGE", "context-$id"))
        db.execute("INSERT INTO vision_results(cache_key,asset_hash,context_hash,model_fingerprint,prompt_version,schema_version,status,ocr_text,description,processed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf("cache-$id", "hash-$id", "context-$id", target, "p", "s", status, "", "", "now"))
    }

    private fun insertAttempt(db: SqlConnection, job: String, id: String, target: String) {
        db.execute("INSERT INTO vision_attempts(request_id,cache_key,job_id,asset_hash,attempt_no,status,stage,dispatch_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf("request-$id", "cache-$id", job, "hash-$id", 1, "UNKNOWN_OUTCOME", target, "DISPATCHED", "now", "now"))
    }
}
