// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage

internal const val TEXT_ONLY_VISUAL_GAPS_PREFIX = "TEXT_ONLY_VISUAL_GAPS: "

/** Filter jobs in SQLite before decoding, so other knowledge bases do not affect the list. */
internal class KnowledgeJobDisplayStore(private val db: SqlConnection) {
    fun waitingForVisionCount(): Int =
        db.query(
            "SELECT COUNT(*) AS n FROM import_jobs WHERE stage = ?",
            listOf(ImportStage.WAITING_FOR_VISION_MODEL.name),
        ).singleOrNull()?.long("n")?.toInt() ?: 0

    fun find(id: String): ImportJob? =
        db.query("SELECT * FROM import_jobs WHERE id=?", listOf(id)).singleOrNull()?.importJob()

    fun latestInBatch(batchId: String): ImportJob? = db.query(
        "SELECT * FROM import_jobs WHERE batch_id=? ORDER BY updated_at DESC, id DESC LIMIT 1", listOf(batchId),
    ).singleOrNull()?.importJob()

    fun list(): List<Triple<ImportJob, String, String>> =
        views(db.query("SELECT * FROM import_jobs ORDER BY updated_at DESC"))

    fun list(knowledgeBaseId: String): List<Triple<ImportJob, String, String>> =
        views(db.query("SELECT * FROM import_jobs WHERE kb_id=? ORDER BY updated_at DESC", listOf(knowledgeBaseId)))

    private fun views(rows: List<SqlRow>) = rows.map { row ->
        Triple(row.importJob(), row.string("display_name"), row.string("updated_at"))
    }
}

/** Both display and resume use the same persisted job fields. */
internal fun SqlRow.importJob(
    visionConfigured: Boolean = false,
    stage: ImportStage = ImportStage.valueOf(string("stage")),
    documentId: String = string("document_id"),
    knowledgeBaseId: String = string("kb_id"),
): ImportJob {
    val error = string("error")
    return ImportJob(
        id = string("id"),
        knowledgeBaseId = knowledgeBaseId,
        documentId = documentId,
        stage = stage,
        hasImages = boolean("has_images"),
        visionConfigured = visionConfigured,
        visionConsent = boolean("vision_consent"),
        embeddingIsApi = boolean("embedding_is_api"),
        embeddingConsent = boolean("embedding_consent"),
        localEmbeddingAvailable = true,
        error = error.ifBlank { null },
        consentedVisionFingerprint = string("vision_binding_json").ifBlank { null },
        visualGapsAccepted = stage == ImportStage.READY_WITH_VISUAL_GAPS || error.startsWith(TEXT_ONLY_VISUAL_GAPS_PREFIX),
    )
}
