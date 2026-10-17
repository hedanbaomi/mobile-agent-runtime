// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.ImportBatchItemView
import runtime.mobileagent.knowledge.PipelinePolicy

data class KnowledgeBatchDisplay(
    val stagingComplete: Long? = null,
    val items: List<ImportBatchItemView> = emptyList(),
    val policy: PipelinePolicy = PipelinePolicy(),
)

/**
 * The counters and flags a batch card shows while its member rows are not materialized.
 *
 * Every field is computed by the summary query, so a card states its real numbers before - and
 * without - reading a single member row: a batch whose rows were never loaded can never be
 * mistaken for a batch that has no members.
 */
data class KnowledgeBatchSummary(
    val stagingComplete: Long? = null,
    /** Real member count of this batch, straight from import_items. */
    val itemCount: Int = 0,
    /** A member is queued or processing while its own job already failed: an automatic retry waits. */
    val retryWaiting: Boolean = false,
    /** First member error carrying UNKNOWN_OUTCOME in member order; null when this batch has none. */
    val unknownOutcomeReason: String? = null,
    val policy: PipelinePolicy = PipelinePolicy(),
)

/**
 * The member state one card shows.
 *
 * A batch whose staging is complete reports a member whose bytes are still being copied as QUEUED,
 * exactly like the full read does, so the summary counters and the opened rows can never disagree
 * about the same member.  Kept as one fragment because the aggregate and the detail read must apply
 * precisely this rule.
 */
private const val EFFECTIVE_ITEM_STATE =
    "CASE WHEN b.staging_complete=1 AND i.job_id IS NOT NULL AND i.job_id<>'' " +
        "AND i.state IN ('PENDING','COPYING') THEN 'QUEUED' ELSE i.state END"

/** Read-only, refresh-local display facts. Execution still reads its canonical stores. */
class KnowledgeBatchDisplayStore(private val db: SqlConnection) {
    fun read(knowledgeBaseId: String?, batchIds: List<String>): Map<String, KnowledgeBatchDisplay> {
        if (knowledgeBaseId == null || batchIds.isEmpty()) return emptyMap()
        return db.transaction {
            buildMap {
                batchIds.distinct().chunked(800).forEach { ids ->
                    val requested = ids.joinToString(",") { "(?)" }
                    val args = ids + knowledgeBaseId
                    val metadata = db.query("""
                        WITH requested(id) AS (VALUES $requested)
                        SELECT r.id AS batch_id,b.id AS stored_batch_id,b.staging_complete,
                               p.batch_id AS policy_batch_id,p.max_concurrency,p.failure_limit,p.token_ceiling,p.reservation_tokens
                        FROM requested r LEFT JOIN import_batches b ON b.id=r.id
                        LEFT JOIN pipeline_policies p ON p.batch_id=r.id
                        WHERE b.id IS NULL OR b.kb_id=?
                    """.trimIndent(), args)
                    val items = db.query("""
                        WITH requested(id) AS (VALUES $requested)
                        SELECT i.batch_id,i.job_id,COALESCE(NULLIF(j.display_name,''),i.relative_path) AS display_name,
                               i.state,i.error
                        FROM requested r JOIN import_items i ON i.batch_id=r.id
                        LEFT JOIN import_batches b ON b.id=r.id LEFT JOIN import_jobs j ON j.id=i.job_id
                        WHERE b.id IS NULL OR b.kb_id=? ORDER BY i.batch_id,i.rowid
                    """.trimIndent(), args).groupBy { it.string("batch_id") }
                    metadata.forEach { row ->
                        val id = row.string("batch_id")
                        val staged = if (row.string("stored_batch_id").isEmpty()) null else row.long("staging_complete")
                        val policy = if (row.string("policy_batch_id").isEmpty()) PipelinePolicy() else
                            PipelinePolicy(row.long("max_concurrency").toInt(),row.long("failure_limit").toInt(),
                                row.longOrNull("token_ceiling"),row.longOrNull("reservation_tokens"))
                        val views = items[id].orEmpty().map { item ->
                            val jobId = item.string("job_id").ifBlank { null }
                            val state = item.string("state")
                            ImportBatchItemView(jobId, item.string("display_name"),
                                if (staged == 1L && jobId != null && state in setOf("PENDING", "COPYING")) "QUEUED" else state,
                                item.string("error").ifBlank { null })
                        }
                        put(id, KnowledgeBatchDisplay(staged, views, policy))
                    }
                }
            }
        }
    }

    /**
     * Summary-only read for a snapshot that shows collapsed cards.
     *
     * It aggregates every requested batch inside one transaction and never materializes a member
     * row, so the returned objects follow the batch count. SQLite still aggregates matching
     * members. Member rows are read afterwards, by exact batch id, only for the batches the user
     * actually opened.
     */
    fun summaries(knowledgeBaseId: String?, batchIds: List<String>): Map<String, KnowledgeBatchSummary> {
        if (knowledgeBaseId == null || batchIds.isEmpty()) return emptyMap()
        return db.transaction {
            buildMap {
                batchIds.distinct().chunked(800).forEach { ids ->
                    val requested = ids.joinToString(",") { "(?)" }
                    val metadata = db.query("""
                        WITH requested(id) AS (VALUES $requested)
                        SELECT r.id AS batch_id,b.id AS stored_batch_id,b.staging_complete,
                               p.batch_id AS policy_batch_id,p.max_concurrency,p.failure_limit,p.token_ceiling,p.reservation_tokens
                        FROM requested r LEFT JOIN import_batches b ON b.id=r.id
                        LEFT JOIN pipeline_policies p ON p.batch_id=r.id
                        WHERE b.id IS NULL OR b.kb_id=?
                    """.trimIndent(), ids + knowledgeBaseId)
                    // LEFT JOIN keeps a member of a batch row that is already gone visible, exactly
                    // like the full read, and then reports it as not fully staged.
                    val aggregates = db.query("""
                        WITH requested(id) AS (VALUES $requested)
                        SELECT i.batch_id AS batch_id,COUNT(*) AS item_count,
                               SUM(CASE WHEN $EFFECTIVE_ITEM_STATE IN ('QUEUED','PROCESSING') AND j.stage='FAILED'
                                   THEN 1 ELSE 0 END) AS retry_waiting
                        FROM requested r JOIN import_items i ON i.batch_id=r.id
                        LEFT JOIN import_batches b ON b.id=i.batch_id
                        LEFT JOIN import_jobs j ON j.id=i.job_id
                        GROUP BY i.batch_id
                    """.trimIndent(), ids).associateBy { it.string("batch_id") }
                    // MIN(rowid) keeps the "first member error" order the card already showed.
                    val reasons = db.query("""
                        WITH requested(id) AS (VALUES $requested),
                             flagged AS (SELECT i.batch_id AS batch_id,MIN(i.rowid) AS first_rowid
                                         FROM requested r JOIN import_items i ON i.batch_id=r.id
                                         WHERE instr(COALESCE(i.error,''),'UNKNOWN_OUTCOME') > 0
                                         GROUP BY i.batch_id)
                        SELECT f.batch_id AS batch_id,i.error AS unknown_reason
                        FROM flagged f JOIN import_items i ON i.rowid=f.first_rowid
                    """.trimIndent(), ids).associateBy { it.string("batch_id") }
                    metadata.forEach { row ->
                        val id = row.string("batch_id")
                        val staged = if (row.string("stored_batch_id").isEmpty()) null else row.long("staging_complete")
                        val policy = if (row.string("policy_batch_id").isEmpty()) PipelinePolicy() else
                            PipelinePolicy(row.long("max_concurrency").toInt(),row.long("failure_limit").toInt(),
                                row.longOrNull("token_ceiling"),row.longOrNull("reservation_tokens"))
                        val aggregate = aggregates[id]
                        put(id, KnowledgeBatchSummary(
                            stagingComplete = staged,
                            itemCount = aggregate?.long("item_count")?.toInt() ?: 0,
                            retryWaiting = (aggregate?.long("retry_waiting") ?: 0L) > 0L,
                            unknownOutcomeReason = reasons[id]?.string("unknown_reason")?.ifBlank { null },
                            policy = policy,
                        ))
                    }
                }
            }
        }
    }

    /**
     * The member rows of exactly one batch, read by its id.
     *
     * The user opens one batch at a time, so this stays one query bounded by that batch and can
     * never pull another batch's members into the opened card.
     */
    fun details(batchId: String): List<ImportBatchItemView> = db.query(
        """
        SELECT i.job_id AS job_id,COALESCE(NULLIF(j.display_name,''),i.relative_path) AS display_name,
               $EFFECTIVE_ITEM_STATE AS state,i.error AS error
        FROM import_items i LEFT JOIN import_batches b ON b.id=i.batch_id
        LEFT JOIN import_jobs j ON j.id=i.job_id WHERE i.batch_id=? ORDER BY i.rowid
        """.trimIndent(), listOf(batchId),
    ).map { row ->
        ImportBatchItemView(row.string("job_id").ifBlank { null }, row.string("display_name"),
            row.string("state"), row.string("error").ifBlank { null })
    }

    /**
     * Job ids owned by a batch of this knowledge base, so a collapsed card can keep its members out
     * of the legacy job list without reading a single member row.  One query, one column.
     */
    fun memberJobIds(knowledgeBaseId: String?): Set<String> {
        if (knowledgeBaseId == null) return emptySet()
        return db.query(
            "SELECT DISTINCT i.job_id AS job_id FROM import_items i JOIN import_batches b ON b.id=i.batch_id " +
                "WHERE b.kb_id=? AND i.job_id IS NOT NULL AND i.job_id<>''",
            listOf(knowledgeBaseId),
        ).mapNotNull { it.string("job_id").ifBlank { null } }.toSet()
    }
}
