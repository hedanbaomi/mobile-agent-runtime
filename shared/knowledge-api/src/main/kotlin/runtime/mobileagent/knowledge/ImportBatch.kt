// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

enum class ImportBatchKind { FILES, FOLDER, ZIP }

enum class ImportBatchState {
    STAGING,
    COPYING,
    PROCESSING,
    WAITING,

    /** Explicit user stop.  A paused batch survives process death and is never auto-resumed. */
    PAUSED,

    /** The batch stopped because one of its own items needs a Vision target the user has not chosen. */
    BLOCKED,
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

/**
 * Closed reason codes for a [ImportBatchState.BLOCKED] batch.  They are stable, provider-neutral and
 * safe to log; they never contain a file name, path or provider credential.
 */
enum class ImportBatchBlockReason {
    /** A scanned item needs visual processing but no Vision target is configured. */
    NEEDS_VISION_MODEL,

    /** A scanned item needs visual processing and the selected Vision destination changed. */
    VISION_TARGET_CHANGED,

    /** The document references image bytes that were not imported; configuring a model cannot resolve them. */
    MISSING_VISUAL_SOURCE,
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
    /** Items whose text/visual work reached a published, searchable index version. */
    var published: Int = 0,
    /** Items whose external outcome is unknown and must not be replayed automatically. */
    var unknown: Int = 0,
    /** Vision destination fingerprint this batch was authorized against, if any. */
    var visionTarget: String? = null,
    var visionAuthorizedAt: String? = null,
    var pausedAt: String? = null,
    var blockedReason: ImportBatchBlockReason? = null,
)

/**
 * Honest, derived progress for one batch.  Completion is only ever reported from [published]; copied
 * bytes and queued items are reported separately and never presented as finished work.
 */
data class ImportBatchProgress(
    val total: Int,
    val published: Int,
    val pending: Int,
    val copying: Int,
    val queued: Int,
    val processing: Int,
    val waiting: Int,
    val failed: Int,
    val unknown: Int,
    val cancelled: Int,
) {
    /** Items whose bytes are already in the local CAS (everything that is not still PENDING). */
    val copied: Int get() = (total - pending).coerceAtLeast(0)

    /** Items the coordinator has already started or finished at least once. */
    val started: Int get() = copied

    /** True while nothing is pending and nothing is being copied or worked on. */
    val idle: Boolean get() = pending == 0 && copying == 0 && queued == 0 && processing == 0

    /** Completed share of this batch, or null while nothing has been published yet. */
    fun percentComplete(): Int? = if (total <= 0) null else published * 100 / total
}

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

/**
 * Stable identity of an authorized import scope.
 *
 * The scope is the ordered list of (item key, content hash) pairs the user actually selected.  It is
 * deliberately independent of the knowledge base generation, of parser output and of
 * `documents.active_version_id`, so ordinary progress inside the batch can never invalidate the
 * authorization it already received.  Adding a new file to the knowledge base does not widen it
 * either: the hash only covers members of this batch.
 */
object ImportBatchScope {
    fun hash(members: List<Pair<String, String>>): String = sha256Hex(
        members.joinToString("\n") { (key, contentHash) -> "$key|$contentHash" }.toByteArray(Charsets.UTF_8),
    )
}

/**
 * Minimal, redacted batch lifecycle event.
 *
 * Only opaque, app-generated references and closed reason codes are carried.  File names, paths,
 * URIs, provider credentials and any document text are deliberately absent, and the event carries
 * no provider body.  [batchRef]/[itemRef] are the durable batch/job identifiers, which are random
 * and referential rather than descriptive, so a log can be correlated without exposing content.
 */
data class ImportBatchEvent(
    val batchRef: String,
    val itemRef: String?,
    val attempt: Int,
    val phase: ImportBatchEventPhase,
    val reasonCode: String,
    val count: Int = 0,
)

enum class ImportBatchEventPhase {
    ACTION_RECEIVED,
    STATE_CHANGED,
    TARGET_RESOLVED,
    AUTHORIZATION_SAVED,
    ENQUEUED,
    STARTED,
    DISPATCHED,
    RESPONDED,
    CHECKPOINT,
    BLOCKED,
    FAILED,
}

/** One line of the user-opened batch detail.  Never produced for diagnostics. */
data class ImportBatchItemView(
    val jobId: String?,
    val displayName: String,
    val state: String,
    val error: String?,
)
