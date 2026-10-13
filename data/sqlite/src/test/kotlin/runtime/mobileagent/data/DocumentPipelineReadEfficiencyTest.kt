// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class DocumentPipelineReadEfficiencyTest {
    private class CountingConnection(private val delegate: SqlConnection) : SqlConnection by delegate {
        var queries = 0
        var depth = 0
        var requireSnapshot = false
        val projections = mutableListOf<Set<String>>()
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            queries++
            if (requireSnapshot) assertTrue(depth > 0, "reuse facts must share one SQL snapshot")
            return delegate.query(sql, args).also { rows -> rows.forEach { projections += it.columns.keys } }
        }
        override fun <T> transaction(block: () -> T): T {
            depth++
            try { return delegate.transaction(block) } finally { depth-- }
        }
        fun reset() { queries = 0; projections.clear() }
    }

    /** The previous per-unit algorithm remains an independent parity oracle. */
    private fun oldReuse(db: SqlConnection, job: String, units: List<ProcessingUnit>, target: String): PipelineReuseSummary {
        val store = DocumentPipelineStore(db)
        val selected = db.query("SELECT vision_binding_json FROM import_jobs WHERE id=?", listOf(job)).single().string("vision_binding_json")
        var direct = 0; var local = 0; var requests = 0; var unknown = 0
        units.forEach { unit ->
            val prior = db.query("SELECT chunk_version,published FROM pipeline_units WHERE job_id=? AND unit_id=?", listOf(job, unit.unitId)).singleOrNull()
            when {
                store.unknown(job, unit.unitId, unit.page) -> unknown++
                !unit.requiresVision || store.result(job, unit.unitId, target) != null ->
                    if (prior?.string("chunk_version") == "chunks" && prior.long("published") == 1L && (!unit.requiresVision || selected == target)) direct++ else local++
                else -> requests++
            }
        }
        return PipelineReuseSummary(direct, local, requests, unknown, plannerVersion = units.firstOrNull()?.plannerVersion.orEmpty(), chunkVersion = "chunks")
    }

    @Test fun reuseQueryCountIsConstantAndUnknownTargetAndRetrySemanticsMatch() {
        listOf(1, 500).forEach { size ->
            JdbcSqlConnection().use { raw ->
                Migrations.apply(raw)
                val repo = KnowledgeRepository(raw, MemoryBlobSink(), automaticStorageMaintenance = false)
                val job = repo.importBytes("test.txt", "text/plain", "local evidence".toByteArray(), false, pauseAt = ImportStage.COPYING)
                raw.execute("UPDATE import_jobs SET vision_binding_json='target' WHERE id=?", listOf(job.id))
                val base = DocumentUnitPlanner().plan("synthetic", listOf(PlanningPage(1, "", true))).single()
                val units = (0 until size).map { index -> base.copy(unitId = "unit-$index", page = index + 1, requiresVision = index % 8 != 0) }
                val store = DocumentPipelineStore(raw)
                store.materialize(job.id, "synthetic", base.plannerVersion, units)
                raw.execute("UPDATE pipeline_units SET published=1,chunk_version='chunks' WHERE job_id=?", listOf(job.id))
                units.forEachIndexed { index, unit ->
                    when (index % 8) {
                        1 -> store.saveResult(job.id, unit, "target", "cache-$index", "asset-$index", VisionSuccess("", ""))
                        2 -> store.saveResult(job.id, unit, "other-target", "cache-$index", "asset-$index", VisionSuccess("", ""))
                        3, 4, 5, 6, 7 -> {
                            insertAttempt(raw, job.id, unit.unitId, "attempt-$index", 1, "UNKNOWN_OUTCOME")
                            if (index % 8 == 4) raw.execute("INSERT INTO pipeline_retry_permits(unknown_attempt_id,consumed) VALUES(?,0)", listOf("attempt-$index"))
                            if (index % 8 == 5) insertAttempt(raw, job.id, unit.unitId, "latest-$index", 2, "SUCCEEDED")
                            if (index % 8 == 6 || index % 8 == 7) raw.execute(
                                "INSERT INTO pipeline_retry_permits(unknown_attempt_id,consumed,replacement_request_id) VALUES(?,1,?)",
                                listOf("attempt-$index", if (index % 8 == 6) "replacement" else null))
                        }
                    }
                }
                // Inactive old splits still block candidates on the same page, across targets.
                val old = base.copy(unitId = "old-split", page = size + 1)
                store.materialize(job.id, "synthetic", base.plannerVersion, units + old)
                raw.execute("UPDATE pipeline_units SET active=0 WHERE job_id=? AND unit_id=?", listOf(job.id, old.unitId))
                insertAttempt(raw, job.id, old.unitId, "old-split-attempt", 1, "UNKNOWN_OUTCOME")
                val candidate = units + old.copy(unitId = "new-split", requiresVision = false)
                val observed = CountingConnection(raw)
                val efficient = DocumentPipelineStore(observed)
                listOf("target", "changed-target").forEach { target ->
                    observed.reset()
                    val expected = oldReuse(observed, job.id, candidate, target)
                    val oldQueries = observed.queries
                    observed.reset(); observed.requireSnapshot = true
                    assertEquals(expected, efficient.reuse(job.id, candidate, target, "chunks"))
                    assertEquals(4, observed.queries, "four fact projections regardless of unit count")
                    assertTrue(oldQueries > observed.queries)
                    assertTrue(observed.projections.none { "unit_json" in it || "ocr" in it || "description" in it })
                    println("PIPELINE_REUSE units=${candidate.size} target=$target old_queries=$oldQueries new_queries=${observed.queries}")
                    observed.requireSnapshot = false
                }
                // READY and DISPATCHED remain uncertain even without terminal outcomes.
                insertAttempt(raw, job.id, units.first().unitId, "ready", 1, "READY", 1)
                val ready = efficient.reuse(job.id, units.take(1), "target", "chunks")
                assertEquals(1, ready.unknown)
                raw.execute("UPDATE pipeline_attempts SET state='DISPATCHED' WHERE request_id='ready'")
                assertEquals(ready, efficient.reuse(job.id, units.take(1), "target", "chunks"))
            }
        }
    }

    @Test fun progressNeverMaterializesLargeUnitPayloadsAndKeepsCountersAndUsage() {
        JdbcSqlConnection().use { raw ->
            Migrations.apply(raw)
            val repo = KnowledgeRepository(raw, MemoryBlobSink(), automaticStorageMaintenance = false)
            val batch = repo.beginBatch(repo.ensureDefaultBase(), ImportBatchKind.FILES, "progress")
            val job = repo.importBytes("progress.txt", "text/plain", "local evidence".toByteArray(), false, pauseAt = ImportStage.COPYING)
            repo.bindJobToBatch(batch, job, "progress.txt")
            val base = DocumentUnitPlanner().plan("synthetic", listOf(PlanningPage(1, "", true))).single()
            val states = listOf("PLANNED", "READY", "CANCELLED", "DISPATCHED", "SUCCEEDED", "FAILED", "UNKNOWN_OUTCOME")
            val units = (0 until 500).map { base.copy(unitId = "large-$it", page = it + 1) }
            DocumentPipelineStore(raw).materialize(job.id, "synthetic", base.plannerVersion, units)
            val payload = "large native text ".repeat(4096)
            raw.transaction { units.forEachIndexed { index, unit ->
                raw.execute("UPDATE pipeline_units SET state=?,published=?,unit_json=? WHERE job_id=? AND unit_id=?",
                    listOf(states[index % states.size], if (index % states.size == 4) 1 else 0, payload, job.id, unit.unitId))
            } }
            insertAttempt(raw, job.id, units.first().unitId, "usage", 1, "UNKNOWN_OUTCOME", batch = batch, inputTokens = 10L)
            // Terminal rows are immutable, so seed a second complete usage receipt at insertion.
            raw.execute("INSERT INTO pipeline_attempts(request_id,job_id,batch_id,unit_id,ordinal,target,config_fingerprint,planner_version,result_version,cache_key,state,reservation_tokens,input_tokens,output_tokens,reasoning_tokens,created_at,dispatched_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf("reported", job.id, batch, units.last().unitId, 1, "target", "f", "v", "v", "c", "SUCCEEDED", 0, 20, 5, 3, "now", "now"))
            val observed = CountingConnection(raw)
            val progress = DocumentPipelineStore(observed).progress(batch)
            assertEquals(500, progress.units)
            assertEquals(500, progress.pages)
            assertEquals(1, progress.files)
            assertEquals(0, progress.completedFiles)
            assertEquals(0, progress.legacyUnplannedFiles)
            assertEquals(units.indices.count { it % 7 in 0..2 }, progress.pending)
            assertEquals(units.indices.count { it % 7 == 3 }, progress.inFlight)
            assertEquals(units.indices.count { it % 7 == 4 }, progress.succeeded)
            assertEquals(progress.succeeded, progress.published)
            assertEquals(units.indices.count { it % 7 == 5 }, progress.failed)
            assertEquals(units.indices.count { it % 7 == 6 }, progress.unknown)
            assertEquals(30L, progress.usage.inputTokens)
            assertEquals(5L, progress.usage.outputTokens)
            assertEquals(3L, progress.usage.reasoningTokens)
            assertEquals(2, progress.usage.attempts)
            assertEquals(1, progress.usage.unknownUsageAttempts)
            assertEquals(35L, progress.usage.reservedTokens)
            assertEquals(4, observed.queries)
            assertTrue(observed.projections.none { "unit_json" in it || "ocr" in it || "config_fingerprint" in it })
        }
    }

    private fun insertAttempt(db: SqlConnection, job: String, unit: String, request: String, ordinal: Int, state: String,
        slot: Int? = null, batch: String? = null, inputTokens: Long? = null) {
        db.execute("INSERT INTO pipeline_attempts(request_id,job_id,batch_id,unit_id,ordinal,target,config_fingerprint,planner_version,result_version,cache_key,state,dispatch_slot,created_at,input_tokens) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(request, job, batch, unit, ordinal, "old-target", "f", "v", "v", "c", state, slot, "now", inputTokens))
    }
}
