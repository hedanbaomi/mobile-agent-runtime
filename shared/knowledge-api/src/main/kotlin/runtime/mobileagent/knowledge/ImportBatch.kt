// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

enum class ImportBatchKind { FILES, FOLDER, ZIP }

enum class ImportBatchState {
    STAGING,
    COPYING,
    PROCESSING,
    WAITING,
    PAUSED,
    CANCELLED,
    FAILED,
    COMPLETED,
}

enum class ImportItemState {
    PENDING,
    COPYING,
    QUEUED,
    PROCESSING,
    WAITING,
    FAILED,
    CANCELLED,
    PUBLISHED,
}

data class ImportBatch(
    val id: String,
    val knowledgeBaseId: String,
    val generationId: String?,
    val kind: ImportBatchKind,
    val displayName: String,
    var state: ImportBatchState = ImportBatchState.STAGING,
    var totalItems: Int = 0,
    var copied: Int = 0,
    var processing: Int = 0,
    var waiting: Int = 0,
    var failed: Int = 0,
    var error: String? = null,
)

data class ImportItem(
    val id: String,
    val batchId: String,
    val itemKey: String,
    val relativePath: String,
    val jobId: String? = null,
    var kind: String = "FILE",
    var state: ImportItemState = ImportItemState.PENDING,
    var attemptCount: Int = 0,
    var error: String? = null,
)

data class KnowledgeArchiveEntry(
    val name: String,
    val size: Long,
    val format: SourceFormat,
)

data class KnowledgeArchiveSummary(
    val ok: Boolean,
    val reason: String,
    val entries: List<KnowledgeArchiveEntry> = emptyList(),
    val totalUncompressed: Long = 0,
)

data class ConsumedConsentTicket(
    val kind: String,
    val jobId: String?,
    val knowledgeBaseId: String,
    val fingerprint: String,
)
