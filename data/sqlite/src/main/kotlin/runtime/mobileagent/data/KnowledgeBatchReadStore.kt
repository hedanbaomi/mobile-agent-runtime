// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.ImportBatch
import runtime.mobileagent.knowledge.ImportBatchBlockReason
import runtime.mobileagent.knowledge.ImportBatchItemView
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState

/** Canonical persisted batch rows. Callers retain ownership of transactions and the index lock. */
internal class KnowledgeBatchReadStore(private val db: SqlConnection) {
    fun list(knowledgeBaseId: String): List<ImportBatch> =
        db.query("SELECT * FROM import_batches WHERE kb_id = ? ORDER BY updated_at DESC", listOf(knowledgeBaseId))
            .map(::batchFromRow)

    fun find(batchId: String): ImportBatch? =
        db.query("SELECT * FROM import_batches WHERE id = ?", listOf(batchId)).singleOrNull()?.let(::batchFromRow)

    private fun batchFromRow(row: SqlRow): ImportBatch = ImportBatch(
        id = row.string("id"),
        knowledgeBaseId = row.string("kb_id"),
        generationId = row.string("generation_id").ifBlank { null },
        kind = ImportBatchKind.valueOf(row.string("kind")),
        displayName = row.string("display_name"),
        state = ImportBatchState.valueOf(row.string("state")),
        totalItems = row.long("total_items").toInt(),
        copied = row.long("copied").toInt(),
        processing = row.long("processing").toInt(),
        waiting = row.long("waiting").toInt(),
        failed = row.long("failed").toInt(),
        error = row.string("error").ifBlank { null },
        published = row.long("published_items").toInt(),
        unknown = row.long("unknown_items").toInt(),
        visionTarget = row.string("vision_target").ifBlank { null },
        visionAuthorizedAt = row.string("vision_authorized_at").ifBlank { null },
        pausedAt = row.string("paused_at").ifBlank { null },
        blockedReason = row.string("blocked_reason").ifBlank { null }
            ?.let { reason -> runCatching { ImportBatchBlockReason.valueOf(reason) }.getOrNull() },
    )

    fun itemViews(batchId: String): List<ImportBatchItemView> {
        val fullyStaged = db.query("SELECT staging_complete FROM import_batches WHERE id = ?", listOf(batchId))
            .singleOrNull()?.long("staging_complete") == 1L
        return db.query(
            "SELECT i.job_id AS job_id, COALESCE(NULLIF(j.display_name, ''), i.relative_path) AS display_name, " +
                "i.state AS state, i.error AS error FROM import_items i " +
                "LEFT JOIN import_jobs j ON j.id = i.job_id WHERE i.batch_id = ? ORDER BY i.rowid",
            listOf(batchId),
        ).map { it.batchItemView(fullyStaged) }
    }
}
