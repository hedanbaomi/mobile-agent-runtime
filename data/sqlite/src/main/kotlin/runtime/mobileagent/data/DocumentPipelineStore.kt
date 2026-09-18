// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.*

/** Durable facts. The coordinator owns authorization; no method here invokes a provider. */
internal class DocumentPipelineStore(private val db: SqlConnection) {
    data class SavedResult(val cacheKey: String, val assetId: String, val result: VisionSuccess, val section: String?)

    fun materialize(jobId: String, contentHash: String, version: String, units: List<ProcessingUnit>) {
        db.transaction {
            db.execute("INSERT OR REPLACE INTO pipeline_plans(job_id,content_hash,planner_version,result_version,plan_hash) VALUES(?,?,?,?,?)",
                listOf(jobId, contentHash, version,resultVersion,sha256Hex(units.joinToString("|") { it.unitId }.toByteArray())))
            db.execute("UPDATE pipeline_units SET active=0 WHERE job_id=?",listOf(jobId))
            units.forEach { unit ->
                db.execute("INSERT OR IGNORE INTO pipeline_units(job_id,unit_id,planner_version,page,requires_vision,unit_json,state) VALUES(?,?,?,?,?,?,?)",
                    listOf(jobId,unit.unitId,unit.plannerVersion,unit.page,if(unit.requiresVision) 1 else 0,
                        Json.encodeToString(unit),if(unit.requiresVision) "PLANNED" else "SUCCEEDED"))
                db.execute("UPDATE pipeline_units SET active=1 WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId))
            }
        }
    }

    fun units(jobId: String): List<ProcessingUnit> = db.query(
        "SELECT u.unit_json FROM pipeline_units u JOIN pipeline_plans p ON p.job_id=u.job_id AND p.planner_version=u.planner_version WHERE u.job_id=? AND u.active=1 ORDER BY u.page,u.rowid",
        listOf(jobId)).map { Json.decodeFromString(it.string("unit_json")) }

    fun selectTarget(jobId: String, target: String?) {
        if(target == null) return
        val targetChanged=db.query("SELECT vision_binding_json FROM import_jobs WHERE id=?",listOf(jobId)).singleOrNull()?.string("vision_binding_json")!=target
        units(jobId).forEach { unit ->
            val saved = !unit.requiresVision || result(jobId,unit.unitId,target) != null
            val attempt = db.query("SELECT state FROM pipeline_attempts WHERE job_id=? AND unit_id=? AND target=? ORDER BY ordinal DESC LIMIT 1",listOf(jobId,unit.unitId,target)).singleOrNull()
            val unitRow = db.query("SELECT state FROM pipeline_units WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId)).singleOrNull()
            val priorUnitState = unitRow?.string("state")
            val state = if(saved) "SUCCEEDED" else if(unknown(jobId,unit.unitId,unit.page)) "UNKNOWN_OUTCOME" else attempt?.string("state") ?: priorUnitState?.takeIf { it != "FAILED" } ?: "PLANNED"
            db.execute("UPDATE pipeline_units SET state=?,published=CASE WHEN ?=1 THEN published ELSE 0 END,failure_code=CASE WHEN ?='PLANNED' THEN NULL ELSE failure_code END,failure_phase=CASE WHEN ?='PLANNED' THEN NULL ELSE failure_phase END WHERE job_id=? AND unit_id=?",listOf(state,if(saved && !targetChanged) 1 else 0,state,state,jobId,unit.unitId))
        }
    }

    fun result(jobId: String, unitId: String, target: String): SavedResult? = db.query(
        "SELECT * FROM pipeline_results WHERE job_id=? AND unit_id=? AND target=? AND result_version=?",
        listOf(jobId,unitId,target,resultVersion)).singleOrNull()?.let {
        SavedResult(it.string("cache_key"),it.string("asset_id"),VisionSuccess(it.string("ocr"),it.string("description"),it.string("table_markdown"),it.string("result_type")),it.string("section").ifBlank { null })
    }

    fun unknown(jobId: String, unitId: String, page: Int? = null): Boolean = unresolved(jobId,unitId,page).any { last ->
        db.query("SELECT unknown_attempt_id FROM pipeline_retry_permits WHERE unknown_attempt_id=? AND consumed=0",
            listOf(last.string("request_id"))).isEmpty()
    }

    // A changed split must not turn the same uncertain page into unrelated billable inputs.
    private fun unresolved(jobId: String, unitId: String, page: Int?): List<SqlRow> = db.query(
        "SELECT a.request_id FROM pipeline_attempts a JOIN pipeline_units u ON u.job_id=a.job_id AND u.unit_id=a.unit_id WHERE a.job_id=? AND (a.unit_id=? OR u.page=?) AND a.state IN ('UNKNOWN_OUTCOME','READY','DISPATCHED') AND a.ordinal=(SELECT MAX(b.ordinal) FROM pipeline_attempts b WHERE b.job_id=a.job_id AND b.unit_id=a.unit_id) AND NOT EXISTS(SELECT 1 FROM pipeline_retry_permits r WHERE r.unknown_attempt_id=a.request_id AND r.consumed=1 AND r.replacement_request_id IS NOT NULL)",
        listOf(jobId,unitId,page))

    fun authorizeUnknown(jobId: String) {
        recover(jobId)
        db.query("SELECT a.request_id FROM pipeline_attempts a WHERE a.job_id=? AND a.state='UNKNOWN_OUTCOME' AND a.ordinal=(SELECT MAX(b.ordinal) FROM pipeline_attempts b WHERE b.job_id=a.job_id AND b.unit_id=a.unit_id)",listOf(jobId)).forEach {
            db.execute("INSERT OR IGNORE INTO pipeline_retry_permits(unknown_attempt_id,consumed) VALUES(?,0)",listOf(it.string("request_id")))
        }
    }

    /** Only process-death recovery calls this; a live request must retain its terminal-write right. */
    fun recover(jobId: String) = db.transaction {
        db.execute(
            """
            UPDATE pipeline_units 
            SET state='UNKNOWN_OUTCOME',
                failure_code='PROCESS_INTERRUPTED',
                failure_phase='PROVIDER'
            WHERE job_id=? AND unit_id IN (SELECT unit_id FROM pipeline_attempts WHERE job_id=? AND state IN ('READY','DISPATCHED'))
            """.trimIndent(),
            listOf(jobId,jobId)
        )
        db.execute(
            """
            UPDATE pipeline_attempts 
            SET state='UNKNOWN_OUTCOME',
                failure_code='PROCESS_INTERRUPTED',
                terminal_at=?,
                input_tokens=COALESCE(input_tokens, (SELECT v.input_tokens FROM vision_attempts v WHERE v.request_id=pipeline_attempts.request_id)),
                output_tokens=COALESCE(output_tokens, (SELECT v.output_tokens FROM vision_attempts v WHERE v.request_id=pipeline_attempts.request_id))
            WHERE job_id=? AND state IN ('READY','DISPATCHED')
            """.trimIndent(),
            listOf(Utc.nowIso(),jobId)
        )
    }

    fun recordUsage(requestId: String, inputTokens: Long?, outputTokens: Long?, reasoningTokens: Long?) {
        if (inputTokens == null && outputTokens == null && reasoningTokens == null) return
        db.execute(
            "UPDATE pipeline_attempts SET input_tokens=COALESCE(?,input_tokens), output_tokens=COALESCE(?,output_tokens), reasoning_tokens=COALESCE(?,reasoning_tokens) WHERE request_id=? AND state IN ('READY','DISPATCHED')",
            listOf(inputTokens, outputTokens, reasoningTokens, requestId)
        )
    }

    fun failUnit(jobId: String, unitId: String, failureCode: String, failurePhase: String = "LOCAL_RENDER") {
        db.execute(
            "UPDATE pipeline_units SET state='FAILED', failure_code=?, failure_phase=? WHERE job_id=? AND unit_id=?",
            listOf(failureCode, failurePhase, jobId, unitId)
        )
    }

    fun policy(batchId: String?): PipelinePolicy {
        if (batchId == null) return PipelinePolicy()
        val r = db.query("SELECT * FROM pipeline_policies WHERE batch_id=?",listOf(batchId)).singleOrNull() ?: return PipelinePolicy()
        return PipelinePolicy(r.long("max_concurrency").toInt(),r.long("failure_limit").toInt(),r.longOrNull("token_ceiling"),r.longOrNull("reservation_tokens"))
    }

    fun configure(batchId: String, policy: PipelinePolicy) {
        check(db.query("SELECT request_id FROM pipeline_attempts WHERE batch_id=? AND state IN ('READY','DISPATCHED')",listOf(batchId)).isEmpty()) {
            "Wait for in-flight work to settle before changing dispatch policy"
        }
        if (policy.tokenDispatchCeiling != null) check(db.query(
            "SELECT request_id FROM pipeline_attempts WHERE batch_id=? AND reservation_tokens=0 AND (input_tokens IS NULL OR output_tokens IS NULL) AND (state='UNKNOWN_OUTCOME' OR dispatched_at IS NOT NULL)",listOf(batchId)).isEmpty()) {
            "Cannot assign a finite ceiling after unreserved requests with unknown usage"
        }
        db.execute("INSERT OR REPLACE INTO pipeline_policies(batch_id,max_concurrency,failure_limit,token_ceiling,reservation_tokens) VALUES(?,?,?,?,?)",
            listOf(batchId,policy.maxConcurrency,policy.consecutiveFailureLimit,policy.tokenDispatchCeiling,policy.reservationTokensPerRequest))
    }

    fun stopReason(batchId: String?): String? {
        if (db.query("SELECT request_id FROM pipeline_attempts WHERE state IN ('READY','DISPATCHED') LIMIT 1").isNotEmpty()) return "PIPELINE_MAX_CONCURRENCY"
        if (batchId == null) return null
        val policy = policy(batchId)
        val attempts = db.query(
            """
            SELECT p.state, p.reservation_tokens,
                   COALESCE(p.input_tokens, v.input_tokens) AS input_tokens,
                   COALESCE(p.output_tokens, v.output_tokens) AS output_tokens,
                   p.dispatched_at
            FROM pipeline_attempts p
            LEFT JOIN vision_attempts v ON v.request_id = p.request_id
            WHERE p.batch_id=? ORDER BY p.rowid DESC
            """.trimIndent(),
            listOf(batchId)
        )
        if (attempts.any { it.string("state") in setOf("READY","DISPATCHED") }) return "PIPELINE_MAX_CONCURRENCY"
        if (attempts.takeWhile { it.string("state") == "FAILED" }.size >= policy.consecutiveFailureLimit) return "PIPELINE_FAILURE_LIMIT"
        val ceiling = policy.tokenDispatchCeiling ?: return null
        val spent = attempts.sumOf(::chargedReservation) + legacyAttempts(batchId).sumOf(::chargedReservation)
        if ((policy.reservationTokensPerRequest ?: 0) > ceiling - spent) return "PIPELINE_TOKEN_DISPATCH_CEILING"
        return null
    }

    private fun chargedReservation(row: SqlRow): Long {
        if (row.string("state") == "CANCELLED" && row.string("dispatched_at").isBlank()) return 0
        val input = row.longOrNull("input_tokens")
        val output = row.longOrNull("output_tokens")
        val reservation = row.long("reservation_tokens")
        val observed = (input ?: 0L) + (output ?: 0L)
        // Unknown or incomplete usage must not fall below observed tokens or safety reservation.
        if (row.string("state") in setOf("UNKNOWN_OUTCOME","READY","DISPATCHED") || input == null || output == null)
            return maxOf(reservation, observed)
        return input + output
    }

    /** Existing reported facts count once; no terminal history is rewritten during migration. */
    private fun legacyAttempts(batchId: String): List<SqlRow> = db.query(
        "SELECT a.*,0 AS reservation_tokens,NULL AS reasoning_tokens,CASE WHEN a.status='SUCCESS' THEN 'SUCCEEDED' ELSE a.status END AS state,CASE WHEN a.dispatch_status IN ('DISPATCHED','RESPONSE_RECEIVED') THEN a.created_at ELSE NULL END AS dispatched_at FROM vision_attempts a JOIN import_jobs j ON j.id=a.job_id WHERE j.batch_id=? AND NOT EXISTS(SELECT 1 FROM pipeline_attempts p WHERE p.request_id=a.request_id)",listOf(batchId))

    fun prepare(jobId: String, unit: ProcessingUnit, batchId: String?, target: String, cacheKey: String, requestId: String): Int = db.transaction {
        check(!unknown(jobId,unit.unitId,unit.page)) { "UNKNOWN_OUTCOME: explicit duplicate-charge acknowledgement required" }
        check(stopReason(batchId) == null) { "Pipeline dispatch policy stopped new requests" }
        val ordinal = db.query("SELECT COALESCE(MAX(ordinal),0)+1 AS n FROM pipeline_attempts WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId)).single().long("n").toInt()
        unresolved(jobId,unit.unitId,unit.page).forEach { prior ->
            db.execute("UPDATE pipeline_retry_permits SET consumed=1,replacement_request_id=? WHERE unknown_attempt_id=? AND consumed=0",listOf(requestId,prior.string("request_id")))
        }
        db.execute("INSERT INTO pipeline_attempts(request_id,job_id,batch_id,unit_id,ordinal,target,config_fingerprint,planner_version,result_version,cache_key,state,reservation_tokens,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(requestId,jobId,batchId,unit.unitId,ordinal,target,sha256Hex("$target|$resultVersion|${unit.unitId}".toByteArray()),unit.plannerVersion,resultVersion,cacheKey,"READY",policy(batchId).reservationTokensPerRequest ?: 0,Utc.nowIso()))
        db.execute("UPDATE pipeline_units SET state='READY',failure_code=NULL,failure_phase=NULL WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId))
        ordinal
    }

    fun dispatched(requestId: String): Boolean = db.transaction {
        val row = db.query("SELECT state FROM pipeline_attempts WHERE request_id=?",listOf(requestId)).singleOrNull()
        if (row?.string("state") != "READY") return@transaction false
        db.execute("UPDATE pipeline_attempts SET state='DISPATCHED',dispatched_at=COALESCE(dispatched_at,?) WHERE request_id=? AND state='READY'",listOf(Utc.nowIso(),requestId))
        db.execute("UPDATE pipeline_units SET state='DISPATCHED' WHERE (job_id,unit_id) IN (SELECT job_id,unit_id FROM pipeline_attempts WHERE request_id=? AND state='DISPATCHED')",listOf(requestId))
        true
    }

    fun settle(requestId: String, state: PipelineAttemptState, metadata: VisionDiagnosticMetadata): Boolean = db.transaction {
        require(state !in setOf(PipelineAttemptState.READY,PipelineAttemptState.DISPATCHED))
        val row = db.query("SELECT * FROM pipeline_attempts WHERE request_id=?",listOf(requestId)).singleOrNull() ?: return@transaction false
        if (row.string("state") !in setOf("READY","DISPATCHED")) return@transaction false
        db.execute("UPDATE pipeline_attempts SET state=?,failure_code=?,input_tokens=COALESCE(?,input_tokens),output_tokens=COALESCE(?,output_tokens),reasoning_tokens=COALESCE(?,reasoning_tokens),terminal_at=?,dispatched_at=CASE WHEN ?=1 THEN COALESCE(dispatched_at,?) ELSE dispatched_at END WHERE request_id=? AND state IN ('READY','DISPATCHED')",
            listOf(state.name,metadata.errorCode,metadata.inputTokens,metadata.outputTokens,metadata.reasoningTokens,Utc.nowIso(),if(metadata.dispatched) 1 else 0,Utc.nowIso(),requestId))
        val failurePhase = if (state == PipelineAttemptState.FAILED) "PROVIDER" else null
        db.execute("UPDATE pipeline_units SET state=?,failure_code=?,failure_phase=? WHERE job_id=? AND unit_id=?",
            listOf(state.name, if (state == PipelineAttemptState.FAILED) metadata.errorCode else null, failurePhase, row.string("job_id"), row.string("unit_id")))
        true
    }

    fun saveResult(jobId: String, unit: ProcessingUnit, target: String, cacheKey: String, assetId: String, result: VisionSuccess, section: String? = null) {
        db.execute("INSERT OR IGNORE INTO pipeline_results(job_id,unit_id,target,result_version,cache_key,asset_id,ocr,description,table_markdown,result_type,section) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            listOf(jobId,unit.unitId,target,resultVersion,cacheKey,assetId,result.ocrText,result.semanticDescription,result.tableMarkdown,result.type,section))
        db.execute("UPDATE pipeline_units SET state='SUCCEEDED',failure_code=NULL,failure_phase=NULL WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId))
    }

    fun published(jobId: String, chunkVersion: String) {
        db.execute("UPDATE pipeline_units SET published=1,chunk_version=? WHERE job_id=? AND active=1 AND state='SUCCEEDED' AND planner_version=(SELECT planner_version FROM pipeline_plans WHERE job_id=?)",listOf(chunkVersion,jobId,jobId))
    }

    fun publication(jobId: String, chunkVersion: String): String? = db.query(
        "SELECT x.version_id FROM pipeline_publications x JOIN pipeline_plans p ON p.job_id=x.job_id AND p.plan_hash=x.plan_hash AND p.result_version=x.result_version JOIN import_jobs j ON j.id=x.job_id JOIN documents d ON d.id=j.document_id WHERE x.job_id=? AND x.chunk_version=? AND x.target=COALESCE(j.vision_binding_json,'') AND d.active_version_id=x.version_id",
        listOf(jobId,chunkVersion)).singleOrNull()?.string("version_id")

    fun recordPublication(jobId: String, chunkVersion: String, versionId: String) {
        db.execute("INSERT OR REPLACE INTO pipeline_publications(job_id,planner_version,plan_hash,result_version,target,chunk_version,version_id) SELECT p.job_id,p.planner_version,p.plan_hash,p.result_version,COALESCE(j.vision_binding_json,''),?,? FROM pipeline_plans p JOIN import_jobs j ON j.id=p.job_id WHERE p.job_id=?",listOf(chunkVersion,versionId,jobId))
        published(jobId,chunkVersion)
    }

    fun progress(batchId: String): PipelineProgress {
        val jobs = db.query("SELECT id FROM import_jobs WHERE batch_id=?",listOf(batchId))
        val rows = db.query("SELECT u.* FROM pipeline_units u JOIN pipeline_plans p ON p.job_id=u.job_id AND p.planner_version=u.planner_version JOIN import_jobs j ON j.id=u.job_id WHERE j.batch_id=? AND u.active=1",listOf(batchId))
        val attempts = db.query(
            """
            SELECT p.request_id, p.job_id, p.batch_id, p.unit_id, p.ordinal, p.target,
                   p.config_fingerprint, p.planner_version, p.result_version, p.cache_key,
                   p.state, p.reservation_tokens,
                   COALESCE(p.input_tokens, v.input_tokens) AS input_tokens,
                   COALESCE(p.output_tokens, v.output_tokens) AS output_tokens,
                   p.reasoning_tokens, p.failure_code, p.created_at, p.dispatched_at, p.terminal_at
            FROM pipeline_attempts p
            LEFT JOIN vision_attempts v ON v.request_id = p.request_id
            WHERE p.batch_id=?
            """.trimIndent(),
            listOf(batchId)
        ) + legacyAttempts(batchId)
        fun sum(field: String): Long? = attempts.mapNotNull { it.longOrNull(field) }.takeIf { it.isNotEmpty() }?.sum()
        val planned = rows.map { it.string("job_id") }.toSet()
        val completed = rows.groupBy { it.string("job_id") }.count { (_, units) -> units.all { it.long("published") == 1L } }
        return PipelineProgress(files=jobs.size,completedFiles=completed,pages=rows.map { it.string("job_id") to it.long("page") }.distinct().size,
            units=rows.size,pending=rows.count { it.string("state") in setOf("PLANNED","READY","CANCELLED") },inFlight=rows.count { it.string("state")=="DISPATCHED" },
            succeeded=rows.count { it.string("state")=="SUCCEEDED" },failed=rows.count { it.string("state")=="FAILED" },unknown=rows.count { it.string("state")=="UNKNOWN_OUTCOME" },
            published=rows.count { it.long("published")==1L },legacyUnplannedFiles=jobs.count { it.string("id") !in planned },
            usage=PipelineUsage(sum("input_tokens"),sum("output_tokens"),sum("reasoning_tokens"),attempts.size,attempts.count { it.longOrNull("input_tokens")==null || it.longOrNull("output_tokens")==null },attempts.sumOf(::chargedReservation)))
    }

    fun reuse(jobId: String, candidate: List<ProcessingUnit>, target: String, chunkVersion: String): PipelineReuseSummary {
        var direct=0; var local=0; var requests=0; var unknown=0
        val publishedTarget=db.query("SELECT vision_binding_json FROM import_jobs WHERE id=?",listOf(jobId)).singleOrNull()?.string("vision_binding_json")
        candidate.forEach { unit ->
            val prior = db.query("SELECT chunk_version,published FROM pipeline_units WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId)).singleOrNull()
            when {
                unknown(jobId,unit.unitId,unit.page) -> unknown++
                !unit.requiresVision || result(jobId,unit.unitId,target)!=null ->
                    if (prior?.string("chunk_version")==chunkVersion && prior.long("published")==1L && (!unit.requiresVision || publishedTarget==target)) direct++ else local++
                else -> requests++
            }
        }
        return PipelineReuseSummary(direct,local,requests,unknown,plannerVersion=candidate.firstOrNull()?.plannerVersion.orEmpty(),chunkVersion=chunkVersion)
    }

    companion object {
        val resultVersion = "$VISION_PROMPT_VERSION|$VISION_SCHEMA_VERSION|$VISION_PREPROCESS_VERSION"
        val schema = listOf(
            "CREATE TABLE IF NOT EXISTS pipeline_plans(job_id TEXT PRIMARY KEY,content_hash TEXT NOT NULL,planner_version TEXT NOT NULL,result_version TEXT NOT NULL,plan_hash TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pipeline_units(job_id TEXT NOT NULL,unit_id TEXT NOT NULL,planner_version TEXT NOT NULL,page INTEGER NOT NULL,requires_vision INTEGER NOT NULL,unit_json TEXT NOT NULL,state TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,published INTEGER NOT NULL DEFAULT 0,chunk_version TEXT,failure_code TEXT,failure_phase TEXT,PRIMARY KEY(job_id,unit_id))",
            "CREATE TABLE IF NOT EXISTS pipeline_results(job_id TEXT NOT NULL,unit_id TEXT NOT NULL,target TEXT NOT NULL,result_version TEXT NOT NULL,cache_key TEXT NOT NULL,asset_id TEXT NOT NULL,ocr TEXT NOT NULL,description TEXT NOT NULL,table_markdown TEXT NOT NULL,result_type TEXT NOT NULL,section TEXT,PRIMARY KEY(job_id,unit_id,target,result_version))",
            "CREATE TABLE IF NOT EXISTS pipeline_attempts(request_id TEXT PRIMARY KEY,job_id TEXT NOT NULL,batch_id TEXT,unit_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal>0),target TEXT NOT NULL,config_fingerprint TEXT NOT NULL,planner_version TEXT NOT NULL,result_version TEXT NOT NULL,cache_key TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('READY','DISPATCHED','SUCCEEDED','FAILED','CANCELLED','UNKNOWN_OUTCOME')),reservation_tokens INTEGER NOT NULL DEFAULT 0,input_tokens INTEGER,output_tokens INTEGER,reasoning_tokens INTEGER,failure_code TEXT,created_at TEXT NOT NULL,dispatched_at TEXT,terminal_at TEXT,UNIQUE(job_id,unit_id,ordinal))",
            "CREATE INDEX IF NOT EXISTS pipeline_attempts_batch ON pipeline_attempts(batch_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS pipeline_one_dispatch ON pipeline_attempts((1)) WHERE state IN ('READY','DISPATCHED')",
            "CREATE TABLE IF NOT EXISTS pipeline_retry_permits(unknown_attempt_id TEXT PRIMARY KEY,consumed INTEGER NOT NULL DEFAULT 0,replacement_request_id TEXT)",
            "CREATE TABLE IF NOT EXISTS pipeline_policies(batch_id TEXT PRIMARY KEY,max_concurrency INTEGER NOT NULL,failure_limit INTEGER NOT NULL,token_ceiling INTEGER,reservation_tokens INTEGER)",
            "CREATE TABLE IF NOT EXISTS pipeline_publications(job_id TEXT NOT NULL,planner_version TEXT NOT NULL,plan_hash TEXT NOT NULL,result_version TEXT NOT NULL,target TEXT NOT NULL,chunk_version TEXT NOT NULL,version_id TEXT NOT NULL,PRIMARY KEY(job_id,plan_hash,result_version,target,chunk_version))",
            "CREATE TRIGGER IF NOT EXISTS pipeline_attempt_terminal_immutable BEFORE UPDATE ON pipeline_attempts WHEN OLD.state IN ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN_OUTCOME') BEGIN SELECT RAISE(ABORT,'terminal pipeline attempt is immutable'); END",
            "CREATE TRIGGER IF NOT EXISTS pipeline_attempt_identity_immutable BEFORE UPDATE ON pipeline_attempts WHEN NEW.request_id<>OLD.request_id OR NEW.job_id<>OLD.job_id OR NEW.unit_id<>OLD.unit_id OR NEW.ordinal<>OLD.ordinal OR NEW.target<>OLD.target OR NEW.config_fingerprint<>OLD.config_fingerprint OR NEW.planner_version<>OLD.planner_version OR NEW.result_version<>OLD.result_version OR NEW.cache_key<>OLD.cache_key BEGIN SELECT RAISE(ABORT,'pipeline attempt identity is immutable'); END",
        )
    }
}
