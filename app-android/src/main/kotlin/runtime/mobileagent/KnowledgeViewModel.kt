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
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.feature.knowledge.*
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.KnowledgeArchive
import runtime.mobileagent.knowledge.MediaKind
import androidx.documentfile.provider.DocumentFile
import android.content.Intent
import runtime.mobileagent.provider.SecretRedactor
import java.io.ByteArrayOutputStream

data class EmbeddingConfirmation(val target: String, val retry: Boolean, val rebind: Boolean, val documentCount: Int,
    val queryRetry: Boolean = false)

class KnowledgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val repo get() = app.container.knowledge
    val state = mutableStateOf(KnowledgeUiState())
    val visionRequest = mutableStateOf<Pair<String, Boolean>?>(null)
    val visionTarget = mutableStateOf("")
    val embeddingRequest = mutableStateOf<EmbeddingConfirmation?>(null)
    private var consentFingerprint: String? = null
    private var pendingEmbedding: PendingEmbedding? = null
    private var selectionRevision = 0L
    private var embeddingRevision = 0L
    private var visionRevision = 0L
    private var evidenceRevision = 0L
    private var refreshJob: Job? = null
    private var refreshRequested = false
    private var activeOperations = 0

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
            while (isActive) {
                delay(2000)
                if (refreshJob?.isActive != true && state.value.jobs.any { it.stage in ACTIVE }) reload()
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
                            )
                        } else refreshRequested = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (revision == selectionRevision) fail(failure) }
                }
            } finally { refreshJob = null }
        }
    }

    /** IO only. No Compose state is read or written while repository locks may wait. */
    private fun loadSnapshot(selectedId: String?): KnowledgeUiState {
            val bases = repo.listKnowledgeBases()
            val selected = selectedId?.takeIf { id -> bases.any { it.first == id } } ?: bases.firstOrNull()?.first
            val documents = if (selected == null) emptyList() else app.container.db.query(
                "SELECT d.id,d.display_name,d.format,d.active_version_id,v.status,b.byte_length FROM documents d LEFT JOIN document_versions v ON v.id=d.active_version_id LEFT JOIN blobs b ON b.hash=d.blob_hash WHERE d.kb_id=? AND d.deleted_at IS NULL ORDER BY d.display_name,d.id", listOf(selected))
            val jobs = repo.listJobs().filter { it.first.knowledgeBaseId == selected }
            val target = currentVisionTarget()
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
                    .map { (job, name, _) -> KnowledgeWaitingUi(job.id, name, job.error ?: "图片留在本地；需要明确同意上传后才会继续。", target) },
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
                batches = selected?.let(repo::listBatches).orEmpty().map { batch ->
                    KnowledgeBatchUi(
                        id = batch.id,
                        displayName = batch.displayName,
                        kind = batch.kind.name,
                        state = batch.state.name,
                        totalItems = batch.totalItems,
                        copied = batch.copied,
                        processing = batch.processing,
                        waiting = batch.waiting,
                        failed = batch.failed,
                        error = batch.error,
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
                    val fingerprint = buildString {
                        appendLine(actionName)
                        if (pending.kind == EmbeddingAction.REBIND) {
                            appendLine(pending.binding.spaceId)
                            append(if (pending.retry) "duplicate" else "fresh")
                        } else {
                            append(pending.binding.fingerprint)
                        }
                    }
                    val ticket = repo.issueConsentTicket(
                        "API_EMBEDDING",
                        pending.jobId,
                        pending.knowledgeBaseId,
                        fingerprint,
                    )
                    ImportWorkScheduler.enqueueConsent(app, ticket, app.container.profiles.visionConfigured())
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
        readOnIo({ currentVisionTarget().also { require(it.isNotBlank()) { "请先配置 Vision 模型。" } } },
            { revision == visionRevision && selection == selectionRevision }) { target ->
            visionTarget.value = target
            consentFingerprint = target
            visionRequest.value = id to retry
        }
    }
    fun dismissVision() { visionRevision += 1; visionRequest.value = null; consentFingerprint = null }
    fun confirmVision() {
        val request = visionRequest.value ?: return
        val fingerprint = consentFingerprint
        dismissVision()
        action {
            require(currentVisionTarget() == fingerprint) { "Vision 目标已变更，请重新确认。" }
            val job = repo.listJobs().firstOrNull { it.first.id == request.first }?.first
                ?: error("导入任务不存在。")
            val ticket = repo.issueConsentTicket(
                "VISION",
                job.id,
                job.knowledgeBaseId,
                (if (request.second) "RETRY\n" else "GRANT\n") + fingerprint.orEmpty(),
            )
            ImportWorkScheduler.enqueueConsent(app, ticket, app.container.profiles.visionConfigured())
            "已记录一次性授权并转入前台任务；不会在此页面协程中上传图片。"
        }
    }
    fun keepWaiting() { state.value = state.value.copy(status = "继续保留本地原件，不会自动上传或标记完成。") }
    fun textOnly(id: String) = action {
        val job = repo.acceptTextOnlyVisualGaps(id)
        job.error ?: "已建立仅文本版本；图片仍在本地，未标为完整导入。"
    }

    fun importUris(uris: List<Uri>) = importNamedUris(uris.map { displayName(it) to it }, ImportBatchKind.FILES, "files")

    fun importZip(uri: Uri) = importNamedUris(listOf(displayName(uri) to uri), ImportBatchKind.ZIP, displayName(uri))

    fun importTree(treeUri: Uri) {
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
                importNamedUris(files, ImportBatchKind.FOLDER, label)
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

    private fun importNamedUris(files: List<Pair<String, Uri>>, kind: ImportBatchKind, label: String) {
        if (files.isEmpty()) return
        if (files.size > 500) { state.value = state.value.copy(error = "一次最多选择 500 个文件。"); return }
        viewModelScope.launch {
            activeOperations += 1
            state.value = state.value.copy(loading = true, error = null)
            try {
                val requestedBase = state.value.selectedBaseId
                val id = requestedBase ?: runInterruptible(Dispatchers.IO) { repo.createKnowledgeBase("我的知识库") }.also {
                    selectionRevision += 1
                    state.value = state.value.copy(selectedBaseId = it)
                }
                val createdBatchId = if (kind == ImportBatchKind.ZIP) {
                    null
                } else {
                    runInterruptible(Dispatchers.IO) { repo.beginBatch(id, kind, label) }
                }
                var resolvedBatchId = createdBatchId
                files.forEachIndexed { index, (name, uri) ->
                    state.value = state.value.copy(status = "正在复制 ${index + 1}/${files.size}：$name")
                    runInterruptible(Dispatchers.IO) {
                        val bytes = readLimited(uri, name)
                        val imported = repo.importBytes(name, app.contentResolver.getType(uri).orEmpty(), bytes,
                            app.container.profiles.visionConfigured(), id, pauseAt = ImportStage.COPYING,
                            embeddingIsApi = repo.embeddingSpaceId(id)?.let(ApiEmbeddingBinding::parseSpaceId) != null,
                            embeddingConsent = false)
                        if (createdBatchId != null) {
                            repo.bindJobToBatch(createdBatchId, imported, name)
                        } else if (resolvedBatchId == null) {
                            resolvedBatchId = repo.jobBatchId(imported.id)
                        }
                    }
                }
                runInterruptible(Dispatchers.IO) {
                    val batchId = resolvedBatchId
                    if (batchId != null) {
                        repo.refreshBatchProgress(batchId)
                        if (!repo.generationStillCurrent(batchId)) {
                            repo.failBatch(batchId, "Knowledge base generation changed; this batch cannot publish.")
                        } else {
                            ImportWorkScheduler.enqueueBatch(app, batchId, app.container.profiles.visionConfigured())
                        }
                    }
                    repo.listJobs().filter { it.first.knowledgeBaseId == id && it.first.stage.name in ACTIVE }.forEach { (job, _, _) ->
                        ImportWorkScheduler.enqueue(app, job.id, app.container.profiles.visionConfigured())
                    }
                }
                val snapshot = runInterruptible(Dispatchers.IO) { loadSnapshot(id) }
                state.value = state.value.copy(
                    bases = snapshot.bases, selectedBaseId = snapshot.selectedBaseId,
                    documents = snapshot.documents, jobs = snapshot.jobs, waiting = snapshot.waiting,
                    embeddingSpaceLabel = snapshot.embeddingSpaceLabel,
                    embeddingModels = snapshot.embeddingModels, apiQueryAttempts = snapshot.apiQueryAttempts,
                    batches = snapshot.batches,
                    status = "原件已复制到本地；批次任务将继续处理，可离开此页。图片及 API Embedding 文本不会未经同意上传。",
                    rebuildEnabled = snapshot.selectedBaseId != null && snapshot.jobs.none { it.stage in ACTIVE },
                )
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { fail(failure) }
            finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    activeOperations = (activeOperations - 1).coerceAtLeast(0)
                    state.value = state.value.copy(loading = activeOperations > 0)
                    reload()
                }
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
    private fun currentVisionTarget(): String = app.container.profiles.visionBinding()?.let { (provider, model) ->
        "${provider.name} · ${provider.baseUrl} · ${model.modelId} · provider rev ${provider.revision} / model rev ${model.revision}"
    }.orEmpty()
    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { return cursor.getString(it) }
        }
        return uri.lastPathSegment ?: "file"
    }
    private fun readLimited(uri: Uri, name: String = displayName(uri)): ByteArray = (app.contentResolver.openInputStream(uri) ?: error("无法读取文件。")).use { input ->
        val limit = if (name.lowercase().endsWith(".zip")) KnowledgeArchive.MAX_TOTAL_BYTES else MediaKind.MAX_IMPORT_BYTES
        val out = ByteArrayOutputStream(); val buffer = ByteArray(16384)
        while (true) { val count = input.read(buffer); if (count < 0) break
            require(out.size().toLong() + count <= limit) { "RESOURCE_LIMIT" }; out.write(buffer, 0, count) }
        out.toByteArray()
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
