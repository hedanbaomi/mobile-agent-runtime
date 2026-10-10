// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.ImportBatchProgress

/** Counters describe published truth; an UNKNOWN receipt never becomes successful progress. */
internal class KnowledgeBatchProgressStore(private val db: SqlConnection) {
    fun progress(batchId: String): ImportBatchProgress {
        val row = db.query("""
            SELECT b.staging_complete, b.total_items, COUNT(i.id) AS members,
                   SUM(CASE WHEN i.state='PUBLISHED' THEN 1 ELSE 0 END) AS published,
                   SUM(CASE WHEN i.state='PENDING' THEN 1 ELSE 0 END) AS pending,
                   SUM(CASE WHEN i.state='COPYING' THEN 1 ELSE 0 END) AS copying,
                   SUM(CASE WHEN i.state='QUEUED' THEN 1 ELSE 0 END) AS queued,
                   SUM(CASE WHEN i.state='PROCESSING' THEN 1 ELSE 0 END) AS processing,
                   SUM(CASE WHEN i.state='WAITING' THEN 1 ELSE 0 END) AS waiting,
                   SUM(CASE WHEN i.id IS NOT NULL AND (i.state='FAILED' OR i.state NOT IN
                       ('PUBLISHED','PENDING','COPYING','QUEUED','PROCESSING','WAITING','CANCELLED')) THEN 1 ELSE 0 END) AS failed,
                   SUM(CASE WHEN i.state IN('WAITING','FAILED') AND COALESCE(j.error,'') LIKE '%UNKNOWN_OUTCOME%' THEN 1 ELSE 0 END) AS unknown,
                   SUM(CASE WHEN i.state='CANCELLED' THEN 1 ELSE 0 END) AS cancelled
            FROM import_batches b LEFT JOIN import_items i ON i.batch_id=b.id
            LEFT JOIN import_jobs j ON j.id=i.job_id WHERE b.id=? GROUP BY b.id
        """.trimIndent(), listOf(batchId)).singleOrNull()
            ?: return ImportBatchProgress(0,0,0,0,0,0,0,0,0,0)
        val staged = row.long("staging_complete") == 1L
        val members = row.long("members").toInt()
        val unstaged = if (staged) 0 else (row.long("total_items").toInt() - members).coerceAtLeast(0)
        val copying = row.long("copying").toInt()
        return ImportBatchProgress(members + unstaged, row.long("published").toInt(),
            row.long("pending").toInt() + unstaged, if (staged) 0 else copying,
            row.long("queued").toInt() + if (staged) copying else 0,
            row.long("processing").toInt(), row.long("waiting").toInt(),
            row.long("failed").toInt(), row.long("unknown").toInt(), row.long("cancelled").toInt())
    }
}
