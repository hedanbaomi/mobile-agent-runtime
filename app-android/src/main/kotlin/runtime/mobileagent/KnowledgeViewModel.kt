// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.feature.knowledge.*
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.sha256Hex
import android.content.Intent
import java.util.UUID
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.knowledge.VisionBinding
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.acceptsImages

internal data class VisionConsentTarget(val label: String, val fingerprint: String)

internal fun visionConsentTarget(providerName: String, binding: VisionBinding): VisionConsentTarget {
    val label = "$providerName · ${binding.endpoint} · ${binding.modelId} · provider rev ${binding.providerRevision} / model rev ${binding.modelRevision}"
    return VisionConsentTarget(label, binding.fingerprint)
}

/**
 * Enumerate every configured image-capable model and bind it to the provider
 * row that owns it.  The first item is only a deterministic UI default; its
 * fingerprint is never used as an implicit execution target.
 */
internal fun visionTargetOptions(
    providers: List<ProviderProfile>,
    models: List<ModelProfile>,
): List<KnowledgeVisionTargetUi> {
    val providersById = providers.associateBy { it.id }
    return models.asSequence()
        .filter { it.acceptsImages() }
        .mapNotNull { model ->
            val provider = providersById[model.providerId] ?: return@mapNotNull null
            val binding = visionProfileBinding(provider, model)
            KnowledgeVisionTargetUi(
                fingerprint = binding.fingerprint,
                label = visionConsentTarget(provider.name, binding).label,
                providerId = provider.id,
                modelProfileId = model.id,
                modelId = model.modelId,
            )
        }
        .sortedWith(compareBy<KnowledgeVisionTargetUi>({ it.label }, { it.fingerprint }))
        .toList()
}

data class EmbeddingConfirmation(val target: String, val retry: Boolean, val rebind: Boolean, val documentCount: Int,
    val queryRetry: Boolean = false)

/** One importable file discovered by [KnowledgeFolderWalk]; [relativePath] keeps the folder shape. */
internal data class KnowledgeFolderEntry(val relativePath: String, val key: String)

/**
 * Minimal view of a folder node so the traversal contract stays testable without a
 * DocumentsProvider.  Production supplies the SAF adapter; tests supply plain in-memory trees.
 */
internal interface KnowledgeFolderNode {
    val name: String?
    val isDirectory: Boolean

    /** Stable provider identity of this node (a document URI string in production). */
    val key: String

    /**
     * Read this directory's children **one entry at a time** and never materialize the listing.
     *
     * A SAF provider can expose directories that are far larger than any import budget, so the
     * enumeration itself is bounded: implementations must stop reading rows once [maximum] entries
     * were handed to [visit], must poll [cancelled] before reading and between rows, and must stop as
     * soon as [visit] returns false.
     *
     * @return [KnowledgeFolderChildRead.COMPLETE] only when every child of the directory was read.
     *   [KnowledgeFolderChildRead.UNREADABLE] means the listing could not be read faithfully, so the
     *   caller must fail closed rather than import what happened to be readable.
     */
    fun forEachChild(
        maximum: Int,
        cancelled: () -> Boolean,
        visit: (KnowledgeFolderNode) -> Boolean,
    ): KnowledgeFolderChildRead
}

/** Outcome of streaming one directory's children. */
internal enum class KnowledgeFolderChildRead {
    /** Every child of the directory was handed to the visitor. */
    COMPLETE,

    /** The read stopped early: cancellation, an exhausted budget, or the visitor asked to stop. */
    STOPPED,

    /** The directory could not be read faithfully; the caller must fail closed. */
    UNREADABLE,
}

internal sealed class KnowledgeFolderWalkResult {
    data class Files(val files: List<KnowledgeFolderEntry>) : KnowledgeFolderWalkResult()
    data class Rejected(val reason: KnowledgeFolderWalkReason) : KnowledgeFolderWalkResult()
    data object Cancelled : KnowledgeFolderWalkResult()
}

internal enum class KnowledgeFolderWalkReason { EMPTY, FILE_LIMIT, DEPTH_LIMIT, NODE_LIMIT, UNREADABLE }

/**
 * Bounded, cancellable traversal for SAF folder imports.
 *
 * Every limit is enforced *while reading*: a folder with more files, more levels or more visited
 * entries than the budget is rejected as a whole rather than importing a silent subset, and a
 * cancelled walk reports [KnowledgeFolderWalkResult.Cancelled] instead of a partial list.  Because
 * [KnowledgeFolderNode.forEachChild] streams single entries, a single over-wide directory is never
 * listed in full before the budget applies.  The traversal is iterative, so a pathologically deep
 * provider tree cannot overflow the stack, and it is a pure function over [KnowledgeFolderNode] so
 * all boundaries are unit-testable.
 */
internal object KnowledgeFolderWalk {
    /** The same selection budget the multi-file picker enforces. */
    const val MAX_FILES = 500

    /** SAF trees are user folders, not build trees; anything deeper is treated as a mistake. */
    const val MAX_DEPTH = 32

    /** Total visited directory entries (files plus directories) for the whole walk. */
    const val MAX_NODES = 5_000

    /** Rows a single directory may hand over before the walk fails closed. */
    const val MAX_DIRECTORY_CHILDREN = MAX_NODES

    fun walk(
        root: KnowledgeFolderNode,
        cancelled: () -> Boolean = { false },
    ): KnowledgeFolderWalkResult {
        val files = ArrayList<KnowledgeFolderEntry>()
        val stack = ArrayDeque<Pending>()
        stack.addLast(Pending(root, "", 0))
        var visited = 0
        while (stack.isNotEmpty()) {
            if (cancelled()) return KnowledgeFolderWalkResult.Cancelled
            val current = stack.removeLast()
            if (current.depth > MAX_DEPTH) {
                return KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.DEPTH_LIMIT)
            }
            var stop: KnowledgeFolderWalkResult? = null
            val read = current.node.forEachChild(MAX_DIRECTORY_CHILDREN, cancelled) { child ->
                visited += 1
                if (visited > MAX_NODES) {
                    stop = KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.NODE_LIMIT)
                    return@forEachChild false
                }
                // Missing metadata makes the listing incomplete; never import only its siblings.
                val name = child.name?.takeIf { it.isNotBlank() }
                if (name == null) {
                    stop = KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.UNREADABLE)
                    false
                } else if (child.isDirectory) {
                    stack.addLast(Pending(child, current.prefix + name + "/", current.depth + 1))
                    true
                } else {
                    files += KnowledgeFolderEntry(current.prefix + name, child.key)
                    if (files.size > MAX_FILES) {
                        stop = KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.FILE_LIMIT)
                        false
                    } else {
                        true
                    }
                }
            }
            stop?.let { return it }
            if (cancelled()) return KnowledgeFolderWalkResult.Cancelled
            when (read) {
                KnowledgeFolderChildRead.COMPLETE -> Unit
                // A directory that could not be read to its end within the per-directory budget is a
                // bounded failure, never a silently truncated import.
                KnowledgeFolderChildRead.STOPPED ->
                    return KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.NODE_LIMIT)
                // An unreadable directory (provider failure, missing document id) must abort the whole
                // selection: importing only the readable siblings would be a partial import.
                KnowledgeFolderChildRead.UNREADABLE ->
                    return KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.UNREADABLE)
            }
        }
        if (files.isEmpty()) return KnowledgeFolderWalkResult.Rejected(KnowledgeFolderWalkReason.EMPTY)
        return KnowledgeFolderWalkResult.Files(files)
    }

    private data class Pending(val node: KnowledgeFolderNode, val prefix: String, val depth: Int)
}

/**
 * SAF adapter for [KnowledgeFolderWalk]; the only Android-aware part of a folder import.
 *
 * Children come from `DocumentsContract` rows read one at a time from the provider cursor, so a wide
 * directory is never materialized as a list before the walk budget applies.  A directory that cannot
 * be read faithfully (no cursor, a provider failure, a row without a document id) reports
 * [KnowledgeFolderChildRead.UNREADABLE] instead of a complete listing: a partial selection is never
 * imported and the reason reaches the user.  The cursor is closed on every exit path.
 */
private class DocumentFolderNode(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    override val name: String?,
    override val isDirectory: Boolean,
    override val key: String,
) : KnowledgeFolderNode {
    override fun forEachChild(
        maximum: Int,
        cancelled: () -> Boolean,
        visit: (KnowledgeFolderNode) -> Boolean,
    ): KnowledgeFolderChildRead {
        if (cancelled()) return KnowledgeFolderChildRead.STOPPED
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (cancelledException: CancellationException) {
            throw cancelledException
        } catch (_: RuntimeException) {
            return KnowledgeFolderChildRead.UNREADABLE
        } ?: return KnowledgeFolderChildRead.UNREADABLE
        cursor.use { rows ->
            var read = 0
            while (true) {
                if (cancelled()) return KnowledgeFolderChildRead.STOPPED
                val hasRow = try {
                    rows.moveToNext()
                } catch (cancelledException: CancellationException) {
                    throw cancelledException
                } catch (_: RuntimeException) {
                    return KnowledgeFolderChildRead.UNREADABLE
                }
                if (!hasRow) break
                if (read >= maximum) return KnowledgeFolderChildRead.STOPPED
                read += 1
                // A provider row without a usable document id cannot be turned into a readable
                // child; skipping it would import a partial selection without telling anyone.
                val childId = try {
                    rows.getString(0)?.takeIf { it.isNotBlank() }
                } catch (cancelledException: CancellationException) {
                    throw cancelledException
                } catch (_: RuntimeException) {
                    null
                } ?: return KnowledgeFolderChildRead.UNREADABLE
                val (childName, childMime) = try {
                    rows.getString(1) to rows.getString(2)
                } catch (cancelledException: CancellationException) {
                    throw cancelledException
                } catch (_: RuntimeException) {
                    return KnowledgeFolderChildRead.UNREADABLE
                }
                if (childName.isNullOrBlank() || childMime.isNullOrBlank()) {
                    return KnowledgeFolderChildRead.UNREADABLE
                }
                val childIsDirectory = childMime == DocumentsContract.Document.MIME_TYPE_DIR
                val childKey = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString()
                val child = DocumentFolderNode(
                    resolver = resolver,
                    treeUri = treeUri,
                    documentId = childId,
                    name = childName,
                    isDirectory = childIsDirectory,
                    key = childKey,
                )
                if (!visit(child)) return KnowledgeFolderChildRead.STOPPED
            }
        }
        return KnowledgeFolderChildRead.COMPLETE
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

/**
 * Root node of a `ACTION_OPEN_DOCUMENT_TREE` selection.  The tree document id is provider metadata,
 * not a filesystem path, and it is never surfaced to the model or to diagnostics.
 */
internal fun folderRootNode(resolver: ContentResolver, treeUri: Uri): KnowledgeFolderNode? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    val name = runCatching {
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
    }.getOrNull()
    return DocumentFolderNode(
        resolver = resolver,
        treeUri = treeUri,
        documentId = documentId,
        name = name,
        isDirectory = true,
        key = documentUri.toString(),
    )
}

/** Update the target of the staged choice before confirmation. */
internal fun selectKnowledgeImportVisionTarget(state: KnowledgeUiState, fingerprint: String?): KnowledgeUiState {
    if (state.importSubmitting) return state
    val pending = state.pendingImport ?: return state
    return state.copy(pendingImport = pending.copy(
        selectedVisionTargetFingerprint = fingerprint,
        visionTargetSelectionInitialized = true,
    ))
}

/** Apply the coordinator's acceptance to the current choice, which may have changed during IO. */
internal fun applyKnowledgeImportStart(
    state: KnowledgeUiState,
    start: KnowledgeImportStart,
    submittedSelectionId: String?,
): KnowledgeUiState = when (start) {
    is KnowledgeImportStart.Started -> if (submittedSelectionId != null &&
        state.pendingImport?.id == submittedSelectionId) state.copy(pendingImport = null) else state
    is KnowledgeImportStart.AlreadyRunning -> {
        val reason = "已有导入正在准备或复制原件，请等待其完成后再提交本次选择；本次选择已保留。"
        state.copy(error = reason, status = reason)
    }
    is KnowledgeImportStart.Rejected -> state.copy(error = start.reason, status = start.reason)
}

class KnowledgeViewModel(
    application: Application,
    private val importCoordinator: KnowledgeImportCoordinator,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, KnowledgeImportCoordinator.forApplication(application))

    private val app = application as MobileAgentApp
    private val repo get() = app.container.knowledge
    val state = mutableStateOf(KnowledgeUiState(visionTargetsLoading = true))
    val visionRequest = mutableStateOf<Pair<String, Boolean>?>(null)
    val visionTarget = mutableStateOf("")
    val embeddingRequest = mutableStateOf<EmbeddingConfirmation?>(null)
    private var consentFingerprint: String? = null
    private var pendingEmbedding: PendingEmbedding? = null
    private var selectionRevision = 0L
    private var embeddingRevision = 0L
    private var visionRevision = 0L
    private var batchPreviewRevision = 0L
    private var evidenceRevision = 0L
    private var refreshJob: Job? = null
    private var refreshRequested = false
    /** Serializes import submissions; the coordinator remains the authoritative staging gate. */
    private var importSubmission: Job? = null
    private var activeOperations = 0
    private var hasActiveImportWork = false
    private var refreshReadError: String? = null
    private val observedImportOperations = mutableSetOf<String>()

    private enum class EmbeddingAction { REBIND, REBUILD, GRANT, RETRY, QUERY_RETRY }
    private data class PendingEmbedding(
        val kind: EmbeddingAction,
        val knowledgeBaseId: String,
        val jobId: String?,
        val binding: ApiEmbeddingBinding,
        val previousSpace: String?,
        val documentsFingerprint: String,
        val retry: Boolean,
        val queryHash: String? = null,
    )

    init {
        reload()
        viewModelScope.launch {
            ImportWorkScheduler.activeWork(app).collect { active ->
                hasActiveImportWork = active
                reload()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (refreshJob?.isActive != true && (hasActiveImportWork || state.value.jobs.any { it.stage in ACTIVE })) reload()
            }
        }
    }

    fun reload() {
        refreshRequested = true
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                while (refreshRequested) {
                    refreshRequested = false
                    val revision = selectionRevision
                    val selected = state.value.selectedBaseId
                    try {
                        val snapshot = runInterruptible(Dispatchers.IO) { loadSnapshot(selected) }
                        if (revision == selectionRevision) {
                            // Keep live UI fields: a slow read must not reset a
                            // status, loading flag or preview changed meanwhile.
                            state.value = state.value.copy(
                                bases = snapshot.bases, selectedBaseId = snapshot.selectedBaseId,
                                storageUsedBytes = snapshot.storageUsedBytes,
                                storageQuotaBytes = snapshot.storageQuotaBytes,
                                foreignKeyIssueCount = snapshot.foreignKeyIssueCount,
                                documents = snapshot.documents, jobs = snapshot.jobs, waiting = snapshot.waiting,
                                rebuildEnabled = snapshot.selectedBaseId != null &&
                                    !state.value.loading &&
                                    snapshot.jobs.none { it.stage in ACTIVE },
                                embeddingSpaceLabel = snapshot.embeddingSpaceLabel,
                                embeddingModels = snapshot.embeddingModels, apiQueryAttempts = snapshot.apiQueryAttempts,
                                batches = snapshot.batches,
                                visionConfigured = snapshot.visionConfigured,
                                visionTargetLabel = snapshot.visionTargetLabel,
                                visionTargetFingerprint = snapshot.visionTargetFingerprint,
                                visionTargets = snapshot.visionTargets,
                                visionTargetsLoading = false,
                                error = state.value.error.takeUnless { it == refreshReadError },
                            )
                            refreshReadError = null
                        } else refreshRequested = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (revision == selectionRevision) {
                            state.value = state.value.copy(visionTargetsLoading = false)
                            fail(failure)
                            refreshReadError = state.value.error
                        }
                    }
                }
            } finally { refreshJob = null }
        }
    }

    /** Refresh provider/model targets after returning from Provider settings. */
    fun refreshVisionTargets() {
        state.value = state.value.copy(visionTargetsLoading = true)
        reload()
    }

    /** IO only. No Compose state is read or written while repository locks may wait. */
    private fun loadSnapshot(selectedId: String?): KnowledgeUiState {
            val storage = repo.storageUsage()
            val bases = repo.listKnowledgeBaseDisplaySummaries()
            val selected = selectedId?.takeIf { id -> bases.any { it.id == id } } ?: bases.firstOrNull()?.id
            val documents = if (selected == null) emptyList() else app.container.db.query(
                "SELECT d.id,d.display_name,d.format,d.active_version_id,v.status,b.byte_length FROM documents d LEFT JOIN document_versions v ON v.id=d.active_version_id LEFT JOIN blobs b ON b.hash=d.blob_hash WHERE d.kb_id=? AND d.deleted_at IS NULL ORDER BY d.display_name,d.id", listOf(selected))
            val jobs = selected?.let(repo::listJobs).orEmpty()
            val visionTargets = visionTargetOptions(
                app.container.profiles.listProviders(),
                app.container.profiles.listModels(),
            )
            val defaultVisionTarget = visionTargets.firstOrNull()
            val target = defaultVisionTarget?.label.orEmpty()
            return KnowledgeUiState(
                storageUsedBytes = storage.totalBytes,
                storageQuotaBytes = storage.quotaBytes,
                foreignKeyIssueCount = repo.foreignKeyCompatibility().size,
                bases = bases.map { KnowledgeBaseUi(it.id, it.name, it.documentCount) },
                selectedBaseId = selected,
                documents = documents.map { row -> KnowledgeDocumentUi(row.string("id"), row.string("display_name"), row.string("format"),
                    row.string("status").ifBlank { "NOT_READY" }, row.long("byte_length").toString() + " B") },
                jobs = jobs.map { (job, name, updated) -> KnowledgeImportJobUi(job.id,
                    if (isRebindUnknown(job.error)) "API Embedding 重新绑定" else name, job.stage.name,
                    if (isRebindUnknown(job.error)) "结果未知：请重新配置 Embedding，明确确认可能重复收费后重试。" else job.error, updated,
                    requiresVisionConsent = job.stage in setOf(ImportStage.WAITING_FOR_VISION_MODEL, ImportStage.AWAITING_UPLOAD_CONSENT) && target.isNotBlank(),
                    unknownOutcome = job.error?.contains("UNKNOWN_OUTCOME") == true && !isRebindUnknown(job.error),
                    embeddingIsApi = job.embeddingIsApi && !(job.error?.contains("UNKNOWN_OUTCOME") == true && job.error?.contains("embedding", true) != true),
                    requiresEmbeddingConsent = job.stage == ImportStage.AWAITING_EMBEDDING_CONSENT) },
                waiting = jobs.filter { it.first.stage in setOf(ImportStage.WAITING_FOR_VISION_MODEL, ImportStage.AWAITING_UPLOAD_CONSENT) }
                    .map { (job, name, _) ->
                        val fingerprint = repo.jobBatchId(job.id)?.let(repo::findBatch)?.visionTarget
                            ?: job.consentedVisionFingerprint
                        val label = if (fingerprint != null) {
                            visionTargets.singleOrNull { it.fingerprint == fingerprint }?.label ?: "原 Vision 目标已变更，请重新确认"
                        } else visionTargets.singleOrNull()?.label ?: "请选择本批次 Vision 目标"
                        KnowledgeWaitingUi(job.id, name, job.error ?: "图片留在本地；需要明确同意上传后才会继续。", label)
                    },
                rebuildEnabled = selected != null,
                embeddingSpaceLabel = selected?.let { kb -> repo.embeddingSpaceId(kb)?.let { space ->
                    ApiEmbeddingBinding.parseSpaceId(space)?.let(app.container.apiEmbeddings::label) ?: "本机模型 · $space"
                } }.orEmpty(),
                embeddingModels = app.container.apiEmbeddings.options().map { (id, label) -> KnowledgeEmbeddingModelUi(id, label) },
                apiQueryAttempts = selected?.let(repo::pendingApiQueries).orEmpty().map { attempt ->
                    KnowledgeQueryAttemptUi(attempt.spaceId, attempt.queryHash,
                        ApiEmbeddingBinding.parseSpaceId(attempt.spaceId)?.let(app.container.apiEmbeddings::label)
                            ?: attempt.spaceId, attempt.retryAuthorized)
                },
                visionTargetLabel = target,
                visionConfigured = visionTargets.isNotEmpty(),
                visionTargetFingerprint = defaultVisionTarget?.fingerprint,
                visionTargets = visionTargets,
                batches = selected?.let(repo::listBatches).orEmpty().map { batch ->
                    val progress = repo.batchProgress(batch.id)
                    KnowledgeBatchUi(
                        id = batch.id,
                        displayName = batch.displayName,
                        kind = batch.kind.name,
                        state = batch.state.name,
                        totalItems = batch.totalItems,
                        copied = progress.copied,
                        processing = progress.processing,
                        waiting = progress.waiting,
                        failed = progress.failed,
                        error = batch.error,
                        published = progress.published,
                        // "已复制" already counts every member whose bytes are local, so the queued
                        // share must never be added again: copied + pending stays equal to total.
                        pending = progress.pending,
                        unknown = progress.unknown,
                        cancelled = progress.cancelled,
                        blockedReason = batch.blockedReason?.name,
                        visionTarget = batch.visionTarget,
                        // Presentation only: the raw fingerprint stays the authorization identity.
                        visionTargetLabel = batch.visionTarget?.let { fingerprint ->
                            visionTargets.firstOrNull { it.fingerprint == fingerprint }?.label
                        },
                        paused = batch.state == ImportBatchState.PAUSED,
                        resumeStagingAvailable = batch.state in setOf(ImportBatchState.COPYING, ImportBatchState.STAGING) &&
                            importCoordinator.activeOperation()?.progress?.value?.batchId != batch.id &&
                            app.container.db.query("SELECT staging_complete FROM import_batches WHERE id=?", listOf(batch.id))
                                .singleOrNull()?.long("staging_complete") == 0L,
                        items = repo.listBatchItemViews(batch.id).map { view ->
                            KnowledgeBatchItemUi(
                                jobId = view.jobId,
                                displayName = view.displayName,
                                state = view.state,
                                error = view.error,
                            )
                        },
                        pipeline = repo.batchPipelineProgress(batch.id),
                        reuse = repo.batchReuseSummary(batch.id, refreshPlan = false),
                        policy = repo.batchPipelinePolicy(batch.id),
                    )
                },
            )
    }
    fun selectBase(id: String) {
        selectionRevision += 1
        closeEvidence(); dismissEmbedding(); dismissVision()
        state.value = state.value.copy(selectedBaseId = id, documents = emptyList(), jobs = emptyList(), waiting = emptyList())
        reload()
    }

    /**
     * Keep the SAF selection in the shell-scoped state while the user visits
     * Provider settings.  Persistable URI grants are still taken before navigation so the selection
     * remains readable when the confirmation dialog returns, but the per-URI ContentResolver calls
     * run on [Dispatchers.IO]: a large multi-select must not block the frame that shows the dialog.
     */
    fun stageImport(uris: List<Uri>, sourceKind: String) {
        if (uris.isEmpty()) return
        // Each picker result is a new choice, even when its URI list matches an older submission.
        val id = UUID.randomUUID().toString()
        state.value = state.value.copy(
            pendingImport = KnowledgePendingImportUi(id = id, uris = uris, sourceKind = sourceKind),
            error = null,
            visionTargetsLoading = true,
        )
        viewModelScope.launch { withContext(Dispatchers.IO) { uris.forEach(::persistReadGrant) } }
        reload()
    }

    fun clearPendingImport() {
        // Cancels preparation only; an accepted batch belongs to the process coordinator.
        importSubmission?.cancel()
        importSubmission = null
        state.value = state.value.copy(pendingImport = null, importSubmitting = false)
    }

    fun selectPendingVisionTarget(fingerprint: String?) {
        state.value = selectKnowledgeImportVisionTarget(state.value, fingerprint)
    }

    fun beginBatchVision(batchId: String) {
        if (batchId.isBlank()) return
        batchPreviewRevision++
        state.value = state.value.copy(
            pendingBatchVision = KnowledgeBatchVisionUi(batchId = batchId),
            visionTargetsLoading = true,
        )
        reload()
    }

    fun dismissBatchVision() {
        batchPreviewRevision++
        state.value = state.value.copy(pendingBatchVision = null)
    }

    fun selectBatchVisionTarget(fingerprint: String?) {
        val pending = state.value.pendingBatchVision ?: return
        val revision = ++batchPreviewRevision
        state.value = state.value.copy(
            pendingBatchVision = pending.copy(
                selectedVisionTargetFingerprint = fingerprint,
                visionTargetSelectionInitialized = true,
                reusePreview = null,
            ),
        )
        if (fingerprint != null) operation({ repo.batchReuseSummary(pending.batchId, fingerprint) }) { preview ->
            val current = state.value.pendingBatchVision
            if (revision == batchPreviewRevision && current?.batchId == pending.batchId &&
                current.selectedVisionTargetFingerprint == fingerprint) {
                state.value = state.value.copy(pendingBatchVision = current.copy(reusePreview = preview))
            }
        }
    }
    fun createBase(name: String) {
        val revision = selectionRevision
        operation({
            require(name.isNotBlank() && name.length <= 120) { "请输入 1—120 字知识库名称。" }
            repo.createKnowledgeBase(name.trim())
        }) { id -> if (revision == selectionRevision) selectBase(id) }
    }
    fun deleteBase(id: String) = action { repo.deleteKnowledgeBase(id); "知识库已删除，引用保留为来源已移除。" }
    fun collectStorage() = action {
        val result = repo.collectStorage()
        "已回收 %.1f MiB；共享资料和未结束任务已保留。".format(java.util.Locale.ROOT, result.reclaimedBytes / (1024.0 * 1024.0))
    }
    fun configureStorageQuota(bytes: Long) = action {
        repo.configureStorageQuota(bytes)
        "本地存储上限已保存。"
    }
    fun deleteDocument(id: String) = action { repo.deleteDocument(id); "文档已从知识库与当前索引删除。" }
    fun cancelJob(id: String) { ImportWorkScheduler.cancel(app, id); reload() }
    /** Cancel the process-owned staging operation, when the UI has an operation handle. */
    fun cancelOperation(operationId: String) {
        if (!importCoordinator.cancel(operationId)) {
            state.value = state.value.copy(error = "导入操作已结束或不存在。")
        }
    }
    fun rebuild() {
        val id = state.value.selectedBaseId ?: return
        val revision = ++embeddingRevision
        val selection = selectionRevision
        readOnIo({
            require(repo.listJobs().none { it.first.knowledgeBaseId == id && it.first.stage.name in ACTIVE }) {
                "请先取消或等待当前导入任务，再重建索引。"
            }
            val api = repo.embeddingSpaceId(id)?.let(ApiEmbeddingBinding::parseSpaceId)
            api?.let { prepareEmbedding(EmbeddingAction.REBUILD, id, null, it, false) }
        }, { revision == embeddingRevision && selection == selectionRevision }) { prepared ->
            if (prepared == null) action { repo.rebuildIndex(id); "索引已从本地数据重建。" }
            else showEmbedding(prepared)
        }
    }

    fun configureEmbedding(modelProfileId: String, dimension: Int) {
        val kbId = state.value.selectedBaseId ?: run { fail(IllegalStateException("请先选择知识库。")); return }
        val wasLoading = state.value.loading
        requestEmbeddingRead {
            require(!wasLoading && repo.listJobs().none { it.first.knowledgeBaseId == kbId && it.first.stage.name in ACTIVE }) {
                "请等待当前导入结束或取消任务，再更换 Embedding。"
            }
            val binding = app.container.apiEmbeddings.binding(modelProfileId, dimension, kbId)
            require(repo.embeddingSpaceId(kbId) != binding.spaceId) { "已绑定此完整模型配置；如需重建请使用重建索引。" }
            prepareEmbedding(EmbeddingAction.REBIND, kbId, null, binding, repo.hasUnknownApiRebind(kbId))
        }
    }
    fun grantEmbedding(jobId: String) { requestJobEmbedding(jobId, false) }
    fun retryEmbedding(jobId: String) { requestJobEmbedding(jobId, true) }
    fun requestQueryRetry(spaceId: String, queryHash: String) {
        val kbId = state.value.selectedBaseId ?: run { fail(IllegalStateException("请先选择知识库。")); return }
        requestEmbeddingRead {
            require(repo.embeddingSpaceId(kbId) == spaceId && repo.pendingApiQueries(kbId).any {
                it.spaceId == spaceId && it.queryHash == queryHash && !it.retryAuthorized
            }) { "查询重试状态或模型已变更，请刷新；未发送文本。" }
            val binding = ApiEmbeddingBinding.parseSpaceId(spaceId) ?: error("API Embedding 绑定不可用。")
            prepareEmbedding(EmbeddingAction.QUERY_RETRY, kbId, null, binding, true, queryHash)
        }
    }
    private fun requestJobEmbedding(jobId: String, retry: Boolean) {
        requestEmbeddingRead {
            val job = repo.listJobs().firstOrNull { it.first.id == jobId }?.first ?: error("导入任务不存在。")
            require(job.embeddingIsApi && !isRebindUnknown(job.error)) { "请使用对应阶段的授权操作。" }
            require(if (retry) job.error?.contains("UNKNOWN_OUTCOME") == true && job.error?.contains("embedding", true) == true
                else job.stage == ImportStage.AWAITING_EMBEDDING_CONSENT) { "任务状态已变更，请刷新后重试。" }
            val binding = repo.embeddingSpaceId(job.knowledgeBaseId)?.let(ApiEmbeddingBinding::parseSpaceId)
                ?: error("API Embedding 绑定不可用，请重新配置。")
            prepareEmbedding(if (retry) EmbeddingAction.RETRY else EmbeddingAction.GRANT, job.knowledgeBaseId, jobId, binding, retry)
        }
    }
    private fun requestEmbeddingRead(block: () -> Pair<PendingEmbedding, EmbeddingConfirmation>) {
        val revision = ++embeddingRevision
        val selection = selectionRevision
        readOnIo(block, { revision == embeddingRevision && selection == selectionRevision }, ::showEmbedding)
    }
    private fun showEmbedding(prepared: Pair<PendingEmbedding, EmbeddingConfirmation>) {
        pendingEmbedding = prepared.first
        embeddingRequest.value = prepared.second
    }
    private fun prepareEmbedding(kind: EmbeddingAction, kbId: String, jobId: String?, binding: ApiEmbeddingBinding, retry: Boolean,
        queryHash: String? = null): Pair<PendingEmbedding, EmbeddingConfirmation> {
            require(app.container.apiEmbeddings.binding(binding.modelProfileId, binding.dimension, kbId) == binding &&
                app.container.apiEmbeddings.resolve(binding.spaceId) != null) { "Embedding 目标已变更或不可用，请重新选择。" }
            val fingerprint = documentsFingerprint(kbId)
            val pending = PendingEmbedding(kind, kbId, jobId, binding, repo.embeddingSpaceId(kbId), fingerprint, retry, queryHash)
            return pending to EmbeddingConfirmation(app.container.apiEmbeddings.label(binding) +
                queryHash?.let { "\nQuery SHA-256: $it" }.orEmpty(), retry,
                kind == EmbeddingAction.REBIND || kind == EmbeddingAction.REBUILD,
                app.container.db.query("SELECT count(*) AS count FROM documents WHERE kb_id=? AND deleted_at IS NULL", listOf(kbId)).single().long("count").toInt(),
                queryRetry = kind == EmbeddingAction.QUERY_RETRY)
    }
    fun dismissEmbedding() { embeddingRevision += 1; embeddingRequest.value = null; pendingEmbedding = null }
    fun confirmEmbedding() {
        val pending = pendingEmbedding ?: return
        dismissEmbedding()
        action {
            require(repo.embeddingSpaceId(pending.knowledgeBaseId) == pending.previousSpace &&
                documentsFingerprint(pending.knowledgeBaseId) == pending.documentsFingerprint &&
                app.container.apiEmbeddings.binding(pending.binding.modelProfileId, pending.binding.dimension, pending.knowledgeBaseId) == pending.binding) {
                "知识库或 Embedding 目标已变更，请重新确认；未发送文本。"
            }
            when (pending.kind) {
                EmbeddingAction.QUERY_RETRY -> {
                    repo.authorizeApiQueryRetry(pending.knowledgeBaseId, pending.binding.spaceId,
                        requireNotNull(pending.queryHash), acknowledgeDuplicateCharge = true)
                    "已允许此查询按相同目标重试一次。请返回聊天页重新提交；未自动发送任何请求。"
                }
                EmbeddingAction.REBIND, EmbeddingAction.REBUILD, EmbeddingAction.GRANT, EmbeddingAction.RETRY -> {
                    val actionName = pending.kind.name
                    val documentsHash = sha256Hex(pending.documentsFingerprint.toByteArray(Charsets.UTF_8))
                    val fingerprint = buildString {
                        appendLine(actionName)
                        if (pending.kind == EmbeddingAction.REBIND) {
                            appendLine(pending.binding.spaceId)
                            append(if (pending.retry) "duplicate" else "fresh")
                        } else {
                            append(pending.binding.fingerprint)
                        }
                        append('\n')
                        append(documentsHash)
                    }
                    val ticket = repo.issueConsentTicket(
                        "API_EMBEDDING",
                        pending.jobId,
                        pending.knowledgeBaseId,
                        fingerprint,
                    )
                    ImportWorkScheduler.enqueueConsent(app, ticket, app.container.profiles.visionConfigured(), pending.jobId)
                    "已记录一次性授权并转入前台任务；不会在此页面协程中发送文本。"
                }
            }
        }
    }
    private fun documentsFingerprint(kbId: String): String = app.container.db.query(
        "SELECT id,blob_hash,active_version_id FROM documents WHERE kb_id=? AND deleted_at IS NULL ORDER BY id", listOf(kbId))
        .joinToString("\n") { "${it.string("id")}:${it.string("blob_hash")}:${it.string("active_version_id")}" }
    private fun isRebindUnknown(error: String?): Boolean = error?.contains("UNKNOWN_OUTCOME") == true && error.contains("API rebind", true)
    fun grantVision(id: String) { requestVision(id, false) }
    fun retryVision(id: String) { requestVision(id, true) }
    private fun requestVision(id: String, retry: Boolean) {
        val revision = ++visionRevision
        val selection = selectionRevision
        readOnIo({
            val batchTarget = repo.jobBatchId(id)?.let(repo::findBatch)?.visionTarget
            if (!batchTarget.isNullOrBlank()) {
                requireNotNull(currentVisionTarget(batchTarget)) { "原 Vision 目标已变更，请在本批次确认卡中重新选择并确认。" }
            } else {
                val targets = currentVisionTargets()
                require(targets.size == 1) { "请在本批次确认卡中选择一个 Vision 目标。" }
                targets.single()
            }
        },
            { revision == visionRevision && selection == selectionRevision }) { target ->
            visionTarget.value = target.label
            consentFingerprint = target.fingerprint
            visionRequest.value = id to retry
        }
    }
    fun dismissVision() { visionRevision += 1; visionRequest.value = null; consentFingerprint = null }
    fun confirmVision() {
        val request = visionRequest.value ?: return
        val fingerprint = consentFingerprint
        dismissVision()
        action {
            require(fingerprint != null && currentVisionTarget(fingerprint)?.fingerprint == fingerprint) { "Vision 目标已变更，请重新确认。" }
            val job = repo.listJobs().firstOrNull { it.first.id == request.first }?.first
                ?: error("导入任务不存在。")
            val documentsHash = sha256Hex(documentsFingerprint(job.knowledgeBaseId).toByteArray(Charsets.UTF_8))
            val ticket = repo.issueConsentTicket(
                "VISION",
                job.id,
                job.knowledgeBaseId,
                (if (request.second) "RETRY\n" else "GRANT\n") + fingerprint.orEmpty() + "\n" + documentsHash,
            )
            ImportWorkScheduler.enqueueConsent(app, ticket, app.container.profiles.visionConfigured(), job.id)
            "已记录一次性授权并转入前台任务；不会在此页面协程中上传图片。"
        }
    }
    /** Pause is durable: the flag is written before the scheduled worker is stopped. */
    fun pauseBatch(batchId: String) = action {
        check(importCoordinator.pauseBatch(batchId)) { "批次不存在或已结束。" }
        "已暂停；不再派发新任务，已完成的成果保留。"
    }

    fun configurePipeline(batchId: String, policy: runtime.mobileagent.knowledge.PipelinePolicy) = action {
        repo.configureBatchPipeline(batchId, policy)
        "处理限制已保存；不会取消已在途请求。"
    }

    fun rebuildBatchLocalChunks(batchId: String) = action {
        val rebuilt = repo.rebuildBatchLocalChunks(batchId)
        "已在本地重建 $rebuilt 份资料的检索片段，未新增 Provider 请求。"
    }

    fun resumeBatch(batchId: String) {
        operation({ importCoordinator.resumeStaging(batchId) }) { started ->
            when (started) {
                is KnowledgeImportStart.Started -> observeImport(started.operation)
                is KnowledgeImportStart.AlreadyRunning -> observeImport(started.operation)
                is KnowledgeImportStart.Rejected -> state.value = state.value.copy(error = started.reason)
                null -> action {
                    repo.resumeBatch(batchId) ?: error("批次不在暂停状态。")
                    ImportWorkScheduler.enqueueBatchFence(app, batchId, app.container.profiles.visionConfigured())
                    "已继续导入。"
                }
            }
        }
    }

    /**
     * "Configure and continue": one action authorizes this whole batch against the Vision target the
     * user just selected, then resumes it in place.  It never widens to files added later, because
     * the authorization is bound to the batch's own member list.
     */
    fun authorizeBatchVision(batchId: String, expectedTarget: String, acknowledgeDuplicateCharge: Boolean) {
        val reviewed = state.value.pendingBatchVision?.takeIf {
            it.batchId == batchId && it.selectedVisionTargetFingerprint == expectedTarget
        }?.reusePreview
        if (reviewed == null) {
            state.value = state.value.copy(error = "处理范围尚未加载，请重新打开批次确认。")
            return
        }
        action {
            requireNotNull(currentVisionTarget(expectedTarget)) { "视觉目标已变更或不可用，请重新选择本批次目标。" }
            repo.reconfigureBatchPipeline(batchId, expectedTarget, reviewed, acknowledgeDuplicateCharge)
            ImportWorkScheduler.enqueueBatchFence(app, batchId, app.container.profiles.visionConfigured())
            "已按确认的复用范围继续本批次。"
        }
    }
    fun keepWaiting() { state.value = state.value.copy(status = "继续保留本地原件，不会自动上传或标记完成。") }
    fun textOnly(id: String) = action {
        val job = repo.acceptTextOnlyVisualGaps(id)
        repo.jobBatchId(id)?.let { batchId ->
            ImportWorkScheduler.enqueueBatchFence(app, batchId, app.container.profiles.visionConfigured())
        }
        job.error ?: "已建立仅文本版本；图片仍在本地，未标为完整导入。"
    }

    /**
     * The creation page already showed the selected batch, the Vision destination, what may leave
     * the device and the cost note.  Passing that confirmed destination here is the one action that
     * authorizes this batch's visual work; passing null means "text only unless something really
     * needs vision", in which case the batch blocks and asks to configure and continue.
     */
    fun importUris(uris: List<Uri>, visionTarget: String?) {
        if (uris.isEmpty()) return
        submitImport(uris, ImportSelection.FILES, visionTarget)
    }

    fun importZip(uri: Uri, visionTarget: String?) =
        submitImport(listOf(uri), ImportSelection.ZIP, visionTarget)

    fun importTree(treeUri: Uri, visionTarget: String? = null) {
        submitImport(listOf(treeUri), ImportSelection.FOLDER, visionTarget)
    }

    private enum class ImportSelection { FILES, ZIP, FOLDER }

    private sealed class PreparedImport {
        data class Ready(
            val files: List<Pair<String, Uri>>,
            val kind: ImportBatchKind,
            val label: String,
        ) : PreparedImport()

        data class Rejected(val reason: String) : PreparedImport()
    }

    /**
     * Resolve one SAF selection and hand it to the process-owned coordinator.
     *
     * Every ContentResolver call (display name, persistable grant, stream openers) runs on
     * [Dispatchers.IO].  The staged selection stays in [KnowledgeUiState.pendingImport] until the
     * coordinator actually accepted the batch, so a busy staging window, a rejected selection or a
     * read failure keeps exactly what the user picked and shows the reason instead of silently
     * importing nothing.  Submissions are serialized so a second confirmation cannot race the first:
     * a repeated confirmation during preparation does not queue another batch.
     *
     * The target knowledge base and the identity of the confirmed selection are frozen here, before
     * any suspension point.  A selection staged later by the user is therefore neither retargeted nor
     * cleared by this submission's outcome.
     */
    private fun submitImport(uris: List<Uri>, selection: ImportSelection, visionTarget: String?) {
        if (state.value.importSubmitting) return
        val selectionId = state.value.pendingImport?.id
        val requestedBase = state.value.selectedBaseId
        // Set synchronously, before launch, to reject a second click in the same UI frame.
        state.value = state.value.copy(importSubmitting = true, error = null)
        importSubmission = viewModelScope.launch {
            try {
                val prepared = ioInterruptible { cancelled -> prepareImport(uris, selection, cancelled) }
                when (prepared) {
                    is PreparedImport.Rejected -> rejectImport(prepared.reason)
                    is PreparedImport.Ready -> startPreparedImport(prepared, visionTarget, requestedBase, selectionId)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // Fail closed: the staged selection survives so the user can retry a readable source.
                fail(failure)
            } finally {
                if (importSubmission === currentCoroutineContext()[Job]) {
                    importSubmission = null
                    state.value = state.value.copy(importSubmitting = false)
                }
            }
        }
    }

    /**
     * Runs [block] on [Dispatchers.IO] with a cancellation probe tied to this coroutine's own job.
     *
     * `withContext(Dispatchers.IO)` alone does not make a blocking provider read observe
     * cancellation, and `Thread.interrupted` is only set by [runInterruptible]; providers that do not
     * react to interruption are still stopped by the job-state probe between rows.
     */
    private suspend fun <T> ioInterruptible(block: (cancelled: () -> Boolean) -> T): T {
        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isActive != true || Thread.currentThread().isInterrupted }
        return runInterruptible(Dispatchers.IO) { block(cancelled) }
    }

    /**
     * Always called on [Dispatchers.IO] with a live cancellation probe.  A folder is traversed with
     * the shared bounded walker, so an oversized or too deep selection is rejected as a whole instead
     * of importing a silent subset.
     */
    private fun prepareImport(
        uris: List<Uri>,
        selection: ImportSelection,
        cancelled: () -> Boolean,
    ): PreparedImport {
        if (selection != ImportSelection.FOLDER && uris.size > KnowledgeFolderWalk.MAX_FILES) {
            return PreparedImport.Rejected("一次最多选择 ${KnowledgeFolderWalk.MAX_FILES} 个文件。")
        }
        if (selection == ImportSelection.FOLDER) {
            val treeUri = uris.first()
            persistReadGrant(treeUri)
            val root = folderRootNode(app.contentResolver, treeUri)
                ?: return PreparedImport.Rejected("无法打开文件夹。")
            val label = root.name ?: "folder"
            return when (val walked = KnowledgeFolderWalk.walk(root, cancelled)) {
                is KnowledgeFolderWalkResult.Files -> PreparedImport.Ready(
                    walked.files.map { entry -> entry.relativePath to Uri.parse(entry.key) },
                    ImportBatchKind.FOLDER,
                    label,
                )
                is KnowledgeFolderWalkResult.Rejected -> PreparedImport.Rejected(walked.reason.message())
                KnowledgeFolderWalkResult.Cancelled -> throw CancellationException("folder selection cancelled")
            }
        }
        val named = uris.map { uri ->
            if (cancelled()) throw CancellationException("selection cancelled")
            persistReadGrant(uri)
            displayName(uri) to uri
        }
        val asZip = selection == ImportSelection.ZIP ||
            (named.size == 1 && named.first().first.endsWith(".zip", ignoreCase = true))
        if (!asZip && named.any { it.first.endsWith(".zip", ignoreCase = true) }) {
            return PreparedImport.Rejected("请将 ZIP 单独导入，其余文件可作为另一批次导入。")
        }
        if (asZip && named.size != 1) return PreparedImport.Rejected("每批请选择一个 ZIP 文件。")
        return PreparedImport.Ready(
            files = named,
            kind = if (asZip) ImportBatchKind.ZIP else ImportBatchKind.FILES,
            label = if (asZip) named.first().first else "files",
        )
    }

    /**
     * Start the batch, and only then release the staged selection — and only when the still-staged
     * selection is the one this submission confirmed.
     */
    private fun startPreparedImport(
        prepared: PreparedImport.Ready,
        visionTarget: String?,
        requestedBase: String?,
        selectionId: String?,
    ) {
        val inputs = prepared.files.map { (name, uri) ->
            KnowledgeImportInput(
                displayName = name,
                sourceKey = uri.toString(),
                openStream = { app.contentResolver.openInputStream(uri) ?: error("无法读取文件。") },
                mediaType = { app.contentResolver.getType(uri).orEmpty() },
            )
        }
        val started = importCoordinator.start(inputs, prepared.kind, prepared.label, requestedBase, visionTarget)
        state.value = applyKnowledgeImportStart(state.value, started, selectionId)
        when (started) {
            is KnowledgeImportStart.Started -> {
                // Accepted into the durable batch: staging ownership moved, so the dialog may close.
                // A selection staged while this submission was preparing belongs to the user and is
                // left untouched.
                observeImport(started.operation)
            }
            is KnowledgeImportStart.AlreadyRunning -> {
                // Only an in-process staging window answers this way, and only against a competing
                // staging operation.  Background batch processing never blocks a new selection, so
                // this never gates on "the knowledge page is busy".  Keep the user's choice.
                observeImport(started.operation)
            }
            is KnowledgeImportStart.Rejected -> Unit
        }
    }

    /** Keep every staged choice; only the reason is added. */
    private fun rejectImport(reason: String) {
        state.value = state.value.copy(error = reason, status = reason)
    }

    /** Best-effort persistable read grant; a provider that refuses it must not fail staging. */
    private fun persistReadGrant(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun KnowledgeFolderWalkReason.message(): String = when (this) {
        KnowledgeFolderWalkReason.EMPTY -> "所选文件夹中没有可导入的文件。"
        KnowledgeFolderWalkReason.FILE_LIMIT ->
            "所选文件夹超过 ${KnowledgeFolderWalk.MAX_FILES} 个文件上限；本次未导入任何文件，请缩小范围后重试。"
        KnowledgeFolderWalkReason.DEPTH_LIMIT ->
            "所选文件夹层级超过 ${KnowledgeFolderWalk.MAX_DEPTH} 层上限；本次未导入任何文件。"
        KnowledgeFolderWalkReason.NODE_LIMIT ->
            "所选文件夹条目过多；本次未导入任何文件，请缩小范围后重试。"
        KnowledgeFolderWalkReason.UNREADABLE ->
            "无法完整读取所选文件夹（子目录不可读或条目不完整）；本次未导入任何文件，请重新选择或检查该文件夹的访问权限。"
    }

    private fun observeImport(operation: KnowledgeImportOperation) {
        if (!observedImportOperations.add(operation.operationId)) return
        activeOperations += 1
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val progressJob = launch {
                operation.progress.collect { progress ->
                    progress.batchId?.let { batchId ->
                        if (state.value.selectedBaseId == null) {
                            val baseId = runInterruptible(Dispatchers.IO) { app.container.db.query("SELECT kb_id FROM import_batches WHERE id=?", listOf(batchId)).singleOrNull()?.string("kb_id") }
                            if (state.value.selectedBaseId == null && baseId != null) {
                                selectionRevision += 1
                                state.value = state.value.copy(selectedBaseId = baseId)
                            }
                        }
                        reload()
                    }
                    if (progress.totalItems > 0) {
                        state.value = state.value.copy(
                            status = "导入进度：已完成 ${progress.published}/${progress.totalItems}，已复制 ${progress.copied}，处理 ${progress.processing}，等待 ${progress.waiting}。",
                        )
                    }
                }
            }
            try {
                val outcome = operation.completion.await()
                when (outcome.terminal) {
                    KnowledgeImportTerminal.COMPLETED -> state.value = state.value.copy(
                        status = if (outcome.progress.state == "BLOCKED") "原件已保存；批次已阻塞，请查看进度卡并确认视觉目标后继续。"
                        else "原件已复制到本地；批次将按已确认的目标继续处理，可离开此页。",
                    )
                    KnowledgeImportTerminal.USER_CANCELLED -> state.value = state.value.copy(
                        status = "已按用户请求取消导入；已复制的本地原件仍保留，可稍后重新导入。",
                    )
                    KnowledgeImportTerminal.SYSTEM_CANCELLED -> state.value = state.value.copy(
                        status = "导入暂时停止；检查点与剩余选择已保留，请点击继续导入。",
                    )
                    KnowledgeImportTerminal.PAUSED -> state.value = state.value.copy(status = "已暂停；已复制资料和未完成的选择均已保存，可继续导入。")
                    KnowledgeImportTerminal.FAILED -> state.value = state.value.copy(error = "导入失败，请查看批次状态后重试。")
                }
                outcome.knowledgeBaseId?.let { id ->
                    if (state.value.selectedBaseId == null) {
                        selectionRevision += 1
                        state.value = state.value.copy(selectedBaseId = id)
                    }
                }
            } catch (_: CancellationException) {
                // Clearing this observer must never cancel the coordinator worker.
            } finally {
                progressJob.cancel()
                observedImportOperations.remove(operation.operationId)
                activeOperations = (activeOperations - 1).coerceAtLeast(0)
                state.value = state.value.copy(loading = activeOperations > 0)
                reload()
            }
        }
    }
    fun openEvidence(id: String) {
        val revision = ++evidenceRevision
        val selection = selectionRevision
        operation({
            val row = app.container.db.query("SELECT d.*,v.status FROM documents d LEFT JOIN document_versions v ON v.id=d.active_version_id WHERE d.id=?", listOf(id)).singleOrNull() ?: error("文档不存在。")
            val chunks = app.container.db.query("SELECT text FROM chunks WHERE document_version_id=? ORDER BY ordinal", listOf(row.string("active_version_id")))
            val count = chunks.size
            val detail = chunks.take(20).joinToString("\n\n") { it.string("text") }.take(20_000)
            KnowledgeEvidenceUi(id, row.string("display_name"), count, row.string("blob_hash"),
                row.string("status") == "READY" && row.string("deleted_at").isBlank(), detail)
        }) { evidence ->
            if (revision == evidenceRevision && selection == selectionRevision) {
                state.value = state.value.copy(evidence = evidence, status = "证据预览已从本地持久化版本读取。")
            }
        }
    }
    fun closeEvidence() { evidenceRevision += 1; state.value = state.value.copy(evidence = null) }
    private fun action(block: () -> String) = operation(block) { status ->
        state.value = state.value.copy(status = status)
    }
    private fun <T> operation(block: () -> T, onSuccess: (T) -> Unit) {
        viewModelScope.launch {
            activeOperations += 1
            state.value = state.value.copy(loading = true, error = null)
            try { onSuccess(runInterruptible(Dispatchers.IO, block)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { fail(failure) }
            finally {
                // A cancelled coroutine cannot normally return to Main. Restore
                // local UI bookkeeping only; do not start provider work here.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    activeOperations = (activeOperations - 1).coerceAtLeast(0)
                    state.value = state.value.copy(loading = activeOperations > 0)
                    reload()
                }
            }
        }
    }
    private fun <T> readOnIo(block: () -> T, current: () -> Boolean, onSuccess: (T) -> Unit) {
        viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO, block)
                if (current()) onSuccess(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) fail(failure) }
        }
    }

    private fun currentVisionTargets(): List<VisionConsentTarget> = visionTargetOptions(
        app.container.profiles.listProviders(),
        app.container.profiles.listModels(),
    ).map { target -> VisionConsentTarget(target.label, target.fingerprint) }

    /** Resolve an exact target fingerprint; an omitted fingerprint is safe only for one target. */
    private fun currentVisionTarget(fingerprint: String? = null): VisionConsentTarget? {
        val targets = currentVisionTargets()
        return if (fingerprint == null) targets.singleOrNull()
        else targets.singleOrNull { it.fingerprint == fingerprint }
    }
    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { return cursor.getString(it) }
        }
        return uri.lastPathSegment ?: "file"
    }
    private fun fail(failure: Exception) {
        val message = if (failure is android.database.SQLException || failure is java.sql.SQLException) {
            if (java.util.Locale.getDefault().language == "zh") "本地资料暂时无法读取，请刷新后重试。" else
                "Local data could not be read. Refresh and try again."
        } else SecretRedactor.redact(failure.message ?: "操作失败。")
        state.value = state.value.copy(error = message)
    }
    private companion object {
        val ACTIVE = setOf(
            "QUEUED", "COPYING", "HASHING", "PARSING", "VISION_PROCESSING", "CHUNKING",
            "SELECT_EMBEDDING_BACKEND", "EMBEDDING", "INDEXING", "RETRY_WAIT",
            "WAITING_FOR_VISION_MODEL", "AWAITING_UPLOAD_CONSENT", "AWAITING_EMBEDDING_CONSENT",
        )
    }
}
