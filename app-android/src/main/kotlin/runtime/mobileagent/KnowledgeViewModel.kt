// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import android.net.Uri
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
import androidx.documentfile.provider.DocumentFile
import android.content.Intent
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
    private var activeOperations = 0
    private var hasActiveImportWork = false
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
                            )
                        } else refreshRequested = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (revision == selectionRevision) {
                            state.value = state.value.copy(visionTargetsLoading = false)
                            fail(failure)
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
            val bases = repo.listKnowledgeBases()
            val selected = selectedId?.takeIf { id -> bases.any { it.first == id } } ?: bases.firstOrNull()?.first
            val documents = if (selected == null) emptyList() else app.container.db.query(
                "SELECT d.id,d.display_name,d.format,d.active_version_id,v.status,b.byte_length FROM documents d LEFT JOIN document_versions v ON v.id=d.active_version_id LEFT JOIN blobs b ON b.hash=d.blob_hash WHERE d.kb_id=? AND d.deleted_at IS NULL ORDER BY d.display_name,d.id", listOf(selected))
            val jobs = repo.listJobs().filter { it.first.knowledgeBaseId == selected }
            val visionTargets = visionTargetOptions(
                app.container.profiles.listProviders(),
                app.container.profiles.listModels(),
            )
            val defaultVisionTarget = visionTargets.firstOrNull()
            val target = defaultVisionTarget?.label.orEmpty()
            return KnowledgeUiState(
                bases = bases.map { (id, name) -> KnowledgeBaseUi(id, name,
                    app.container.db.query("SELECT count(*) AS count FROM documents WHERE kb_id=? AND deleted_at IS NULL", listOf(id)).single().long("count").toInt()) },
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
     * Provider settings.  Persistable URI grants are taken before navigation
     * so the selection remains readable when the confirmation dialog returns.
     */
    fun stageImport(uris: List<Uri>, sourceKind: String) {
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val id = sha256Hex(
            (sourceKind + "\n" + uris.joinToString("\n") { it.toString() }).toByteArray(Charsets.UTF_8),
        )
        state.value = state.value.copy(
            pendingImport = KnowledgePendingImportUi(id = id, uris = uris, sourceKind = sourceKind),
            error = null,
            visionTargetsLoading = true,
        )
        reload()
    }

    fun clearPendingImport() {
        state.value = state.value.copy(pendingImport = null)
    }

    fun selectPendingVisionTarget(fingerprint: String?) {
        val pending = state.value.pendingImport ?: return
        state.value = state.value.copy(
            pendingImport = pending.copy(
                selectedVisionTargetFingerprint = fingerprint,
                visionTargetSelectionInitialized = true,
            ),
        )
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
        if (uris.size == 1 && displayName(uris.single()).endsWith(".zip", ignoreCase = true)) {
            importZip(uris.single(), visionTarget)
        } else {
            importNamedUris(uris.map { displayName(it) to it }, ImportBatchKind.FILES, "files", visionTarget)
        }
    }

    fun importZip(uri: Uri, visionTarget: String?) =
        importNamedUris(listOf(displayName(uri) to uri), ImportBatchKind.ZIP, displayName(uri), visionTarget)

    fun importTree(treeUri: Uri, visionTarget: String? = null) {
        viewModelScope.launch {
            try {
                runCatching {
                    app.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val files = runInterruptible(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(app, treeUri) ?: error("无法打开文件夹。")
                    walkTree(root, "")
                }
                require(files.size <= 500) { "一次最多选择 500 个文件。" }
                val label = DocumentFile.fromTreeUri(app, treeUri)?.name ?: "folder"
                importNamedUris(files, ImportBatchKind.FOLDER, label, visionTarget)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { fail(failure) }
        }
    }

    private fun walkTree(dir: DocumentFile, prefix: String): List<Pair<String, Uri>> {
        val out = ArrayList<Pair<String, Uri>>()
        dir.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) out += walkTree(child, "$prefix$name/")
            else out += ("$prefix$name" to child.uri)
        }
        return out
    }

    private fun importNamedUris(files: List<Pair<String, Uri>>, kind: ImportBatchKind, label: String, visionTarget: String? = null) {
        if (files.isEmpty()) return
        if (files.size > 500) { state.value = state.value.copy(error = "一次最多选择 500 个文件。"); return }
        if (kind != ImportBatchKind.ZIP && files.any { it.first.endsWith(".zip", ignoreCase = true) }) {
            state.value = state.value.copy(error = "请将 ZIP 单独导入，其余文件可作为另一批次导入。"); return
        }
        val requestedBase = state.value.selectedBaseId
        val inputs = files.map { (name, uri) ->
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            KnowledgeImportInput(
                displayName = name,
                sourceKey = uri.toString(),
                openStream = { app.contentResolver.openInputStream(uri) ?: error("无法读取文件。") },
                mediaType = { app.contentResolver.getType(uri).orEmpty() },
            )
        }
        when (val started = importCoordinator.start(inputs, kind, label, requestedBase, visionTarget)) {
            is KnowledgeImportStart.Started -> observeImport(started.operation)
            is KnowledgeImportStart.AlreadyRunning -> observeImport(started.operation)
            is KnowledgeImportStart.Rejected -> state.value = state.value.copy(error = started.reason)
        }
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
    private fun fail(failure: Exception) { state.value = state.value.copy(error = SecretRedactor.redact(failure.message ?: "操作失败。")) }
    private companion object {
        val ACTIVE = setOf(
            "QUEUED", "COPYING", "HASHING", "PARSING", "VISION_PROCESSING", "CHUNKING",
            "SELECT_EMBEDDING_BACKEND", "EMBEDDING", "INDEXING", "RETRY_WAIT",
            "WAITING_FOR_VISION_MODEL", "AWAITING_UPLOAD_CONSENT", "AWAITING_EMBEDDING_CONSENT",
        )
    }
}
