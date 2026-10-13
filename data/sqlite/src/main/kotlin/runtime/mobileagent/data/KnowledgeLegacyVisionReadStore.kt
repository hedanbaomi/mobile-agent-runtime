// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** One job-local projection; a null asset page preserves the legacy barrier for every page. */
internal fun readLegacyVisionUnknownPages(
    db: SqlConnection, jobId: String, documentId: String, target: String, legacyTarget: String?,
): Set<Long?> = db.query(
    """
    SELECT DISTINCT a.page FROM assets a
    JOIN vision_results v ON a.blob_hash=v.asset_hash AND a.surrounding_text_hash=v.context_hash
    WHERE a.document_id=? AND v.status='UNKNOWN_OUTCOME'
      AND (v.model_fingerprint=? OR v.model_fingerprint=?
        OR v.model_fingerprint=(SELECT vision_binding_json FROM import_jobs WHERE id=?)
        OR EXISTS(SELECT 1 FROM vision_attempts old WHERE old.job_id=? AND old.cache_key=v.cache_key))
    """.trimIndent(), listOf(documentId, target, legacyTarget, jobId, jobId),
).mapTo(hashSetOf()) { it.longOrNull("page") }
