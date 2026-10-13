// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.feature.knowledge.KnowledgeEmbeddingModelUi
import runtime.mobileagent.feature.knowledge.KnowledgeVisionTargetUi

internal data class KnowledgeDisplayMetadata(
    val visionTargets: List<KnowledgeVisionTargetUi>,
    val embeddingModels: List<KnowledgeEmbeddingModelUi>,
)

/** Fresh, refresh-local metadata. These labels are never an authorization cache. */
internal fun readKnowledgeDisplayMetadata(
    providers: () -> List<ProviderProfile>,
    models: () -> List<ModelProfile>,
): KnowledgeDisplayMetadata {
    val providerRows = providers()
    val modelRows = models()
    val byProvider = providerRows.associateBy { it.id }
    val embeddings = modelRows.filter { it.role == ModelRole.EMBEDDING }.mapNotNull { model ->
        byProvider[model.providerId]?.let { provider ->
            KnowledgeEmbeddingModelUi(model.id, "${provider.name} · ${model.modelId} · r${provider.revision}/${model.revision}")
        }
    }
    return KnowledgeDisplayMetadata(visionTargetOptions(providerRows, modelRows), embeddings)
}

/** Snapshot IDs keep stage changes harmless and exclude unrelated historical jobs. */
internal fun readKnowledgeWaitingBatchTargets(db: SqlConnection, knowledgeBaseId: String?, waitingJobIds: List<String>): Map<String, String?> {
    if (knowledgeBaseId == null || waitingJobIds.isEmpty()) return emptyMap()
    return buildMap {
        // One KB binding plus at most 800 IDs stays below SQLite's legacy 999 limit.
        waitingJobIds.distinct().chunked(800).forEach { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            db.query(
                """SELECT j.id AS job_id,b.vision_target AS vision_target
                   FROM import_jobs j LEFT JOIN import_batches b ON b.id=j.batch_id
                   WHERE j.kb_id=? AND j.id IN ($placeholders)""".trimIndent(),
                listOf(knowledgeBaseId) + ids,
            ).forEach { row -> put(row.string("job_id"), row.string("vision_target").ifBlank { null }) }
        }
    }
}
