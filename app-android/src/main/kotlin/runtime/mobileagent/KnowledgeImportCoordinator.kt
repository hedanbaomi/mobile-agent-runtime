// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.WeakHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DiagnosticProgressGate
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.KnowledgeArchive
import runtime.mobileagent.knowledge.MediaKind

/** A source owned by the application, not by a screen or a ViewModel. */
data class KnowledgeImportInput(
    val displayName: String,
    val sourceKey: String = displayName,
    val openStream: () -> InputStream,
    val mediaType: () -> String = { "" },
)

data class KnowledgeImportProgress(
    val batchId: String? = null,
    val state: String = "STAGING",
    val totalItems: Int = 0,
    val copied: Int = 0,
    val processing: Int = 0,
    val waiting: Int = 0,
    val failed: Int = 0,
)

enum class KnowledgeImportTerminal {
    COMPLETED,
    FAILED,
    USER_CANCELLED,
    SYSTEM_CANCELLED,
}

enum class KnowledgeImportFailureKind {
    NONE,
    USER_CANCELLED,
    SYSTEM_CANCELLED,
    RESOURCE_LIMIT,
    PERMISSION,
    IO,
    VALIDATION,
    UNKNOWN,
}

data class KnowledgeImportOutcome(
    val operationId: String,
    val batchId: String?,
    val knowledgeBaseId: String?,
    val terminal: KnowledgeImportTerminal,
    val failureKind: KnowledgeImportFailureKind = KnowledgeImportFailureKind.NONE,
    val progress: KnowledgeImportProgress = KnowledgeImportProgress(),
)

/**
 * Closed diagnostic input for import lifecycle events. Implementations must not retain source
 * names, paths, URI strings, bytes, or exception messages. Operation IDs are random references.
 */
sealed interface KnowledgeImportDiagnosticEvent {
    val operationId: String

    data class Started(
        override val operationId: String,
        val kind: String,
        val total: Int,
    ) : KnowledgeImportDiagnosticEvent

    data class Progress(
        override val operationId: String,
        val kind: String,
        val stage: String,
        val completed: Int,
        val total: Int,
    ) : KnowledgeImportDiagnosticEvent

    data class Enqueued(
        override val operationId: String,
        val count: Int,
    ) : KnowledgeImportDiagnosticEvent

    data class Terminal(
        override val operationId: String,
        val terminal: KnowledgeImportTerminal,
        val failureKind: KnowledgeImportFailureKind,
        val stage: String,
        val completed: Int,
    ) : KnowledgeImportDiagnosticEvent
}

fun interface KnowledgeImportDiagnosticSink {
    fun record(event: KnowledgeImportDiagnosticEvent)
}

object NoOpKnowledgeImportDiagnosticSink : KnowledgeImportDiagnosticSink {
    override fun record(event: KnowledgeImportDiagnosticEvent) = Unit
}

/**
 * Ports keep the lifecycle coordinator independent of the repository implementation and make
 * cancellation/duplicate/fence behavior testable with a small in-memory fixture.
 */
interface KnowledgeImportPorts {
    fun visionConfigured(): Boolean
    fun createKnowledgeBase(name: String): String
    fun beginBatch(knowledgeBaseId: String, kind: ImportBatchKind, displayName: String): String
    fun importOne(
        input: KnowledgeImportInput,
        kind: ImportBatchKind,
        knowledgeBaseId: String,
        visionConfigured: Boolean,
    ): ImportJob

    fun bindJobToBatch(batchId: String, job: ImportJob, relativePath: String)
    fun jobBatchId(jobId: String): String?
    fun refreshBatchProgress(batchId: String): KnowledgeImportProgress
    fun readBatchProgress(batchId: String): KnowledgeImportProgress
    fun generationStillCurrent(batchId: String): Boolean
    fun failBatch(batchId: String, reason: String)
    fun enqueueBatch(batchId: String, visionConfigured: Boolean)
    fun enqueueBatchFence(batchId: String, visionConfigured: Boolean)
    fun cancelBatch(batchId: String, jobIds: List<String>)
}

sealed interface KnowledgeImportStart {
    data class Started(val operation: KnowledgeImportOperation) : KnowledgeImportStart
    data class AlreadyRunning(val operation: KnowledgeImportOperation) : KnowledgeImportStart
    data class Rejected(val reason: String) : KnowledgeImportStart
}

/** A handle is safe to retain after its originating ViewModel is cleared. */
class KnowledgeImportOperation internal constructor(
    val operationId: String,
    private val completionDeferred: CompletableDeferred<KnowledgeImportOutcome>,
    val progress: StateFlow<KnowledgeImportProgress>,
    internal val cancelRequested: AtomicBoolean,
    internal val worker: Job,
) {
    val completion: Deferred<KnowledgeImportOutcome> get() = completionDeferred
    fun cancelByUser() = cancelRequested.compareAndSet(false, true).also { requested ->
        if (requested) worker.cancel(CancellationException("user cancellation"))
    }
}

/**
 * Process-owned owner for URI staging and the final durable batch fence. It intentionally has no
 * ViewModel parent. A ViewModel observes [KnowledgeImportOperation] but never owns its worker.
 */
class KnowledgeImportCoordinator(
    private val scope: CoroutineScope,
    private val ports: KnowledgeImportPorts,
    private val diagnostics: KnowledgeImportDiagnosticSink = NoOpKnowledgeImportDiagnosticSink,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Record(
        val operationId: String,
        val completion: CompletableDeferred<KnowledgeImportOutcome>,
        val progress: MutableStateFlow<KnowledgeImportProgress>,
        val cancelRequested: AtomicBoolean,
        var worker: Job? = null,
        var terminal: KnowledgeImportTerminal? = null,
        var outcome: KnowledgeImportOutcome? = null,
        var terminalEventEmitted: Boolean = false,
    )

    private val lock = Any()
    private var active: Record? = null

    /** Import staging is serialized at this boundary; repository workers remain idempotent. */
    fun start(
        files: List<KnowledgeImportInput>,
        kind: ImportBatchKind,
        label: String,
        knowledgeBaseId: String?,
    ): KnowledgeImportStart {
        if (files.isEmpty()) return KnowledgeImportStart.Rejected("没有可导入的文件。")
        if (files.size > MAX_ITEMS) return KnowledgeImportStart.Rejected("一次最多选择 $MAX_ITEMS 个文件。")
        if (label.isBlank()) return KnowledgeImportStart.Rejected("导入名称不能为空。")
        synchronized(lock) {
            val current = active?.takeUnless { it.terminal != null }
            if (current != null) {
                val operation = operationOf(current)
                return KnowledgeImportStart.AlreadyRunning(operation)
            }
            val record = Record(
                operationId = UUID.randomUUID().toString(),
                completion = CompletableDeferred(),
                progress = MutableStateFlow(KnowledgeImportProgress()),
                cancelRequested = AtomicBoolean(false),
            )
            active = record
            val worker = scope.launch {
                run(record, files, kind, label, knowledgeBaseId)
            }
            record.worker = worker
            worker.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    finishIfNeeded(
                        record,
                        KnowledgeImportOutcome(
                            operationId = record.operationId,
                            batchId = record.progress.value.batchId,
                            knowledgeBaseId = knowledgeBaseId,
                            terminal = if (record.cancelRequested.get()) KnowledgeImportTerminal.USER_CANCELLED
                            else KnowledgeImportTerminal.SYSTEM_CANCELLED,
                            failureKind = if (record.cancelRequested.get()) KnowledgeImportFailureKind.USER_CANCELLED
                            else KnowledgeImportFailureKind.SYSTEM_CANCELLED,
                            progress = record.progress.value,
                        ),
                    )
                    emitTerminal(record, "staging", record.progress.value.copied)
                }
            }
            return KnowledgeImportStart.Started(operationOf(record))
        }
    }

    fun cancel(operationId: String): Boolean {
        synchronized(lock) {
            val record = active?.takeIf { it.operationId == operationId && it.terminal == null } ?: return false
            record.cancelRequested.set(true)
            record.worker?.cancel(CancellationException("user cancellation"))
            return true
        }
    }

    fun activeOperation(): KnowledgeImportOperation? = synchronized(lock) {
        active?.takeIf { it.terminal == null }?.let(::operationOf)
    }

    private fun operationOf(record: Record): KnowledgeImportOperation = KnowledgeImportOperation(
        operationId = record.operationId,
        completionDeferred = record.completion,
        progress = record.progress,
        cancelRequested = record.cancelRequested,
        worker = record.worker ?: error("operation worker was not installed"),
    )

    private suspend fun run(
        record: Record,
        files: List<KnowledgeImportInput>,
        kind: ImportBatchKind,
        label: String,
        requestedKnowledgeBaseId: String?,
    ) {
        var batchId: String? = null
        var knowledgeBaseId: String? = requestedKnowledgeBaseId
        val importedJobIds = mutableListOf<String>()
        var copied = 0
        var stage = "staging"
        val progressGate = DiagnosticProgressGate()
        try {
            emit(KnowledgeImportDiagnosticEvent.Started(record.operationId, kind.name, files.size))
            knowledgeBaseId = requestedKnowledgeBaseId ?: io { ports.createKnowledgeBase("我的知识库") }
            if (kind != ImportBatchKind.ZIP) {
                batchId = io { ports.beginBatch(requireNotNull(knowledgeBaseId), kind, label) }
                require(batchId!!.isNotBlank()) { "批次创建失败。" }
                updateProgress(record, batchId!!, io { ports.readBatchProgress(batchId!!) })
            }
            files.forEachIndexed { index, input ->
                ensureActive(record)
                stage = "copying"
                emitProgress(record, kind, "copying", copied, files.size, progressGate)
                val job = io {
                    ports.importOne(input, kind, requireNotNull(knowledgeBaseId), ports.visionConfigured())
                }
                importedJobIds += job.id
                if (batchId == null) batchId = io { ports.jobBatchId(job.id) }
                val resolvedBatchId = batchId
                if (resolvedBatchId != null && kind != ImportBatchKind.ZIP) {
                    io {
                        ports.bindJobToBatch(resolvedBatchId, job, input.displayName)
                        check(ports.generationStillCurrent(resolvedBatchId)) {
                            "知识库代际已变更，批次无法发布。"
                        }
                        ports.enqueueBatch(resolvedBatchId, ports.visionConfigured())
                    }
                    emit(KnowledgeImportDiagnosticEvent.Enqueued(record.operationId, index + 1))
                }
                copied = index + 1
                if (resolvedBatchId != null) {
                    updateProgress(record, resolvedBatchId, io { ports.refreshBatchProgress(resolvedBatchId) })
                }
                emitProgress(record, kind, "copied", copied, files.size, progressGate)
            }
            batchId?.let { durableBatchId ->
                ensureActive(record)
                stage = "processing"
                val progress = io { ports.refreshBatchProgress(durableBatchId) }
                updateProgress(record, durableBatchId, progress)
                io {
                    check(ports.generationStillCurrent(durableBatchId)) {
                        "知识库代际已变更，批次无法发布。"
                    }
                    ports.enqueueBatchFence(durableBatchId, ports.visionConfigured())
                }
                emit(KnowledgeImportDiagnosticEvent.Enqueued(record.operationId, copied))
            }
            finishIfNeeded(
                record,
                KnowledgeImportOutcome(
                    operationId = record.operationId,
                    batchId = batchId,
                    knowledgeBaseId = knowledgeBaseId,
                    terminal = KnowledgeImportTerminal.COMPLETED,
                    progress = record.progress.value,
                ),
            )
        } catch (cancel: CancellationException) {
            val userCancelled = record.cancelRequested.get()
            val terminal = if (userCancelled) KnowledgeImportTerminal.USER_CANCELLED
            else KnowledgeImportTerminal.SYSTEM_CANCELLED
            val kind = if (userCancelled) KnowledgeImportFailureKind.USER_CANCELLED
            else KnowledgeImportFailureKind.SYSTEM_CANCELLED
            if (userCancelled) {
                withContext(NonCancellable) {
                    io {
                        batchId?.let { ports.cancelBatch(it, importedJobIds.toList()) }
                    }
                }
            }
            finishIfNeeded(
                record,
                KnowledgeImportOutcome(record.operationId, batchId, knowledgeBaseId, terminal, kind, record.progress.value),
            )
            throw cancel
        } catch (failure: Throwable) {
            val failureKind = classify(failure)
            batchId?.let { id ->
                try {
                    io { ports.failBatch(id, safeFailureReason(failureKind)) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    // The original failure remains the operation result; failure persistence is
                    // best effort and must not turn cancellation into a successful completion.
                }
            }
            finishIfNeeded(
                record,
                KnowledgeImportOutcome(
                    record.operationId,
                    batchId,
                    knowledgeBaseId,
                    KnowledgeImportTerminal.FAILED,
                    failureKind,
                    record.progress.value,
                ),
            )
        } finally {
            emitTerminal(record, stage, copied)
            synchronized(lock) {
                if (active === record) active = null
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = runInterruptible(ioDispatcher, block)

    private fun ensureActive(record: Record) {
        if (record.cancelRequested.get()) throw CancellationException("user cancellation")
    }

    private fun updateProgress(record: Record, batchId: String, progress: KnowledgeImportProgress) {
        record.progress.value = progress.copy(batchId = batchId)
    }

    private fun emitProgress(
        record: Record,
        kind: ImportBatchKind,
        stage: String,
        completed: Int,
        total: Int,
        gate: DiagnosticProgressGate,
    ) {
        if (gate.shouldRecord(stage, completed, total)) {
            emit(KnowledgeImportDiagnosticEvent.Progress(record.operationId, kind.name, stage, completed, total))
        }
    }

    private fun emit(event: KnowledgeImportDiagnosticEvent) {
        runCatching { diagnostics.record(event) }
    }

    private fun emitTerminal(record: Record, stage: String, copied: Int) {
        val outcome = synchronized(record) {
            if (record.terminalEventEmitted) return
            record.terminalEventEmitted = true
            record.outcome
        } ?: return
        emit(
            KnowledgeImportDiagnosticEvent.Terminal(
                record.operationId,
                outcome.terminal,
                outcome.failureKind,
                stage,
                copied,
            ),
        )
    }

    private fun finishIfNeeded(record: Record, outcome: KnowledgeImportOutcome) {
        synchronized(record) {
            if (record.terminal != null) return
            record.terminal = outcome.terminal
            record.outcome = outcome
            record.progress.value = outcome.progress.copy(batchId = outcome.batchId)
            record.completion.complete(outcome)
        }
    }

    private fun classify(failure: Throwable): KnowledgeImportFailureKind = when {
        failure is CancellationException -> KnowledgeImportFailureKind.SYSTEM_CANCELLED
        failure is SecurityException -> KnowledgeImportFailureKind.PERMISSION
        failure is java.io.IOException -> KnowledgeImportFailureKind.IO
        failure.message?.contains("RESOURCE_LIMIT", true) == true -> KnowledgeImportFailureKind.RESOURCE_LIMIT
        failure is IllegalArgumentException || failure is IllegalStateException -> KnowledgeImportFailureKind.VALIDATION
        else -> KnowledgeImportFailureKind.UNKNOWN
    }

    private fun safeFailureReason(kind: KnowledgeImportFailureKind): String = when (kind) {
        KnowledgeImportFailureKind.RESOURCE_LIMIT -> "Import failed: resource limit"
        KnowledgeImportFailureKind.PERMISSION -> "Import failed: permission denied"
        KnowledgeImportFailureKind.IO -> "Import failed: I/O error"
        KnowledgeImportFailureKind.VALIDATION -> "Import failed: validation error"
        else -> "Import failed"
    }

    companion object {
        const val MAX_ITEMS = 500

        private val defaults = WeakHashMap<MobileAgentApp, KnowledgeImportCoordinator>()

        /** Compatibility fallback for the default ViewModel factory; one instance per process. */
        fun forApplication(application: Application): KnowledgeImportCoordinator {
            val app = application as? MobileAgentApp
                ?: error("KnowledgeImportCoordinator requires MobileAgentApp")
            synchronized(defaults) {
                return defaults.getOrPut(app) {
                    KnowledgeImportCoordinator(
                        scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
                        ports = AndroidKnowledgeImportPorts(app),
                        diagnostics = AndroidKnowledgeImportDiagnosticSink(app.diagnostics),
                    )
                }
            }
        }
    }
}

/** Android adapter; it is deliberately the only place that knows ContentResolver/CAS details. */
class AndroidKnowledgeImportPorts(private val app: MobileAgentApp) : KnowledgeImportPorts {
    private val repo get() = app.container.knowledge

    override fun visionConfigured(): Boolean = app.container.profiles.visionConfigured()
    override fun createKnowledgeBase(name: String): String = repo.createKnowledgeBase(name)
    override fun beginBatch(knowledgeBaseId: String, kind: ImportBatchKind, displayName: String): String =
        repo.beginBatch(knowledgeBaseId, kind, displayName)

    override fun importOne(
        input: KnowledgeImportInput,
        kind: ImportBatchKind,
        knowledgeBaseId: String,
        visionConfigured: Boolean,
    ): ImportJob {
        if (kind == ImportBatchKind.ZIP) {
            val staged = stageArchive(input)
            return try {
                repo.importKnowledgeArchiveFile(
                    displayName = input.displayName,
                    file = staged,
                    visionConfigured = visionConfigured,
                    knowledgeBaseId = knowledgeBaseId,
                    pauseAt = ImportStage.COPYING,
                    embeddingIsApi = apiEmbedding(knowledgeBaseId),
                    embeddingConsent = false,
                )
            } finally {
                if (!staged.delete()) staged.deleteOnExit()
            }
        }
        val bytes = readLimited(input)
        return repo.importBytes(
            input.displayName,
            input.mediaType(),
            bytes,
            visionConfigured,
            knowledgeBaseId,
            pauseAt = ImportStage.COPYING,
            embeddingIsApi = apiEmbedding(knowledgeBaseId),
            embeddingConsent = false,
        )
    }

    override fun bindJobToBatch(batchId: String, job: ImportJob, relativePath: String) =
        repo.bindJobToBatch(batchId, job, relativePath)

    override fun jobBatchId(jobId: String): String? = repo.jobBatchId(jobId)

    override fun refreshBatchProgress(batchId: String): KnowledgeImportProgress {
        repo.refreshBatchProgress(batchId)
        return progress(batchId)
    }

    override fun readBatchProgress(batchId: String): KnowledgeImportProgress = progress(batchId)

    override fun generationStillCurrent(batchId: String): Boolean = repo.generationStillCurrent(batchId)
    override fun failBatch(batchId: String, reason: String) = repo.failBatch(batchId, reason)
    override fun enqueueBatch(batchId: String, visionConfigured: Boolean) {
        ImportWorkScheduler.enqueueBatch(app, batchId, visionConfigured)
    }

    override fun enqueueBatchFence(batchId: String, visionConfigured: Boolean) {
        ImportWorkScheduler.enqueueBatchFence(app, batchId, visionConfigured)
    }

    override fun cancelBatch(batchId: String, jobIds: List<String>) {
        val ids = (repo.queuedJobIds(batchId) + jobIds).distinct()
        ids.forEach { id ->
            runCatching { repo.cancelImport(id) }
            ImportWorkScheduler.cancel(app, id)
        }
        repo.refreshBatchProgress(batchId)
    }

    private fun apiEmbedding(knowledgeBaseId: String): Boolean =
        repo.embeddingSpaceId(knowledgeBaseId)?.let(ApiEmbeddingBinding::parseSpaceId) != null

    private fun progress(batchId: String): KnowledgeImportProgress {
        val row = app.container.db.query(
            "SELECT state,total_items,copied,processing,waiting,failed FROM import_batches WHERE id=?",
            listOf(batchId),
        ).singleOrNull() ?: return KnowledgeImportProgress(batchId = batchId)
        return KnowledgeImportProgress(
            batchId = batchId,
            state = row.string("state"),
            totalItems = row.long("total_items").toInt(),
            copied = row.long("copied").toInt(),
            processing = row.long("processing").toInt(),
            waiting = row.long("waiting").toInt(),
            failed = row.long("failed").toInt(),
        )
    }

    private fun readLimited(input: KnowledgeImportInput): ByteArray = input.openStream().use { stream ->
        val limit = if (input.displayName.lowercase().endsWith(".zip")) {
            KnowledgeArchive.MAX_TOTAL_BYTES
        } else {
            MediaKind.MAX_IMPORT_BYTES
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= limit) { "RESOURCE_LIMIT" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

    private fun stageArchive(input: KnowledgeImportInput): File {
        val directory = File(app.filesDir, "import-staging").apply {
            check(exists() || mkdirs()) { "无法创建导入暂存目录。" }
        }
        val target = File.createTempFile("knowledge-", ".zip", directory)
        try {
            input.openStream().use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= KnowledgeArchive.MAX_TOTAL_BYTES) { "RESOURCE_LIMIT" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(target.length() >= 22L) { "Not a ZIP archive" }
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }
}

class AndroidKnowledgeImportDiagnosticSink(
    private val logger: AndroidDiagnosticLogger,
) : KnowledgeImportDiagnosticSink {
    override fun record(event: KnowledgeImportDiagnosticEvent) {
        when (event) {
            is KnowledgeImportDiagnosticEvent.Started -> logger.recordKnowledgeImportStart(event.kind, event.total)
            is KnowledgeImportDiagnosticEvent.Progress -> logger.recordKnowledgeImportProgress(
                event.kind,
                event.stage,
                event.completed,
                event.total,
            )
            is KnowledgeImportDiagnosticEvent.Enqueued -> logger.recordKnowledgeImportEnqueued("batch", event.count)
            is KnowledgeImportDiagnosticEvent.Terminal -> when (event.terminal) {
                KnowledgeImportTerminal.COMPLETED -> logger.recordKnowledgeImportStaged("batch", event.completed)
                KnowledgeImportTerminal.FAILED -> logger.recordKnowledgeImportFailed(
                    "batch",
                    event.stage,
                    event.completed,
                    IllegalStateException(event.failureKind.name),
                )
                KnowledgeImportTerminal.USER_CANCELLED,
                KnowledgeImportTerminal.SYSTEM_CANCELLED -> logger.recordKnowledgeImportFailed(
                    "batch",
                    event.stage,
                    event.completed,
                    KnowledgeImportCancelledForDiagnostics(),
                )
            }
        }
    }
}

private class KnowledgeImportCancelledForDiagnostics : CancellationException()
