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
}
