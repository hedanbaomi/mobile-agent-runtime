// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.EmbeddingUnknownOutcomeException
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportItemState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.sha256Hex

class ConsentTicketTest {
    @Test
    fun validApiConsentIsAtomicallyConsumedAndAppliedOnce() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = TicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val awaiting = repo.importBytes(
            "notes.txt",
            "text/plain",
            "ticket-protected text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )

        val ready = repo.applyConsentTicket(ticket, visionConfigured = false)

        assertEquals(ImportStage.READY, ready?.stage)
        assertTrue(api.calls > 0)
        assertEquals(1L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        val callsAfterApply = api.calls
        assertEquals(null, repo.applyConsentTicket(ticket, visionConfigured = false))
        assertEquals(callsAfterApply, api.calls)
    }

    @Test
    fun staleDocumentFingerprintIsRejectedBeforeConsumptionOrProviderDispatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = TicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val awaiting = repo.importBytes(
            "notes.txt",
            "text/plain",
            "stale-ticket text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )
        db.execute("UPDATE documents SET active_version_id = ? WHERE id = ?", listOf("changed-version", awaiting.documentId))

        assertThrows(IllegalStateException::class.java) {
            repo.applyConsentTicket(ticket, visionConfigured = false)
        }
        assertEquals(0L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        assertEquals(0, api.calls)
    }

    @Test
    fun uncertainProviderOutcomeConsumesTicketAndCannotBeAutomaticallyReplayed() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = UnknownTicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val awaiting = repo.importBytes(
            "unknown.txt",
            "text/plain",
            "uncertain-ticket text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )

        val failed = repo.applyConsentTicket(ticket, visionConfigured = false)
        assertEquals(ImportStage.FAILED, failed?.stage)
        assertTrue(failed?.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, api.calls)
        assertEquals(1L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        assertEquals(
            1L,
            db.query("SELECT COUNT(*) AS n FROM embedding_operations WHERE kb_id = ? AND state = 'UNKNOWN'", listOf(kb)).single().long("n"),
        )
        assertEquals(null, repo.applyConsentTicket(ticket, visionConfigured = false))
        assertEquals(1, api.calls)
    }

    @Test
    fun consumedApiConsentTicketReplayMarksInFlightEmbeddingUnknownWithoutApiDispatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = TicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API replay library", binding)
        val awaiting = repo.importBytes(
            "replayed.txt",
            "text/plain",
            "replayed API text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, awaiting.stage)
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )
        db.execute("UPDATE consent_tickets SET consumed = 1 WHERE id = ?", listOf(ticket))
        db.execute(
            "UPDATE import_jobs SET stage = ?, embedding_consent = 1, error = NULL WHERE id = ?",
            listOf(ImportStage.PARSING.name, awaiting.id),
        )
        val now = "2026-01-01T00:00:00Z"
        db.execute(
            "INSERT INTO embedding_operations(token,kind,kb_id,job_id,document_id,document_version_id,space_id,input_manifest_hash,binding_fingerprint,consent_fingerprint,state,cancel_requested,error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                "api-replay-operation",
                "IMPORT",
                kb,
                awaiting.id,
                null,
                null,
                binding.spaceId,
                documentsHash(db, kb),
                binding.fingerprint,
                "replayed-consent",
                "DISPATCHED",
                0,
                "",
                now,
                now,
            ),
        )

        repo.applyConsentTicket(ticket, visionConfigured = false)

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(awaiting.id)).single()
        assertEquals(ImportStage.FAILED.name, persisted.string("stage"))
        assertTrue(persisted.string("error").contains("UNKNOWN_OUTCOME"))
        assertEquals(
            "UNKNOWN",
            db.query("SELECT state FROM embedding_operations WHERE token = ?", listOf("api-replay-operation")).single().string("state"),
        )
        assertEquals(0, api.calls)
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(awaiting.id, visionConfigured = false)
        }
    }

    @Test
    fun consumedApiTicketBeforeOperationCreationReturnsToConsentWaitingWithoutDispatch() {
        assertConsumedApiTicketReturnsToConsentWaiting(null)
    }

    @Test
    fun consumedApiTicketWithPreparedOperationReturnsToConsentWaitingWithoutDispatch() {
        assertConsumedApiTicketReturnsToConsentWaiting("PREPARED")
    }

    @Test
    fun consumedApiTicketWithCachedResultReturnsToConsentWaitingWithoutDispatch() {
        assertConsumedApiTicketReturnsToConsentWaiting("CACHE_READY")
    }

    @Test
    fun consumedApiBatchStartupRestoresConsentBeforeResume() {
        listOf(null, "PREPARED", "CACHE_READY").forEach {
            assertConsumedApiTicketReturnsToConsentWaiting(it, "startup")
        }
    }

    @Test
    fun consumedApiWorkerFailureRestoresConsentBeforeResume() {
        listOf(null, "PREPARED", "CACHE_READY").forEach {
            assertConsumedApiTicketReturnsToConsentWaiting(it, "failure")
        }
    }

    private fun assertConsumedApiTicketReturnsToConsentWaiting(operationState: String?, recovery: String = "ticket") {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = TicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("Interrupted API library", binding)
        val job = repo.importBytes(
            "interrupted.txt", "text/plain", "interrupted text".toByteArray(),
            visionConfigured = false, knowledgeBaseId = kb, embeddingIsApi = true, embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket("API_EMBEDDING", job.id, kb, "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}")
        db.execute("UPDATE consent_tickets SET consumed = 1 WHERE id = ?", listOf(ticket))
        db.execute("UPDATE import_jobs SET stage = 'PARSING', embedding_consent = 1 WHERE id = ?", listOf(job.id))
        if (operationState != null) {
            db.execute(
                "INSERT INTO embedding_operations(token,kind,kb_id,job_id,document_id,document_version_id,space_id,input_manifest_hash,binding_fingerprint,consent_fingerprint,state,cancel_requested,error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf("interrupted-operation", "IMPORT", kb, job.id, null, null, binding.spaceId,
                    documentsHash(db, kb), binding.fingerprint, "interrupted-consent", operationState, 0, "",
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
            )
        }

        when (recovery) {
            "startup" -> {
                val batch = repo.beginBatch(kb, runtime.mobileagent.knowledge.ImportBatchKind.FOLDER, "interrupted batch")
                repo.bindJobToBatch(batch, job, "interrupted.txt")
                db.execute("UPDATE import_batches SET state = 'PROCESSING' WHERE id = ?", listOf(batch))
                db.execute("UPDATE import_items SET state = 'PROCESSING' WHERE batch_id = ?", listOf(batch))
                repo.recoverableBatchIds()
            }
            "failure" -> assertTrue(repo.markConsentWorkerFailure(ticket))
            else -> assertEquals(null, repo.applyConsentTicket(ticket, visionConfigured = false))
        }
        val row = db.query("SELECT stage, embedding_consent FROM import_jobs WHERE id = ?", listOf(job.id)).single()
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT.name, row.string("stage"))
        assertEquals(0L, row.long("embedding_consent"))
        assertEquals(0, api.calls)
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, repo.resumeImport(job.id, visionConfigured = false).stage)
        assertEquals(0, api.calls)
        if (operationState != null) {
            assertEquals(operationState, db.query("SELECT state FROM embedding_operations WHERE token = 'interrupted-operation'").single().string("state"))
        }
    }

    @Test
    fun unconsumedConsentWorkerFailureLeavesWaitingJobVisibleWithoutDispatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var visionCalls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            visionCalls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "diagram"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("waiting.png", "image/png", png, visionConfigured = false)
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, job.stage)
        val ticket = repo.issueConsentTicket("VISION", job.id, job.knowledgeBaseId, "GRANT")

        assertTrue(repo.markConsentWorkerFailure(ticket))

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(job.id)).single()
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL.name, persisted.string("stage"))
        assertEquals("Vision consent worker could not start. Approve again to retry.", persisted.string("error"))
        assertEquals(0, visionCalls)
        assertEquals(0L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
    }

    @Test
    fun consumedVisionWorkerFailureDuringProcessingBecomesUnknownAndRequiresManualRetry() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var visionCalls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            visionCalls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "diagram"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("processing.png", "image/png", png, visionConfigured = false)
        val ticket = repo.issueConsentTicket("VISION", job.id, job.knowledgeBaseId, "GRANT")
        db.execute("UPDATE consent_tickets SET consumed = 1 WHERE id = ?", listOf(ticket))
        db.execute(
            "UPDATE import_jobs SET stage = ?, error = NULL WHERE id = ?",
            listOf(ImportStage.VISION_PROCESSING.name, job.id),
        )

        assertTrue(repo.markConsentWorkerFailure(ticket))

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(job.id)).single()
        assertEquals(ImportStage.FAILED.name, persisted.string("stage"))
        assertEquals(
            "UNKNOWN_OUTCOME: Vision result is uncertain; explicit duplicate-charge acknowledgement is required",
            persisted.string("error"),
        )
        assertEquals(1L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(job.id, visionConfigured = true)
        }
        assertThrows(IllegalStateException::class.java) {
            repo.retryUnknownVision(job.id, acknowledgeDuplicateCharge = false)
        }
        assertEquals(0, visionCalls)
    }

    @Test
    fun existingUnknownOutcomeIsPreservedWhenConsentWorkerFailsAgain() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("already-unknown.png", "image/png", png, visionConfigured = false)
        val error = "UNKNOWN_OUTCOME: existing Vision result is uncertain; manual retry only"
        db.execute(
            "UPDATE import_jobs SET stage = ?, error = ? WHERE id = ?",
            listOf(ImportStage.FAILED.name, error, job.id),
        )
        val ticket = repo.issueConsentTicket("VISION", job.id, job.knowledgeBaseId, "GRANT")

        assertTrue(repo.markConsentWorkerFailure(ticket))

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(job.id)).single()
        assertEquals(ImportStage.FAILED.name, persisted.string("stage"))
        assertEquals(error, persisted.string("error"))
        assertEquals(0L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
    }

    @Test
    fun readyAndCancelledJobsRemainUnchangedWhenConsentWorkerFails() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val ready = repo.importBytes("ready.txt", "text/plain", "ready marker".toByteArray(), visionConfigured = false)
        val readyTicket = repo.issueConsentTicket("VISION", ready.id, ready.knowledgeBaseId, "GRANT")
        assertEquals(ImportStage.READY, ready.stage)
        assertTrue(!repo.markConsentWorkerFailure(readyTicket))
        val persistedReady = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(ready.id)).single()
        assertEquals(ImportStage.READY.name, persistedReady.string("stage"))
        assertEquals("", persistedReady.string("error"))

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val cancelled = repo.importBytes("cancelled.png", "image/png", png, visionConfigured = false)
        assertTrue(repo.cancelImport(cancelled.id))
        val cancelledTicket = repo.issueConsentTicket("VISION", cancelled.id, cancelled.knowledgeBaseId, "GRANT")
        assertEquals(ImportStage.CANCELLED.name, db.query("SELECT stage FROM import_jobs WHERE id = ?", listOf(cancelled.id)).single().string("stage"))
        assertTrue(!repo.markConsentWorkerFailure(cancelledTicket))
        val persistedCancelled = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(cancelled.id)).single()
        assertEquals(ImportStage.CANCELLED.name, persistedCancelled.string("stage"))
        assertEquals("Cancelled by user", persistedCancelled.string("error"))
    }

    @Test
    fun consumedVisionTicketReplayMarksInFlightJobUnknownWithoutVisionDispatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var visionCalls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            visionCalls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "diagram"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("replayed.png", "image/png", png, visionConfigured = false)
        val ticket = repo.issueConsentTicket("VISION", job.id, job.knowledgeBaseId, "GRANT")
        db.execute("UPDATE consent_tickets SET consumed = 1 WHERE id = ?", listOf(ticket))
        db.execute(
            "UPDATE import_jobs SET stage = ?, error = NULL WHERE id = ?",
            listOf(ImportStage.VISION_PROCESSING.name, job.id),
        )

        repo.applyConsentTicket(ticket, visionConfigured = true)

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(job.id)).single()
        assertEquals(ImportStage.FAILED.name, persisted.string("stage"))
        assertTrue(persisted.string("error").contains("UNKNOWN_OUTCOME"))
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(job.id, visionConfigured = true)
        }
        assertEquals(0, visionCalls)
    }

    @Test
    fun consumedVisionProcessingBatchRecoveryFailsUnknownAndIsNotRequeued() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var visionCalls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            visionCalls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "diagram"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "replayed Vision batch")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val copied = repo.importBytes(
            "replayed-batch.png",
            "image/png",
            png,
            visionConfigured = true,
            knowledgeBaseId = kb,
            pauseAt = ImportStage.COPYING,
        )
        repo.bindJobToBatch(batchId, copied, "replayed-batch.png")
        val ticket = repo.issueConsentTicket("VISION", copied.id, kb, "GRANT")
        db.execute("UPDATE consent_tickets SET consumed = 1 WHERE id = ?", listOf(ticket))
        db.execute(
            "UPDATE import_jobs SET stage = ?, error = NULL WHERE id = ?",
            listOf(ImportStage.VISION_PROCESSING.name, copied.id),
        )
        db.execute(
            "UPDATE import_items SET state = ?, error = NULL WHERE batch_id = ?",
            listOf(ImportItemState.PROCESSING.name, batchId),
        )
        db.execute(
            "UPDATE import_batches SET state = ? WHERE id = ?",
            listOf(ImportBatchState.PROCESSING.name, batchId),
        )

        assertTrue(repo.recoverableBatchIds().isEmpty())

        val persistedJob = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(copied.id)).single()
        assertEquals(ImportStage.FAILED.name, persistedJob.string("stage"))
        assertTrue(persistedJob.string("error").contains("UNKNOWN_OUTCOME"))
        assertEquals(
            ImportItemState.FAILED.name,
            db.query("SELECT state FROM import_items WHERE batch_id = ?", listOf(batchId)).single().string("state"),
        )
        assertEquals(ImportBatchState.FAILED, repo.listBatches(kb).single().state)
        assertEquals(0, visionCalls)
    }

    @Test
    fun cancellingVisionProcessingFailsUnknownAndCannotRestartBatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var visionCalls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            visionCalls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "diagram"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "cancelled Vision batch")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val copied = repo.importBytes(
            "cancelled-processing.png",
            "image/png",
            png,
            visionConfigured = true,
            knowledgeBaseId = kb,
            pauseAt = ImportStage.COPYING,
        )
        repo.bindJobToBatch(batchId, copied, "cancelled-processing.png")
        db.execute(
            "UPDATE import_jobs SET stage = ?, error = NULL WHERE id = ?",
            listOf(ImportStage.VISION_PROCESSING.name, copied.id),
        )
        db.execute(
            "UPDATE import_items SET state = ?, error = NULL WHERE batch_id = ?",
            listOf(ImportItemState.PROCESSING.name, batchId),
        )
        db.execute(
            "UPDATE import_batches SET state = ? WHERE id = ?",
            listOf(ImportBatchState.PROCESSING.name, batchId),
        )

        assertTrue(repo.cancelImport(copied.id))

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(copied.id)).single()
        assertEquals(ImportStage.FAILED.name, persisted.string("stage"))
        assertTrue(persisted.string("error").contains("UNKNOWN_OUTCOME"))
        assertEquals(ImportItemState.FAILED.name, db.query("SELECT state FROM import_items WHERE batch_id = ?", listOf(batchId)).single().string("state"))
        assertEquals(ImportBatchState.FAILED, repo.listBatches(kb).single().state)
        assertTrue(repo.recoverableBatchIds().isEmpty())
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(copied.id, visionConfigured = true)
        }
        assertEquals(0, visionCalls)
    }

    @Test
    fun cancellationDoesNotOverwritePersistedUnknownOutcome() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("unknown-before-cancel.png", "image/png", png, visionConfigured = false)
        val error = "UNKNOWN_OUTCOME: Vision result is uncertain; explicit duplicate-charge acknowledgement is required"
        db.execute(
            "UPDATE import_jobs SET stage = ?, error = ? WHERE id = ?",
            listOf(ImportStage.FAILED.name, error, job.id),
        )

        assertTrue(repo.cancelImport(job.id))

        val persisted = db.query("SELECT stage, error FROM import_jobs WHERE id = ?", listOf(job.id)).single()
        assertEquals(ImportStage.FAILED.name, persisted.string("stage"))
        assertEquals(error, persisted.string("error"))
    }

    private fun documentsHash(db: SqlConnection, kb: String): String = sha256Hex(
        db.query(
            "SELECT id,blob_hash,active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id",
            listOf(kb),
        ).joinToString("\n") { "${it.string("id")}:${it.string("blob_hash")}:${it.string("active_version_id")}" }
            .toByteArray(Charsets.UTF_8),
    )

    private fun testBinding(): ApiEmbeddingBinding = ApiEmbeddingBinding(
        providerId = "provider-ticket",
        endpoint = "https://api.example.test/v1/embeddings",
        providerRevision = 1,
        modelId = "ticket-model",
        modelRevision = 1,
        dimension = 8,
        dataScope = "document text; retrieval purpose",
    )
}

private class TicketEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        return FloatArray(dimension) { index -> (text.hashCode() + index).toFloat() }
    }
}

private class UnknownTicketEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        throw EmbeddingUnknownOutcomeException()
    }
}
