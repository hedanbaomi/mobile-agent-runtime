// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchBlockReason
import runtime.mobileagent.knowledge.ImportBatchEvent
import runtime.mobileagent.knowledge.ImportBatchEventPhase
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportItemState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.PdfParser
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess
import runtime.mobileagent.knowledge.sha256Hex

/**
 * P1 regression for the 294-file report: creating one batch and confirming one destination must be
 * enough to upload every visual member exactly once and publish real, searchable knowledge - with
 * no per-file consent ticket, no copied-count-as-complete progress, and no automatic replay of an
 * uncertain provider call.
 *
 * The Vision destination here is a real local HTTP service, so the upload assertions come from
 * request counts observed by the service and from persisted rows - not from a Composable state.
 */
class KnowledgeBatchVisionTest {
    @Test
    fun unresolvedMarkdownImagesNeedSourcesAndExplicitTextOnlyResumesWithoutOverridingPause() {
        listOf(false, true).forEach { pauseBeforeTextOnly ->
            val db = JdbcSqlConnection()
            Migrations.apply(db)
            val calls = AtomicInteger()
            val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = VisionBackend {
                calls.incrementAndGet(); VisionOutcome.Success(VisionSuccess("ocr", "vision"))
            }, visionModelFingerprint = "vision-test")
            val batch = stagedBatch(repo, "markdown", listOf(
                Triple("notes.md", "text/markdown", "Local note\n![diagram](https://example.invalid/private.png)".toByteArray()),
                Triple("next.txt", "text/plain", "next local note".toByteArray()),
            ))
            repo.authorizeBatchVision(batch, "vision-test")
            repo.processBatch(batch, true)
            assertEquals(ImportBatchBlockReason.MISSING_VISUAL_SOURCE, repo.findBatch(batch)!!.blockedReason)
            assertEquals(ImportBatchState.BLOCKED, repo.findBatch(batch)!!.state)
            assertNull(repo.resumeBatch(batch), "only an explicitly paused batch may resume")
            if (pauseBeforeTextOnly) repo.pauseBatch(batch)
            val markdownJob = repo.listBatchItemViews(batch).first().jobId!!
            assertEquals(ImportStage.READY_WITH_VISUAL_GAPS, repo.acceptTextOnlyVisualGaps(markdownJob).stage)
            if (pauseBeforeTextOnly) {
                assertEquals(ImportBatchState.PAUSED, repo.findBatch(batch)!!.state)
                repo.resumeBatch(batch)
            }
            repo.processBatch(batch, true)
            assertEquals(2, repo.batchProgress(batch).published)
            assertEquals(ImportBatchState.COMPLETED, repo.findBatch(batch)!!.state)
            assertEquals(0, calls.get(), "unresolved Markdown references are never fetched or uploaded")
        }
    }

    @Test
    fun validLegacyOneShotTicketAuthorizesOnlyItsOwnBatchMember() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val calls = AtomicInteger()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = VisionBackend {
            calls.incrementAndGet(); VisionOutcome.Success(VisionSuccess("legacy ocr", "legacy result"))
        }, visionModelFingerprint = "vision-test")
        val batch = stagedBatch(repo, "legacy", listOf(
            Triple("one.pdf", "application/pdf", visionPdf("one")),
            Triple("two.pdf", "application/pdf", visionPdf("two")),
        ))
        val job = repo.listBatchItemViews(batch).first().jobId!!
        repo.resumeImport(job, visionConfigured = true)
        val kb = repo.ensureDefaultBase()
        val fingerprint = sha256Hex(db.query(
            "SELECT id,blob_hash,active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id", listOf(kb),
        ).joinToString("\n") { "${it.string("id")}:${it.string("blob_hash")}:${it.string("active_version_id")}" }.toByteArray())
        val ticket = repo.issueConsentTicket("VISION", job, kb, "GRANT\nvision-test\n$fingerprint")
        assertEquals(ImportStage.READY, repo.applyConsentTicket(ticket, true)!!.stage)
        assertNull(repo.batchVisionAuthorization(batch))
        assertNull(repo.applyConsentTicket(ticket, true))
        repo.processBatch(batch, false)
        assertEquals(1, calls.get())
        assertEquals(1, repo.batchProgress(batch).published)
    }

    @Test
    fun pauseCanPersistWhileBackendIsStillInFlight() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = VisionBackend {
            attempts.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            VisionOutcome.Success(VisionSuccess("ocr", "in flight result"))
        }, visionModelFingerprint = "vision-test")
        val batch = stagedBatch(repo, "concurrent pause", listOf(
            Triple("one.pdf", "application/pdf", visionPdf("one")),
            Triple("two.pdf", "application/pdf", visionPdf("two")),
        ))
        repo.authorizeBatchVision(batch, "vision-test")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit { repo.processBatch(batch, false) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val paused = executor.submit<runtime.mobileagent.knowledge.ImportBatch?> { repo.pauseBatch(batch) }
            try {
                assertEquals(ImportBatchState.PAUSED, paused.get(2, TimeUnit.SECONDS)!!.state)
            } finally {
                release.countDown()
            }
            worker.get(10, TimeUnit.SECONDS)
            assertEquals(1, attempts.get(), "no next member is sent after the persisted pause")
            assertEquals("SUCCESS", db.query("SELECT status FROM vision_results").single().string("status"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun dispatchIsDurablyUnknownBeforeBackendAndRestartCannotReplayIt() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val attempts = AtomicInteger()
        var guardedAtDispatch = false
        val backend = VisionBackend {
            attempts.incrementAndGet()
            guardedAtDispatch = db.query("SELECT status FROM vision_results").singleOrNull()?.string("status") == "UNKNOWN_OUTCOME"
            VisionOutcome.UnknownOutcome
        }
        val repo = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        val batch = stagedBatch(repo, "crash", listOf(Triple("one.pdf", "application/pdf", visionPdf("crash"))))
        repo.authorizeBatchVision(batch, "vision-test")
        repo.processBatch(batch, false)
        assertTrue(guardedAtDispatch, "the durable marker must exist before the external call")
        // Reconstruct precisely the state a process death during the backend leaves behind.
        db.execute("UPDATE import_jobs SET stage = 'VISION_PROCESSING', error = NULL WHERE batch_id = ?", listOf(batch))
        db.execute("UPDATE import_items SET state = 'PROCESSING', error = NULL WHERE batch_id = ?", listOf(batch))
        db.execute("UPDATE import_batches SET state = 'PROCESSING', error = NULL WHERE id = ?", listOf(batch))
        val restarted = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        restarted.recoverableBatchIds().forEach { restarted.processBatch(it, false) }
        assertEquals(1, attempts.get())
        assertEquals(1, restarted.batchProgress(batch).unknown)
    }

    @Test
    fun completedResponseCheckpointIsReusedAfterInterruptedPublication() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val attempts = AtomicInteger()
        val backend = VisionBackend { attempts.incrementAndGet(); VisionOutcome.Success(VisionSuccess("cached ocr", "cached visual")) }
        val repo = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        val batch = stagedBatch(repo, "cache", listOf(Triple("one.pdf", "application/pdf", visionPdf("cache"))))
        repo.authorizeBatchVision(batch, "vision-test")
        repo.processBatch(batch, false)
        db.execute("UPDATE import_jobs SET stage = 'VISION_PROCESSING', error = NULL WHERE batch_id = ?", listOf(batch))
        db.execute("UPDATE import_items SET state = 'PROCESSING', error = NULL WHERE batch_id = ?", listOf(batch))
        db.execute("UPDATE import_batches SET state = 'PROCESSING', error = NULL WHERE id = ?", listOf(batch))
        val restarted = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        restarted.recoverableBatchIds().forEach { restarted.processBatch(it, false) }
        assertEquals(1, attempts.get(), "completed response cache must survive a worker restart")
        assertEquals(1, restarted.batchProgress(batch).published)
    }

    @Test
    fun pauseDuringBackendKeepsResponseAndAuthorizationDoesNotResumeIt() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val attempts = AtomicInteger()
        lateinit var repo: KnowledgeRepository
        lateinit var batch: String
        val backend = VisionBackend {
            attempts.incrementAndGet()
            repo.pauseBatch(batch)
            VisionOutcome.Success(VisionSuccess("ocr", "paused visual"))
        }
        repo = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        batch = stagedBatch(repo, "pause", listOf(
            Triple("one.pdf", "application/pdf", visionPdf("one")),
            Triple("two.pdf", "application/pdf", visionPdf("two")),
        ))
        repo.authorizeBatchVision(batch, "vision-test")
        repo.processBatch(batch, false)
        assertEquals(1, attempts.get())
        assertEquals("SUCCESS", db.query("SELECT status FROM vision_results").single().string("status"))
        repo.authorizeBatchVision(batch, "vision-test")
        assertEquals(ImportBatchState.PAUSED, repo.findBatch(batch)!!.state)
        val restarted = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        assertFalse(batch in restarted.recoverableBatchIds())
        restarted.processBatch(batch, false)
        assertEquals(1, attempts.get())
    }

    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun stopServers() {
        servers.forEach { server -> server.stop(0) }
        servers.clear()
    }

    /** A local upload service that counts every request body it actually receives. */
    private fun mockUploadService(): Pair<String, AtomicInteger> {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/vision") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            requests.incrementAndGet()
        }
        server.executor = null
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/vision" to requests
    }

    private fun uploadingVision(url: String): VisionBackend = VisionBackend { input ->
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Content-Type", input.mediaType.ifBlank { "application/octet-stream" })
            connection.outputStream.use { it.write(input.bytes) }
            val code = connection.responseCode
            connection.inputStream.use { it.readBytes() }
            if (code !in 200..299) {
                VisionOutcome.Failed("VISION_HTTP_$code")
            } else {
                VisionOutcome.Success(VisionSuccess("ocr-${input.assetHash.take(6)}", "vision-marker-${input.assetHash.take(6)}"))
            }
        } catch (failure: Exception) {
            VisionOutcome.UnknownOutcome
        } finally {
            connection?.disconnect()
        }
    }

    private fun visionPdf(marker: String): ByteArray = PdfParser.writePdfWithImageXObject(marker)

    /** Stage one durable batch whose members are all already copied into the local CAS. */
    private fun stagedBatch(
        repo: KnowledgeRepository,
        label: String,
        entries: List<Triple<String, String, ByteArray>>,
    ): String {
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, label)
        entries.forEach { (name, mime, bytes) ->
            val job = repo.importBytes(
                displayName = name,
                mediaType = mime,
                bytes = bytes,
                visionConfigured = false,
                knowledgeBaseId = kb,
                pauseAt = ImportStage.COPYING,
            )
            repo.bindJobToBatch(batchId, job, name)
        }
        return batchId
    }

    private fun count(db: JdbcSqlConnection, table: String): Int =
        db.query("SELECT COUNT(*) AS n FROM $table").single().long("n").toInt()

    @Test
    fun oneBatchAuthorizationUploadsEveryVisualMemberOnceAndPublishesSearchableKnowledge() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, uploads) = mockUploadService()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = uploadingVision(url), visionModelFingerprint = "vision-test")
        val batchId = stagedBatch(
            repo,
            "mixed batch",
            listOf(
                Triple("notes.txt", "text/plain", "alpha local marker".toByteArray()),
                Triple("figure-a.pdf", "application/pdf", visionPdf("figure-a")),
                Triple("figure-b.pdf", "application/pdf", visionPdf("figure-b")),
            ),
        )

        repo.authorizeBatchVision(batchId, "vision-test")
        repo.processBatch(batchId, visionConfigured = false)

        val batch = repo.findBatch(batchId)!!
        assertEquals(ImportBatchState.COMPLETED, batch.state, batch.error)
        val progress = repo.batchProgress(batchId)
        assertEquals(3, progress.published, "completion must come from published items")
        assertEquals(0, progress.pending)
        assertEquals(0, progress.unknown)
        assertEquals(2, uploads.get(), "only the two visual members may be uploaded")
        assertEquals(0, count(db, "consent_tickets"), "one batch authorization must not create per-file tickets")
        assertEquals(2, count(db, "vision_results"), "each uploaded image keeps a persisted result row")
        assertTrue(repo.search("alpha").any { "alpha" in it.text }, "text-only material stays local and searchable")
        assertTrue(repo.search("vision-marker").size >= 2, "vision results are indexed and searchable")

        // A second worker run must not re-upload anything.
        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(2, uploads.get())
    }

    @Test
    fun batchWithoutAConfirmedTargetBlocksAtTheFirstVisualMemberAndContinuesInPlace() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, uploads) = mockUploadService()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = uploadingVision(url), visionModelFingerprint = "vision-test")
        val batchId = stagedBatch(
            repo,
            "blocks on vision",
            listOf(
                Triple("first.txt", "text/plain", "first local marker".toByteArray()),
                Triple("figure.pdf", "application/pdf", visionPdf("figure")),
                Triple("last.txt", "text/plain", "last local marker".toByteArray()),
            ),
        )

        repo.processBatch(batchId, visionConfigured = false)

        val blocked = repo.findBatch(batchId)!!
        assertEquals(ImportBatchState.BLOCKED, blocked.state)
        assertEquals(ImportBatchBlockReason.NEEDS_VISION_MODEL, blocked.blockedReason)
        assertEquals(0, uploads.get(), "no image may leave the device without a confirmed target")
        val items = repo.listBatchItemViews(batchId)
        assertEquals(ImportItemState.PUBLISHED.name, items[0].state, "the checkpoint keeps finished work")
        assertEquals(ImportItemState.WAITING.name, items[1].state)
        assertEquals(ImportItemState.COPYING.name, items[2].state, "no later member is dispatched while blocked")

        // "Configure and continue" is one action for the whole batch: authorize, then keep going.
        repo.authorizeBatchVision(batchId, "vision-test")
        repo.processBatch(batchId, visionConfigured = false)

        val done = repo.findBatch(batchId)!!
        assertEquals(ImportBatchState.COMPLETED, done.state, done.error)
        assertEquals(3, repo.batchProgress(batchId).published)
        assertEquals(1, uploads.get())
    }

    @Test
    fun requestedDestinationOtherThanTheConfiguredOneAuthorizesNothing() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, uploads) = mockUploadService()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = uploadingVision(url), visionModelFingerprint = "vision-test")
        val batchId = stagedBatch(repo, "destination change", listOf(Triple("figure.pdf", "application/pdf", visionPdf("figure"))))

        assertThrows(IllegalStateException::class.java) { repo.authorizeBatchVision(batchId, "provider-b|model-b") }
        assertNull(repo.batchVisionAuthorization(batchId))
        repo.processBatch(batchId, visionConfigured = true)
        assertEquals(ImportBatchState.BLOCKED, repo.findBatch(batchId)!!.state)
        assertEquals(0, uploads.get())
    }

    @Test
    fun addingAMemberInvalidatesTheAuthorizationInsteadOfWideningIt() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, _) = mockUploadService()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = uploadingVision(url), visionModelFingerprint = "vision-test")
        val batchId = stagedBatch(
            repo,
            "scope",
            listOf(
                Triple("a.pdf", "application/pdf", visionPdf("a")),
                Triple("b.pdf", "application/pdf", visionPdf("b")),
            ),
        )
        repo.authorizeBatchVision(batchId, "vision-test")
        assertNotNull(repo.batchVisionAuthorization(batchId))

        val kb = repo.ensureDefaultBase()
        val extra = repo.importBytes("extra.txt", "text/plain", "extra".toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
        repo.bindJobToBatch(batchId, extra, "extra.txt")

        assertNull(repo.batchVisionAuthorization(batchId), "the scope is bound to the members the user actually confirmed")
    }

    @Test
    fun pauseStopsDispatchAndResumeContinuesWithoutDuplicatingUploads() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, uploads) = mockUploadService()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = uploadingVision(url), visionModelFingerprint = "vision-test")
        val batchId = stagedBatch(
            repo,
            "pause and resume",
            listOf(
                Triple("one.pdf", "application/pdf", visionPdf("one")),
                Triple("two.pdf", "application/pdf", visionPdf("two")),
            ),
        )
        repo.authorizeBatchVision(batchId, "vision-test")
        assertNotNull(repo.pauseBatch(batchId))

        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(ImportBatchState.PAUSED, repo.findBatch(batchId)!!.state)
        assertEquals(0, uploads.get(), "a paused batch dispatches nothing")
        assertTrue(repo.recoverableBatchIds().none { it == batchId }, "a user pause must survive process restart")

        assertNotNull(repo.resumeBatch(batchId))
        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(2, repo.batchProgress(batchId).published)
        assertEquals(2, uploads.get())

        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(2, uploads.get(), "a repeated worker delivery must not dispatch again")
        assertEquals(2, repo.batchProgress(batchId).published)
    }

    @Test
    fun uncertainVisionOutcomeIsPersistedAndNeverAutoRetried() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val attempts = AtomicInteger()
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            vision = VisionBackend { attempts.incrementAndGet(); VisionOutcome.UnknownOutcome },
            visionModelFingerprint = "vision-test",
        )
        val batchId = stagedBatch(repo, "unknown outcome", listOf(Triple("figure.pdf", "application/pdf", visionPdf("figure"))))
        repo.authorizeBatchVision(batchId, "vision-test")
        repo.processBatch(batchId, visionConfigured = false)

        assertEquals(ImportBatchState.FAILED, repo.findBatch(batchId)!!.state)
        val item = repo.listBatchItemViews(batchId).single()
        assertEquals(ImportItemState.FAILED.name, item.state)
        assertTrue(item.error!!.contains("UNKNOWN_OUTCOME"), item.error!!)
        assertEquals(1, repo.batchProgress(batchId).unknown)
        assertEquals(1, attempts.get())

        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(1, attempts.get(), "an uncertain provider call is never replayed automatically")
        assertThrows(IllegalStateException::class.java) {
            repo.retryUnknownVision(item.jobId!!, acknowledgeDuplicateCharge = false)
        }
        assertThrows(IllegalStateException::class.java) {
            repo.retryUnknownVision(item.jobId!!, acknowledgeDuplicateCharge = true, expectedVisionFingerprint = "changed-target")
        }
        assertEquals("UNKNOWN_OUTCOME", db.query("SELECT status FROM vision_results").single().string("status"),
            "a rejected retry must not erase the durable no-replay marker")
    }

    @Test
    fun alreadyPublishedVisualPageIsReusedInsteadOfReuploaded() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, uploads) = mockUploadService()
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = uploadingVision(url), visionModelFingerprint = "vision-test")
        val bytes = visionPdf("reused")

        val first = stagedBatch(repo, "first", listOf(Triple("figure.pdf", "application/pdf", bytes)))
        repo.authorizeBatchVision(first, "vision-test")
        repo.processBatch(first, visionConfigured = false)
        assertEquals(1, uploads.get())
        assertEquals(1, repo.batchProgress(first).published)

        val second = stagedBatch(repo, "second", listOf(Triple("figure.pdf", "application/pdf", bytes)))
        // The unchanged blob already has a published, indexed version, so the member is published
        // on reuse and the batch never needs a Vision authorization at all.
        assertEquals(ImportBatchState.COMPLETED, repo.findBatch(second)!!.state)
        repo.processBatch(second, visionConfigured = false)

        assertEquals(ImportBatchState.COMPLETED, repo.findBatch(second)!!.state)
        assertEquals(1, repo.batchProgress(second).published)
        assertEquals(0, count(db, "vision_results") - 1, "no extra vision row is written for a reused version")
        assertEquals(1, uploads.get(), "an already published page must be reused, never uploaded twice")
    }

    /**
     * Documents the confirmed defect behind the reported "approved but nothing happened" click: the
     * legacy per-file Vision ticket is bound to the WHOLE knowledge base document fingerprint
     * (including every document's active_version_id).  Ordinary publication inside the same import
     * changes that fingerprint, so a ticket issued while the batch is still progressing is already
     * stale when the consent worker consumes it - the approval is silently discarded and no image
     * request is ever made.  Batch imports no longer use this gate (see the tests above).
     */
    @Test
    fun legacyPerFileVisionTicketIsInvalidatedByOrdinaryKnowledgeBaseProgress() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val uploads = AtomicInteger()
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            vision = VisionBackend { uploads.incrementAndGet(); VisionOutcome.Success(VisionSuccess("ocr", "desc")) },
            visionModelFingerprint = "vision-test",
        )
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "legacy ticket race")
        val figure = repo.importBytes(
            "figure.pdf",
            "application/pdf",
            visionPdf("figure"),
            visionConfigured = true,
            knowledgeBaseId = kb,
            pauseAt = ImportStage.COPYING,
        )
        repo.bindJobToBatch(batchId, figure, "figure.pdf")
        assertEquals(ImportStage.AWAITING_UPLOAD_CONSENT, repo.resumeImport(figure.id, visionConfigured = true).stage)

        // The user approves: the ticket captures the current whole-KB document fingerprint.
        val fingerprint = sha256Hex(
            db.query(
                "SELECT id,blob_hash,active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id",
                listOf(kb),
            ).joinToString("\n") { "${it.string("id")}:${it.string("blob_hash")}:${it.string("active_version_id")}" }
                .toByteArray(Charsets.UTF_8),
        )
        val ticket = repo.issueConsentTicket("VISION", figure.id, kb, "GRANT\nvision-test\n$fingerprint")

        // Meanwhile the batch keeps doing ordinary work: another member parses and publishes.
        repo.importBytes("other.txt", "text/plain", "other local marker".toByteArray(), false, kb)

        assertThrows(IllegalStateException::class.java) { repo.applyConsentTicket(ticket, visionConfigured = true) }
        assertEquals(0, uploads.get(), "a stale approval must not upload anything")
    }
    @Test
    fun batchLifecycleEventsCarryOnlyOpaqueRefsAndClosedCodes() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val (url, uploads) = mockUploadService()
        val events = mutableListOf<ImportBatchEvent>()
        val backendStarts = AtomicInteger()
        val backend = uploadingVision(url)
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            vision = VisionBackend { input ->
                assertEquals(backendStarts.incrementAndGet(), events.count { it.phase == ImportBatchEventPhase.DISPATCHED },
                    "the sink must receive DISPATCHED before the backend receives image bytes")
                assertEquals("UNKNOWN_OUTCOME", db.query("SELECT status FROM vision_results WHERE cache_key = ?", listOf(input.cacheKey))
                    .single().string("status"), "the dispatch checkpoint must already be durable")
                backend.process(input)
            },
            visionModelFingerprint = "vision-test",
            importEvents = { event -> events += event },
        )
        val batchId = stagedBatch(
            repo,
            "secret/customer-statement.pdf",
            listOf(
                Triple("secret/customer-statement.pdf", "application/pdf", visionPdf("figure-one")),
                Triple("secret/customer-statement-two.pdf", "application/pdf", visionPdf("figure-two")),
            ),
        )
        repo.authorizeBatchVision(batchId, "vision-test")
        repo.processBatch(batchId, visionConfigured = false)

        assertTrue(events.any { it.phase == ImportBatchEventPhase.AUTHORIZATION_SAVED })
        assertTrue(events.any { it.phase == ImportBatchEventPhase.STARTED })
        assertTrue(events.any { it.phase == ImportBatchEventPhase.CHECKPOINT })
        assertEquals(2, uploads.get())
        assertEquals(2, events.count { it.phase == ImportBatchEventPhase.DISPATCHED })
        assertEquals(2, events.count { it.phase == ImportBatchEventPhase.RESPONDED && it.reasonCode == "vision_success" })
        assertEquals(2, db.query("SELECT status FROM vision_results").count { it.string("status") == "SUCCESS" },
            "both successful responses must be checkpointed for recovery")

        // A worker interrupted after successful responses reuses the checkpoints. This exercises
        // the cache path, rather than merely no-oping an already completed batch.
        db.execute("UPDATE import_jobs SET stage = 'VISION_PROCESSING', error = NULL WHERE batch_id = ?", listOf(batchId))
        db.execute("UPDATE import_items SET state = 'PROCESSING', error = NULL WHERE batch_id = ?", listOf(batchId))
        db.execute("UPDATE import_batches SET state = 'PROCESSING', error = NULL WHERE id = ?", listOf(batchId))
        repo.recoverableBatchIds().forEach { repo.processBatch(it, false) }
        assertEquals(2, repo.batchProgress(batchId).published)
        assertEquals(2, uploads.get(), "cache recovery must not upload again")
        assertEquals(2, backendStarts.get())
        assertEquals(2, events.count { it.phase == ImportBatchEventPhase.DISPATCHED }, "cache hits are not dispatches")
        assertEquals(2, events.count { it.phase == ImportBatchEventPhase.RESPONDED }, "cache hits are not new backend responses")

        val rendered = events.joinToString("|") { it.toString() }
        assertFalse(rendered.contains("customer-statement"), "no real file name may reach diagnostics")
        assertFalse(rendered.contains("/"), "no path or URI may reach diagnostics")
        assertTrue(events.all { it.reasonCode.length <= 64 && it.reasonCode.isNotBlank() })
        assertTrue(events.all { it.batchRef == batchId })
    }
    @Test
    fun progressNeverReportsCopiedBytesAsCompletion() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val batchId = stagedBatch(
            repo,
            "progress honesty",
            listOf(
                Triple("a.txt", "text/plain", "a".toByteArray()),
                Triple("b.txt", "text/plain", "b".toByteArray()),
            ),
        )
        val staged = repo.findBatch(batchId)!!
        val progress = repo.batchProgress(batchId)
        assertEquals(2, progress.copied)
        assertEquals(0, progress.published)
        assertEquals(0, staged.published)
        assertEquals(0, progress.percentComplete())
    }
}
