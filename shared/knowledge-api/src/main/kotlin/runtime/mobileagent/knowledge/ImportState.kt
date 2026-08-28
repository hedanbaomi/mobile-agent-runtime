// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

enum class ImportStage {
    QUEUED,
    HASHING,
    COPYING,
    PARSING,
    WAITING_FOR_VISION_MODEL,
    AWAITING_UPLOAD_CONSENT,
    VISION_PROCESSING,
    CHUNKING,
    SELECT_EMBEDDING_BACKEND,
    AWAITING_EMBEDDING_CONSENT,
    EMBEDDING,
    INDEXING,
    READY,
    PAUSED,
    RETRY_WAIT,
    FAILED,
    CANCELLED,
}

data class ImportJob(
    val id: String,
    val knowledgeBaseId: String,
    val documentId: String,
    var stage: ImportStage = ImportStage.QUEUED,
    var hasImages: Boolean = false,
    var visionConfigured: Boolean = false,
    var visionConsent: Boolean = false,
    var embeddingIsApi: Boolean = false,
    var embeddingConsent: Boolean = false,
    var localEmbeddingAvailable: Boolean = true,
    var error: String? = null,
)

object ImportStateMachine {
    fun advance(job: ImportJob): ImportStage {
        job.stage = when (job.stage) {
            ImportStage.QUEUED -> ImportStage.HASHING
            ImportStage.HASHING -> ImportStage.COPYING
            ImportStage.COPYING -> ImportStage.PARSING
            ImportStage.PARSING -> if (job.hasImages) {
                if (!job.visionConfigured) ImportStage.WAITING_FOR_VISION_MODEL else ImportStage.AWAITING_UPLOAD_CONSENT
            } else {
                ImportStage.CHUNKING
            }
            ImportStage.WAITING_FOR_VISION_MODEL ->
                if (job.visionConfigured) ImportStage.AWAITING_UPLOAD_CONSENT else ImportStage.WAITING_FOR_VISION_MODEL
            ImportStage.AWAITING_UPLOAD_CONSENT ->
                if (job.visionConsent) ImportStage.VISION_PROCESSING else ImportStage.AWAITING_UPLOAD_CONSENT
            ImportStage.VISION_PROCESSING -> ImportStage.CHUNKING
            ImportStage.CHUNKING -> ImportStage.SELECT_EMBEDDING_BACKEND
            ImportStage.SELECT_EMBEDDING_BACKEND -> when {
                job.embeddingIsApi && !job.embeddingConsent -> ImportStage.AWAITING_EMBEDDING_CONSENT
                job.embeddingIsApi && job.embeddingConsent -> ImportStage.EMBEDDING
                job.localEmbeddingAvailable -> ImportStage.EMBEDDING
                else -> ImportStage.PAUSED
            }
            ImportStage.AWAITING_EMBEDDING_CONSENT ->
                if (job.embeddingConsent) ImportStage.EMBEDDING else ImportStage.AWAITING_EMBEDDING_CONSENT
            ImportStage.EMBEDDING -> ImportStage.INDEXING
            ImportStage.INDEXING -> ImportStage.READY
            else -> job.stage
        }
        return job.stage
    }

    fun isCompleteSuccess(job: ImportJob): Boolean = job.stage == ImportStage.READY
}
