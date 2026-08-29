// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.ApiQueryAttempt
import runtime.mobileagent.knowledge.ApiQueryUnknownOutcomeException
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.BatchTextEmbedder
import runtime.mobileagent.knowledge.CancellableBatchTextEmbedder
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.knowledge.CitationMap
import runtime.mobileagent.knowledge.CjkLexical
import runtime.mobileagent.knowledge.CosineIndex
import runtime.mobileagent.knowledge.EmbeddingUnknownOutcomeException
import runtime.mobileagent.knowledge.EvidenceLocator
import runtime.mobileagent.knowledge.ExtractedAsset
import runtime.mobileagent.knowledge.ExtractedPage
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportBatch
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportItemState
import runtime.mobileagent.knowledge.ConsumedConsentTicket
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.KnowledgeArchive
import runtime.mobileagent.knowledge.ImportStateMachine
import runtime.mobileagent.knowledge.MediaKind
import runtime.mobileagent.knowledge.OfficeParser
import runtime.mobileagent.knowledge.ParsedPublication
import runtime.mobileagent.knowledge.PdfParser
import runtime.mobileagent.knowledge.PdfPageRasterizer
import runtime.mobileagent.knowledge.ReciprocalRankFusion
import runtime.mobileagent.knowledge.RetrievalResult
import runtime.mobileagent.knowledge.SearchHit
import runtime.mobileagent.knowledge.SourceFormat
import runtime.mobileagent.knowledge.StoredBlob
import runtime.mobileagent.knowledge.TextChunker
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.VISION_PROMPT_VERSION
import runtime.mobileagent.knowledge.VISION_SCHEMA_VERSION
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionBinding
import runtime.mobileagent.knowledge.VisionCacheKey
import runtime.mobileagent.knowledge.VisionInput
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VectorIndexFactory
import runtime.mobileagent.knowledge.ZipSafety
import runtime.mobileagent.knowledge.sha256Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class KnowledgeRepository(
    private val db: SqlConnection,
    private val blobs: BlobSink,
    private val embedder: TextEmbedder = HashingTextEmbedder(),
    private val vision: VisionBackend? = null,
    private val visionModelFingerprint: String = "vision-unconfigured",
    private val visionBinding: () -> VisionBinding? = { null },
    private val pdfRasterizer: PdfPageRasterizer? = null,
    /** Optional explicitly selected remote/API text embedding adapter. */
    private val apiEmbedder: TextEmbedder? = null,
    /** Additional explicitly selected API adapters, keyed by their immutable spaceId. */
    private val apiEmbedders: List<TextEmbedder> = emptyList(),
    /** Optional Android ANN implementation; JVM callers use CosineIndex. */
    private val vectorIndexFactory: VectorIndexFactory? = null,
    /** Resolves a newly selected API adapter by its exact persisted space id. */
    private val apiEmbedderResolver: (String) -> TextEmbedder? = { null },
) {
    private val indexLock = Any()
    private val configuredApiEmbedders: List<TextEmbedder> =
        listOfNotNull(apiEmbedder) + apiEmbedders

    init {
        require(configuredApiEmbedders.none { it.spaceId == embedder.spaceId }) {
            "An API embedding adapter must not reuse the local embedding spaceId"
        }
        require(configuredApiEmbedders.map { it.spaceId }.distinct().size == configuredApiEmbedders.size) {
            "Each API embedding adapter must have a unique fixed spaceId"
        }
        configuredApiEmbedders.forEach { adapter ->
            require(adapter.spaceId.isNotBlank()) { "API embedding adapter spaceId must not be blank" }
            require(adapter.dimension > 0) { "API embedding adapter dimension must be positive" }
        }
    }

    fun ensureDefaultBase(): String {
        val existing = db.query("SELECT id FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1")
        // The ordering/limit is an optimization, not a uniqueness contract:
        // an already populated database may contain multiple live bases.
        if (existing.isNotEmpty()) return existing.first().string("id")
        return createKnowledgeBase("On-device library", DEFAULT_KB_ID)
    }

    fun createKnowledgeBase(
        name: String,
        id: String = EntityId.random().value,
        embeddingSpaceId: String = embedder.spaceId,
    ): String {
        require(embeddingSpaceId.isNotBlank()) { "embeddingSpaceId must not be blank" }
        db.execute(
            "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES (?,?,?,?,?,?)",
            listOf(id, name, null, embeddingSpaceId, Utc.nowIso(), null),
        )
        return id
    }

    /**
     * Create a KB with a user-selected, fully specified API binding.  The
     * adapter must already be registered by AppContainer; this method never
     * discovers a provider or makes a network call.
     */
    fun createKnowledgeBase(
        name: String,
        binding: ApiEmbeddingBinding,
        id: String = EntityId.random().value,
    ): String {
        check(apiEmbedderForSpace(binding.spaceId) != null) {
            "No API embedding adapter is registered for the selected binding"
        }
        return createKnowledgeBase(name, id, binding.spaceId)
    }

    /** Explicit name for AppContainer/KnowledgeVM call sites. */
    fun createApiKnowledgeBase(
        name: String,
        binding: ApiEmbeddingBinding,
        id: String = EntityId.random().value,
    ): String = createKnowledgeBase(name, binding, id)

    /**
     * Rebind one KB only after a fresh explicit API consent.  A new generation
     * is built under the new space before it becomes active; a failed build
     * leaves the prior binding and active generation intact through the DB
     * transaction.
     */
    fun rebindApiKnowledgeBase(
        knowledgeBaseId: String,
        binding: ApiEmbeddingBinding,
        embeddingConsent: Boolean,
        acknowledgeDuplicateCharge: Boolean = false,
    ): String = runBlocking {
        rebindApiKnowledgeBaseCancellable(
            knowledgeBaseId = knowledgeBaseId,
            binding = binding,
            embeddingConsent = embeddingConsent,
            acknowledgeDuplicateCharge = acknowledgeDuplicateCharge,
        )
    }

    /** Read the immutable space identity selected for a KB for UI confirmation. */
    fun embeddingSpaceId(knowledgeBaseId: String): String? {
        requireKb(knowledgeBaseId)
        return db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(knowledgeBaseId),
        ).singleOrNull()?.string("embedding_space_id")?.ifBlank { null }
    }

    /** Read-only signal for UI to route an uncertain rebind to reconfiguration. */
    fun hasUnknownApiRebind(knowledgeBaseId: String): Boolean = synchronized(indexLock) {
        requireKb(knowledgeBaseId)
        unknownRebindGates(knowledgeBaseId).isNotEmpty()
    }

    /**
     * List query embedding attempts which still need an explicit user action.
     * Query text is never persisted or returned; callers receive only the
     * complete target space and its SHA-256 key.
     */
    fun pendingApiQueries(knowledgeBaseId: String): List<ApiQueryAttempt> = synchronized(indexLock) {
        requireKb(knowledgeBaseId)
        db.query(
            "SELECT kb_id, space_id, query_hash, retry_authorized, error, updated_at FROM embedding_query_attempts WHERE kb_id = ? ORDER BY updated_at, query_hash",
            listOf(knowledgeBaseId),
        ).map(::apiQueryAttempt)
    }

    /**
     * Grant one retry for a pending API query. The grant is consumed
     * atomically by the next matching [retrieve] call and cannot be reused.
     */
    fun authorizeApiQueryRetry(
        knowledgeBaseId: String,
        spaceId: String,
        queryHash: String,
        acknowledgeDuplicateCharge: Boolean,
    ): ApiQueryAttempt = synchronized(indexLock) {
        check(acknowledgeDuplicateCharge) {
            "Retry may bill the embedding provider twice. Acknowledge the duplicate-charge risk."
        }
        requireKb(knowledgeBaseId)
        requireQueryHash(queryHash)
        check(ApiEmbeddingBinding.parseSpaceId(spaceId) != null) {
            "API query retry requires a complete current embedding binding"
        }
        val currentSpace = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ? AND deleted_at IS NULL",
            listOf(knowledgeBaseId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        check(currentSpace == spaceId) {
            "API query retry target no longer matches the knowledge base binding"
        }
        check(currentSpace != embedder.spaceId) { "API query retry requires an API embedding space" }
        check(hasApiEmbeddingConsent(knowledgeBaseId)) {
            "API query retry requires persisted text embedding consent"
        }
        db.transaction {
            val row = db.query(
                "SELECT kb_id, space_id, query_hash, retry_authorized, error, updated_at FROM embedding_query_attempts WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
                listOf(knowledgeBaseId, spaceId, queryHash),
            ).singleOrNull() ?: error("No pending API query attempt exists for this key")
            check(!row.boolean("retry_authorized")) {
                "This API query retry authorization has already been consumed or granted"
            }
            db.execute(
                "UPDATE embedding_query_attempts SET retry_authorized = 1, updated_at = ? WHERE kb_id = ? AND space_id = ? AND query_hash = ? AND retry_authorized = 0",
                listOf(Utc.nowIso(), knowledgeBaseId, spaceId, queryHash),
            )
            val updated = db.query(
                "SELECT kb_id, space_id, query_hash, retry_authorized, error, updated_at FROM embedding_query_attempts WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
                listOf(knowledgeBaseId, spaceId, queryHash),
            ).singleOrNull() ?: error("API query retry authorization row disappeared")
            check(updated.boolean("retry_authorized")) {
                "API query retry authorization was not atomically recorded"
            }
            apiQueryAttempt(updated)
        }
    }

    fun listKnowledgeBases(): List<Pair<String, String>> =
        db.query("SELECT id, name FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY created_at")
            .map { it.string("id") to it.string("name") }

    /**
     * Suspending API import entry point.  Parsing, CAS writes, cache commits,
     * and short SQLite transitions remain synchronous, but the selected API
     * adapter is called only by [executeEmbeddingOperation] outside
     * [indexLock].  App/Worker callers should prefer this method for API KBs.
     */
    suspend fun importBytesCancellable(
        displayName: String,
        mediaType: String,
        bytes: ByteArray,
        visionConfigured: Boolean,
        knowledgeBaseId: String? = null,
        pauseAt: ImportStage? = null,
        visionConsent: Boolean = false,
        embeddingIsApi: Boolean = false,
        embeddingConsent: Boolean = false,
    ): ImportJob {
        val kbId = knowledgeBaseId ?: ensureDefaultBase()
        requireKb(kbId)
        validateRequestedEmbeddingSelection(kbId, embeddingIsApi, embeddingConsent)
        check(embeddingIsApi) {
            "The suspending import entry point is reserved for an explicitly selected API embedding"
        }
        val format = MediaKind.detect(displayName, mediaType, bytes.copyOf(minOf(bytes.size, 64)))
        if (format == SourceFormat.KNOWLEDGE_ARCHIVE) {
            require(bytes.size <= KnowledgeArchive.MAX_TOTAL_BYTES) { "RESOURCE_LIMIT" }
            return expandKnowledgeArchive(
                displayName, bytes, visionConfigured, kbId, visionConsent, embeddingIsApi, embeddingConsent,
                pauseAt,
            )
        }
        require(bytes.size <= MediaKind.MAX_IMPORT_BYTES) { "RESOURCE_LIMIT" }
        val stored = blobs.put(bytes, mediaType.ifBlank { guessedMime(format) })
        val existingId = existingDocument(kbId, stored.sha256)
        if (existingId != null) {
            val prior = db.query("SELECT active_version_id, deleted_at FROM documents WHERE id = ?", listOf(existingId)).single()
            if (prior.string("deleted_at").isBlank()) {
                val unknownEmbedding = db.query(
                    "SELECT error FROM import_jobs WHERE document_id = ? AND embedding_is_api = 1 ORDER BY updated_at DESC LIMIT 1",
                    listOf(existingId),
                ).firstOrNull()?.string("error")?.takeIf { it.contains("UNKNOWN_OUTCOME", ignoreCase = true) }
                    ?.takeIf { it.contains("embedding", ignoreCase = true) }
                if (unknownEmbedding != null) {
                    val job = ImportJob(
                        id = EntityId.random().value,
                        knowledgeBaseId = kbId,
                        documentId = existingId,
                        stage = ImportStage.FAILED,
                        visionConfigured = visionConfigured,
                        visionConsent = visionConsent,
                        embeddingIsApi = true,
                        embeddingConsent = embeddingConsent,
                        localEmbeddingAvailable = true,
                        error = API_EMBEDDING_UNKNOWN_ERROR,
                    )
                    persistJob(job, displayName)
                    return job
                }
                if (isPublishedReady(existingId, kbId, requestedApi = true)) {
                    val job = ImportJob(
                        id = EntityId.random().value,
                        knowledgeBaseId = kbId,
                        documentId = existingId,
                        stage = ImportStage.READY,
                        visionConfigured = visionConfigured,
                        visionConsent = visionConsent,
                        embeddingIsApi = true,
                        embeddingConsent = embeddingConsent,
                        localEmbeddingAvailable = true,
                    )
                    persistJob(job, displayName)
                    return job
                }
                val job = ImportJob(
                    id = EntityId.random().value,
                    knowledgeBaseId = kbId,
                    documentId = existingId,
                    hasImages = MediaKind.isImage(format),
                    visionConfigured = visionConfigured,
                    visionConsent = visionConsent,
                    embeddingIsApi = true,
                    embeddingConsent = embeddingConsent,
                    localEmbeddingAvailable = true,
                )
                persistJob(job, displayName)
                if (pauseAt == ImportStage.COPYING) {
                    job.stage = ImportStage.COPYING
                    persistJob(job, displayName)
                    return job
                }
                return continueImportCancellable(job, displayName, bytes, format)
            }
        }
        synchronized(indexLock) {
            db.transaction {
                upsertBlob(stored)
                val documentId = existingId ?: EntityId.random().value
                db.execute(
                    "INSERT OR REPLACE INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES (?,?,?,?,?,?,?)",
                    listOf(documentId, kbId, stored.sha256, displayName, format.name, null, null),
                )
                syncBlobRef(stored.sha256)
            }
        }
        val documentId = existingDocument(kbId, stored.sha256)!!
        val job = ImportJob(
            id = EntityId.random().value,
            knowledgeBaseId = kbId,
            documentId = documentId,
            hasImages = MediaKind.isImage(format),
            visionConfigured = visionConfigured,
            visionConsent = visionConsent,
            embeddingIsApi = true,
            embeddingConsent = embeddingConsent,
        )
        job.localEmbeddingAvailable = true
        persistJob(job, displayName)
        if (pauseAt == ImportStage.COPYING) {
            job.stage = ImportStage.COPYING
            persistJob(job, displayName)
            return job
        }
        return continueImportCancellable(job, displayName, bytes, format)
    }

    /** Suspending API resume bridge used after a Worker/process interruption. */
    suspend fun resumeImportCancellable(
        jobId: String,
        bytes: ByteArray? = null,
        visionConfigured: Boolean,
    ): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        check(row.boolean("embedding_is_api")) {
            "The suspending resume entry point is reserved for API embedding jobs"
        }
        val documentId = row.string("document_id")
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val document = db.query("SELECT blob_hash, deleted_at, format FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
            ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val stage = ImportStage.valueOf(row.string("stage"))
        check(stage != ImportStage.CANCELLED && !ImportStateMachine.isPublished(stage)) { "import job is not resumable" }
        if (stage == ImportStage.FAILED && row.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true)) {
            error("UNKNOWN_OUTCOME: explicit duplicate-charge acknowledgement is required before retry")
        }
        val expected = document.string("blob_hash")
        val payload = when {
            bytes != null -> {
                check(sha256Hex(bytes) == expected) { "resume bytes do not match the stored CAS blob" }
                bytes
            }
            else -> blobs.get(expected) ?: error("CAS blob is missing")
        }
        val displayName = row.string("display_name")
        val recordedFormat = document.string("format")
        val format = recordedFormat.takeIf { it.isNotBlank() }?.let { runCatching { SourceFormat.valueOf(it) }.getOrNull() }
            ?: MediaKind.detect(displayName, "", payload.copyOf(minOf(payload.size, 64)))
        val job = importJobFromRow(row, visionConfigured, stage, documentId, kbId)
        validateRequestedEmbeddingSelection(kbId, api = true, consent = job.embeddingConsent)
        return continueImportCancellable(job, displayName, payload, format)
    }

    /** Suspending Vision consent bridge; text embedding consent remains separate. */
    suspend fun grantVisionConsentCancellable(
        jobId: String,
        expectedVisionFingerprint: String? = null,
        expectedDocumentsFingerprintHash: String? = null,
    ): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        check(row.boolean("embedding_is_api")) {
            "The suspending Vision bridge is reserved for API embedding jobs"
        }
        expectedVisionFingerprint?.let {
            check(it == visionFingerprint()) { "Vision destination changed; no image was sent" }
        }
        expectedDocumentsFingerprintHash?.let {
            check(it == documentsFingerprintHash(row.string("kb_id"))) {
                "Vision consent documents changed; no image was sent"
            }
        }
        val documentId = row.string("document_id")
        val document = db.query("SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?", listOf(documentId)).single()
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = row.string("kb_id"),
            documentId = documentId,
            stage = ImportStage.QUEUED,
            hasImages = true,
            visionConfigured = true,
            visionConsent = true,
            embeddingIsApi = true,
            embeddingConsent = row.boolean("embedding_consent"),
            localEmbeddingAvailable = true,
            consentedVisionFingerprint = expectedVisionFingerprint ?: visionFingerprint(),
        )
        return continueImportCancellable(job, row.string("display_name"), bytes, format)
    }

    /** Suspending text embedding consent bridge. */
    suspend fun grantEmbeddingConsentCancellable(
        jobId: String,
        visionConfigured: Boolean = false,
    ): ImportJob = grantEmbeddingConsentCancellableInternal(jobId, visionConfigured, allowUnknownOutcome = false)

    private suspend fun grantEmbeddingConsentCancellableInternal(
        jobId: String,
        visionConfigured: Boolean,
        allowUnknownOutcome: Boolean,
    ): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        check(row.boolean("embedding_is_api")) { "Import job did not select API embedding" }
        check(allowUnknownOutcome || !row.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true)) {
            "UNKNOWN_OUTCOME: use retryUnknownEmbedding with explicit duplicate-charge acknowledgement"
        }
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val space = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kbId))
            .singleOrNull()?.string("embedding_space_id").orEmpty()
        check(apiEmbedderForSpace(space) != null) {
            "The selected API embedding binding is unavailable; no text was sent"
        }
        val documentId = row.string("document_id")
        val document = db.query("SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?", listOf(documentId))
            .singleOrNull() ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = importJobFromRow(row, visionConfigured, ImportStage.QUEUED, documentId, kbId).also {
            it.embeddingIsApi = true
            it.embeddingConsent = true
        }
        validateRequestedEmbeddingSelection(kbId, api = true, consent = true)
        // The staged operation rechecks consent from durable state immediately
        // before dispatch. Persist the user's explicit approval before creating
        // or dispatching that operation; otherwise the safety recheck observes
        // the old false value and converts a local preflight mismatch into an
        // UNKNOWN external outcome without sending any request.
        persistJob(job, row.string("display_name"))
        return continueImportCancellable(job, row.string("display_name"), bytes, format)
    }

    /** Explicit duplicate-charge acknowledgement for an uncertain API import. */
    suspend fun retryUnknownEmbeddingCancellable(
        jobId: String,
        acknowledgeDuplicateCharge: Boolean,
        visionConfigured: Boolean = false,
    ): ImportJob {
        check(acknowledgeDuplicateCharge) {
            "Retry may bill the embedding provider twice. Acknowledge the duplicate-charge risk."
        }
        val row = db.query("SELECT embedding_is_api, error, display_name FROM import_jobs WHERE id = ?", listOf(jobId))
            .singleOrNull() ?: error("import job not found")
        check(!row.string("display_name").startsWith(UNKNOWN_REBIND_PREFIX)) {
            "This UNKNOWN_OUTCOME belongs to an API rebind; retry the rebind with explicit acknowledgement"
        }
        check(row.boolean("embedding_is_api")) { "Import job did not select API embedding" }
        check(row.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true)) {
            "Import job has no unknown embedding outcome to retry"
        }
        check(row.string("error").contains("embedding", ignoreCase = true)) {
            "Unknown outcome belongs to another stage; use its explicit retry action"
        }
        return grantEmbeddingConsentCancellableInternal(jobId, visionConfigured, allowUnknownOutcome = true)
    }

    /** Suspending API rebind bridge.  The provider call is always lock free. */
    suspend fun rebindApiKnowledgeBaseCancellable(
        knowledgeBaseId: String,
        binding: ApiEmbeddingBinding,
        embeddingConsent: Boolean,
        acknowledgeDuplicateCharge: Boolean = false,
    ): String {
        check(embeddingConsent) {
            "Changing the API embedding binding requires fresh text embedding consent"
        }
        requireKb(knowledgeBaseId)
        check(unknownRebindGates(knowledgeBaseId).isEmpty() || acknowledgeDuplicateCharge) {
            "UNKNOWN_OUTCOME: a prior API rebind is uncertain; explicit duplicate-charge acknowledgement is required"
        }
        val selectedEmbedder = apiEmbedderForSpace(binding.spaceId)
        val currentSpace = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(knowledgeBaseId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        check(currentSpace != binding.spaceId) { "Knowledge base is already bound to the selected embedding space" }
        val existing = activeEmbeddingOperation(knowledgeBaseId)
        if (existing != null) {
            check(existing.kind == "REBIND" && existing.spaceId == binding.spaceId) {
                "another embedding operation is already active for this knowledge base"
            }
            return when (existing.state) {
                "CACHE_READY" -> finalizeEmbeddingOperation(existing.token)
                "PREPARED" -> {
                    executeEmbeddingOperation(
                        existing,
                        selectedEmbedder ?: error("No API embedding adapter is registered for the selected binding"),
                    )
                    finalizeEmbeddingOperation(existing.token)
                }
                "DISPATCHED" -> {
                    markEmbeddingOperationUnknown(existing)
                    throw EmbeddingUnknownOutcomeException()
                }
                else -> error("embedding operation is not resumable: ${existing.state}")
            }
        }
        val embedderForOperation = selectedEmbedder
            ?: error("No API embedding adapter is registered for the selected binding")
        val operation = synchronized(indexLock) {
            db.transaction {
                val persistedSpace = db.query(
                    "SELECT embedding_space_id, deleted_at FROM knowledge_bases WHERE id = ?",
                    listOf(knowledgeBaseId),
                ).singleOrNull() ?: error("knowledge base not found")
                check(persistedSpace.string("deleted_at").isBlank()) { "knowledge base deleted" }
                check(persistedSpace.string("embedding_space_id") == currentSpace) {
                    "knowledge base binding changed before API rebind"
                }
                val pointers = activeDocumentPointers(knowledgeBaseId)
                val inputsByVersion = embeddingInputsByVersionForKnowledgeBase(knowledgeBaseId)
                val manifest = operationManifestHash(
                    kind = "REBIND",
                    knowledgeBaseId = knowledgeBaseId,
                    targetSpace = binding.spaceId,
                    sourceSpace = currentSpace,
                    inputsByVersion = inputsByVersion,
                    activePointers = pointers,
                )
                insertEmbeddingOperation(
                    kind = "REBIND",
                    knowledgeBaseId = knowledgeBaseId,
                    jobId = null,
                    documentId = null,
                    documentVersionId = null,
                    spaceId = binding.spaceId,
                    inputManifestHash = manifest,
                    consentFingerprint = apiConsentFingerprint("REBIND", knowledgeBaseId, binding.spaceId, sourceSpace = currentSpace),
                )
            }
        }
        return try {
            executeEmbeddingOperation(operation, embedderForOperation)
            finalizeEmbeddingOperation(operation.token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Throwable) {
            if (isUnknownEmbeddingFailure(failure)) persistRebindUnknownGate(knowledgeBaseId, binding)
            throw failure
        }
    }

    /** Suspending API rebuild bridge; no provider request is made under indexLock. */
    suspend fun rebuildIndexCancellable(
        knowledgeBaseId: String,
        acknowledgeDuplicateCharge: Boolean = false,
    ): String {
        requireKb(knowledgeBaseId)
        val currentSpace = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(knowledgeBaseId))
            .singleOrNull()?.string("embedding_space_id").orEmpty()
        check(currentSpace != embedder.spaceId) {
            "The suspending rebuild entry point is reserved for API embedding spaces"
        }
        check(hasApiEmbeddingConsent(knowledgeBaseId)) {
            "API embedding rebuild requires explicit persisted consent"
        }
        val unknown = latestUnknownEmbeddingOperation(knowledgeBaseId, "REBUILD")
        check(unknown == null || acknowledgeDuplicateCharge) {
            "UNKNOWN_OUTCOME: a prior API rebuild is uncertain; explicit duplicate-charge acknowledgement is required"
        }
        val selectedEmbedder = apiEmbedderForSpace(currentSpace)
        val existing = activeEmbeddingOperation(knowledgeBaseId)
        if (existing != null) {
            check(existing.kind == "REBUILD" && existing.spaceId == currentSpace) {
                "another embedding operation is already active for this knowledge base"
            }
            return when (existing.state) {
                "CACHE_READY" -> finalizeEmbeddingOperation(existing.token)
                "PREPARED" -> {
                    executeEmbeddingOperation(
                        existing,
                        selectedEmbedder ?: error("No API embedding adapter is registered for the selected binding"),
                    )
                    finalizeEmbeddingOperation(existing.token)
                }
                "DISPATCHED" -> {
                    markEmbeddingOperationUnknown(existing)
                    throw EmbeddingUnknownOutcomeException()
                }
                else -> error("embedding operation is not resumable: ${existing.state}")
            }
        }
        val embedderForOperation = selectedEmbedder
            ?: error("No API embedding adapter is registered for the selected binding")
        val operation = synchronized(indexLock) {
            db.transaction {
                val persistedSpace = db.query(
                    "SELECT embedding_space_id, deleted_at FROM knowledge_bases WHERE id = ?",
                    listOf(knowledgeBaseId),
                ).singleOrNull() ?: error("knowledge base not found")
                check(persistedSpace.string("deleted_at").isBlank()) { "knowledge base deleted" }
                check(persistedSpace.string("embedding_space_id") == currentSpace) {
                    "knowledge base binding changed before API rebuild"
                }
                val inputsByVersion = embeddingInputsByVersionForKnowledgeBase(knowledgeBaseId)
                val manifest = operationManifestHash(
                    kind = "REBUILD",
                    knowledgeBaseId = knowledgeBaseId,
                    targetSpace = currentSpace,
                    sourceSpace = currentSpace,
                    inputsByVersion = inputsByVersion,
                    activePointers = activeDocumentPointers(knowledgeBaseId),
                )
                insertEmbeddingOperation(
                    kind = "REBUILD",
                    knowledgeBaseId = knowledgeBaseId,
                    jobId = null,
                    documentId = null,
                    documentVersionId = null,
                    spaceId = currentSpace,
                    inputManifestHash = manifest,
                    consentFingerprint = apiConsentFingerprint("REBUILD", knowledgeBaseId, currentSpace),
                )
            }
        }
        executeEmbeddingOperation(operation, embedderForOperation)
        return finalizeEmbeddingOperation(operation.token)
    }

    fun importBytes(
        displayName: String,
        mediaType: String,
        bytes: ByteArray,
        visionConfigured: Boolean,
        knowledgeBaseId: String? = null,
        pauseAt: ImportStage? = null,
        visionConsent: Boolean = false,
        embeddingIsApi: Boolean = false,
        embeddingConsent: Boolean = false,
    ): ImportJob {
        val routedKbId = knowledgeBaseId ?: ensureDefaultBase()
        // API embedding is the only potentially billable path.  Keep the
        // legacy synchronous method for local ONNX/hash imports, but bridge a
        // selected API KB to the suspending implementation before taking the
        // repository/index lock.  This preserves source compatibility while
        // making the App/Worker entry point safe for slow transports.
        if (embeddingIsApi || isApiKnowledgeBase(routedKbId)) {
            return runBlocking {
                importBytesCancellable(
                    displayName = displayName,
                    mediaType = mediaType,
                    bytes = bytes,
                    visionConfigured = visionConfigured,
                    knowledgeBaseId = routedKbId,
                    pauseAt = pauseAt,
                    visionConsent = visionConsent,
                    embeddingIsApi = embeddingIsApi,
                    embeddingConsent = embeddingConsent,
                )
            }
        }
        val kbId = routedKbId
        requireKb(kbId)
        validateRequestedEmbeddingSelection(kbId, embeddingIsApi, embeddingConsent)
        val format = MediaKind.detect(displayName, mediaType, bytes.copyOf(minOf(bytes.size, 64)))
        if (format == SourceFormat.KNOWLEDGE_ARCHIVE) {
            require(bytes.size <= KnowledgeArchive.MAX_TOTAL_BYTES) { "RESOURCE_LIMIT" }
            return expandKnowledgeArchive(
                displayName, bytes, visionConfigured, kbId, visionConsent, embeddingIsApi, embeddingConsent,
                pauseAt,
            )
        }
        require(bytes.size <= MediaKind.MAX_IMPORT_BYTES) { "RESOURCE_LIMIT" }
        val stored = blobs.put(bytes, mediaType.ifBlank { guessedMime(format) })
        val existingId = existingDocument(kbId, stored.sha256)
        if (existingId != null) {
            val prior = db.query("SELECT active_version_id, deleted_at FROM documents WHERE id = ?", listOf(existingId)).single()
            if (prior.string("deleted_at").isBlank()) {
                val unknownEmbedding = db.query(
                    "SELECT error FROM import_jobs WHERE document_id = ? AND embedding_is_api = 1 ORDER BY updated_at DESC LIMIT 1",
                    listOf(existingId),
                ).firstOrNull()?.string("error")?.takeIf { it.contains("UNKNOWN_OUTCOME", ignoreCase = true) }
                    ?.takeIf { it.contains("embedding", ignoreCase = true) }
                if (unknownEmbedding != null && embeddingIsApi) {
                    val job = ImportJob(
                        id = EntityId.random().value,
                        knowledgeBaseId = kbId,
                        documentId = existingId,
                        stage = ImportStage.FAILED,
                        visionConfigured = visionConfigured,
                        visionConsent = visionConsent,
                        embeddingIsApi = true,
                        embeddingConsent = embeddingConsent,
                        localEmbeddingAvailable = true,
                        error = "UNKNOWN_OUTCOME: prior API embedding is uncertain; explicit duplicate-charge acknowledgement is required",
                    )
                    persistJob(job, displayName)
                    return job
                }
                if (isPublishedReady(existingId, kbId, embeddingIsApi)) {
                    val job = ImportJob(
                        id = EntityId.random().value,
                        knowledgeBaseId = kbId,
                        documentId = existingId,
                        stage = ImportStage.READY,
                        visionConfigured = visionConfigured,
                        visionConsent = visionConsent,
                        embeddingIsApi = embeddingIsApi,
                        embeddingConsent = embeddingConsent,
                        localEmbeddingAvailable = true,
                    )
                    persistJob(job, displayName)
                    return job
                }
                val job = ImportJob(
                    id = EntityId.random().value,
                    knowledgeBaseId = kbId,
                    documentId = existingId,
                    hasImages = MediaKind.isImage(format),
                    visionConfigured = visionConfigured,
                    visionConsent = visionConsent,
                    embeddingIsApi = embeddingIsApi,
                    embeddingConsent = embeddingConsent,
                    localEmbeddingAvailable = true,
                )
                persistJob(job, displayName)
                if (pauseAt == ImportStage.COPYING) {
                    job.stage = ImportStage.COPYING
                    persistJob(job, displayName)
                    return job
                }
                return continueImport(job, displayName, bytes, format)
            }
        }
        db.transaction {
            upsertBlob(stored)
            val documentId = existingId ?: EntityId.random().value
            db.execute(
                "INSERT OR REPLACE INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES (?,?,?,?,?,?,?)",
                listOf(documentId, kbId, stored.sha256, displayName, format.name, null, null),
            )
            syncBlobRef(stored.sha256)
        }
        val documentId = existingDocument(kbId, stored.sha256)!!
        val job = ImportJob(
            id = EntityId.random().value,
            knowledgeBaseId = kbId,
            documentId = documentId,
            hasImages = MediaKind.isImage(format),
            visionConfigured = visionConfigured,
            visionConsent = visionConsent,
            embeddingIsApi = embeddingIsApi,
            embeddingConsent = embeddingConsent,
        )
        job.localEmbeddingAvailable = true
        persistJob(job, displayName)
        if (pauseAt == ImportStage.COPYING) {
            job.stage = ImportStage.COPYING
            persistJob(job, displayName)
            return job
        }
        return continueImport(job, displayName, bytes, format)
    }

    /**
     * Android URI staging path for a knowledge ZIP. The archive itself stays
     * file-backed; only one bounded child entry is materialized before it is
     * copied into CAS and attached to the durable batch.
     */
    fun importKnowledgeArchiveFile(
        displayName: String,
        file: File,
        visionConfigured: Boolean,
        knowledgeBaseId: String? = null,
        pauseAt: ImportStage? = null,
        visionConsent: Boolean = false,
        embeddingIsApi: Boolean = false,
        embeddingConsent: Boolean = false,
    ): ImportJob {
        require(file.isFile && file.length() in 22L..KnowledgeArchive.MAX_TOTAL_BYTES) { "RESOURCE_LIMIT" }
        val kbId = knowledgeBaseId ?: ensureDefaultBase()
        requireKb(kbId)
        validateRequestedEmbeddingSelection(kbId, embeddingIsApi, embeddingConsent)
        require(!KnowledgeArchive.isOfficePackage(displayName, file)) {
            "Office packages must be imported as documents, not knowledge archives"
        }
        return expandKnowledgeArchive(
            displayName,
            file,
            visionConfigured,
            kbId,
            visionConsent,
            embeddingIsApi,
            embeddingConsent,
            pauseAt,
        )
    }

    fun resumeImport(jobId: String, bytes: ByteArray? = null, visionConfigured: Boolean): ImportJob {
        val apiJob = db.query(
            "SELECT embedding_is_api FROM import_jobs WHERE id = ?",
            listOf(jobId),
        ).singleOrNull()?.boolean("embedding_is_api") == true
        if (apiJob) {
            return runBlocking { resumeImportCancellable(jobId, bytes, visionConfigured) }
        }
        return synchronized(indexLock) {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val documentId = row.string("document_id")
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val document = db.query("SELECT blob_hash, deleted_at, format FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
            ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val stage = ImportStage.valueOf(row.string("stage"))
        check(stage != ImportStage.CANCELLED && !ImportStateMachine.isPublished(stage)) { "import job is not resumable" }
        if (stage == ImportStage.FAILED && row.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true)) {
            error("UNKNOWN_OUTCOME: explicit duplicate-charge acknowledgement is required before retry")
        }
        val expected = document.string("blob_hash")
        val payload = when {
            bytes != null -> {
                val actual = sha256Hex(bytes)
                check(actual == expected) { "resume bytes do not match the stored CAS blob" }
                bytes
            }
            else -> blobs.get(expected) ?: error("CAS blob is missing")
        }
        val displayName = row.string("display_name")
        val recordedFormat = document.string("format")
        val format = recordedFormat.takeIf { it.isNotBlank() }?.let { runCatching { SourceFormat.valueOf(it) }.getOrNull() }
            ?: MediaKind.detect(displayName, "", payload.copyOf(minOf(payload.size, 64)))
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = kbId,
            documentId = documentId,
            stage = stage,
            hasImages = row.boolean("has_images"),
            visionConfigured = visionConfigured,
            visionConsent = row.string("vision_consent").let { it == "1" || it.equals("true", true) } ||
                runCatching { row.long("vision_consent") != 0L }.getOrDefault(false),
            embeddingIsApi = row.boolean("embedding_is_api"),
            embeddingConsent = row.boolean("embedding_consent"),
            localEmbeddingAvailable = true,
            error = row.string("error").ifBlank { null },
            consentedVisionFingerprint = runCatching { row.string("vision_binding_json") }.getOrNull()?.ifBlank { null },
        )
        validateRequestedEmbeddingSelection(kbId, job.embeddingIsApi, job.embeddingConsent)
        return continueImport(job, displayName, payload, format)
        }
    }

    /**
     * Mark an in-flight import cancelled.  The CAS source remains intact so a
     * later explicit re-import can resume from the copied document without
     * mutating the published generation.  This method is idempotent for an
     * already-cancelled job and never rewrites a READY job.
     */
    fun cancelImport(jobId: String): Boolean = synchronized(indexLock) {
        val row = db.query("SELECT stage FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull() ?: return false
        val stage = runCatching { ImportStage.valueOf(row.string("stage")) }.getOrNull() ?: return false
        if (ImportStateMachine.isPublished(stage)) return false
        val operation = requestEmbeddingOperationCancelForJob(jobId)
        if (stage != ImportStage.CANCELLED) {
            val postDispatchUnknown = operation?.state == "UNKNOWN" && operation.error.contains("UNKNOWN_OUTCOME")
            db.execute(
                "UPDATE import_jobs SET stage = ?, error = ?, updated_at = ? WHERE id = ?",
                listOf(
                    if (postDispatchUnknown) ImportStage.FAILED.name else ImportStage.CANCELLED.name,
                    if (postDispatchUnknown) API_EMBEDDING_CANCEL_UNKNOWN_ERROR else "Cancelled by user",
                    Utc.nowIso(),
                    jobId,
                ),
            )
            syncBatchItemFromJobLocked(jobId)
        }
        true
    }

    fun grantVisionConsent(
        jobId: String,
        expectedVisionFingerprint: String? = null,
        expectedDocumentsFingerprintHash: String? = null,
    ): ImportJob {
        val apiJob = db.query(
            "SELECT embedding_is_api FROM import_jobs WHERE id = ?",
            listOf(jobId),
        ).singleOrNull()?.boolean("embedding_is_api") == true
        if (apiJob) {
            return runBlocking {
                grantVisionConsentCancellable(jobId, expectedVisionFingerprint, expectedDocumentsFingerprintHash)
            }
        }
        return synchronized(indexLock) {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        expectedVisionFingerprint?.let {
            check(it == visionFingerprint()) { "Vision destination changed; no image was sent" }
        }
        expectedDocumentsFingerprintHash?.let {
            check(it == documentsFingerprintHash(row.string("kb_id"))) {
                "Vision consent documents changed; no image was sent"
            }
        }
        val documentId = row.string("document_id")
        val document = db.query("SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?", listOf(documentId)).single()
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = row.string("kb_id"),
            documentId = documentId,
            stage = ImportStage.QUEUED,
            hasImages = true,
            visionConfigured = true,
            visionConsent = true,
            embeddingIsApi = row.boolean("embedding_is_api"),
            embeddingConsent = row.boolean("embedding_consent"),
            localEmbeddingAvailable = true,
            consentedVisionFingerprint = expectedVisionFingerprint ?: visionFingerprint(),
        )
        return continueImport(job, row.string("display_name"), bytes, format)
        }
    }

    /**
     * Persist an auditable text-only version while images stay in CAS.
     * The document is never marked complete [ImportStage.READY].
     */
    fun acceptTextOnlyVisualGaps(jobId: String): ImportJob {
        val apiJob = db.query(
            "SELECT embedding_is_api FROM import_jobs WHERE id = ?",
            listOf(jobId),
        ).singleOrNull()?.boolean("embedding_is_api") == true
        return if (apiJob) {
            runBlocking { acceptTextOnlyVisualGapsCancellable(jobId) }
        } else {
            synchronized(indexLock) { acceptTextOnlyVisualGapsInternal(jobId) }
        }
    }

    private suspend fun acceptTextOnlyVisualGapsCancellable(jobId: String): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val stage = ImportStage.valueOf(row.string("stage"))
        check(
            stage == ImportStage.WAITING_FOR_VISION_MODEL || stage == ImportStage.AWAITING_UPLOAD_CONSENT,
        ) { "Text-only degradation is only available while waiting for Vision. The job was not changed." }
        val documentId = row.string("document_id")
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val document = db.query(
            "SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?",
            listOf(documentId),
        ).singleOrNull() ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = importJobFromRow(row, visionConfigured = true, stage = stage)
        job.visualGapsAccepted = true
        return continueImportCancellable(job, row.string("display_name"), bytes, format)
    }

    private fun acceptTextOnlyVisualGapsInternal(jobId: String): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val stage = ImportStage.valueOf(row.string("stage"))
        check(
            stage == ImportStage.WAITING_FOR_VISION_MODEL || stage == ImportStage.AWAITING_UPLOAD_CONSENT,
        ) { "Text-only degradation is only available while waiting for Vision. The job was not changed." }
        val documentId = row.string("document_id")
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val document = db.query(
            "SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?",
            listOf(documentId),
        ).singleOrNull() ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = importJobFromRow(row, visionConfigured = row.boolean("has_images"), stage = stage)
        job.visualGapsAccepted = true
        return continueImport(job, row.string("display_name"), bytes, format)
    }

    fun retryUnknownVision(
        jobId: String,
        acknowledgeDuplicateCharge: Boolean,
        expectedVisionFingerprint: String? = null,
        expectedDocumentsFingerprintHash: String? = null,
    ): ImportJob {
        check(acknowledgeDuplicateCharge) {
            "Retry may bill the Vision provider twice. Acknowledge the duplicate-charge risk."
        }
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val documentId = row.string("document_id")
        db.execute(
            "DELETE FROM vision_results WHERE status = ? AND asset_hash IN (SELECT blob_hash FROM assets WHERE document_id = ?)",
            listOf("UNKNOWN_OUTCOME", documentId),
        )
        return grantVisionConsent(jobId, expectedVisionFingerprint, expectedDocumentsFingerprintHash)
    }

    /**
     * Approve sending text for an API-bound import.  Vision consent is copied
     * independently from the persisted job and is never implied by this call.
     */
    fun grantEmbeddingConsent(jobId: String, visionConfigured: Boolean = false): ImportJob =
        runBlocking { grantEmbeddingConsentCancellable(jobId, visionConfigured) }

    private fun grantEmbeddingConsentInternal(
        jobId: String,
        visionConfigured: Boolean,
        allowUnknownOutcome: Boolean,
    ): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        check(row.boolean("embedding_is_api")) { "Import job did not select API embedding" }
        check(allowUnknownOutcome || !row.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true)) {
            "UNKNOWN_OUTCOME: use retryUnknownEmbedding with explicit duplicate-charge acknowledgement"
        }
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val space = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(kbId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        check(apiEmbedderForSpace(space) != null) {
            "The selected API embedding binding is unavailable; no text was sent"
        }
        val documentId = row.string("document_id")
        val document = db.query(
            "SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?",
            listOf(documentId),
        ).singleOrNull() ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = kbId,
            documentId = documentId,
            stage = ImportStage.QUEUED,
            hasImages = row.boolean("has_images"),
            visionConfigured = visionConfigured,
            visionConsent = row.boolean("vision_consent"),
            embeddingIsApi = true,
            embeddingConsent = true,
            localEmbeddingAvailable = true,
            consentedVisionFingerprint = row.string("vision_binding_json").ifBlank { null },
        )
        validateRequestedEmbeddingSelection(kbId, api = true, consent = true)
        return continueImport(job, row.string("display_name"), bytes, format)
    }

    /**
     * Retry an uncertain API embedding only after the user acknowledges a
     * possible duplicate provider charge.  There is intentionally no worker
     * path to this method.
     */
    fun retryUnknownEmbedding(
        jobId: String,
        acknowledgeDuplicateCharge: Boolean,
        visionConfigured: Boolean = false,
    ): ImportJob {
        val apiJob = db.query(
            "SELECT embedding_is_api FROM import_jobs WHERE id = ?",
            listOf(jobId),
        ).singleOrNull()?.boolean("embedding_is_api") == true
        if (apiJob) {
            return runBlocking {
                retryUnknownEmbeddingCancellable(jobId, acknowledgeDuplicateCharge, visionConfigured)
            }
        }
        return synchronized(indexLock) {
        check(acknowledgeDuplicateCharge) {
            "Retry may bill the embedding provider twice. Acknowledge the duplicate-charge risk."
        }
        val row = db.query("SELECT embedding_is_api, error, display_name FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        check(!row.string("display_name").startsWith(UNKNOWN_REBIND_PREFIX)) {
            "This UNKNOWN_OUTCOME belongs to an API rebind; retry the rebind with explicit acknowledgement"
        }
        check(row.boolean("embedding_is_api")) { "Import job did not select API embedding" }
        check(row.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true)) {
            "Import job has no unknown embedding outcome to retry"
        }
        check(row.string("error").contains("embedding", ignoreCase = true)) {
            "Unknown outcome belongs to another stage; use its explicit retry action"
        }
        // The persisted UNKNOWN_OUTCOME row is deliberately left untouched
        // until continueImport writes a successful READY result. If the
        // explicit retry crashes or is uncertain again, the old gate remains.
        grantEmbeddingConsentInternal(jobId, visionConfigured, allowUnknownOutcome = true)
        }
    }

    fun locateCitation(citation: Citation): EvidenceLocator {
        val missing = EvidenceLocator(citation.documentId, "Source removed", citation.page, citation.assetId, citation.sourceSpan, null, removed = true)
        val citedKnowledgeBaseId = citation.knowledgeBaseId.takeIf { it.isNotBlank() } ?: return missing
        val knowledgeBase = db.query(
            "SELECT id, deleted_at FROM knowledge_bases WHERE id = ?",
            listOf(citedKnowledgeBaseId),
        ).singleOrNull()
        if (knowledgeBase == null || knowledgeBase.string("id").isBlank() || knowledgeBase.string("deleted_at").isNotBlank()) {
            return missing
        }
        val document = db.query(
            "SELECT kb_id, display_name, blob_hash, deleted_at, active_version_id FROM documents WHERE id = ?",
            listOf(citation.documentId),
        ).singleOrNull()
        if (document == null || document.string("deleted_at").isNotBlank()) return missing
        val documentKnowledgeBaseId = document.string("kb_id")
        if (documentKnowledgeBaseId.isBlank() || documentKnowledgeBaseId != citedKnowledgeBaseId) return missing
        // A citation must carry the exact version it came from.  Falling back
        // to the current active version would let a forged/legacy citation
        // silently point at a different document revision.
        val versionId = citation.documentVersionId
        if (versionId.isBlank()) return missing
        val version = db.query(
            "SELECT id FROM document_versions WHERE id = ? AND document_id = ?",
            listOf(versionId, citation.documentId),
        ).singleOrNull()
        if (version == null) return missing
        if (citation.chunkId.isNotBlank()) {
            val chunk = db.query(
                "SELECT id, page, asset_ids FROM chunks WHERE id = ? AND document_version_id = ?",
                listOf(citation.chunkId, versionId),
            ).singleOrNull() ?: return missing
            val chunkAssets = chunk.string("asset_ids").split(',').filter { it.isNotBlank() }
            if (citation.assetId != null && citation.assetId !in chunkAssets) return missing
            val page = chunk.string("page").toIntOrNull() ?: citation.page
            if (citation.assetId != null) {
                val asset = db.query(
                    "SELECT blob_hash, page, document_version_id FROM assets WHERE id = ? AND document_id = ?",
                    listOf(citation.assetId, citation.documentId),
                ).singleOrNull() ?: return missing
                val assetVersion = asset.string("document_version_id")
                if (assetVersion.isBlank() || assetVersion != versionId) return missing
                return EvidenceLocator(
                    documentId = citation.documentId,
                    displayName = document.string("display_name"),
                    page = asset.string("page").toIntOrNull() ?: page,
                    assetId = citation.assetId,
                    sourceSpan = citation.sourceSpan,
                    blobHash = asset.string("blob_hash"),
                    removed = false,
                )
            }
            return EvidenceLocator(
                documentId = citation.documentId,
                displayName = document.string("display_name"),
                page = page,
                assetId = null,
                sourceSpan = citation.sourceSpan,
                blobHash = document.string("blob_hash"),
                removed = false,
            )
        }
        return missing
    }

    /**
     * Resolve source bytes only after the full citation scope/version/asset
     * checks above have passed.  The caller never needs the CAS hash or path;
     * PDF page citations return the original PDF bytes and retain [Citation.page].
     */
    fun evidenceBytes(citation: Citation): Pair<String, ByteArray>? {
        val locator = locateCitation(citation)
        if (locator.removed) return null
        val hash = locator.blobHash?.takeIf { it.isNotBlank() } ?: return null
        val bytes = blobs.get(hash) ?: return null
        val mediaType = db.query(
            "SELECT media_type FROM blobs WHERE hash = ?",
            listOf(hash),
        ).singleOrNull()?.string("media_type")?.ifBlank { null } ?: return null
        return mediaType to bytes
    }

    fun assetBytes(assetId: String): Pair<String, ByteArray>? {
        val asset = db.query("SELECT blob_hash FROM assets WHERE id = ?", listOf(assetId)).singleOrNull() ?: return null
        val hash = asset.string("blob_hash")
        val bytes = blobs.get(hash) ?: return null
        val media = db.query("SELECT media_type FROM blobs WHERE hash = ?", listOf(hash)).singleOrNull()?.string("media_type")
            ?: "application/octet-stream"
        return media to bytes
    }

    fun search(query: String, topK: Int = 8, knowledgeBaseIds: List<String>? = null): List<SearchHit> =
        retrieve("search", query, topK, knowledgeBaseIds).hits

    fun retrieve(runId: String, query: String, topK: Int = 8, knowledgeBaseIds: List<String>? = null): RetrievalResult {
        if (query.isBlank()) return RetrievalResult(emptyList(), emptyList(), listOf("empty query"))
        val warnings = mutableListOf<String>()
        val bases = (knowledgeBaseIds ?: listKnowledgeBases().map { it.first }).distinct()
        val lexical = mutableListOf<SearchHit>()
        val vector = mutableListOf<SearchHit>()
        for (kbId in bases) {
            val kb = db.query("SELECT * FROM knowledge_bases WHERE id = ? AND deleted_at IS NULL", listOf(kbId)).singleOrNull()
            if (kb == null) {
                warnings += "Knowledge base $kbId is missing or deleted"
                continue
            }
            val space = kb.string("embedding_space_id")
            // Check persisted consent before resolving a dynamic adapter. The
            // resolver is construction-only by contract, but this ordering
            // keeps a no-consent query from reaching any provider adapter.
            if (space.isNotBlank() && space != embedder.spaceId && !hasApiEmbeddingConsent(kbId)) {
                warnings += "Knowledge base $kbId uses embedding space $space but has no persisted API embedding consent for query vectors"
                continue
            }
            val apiQueryHash = if (space.isNotBlank() && space != embedder.spaceId) {
                queryHash(query).also { rejectPendingApiQueryBeforeResolver(kbId, space, it) }
            } else {
                null
            }
            // A successful provider result is an immutable, billable cache
            // entry.  Read it before resolving a dynamic adapter so a process
            // restart can finish local retrieval even when that adapter is no
            // longer configured.  The cache is keyed by the complete space id
            // and query digest; it never stores the original query text.
            val cachedQueryVector = apiQueryHash?.let { queryVectorCache(space, it) }
            val queryEmbedder = if (cachedQueryVector != null) {
                CachedQueryEmbedder(space, cachedQueryVector.dimension)
            } else {
                embeddingForSpace(space)
            }
            if (queryEmbedder == null) {
                warnings += "Knowledge base $kbId uses space $space; no matching query embedder is configured"
                continue
            }
            val pin = pinnedReadyGeneration(kbId)
            if (pin == null) {
                warnings += "Knowledge base $kbId has no READY generation"
                continue
            }
            lexical += lexicalHits(kbId, query, 40, pin)
            if (apiQueryHash != null && cachedQueryVector == null) {
                // Resolve only after a durable pending-row check.  A previous
                // UNKNOWN result therefore cannot reach a provider adapter
                // until the UI explicitly authorizes this exact query key.
                claimApiQueryAttempt(kbId, space, apiQueryHash)
            }
            var queryVectorReady = cachedQueryVector != null
            try {
                vector += vectorHits(
                    kbId,
                    query,
                    40,
                    pin,
                    queryEmbedder,
                    queryVector = cachedQueryVector?.vector,
                    onQueryVectorReady = apiQueryHash?.let { hash ->
                        { vector ->
                            // Commit the successful provider vector before
                            // deleting the attempt row.  If local SQLite/JNI
                            // retrieval fails afterwards, the next identical
                            // query reuses this vector and is never re-billed.
                            insertQueryVectorCache(space, hash, vector, queryEmbedder.dimension)
                            clearApiQueryAttempt(kbId, space, hash)
                            queryVectorReady = true
                        }
                    },
                )
            } catch (failure: Throwable) {
                if (apiQueryHash != null && cachedQueryVector == null && !queryVectorReady && isUncertainApiQueryFailure(failure)) {
                    persistApiQueryUnknown(kbId, space, apiQueryHash)
                    // Do not attach a provider exception: it may echo the
                    // original query. The durable row carries only the key.
                    throw ApiQueryUnknownOutcomeException(kbId, space, apiQueryHash)
                }
                throw failure
            }
        }
        val hits = ReciprocalRankFusion.merge(listOf(lexical, vector)).take(topK)
        if (hits.isEmpty()) warnings += "No in-scope evidence"
        return RetrievalResult(hits, CitationMap.bind(runId, hits), warnings)
    }

    fun waitingForVisionCount(): Int =
        db.query(
            "SELECT COUNT(*) AS n FROM import_jobs WHERE stage = ?",
            listOf(ImportStage.WAITING_FOR_VISION_MODEL.name),
        ).singleOrNull()?.long("n")?.toInt() ?: 0

    fun listJobs(): List<Triple<ImportJob, String, String>> =
        db.query("SELECT * FROM import_jobs ORDER BY updated_at DESC").map { row ->
            val stage = ImportStage.valueOf(row.string("stage"))
            Triple(
                ImportJob(
                    id = row.string("id"),
                    knowledgeBaseId = row.string("kb_id"),
                    documentId = row.string("document_id"),
                    stage = stage,
                    hasImages = row.boolean("has_images"),
                    visionConsent = row.boolean("vision_consent"),
                    embeddingIsApi = row.boolean("embedding_is_api"),
                    embeddingConsent = row.boolean("embedding_consent"),
                    error = row.string("error").ifBlank { null },
                    visualGapsAccepted = stage == ImportStage.READY_WITH_VISUAL_GAPS ||
                        row.string("error").startsWith(TEXT_ONLY_VISUAL_GAPS_PREFIX),
                ),
                row.string("display_name"),
                row.string("updated_at"),
            )
        }

    fun blobRefCount(hash: String): Long =
        db.query("SELECT ref_count FROM blobs WHERE hash = ?", listOf(hash)).singleOrNull()?.long("ref_count") ?: 0L

    fun readDocumentText(documentId: String, maxChars: Int, allowedKnowledgeBaseIds: Set<String>? = null): String {
        val document = db.query("SELECT active_version_id, deleted_at, kb_id FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
            ?: return ""
        if (document.string("deleted_at").isNotBlank()) return ""
        if (allowedKnowledgeBaseIds != null && document.string("kb_id") !in allowedKnowledgeBaseIds) return ""
        val version = document.string("active_version_id")
        val text = db.query(
            "SELECT text FROM chunks WHERE document_version_id = ? ORDER BY ordinal",
            listOf(version),
        ).joinToString("\n") { it.string("text") }
        val cap = maxChars.coerceIn(0, 16_384)
        return text.take(cap)
    }

    fun documentKnowledgeBaseId(documentId: String): String? {
        val document = db.query("SELECT kb_id, deleted_at FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
            ?: return null
        if (document.string("deleted_at").isNotBlank()) return null
        return document.string("kb_id").ifBlank { null }
    }

    fun deleteDocument(documentId: String) {
        var knowledgeBaseId: String? = null
        synchronized(indexLock) {
            val row = db.query("SELECT kb_id, blob_hash, deleted_at FROM documents WHERE id = ?", listOf(documentId)).singleOrNull() ?: return
            if (row.string("deleted_at").isNotBlank()) return
            knowledgeBaseId = row.string("kb_id")
            db.transaction {
                db.execute("UPDATE documents SET deleted_at = ? WHERE id = ?", listOf(Utc.nowIso(), documentId))
                db.execute(
                    "UPDATE import_jobs SET stage = ?, error = ? WHERE document_id = ? AND stage NOT IN (?,?,?)",
                    listOf(ImportStage.CANCELLED.name, "document deleted", documentId, ImportStage.READY.name, ImportStage.READY_WITH_VISUAL_GAPS.name, ImportStage.CANCELLED.name),
                )
                db.execute(
                    "UPDATE embedding_operations SET cancel_requested = 1, state = CASE WHEN state = 'DISPATCHED' THEN 'UNKNOWN' WHEN state IN('PREPARED','CACHE_READY') THEN 'CANCELLED' ELSE state END, error = CASE WHEN state = 'DISPATCHED' THEN ? ELSE error END, updated_at = ? WHERE document_id = ? AND state IN('PREPARED','DISPATCHED','CACHE_READY')",
                    listOf(API_EMBEDDING_CANCEL_UNKNOWN_ERROR, Utc.nowIso(), documentId),
                )
                syncBlobRef(row.string("blob_hash"))
            }
        }
        // Do not call the API rebuild bridge while the repository monitor is
        // held.  A remote embedding rebuild after deletion is also avoided:
        // invalidate the generation and let an explicit user rebuild decide
        // whether the remaining text may leave the device.
        val kbId = knowledgeBaseId ?: return
        if (isApiKnowledgeBase(kbId)) {
            synchronized(indexLock) {
                db.execute("UPDATE knowledge_bases SET active_generation_id = NULL WHERE id = ?", listOf(kbId))
            }
        } else {
            rebuildIndex(kbId)
        }
    }

    fun deleteKnowledgeBase(kbId: String) {
        synchronized(indexLock) {
            db.transaction {
                db.query("SELECT id, blob_hash FROM documents WHERE kb_id = ? AND deleted_at IS NULL", listOf(kbId)).forEach { row ->
                    db.execute("UPDATE documents SET deleted_at = ? WHERE id = ?", listOf(Utc.nowIso(), row.string("id")))
                    db.execute(
                        "UPDATE import_jobs SET stage = ?, error = ? WHERE document_id = ? AND stage NOT IN (?,?,?)",
                        listOf(ImportStage.CANCELLED.name, "knowledge base deleted", row.string("id"), ImportStage.READY.name, ImportStage.READY_WITH_VISUAL_GAPS.name, ImportStage.CANCELLED.name),
                    )
                    syncBlobRef(row.string("blob_hash"))
                }
                db.execute("UPDATE knowledge_bases SET deleted_at = ?, active_generation_id = NULL WHERE id = ?", listOf(Utc.nowIso(), kbId))
                db.execute(
                    "UPDATE embedding_operations SET cancel_requested = 1, state = CASE WHEN state = 'DISPATCHED' THEN 'UNKNOWN' WHEN state IN('PREPARED','CACHE_READY') THEN 'CANCELLED' ELSE state END, error = CASE WHEN state = 'DISPATCHED' THEN ? ELSE error END, updated_at = ? WHERE kb_id = ? AND state IN('PREPARED','DISPATCHED','CACHE_READY')",
                    listOf(API_EMBEDDING_CANCEL_UNKNOWN_ERROR, Utc.nowIso(), kbId),
                )
            }
        }
    }

    fun rebuildIndex(kbId: String, acknowledgeDuplicateCharge: Boolean = false): String {
        if (isApiKnowledgeBase(kbId)) {
            return runBlocking { rebuildIndexCancellable(kbId, acknowledgeDuplicateCharge) }
        }
        return synchronized(indexLock) {
            requireKb(kbId)
            db.transaction { rebuildUnlocked(kbId) }
        }
    }

    fun repairIndexes() {
        // API rebuilds must leave the repository monitor before awaiting the
        // adapter.  The legacy local repair below remains serialized exactly
        // as before and explicitly skips API spaces.
        listKnowledgeBases()
            .map { it.first }
            .filter {
                isApiKnowledgeBase(it) && hasApiEmbeddingConsent(it) &&
                    latestUnknownEmbeddingOperation(it, "REBUILD") == null &&
                    db.query(
                        "SELECT embedding_space_id FROM knowledge_bases WHERE id = ? AND deleted_at IS NULL",
                        listOf(it),
                    ).singleOrNull()?.string("embedding_space_id")?.let { space -> apiEmbedderForSpace(space) != null } == true
            }
            .forEach { runBlocking { rebuildIndexCancellable(it) } }
        synchronized(indexLock) {
            db.query("SELECT id, active_version_id, blob_hash FROM documents WHERE deleted_at IS NULL AND active_version_id IS NOT NULL").forEach { doc ->
                val versionId = doc.string("active_version_id")
                val exists = db.query("SELECT id FROM document_versions WHERE id = ?", listOf(versionId))
                if (exists.isEmpty()) {
                    db.execute(
                        "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES (?,?,?,?,?,?)",
                        listOf(versionId, doc.string("id"), PARSER_FINGERPRINT, doc.string("blob_hash"), "READY", Utc.nowIso()),
                    )
                }
            }
            listKnowledgeBases().forEach { (kbId, _) ->
                val live = db.query(
                    "SELECT COUNT(*) AS n FROM documents WHERE kb_id = ? AND deleted_at IS NULL AND active_version_id IS NOT NULL",
                    listOf(kbId),
                ).single().long("n")
                if (live == 0L) return@forEach
                var space = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kbId))
                    .singleOrNull()?.string("embedding_space_id").orEmpty()
                if (space.isBlank()) {
                    // This is a one-time legacy repair, not a retrieval
                    // fallback: after this write the KB has an explicit fixed
                    // space and all query paths require that persisted value.
                    space = embedder.spaceId
                    db.execute(
                        "UPDATE knowledge_bases SET embedding_space_id = ? WHERE id = ? AND (embedding_space_id IS NULL OR embedding_space_id = '')",
                        listOf(space, kbId),
                    )
                }
                // A persisted API space may be intentionally unavailable
                // during early process startup.  Leave its previous
                // generation pinned until the explicitly selected adapter is
                // injected; migration/repair must not silently rebuild it in
                // the local fixture space.
                if (space != embedder.spaceId) return@forEach
                if (embeddingForSpace(space) == null) return@forEach
                val pin = pinnedReadyGeneration(kbId)
                val members = if (pin == null) 0L else {
                    db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id = ?", listOf(pin)).single().long("n")
                }
                if (pin == null || members == 0L) {
                    db.transaction { rebuildUnlocked(kbId) }
                }
            }
        }
    }

    private fun embeddingInputsForKnowledgeBase(kbId: String): List<EmbeddingInput> =
        embeddingInputsByVersionForKnowledgeBase(kbId).values.flatten()

    private fun embeddingInputsByVersionForKnowledgeBase(kbId: String): LinkedHashMap<String, List<EmbeddingInput>> {
        val versions = db.query(
            "SELECT id, active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL AND active_version_id IS NOT NULL",
            listOf(kbId),
        )
        return linkedMapOf<String, List<EmbeddingInput>>().also { chunksByVersion ->
            versions.forEach { doc ->
                val versionId = doc.string("active_version_id")
                chunksByVersion[versionId] = db.query(
                    "SELECT id, ordinal, text, content_hash FROM chunks WHERE document_version_id = ? ORDER BY ordinal",
                    listOf(versionId),
                ).map { row ->
                    EmbeddingInput(
                        chunkId = row.string("id"),
                        text = row.string("text"),
                        contentHash = row.string("content_hash").ifBlank {
                            sha256Hex(row.string("text").toByteArray(Charsets.UTF_8))
                        },
                    )
                }
            }
        }
    }

    private fun rebuildUnlocked(
        kbId: String,
        apiConsentGrantedForOperation: Boolean = false,
    ): String {
        val boundSpace = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(kbId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        check(boundSpace.isNotBlank()) {
            "Knowledge base $kbId has no fixed embedding space; refusing to rebuild"
        }
        if (boundSpace != embedder.spaceId &&
            !apiConsentGrantedForOperation &&
            !hasApiEmbeddingConsent(kbId)
        ) {
            error("API embedding rebuild requires explicit persisted consent")
        }
        val indexEmbedder = embedderForKnowledgeBase(kbId)
        check(boundSpace == indexEmbedder.spaceId) {
            "Knowledge base $kbId binding changed while rebuilding; refusing a mixed-space generation"
        }
        val chunksByVersion = embeddingInputsByVersionForKnowledgeBase(kbId)
        ensureEmbeddings(chunksByVersion.values.flatten(), indexEmbedder)
        return buildGenerationFromCachedUnlocked(kbId, indexEmbedder, chunksByVersion)
    }

    /**
     * Build only the derived FTS/ANN generation.  Every embedding must already
     * be present in the immutable embedding cache before this method is called;
     * it therefore never invokes a provider and is safe for the short finalize
     * transaction of an API operation.
     */
    private fun buildGenerationFromCachedUnlocked(
        kbId: String,
        indexEmbedder: TextEmbedder,
        chunksByVersion: LinkedHashMap<String, List<EmbeddingInput>> = embeddingInputsByVersionForKnowledgeBase(kbId),
    ): String {
        val generationId = EntityId.random().value
        var vectors = 0
        db.execute(
            "INSERT INTO index_generations(id,kb_id,space_id,manifest_hash,state,vector_count,fts_version,created_at) VALUES (?,?,?,?,?,?,?,?)",
            listOf(generationId, kbId, indexEmbedder.spaceId, generationId, "BUILDING", 0, 1, Utc.nowIso()),
        )
        chunksByVersion.forEach { (versionId, chunks) ->
            chunks.forEach { chunk ->
                val chunkId = chunk.chunkId
                val text = chunk.text
                val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id = ?", listOf(chunkId)).single().long("rid")
                runCatching { db.execute("DELETE FROM chunks_fts WHERE rowid = ?", listOf(rowid)) }
                runCatching { db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, CjkLexical.indexText(text))) }
                val stored = db.query(
                    "SELECT content_hash, vector_blob FROM embeddings WHERE chunk_id = ? AND space_id = ?",
                    listOf(chunkId, indexEmbedder.spaceId),
                ).singleOrNull() ?: error("embedding missing after rebuild")
                check(stored.string("content_hash") == chunk.contentHash) {
                    "embedding content hash changed for chunk $chunkId"
                }
                val bytes = embeddingBytes(stored)
                validateEmbeddingBytes(bytes, indexEmbedder.dimension)
                db.execute(
                    "INSERT OR IGNORE INTO generation_members(generation_id,chunk_id,space_id,document_version_id) VALUES (?,?,?,?)",
                    listOf(generationId, chunkId, indexEmbedder.spaceId, versionId),
                )
                vectors += 1
            }
        }
        val actualVectors = db.query(
            "SELECT COUNT(*) AS n FROM generation_members g JOIN embeddings e ON e.chunk_id = g.chunk_id AND e.space_id = g.space_id WHERE g.generation_id = ?",
            listOf(generationId),
        ).single().long("n").toInt()
        if (actualVectors != vectors) {
            db.execute("UPDATE index_generations SET state = ? WHERE id = ?", listOf("FAILED", generationId))
            error("rebuild vector count mismatch")
        }
        db.execute(
            "UPDATE index_generations SET state = ?, vector_count = ?, manifest_hash = ? WHERE id = ?",
            listOf("READY", vectors, sha256Hex("$generationId:$vectors".toByteArray()), generationId),
        )
        db.execute(
            "UPDATE knowledge_bases SET active_generation_id = ? WHERE id = ?",
            listOf(generationId, kbId),
        )
        return generationId
    }

    private fun importJobFromRow(
        row: SqlRow,
        visionConfigured: Boolean,
        stage: ImportStage,
        documentId: String = row.string("document_id"),
        knowledgeBaseId: String = row.string("kb_id"),
    ): ImportJob = ImportJob(
        id = row.string("id"),
        knowledgeBaseId = knowledgeBaseId,
        documentId = documentId,
        stage = stage,
        hasImages = row.boolean("has_images"),
        visionConfigured = visionConfigured,
        visionConsent = row.boolean("vision_consent"),
        embeddingIsApi = row.boolean("embedding_is_api"),
        embeddingConsent = row.boolean("embedding_consent"),
        localEmbeddingAvailable = true,
        error = row.string("error").ifBlank { null },
        consentedVisionFingerprint = row.string("vision_binding_json").ifBlank { null },
        visualGapsAccepted = stage == ImportStage.READY_WITH_VISUAL_GAPS ||
            row.string("error").startsWith(TEXT_ONLY_VISUAL_GAPS_PREFIX),
    )

    private suspend fun continueImportCancellable(
        job: ImportJob,
        displayName: String,
        bytes: ByteArray,
        format: SourceFormat,
    ): ImportJob {
        // A CACHE_READY operation is resumable without another provider call.
        // A DISPATCHED operation has an unknown external outcome (for example
        // after process death) and is converted to a durable gate instead of
        // being replayed automatically.
        activeEmbeddingOperationForJob(job.id)?.let { operation ->
            when (operation.state) {
                "CACHE_READY" -> {
                    finalizeEmbeddingOperation(operation.token)
                    finishPublished(job)
                    persistJob(job, displayName)
                    return job
                }
                "PREPARED" -> {
                    val selected = apiEmbedderForSpace(operation.spaceId)
                        ?: error("API embedding binding is unavailable; no text was sent")
                    executeEmbeddingOperation(operation, selected)
                    finalizeEmbeddingOperation(operation.token)
                    finishPublished(job)
                    persistJob(job, displayName)
                    return job
                }
                "DISPATCHED" -> {
                    markEmbeddingOperationUnknown(operation)
                    job.stage = ImportStage.FAILED
                    job.error = API_EMBEDDING_UNKNOWN_ERROR
                    persistJob(job, displayName)
                    throw EmbeddingUnknownOutcomeException()
                }
            }
        }
        try {
            advanceThrough(job, ImportStage.PARSING)
            when (format) {
                SourceFormat.IMAGE -> indexPublicationCancellable(job, bytes, standaloneImage(bytes, displayName))
                SourceFormat.TEXT, SourceFormat.MARKDOWN -> indexTextDocumentCancellable(job, bytes, format)
                SourceFormat.PDF -> indexPublicationCancellable(job, bytes, PdfParser.parse(bytes, pdfRasterizer))
                SourceFormat.OFFICE_ARCHIVE -> {
                    val inspection = ZipSafety.inspect(bytes)
                    if (!inspection.ok) {
                        fail(job, inspection.reason)
                    } else {
                        indexPublicationCancellable(job, bytes, OfficeParser.parse(displayName, bytes))
                    }
                }
                SourceFormat.KNOWLEDGE_ARCHIVE -> {
                    fail(job, "Knowledge ZIP datasets must be expanded as a batch and cannot resume as one document.")
                }
                SourceFormat.UNKNOWN -> fail(job, "Unsupported file type. The file was copied and was not dropped.")
            }
            persistJob(job, displayName)
            return job
        } catch (cancelled: CancellationException) {
            persistApiCancellation(job, displayName)
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            persistApiCancellation(job, displayName)
            throw interrupted
        } catch (unavailable: TextOnlyUnavailable) {
            throw unavailable
        } catch (failure: Throwable) {
            // Unknown embedding results are already persisted by the
            // operation state machine.  Keep the compatibility contract of
            // importBytes: return a FAILED job rather than replaying it.
            fail(job, failure.message ?: "API import failed")
            persistJob(job, displayName)
            return job
        }
    }

    private fun persistApiCancellation(job: ImportJob, displayName: String) {
        val operation = activeEmbeddingOperationForJob(job.id) ?: latestEmbeddingOperationForJob(job.id)
        when (operation?.state) {
            "DISPATCHED" -> {
                markEmbeddingOperationUnknown(operation)
                job.stage = ImportStage.FAILED
                job.error = API_EMBEDDING_CANCEL_UNKNOWN_ERROR
            }
            "UNKNOWN" -> {
                job.stage = ImportStage.FAILED
                job.error = API_EMBEDDING_CANCEL_UNKNOWN_ERROR
            }
            "PREPARED", "CACHE_READY" -> {
                operation?.let { markEmbeddingOperation(it.token, "CANCELLED", "Cancelled by user", onlyIfActive = true) }
                job.stage = ImportStage.CANCELLED
                job.error = "Cancelled by user"
            }
            "PUBLISHED" -> {
                // Finalize won the race with cancellation.  Preserve the
                // durable published outcome rather than overwriting it.
                finishPublished(job)
            }
            else -> {
                job.stage = ImportStage.CANCELLED
                job.error = "Cancelled by user"
            }
        }
        persistJob(job, displayName)
    }

    private fun displayNameForJob(jobId: String): String =
        db.query("SELECT display_name FROM import_jobs WHERE id = ?", listOf(jobId))
            .singleOrNull()?.string("display_name")?.ifBlank { jobId } ?: jobId

    private suspend fun indexTextDocumentCancellable(job: ImportJob, bytes: ByteArray, format: SourceFormat) {
        val text = String(bytes, Charsets.UTF_8)
        job.hasImages = format == SourceFormat.MARKDOWN && MediaKind.markdownReferencesImages(text)
        if (job.hasImages && !job.visualGapsAccepted) {
            advanceThrough(job, ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Markdown references images. They were not downloaded and the document is not READY."
            } else if (job.stage == ImportStage.AWAITING_UPLOAD_CONSENT) {
                job.error = "Markdown image files are not fetched automatically. Import the image files or grant Vision after they exist in CAS."
            }
            return
        }
        if (job.hasImages && job.visualGapsAccepted) {
            val chunks = TextChunker.chunk(text).map { IndexedChunk(it, null, emptyList(), null) }
            if (chunks.isEmpty()) {
                throw TextOnlyUnavailable("This file has no indexable text. Visual items stay waiting and were not marked READY.")
            }
            publishChunksCancellable(job, bytes, chunks, PARSER_FINGERPRINT)
            return
        }
        publishChunksCancellable(
            job,
            bytes,
            TextChunker.chunk(text).map { IndexedChunk(it, null, emptyList(), null) },
            PARSER_FINGERPRINT,
        )
    }

    private suspend fun indexPublicationCancellable(job: ImportJob, bytes: ByteArray, parsed: ParsedPublication) {
        val processable = parsed.assets.filter { it.kind == "IMAGE" && it.bytes.isNotEmpty() }
        val blocked = parsed.assets.filter {
            it.kind == "EXTERNAL" || it.kind == "MISSING" || it.kind == "PAGE" ||
                (it.kind == "IMAGE" && it.bytes.isEmpty())
        }
        job.hasImages = parsed.needsVision || processable.isNotEmpty() || blocked.isNotEmpty()
        val visionTexts = mutableListOf<IndexedChunk>()
        if (job.hasImages && job.visualGapsAccepted) {
            val chunks = textChunksSkippingVision(parsed)
            if (chunks.isEmpty()) {
                throw TextOnlyUnavailable("This file has no indexable text. Visual items stay waiting and were not marked READY.")
            }
            publishChunksCancellable(job, bytes, chunks, parsed.parserFingerprint)
            return
        }
        if (job.hasImages) {
            advanceThrough(job, if (job.visionConfigured) ImportStage.AWAITING_UPLOAD_CONSENT else ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Visual content is waiting for a Vision model and is not READY."
                return
            }
            if (job.stage == ImportStage.AWAITING_UPLOAD_CONSENT && !job.visionConsent) {
                job.error = "Vision upload has not been approved. No image bytes left the device."
                return
            }
            if (!visionBindingMatches(job)) {
                job.visionConsent = false
                job.stage = ImportStage.AWAITING_UPLOAD_CONSENT
                job.error = "Vision destination changed. Approve upload to the current Provider and model. No image bytes left the device."
                return
            }
            job.visionConsent = true
            job.consentedVisionFingerprint = visionFingerprint()
            if (blocked.isNotEmpty()) {
                fail(job, "Visual pages or external/missing images cannot be processed without local raster bytes. Nothing was downloaded.")
                return
            }
            if (processable.isEmpty()) {
                fail(job, "needsVision is set but there is no processable page or image asset. The document is not READY.")
                return
            }
            advanceThrough(job, ImportStage.VISION_PROCESSING)
            when (val outcome = processAssets(job, processable)) {
                is VisionBatch.Failed -> {
                    fail(job, outcome.message)
                    return
                }
                is VisionBatch.Unknown -> {
                    job.stage = ImportStage.FAILED
                    job.error = "UNKNOWN_OUTCOME: Vision result is uncertain and was not billed as success. Retry is manual."
                    return
                }
                is VisionBatch.Ok -> visionTexts += outcome.chunks
            }
        }
        val pageChunks = parsed.pages.filter { it.text.isNotBlank() && !it.needsVision }.flatMap { page ->
            TextChunker.chunk(page.text).map { IndexedChunk(it, page.page, emptyList(), "page:${page.page}") }
        }
        val chunks = (pageChunks + visionTexts).ifEmpty {
            if (!parsed.needsVision && parsed.text.isNotBlank()) {
                TextChunker.chunk(parsed.text).map { IndexedChunk(it, 1, emptyList(), null) }
            } else {
                emptyList()
            }
        }
        if (chunks.isEmpty()) {
            fail(job, "The file produced no indexable text. Visual items were not dropped.")
            return
        }
        publishChunksCancellable(job, bytes, chunks, parsed.parserFingerprint, processable)
    }

    private fun continueImport(job: ImportJob, displayName: String, bytes: ByteArray, format: SourceFormat): ImportJob {
        advanceThrough(job, ImportStage.PARSING)
        when (format) {
            SourceFormat.IMAGE -> {
                try {
                    indexPublication(job, bytes, standaloneImage(bytes, displayName))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                } catch (unavailable: TextOnlyUnavailable) {
                    throw unavailable
                } catch (t: Throwable) {
                    fail(job, t.message ?: "image import failed")
                }
            }
            SourceFormat.TEXT, SourceFormat.MARKDOWN -> {
                try {
                    indexTextDocument(job, bytes, format)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                } catch (unavailable: TextOnlyUnavailable) {
                    throw unavailable
                } catch (t: Throwable) {
                    fail(job, t.message ?: "indexing failed")
                }
            }
            SourceFormat.PDF -> {
                try {
                    indexPublication(job, bytes, PdfParser.parse(bytes, pdfRasterizer))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                } catch (unavailable: TextOnlyUnavailable) {
                    throw unavailable
                } catch (t: Throwable) {
                    fail(job, t.message ?: "PDF import failed")
                }
            }
            SourceFormat.OFFICE_ARCHIVE -> {
                val inspection = ZipSafety.inspect(bytes)
                if (!inspection.ok) {
                    fail(job, inspection.reason)
                } else {
                    try {
                        indexPublication(job, bytes, OfficeParser.parse(displayName, bytes))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interrupted
                    } catch (unavailable: TextOnlyUnavailable) {
                        throw unavailable
                    } catch (t: Throwable) {
                        fail(job, t.message ?: "DOCX/EPUB import failed")
                    }
                }
            }
            SourceFormat.KNOWLEDGE_ARCHIVE -> fail(job, "Knowledge ZIP datasets must be expanded as a batch and cannot resume as one document.")
            SourceFormat.UNKNOWN -> fail(job, "Unsupported file type. The file was copied and was not dropped.")
        }
        persistJob(job, displayName)
        return job
    }

    private fun standaloneImage(bytes: ByteArray, displayName: String): ParsedPublication {
        val mime = when {
            displayName.lowercase().endsWith(".png") -> "image/png"
            displayName.lowercase().endsWith(".jpg") || displayName.lowercase().endsWith(".jpeg") -> "image/jpeg"
            else -> "image/*"
        }
        return ParsedPublication(
            format = SourceFormat.IMAGE,
            text = "",
            pages = listOf(ExtractedPage(1, "", needsVision = true)),
            assets = listOf(
                ExtractedAsset("image-1", "IMAGE", 1, displayName, bytes, mime, ""),
            ),
            needsVision = true,
            parserFingerprint = "image-v1",
        )
    }

    private fun indexTextDocument(job: ImportJob, bytes: ByteArray, format: SourceFormat) {
        val text = String(bytes, Charsets.UTF_8)
        job.hasImages = format == SourceFormat.MARKDOWN && MediaKind.markdownReferencesImages(text)
        if (job.hasImages && !job.visualGapsAccepted) {
            advanceThrough(job, ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Markdown references images. They were not downloaded and the document is not READY."
            } else if (job.stage == ImportStage.AWAITING_UPLOAD_CONSENT) {
                job.error = "Markdown image files are not fetched automatically. Import the image files or grant Vision after they exist in CAS."
            }
            return
        }
        if (job.hasImages && job.visualGapsAccepted) {
            val chunks = TextChunker.chunk(text).map { IndexedChunk(it, null, emptyList(), null) }
            if (chunks.isEmpty()) {
                throw TextOnlyUnavailable("This file has no indexable text. Visual items stay waiting and were not marked READY.")
            }
            publishChunks(job, bytes, chunks, PARSER_FINGERPRINT)
            return
        }
        publishChunks(job, bytes, textChunks = TextChunker.chunk(text).map { IndexedChunk(it, null, emptyList(), null) }, fingerprint = PARSER_FINGERPRINT)
    }

    private fun indexPublication(job: ImportJob, bytes: ByteArray, parsed: ParsedPublication) {
        val processable = parsed.assets.filter { it.kind == "IMAGE" && it.bytes.isNotEmpty() }
        val blocked = parsed.assets.filter {
            it.kind == "EXTERNAL" || it.kind == "MISSING" || it.kind == "PAGE" ||
                (it.kind == "IMAGE" && it.bytes.isEmpty())
        }
        job.hasImages = parsed.needsVision || processable.isNotEmpty() || blocked.isNotEmpty()
        val visionTexts = mutableListOf<IndexedChunk>()
        if (job.hasImages && job.visualGapsAccepted) {
            val chunks = textChunksSkippingVision(parsed)
            if (chunks.isEmpty()) {
                throw TextOnlyUnavailable("This file has no indexable text. Visual items stay waiting and were not marked READY.")
            }
            publishChunks(job, bytes, chunks, parsed.parserFingerprint)
            return
        }
        if (job.hasImages) {
            advanceThrough(job, if (job.visionConfigured) ImportStage.AWAITING_UPLOAD_CONSENT else ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Visual content is waiting for a Vision model and is not READY."
                return
            }
            if (job.stage == ImportStage.AWAITING_UPLOAD_CONSENT && !job.visionConsent) {
                job.error = "Vision upload has not been approved. No image bytes left the device."
                return
            }
            if (!visionBindingMatches(job)) {
                job.visionConsent = false
                job.stage = ImportStage.AWAITING_UPLOAD_CONSENT
                job.error = "Vision destination changed. Approve upload to the current Provider and model. No image bytes left the device."
                return
            }
            job.visionConsent = true
            job.consentedVisionFingerprint = visionFingerprint()
            if (blocked.isNotEmpty()) {
                fail(
                    job,
                    "Visual pages or external/missing images cannot be processed without local raster bytes. Nothing was downloaded.",
                )
                return
            }
            if (processable.isEmpty()) {
                fail(job, "needsVision is set but there is no processable page or image asset. The document is not READY.")
                return
            }
            advanceThrough(job, ImportStage.VISION_PROCESSING)
            when (val outcome = processAssets(job, processable)) {
                is VisionBatch.Failed -> {
                    fail(job, outcome.message)
                    return
                }
                is VisionBatch.Unknown -> {
                    job.stage = ImportStage.FAILED
                    job.error = "UNKNOWN_OUTCOME: Vision result is uncertain and was not billed as success. Retry is manual."
                    return
                }
                is VisionBatch.Ok -> visionTexts += outcome.chunks
            }
        }
        val pageChunks = parsed.pages.filter { it.text.isNotBlank() && !it.needsVision }.flatMap { page ->
            TextChunker.chunk(page.text).map { IndexedChunk(it, page.page, emptyList(), "page:${page.page}") }
        }
        val chunks = (pageChunks + visionTexts).ifEmpty {
            if (!parsed.needsVision && parsed.text.isNotBlank()) {
                TextChunker.chunk(parsed.text).map { IndexedChunk(it, 1, emptyList(), null) }
            } else {
                emptyList()
            }
        }
        if (chunks.isEmpty()) {
            fail(job, "The file produced no indexable text. Visual items were not dropped.")
            return
        }
        publishChunks(job, bytes, chunks, parsed.parserFingerprint, processable)
    }

    private sealed interface VisionBatch {
        data class Ok(val chunks: List<IndexedChunk>) : VisionBatch
        data class Failed(val message: String) : VisionBatch
        data object Unknown : VisionBatch
    }

    private fun processAssets(job: ImportJob, assets: List<ExtractedAsset>): VisionBatch {
        val backend = vision ?: return VisionBatch.Failed("Vision model is configured in profile but no backend is bound")
        val chunks = mutableListOf<IndexedChunk>()
        assets.forEach { asset ->
            if (asset.bytes.isEmpty() || asset.kind != "IMAGE") {
                return VisionBatch.Failed("Visual asset ${asset.localId} is not rasterizable and was not downloaded")
            }
            val stored = blobs.put(asset.bytes, asset.mediaType)
            upsertBlob(stored)
            val assetId = EntityId.random().value
            val contextHash = VisionCacheKey.contextHash(asset.surroundingText, asset.page, asset.section)
            val input = VisionInput(
                assetHash = stored.sha256,
                contextHash = contextHash,
                modelFingerprint = visionFingerprint(),
                bytes = asset.bytes,
                mediaType = asset.mediaType,
                surroundingText = asset.surroundingText,
                page = asset.page,
                section = asset.section,
            )
            db.execute(
                "INSERT OR REPLACE INTO assets(id,document_id,document_version_id,blob_hash,page,section,kind,surrounding_text_hash) VALUES (?,?,?,?,?,?,?,?)",
                listOf(assetId, job.documentId, null, stored.sha256, asset.page, asset.section, asset.kind, contextHash),
            )
            val cached = db.query(
                "SELECT status, ocr_text, description, table_markdown, result_type FROM vision_results WHERE cache_key = ?",
                listOf(input.cacheKey),
            ).singleOrNull()
            val outcome = when (cached?.string("status")) {
                "SUCCESS" -> VisionOutcome.Success(
                    runtime.mobileagent.knowledge.VisionSuccess(
                        ocrText = cached.string("ocr_text"),
                        semanticDescription = cached.string("description"),
                        tableMarkdown = cached.string("table_markdown"),
                        type = cached.string("result_type").ifBlank { "image" },
                    ),
                )
                "UNKNOWN_OUTCOME" -> VisionOutcome.UnknownOutcome
                else -> backend.process(input)
            }
            when (outcome) {
                is VisionOutcome.UnknownOutcome -> {
                    persistVision(input.cacheKey, stored.sha256, contextHash, "UNKNOWN_OUTCOME", "", "", "", "")
                    return VisionBatch.Unknown
                }
                is VisionOutcome.Failed -> {
                    persistVision(input.cacheKey, stored.sha256, contextHash, "FAILED", "", outcome.message, "", "")
                    return VisionBatch.Failed(outcome.message)
                }
                is VisionOutcome.Success -> {
                    persistVision(
                        input.cacheKey,
                        stored.sha256,
                        contextHash,
                        "SUCCESS",
                        outcome.result.ocrText,
                        outcome.result.semanticDescription,
                        outcome.result.tableMarkdown,
                        outcome.result.type,
                    )
                    val body = buildString {
                        append("Visual evidence")
                        asset.page?.let { append(" page $it") }
                        append(": ")
                        append(outcome.result.semanticDescription)
                        if (outcome.result.ocrText.isNotBlank()) {
                            append('\n')
                            append(outcome.result.ocrText)
                        }
                        if (outcome.result.tableMarkdown.isNotBlank()) {
                            append('\n')
                            append(outcome.result.tableMarkdown)
                        }
                        if (asset.surroundingText.isNotBlank()) {
                            append('\n')
                            append(asset.surroundingText)
                        }
                    }
                    chunks += IndexedChunk(body, asset.page, listOf(assetId), asset.section)
                }
            }
        }
        return VisionBatch.Ok(chunks)
    }

    private fun persistVision(
        cacheKey: String,
        assetHash: String,
        contextHash: String,
        status: String,
        ocr: String,
        description: String,
        tableMarkdown: String,
        type: String,
    ) {
        db.execute(
            "INSERT OR REPLACE INTO vision_results(cache_key,asset_hash,context_hash,model_fingerprint,prompt_version,schema_version,status,ocr_text,description,table_markdown,result_type,processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                cacheKey,
                assetHash,
                contextHash,
                visionFingerprint(),
                VISION_PROMPT_VERSION,
                VISION_SCHEMA_VERSION,
                status,
                ocr,
                description,
                tableMarkdown,
                type,
                Utc.nowIso(),
            ),
        )
    }

    private data class IndexedChunk(
        val text: String,
        val page: Int?,
        val assetIds: List<String>,
        val span: String?,
    )

    private data class EmbeddingInput(
        val chunkId: String,
        val text: String,
        val contentHash: String,
    )

    private data class EmbeddingOperationRecord(
        val token: String,
        val kind: String,
        val knowledgeBaseId: String,
        val jobId: String?,
        val documentId: String?,
        val documentVersionId: String?,
        val spaceId: String,
        val inputManifestHash: String,
        val bindingFingerprint: String,
        val consentFingerprint: String,
        val state: String,
        val cancelRequested: Boolean,
        val error: String,
    )

    private data class PreparedEmbeddingOperation(
        val record: EmbeddingOperationRecord,
        val inputsByVersion: LinkedHashMap<String, List<EmbeddingInput>>,
    )

    private class OperationAborted(message: String) : IllegalStateException(message)

    private fun activeDocumentPointers(knowledgeBaseId: String): LinkedHashMap<String, String> =
        linkedMapOf<String, String>().also { pointers ->
            db.query(
                "SELECT id, active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id",
                listOf(knowledgeBaseId),
            ).forEach { row -> pointers[row.string("id")] = row.string("active_version_id") }
            // The active generation is also a publication pointer.  Include
            // it in the operation manifest so an unrelated local rebuild
            // cannot be silently overwritten by a later API finalize.
            pointers[ACTIVE_GENERATION_POINTER] = db.query(
                "SELECT active_generation_id FROM knowledge_bases WHERE id = ?",
                listOf(knowledgeBaseId),
            ).singleOrNull()?.string("active_generation_id").orEmpty()
        }

    private fun operationManifestHash(
        kind: String,
        knowledgeBaseId: String,
        targetSpace: String,
        sourceSpace: String,
        inputsByVersion: Map<String, List<EmbeddingInput>>,
        activePointers: Map<String, String>,
    ): String {
        val canonical = buildString {
            append("kind=").append(kind)
            append("|kb=").append(knowledgeBaseId)
            append("|target=").append(targetSpace)
            append("|source=").append(sourceSpace)
            activePointers.toSortedMap().forEach { (documentId, versionId) ->
                append("|active=").append(documentId).append(':').append(versionId)
            }
            inputsByVersion.toSortedMap().forEach { (versionId, inputs) ->
                append("|version=").append(versionId)
                inputs.sortedWith(compareBy<EmbeddingInput> { it.chunkId }.thenBy { it.contentHash })
                    .forEach { input ->
                        val hash = input.contentHash.ifBlank {
                            sha256Hex(input.text.toByteArray(Charsets.UTF_8))
                        }
                        append('|').append(input.chunkId).append('=').append(hash)
                    }
            }
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun apiConsentFingerprint(
        kind: String,
        knowledgeBaseId: String,
        spaceId: String,
        jobId: String? = null,
        sourceSpace: String = spaceId,
    ): String = sha256Hex(
        "api-consent|kind=$kind|kb=$knowledgeBaseId|space=$spaceId|source=$sourceSpace|job=${jobId.orEmpty()}"
            .toByteArray(Charsets.UTF_8),
    )

    private fun embeddingOperation(row: SqlRow): EmbeddingOperationRecord = EmbeddingOperationRecord(
        token = row.string("token"),
        kind = row.string("kind"),
        knowledgeBaseId = row.string("kb_id"),
        jobId = row.string("job_id").ifBlank { null },
        documentId = row.string("document_id").ifBlank { null },
        documentVersionId = row.string("document_version_id").ifBlank { null },
        spaceId = row.string("space_id"),
        inputManifestHash = row.string("input_manifest_hash"),
        bindingFingerprint = row.string("binding_fingerprint"),
        consentFingerprint = row.string("consent_fingerprint"),
        state = row.string("state"),
        cancelRequested = row.boolean("cancel_requested"),
        error = row.string("error"),
    )

    private fun operationByToken(token: String): EmbeddingOperationRecord? =
        db.query("SELECT * FROM embedding_operations WHERE token = ?", listOf(token))
            .singleOrNull()?.let(::embeddingOperation)

    private fun activeEmbeddingOperation(knowledgeBaseId: String): EmbeddingOperationRecord? =
        db.query(
            "SELECT * FROM embedding_operations WHERE kb_id = ? AND state IN('PREPARED','DISPATCHED','CACHE_READY') ORDER BY created_at LIMIT 1",
            listOf(knowledgeBaseId),
        ).singleOrNull()?.let(::embeddingOperation)

    private fun latestUnknownEmbeddingOperation(knowledgeBaseId: String, kind: String): EmbeddingOperationRecord? =
        db.query(
            "SELECT * FROM embedding_operations WHERE kb_id = ? AND kind = ? AND state = 'UNKNOWN' ORDER BY updated_at DESC LIMIT 1",
            listOf(knowledgeBaseId, kind),
        ).singleOrNull()?.let(::embeddingOperation)

    private fun activeEmbeddingOperationForJob(jobId: String): EmbeddingOperationRecord? =
        db.query(
            "SELECT * FROM embedding_operations WHERE job_id = ? AND state IN('PREPARED','DISPATCHED','CACHE_READY') ORDER BY created_at LIMIT 1",
            listOf(jobId),
        ).singleOrNull()?.let(::embeddingOperation)

    private fun latestEmbeddingOperationForJob(jobId: String): EmbeddingOperationRecord? =
        db.query(
            "SELECT * FROM embedding_operations WHERE job_id = ? ORDER BY created_at DESC LIMIT 1",
            listOf(jobId),
        ).singleOrNull()?.let(::embeddingOperation)

    private fun inputsForVersion(documentVersionId: String): List<EmbeddingInput> =
        db.query(
            "SELECT id, ordinal, text, content_hash FROM chunks WHERE document_version_id = ? ORDER BY ordinal",
            listOf(documentVersionId),
        ).map { row ->
            EmbeddingInput(
                chunkId = row.string("id"),
                text = row.string("text"),
                contentHash = row.string("content_hash").ifBlank {
                    sha256Hex(row.string("text").toByteArray(Charsets.UTF_8))
                },
            )
        }

    private fun operationInputsByVersion(operation: EmbeddingOperationRecord): LinkedHashMap<String, List<EmbeddingInput>> {
        val versionId = operation.documentVersionId
        if (!versionId.isNullOrBlank()) {
            return linkedMapOf(versionId to inputsForVersion(versionId))
        }
        return embeddingInputsByVersionForKnowledgeBase(operation.knowledgeBaseId)
    }

    private fun insertEmbeddingOperation(
        kind: String,
        knowledgeBaseId: String,
        jobId: String?,
        documentId: String?,
        documentVersionId: String?,
        spaceId: String,
        inputManifestHash: String,
        consentFingerprint: String,
    ): EmbeddingOperationRecord {
        val token = EntityId.random().value
        val now = Utc.nowIso()
        db.execute(
            "INSERT INTO embedding_operations(token,kind,kb_id,job_id,document_id,document_version_id,space_id,input_manifest_hash,binding_fingerprint,consent_fingerprint,state,cancel_requested,error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                token,
                kind,
                knowledgeBaseId,
                jobId,
                documentId,
                documentVersionId,
                spaceId,
                inputManifestHash,
                spaceId,
                consentFingerprint,
                "PREPARED",
                0,
                "",
                now,
                now,
            ),
        )
        return operationByToken(token) ?: error("embedding operation disappeared after insert")
    }

    private fun markEmbeddingOperation(
        token: String,
        state: String,
        error: String = "",
        onlyIfActive: Boolean = false,
    ): EmbeddingOperationRecord = synchronized(indexLock) {
        db.transaction {
            val where = if (onlyIfActive) {
                " AND state IN('PREPARED','DISPATCHED','CACHE_READY')"
            } else {
                ""
            }
            db.execute(
                "UPDATE embedding_operations SET state = ?, error = ?, updated_at = ? WHERE token = ?$where",
                listOf(state, error, Utc.nowIso(), token),
            )
            operationByToken(token) ?: error("embedding operation disappeared")
        }
    }

    private fun requestEmbeddingOperationCancelForJob(jobId: String): EmbeddingOperationRecord? = synchronized(indexLock) {
        db.transaction {
            val current = activeEmbeddingOperationForJob(jobId) ?: return@transaction null
            db.execute(
                "UPDATE embedding_operations SET cancel_requested = 1, state = CASE WHEN state = 'DISPATCHED' THEN 'UNKNOWN' WHEN state IN('PREPARED','CACHE_READY') THEN 'CANCELLED' ELSE state END, error = CASE WHEN state = 'DISPATCHED' THEN ? ELSE error END, updated_at = ? WHERE token = ?",
                listOf(API_EMBEDDING_CANCEL_UNKNOWN_ERROR, Utc.nowIso(), current.token),
            )
            operationByToken(current.token)
        }
    }

    private fun stageEmbeddingCacheHits(
        inputsByVersion: LinkedHashMap<String, List<EmbeddingInput>>,
        spaceId: String,
        dimension: Int,
    ): LinkedHashMap<String, MutableList<EmbeddingInput>> {
        val pending = linkedMapOf<String, MutableList<EmbeddingInput>>()
        inputsByVersion.values.flatten().forEach { input ->
            val contentHash = input.contentHash.ifBlank {
                sha256Hex(input.text.toByteArray(Charsets.UTF_8))
            }
            val existing = storedEmbedding(input.chunkId, spaceId)
            if (existing != null) {
                check(existing.contentHash == contentHash) {
                    "embedding content hash changed for chunk ${input.chunkId}"
                }
                validateEmbeddingBytes(existing.bytes, dimension)
                return@forEach
            }
            val cached = cachedEmbedding(spaceId, contentHash)
            if (cached != null) {
                validateEmbeddingBytes(cached.bytes, dimension)
                insertEmbedding(input.chunkId, spaceId, cached.bytes, contentHash)
            } else {
                pending.getOrPut(contentHash) { mutableListOf() }
                    .add(input.copy(contentHash = contentHash))
            }
        }
        return pending
    }

    private fun markEmbeddingOperationUnknown(operation: EmbeddingOperationRecord): EmbeddingOperationRecord {
        val updated = markEmbeddingOperation(
            operation.token,
            state = "UNKNOWN",
            error = API_EMBEDDING_UNKNOWN_ERROR,
            onlyIfActive = true,
        )
        if (!operation.jobId.isNullOrBlank()) {
            synchronized(indexLock) {
                db.transaction {
                    db.execute(
                        "UPDATE import_jobs SET stage = ?, error = ?, updated_at = ? WHERE id = ? AND stage <> ?",
                        listOf(
                            ImportStage.FAILED.name,
                            API_EMBEDDING_UNKNOWN_ERROR,
                            Utc.nowIso(),
                            operation.jobId,
                            ImportStage.CANCELLED.name,
                        ),
                    )
                    syncBatchItemFromJobLocked(operation.jobId)
                }
            }
        }
        if (operation.kind == "REBIND") {
            ApiEmbeddingBinding.parseSpaceId(operation.spaceId)?.let {
                persistRebindUnknownGate(operation.knowledgeBaseId, it)
            }
        }
        return updated
    }

    private fun dispatchEmbeddingOperation(operation: EmbeddingOperationRecord): EmbeddingOperationRecord {
        return synchronized(indexLock) {
            db.transaction {
                val current = operationByToken(operation.token) ?: error("embedding operation not found")
                check(current.state == "PREPARED") {
                    when (current.state) {
                        "UNKNOWN" -> API_EMBEDDING_UNKNOWN_ERROR
                        "CANCELLED" -> "API embedding operation was cancelled"
                        else -> "embedding operation is not dispatchable in state ${current.state}"
                    }
                }
                check(!current.cancelRequested) { "API embedding operation was cancelled before dispatch" }
                db.execute(
                    "UPDATE embedding_operations SET state = 'DISPATCHED', updated_at = ? WHERE token = ? AND state = 'PREPARED' AND cancel_requested = 0",
                    listOf(Utc.nowIso(), operation.token),
                )
                operationByToken(operation.token) ?: error("embedding operation disappeared")
            }
        }
    }

    /**
     * Recheck the operation's local safety boundary immediately before the
     * provider call.  Preparation and dispatch are deliberately separate so a
     * concurrent cancel/delete/revoke can win without ever sending text.  No
     * provider code is called from this transaction.
     */
    private fun verifyEmbeddingOperationBeforeProvider(operation: EmbeddingOperationRecord): EmbeddingOperationRecord =
        synchronized(indexLock) {
            db.transaction {
                val current = operationByToken(operation.token) ?: error("embedding operation not found")
                check(current.state == "DISPATCHED") {
                    when (current.state) {
                        "UNKNOWN" -> API_EMBEDDING_UNKNOWN_ERROR
                        "CANCELLED" -> "API embedding operation was cancelled"
                        else -> "embedding operation is not dispatched in state ${current.state}"
                    }
                }
                check(!current.cancelRequested) { "API embedding operation was cancelled before provider request" }
                val kb = db.query(
                    "SELECT embedding_space_id, deleted_at FROM knowledge_bases WHERE id = ?",
                    listOf(current.knowledgeBaseId),
                ).singleOrNull() ?: error("knowledge base not found")
                check(kb.string("deleted_at").isBlank()) { "knowledge base deleted before provider request" }
                val currentSpace = kb.string("embedding_space_id")
                if (current.kind == "REBIND") {
                    check(currentSpace != current.spaceId) {
                        "knowledge base rebind target changed before provider request"
                    }
                } else {
                    check(currentSpace == current.spaceId) {
                        "knowledge base binding changed before provider request"
                    }
                }
                check(current.bindingFingerprint == current.spaceId) {
                    "embedding operation binding fingerprint changed before provider request"
                }
                val sourceSpace = if (current.kind == "REBIND") currentSpace else current.spaceId
                check(
                    operationManifestHash(
                        kind = current.kind,
                        knowledgeBaseId = current.knowledgeBaseId,
                        targetSpace = current.spaceId,
                        sourceSpace = sourceSpace,
                        inputsByVersion = operationInputsByVersion(current),
                        activePointers = activeDocumentPointers(current.knowledgeBaseId),
                    ) == current.inputManifestHash,
                ) {
                    "knowledge base inputs or active document pointers changed before provider request"
                }
                current.documentId?.let { documentId ->
                    val document = db.query(
                        "SELECT kb_id, deleted_at FROM documents WHERE id = ?",
                        listOf(documentId),
                    ).singleOrNull() ?: error("document not found before provider request")
                    check(document.string("kb_id") == current.knowledgeBaseId && document.string("deleted_at").isBlank()) {
                        "document was deleted or moved before provider request"
                    }
                }
                current.jobId?.let { jobId ->
                    val job = db.query(
                        "SELECT embedding_is_api, embedding_consent, stage FROM import_jobs WHERE id = ?",
                        listOf(jobId),
                    ).singleOrNull() ?: error("import job not found before provider request")
                    check(job.boolean("embedding_is_api") && job.boolean("embedding_consent")) {
                        "API embedding consent was revoked before provider request"
                    }
                    check(job.string("stage") != ImportStage.CANCELLED.name) {
                        "API embedding job was cancelled before provider request"
                    }
                }
                current
            }
        }

    private suspend fun executeEmbeddingOperation(
        operation: EmbeddingOperationRecord,
        selectedEmbedder: TextEmbedder,
    ): EmbeddingOperationRecord {
        val inputsByVersion = try {
            operationInputsByVersion(operation)
        } catch (failure: Throwable) {
            // No provider request has been dispatched yet.  Keep this local
            // preparation failure distinct from an uncertain external result.
            markEmbeddingOperation(operation.token, "FAILED", API_EMBEDDING_FAILED_ERROR, onlyIfActive = true)
            throw failure
        }
        val pending = try {
            synchronized(indexLock) {
                db.transaction {
                    stageEmbeddingCacheHits(inputsByVersion, operation.spaceId, selectedEmbedder.dimension)
                }
            }
        } catch (failure: Throwable) {
            markEmbeddingOperation(operation.token, "FAILED", API_EMBEDDING_FAILED_ERROR, onlyIfActive = true)
            throw failure
        }
        if (pending.isEmpty()) {
            val current = operationByToken(operation.token) ?: error("embedding operation not found")
            if (current.state == "PREPARED") {
                return markEmbeddingOperation(current.token, "CACHE_READY", onlyIfActive = true)
            }
            return current
        }
        val dispatched = dispatchEmbeddingOperation(operation)
        try {
            val readyToSend = verifyEmbeddingOperationBeforeProvider(dispatched)
            val representatives = pending.values.map { it.first() }
            val vectors = when (selectedEmbedder) {
                is CancellableBatchTextEmbedder ->
                    selectedEmbedder.embedBatchCancellable(representatives.map { it.text })
                is BatchTextEmbedder -> selectedEmbedder.embedBatch(representatives.map { it.text })
                else -> representatives.map { selectedEmbedder.embed(it.text) }
            }
            check(vectors.size == representatives.size) {
                "embedding backend returned ${vectors.size} vectors for ${representatives.size} cache misses"
            }
            val bytesByHash = pending.keys.zip(vectors).associate { (contentHash, vector) ->
                validateEmbeddingVector(vector, selectedEmbedder.dimension)
                contentHash to floatsToBytes(vector)
            }
            // This is the independent successful-cache commit.  It is kept
            // separate from generation publication so a later SQL/JNI failure
            // cannot make the provider request run again.
            synchronized(indexLock) {
                db.transaction {
                    val current = operationByToken(dispatched.token) ?: error("embedding operation not found")
                    check(current.state == "DISPATCHED" || current.state == "UNKNOWN") {
                        "embedding operation changed while provider request was in flight"
                    }
                    pending.forEach { (contentHash, inputs) ->
                        val bytes = bytesByHash.getValue(contentHash)
                        inputs.forEach { input ->
                            insertEmbedding(input.chunkId, dispatched.spaceId, bytes, contentHash)
                        }
                    }
                    if (current.state == "DISPATCHED" && !current.cancelRequested) {
                        db.execute(
                            "UPDATE embedding_operations SET state = 'CACHE_READY', error = '', updated_at = ? WHERE token = ? AND state = 'DISPATCHED' AND cancel_requested = 0",
                            listOf(Utc.nowIso(), dispatched.token),
                        )
                    }
                }
            }
            return operationByToken(readyToSend.token) ?: error("embedding operation disappeared")
        } catch (cancelled: CancellationException) {
            markEmbeddingOperationUnknown(dispatched)
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            markEmbeddingOperationUnknown(dispatched)
            throw interrupted
        } catch (failure: Throwable) {
            // Once DISPATCHED was durably recorded, every subsequent failure
            // has an uncertain external outcome, including malformed output,
            // local cache commit errors, and ordinary transport exceptions.
            // Never classify it as a retryable FAILED operation.
            markEmbeddingOperationUnknown(dispatched)
            throw failure
        }
    }

    private fun validateOperationCache(operation: EmbeddingOperationRecord, inputsByVersion: LinkedHashMap<String, List<EmbeddingInput>>, dimension: Int) {
        inputsByVersion.values.flatten().forEach { input ->
            val expectedHash = input.contentHash.ifBlank {
                sha256Hex(input.text.toByteArray(Charsets.UTF_8))
            }
            val stored = storedEmbedding(input.chunkId, operation.spaceId)
                ?: error("embedding cache missing for chunk ${input.chunkId}")
            check(stored.contentHash == expectedHash) {
                "embedding content hash changed for chunk ${input.chunkId}"
            }
            validateEmbeddingBytes(stored.bytes, dimension)
        }
    }

    private fun operationDimension(operation: EmbeddingOperationRecord): Int {
        ApiEmbeddingBinding.parseSpaceId(operation.spaceId)?.dimension?.let { return it }
        val row = db.query(
            "SELECT vector_blob FROM embeddings WHERE space_id = ? LIMIT 1",
            listOf(operation.spaceId),
        ).singleOrNull() ?: error("API embedding dimension is unavailable")
        val bytes = embeddingBytes(row)
        check(bytes.isNotEmpty() && bytes.size % 4 == 0) { "API embedding dimension is unavailable" }
        return bytes.size / 4
    }

    private fun currentOperationEmbedder(operation: EmbeddingOperationRecord): TextEmbedder =
        apiEmbedderForSpace(operation.spaceId)
            ?: CachedQueryEmbedder(operation.spaceId, operationDimension(operation))

    /**
     * Finalize one operation in a short CAS transaction.  Provider calls have
     * already completed and all successful vectors are durable at this point.
     */
    private fun finalizeEmbeddingOperation(token: String): String {
        val initial = operationByToken(token) ?: error("embedding operation not found")
        val selectedEmbedder = currentOperationEmbedder(initial)
        return try {
            synchronized(indexLock) {
                db.transaction {
                    val operation = operationByToken(token) ?: error("embedding operation not found")
                    if (operation.state != "CACHE_READY") {
                        when (operation.state) {
                            "UNKNOWN" -> throw EmbeddingUnknownOutcomeException()
                            "CANCELLED" -> error("API embedding operation was cancelled")
                            else -> error("embedding operation is not ready to publish: ${operation.state}")
                        }
                    }
                    if (operation.cancelRequested) error("API embedding operation was cancelled")
                    check(operation.bindingFingerprint == operation.spaceId) {
                        "embedding operation binding fingerprint changed"
                    }
                    val kb = db.query(
                        "SELECT embedding_space_id, deleted_at FROM knowledge_bases WHERE id = ?",
                        listOf(operation.knowledgeBaseId),
                    ).singleOrNull() ?: throw OperationAborted("knowledge base was removed while embedding")
                    val currentSpace = kb.string("embedding_space_id")
                    if (kb.string("deleted_at").isNotBlank()) {
                        throw OperationAborted("knowledge base was deleted while embedding")
                    }
                    val expectedConsentFingerprint = when (operation.kind) {
                        "IMPORT" -> apiConsentFingerprint(
                            "IMPORT",
                            operation.knowledgeBaseId,
                            operation.spaceId,
                            operation.jobId,
                        )
                        "REBUILD" -> apiConsentFingerprint("REBUILD", operation.knowledgeBaseId, operation.spaceId)
                        "REBIND" -> apiConsentFingerprint(
                            "REBIND",
                            operation.knowledgeBaseId,
                            operation.spaceId,
                            sourceSpace = currentSpace,
                        )
                        else -> operation.consentFingerprint
                    }
                    if (operation.consentFingerprint != expectedConsentFingerprint) {
                        throw OperationAborted("API embedding consent changed while embedding")
                    }
                    val expectedSourceSpace = if (operation.kind == "REBIND") currentSpace else operation.spaceId
                    val inputsByVersion = operationInputsByVersion(operation)
                    val manifest = operationManifestHash(
                        kind = operation.kind,
                        knowledgeBaseId = operation.knowledgeBaseId,
                        targetSpace = operation.spaceId,
                        sourceSpace = expectedSourceSpace,
                        inputsByVersion = inputsByVersion,
                        activePointers = activeDocumentPointers(operation.knowledgeBaseId),
                    )
                    if (manifest != operation.inputManifestHash) {
                        throw OperationAborted("knowledge base inputs or active document pointer changed while embedding")
                    }
                    if (operation.kind == "REBIND") {
                        if (currentSpace == operation.spaceId) {
                            throw OperationAborted("API rebind target was changed by another operation")
                        }
                    } else if (currentSpace != operation.spaceId) {
                        throw OperationAborted("knowledge base embedding binding changed while embedding")
                    }
                    if (operation.kind == "REBUILD" && !hasApiEmbeddingConsent(operation.knowledgeBaseId)) {
                        throw OperationAborted("API embedding consent is no longer present")
                    }
                    operation.jobId?.let { jobId ->
                        val job = db.query(
                            "SELECT embedding_is_api, embedding_consent, stage FROM import_jobs WHERE id = ?",
                            listOf(jobId),
                        ).singleOrNull() ?: throw OperationAborted("import job was removed while embedding")
                        if (!job.boolean("embedding_is_api") || !job.boolean("embedding_consent") ||
                            job.string("stage") == ImportStage.CANCELLED.name
                        ) {
                            throw OperationAborted("import consent or cancellation changed while embedding")
                        }
                    }
                    validateOperationCache(operation, inputsByVersion, selectedEmbedder.dimension)
                    when (operation.kind) {
                        "IMPORT" -> {
                            val versionId = operation.documentVersionId ?: error("import operation has no document version")
                            val documentId = operation.documentId ?: error("import operation has no document")
                            val version = db.query(
                                "SELECT status, document_id FROM document_versions WHERE id = ?",
                                listOf(versionId),
                            ).singleOrNull() ?: throw OperationAborted("staging document version was removed")
                            if (version.string("document_id") != documentId) {
                                throw OperationAborted("import document version does not match document")
                            }
                            if (version.string("status") != "STAGING" &&
                                version.string("status") != "READY" &&
                                version.string("status") != "READY_WITH_VISUAL_GAPS"
                            ) {
                                throw OperationAborted("import document version is not publishable")
                            }
                            val activeBefore = db.query(
                                "SELECT active_version_id, deleted_at FROM documents WHERE id = ? AND kb_id = ?",
                                listOf(documentId, operation.knowledgeBaseId),
                            ).singleOrNull() ?: throw OperationAborted("document was removed while embedding")
                            if (activeBefore.string("deleted_at").isNotBlank()) {
                                throw OperationAborted("document was deleted while embedding")
                            }
                            db.execute("UPDATE assets SET document_version_id = ? WHERE document_id = ? AND (document_version_id IS NULL OR document_version_id = '')", listOf(versionId, documentId))
                            db.execute(
                                "UPDATE document_versions SET status = ? WHERE id = ?",
                                listOf(publishedVersionStatus(operation.jobId), versionId),
                            )
                            val where = if (activeBefore.string("active_version_id").isBlank()) {
                                "active_version_id IS NULL"
                            } else {
                                "active_version_id = ?"
                            }
                            val args = if (activeBefore.string("active_version_id").isBlank()) {
                                listOf<Any?>(versionId, documentId)
                            } else {
                                listOf<Any?>(versionId, documentId, activeBefore.string("active_version_id"))
                            }
                            db.execute(
                                "UPDATE documents SET active_version_id = ?, deleted_at = NULL WHERE id = ? AND $where",
                                args,
                            )
                            val after = db.query("SELECT active_version_id FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
                            if (after?.string("active_version_id") != versionId) {
                                throw OperationAborted("document active pointer changed while publishing")
                            }
                        }
                        "REBIND" -> {
                            db.execute(
                                "UPDATE knowledge_bases SET embedding_space_id = ?, active_generation_id = NULL WHERE id = ? AND embedding_space_id = ?",
                                listOf(operation.spaceId, operation.knowledgeBaseId, currentSpace),
                            )
                            val after = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(operation.knowledgeBaseId)).singleOrNull()
                            check(after?.string("embedding_space_id") == operation.spaceId) { "knowledge base binding changed while rebinding" }
                            db.execute(
                                "UPDATE import_jobs SET embedding_is_api = 1, embedding_consent = 1, updated_at = ? WHERE kb_id = ? AND stage IN (?, ?)",
                                listOf(
                                    Utc.nowIso(),
                                    operation.knowledgeBaseId,
                                    ImportStage.READY.name,
                                    ImportStage.READY_WITH_VISUAL_GAPS.name,
                                ),
                            )
                            db.execute(
                                "DELETE FROM import_jobs WHERE kb_id = ? AND stage = ? AND display_name GLOB ?",
                                listOf(operation.knowledgeBaseId, ImportStage.FAILED.name, "$UNKNOWN_REBIND_PREFIX*"),
                            )
                        }
                        "REBUILD" -> Unit
                        else -> error("unknown embedding operation kind ${operation.kind}")
                    }
                    operation.jobId?.let(::ensureBatchGenerationCurrentLocked)
                    val generation = buildGenerationFromCachedUnlocked(
                        operation.knowledgeBaseId,
                        selectedEmbedder,
                    )
                    operation.jobId?.let { jobId ->
                        advanceBatchGenerationAfterPublicationLocked(jobId, generation)
                    }
                    operation.jobId?.let { jobId ->
                        val published = publishedVersionStatus(jobId)
                        db.execute(
                            "UPDATE import_jobs SET stage = ?, error = ?, updated_at = ? WHERE id = ? AND stage <> ?",
                            listOf(
                                published,
                                if (published == ImportStage.READY_WITH_VISUAL_GAPS.name) TEXT_ONLY_VISUAL_GAPS_MESSAGE else "",
                                Utc.nowIso(),
                                jobId,
                                ImportStage.CANCELLED.name,
                            ),
                        )
                    }
                    db.execute(
                        "UPDATE embedding_operations SET state = 'PUBLISHED', error = '', updated_at = ? WHERE token = ? AND state = 'CACHE_READY'",
                        listOf(Utc.nowIso(), token),
                    )
                    generation
                }
            }
        } catch (aborted: OperationAborted) {
            markEmbeddingOperation(token, "ABORTED", aborted.message ?: "embedding operation aborted", onlyIfActive = true)
            throw aborted
        }
    }

    private suspend fun publishChunksCancellable(
        job: ImportJob,
        bytes: ByteArray,
        textChunks: List<IndexedChunk>,
        fingerprint: String,
        assets: List<ExtractedAsset> = emptyList(),
    ) {
        if (!job.embeddingConsent) {
            advanceThrough(job, ImportStage.AWAITING_EMBEDDING_CONSENT)
            if (job.stage == ImportStage.AWAITING_EMBEDDING_CONSENT) {
                job.error = visualGapsNote(job, "API embedding was not approved. No text left the device.")
            }
            return
        }
        val selectedEmbedder = embedderForJob(job)
        check(selectedEmbedder.spaceId != embedder.spaceId) {
            "API suspending path cannot use the local embedding space"
        }
        val chunks = textChunks.ifEmpty {
            fail(job, "The file is empty")
            return
        }
        if (job.visualGapsAccepted) {
            job.error = TEXT_ONLY_VISUAL_GAPS_PREFIX + "embedding in progress"
            persistJob(job, displayNameForJob(job.id))
        }
        val versionId = EntityId.random().value
        val contentHash = sha256Hex(bytes)
        val operation = synchronized(indexLock) {
            db.transaction {
                check(activeEmbeddingOperation(job.knowledgeBaseId) == null) {
                    "another embedding operation is already active for this knowledge base"
                }
                val currentSpace = db.query(
                    "SELECT embedding_space_id, deleted_at FROM knowledge_bases WHERE id = ?",
                    listOf(job.knowledgeBaseId),
                ).singleOrNull() ?: error("knowledge base not found")
                check(currentSpace.string("deleted_at").isBlank()) { "knowledge base deleted" }
                check(currentSpace.string("embedding_space_id") == selectedEmbedder.spaceId) {
                    "knowledge base binding changed before API import"
                }
                val activePointers = activeDocumentPointers(job.knowledgeBaseId)
                db.execute(
                    "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES (?,?,?,?,?,?)",
                    listOf(versionId, job.documentId, fingerprint, contentHash, "STAGING", Utc.nowIso()),
                )
                persistChunks(versionId, chunks)
                val inputsByVersion = linkedMapOf(versionId to inputsForVersion(versionId))
                val manifest = operationManifestHash(
                    kind = "IMPORT",
                    knowledgeBaseId = job.knowledgeBaseId,
                    targetSpace = selectedEmbedder.spaceId,
                    sourceSpace = selectedEmbedder.spaceId,
                    inputsByVersion = inputsByVersion,
                    activePointers = activePointers,
                )
                insertEmbeddingOperation(
                    kind = "IMPORT",
                    knowledgeBaseId = job.knowledgeBaseId,
                    jobId = job.id,
                    documentId = job.documentId,
                    documentVersionId = versionId,
                    spaceId = selectedEmbedder.spaceId,
                    inputManifestHash = manifest,
                    consentFingerprint = apiConsentFingerprint("IMPORT", job.knowledgeBaseId, selectedEmbedder.spaceId, job.id),
                )
            }
        }
        try {
            executeEmbeddingOperation(operation, selectedEmbedder)
            finalizeEmbeddingOperation(operation.token)
            finishPublished(job)
        } catch (cancelled: CancellationException) {
            persistApiCancellation(job, displayNameForJob(job.id))
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            persistApiCancellation(job, displayNameForJob(job.id))
            throw interrupted
        } catch (failure: Throwable) {
            val operationState = latestEmbeddingOperationForJob(job.id)?.state
            if (isUnknownEmbeddingFailure(failure) || operationState == "UNKNOWN") {
                job.stage = ImportStage.FAILED
                job.error = API_EMBEDDING_UNKNOWN_ERROR
            } else if (operationState == "CANCELLED") {
                job.stage = ImportStage.CANCELLED
                job.error = "Cancelled by user"
            } else {
                fail(job, API_EMBEDDING_FAILED_ERROR)
            }
        }
    }

    private fun publishChunks(
        job: ImportJob,
        bytes: ByteArray,
        textChunks: List<IndexedChunk>,
        fingerprint: String,
        assets: List<ExtractedAsset> = emptyList(),
    ) {
        if (job.embeddingIsApi && !job.embeddingConsent) {
            advanceThrough(job, ImportStage.AWAITING_EMBEDDING_CONSENT)
            if (job.stage == ImportStage.AWAITING_EMBEDDING_CONSENT) {
                job.error = visualGapsNote(job, "API embedding was not approved. No text left the device.")
            }
            return
        }
        val selectedEmbedder = embedderForJob(job)
        ensureEmbeddingSpace(job.knowledgeBaseId, selectedEmbedder.spaceId)
        val chunks = textChunks.ifEmpty {
            fail(job, "The file is empty")
            return
        }
        val versionId = EntityId.random().value
        val contentHash = sha256Hex(bytes)
        synchronized(indexLock) {
            db.transaction {
                db.execute(
                    "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES (?,?,?,?,?,?)",
                    listOf(versionId, job.documentId, fingerprint, contentHash, "STAGING", Utc.nowIso()),
                )
                persistChunks(versionId, chunks)
                persistEmbeddings(versionId, selectedEmbedder)
                db.execute("UPDATE assets SET document_version_id = ? WHERE document_id = ? AND (document_version_id IS NULL OR document_version_id = '')", listOf(versionId, job.documentId))
                db.execute(
                    "UPDATE document_versions SET status = ? WHERE id = ?",
                    listOf(if (job.visualGapsAccepted) "READY_WITH_VISUAL_GAPS" else "READY", versionId),
                )
                db.execute("UPDATE documents SET active_version_id = ?, deleted_at = NULL WHERE id = ?", listOf(versionId, job.documentId))
                ensureBatchGenerationCurrentLocked(job.id)
                val generation = rebuildUnlocked(
                    job.knowledgeBaseId,
                    apiConsentGrantedForOperation = job.embeddingIsApi && job.embeddingConsent,
                )
                advanceBatchGenerationAfterPublicationLocked(job.id, generation)
            }
        }
        finishPublished(job)
    }

    private fun persistChunks(documentVersionId: String, chunks: List<IndexedChunk>) {
        val existing = db.query("SELECT id, rowid AS rid FROM chunks WHERE document_version_id = ?", listOf(documentVersionId))
        existing.forEach { row ->
            runCatching { db.execute("DELETE FROM chunks_fts WHERE rowid = ?", listOf(row.long("rid"))) }
        }
        db.execute("DELETE FROM chunks WHERE document_version_id = ?", listOf(documentVersionId))
        chunks.forEachIndexed { ordinal, chunk ->
            val id = EntityId.random().value
            val hash = sha256Hex(chunk.text.toByteArray(Charsets.UTF_8))
            db.execute(
                "INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page) VALUES (?,?,?,?,?,?,?,?)",
                listOf(
                    id,
                    documentVersionId,
                    ordinal,
                    chunk.text,
                    hash,
                    chunk.span,
                    chunk.assetIds.joinToString(","),
                    chunk.page,
                ),
            )
            val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id = ?", listOf(id)).single().long("rid")
            runCatching {
                db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, CjkLexical.indexText(chunk.text)))
            }
        }
    }

    private fun persistEmbeddings(documentVersionId: String, selectedEmbedder: TextEmbedder) {
        val inputs = db.query(
            "SELECT id, ordinal, text, content_hash FROM chunks WHERE document_version_id = ? ORDER BY ordinal",
            listOf(documentVersionId),
        ).map { row ->
            EmbeddingInput(
                chunkId = row.string("id"),
                text = row.string("text"),
                contentHash = row.string("content_hash").ifBlank {
                    sha256Hex(row.string("text").toByteArray(Charsets.UTF_8))
                },
            )
        }
        ensureEmbeddings(inputs, selectedEmbedder)
    }

    /**
     * Ensure immutable successful rows for all [inputs].  Cache hits are
     * copied by content hash into the new chunk key; only cache misses call
     * the selected backend.  Duplicate texts within one version are batched
     * once as well.
     */
    private fun ensureEmbeddings(inputs: List<EmbeddingInput>, selectedEmbedder: TextEmbedder) {
        if (inputs.isEmpty()) return
        val pending = linkedMapOf<String, MutableList<EmbeddingInput>>()
        inputs.forEach { input ->
            val expectedHash = input.contentHash.ifBlank {
                sha256Hex(input.text.toByteArray(Charsets.UTF_8))
            }
            val existing = storedEmbedding(input.chunkId, selectedEmbedder.spaceId)
            if (existing != null) {
                check(existing.contentHash == expectedHash) {
                    "embedding content hash changed for chunk ${input.chunkId}"
                }
                validateEmbeddingBytes(existing.bytes, selectedEmbedder.dimension)
                return@forEach
            }
            val cached = cachedEmbedding(selectedEmbedder.spaceId, expectedHash)
            if (cached != null) {
                validateEmbeddingBytes(cached.bytes, selectedEmbedder.dimension)
                insertEmbedding(input.chunkId, selectedEmbedder.spaceId, cached.bytes, expectedHash)
            } else {
                pending.getOrPut(expectedHash) { mutableListOf() }.add(input.copy(contentHash = expectedHash))
            }
        }
        if (pending.isEmpty()) return

        val representatives = pending.values.map { it.first() }
        val vectors = if (selectedEmbedder is BatchTextEmbedder) {
            selectedEmbedder.embedBatch(representatives.map { it.text })
        } else {
            representatives.map { selectedEmbedder.embed(it.text) }
        }
        check(vectors.size == representatives.size) {
            "embedding backend returned ${vectors.size} vectors for ${representatives.size} cache misses"
        }
        pending.entries.zip(vectors).forEach { (entry, vector) ->
            val contentHash = entry.key
            validateEmbeddingVector(vector, selectedEmbedder.dimension)
            val bytes = floatsToBytes(vector)
            entry.value.forEach { input ->
                insertEmbedding(input.chunkId, selectedEmbedder.spaceId, bytes, contentHash)
            }
        }
    }

    private data class StoredEmbedding(
        val contentHash: String,
        val bytes: ByteArray,
    )

    private fun storedEmbedding(chunkId: String, spaceId: String): StoredEmbedding? =
        db.query(
            "SELECT content_hash, vector_blob FROM embeddings WHERE chunk_id = ? AND space_id = ?",
            listOf(chunkId, spaceId),
        ).singleOrNull()?.let { row -> storedEmbedding(row) }

    private fun cachedEmbedding(spaceId: String, contentHash: String): StoredEmbedding? =
        db.query(
            """
            SELECT e.content_hash, e.vector_blob
            FROM embeddings e
            JOIN chunks c ON c.id = e.chunk_id
            WHERE e.space_id = ? AND e.content_hash = ? AND c.content_hash = ?
            LIMIT 1
            """.trimIndent(),
            listOf(spaceId, contentHash, contentHash),
        ).singleOrNull()?.let { row -> storedEmbedding(row) }

    private fun storedEmbedding(row: SqlRow): StoredEmbedding =
        StoredEmbedding(row.string("content_hash"), embeddingBytes(row))

    private fun insertEmbedding(chunkId: String, spaceId: String, bytes: ByteArray, contentHash: String) {
        val existing = storedEmbedding(chunkId, spaceId)
        if (existing != null) {
            check(existing.contentHash == contentHash) {
                "embedding content hash changed for chunk $chunkId"
            }
            check(existing.bytes.contentEquals(bytes)) {
                "embedding vector changed for chunk $chunkId"
            }
            return
        }
        db.execute(
            "INSERT INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES (?,?,?,?)",
            listOf(chunkId, spaceId, bytes.copyOf(), contentHash),
        )
        val stored = storedEmbedding(chunkId, spaceId) ?: error("embedding missing after insert")
        check(stored.contentHash == contentHash && stored.bytes.contentEquals(bytes)) {
            "embedding changed during insert for chunk $chunkId"
        }
    }

    private fun validateEmbeddingVector(vector: FloatArray, dimension: Int) {
        check(vector.size == dimension) { "embedding dimension mismatch" }
        check(vector.all { it.isFinite() }) { "embedding contains a non-finite value" }
    }

    private fun validateEmbeddingBytes(bytes: ByteArray, dimension: Int) {
        check(bytes.size == dimension * 4) { "embedding byte length mismatch" }
        check(bytesToFloats(bytes, dimension).all { it.isFinite() }) {
            "embedding cache contains a non-finite value"
        }
    }

    private fun embeddingBytes(row: SqlRow): ByteArray {
        val blob = row.columns["vector_blob"]
        return when (blob) {
            is ByteArray -> blob.copyOf()
            is java.sql.Blob -> blob.getBytes(1, blob.length().toInt())
            else -> error("embedding blob unreadable")
        }
    }

    private fun lexicalHits(kbId: String, query: String, topK: Int, generation: String): List<SearchHit> {
        val tokenized = CjkLexical.indexText(query)
        val fts = runCatching {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id,
                       chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
                FROM chunks_fts
                JOIN chunks ON chunks.rowid = chunks_fts.rowid
                JOIN generation_members ON generation_members.chunk_id = chunks.id AND generation_members.generation_id = ?
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE documents.kb_id = ? AND documents.deleted_at IS NULL AND chunks_fts MATCH ?
                ORDER BY bm25(chunks_fts) ASC, chunks.id ASC
                LIMIT ?
                """.trimIndent(),
                listOf(generation, kbId, quoteFts(tokenized.ifBlank { query }), topK),
            )
        }.getOrDefault(emptyList())
        val rows = fts.ifEmpty {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id,
                       chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
                FROM chunks
                JOIN generation_members ON generation_members.chunk_id = chunks.id AND generation_members.generation_id = ?
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE documents.kb_id = ? AND documents.deleted_at IS NULL AND chunks.text LIKE ?
                ORDER BY chunks.id ASC
                LIMIT ?
                """.trimIndent(),
                listOf(generation, kbId, "%$query%", topK),
            )
        }
        return rows.mapIndexed { index, row ->
            SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 1.0 / (index + 1),
                knowledgeBaseId = kbId,
                documentVersionId = row.string("version_id"),
                assetId = row.string("asset_ids").split(',').firstOrNull { it.isNotBlank() },
                page = row.string("page").toIntOrNull(),
                sourceSpan = row.string("source_span").ifBlank { null },
            )
        }
    }

    private fun vectorHits(
        kbId: String,
        query: String,
        topK: Int,
        generation: String,
        selectedEmbedder: TextEmbedder,
        queryVector: FloatArray? = null,
        onQueryVectorReady: ((FloatArray) -> Unit)? = null,
    ): List<SearchHit> {
        val queryVec = queryVector?.copyOf() ?: selectedEmbedder.embed(query)
        validateEmbeddingVector(queryVec, selectedEmbedder.dimension)
        onQueryVectorReady?.invoke(queryVec.copyOf())
        val members = db.query(
            """
            SELECT embeddings.chunk_id AS chunk_id, embeddings.vector_blob AS vector_blob, chunks.text AS text,
                   documents.id AS document_id, chunks.document_version_id AS version_id,
                   chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
            FROM generation_members
            JOIN embeddings ON embeddings.chunk_id = generation_members.chunk_id AND embeddings.space_id = generation_members.space_id
            JOIN chunks ON chunks.id = generation_members.chunk_id
            JOIN documents ON documents.active_version_id = chunks.document_version_id
            WHERE generation_members.generation_id = ? AND documents.kb_id = ? AND documents.deleted_at IS NULL
            """.trimIndent(),
            listOf(generation, kbId),
        )
        val byId = linkedMapOf<String, SearchHit>()
        val vectors = mutableListOf<Pair<String, FloatArray>>()
        members.forEach { row ->
            val blob = row.columns["vector_blob"]
            val bytes = when (blob) {
                is ByteArray -> blob
                is java.sql.Blob -> blob.getBytes(1, blob.length().toInt())
                else -> return@forEach
            }
            if (bytes.size != selectedEmbedder.dimension * 4) return@forEach
            vectors += row.string("chunk_id") to bytesToFloats(bytes, selectedEmbedder.dimension)
            byId[row.string("chunk_id")] = SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 0.0,
                knowledgeBaseId = kbId,
                documentVersionId = row.string("version_id"),
                assetId = row.string("asset_ids").split(',').firstOrNull { it.isNotBlank() },
                page = row.string("page").toIntOrNull(),
                sourceSpan = row.string("source_span").ifBlank { null },
            )
        }
        if (vectors.isEmpty()) return emptyList()
        val native = vectorIndexFactory?.create(selectedEmbedder.spaceId, selectedEmbedder.dimension, vectors.size)
        if (native != null) {
            return try {
                vectors.forEach { (id, vector) -> native.add(id, vector) }
                native.search(queryVec, topK).map { (id, score) -> byId.getValue(id).copy(score = score.toDouble()) }
            } finally {
                native.close()
            }
        }
        val fallback = CosineIndex(selectedEmbedder.dimension)
        vectors.forEach { (id, vector) -> fallback.add(id, vector) }
        return fallback.search(queryVec, topK).map { (id, score) ->
            byId.getValue(id).copy(score = score.toDouble())
        }
    }

    private fun pinnedReadyGeneration(kbId: String): String? {
        // Keep the active-generation read as one stable snapshot boundary. A
        // few SqlConnection implementations pin that exact read to the
        // caller's run, so do not fold the KB-space lookup into this query.
        val generationId = db.query(
            "SELECT active_generation_id FROM knowledge_bases WHERE id = ?",
            listOf(kbId),
        ).singleOrNull()?.string("active_generation_id")?.ifBlank { return null } ?: return null
        val kb = db.query(
            "SELECT embedding_space_id, deleted_at FROM knowledge_bases WHERE id = ?",
            listOf(kbId),
        ).singleOrNull() ?: return null
        if (kb.string("deleted_at").isNotBlank()) return null
        val kbSpace = kb.string("embedding_space_id")
        val generation = db.query(
            "SELECT state, space_id FROM index_generations WHERE id = ?",
            listOf(generationId),
        ).singleOrNull() ?: return null
        return if (generation.string("state") == "READY" &&
            kbSpace.isNotBlank() &&
            generation.string("space_id") == kbSpace
        ) generationId else null
    }

    private fun hasApiEmbeddingConsent(kbId: String): Boolean {
        if (db.query(
                "SELECT id FROM import_jobs WHERE kb_id = ? AND embedding_is_api = 1 AND embedding_consent = 1 LIMIT 1",
                listOf(kbId),
            ).isNotEmpty()
        ) return true

        // An empty KB can have no import_job row to carry the user's explicit
        // consent.  A published API operation is the durable consent record in
        // that case, but it is valid only while its complete fixed space is
        // still the KB's current binding.
        return db.query(
            """
            SELECT o.token
            FROM embedding_operations o
            JOIN knowledge_bases k ON k.id = o.kb_id
            WHERE o.kb_id = ? AND o.state = 'PUBLISHED' AND o.space_id = k.embedding_space_id
              AND o.consent_fingerprint <> ''
            LIMIT 1
            """.trimIndent(),
            listOf(kbId),
        ).isNotEmpty()
    }

    private data class CachedQueryVector(
        val dimension: Int,
        val vector: FloatArray,
    )

    /**
     * Load one immutable successful API query vector.  A malformed or
     * dimension-mismatched cache row fails closed instead of silently calling
     * the provider again.
     */
    private fun queryVectorCache(spaceId: String, queryHash: String): CachedQueryVector? {
        val row = db.query(
            "SELECT vector_blob, dimension FROM embedding_query_vectors WHERE space_id = ? AND query_hash = ?",
            listOf(spaceId, queryHash),
        ).singleOrNull() ?: return null
        val dimension = row.long("dimension").toInt()
        check(dimension > 0) { "query embedding cache dimension is invalid" }
        val boundDimension = ApiEmbeddingBinding.parseSpaceId(spaceId)?.dimension
        check(boundDimension == null || boundDimension == dimension) {
            "query embedding cache dimension does not match the bound API space"
        }
        val bytes = embeddingBytes(row)
        validateEmbeddingBytes(bytes, dimension)
        return CachedQueryVector(dimension, bytesToFloats(bytes, dimension))
    }

    /**
     * Persist a successful provider result independently of index publication.
     * Existing bytes are immutable: an equal retry is idempotent, while any
     * changed content is rejected rather than replaced.
     */
    private fun insertQueryVectorCache(
        spaceId: String,
        queryHash: String,
        vector: FloatArray,
        dimension: Int,
    ) = synchronized(indexLock) {
        requireQueryHash(queryHash)
        validateEmbeddingVector(vector, dimension)
        val bytes = floatsToBytes(vector)
        db.transaction {
            val existing = db.query(
                "SELECT vector_blob, dimension FROM embedding_query_vectors WHERE space_id = ? AND query_hash = ?",
                listOf(spaceId, queryHash),
            ).singleOrNull()
            if (existing != null) {
                val existingDimension = existing.long("dimension").toInt()
                check(existingDimension == dimension) {
                    "query embedding cache dimension changed"
                }
                val existingBytes = embeddingBytes(existing)
                check(existingBytes.contentEquals(bytes)) {
                    "query embedding cache vector changed"
                }
                return@transaction
            }
            db.execute(
                "INSERT INTO embedding_query_vectors(space_id,query_hash,vector_blob,dimension,created_at) VALUES (?,?,?,?,?)",
                listOf(spaceId, queryHash, bytes.copyOf(), dimension, Utc.nowIso()),
            )
        }
    }

    private fun queryHash(query: String): String =
        sha256Hex(query.toByteArray(Charsets.UTF_8))

    private fun requireQueryHash(queryHash: String) {
        require(queryHash.matches(QUERY_HASH_PATTERN)) {
            "queryHash must be a lowercase SHA-256 hex digest"
        }
    }

    private fun apiQueryAttempt(row: SqlRow): ApiQueryAttempt = ApiQueryAttempt(
        knowledgeBaseId = row.string("kb_id"),
        spaceId = row.string("space_id"),
        queryHash = row.string("query_hash"),
        retryAuthorized = row.boolean("retry_authorized"),
        error = row.string("error"),
        updatedAt = row.string("updated_at"),
    )

    /**
     * Reject an unresolved attempt before constructing a dynamic adapter.  A
     * pending row is the durable billable-call barrier for this exact
     * knowledge-base, embedding space, and query hash.
     */
    private fun rejectPendingApiQueryBeforeResolver(
        knowledgeBaseId: String,
        spaceId: String,
        queryHash: String,
    ) {
        val row = db.query(
            "SELECT retry_authorized FROM embedding_query_attempts WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
            listOf(knowledgeBaseId, spaceId, queryHash),
        ).singleOrNull() ?: return
        if (!row.boolean("retry_authorized")) {
            throw ApiQueryUnknownOutcomeException(knowledgeBaseId, spaceId, queryHash)
        }
    }

    /**
     * Insert the first attempt or consume an explicit one-time retry grant.
     * The transaction serializes the state transition so two callers cannot
     * reuse one authorization while an adapter call is in flight.
     */
    private fun claimApiQueryAttempt(
        knowledgeBaseId: String,
        spaceId: String,
        queryHash: String,
    ) = synchronized(indexLock) {
        db.transaction {
            val row = db.query(
                "SELECT retry_authorized FROM embedding_query_attempts WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
                listOf(knowledgeBaseId, spaceId, queryHash),
            ).singleOrNull()
            when {
                row == null -> db.execute(
                    "INSERT INTO embedding_query_attempts(kb_id,space_id,query_hash,retry_authorized,error,updated_at) VALUES (?,?,?,?,?,?)",
                    listOf(
                        knowledgeBaseId,
                        spaceId,
                        queryHash,
                        0,
                        API_QUERY_PENDING_ERROR,
                        Utc.nowIso(),
                    ),
                )

                !row.boolean("retry_authorized") ->
                    throw ApiQueryUnknownOutcomeException(knowledgeBaseId, spaceId, queryHash)

                else -> {
                    db.execute(
                        "UPDATE embedding_query_attempts SET retry_authorized = 0, error = ?, updated_at = ? WHERE kb_id = ? AND space_id = ? AND query_hash = ? AND retry_authorized = 1",
                        listOf(
                            API_QUERY_PENDING_ERROR,
                            Utc.nowIso(),
                            knowledgeBaseId,
                            spaceId,
                            queryHash,
                        ),
                    )
                    check(
                        db.query(
                            "SELECT retry_authorized FROM embedding_query_attempts WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
                            listOf(knowledgeBaseId, spaceId, queryHash),
                        ).singleOrNull()?.boolean("retry_authorized") == false,
                    ) {
                        "API query retry authorization was not atomically consumed"
                    }
                }
            }
        }
    }

    private fun persistApiQueryUnknown(
        knowledgeBaseId: String,
        spaceId: String,
        queryHash: String,
    ) = synchronized(indexLock) {
        db.transaction {
            db.execute(
                "UPDATE embedding_query_attempts SET retry_authorized = 0, error = ?, updated_at = ? WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
                listOf(
                    API_QUERY_UNKNOWN_ERROR,
                    Utc.nowIso(),
                    knowledgeBaseId,
                    spaceId,
                    queryHash,
                ),
            )
        }
    }

    private fun clearApiQueryAttempt(
        knowledgeBaseId: String,
        spaceId: String,
        queryHash: String,
    ) = synchronized(indexLock) {
        db.transaction {
            db.execute(
                "DELETE FROM embedding_query_attempts WHERE kb_id = ? AND space_id = ? AND query_hash = ?",
                listOf(knowledgeBaseId, spaceId, queryHash),
            )
        }
    }

    private fun isPublishedReady(documentId: String, kbId: String, requestedApi: Boolean = false): Boolean {
        val versionId = db.query("SELECT active_version_id FROM documents WHERE id = ? AND deleted_at IS NULL", listOf(documentId))
            .singleOrNull()?.string("active_version_id")?.ifBlank { null } ?: return false
        val versionStatus = db.query("SELECT status FROM document_versions WHERE id = ?", listOf(versionId))
            .singleOrNull()?.string("status")
        val versionReady = versionStatus == "READY" || versionStatus == "READY_WITH_VISUAL_GAPS"
        if (!versionReady) return false
        val chunks = db.query("SELECT COUNT(*) AS n FROM chunks WHERE document_version_id = ?", listOf(versionId)).single().long("n")
        if (chunks == 0L) return false
        val space = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kbId))
            .singleOrNull()?.string("embedding_space_id").orEmpty()
        val selectedEmbedder: TextEmbedder = when {
            requestedApi -> apiEmbedderForSpace(space) ?: return false
            space == embedder.spaceId -> embedder
            else -> return false
        }
        val embeddings = db.query(
            "SELECT COUNT(*) AS n FROM embeddings e JOIN chunks c ON c.id = e.chunk_id WHERE c.document_version_id = ? AND e.space_id = ?",
            listOf(versionId, selectedEmbedder.spaceId),
        ).single().long("n")
        if (embeddings != chunks) return false
        val pin = pinnedReadyGeneration(kbId) ?: return false
        val members = db.query(
            "SELECT COUNT(*) AS n FROM generation_members WHERE generation_id = ? AND document_version_id = ?",
            listOf(pin, versionId),
        ).single().long("n")
        return members == chunks
    }

    private fun embedderForJob(job: ImportJob): TextEmbedder {
        val space = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(job.knowledgeBaseId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        check(space.isNotBlank()) {
            "Knowledge base ${job.knowledgeBaseId} has no fixed embedding space; select and bind one explicitly"
        }
        return if (job.embeddingIsApi) {
            apiEmbedderForSpace(space)
                ?: error("API embedding was selected, but its fixed binding has no matching adapter")
        } else {
            check(space == embedder.spaceId) {
                "Local embedding was selected, but knowledge base ${job.knowledgeBaseId} is bound to API space $space"
            }
            embedder
        }
    }

    private fun embeddingForSpace(spaceId: String): TextEmbedder? {
        if (spaceId.isBlank()) return null
        if (spaceId == embedder.spaceId) return embedder
        return apiEmbedderForSpace(spaceId)
    }

    private fun isApiKnowledgeBase(knowledgeBaseId: String): Boolean {
        val space = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ? AND deleted_at IS NULL",
            listOf(knowledgeBaseId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        return space.isNotBlank() && space != embedder.spaceId
    }

    private fun apiEmbedderForSpace(spaceId: String): TextEmbedder? =
        if (spaceId.isBlank()) {
            null
        } else {
            val binding = ApiEmbeddingBinding.parseSpaceId(spaceId)
            val static = configuredApiEmbedders.firstOrNull { it.spaceId == spaceId }
            when {
                static != null && (binding == null || static.dimension == binding.dimension) -> static
                binding == null -> null
                else -> runCatching { apiEmbedderResolver(spaceId) }
                    .getOrNull()
                    ?.takeIf { it.spaceId == spaceId && it.dimension == binding.dimension }
            }
        }

    private fun unknownRebindGates(knowledgeBaseId: String): List<SqlRow> =
        db.query(
            "SELECT id FROM import_jobs WHERE kb_id = ? AND stage = ? AND embedding_is_api = 1 AND display_name GLOB ?",
            listOf(knowledgeBaseId, ImportStage.FAILED.name, "$UNKNOWN_REBIND_PREFIX*"),
        )

    private fun persistRebindUnknownGate(
        knowledgeBaseId: String,
        binding: ApiEmbeddingBinding,
    ) {
        val document = db.query(
            "SELECT id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id LIMIT 1",
            listOf(knowledgeBaseId),
        ).singleOrNull() ?: return
        val marker = "$UNKNOWN_REBIND_PREFIX${binding.spaceId}"
        val existingId = db.query(
            "SELECT id FROM import_jobs WHERE kb_id = ? AND stage = ? AND display_name = ? LIMIT 1",
            listOf(knowledgeBaseId, ImportStage.FAILED.name, marker),
        ).firstOrNull()?.string("id")
        val job = ImportJob(
            id = existingId ?: EntityId.random().value,
            knowledgeBaseId = knowledgeBaseId,
            documentId = document.string("id"),
            stage = ImportStage.FAILED,
            embeddingIsApi = true,
            embeddingConsent = true,
            error = "UNKNOWN_OUTCOME: API rebind result is uncertain; explicit duplicate-charge acknowledgement is required",
        )
        // Keep the marker outside the failed rebind transaction. The message
        // is intentionally sanitized; only the durable gate matters here.
        persistJob(job, marker)
    }

    private fun isUnknownEmbeddingFailure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is EmbeddingUnknownOutcomeException ||
                current.message?.contains("UNKNOWN_OUTCOME", ignoreCase = true) == true
            ) return true
            current = current.cause
        }
        return false
    }

    private fun isUncertainApiQueryFailure(failure: Throwable): Boolean {
        if (isUnknownEmbeddingFailure(failure)) return true
        var current: Throwable? = failure
        while (current != null) {
            if (current is java.util.concurrent.CancellationException || current is InterruptedException) return true
            current = current.cause
        }
        return false
    }

    private fun validateRequestedEmbeddingSelection(kbId: String, api: Boolean, consent: Boolean) {
        val space = db.query(
            "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(kbId),
        ).singleOrNull()?.string("embedding_space_id").orEmpty()
        if (api) {
            // Before consent we intentionally allow the job to reach the
            // consent state even when the selected adapter is unavailable;
            // this performs no network call.  Approval itself must have a
            // matching, complete binding or it fails closed.
            if (consent) {
                check(space.isNotBlank()) { "API embedding requires a fixed KB embedding binding" }
                check(apiEmbedderForSpace(space) != null) {
                    "API embedding binding $space is unavailable; no text was sent"
                }
            }
        } else if (space.isNotBlank()) {
            check(space == embedder.spaceId) {
                "This knowledge base is bound to API embedding space $space; select API embedding explicitly"
            }
        }
    }

    private fun embedderForKnowledgeBase(kbId: String): TextEmbedder {
        val space = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kbId))
            .singleOrNull()?.string("embedding_space_id").orEmpty()
        return embeddingForSpace(space)
            ?: error("Knowledge base $kbId uses embedding space $space, but no matching adapter is configured")
    }

    private fun ensureEmbeddingSpace(kbId: String, selectedSpace: String) {
        require(selectedSpace.isNotBlank()) { "selected embedding space must not be blank" }
        val row = db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kbId)).singleOrNull()
            ?: error("knowledge base not found")
        val existing = row.string("embedding_space_id").ifBlank { null }
        when {
            existing == null -> db.execute(
                "UPDATE knowledge_bases SET embedding_space_id = ? WHERE id = ?",
                listOf(selectedSpace, kbId),
            )
            existing == selectedSpace -> Unit
            else -> error(
                "Knowledge base $kbId is bound to embedding space $existing; " +
                    "selected space $selectedSpace must use a separate knowledge base",
            )
        }
    }

    private fun requireKb(kbId: String) {
        val row = db.query("SELECT deleted_at FROM knowledge_bases WHERE id = ?", listOf(kbId)).singleOrNull()
            ?: error("knowledge base not found")
        check(row.string("deleted_at").isBlank()) { "knowledge base deleted" }
    }

    private fun fail(job: ImportJob, message: String) {
        job.stage = ImportStage.FAILED
        job.error = message
    }

    private fun advanceThrough(job: ImportJob, target: ImportStage) {
        var guard = 0
        while (job.stage != target && guard++ < 24) {
            val prev = job.stage
            ImportStateMachine.advance(job)
            if (job.stage == prev) break
        }
    }

    private fun textChunksSkippingVision(parsed: ParsedPublication): List<IndexedChunk> {
        val pageChunks = parsed.pages.filter { it.text.isNotBlank() }.flatMap { page ->
            TextChunker.chunk(page.text).map { IndexedChunk(it, page.page, emptyList(), "page:${page.page}") }
        }
        return pageChunks.ifEmpty {
            if (parsed.text.isNotBlank()) {
                TextChunker.chunk(parsed.text).map { IndexedChunk(it, 1, emptyList(), null) }
            } else {
                emptyList()
            }
        }
    }

    private fun visualGapsNote(job: ImportJob, note: String): String =
        if (job.visualGapsAccepted) TEXT_ONLY_VISUAL_GAPS_PREFIX + note else note

    private fun finishPublished(job: ImportJob) {
        // Publication is a jump to a published terminal, including resume from FAILED
        // after CACHE_READY. Walking the state machine cannot leave FAILED/PAUSED.
        if (job.visualGapsAccepted) {
            job.stage = ImportStage.READY_WITH_VISUAL_GAPS
            job.error = TEXT_ONLY_VISUAL_GAPS_MESSAGE
        } else {
            job.stage = ImportStage.READY
            job.error = null
        }
    }

    private fun publishedVersionStatus(jobId: String?): String {
        if (jobId.isNullOrBlank()) return "READY"
        val row = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: return "READY"
        val stage = runCatching { ImportStage.valueOf(row.string("stage")) }.getOrNull()
        return if (stage == ImportStage.READY_WITH_VISUAL_GAPS || row.string("error").startsWith(TEXT_ONLY_VISUAL_GAPS_PREFIX)) {
            "READY_WITH_VISUAL_GAPS"
        } else {
            "READY"
        }
    }

    private fun persistJob(job: ImportJob, displayName: String) = synchronized(indexLock) {
        if (job.visionConsent && job.consentedVisionFingerprint.isNullOrBlank()) {
            job.consentedVisionFingerprint = visionFingerprint()
        }
        val batchId = db.query("SELECT batch_id FROM import_jobs WHERE id = ?", listOf(job.id))
            .singleOrNull()?.string("batch_id")?.ifBlank { null }
        db.execute(
            "INSERT OR REPLACE INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,error,updated_at,vision_consent,embedding_is_api,embedding_consent,vision_binding_json,batch_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                job.id,
                job.knowledgeBaseId,
                job.documentId,
                displayName,
                job.stage.name,
                if (job.hasImages) 1 else 0,
                job.error,
                Utc.nowIso(),
                if (job.visionConsent) 1 else 0,
                if (job.embeddingIsApi) 1 else 0,
                if (job.embeddingConsent) 1 else 0,
                job.consentedVisionFingerprint,
                batchId,
            ),
        )
        syncBatchItemFromJobLocked(job.id)
        batchId?.let(::refreshBatchProgressLocked)
    }

    private fun visionFingerprint(): String = visionBinding()?.fingerprint ?: visionModelFingerprint

    private fun visionBindingMatches(job: ImportJob): Boolean {
        val current = visionFingerprint()
        val consented = job.consentedVisionFingerprint
        if (consented.isNullOrBlank()) return current == "vision-unconfigured"
        return consented == current
    }

    private fun existingDocument(kbId: String, hash: String): String? =
        db.query("SELECT id FROM documents WHERE kb_id = ? AND blob_hash = ?", listOf(kbId, hash))
            .singleOrNull()?.string("id")

    private fun upsertBlob(stored: StoredBlob): StoredBlob {
        val existing = db.query("SELECT ref_count FROM blobs WHERE hash = ?", listOf(stored.sha256)).singleOrNull()
        if (existing == null) {
            db.execute(
                "INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES (?,?,?,?,0)",
                listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef),
            )
        }
        return stored
    }

    private fun syncBlobRef(hash: String) {
        val live = db.query(
            "SELECT COUNT(*) AS n FROM documents WHERE blob_hash = ? AND deleted_at IS NULL",
            listOf(hash),
        ).single().long("n")
        db.execute("UPDATE blobs SET ref_count = ? WHERE hash = ?", listOf(live, hash))
    }

    private fun quoteFts(query: String): String {
        val cleaned = query.replace("\"", " ").trim()
        if (cleaned.isEmpty()) return "\"\""
        return if (cleaned.any { it.isWhitespace() }) {
            cleaned.split(Regex("\\s+")).joinToString(" OR ") { token -> "\"$token\"" }
        } else {
            cleaned
        }
    }

    fun issueConsentTicket(kind: String, jobId: String?, knowledgeBaseId: String, fingerprint: String): String {
        require(kind in setOf("VISION", "API_EMBEDDING", "QUERY_RETRY")) { "unsupported consent ticket kind" }
        require(knowledgeBaseId.isNotBlank()) { "knowledgeBaseId must not be blank" }
        require(fingerprint.isNotBlank() && fingerprint.length <= 32_768) { "consent fingerprint is invalid" }
        require(fingerprint.none { it == '\r' || it == '\u0000' }) { "consent fingerprint contains a control character" }
        val id = EntityId.random().value
        db.execute(
            "INSERT INTO consent_tickets(id,kind,job_id,kb_id,fingerprint,consumed,created_at) VALUES (?,?,?,?,?,0,?)",
            listOf(id, kind, jobId, knowledgeBaseId, fingerprint, Utc.nowIso()),
        )
        return id
    }

    /**
     * Validate all durable consent anchors before consuming a ticket.  The
     * consumed bit is still set before any provider/Vision dispatch, but a
     * stale or forged ticket is rejected while it remains unconsumed and can
     * never cross the external-call boundary.
     */
    fun consumeConsentTicket(ticketId: String): ConsumedConsentTicket? = synchronized(indexLock) {
        db.transaction {
            val row = db.query("SELECT * FROM consent_tickets WHERE id = ? AND consumed = 0", listOf(ticketId)).singleOrNull()
                ?: return@transaction null
            val ticket = ConsumedConsentTicket(
                kind = row.string("kind"),
                jobId = row.string("job_id").ifBlank { null },
                knowledgeBaseId = row.string("kb_id"),
                fingerprint = row.string("fingerprint"),
            )
            validateConsentTicketLocked(ticket)
            db.execute("UPDATE consent_tickets SET consumed = 1 WHERE id = ? AND consumed = 0", listOf(ticketId))
            check(
                db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticketId))
                    .singleOrNull()?.long("consumed") == 1L,
            ) { "consent ticket was not atomically consumed" }
            ticket
        }
    }

    fun jobBatchId(jobId: String): String? =
        db.query("SELECT batch_id FROM import_jobs WHERE id = ?", listOf(jobId))
            .singleOrNull()?.string("batch_id")?.ifBlank { null }

    fun listBatches(knowledgeBaseId: String): List<ImportBatch> =
        db.query("SELECT * FROM import_batches WHERE kb_id = ? ORDER BY updated_at DESC", listOf(knowledgeBaseId)).map { row ->
            ImportBatch(
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
            )
        }

    /**
     * Re-arm only durable, non-terminal batches after process recreation.
     *
     * A worker may die after claiming an item but before synchronising its
     * terminal job state.  The job/CAS rows remain the source of truth, so a
     * PROCESSING item is returned to the coordinator queue.  resumeImport()
     * re-checks persisted embedding operations before any provider dispatch;
     * a prior DISPATCHED operation therefore becomes UNKNOWN_OUTCOME instead
     * of being replayed.
     */
    fun recoverableBatchIds(): List<String> = synchronized(indexLock) {
        val ids = db.query(
            "SELECT id FROM import_batches WHERE state IN (?,?,?) ORDER BY created_at, id",
            listOf(
                ImportBatchState.STAGING.name,
                ImportBatchState.COPYING.name,
                ImportBatchState.PROCESSING.name,
            ),
        ).map { it.string("id") }
        ids.forEach { batchId ->
            db.execute(
                "UPDATE import_items SET state = ?, error = NULL WHERE batch_id = ? AND state = ?",
                listOf(ImportItemState.QUEUED.name, batchId, ImportItemState.PROCESSING.name),
            )
            db.query(
                "SELECT job_id FROM import_items WHERE batch_id = ? AND job_id IS NOT NULL AND job_id != ''",
                listOf(batchId),
            ).forEach { row -> syncBatchItemFromJobLocked(row.string("job_id")) }
            // Active local stages are safe to reconstruct from CAS.  Restore
            // them to QUEUED after the job-derived sync above; external
            // DISPATCHED state is still checked fail-closed by resumeImport.
            db.execute(
                "UPDATE import_items SET state = ?, error = NULL WHERE batch_id = ? AND state = ?",
                listOf(ImportItemState.QUEUED.name, batchId, ImportItemState.PROCESSING.name),
            )
            refreshBatchProgressLocked(batchId)
        }
        ids.filter { batchId ->
            db.query("SELECT state FROM import_batches WHERE id = ?", listOf(batchId))
                .singleOrNull()?.string("state") in setOf(
                    ImportBatchState.COPYING.name,
                    ImportBatchState.PROCESSING.name,
                )
        }
    }

    fun beginBatch(knowledgeBaseId: String, kind: ImportBatchKind, displayName: String): String {
        requireKb(knowledgeBaseId)
        val batchId = EntityId.random().value
        val generation = db.query(
            "SELECT active_generation_id FROM knowledge_bases WHERE id = ?",
            listOf(knowledgeBaseId),
        ).singleOrNull()?.string("active_generation_id")?.ifBlank { null }
        val now = Utc.nowIso()
        db.execute(
            "INSERT INTO import_batches(id,kb_id,generation_id,kind,display_name,state,total_items,copied,processing,waiting,failed,error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                batchId, knowledgeBaseId, generation, kind.name, displayName,
                ImportBatchState.STAGING.name, 0, 0, 0, 0, 0, null, now, now,
            ),
        )
        return batchId
    }

    /**
     * Attach one already-copied job to a durable batch item.  Binding is
     * idempotent so a process death between the two writes cannot duplicate
     * an archive entry when staging is resumed.
     */
    fun bindJobToBatch(batchId: String, job: ImportJob, relativePath: String) = synchronized(indexLock) {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        val batch = db.query("SELECT id FROM import_batches WHERE id = ?", listOf(batchId)).singleOrNull()
            ?: error("import batch not found")
        check(batch.string("id") == batchId) { "import batch not found" }
        db.transaction {
            val existing = db.query(
                "SELECT id, job_id, relative_path FROM import_items WHERE batch_id = ? AND item_key = ?",
                listOf(batchId, relativePath),
            ).singleOrNull()
            if (existing != null) {
                check(existing.string("job_id") == job.id) {
                    "import batch item already belongs to another job"
                }
            } else {
                db.execute(
                    "INSERT INTO import_items(id,batch_id,item_key,relative_path,job_id,kind,state,attempt_count,error) VALUES (?,?,?,?,?,?,?,?,?)",
                    listOf(
                        EntityId.random().value,
                        batchId,
                        relativePath,
                        relativePath,
                        job.id,
                        "FILE",
                        itemStateFor(job).name,
                        0,
                        job.error,
                    ),
                )
            }
            db.execute("UPDATE import_jobs SET batch_id = ? WHERE id = ?", listOf(batchId, job.id))
        }
        syncBatchItemFromJobLocked(job.id)
        refreshBatchProgressLocked(batchId)
    }

    /**
     * Process a batch in one durable coordinator.  Parallelism is deliberately
     * bounded to one because the repository owns one serialized SQLite/index
     * boundary and an API embedding operation is already exclusive per KB.
     * Each item is claimed before resume, so a duplicate WorkManager delivery
     * cannot enqueue or process the same job concurrently.
     */
    fun processBatch(batchId: String, visionConfigured: Boolean) {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        while (true) {
            val jobId = try {
                claimNextBatchJob(batchId)
            } catch (failure: Throwable) {
                synchronized(indexLock) {
                    failBatchLocked(batchId, failure.message ?: BATCH_GENERATION_CHANGED)
                }
                throw failure
            } ?: break
            try {
                resumeImport(jobId, visionConfigured = visionConfigured)
            } catch (cancelled: CancellationException) {
                synchronized(indexLock) {
                    syncBatchItemFromJobLocked(jobId)
                    refreshBatchProgressLocked(batchId)
                }
                throw cancelled
            } catch (failure: Throwable) {
                // resumeImport persists UNKNOWN_OUTCOME before throwing when
                // a provider call may have been dispatched.  Reflect the
                // durable job result and leave the batch failed/blocked;
                // never turn an uncertain external call into an auto-retry.
                synchronized(indexLock) {
                    syncBatchItemFromJobLocked(jobId, failure.message)
                    refreshBatchProgressLocked(batchId)
                }
                if (isUnknownEmbeddingFailure(failure)) {
                    synchronized(indexLock) { failBatchLocked(batchId, failure.message ?: API_EMBEDDING_UNKNOWN_ERROR) }
                    throw failure
                }
            }
            synchronized(indexLock) {
                if (!generationStillCurrentLocked(batchId)) {
                    failBatchLocked(batchId, BATCH_GENERATION_CHANGED)
                    return
                }
                refreshBatchProgressLocked(batchId)
            }
        }
        synchronized(indexLock) { refreshBatchProgressLocked(batchId) }
    }

    /** Refresh counters from import_items; job state is the source of truth. */
    fun refreshBatchProgress(batchId: String) = synchronized(indexLock) {
        refreshBatchProgressLocked(batchId)
    }

    fun generationStillCurrent(batchId: String): Boolean = synchronized(indexLock) {
        generationStillCurrentLocked(batchId)
    }

    fun failBatch(batchId: String, reason: String) = synchronized(indexLock) {
        failBatchLocked(batchId, reason)
    }

    fun queuedJobIds(batchId: String): List<String> = synchronized(indexLock) {
        db.query(
            "SELECT job_id FROM import_items WHERE batch_id = ? AND job_id IS NOT NULL AND job_id != '' AND state IN (?,?,?) ORDER BY id",
            listOf(batchId, ImportItemState.PENDING.name, ImportItemState.COPYING.name, ImportItemState.QUEUED.name),
        ).map { it.string("job_id") }
    }

    private fun validateConsentTicketLocked(ticket: ConsumedConsentTicket) {
        val kb = db.query(
            "SELECT id, deleted_at, embedding_space_id FROM knowledge_bases WHERE id = ?",
            listOf(ticket.knowledgeBaseId),
        ).singleOrNull() ?: error("consent ticket knowledge base not found")
        check(kb.string("deleted_at").isBlank()) { "consent ticket knowledge base is deleted" }
        val lines = ticket.fingerprint.split('\n')
        val action = lines.firstOrNull().orEmpty()
        check(action in setOf("GRANT", "RETRY", "REBUILD", "REBIND")) { "consent ticket action is invalid" }
        val documentHash = when (ticket.kind) {
            "VISION", "API_EMBEDDING" -> lines.lastOrNull().orEmpty()
            else -> ""
        }
        if (ticket.kind == "VISION" || ticket.kind == "API_EMBEDDING") {
            check(documentHash.matches(QUERY_HASH_PATTERN)) { "consent ticket document fingerprint is missing or invalid" }
            check(documentHash == documentsFingerprintHash(ticket.knowledgeBaseId)) {
                "consent ticket documents changed; no data was sent"
            }
        }
        when (ticket.kind) {
            "VISION" -> {
                check(ticket.jobId != null) { "Vision consent ticket is missing a job" }
                val job = db.query(
                    "SELECT kb_id, document_id, stage, has_images, error FROM import_jobs WHERE id = ?",
                    listOf(ticket.jobId),
                ).singleOrNull() ?: error("Vision consent ticket job not found")
                check(job.string("kb_id") == ticket.knowledgeBaseId) { "consent ticket job/KB mismatch" }
                check(job.boolean("has_images")) { "Vision consent ticket has no visual input" }
                val document = db.query(
                    "SELECT kb_id, blob_hash, deleted_at FROM documents WHERE id = ?",
                    listOf(job.string("document_id")),
                ).singleOrNull() ?: error("Vision consent ticket document not found")
                check(document.string("kb_id") == ticket.knowledgeBaseId && document.string("deleted_at").isBlank()) {
                    "Vision consent ticket document is deleted or outside the KB"
                }
                check(document.string("blob_hash").isNotBlank() &&
                    db.query("SELECT hash FROM blobs WHERE hash = ?", listOf(document.string("blob_hash"))).isNotEmpty()) {
                    "Vision consent ticket source bytes are unavailable"
                }
                check(lines.size == 3 && lines[1].isNotBlank() && lines[1] == visionFingerprint()) {
                    "Vision destination changed; no image was sent"
                }
                if (action == "RETRY") {
                    check(job.string("stage") == ImportStage.FAILED.name &&
                        job.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true) &&
                        job.string("error").contains("vision", ignoreCase = true)) {
                        "Vision retry requires a persisted uncertain Vision outcome"
                    }
                } else {
                    check(action == "GRANT") { "Vision consent action is invalid" }
                    check(job.string("stage") == ImportStage.WAITING_FOR_VISION_MODEL.name ||
                        job.string("stage") == ImportStage.AWAITING_UPLOAD_CONSENT.name) {
                        "Vision consent is no longer required for this job"
                    }
                }
            }
            "API_EMBEDDING" -> {
                when (action) {
                    "GRANT", "RETRY" -> {
                        check(ticket.jobId != null) { "Embedding consent ticket is missing a job" }
                        val job = db.query(
                            "SELECT kb_id, stage, error, embedding_is_api, embedding_consent, document_id FROM import_jobs WHERE id = ?",
                            listOf(ticket.jobId),
                        ).singleOrNull() ?: error("Embedding consent ticket job not found")
                        check(job.string("kb_id") == ticket.knowledgeBaseId) { "consent ticket job/KB mismatch" }
                        check(job.boolean("embedding_is_api")) { "Embedding consent ticket does not select API embedding" }
                        val binding = ApiEmbeddingBinding.parseSpaceId(lines.getOrNull(1).orEmpty())
                            ?: error("Embedding consent binding is invalid")
                        check(kb.string("embedding_space_id") == binding.spaceId) {
                            "Embedding destination changed; no text was sent"
                        }
                        check(apiEmbedderForSpace(binding.spaceId) != null) {
                            "Embedding consent binding is unavailable; no text was sent"
                        }
                        val documentId = job.string("document_id")
                        check(documentId.isNotBlank()) { "Embedding consent document is missing" }
                        val document = db.query(
                            "SELECT kb_id, blob_hash, deleted_at FROM documents WHERE id = ?",
                            listOf(documentId),
                        ).singleOrNull() ?: error("Embedding consent document is missing")
                        check(document.string("kb_id") == ticket.knowledgeBaseId && document.string("deleted_at").isBlank()) {
                            "Embedding consent document is deleted or outside the KB"
                        }
                        check(document.string("blob_hash").isNotBlank() &&
                            db.query("SELECT hash FROM blobs WHERE hash = ?", listOf(document.string("blob_hash"))).isNotEmpty()) {
                            "Embedding consent source bytes are unavailable"
                        }
                        if (action == "GRANT") {
                            check(job.string("stage") == ImportStage.AWAITING_EMBEDDING_CONSENT.name && !job.boolean("embedding_consent")) {
                                "Embedding consent is no longer required for this job"
                            }
                        } else {
                            check(job.string("stage") == ImportStage.FAILED.name &&
                                job.boolean("embedding_consent") &&
                                job.string("error").contains("UNKNOWN_OUTCOME", ignoreCase = true) &&
                                job.string("error").contains("embedding", ignoreCase = true)) {
                                "Embedding retry requires a persisted uncertain outcome"
                            }
                        }
                    }
                    "REBUILD" -> {
                        check(ticket.jobId == null) { "Embedding rebuild ticket must not target a job" }
                        val binding = ApiEmbeddingBinding.parseSpaceId(lines.getOrNull(1).orEmpty())
                            ?: error("Embedding rebuild binding is invalid")
                        check(kb.string("embedding_space_id") == binding.spaceId) {
                            "Embedding rebuild destination changed; no text was sent"
                        }
                        check(apiEmbedderForSpace(binding.spaceId) != null) {
                            "Embedding rebuild binding is unavailable; no text was sent"
                        }
                        check(lines.size == 3) { "Embedding rebuild ticket fingerprint is invalid" }
                    }
                    "REBIND" -> {
                        check(ticket.jobId == null) { "Embedding rebind ticket must not target a job" }
                        val binding = ApiEmbeddingBinding.parseSpaceId(lines.getOrNull(1).orEmpty())
                            ?: error("Embedding rebind binding is invalid")
                        check(lines.size == 4 && lines[2] in setOf("fresh", "duplicate")) {
                            "Embedding rebind ticket fingerprint is invalid"
                        }
                        check(kb.string("embedding_space_id") != binding.spaceId) {
                            "Embedding rebind target is already active"
                        }
                        check(apiEmbedderForSpace(binding.spaceId) != null) {
                            "Embedding rebind binding is unavailable; no text was sent"
                        }
                        if (lines[2] == "fresh") {
                            check(unknownRebindGates(ticket.knowledgeBaseId).isEmpty()) {
                                "A prior uncertain rebind requires duplicate-charge acknowledgement"
                            }
                        }
                    }
                    else -> error("Embedding consent action is invalid")
                }
            }
            else -> error("unsupported consent ticket kind")
        }
    }

    private fun documentsFingerprintHash(knowledgeBaseId: String): String = sha256Hex(
        db.query(
            "SELECT id,blob_hash,active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id",
            listOf(knowledgeBaseId),
        ).joinToString("\n") { "${it.string("id")}:${it.string("blob_hash")}:${it.string("active_version_id")}" }
            .toByteArray(Charsets.UTF_8),
    )

    private fun claimNextBatchJob(batchId: String): String? = synchronized(indexLock) {
        db.transaction {
            val batch = db.query(
                "SELECT state FROM import_batches WHERE id = ?",
                listOf(batchId),
            ).singleOrNull() ?: error("import batch not found")
            check(generationStillCurrentLocked(batchId)) { BATCH_GENERATION_CHANGED }
            if (batch.string("state") == ImportBatchState.FAILED.name ||
                batch.string("state") == ImportBatchState.CANCELLED.name
            ) return@transaction null
            val item = db.query(
                "SELECT id, job_id FROM import_items WHERE batch_id = ? AND job_id IS NOT NULL AND job_id != '' AND state IN (?,?,?) ORDER BY id LIMIT 1",
                listOf(batchId, ImportItemState.PENDING.name, ImportItemState.COPYING.name, ImportItemState.QUEUED.name),
            ).singleOrNull() ?: return@transaction null
            db.execute(
                "UPDATE import_items SET state = ?, error = NULL, attempt_count = attempt_count + 1 WHERE id = ? AND state IN (?,?,?)",
                listOf(
                    ImportItemState.PROCESSING.name,
                    item.string("id"),
                    ImportItemState.PENDING.name,
                    ImportItemState.COPYING.name,
                    ImportItemState.QUEUED.name,
                ),
            )
            db.execute(
                "UPDATE import_batches SET state = ?, updated_at = ? WHERE id = ? AND state NOT IN (?,?)",
                listOf(
                    ImportBatchState.PROCESSING.name,
                    Utc.nowIso(),
                    batchId,
                    ImportBatchState.FAILED.name,
                    ImportBatchState.CANCELLED.name,
                ),
            )
            item.string("job_id")
        }
    }

    private fun refreshBatchProgressLocked(batchId: String) {
        val items = db.query("SELECT state FROM import_items WHERE batch_id = ?", listOf(batchId))
        if (items.isEmpty()) {
            db.execute(
                "UPDATE import_batches SET state = ?, total_items = 0, copied = 0, processing = 0, waiting = 0, failed = 0, updated_at = ? WHERE id = ?",
                listOf(ImportBatchState.STAGING.name, Utc.nowIso(), batchId),
            )
            return
        }
        var copied = 0
        var processing = 0
        var waiting = 0
        var failed = 0
        var pending = 0
        var published = 0
        items.forEach { row ->
            when (runCatching { ImportItemState.valueOf(row.string("state")) }.getOrNull()) {
                ImportItemState.PUBLISHED -> {
                    copied += 1
                    published += 1
                }
                ImportItemState.COPYING,
                ImportItemState.QUEUED,
                ImportItemState.PROCESSING,
                ImportItemState.WAITING,
                -> {
                    copied += 1
                    if (row.string("state") == ImportItemState.PROCESSING.name) processing += 1
                    if (row.string("state") == ImportItemState.WAITING.name) waiting += 1
                }
                ImportItemState.PENDING -> pending += 1
                ImportItemState.FAILED, ImportItemState.CANCELLED -> failed += 1
                null -> failed += 1
            }
        }
        val generationOk = generationStillCurrentLocked(batchId)
        val total = items.size
        val active = pending > 0 || processing > 0 ||
            items.any { it.string("state") == ImportItemState.COPYING.name || it.string("state") == ImportItemState.QUEUED.name }
        val state = when {
            !generationOk -> ImportBatchState.FAILED
            failed > 0 && !active && waiting == 0 -> ImportBatchState.FAILED
            waiting > 0 && !active -> ImportBatchState.WAITING
            active -> if (pending > 0 || items.any { it.string("state") == ImportItemState.COPYING.name }) {
                ImportBatchState.COPYING
            } else {
                ImportBatchState.PROCESSING
            }
            failed > 0 -> ImportBatchState.FAILED
            published == total && total > 0 -> ImportBatchState.COMPLETED
            else -> ImportBatchState.PROCESSING
        }
        val error = if (!generationOk) BATCH_GENERATION_CHANGED else null
        db.execute(
            "UPDATE import_batches SET copied = ?, processing = ?, waiting = ?, failed = ?, total_items = ?, state = ?, error = CASE WHEN ? IS NULL THEN error ELSE ? END, updated_at = ? WHERE id = ?",
            listOf(copied, processing, waiting, failed, total, state.name, error, error, Utc.nowIso(), batchId),
        )
    }

    private fun generationStillCurrentLocked(batchId: String): Boolean {
        val row = db.query("SELECT kb_id, generation_id FROM import_batches WHERE id = ?", listOf(batchId)).singleOrNull()
            ?: return false
        val bound = row.string("generation_id").ifBlank { null }
        val current = db.query(
            "SELECT active_generation_id FROM knowledge_bases WHERE id = ?",
            listOf(row.string("kb_id")),
        ).singleOrNull()?.string("active_generation_id")?.ifBlank { null }
        return bound == current
    }

    private fun failBatchLocked(batchId: String, reason: String) {
        db.transaction {
            db.execute(
                "UPDATE import_batches SET state = ?, error = ?, updated_at = ? WHERE id = ?",
                listOf(ImportBatchState.FAILED.name, reason, Utc.nowIso(), batchId),
            )
            db.execute(
                "UPDATE import_items SET state = ?, error = ? WHERE batch_id = ? AND state NOT IN (?,?)",
                listOf(ImportItemState.FAILED.name, reason, batchId, ImportItemState.PUBLISHED.name, ImportItemState.CANCELLED.name),
            )
            db.execute(
                "UPDATE import_jobs SET stage = ?, error = ?, updated_at = ? WHERE batch_id = ? AND stage NOT IN (?,?,?,?)",
                listOf(
                    ImportStage.FAILED.name,
                    reason,
                    Utc.nowIso(),
                    batchId,
                    ImportStage.READY.name,
                    ImportStage.READY_WITH_VISUAL_GAPS.name,
                    ImportStage.FAILED.name,
                    ImportStage.CANCELLED.name,
                ),
            )
        }
    }

    private fun syncBatchItemFromJobLocked(jobId: String, fallbackError: String? = null) {
        val job = db.query("SELECT batch_id, stage, error FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull() ?: return
        val batchId = job.string("batch_id").ifBlank { return }
        val stage = runCatching { ImportStage.valueOf(job.string("stage")) }.getOrNull()
        val state = when (stage) {
            ImportStage.READY, ImportStage.READY_WITH_VISUAL_GAPS -> ImportItemState.PUBLISHED
            ImportStage.FAILED -> ImportItemState.FAILED
            ImportStage.CANCELLED -> ImportItemState.CANCELLED
            ImportStage.WAITING_FOR_VISION_MODEL,
            ImportStage.AWAITING_UPLOAD_CONSENT,
            ImportStage.AWAITING_EMBEDDING_CONSENT,
            -> ImportItemState.WAITING
            ImportStage.COPYING -> ImportItemState.COPYING
            ImportStage.PARSING,
            ImportStage.VISION_PROCESSING,
            ImportStage.CHUNKING,
            ImportStage.SELECT_EMBEDDING_BACKEND,
            ImportStage.EMBEDDING,
            ImportStage.INDEXING,
            ImportStage.HASHING,
            ImportStage.QUEUED,
            ImportStage.RETRY_WAIT,
            ImportStage.PAUSED,
            null,
            -> ImportItemState.PROCESSING
        }
        db.execute(
            "UPDATE import_items SET state = ?, error = ? WHERE batch_id = ? AND job_id = ?",
            listOf(state.name, job.string("error").ifBlank { fallbackError }, batchId, jobId),
        )
    }

    private fun ensureBatchGenerationCurrentLocked(jobId: String) {
        val batchId = db.query("SELECT batch_id FROM import_jobs WHERE id = ?", listOf(jobId))
            .singleOrNull()?.string("batch_id")?.ifBlank { null } ?: return
        check(generationStillCurrentLocked(batchId)) { BATCH_GENERATION_CHANGED }
    }

    /** Called inside the same publication transaction that changes active gen. */
    private fun advanceBatchGenerationAfterPublicationLocked(jobId: String, generationId: String) {
        val batchId = db.query("SELECT batch_id FROM import_jobs WHERE id = ?", listOf(jobId))
            .singleOrNull()?.string("batch_id")?.ifBlank { null } ?: return
        val batch = db.query("SELECT state FROM import_batches WHERE id = ?", listOf(batchId)).singleOrNull()
            ?: error("import batch not found")
        check(batch.string("state") != ImportBatchState.FAILED.name && batch.string("state") != ImportBatchState.CANCELLED.name) {
            "import batch is no longer publishable"
        }
        val active = db.query(
            "SELECT active_generation_id FROM knowledge_bases WHERE id = (SELECT kb_id FROM import_batches WHERE id = ?)",
            listOf(batchId),
        ).singleOrNull()?.string("active_generation_id").orEmpty()
        check(active == generationId) { "batch publication generation changed unexpectedly" }
        db.execute("UPDATE import_batches SET generation_id = ?, updated_at = ? WHERE id = ?", listOf(generationId, Utc.nowIso(), batchId))
    }

    fun applyConsentTicket(ticketId: String, visionConfigured: Boolean): ImportJob? {
        val ticket = consumeConsentTicket(ticketId) ?: return null
        // The ticket was validated before the consumed bit was set.  Repeat
        // the durable checks at the Worker boundary immediately before any
        // action; API operations perform an additional dispatch-time check.
        synchronized(indexLock) { validateConsentTicketLocked(ticket) }
        val action = ticket.fingerprint.substringBefore('\n')
        return when (ticket.kind) {
            "VISION" -> {
                val jobId = ticket.jobId ?: error("Vision consent ticket is missing a job")
                val lines = ticket.fingerprint.split('\n')
                val target = lines.getOrNull(1).orEmpty()
                val documentHash = lines.getOrNull(2).orEmpty()
                if (action == "RETRY") retryUnknownVision(
                    jobId,
                    acknowledgeDuplicateCharge = true,
                    expectedVisionFingerprint = target,
                    expectedDocumentsFingerprintHash = documentHash,
                )
                else grantVisionConsent(
                    jobId,
                    expectedVisionFingerprint = target,
                    expectedDocumentsFingerprintHash = documentHash,
                )
            }
            "API_EMBEDDING" -> when (action) {
                "RETRY" -> retryUnknownEmbedding(
                    ticket.jobId ?: error("Embedding retry ticket is missing a job"),
                    acknowledgeDuplicateCharge = true,
                    visionConfigured = visionConfigured,
                )
                "GRANT" -> grantEmbeddingConsent(
                    ticket.jobId ?: error("Embedding consent ticket is missing a job"),
                    visionConfigured,
                )
                "REBUILD" -> {
                    rebuildIndex(ticket.knowledgeBaseId)
                    listJobs().firstOrNull { it.first.knowledgeBaseId == ticket.knowledgeBaseId }?.first
                }
                "REBIND" -> {
                    val lines = ticket.fingerprint.split('\n')
                    val binding = ApiEmbeddingBinding.parseSpaceId(lines.getOrNull(1).orEmpty())
                        ?: error("API Embedding binding is no longer available")
                    rebindApiKnowledgeBase(
                        ticket.knowledgeBaseId,
                        binding,
                        embeddingConsent = true,
                        acknowledgeDuplicateCharge = lines.getOrNull(2) == "duplicate",
                    )
                    null
                }
                else -> grantEmbeddingConsent(
                    ticket.jobId ?: error("Embedding consent ticket is missing a job"),
                    visionConfigured,
                )
            }
            else -> null
        }
    }

    private fun itemStateFor(job: ImportJob): ImportItemState = when (job.stage) {
        ImportStage.READY, ImportStage.READY_WITH_VISUAL_GAPS -> ImportItemState.PUBLISHED
        ImportStage.FAILED -> ImportItemState.FAILED
        ImportStage.CANCELLED -> ImportItemState.CANCELLED
        ImportStage.WAITING_FOR_VISION_MODEL, ImportStage.AWAITING_UPLOAD_CONSENT, ImportStage.AWAITING_EMBEDDING_CONSENT ->
            ImportItemState.WAITING
        ImportStage.COPYING -> ImportItemState.COPYING
        else -> ImportItemState.QUEUED
    }

    private fun expandKnowledgeArchive(
        displayName: String,
        bytes: ByteArray,
        visionConfigured: Boolean,
        knowledgeBaseId: String,
        visionConsent: Boolean,
        embeddingIsApi: Boolean,
        embeddingConsent: Boolean,
        pauseAt: ImportStage?,
    ): ImportJob {
        // Persist the batch before walking the archive.  Entry payloads are
        // handed to the repository one at a time and immediately reduced to
        // a CAS-backed COPYING job; the old implementation retained a full
        // extracted list in memory and then processed it a second time.
        val batchId = beginBatch(knowledgeBaseId, ImportBatchKind.ZIP, displayName)
        var last: ImportJob? = null
        val summary = try {
            KnowledgeArchive.forEachEntry(bytes) { entry, payload ->
                val child = importBytes(
                    displayName = entry.name,
                    mediaType = guessedMime(entry.format),
                    bytes = payload,
                    visionConfigured = visionConfigured,
                    knowledgeBaseId = knowledgeBaseId,
                    pauseAt = ImportStage.COPYING,
                    visionConsent = visionConsent,
                    embeddingIsApi = embeddingIsApi,
                    embeddingConsent = embeddingConsent,
                )
                bindJobToBatch(batchId, child, entry.name)
                last = child
            }
        } catch (failure: Throwable) {
            failBatch(batchId, failure.message ?: "Archive expansion failed")
            throw failure
        }
        if (!summary.ok) {
            failBatch(batchId, summary.reason)
            error(summary.reason)
        }
        refreshBatchProgress(batchId)
        if (pauseAt != ImportStage.COPYING) {
            processBatch(batchId, visionConfigured)
            last = db.query(
                "SELECT id FROM import_jobs WHERE batch_id = ? ORDER BY updated_at DESC, id DESC LIMIT 1",
                listOf(batchId),
            ).singleOrNull()?.string("id")?.let { id ->
                listJobs().firstOrNull { it.first.id == id }?.first
            } ?: last
        }
        return last ?: error("Archive has no importable files")
    }

    private fun expandKnowledgeArchive(
        displayName: String,
        file: File,
        visionConfigured: Boolean,
        knowledgeBaseId: String,
        visionConsent: Boolean,
        embeddingIsApi: Boolean,
        embeddingConsent: Boolean,
        pauseAt: ImportStage?,
    ): ImportJob {
        val batchId = beginBatch(knowledgeBaseId, ImportBatchKind.ZIP, displayName)
        var last: ImportJob? = null
        val summary = try {
            KnowledgeArchive.forEachEntry(file) { entry, payload ->
                val child = importBytes(
                    displayName = entry.name,
                    mediaType = guessedMime(entry.format),
                    bytes = payload,
                    visionConfigured = visionConfigured,
                    knowledgeBaseId = knowledgeBaseId,
                    pauseAt = ImportStage.COPYING,
                    visionConsent = visionConsent,
                    embeddingIsApi = embeddingIsApi,
                    embeddingConsent = embeddingConsent,
                )
                bindJobToBatch(batchId, child, entry.name)
                last = child
            }
        } catch (failure: Throwable) {
            failBatch(batchId, failure.message ?: "Archive expansion failed")
            throw failure
        }
        if (!summary.ok) {
            failBatch(batchId, summary.reason)
            error(summary.reason)
        }
        refreshBatchProgress(batchId)
        if (pauseAt != ImportStage.COPYING) {
            processBatch(batchId, visionConfigured)
            last = db.query(
                "SELECT id FROM import_jobs WHERE batch_id = ? ORDER BY updated_at DESC, id DESC LIMIT 1",
                listOf(batchId),
            ).singleOrNull()?.string("id")?.let { id ->
                listJobs().firstOrNull { it.first.id == id }?.first
            } ?: last
        }
        return last ?: error("Archive has no importable files")
    }

    private fun guessedMime(format: SourceFormat): String = when (format) {
        SourceFormat.IMAGE -> "image/*"
        SourceFormat.PDF -> "application/pdf"
        SourceFormat.MARKDOWN -> "text/markdown"
        SourceFormat.TEXT -> "text/plain"
        SourceFormat.OFFICE_ARCHIVE -> "application/octet-stream"
        SourceFormat.KNOWLEDGE_ARCHIVE -> "application/zip"
        SourceFormat.UNKNOWN -> "application/octet-stream"
    }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun bytesToFloats(bytes: ByteArray, dimension: Int): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buf.float }
    }

    companion object {
        const val DEFAULT_KB_ID = "kb-default"
        const val PARSER_FINGERPRINT = "text-utf8-v1"
        private const val UNKNOWN_REBIND_PREFIX = "__api_rebind_unknown__:"
        private const val API_QUERY_PENDING_ERROR =
            "API_QUERY_PENDING: explicit retry authorization is required"
        private const val API_QUERY_UNKNOWN_ERROR =
            "UNKNOWN_OUTCOME: API query embedding result is uncertain; explicit retry authorization is required"
        private const val API_EMBEDDING_UNKNOWN_ERROR =
            "UNKNOWN_OUTCOME: API embedding result is uncertain; explicit duplicate-charge acknowledgement is required"
        private const val API_EMBEDDING_CANCEL_UNKNOWN_ERROR =
            "UNKNOWN_OUTCOME: API embedding request was cancelled after dispatch; its external outcome is uncertain"
        private const val BATCH_GENERATION_CHANGED =
            "Knowledge base generation changed; this batch cannot publish."
        private const val API_EMBEDDING_FAILED_ERROR =
            "API embedding failed; inspect the configured provider and retry only when safe"
        private const val TEXT_ONLY_VISUAL_GAPS_PREFIX = "TEXT_ONLY_VISUAL_GAPS: "
        private const val TEXT_ONLY_VISUAL_GAPS_MESSAGE =
            "Indexed text only; visual evidence remains unprocessed and is not READY."
        private const val ACTIVE_GENERATION_POINTER = "\u0000active_generation"
        private val QUERY_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private class TextOnlyUnavailable(message: String) : IllegalStateException(message)

private fun SqlRow.boolean(name: String): Boolean = when (val value = columns[name]) {
    is Boolean -> value
    is Number -> value.toLong() != 0L
    else -> value?.toString()?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
}

/** TextEmbedder facade for a query vector restored from the immutable cache. */
private class CachedQueryEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : TextEmbedder {
    override fun embed(text: String): FloatArray =
        error("query vector was already restored from the immutable cache")
}
