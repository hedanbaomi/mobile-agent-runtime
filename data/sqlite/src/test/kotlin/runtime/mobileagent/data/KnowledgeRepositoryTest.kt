// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.ApiQueryUnknownOutcomeException
import runtime.mobileagent.knowledge.CitationMap
import runtime.mobileagent.knowledge.EmbeddingUnknownOutcomeException
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.ImportStateMachine
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KnowledgeRepositoryTest {
    @Test
    fun textImportIndexesAndIsSearchable() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("notes.txt", "text/plain", "Alpha widget torque spec is 12Nm.".toByteArray(), visionConfigured = false)
        assertEquals(ImportStage.READY, job.stage)
        val hits = repo.search("widget")
        assertTrue(hits.any { "12Nm" in it.text })
    }

    @Test
    fun imageWithoutVisionWaitsAndIsNotReady() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("diagram.png", "image/png", png, visionConfigured = false)
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, job.stage)
        assertFalse(runtime.mobileagent.knowledge.ImportStateMachine.isCompleteSuccess(job))
        assertEquals(1, repo.waitingForVisionCount())
    }

    @Test
    fun pdfIsNotSilentlyTreatedAsReadyText() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("scan.pdf", "application/pdf", "%PDF-1.4 leftover".toByteArray(), visionConfigured = false)
        assertEquals(ImportStage.FAILED, job.stage)
        assertTrue(job.error.orEmpty().contains("PDF"))
    }

    @Test
    fun markdownImageSyntaxWaitsAndIsNotReady() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val body = "# Recipe\n\nSee the diagram:\n\n![oven](photo.png)\n".toByteArray()
        val job = repo.importBytes("recipe.md", "text/markdown", body, visionConfigured = false)
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, job.stage)
        assertTrue(job.hasImages)
        assertFalse(runtime.mobileagent.knowledge.ImportStateMachine.isCompleteSuccess(job))
        assertTrue(repo.search("diagram").isEmpty())
    }

    @Test
    fun sharedBlobSurvivesDeletingOneKnowledgeBase() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val sink = MemoryBlobSink()
        val repo = KnowledgeRepository(db, sink)
        val payload = "shared CAS payload for two libraries".toByteArray()
        val a = repo.createKnowledgeBase("Library A")
        val b = repo.createKnowledgeBase("Library B")
        val first = repo.importBytes("shared.txt", "text/plain", payload, false, a)
        val second = repo.importBytes("shared.txt", "text/plain", payload, false, b)
        assertEquals(ImportStage.READY, first.stage)
        assertEquals(ImportStage.READY, second.stage)
        val hash = sink.blobs.keys.single()
        assertEquals(2, repo.blobRefCount(hash))
        repo.deleteKnowledgeBase(a)
        assertEquals(1, repo.blobRefCount(hash))
        assertTrue(repo.search("payload", knowledgeBaseIds = listOf(b)).any { "shared CAS" in it.text })
        assertTrue(repo.search("payload", knowledgeBaseIds = listOf(a)).isEmpty())
    }

    @Test
    fun sameFileSameLibraryIsIdempotent() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val sink = MemoryBlobSink()
        val repo = KnowledgeRepository(db, sink)
        val payload = "idempotent blob".toByteArray()
        val kb = repo.ensureDefaultBase()
        repo.importBytes("a.txt", "text/plain", payload, false, kb)
        repo.importBytes("a.txt", "text/plain", payload, false, kb)
        assertEquals(1, repo.blobRefCount(sink.blobs.keys.single()))
    }

    @Test
    fun chineseProperNameAndEnglishTermAreFindable() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        repo.importBytes(
            "lab.txt",
            "text/plain",
            "量子纠缠实验记录：张伟在上海同步辐射光源核对 USearch JNI 配置。".toByteArray(),
            false,
        )
        assertTrue(repo.search("张伟").any { "纠缠" in it.text })
        assertTrue(repo.search("USearch").any { "JNI" in it.text })
    }

    @Test
    fun lexicalRankingUsesBm25BeforeLimitForSharedCjkTerms() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            vectorIndexFactory = runtime.mobileagent.knowledge.VectorIndexFactory { spaceId, dimension, _ ->
                EmptyVectorIndex(spaceId, dimension)
            },
        )
        val kb = repo.createKnowledgeBase("Chinese library")
        repeat(20) { index ->
            repo.importBytes(
                "noise-$index.txt",
                "text/plain",
                "仓库日常记录与通用流程 filler-$index".toByteArray(),
                visionConfigured = false,
                knowledgeBaseId = kb,
            )
        }
        repo.importBytes(
            "target.txt",
            "text/plain",
            "松岳仓库专名目标证据 target-marker".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
        )

        val hits = repo.search("松岳仓库", topK = 8, knowledgeBaseIds = listOf(kb))
        assertTrue(hits.any { "target-marker" in it.text })
    }

    @Test
    fun zipSlipIsRejectedAndNotExtracted() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("evil.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zipSlip(), false)
        assertEquals(ImportStage.FAILED, job.stage)
        assertTrue(job.error.orEmpty().contains("path"))
    }

    @Test
    fun deletedDocumentIsNotReturnedAndRebuildKeepsLiveGeneration() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val keep = repo.importBytes("keep.txt", "text/plain", "keep live evidence".toByteArray(), false)
        val gone = repo.importBytes("gone.txt", "text/plain", "must disappear after delete".toByteArray(), false)
        repo.deleteDocument(gone.documentId)
        repo.rebuildIndex(keep.knowledgeBaseId)
        val live = repo.search("live")
        assertTrue(live.any { "keep" in it.text })
        assertTrue(repo.search("disappear").none { it.documentId == gone.documentId })
        assertTrue(live.none { it.documentId == gone.documentId })
    }

    @Test
    fun unknownCitationIdDoesNotResolve() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        repo.importBytes("cite.txt", "text/plain", "citation source paragraph".toByteArray(), false)
        val result = repo.retrieve("run-1", "citation")
        assertTrue(result.hits.isNotEmpty())
        assertEquals(result.hits.size, result.citations.size)
        assertNull(CitationMap.resolve(result.citations, "missing-id"))
        assertEquals("0", result.citations.first().citationId)
    }

    @Test
    fun copyCheckpointCanResumeWithoutDuplicatingBlob() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val sink = MemoryBlobSink()
        val repo = KnowledgeRepository(db, sink)
        val payload = "resume after copy checkpoint".toByteArray()
        val paused = repo.importBytes("resume.txt", "text/plain", payload, false, pauseAt = ImportStage.COPYING)
        assertEquals(ImportStage.COPYING, paused.stage)
        assertEquals(1, repo.blobRefCount(sink.blobs.keys.single()))
        val resumed = repo.resumeImport(paused.id, payload, false)
        assertEquals(ImportStage.READY, resumed.stage)
        assertEquals(1, repo.blobRefCount(sink.blobs.keys.single()))
        assertTrue(repo.search("checkpoint").any { "resume" in it.text })
    }

    @Test
    fun mixedSpacesAreNotScoredTogether() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.createKnowledgeBase("Foreign space")
        db.execute("UPDATE knowledge_bases SET embedding_space_id = ? WHERE id = ?", listOf("onnx-pack-other", kb))
        val result = repo.retrieve("run-x", "anything", knowledgeBaseIds = listOf(kb))
        assertTrue(result.hits.isEmpty())
        assertTrue(result.warnings.any { it.contains("space") })
    }

    @Test
    fun codeAndTableTokensAreFindable() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        repo.importBytes(
            "spec.md",
            "text/markdown",
            """
            | name | value |
            | widget | 12Nm |
            ```kotlin
            fun torqueSpec() = 12
            ```
            """.trimIndent().toByteArray(),
            false,
        )
        assertTrue(repo.search("torqueSpec").any { "12" in it.text })
        assertTrue(repo.search("widget").any { "12Nm" in it.text })
    }

    @Test
    fun incompleteEpubFailsWithEvidence() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("book.epub", "application/epub+zip", validZip("mimetype"), false)
        assertEquals(ImportStage.FAILED, job.stage)
        assertTrue(job.error.orEmpty().contains("EPUB") || job.error.orEmpty().contains("HTML"))
    }

    @Test
    fun rebuildKeepsPreviousReadyGeneration() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        repo.importBytes("keep.txt", "text/plain", "generation pin evidence".toByteArray(), false, kb)
        val first = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kb)).single().string("active_generation_id")
        val second = repo.rebuildIndex(kb)
        assertNotEquals(first, second)
        assertEquals("READY", db.query("SELECT state FROM index_generations WHERE id = ?", listOf(first)).single().string("state"))
        assertEquals(second, db.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kb)).single().string("active_generation_id"))
        assertTrue(repo.search("pin").any { "generation" in it.text })
    }

    @Test
    fun buildingGenerationIsNotUsedUntilActiveSwitch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        repo.importBytes("live.txt", "text/plain", "published live chunk".toByteArray(), false, kb)
        val active = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kb)).single().string("active_generation_id")
        db.execute(
            "INSERT INTO index_generations(id,kb_id,space_id,manifest_hash,state,vector_count,fts_version,created_at) VALUES (?,?,?,?,?,?,?,?)",
            listOf("gen-building", kb, "local-hash-v1-d32", "pending", "BUILDING", 0, 1, "2026-08-28T00:00:00Z"),
        )
        assertEquals(active, db.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kb)).single().string("active_generation_id"))
        assertTrue(repo.search("published").any { "live" in it.text })
    }

    @Test
    fun emptyQueryReturnsNoEvidenceWarning() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val result = repo.retrieve("run-empty", "   ")
        assertTrue(result.hits.isEmpty())
        assertTrue(result.warnings.any { it.contains("empty") })
        assertTrue(result.citations.isEmpty())
    }

    @Test
    fun v3ReadyDocumentsAreSearchableAfterUpgrade() {
        val db = JdbcSqlConnection()
        applyLegacyV3(db)
        db.execute(
            "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES (?,?,?,?,?,?)",
            listOf("kb-old", "Legacy", null, null, "2026-08-28T00:00:00Z", null),
        )
        db.execute(
            "INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES (?,?,?,?,?)",
            listOf("hash-old", 12, "text/plain", "memory:hash-old", 1),
        )
        db.execute(
            "INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES (?,?,?,?,?,?,?)",
            listOf("doc-old", "kb-old", "hash-old", "notes.txt", "TEXT", "ver-old", null),
        )
        db.execute(
            "INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash) VALUES (?,?,?,?,?)",
            listOf("chunk-old", "ver-old", 0, "Alpha widget torque spec is 12Nm.", "c-old"),
        )
        val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id = ?", listOf("chunk-old")).single().long("rid")
        db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, "Alpha widget torque spec is 12Nm."))
        db.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(3))
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        assertTrue(repo.search("widget", knowledgeBaseIds = listOf("kb-old")).any { "12Nm" in it.text })
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM document_versions").single().long("n"))
        assertTrue(db.query("SELECT COUNT(*) AS n FROM index_generations WHERE state = ?", listOf("READY")).single().long("n") >= 1)
    }

    @Test
    fun failedEmbeddingDoesNotPublishReadyAndRetryRepairs() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val sink = MemoryBlobSink()
        val failing = KnowledgeRepository(db, sink, ThrowingEmbedder())
        val first = failing.importBytes("notes.txt", "text/plain", "retryable widget text".toByteArray(), false)
        assertEquals(ImportStage.FAILED, first.stage)
        assertTrue(failing.search("widget").isEmpty())
        val ok = KnowledgeRepository(db, sink)
        val second = ok.importBytes("notes.txt", "text/plain", "retryable widget text".toByteArray(), false)
        assertEquals(ImportStage.READY, second.stage)
        assertTrue(ok.search("widget").any { "retryable" in it.text })
    }

    @Test
    fun resumeRejectsMismatchedBytesAndDeletedDocuments() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val sink = MemoryBlobSink()
        val repo = KnowledgeRepository(db, sink)
        val payload = "resume original source".toByteArray()
        val paused = repo.importBytes("resume.txt", "text/plain", payload, false, pauseAt = ImportStage.COPYING)
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(paused.id, "replacement source".toByteArray(), false)
        }
        assertEquals(ImportStage.COPYING, repo.listJobs().first().first.stage)
        repo.deleteDocument(paused.documentId)
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(paused.id, payload, false)
        }
        assertTrue(repo.search("original").none { it.documentId == paused.documentId })
    }

    @Test
    fun blobRefCountFollowsLiveDocumentsNotRetries() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val sink = MemoryBlobSink()
        val repo = KnowledgeRepository(db, sink)
        val shared = "shared once".toByteArray()
        val a = repo.createKnowledgeBase("A")
        val b = repo.createKnowledgeBase("B")
        repo.importBytes("s.txt", "text/plain", shared, false, a)
        repo.importBytes("s.txt", "text/plain", shared, false, b)
        val hash = sink.blobs.keys.single()
        assertEquals(2, repo.blobRefCount(hash))
        repo.deleteKnowledgeBase(a)
        repo.deleteKnowledgeBase(a)
        assertEquals(1, repo.blobRefCount(hash))
        val pdf = repo.importBytes("x.pdf", "application/pdf", "%PDF-1.4 leftover".toByteArray(), false)
        repeat(2) {
            repo.importBytes("x.pdf", "application/pdf", "%PDF-1.4 leftover".toByteArray(), false)
        }
        val pdfHash = db.query("SELECT blob_hash FROM documents WHERE id = ?", listOf(pdf.documentId)).single().string("blob_hash")
        assertEquals(1, repo.blobRefCount(pdfHash))
        repo.deleteDocument(pdf.documentId)
        repo.deleteDocument(pdf.documentId)
        assertEquals(0, repo.blobRefCount(pdfHash))
    }

    @Test
    fun laterLibraryExactHitIsKeptRegardlessOfBaseOrder() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val a = repo.createKnowledgeBase("Noise")
        val b = repo.createKnowledgeBase("Signal")
        repeat(8) { i ->
            repo.importBytes("n$i.txt", "text/plain", "generic filler paragraph $i about weather".toByteArray(), false, a)
        }
        repo.importBytes("hit.txt", "text/plain", "uniqueTokenOnlyInB".toByteArray(), false, b)
        val forward = repo.search("uniqueTokenOnlyInB", topK = 8, knowledgeBaseIds = listOf(a, b))
        val reverse = repo.search("uniqueTokenOnlyInB", topK = 8, knowledgeBaseIds = listOf(b, a))
        assertTrue(forward.any { "uniqueTokenOnlyInB" in it.text })
        assertTrue(reverse.any { "uniqueTokenOnlyInB" in it.text })
    }

    @Test
    fun retrievePinsGenerationForTheWholeRun() {
        val inner = JdbcSqlConnection()
        Migrations.apply(inner)
        val repo = KnowledgeRepository(inner, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        repo.importBytes("one.txt", "text/plain", "first generation pin token".toByteArray(), false, kb)
        val first = inner.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kb)).single().string("active_generation_id")
        val db = GenerationSwitchingConnection(inner, first)
        val pinned = KnowledgeRepository(db, MemoryBlobSink())
        val hits = pinned.search("pin", knowledgeBaseIds = listOf(kb))
        assertTrue(hits.any { "first generation" in it.text })
        assertEquals(1, db.activeReads)
    }

    @Test
    fun rebuildRestoresWipedDerivedIndexes() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        repo.importBytes("keep.txt", "text/plain", "rebuildable lexical evidence".toByteArray(), false, kb)
        db.execute("DELETE FROM chunks_fts")
        db.execute("DELETE FROM embeddings")
        repo.rebuildIndex(kb)
        assertTrue(repo.search("lexical").any { "rebuildable" in it.text })
    }

    @Test
    fun textPdfIsSearchableWithoutVision() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("spec.pdf", "application/pdf", runtime.mobileagent.knowledge.PdfParser.writeSimpleTextPdf("Alpha widget torque spec is 12Nm."), false)
        assertEquals(ImportStage.READY, job.stage)
        val hits = repo.search("widget")
        assertTrue(hits.any { "12Nm" in it.text })
        assertTrue(hits.any { it.page == 1 })
        val bound = CitationMap.bind("run", hits)
        val locator = repo.locateCitation(bound.first())
        assertEquals(1, locator.page)
        assertFalse(locator.removed)
    }

    @Test
    fun imagePdfWaitsWithoutVisionAndProcessesWithConsent() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val pdf = runtime.mobileagent.knowledge.PdfParser.writePdfWithImageXObject("vector flowchart")
        val waiting = KnowledgeRepository(db, MemoryBlobSink()).importBytes("flow.pdf", "application/pdf", pdf, visionConfigured = false)
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, waiting.stage)
        assertFalse(runtime.mobileagent.knowledge.ImportStateMachine.isCompleteSuccess(waiting))
        val seen = mutableListOf<String>()
        val vision = runtime.mobileagent.knowledge.VisionBackend { input ->
            seen += input.cacheKey
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr-flow", "flowchart of torque"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val awaiting = repo.importBytes("flow.pdf", "application/pdf", pdf, visionConfigured = true, visionConsent = false)
        assertEquals(ImportStage.AWAITING_UPLOAD_CONSENT, awaiting.stage)
        assertTrue(seen.isEmpty())
        val ready = repo.grantVisionConsent(awaiting.id)
        assertEquals(ImportStage.READY, ready.stage, ready.error)
        assertEquals(1, seen.size)
        assertTrue(repo.search("flowchart").any { it.assetId != null })
        val again = repo.grantVisionConsent(awaiting.id)
        assertEquals(ImportStage.READY, again.stage)
        assertEquals(1, seen.size)
    }

    @Test
    fun visionUnknownOutcomeDoesNotMarkReadyOrRetry() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        var calls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            calls += 1
            runtime.mobileagent.knowledge.VisionOutcome.UnknownOutcome
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision)
        val job = repo.importBytes("scan.png", "image/png", png, visionConfigured = true, visionConsent = true)
        assertEquals(ImportStage.FAILED, job.stage)
        assertTrue(job.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, calls)
        val retry = repo.importBytes("scan.png", "image/png", png, visionConfigured = true, visionConsent = true)
        assertEquals(ImportStage.FAILED, retry.stage)
        assertEquals(1, calls)
    }

    @Test
    fun apiEmbeddingWithoutConsentDoesNotIndex() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes(
            "notes.txt",
            "text/plain",
            "secret-on-device-only".toByteArray(),
            visionConfigured = false,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, job.stage)
        assertTrue(repo.search("secret-on-device-only").isEmpty())
    }

    @Test
    fun selectedApiEmbeddingUsesItsOwnSpaceAfterConsent() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val api = CountingEmbedder("api-space-v1", 8)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createKnowledgeBase("API library", embeddingSpaceId = api.spaceId)
        val job = repo.importBytes(
            "api.txt",
            "text/plain",
            "selected API evidence".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )
        assertEquals(ImportStage.READY, job.stage)
        assertTrue(api.calls > 0)
        assertTrue(repo.search("selected", knowledgeBaseIds = listOf(kb)).any { "API evidence" in it.text })
        assertEquals(
            api.spaceId,
            db.query("SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kb)).single().string("embedding_space_id"),
        )
    }

    @Test
    fun apiEmbeddingConsentIsIndependentAndMakesZeroCallsBeforeApproval() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)

        val job = repo.importBytes(
            "notes.txt",
            "text/plain",
            "private text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )

        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, job.stage)
        assertEquals(0, api.calls)
    }

    @Test
    fun changingApiHostOrModelWithoutFreshConsentDoesNotCallEitherAdapter() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val originalBinding = testApiBinding()
        val changedBinding = testApiBinding(endpoint = "https://api.example.test/v2/embeddings", modelId = "other-model", modelRevision = 2)
        val original = CountingEmbedder(originalBinding.spaceId, originalBinding.dimension)
        val changed = CountingEmbedder(changedBinding.spaceId, changedBinding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = original, apiEmbedders = listOf(changed))
        val kb = repo.createApiKnowledgeBase("API library", originalBinding)

        assertThrows(IllegalStateException::class.java) {
            repo.rebindApiKnowledgeBase(kb, changedBinding, embeddingConsent = false)
        }
        assertEquals(0, original.calls)
        assertEquals(0, changed.calls)
    }

    @Test
    fun apiEmbedderResolverIsExactAndChecksBindingDimension() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        val requestedSpaces = mutableListOf<String>()
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            apiEmbedderResolver = { spaceId ->
                requestedSpaces += spaceId
                if (spaceId == binding.spaceId) api else null
            },
        )
        val kb = repo.createApiKnowledgeBase("resolved API library", binding)
        val job = repo.importBytes(
            "resolved.txt",
            "text/plain",
            "resolver selected evidence".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )

        assertEquals(ImportStage.READY, job.stage)
        assertTrue(requestedSpaces.isNotEmpty())
        assertTrue(requestedSpaces.all { it == binding.spaceId })
        assertTrue(api.calls > 0)

        val wrongDimension = CountingEmbedder(binding.spaceId, binding.dimension + 1)
        val rejected = KnowledgeRepository(
            JdbcSqlConnection().also { Migrations.apply(it) },
            MemoryBlobSink(),
            apiEmbedderResolver = { wrongDimension },
        )
        assertThrows(IllegalStateException::class.java) {
            rejected.createApiKnowledgeBase("wrong dimension", binding)
        }
        assertEquals(0, wrongDimension.calls)
    }

    @Test
    fun queryWithoutApiConsentDoesNotResolveOrCallApiEmbedder() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        var resolverCalls = 0
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            apiEmbedderResolver = {
                resolverCalls += 1
                api
            },
        )
        val kb = repo.createApiKnowledgeBase("API library", binding)
        resolverCalls = 0
        val awaiting = repo.importBytes(
            "private.txt",
            "text/plain",
            "private API text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, awaiting.stage)
        resolverCalls = 0
        assertTrue(repo.search("private", knowledgeBaseIds = listOf(kb)).isEmpty())
        assertEquals(0, resolverCalls)
        assertEquals(0, api.calls)
    }

    @Test
    fun publicRebuildRequiresApiConsentBeforeResolvingAdapter() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        var resolverCalls = 0
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            apiEmbedderResolver = {
                resolverCalls += 1
                api
            },
        )
        val kb = repo.createApiKnowledgeBase("API library", binding)
        resolverCalls = 0

        assertThrows(IllegalStateException::class.java) { repo.rebuildIndex(kb) }
        assertEquals(0, resolverCalls)
        assertEquals(0, api.calls)
    }

    @Test
    fun apiKnowledgeBaseCannotBeImportedOrResumedAsLocal() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)

        assertThrows(IllegalStateException::class.java) {
            repo.importBytes(
                "local-mistake.txt",
                "text/plain",
                "must not use local embedding".toByteArray(),
                visionConfigured = false,
                knowledgeBaseId = kb,
                embeddingIsApi = false,
                embeddingConsent = false,
            )
        }
        assertEquals(0, api.calls)

        val awaiting = repo.importBytes(
            "resume-mistake.txt",
            "text/plain",
            "must not resume with local embedding".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        db.execute(
            "UPDATE import_jobs SET embedding_is_api = 0, embedding_consent = 0 WHERE id = ?",
            listOf(awaiting.id),
        )
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(awaiting.id, visionConfigured = false)
        }
        assertEquals(0, api.calls)
    }

    @Test
    fun uncertainApiRebindLeavesDurableGateAndDoesNotRetryAutomatically() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = UnknownEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createKnowledgeBase("Local library")
        repo.importBytes(
            "existing.txt",
            "text/plain",
            "existing local evidence".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
        )
        val localSpace = repo.embeddingSpaceId(kb)

        assertThrows(EmbeddingUnknownOutcomeException::class.java) {
            repo.rebindApiKnowledgeBase(kb, binding, embeddingConsent = true)
        }
        assertEquals(1, api.calls)
        assertEquals(localSpace, repo.embeddingSpaceId(kb))
        assertEquals(
            1,
            db.query(
                "SELECT id FROM import_jobs WHERE kb_id = ? AND stage = ? AND display_name LIKE ?",
                listOf(kb, ImportStage.FAILED.name, "__api_rebind_unknown__:%"),
            ).size,
        )

        assertThrows(IllegalStateException::class.java) {
            repo.rebindApiKnowledgeBase(kb, binding, embeddingConsent = true)
        }
        assertEquals(1, api.calls)
    }

    @Test
    fun successfulApiVectorsAreReusedAcrossRebuildAndKnowledgeBases() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val firstKb = repo.createApiKnowledgeBase("First API library", binding)
        val payload = "stable API evidence".toByteArray()
        assertEquals(
            ImportStage.READY,
            repo.importBytes(
                "first.txt",
                "text/plain",
                payload,
                visionConfigured = false,
                knowledgeBaseId = firstKb,
                embeddingIsApi = true,
                embeddingConsent = true,
            ).stage,
        )
        val callsAfterFirst = api.calls
        repo.rebuildIndex(firstKb)
        assertEquals(callsAfterFirst, api.calls)

        val secondKb = repo.createApiKnowledgeBase("Second API library", binding)
        assertEquals(
            ImportStage.READY,
            repo.importBytes(
                "second.txt",
                "text/plain",
                payload,
                visionConfigured = false,
                knowledgeBaseId = secondKb,
                embeddingIsApi = true,
                embeddingConsent = true,
            ).stage,
        )
        assertEquals(callsAfterFirst, api.calls)
    }

    @Test
    fun unknownApiEmbeddingCannotBeResumedAutomatically() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = UnknownEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val job = repo.importBytes(
            "unknown.txt",
            "text/plain",
            "uncertain response".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )

        assertEquals(ImportStage.FAILED, job.stage)
        assertTrue(job.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(job.id, visionConfigured = false)
        }
        val duplicate = repo.importBytes(
            "unknown.txt",
            "text/plain",
            "uncertain response".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )
        assertEquals(ImportStage.FAILED, duplicate.stage)
        assertEquals(1, api.calls)
    }

    @Test
    fun unknownApiEmbeddingRetriesOnceOnlyAfterExplicitAcknowledgement() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = FailOnceUnknownEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val failed = repo.importBytes(
            "retry.txt",
            "text/plain",
            "retryable API evidence".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )

        assertEquals(ImportStage.FAILED, failed.stage)
        assertTrue(failed.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, api.calls)
        assertThrows(IllegalStateException::class.java) {
            repo.retryUnknownEmbedding(failed.id, acknowledgeDuplicateCharge = false)
        }
        assertEquals(1, api.calls)

        val ready = repo.retryUnknownEmbedding(failed.id, acknowledgeDuplicateCharge = true)
        assertEquals(ImportStage.READY, ready.stage)
        assertEquals(2, api.calls)
        assertTrue(ready.error == null)
        assertThrows(IllegalStateException::class.java) {
            repo.retryUnknownEmbedding(failed.id, acknowledgeDuplicateCharge = true)
        }
        assertEquals(2, api.calls)
    }

    @Test
    fun unknownApiQueryRequiresExplicitOneTimeAuthorization() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = FailOnceUnknownEmbedder(binding.spaceId, binding.dimension).also { it.failNext = false }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        assertEquals(
            ImportStage.READY,
            repo.importBytes(
                "query-source.txt",
                "text/plain",
                "stable API query source".toByteArray(),
                visionConfigured = false,
                knowledgeBaseId = kb,
                embeddingIsApi = true,
                embeddingConsent = true,
            ).stage,
        )
        val query = "uncertain query"
        val queryHash = sha256Hex(query.toByteArray(Charsets.UTF_8))
        api.failNext = true

        val first = assertThrows(ApiQueryUnknownOutcomeException::class.java) {
            repo.retrieve("query-run", query, knowledgeBaseIds = listOf(kb))
        }
        assertEquals(kb, first.knowledgeBaseId)
        assertEquals(binding.spaceId, first.spaceId)
        assertEquals(queryHash, first.queryHash)
        assertTrue(first.message.orEmpty().contains("explicit retry authorization"))
        assertTrue(query !in first.message.orEmpty())
        assertEquals(2, api.calls)

        val pending = repo.pendingApiQueries(kb)
        assertEquals(1, pending.size)
        assertEquals(queryHash, pending.single().queryHash)
        assertFalse(pending.single().retryAuthorized)
        assertThrows(ApiQueryUnknownOutcomeException::class.java) {
            repo.retrieve("query-run-2", query, knowledgeBaseIds = listOf(kb))
        }
        assertEquals(2, api.calls)
        assertThrows(IllegalStateException::class.java) {
            repo.authorizeApiQueryRetry(kb, binding.spaceId, queryHash, acknowledgeDuplicateCharge = false)
        }
        assertEquals(2, api.calls)

        val authorized = repo.authorizeApiQueryRetry(kb, binding.spaceId, queryHash, acknowledgeDuplicateCharge = true)
        assertTrue(authorized.retryAuthorized)
        val retried = repo.retrieve("query-run-3", query, knowledgeBaseIds = listOf(kb))
        assertTrue(retried.hits.isNotEmpty())
        assertEquals(3, api.calls)
        assertTrue(repo.pendingApiQueries(kb).isEmpty())
    }

    @Test
    fun interruptedApiQueryIsDurablyUnknownAndNotAutoRetried() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = InterruptedOnceEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        api.interruptNext = false
        assertEquals(
            ImportStage.READY,
            repo.importBytes(
                "query-source.txt",
                "text/plain",
                "stable API query source".toByteArray(),
                visionConfigured = false,
                knowledgeBaseId = kb,
                embeddingIsApi = true,
                embeddingConsent = true,
            ).stage,
        )
        api.interruptNext = true

        assertThrows(ApiQueryUnknownOutcomeException::class.java) {
            repo.retrieve("interrupted-query-run", "interrupt me", knowledgeBaseIds = listOf(kb))
        }
        assertEquals(2, api.calls)
        assertThrows(ApiQueryUnknownOutcomeException::class.java) {
            repo.retrieve("interrupted-query-run-2", "interrupt me", knowledgeBaseIds = listOf(kb))
        }
        assertEquals(2, api.calls)
        assertFalse(repo.pendingApiQueries(kb).single().retryAuthorized)
    }

    @Test
    fun successfulApiQueryVectorIsReusedAfterLocalAnnFailure() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        val sink = MemoryBlobSink()
        val failing = KnowledgeRepository(
            db,
            sink,
            apiEmbedder = api,
            vectorIndexFactory = runtime.mobileagent.knowledge.VectorIndexFactory { spaceId, dimension, _ ->
                ThrowingVectorIndex(spaceId, dimension)
            },
        )
        val kb = failing.createApiKnowledgeBase("API library", binding)
        assertEquals(
            ImportStage.READY,
            failing.importBytes(
                "query-cache.txt",
                "text/plain",
                "cache survives local ANN failure".toByteArray(),
                visionConfigured = false,
                knowledgeBaseId = kb,
                embeddingIsApi = true,
                embeddingConsent = true,
            ).stage,
        )
        val beforeQuery = api.calls
        assertThrows(IllegalStateException::class.java) {
            failing.retrieve("cache-failure", "reuse this query", knowledgeBaseIds = listOf(kb))
        }
        assertEquals(beforeQuery + 1, api.calls)
        assertEquals(
            1,
            db.query(
                "SELECT COUNT(*) AS n FROM embedding_query_vectors WHERE space_id = ?",
                listOf(binding.spaceId),
            ).single().long("n"),
        )

        val healthy = KnowledgeRepository(db, sink, apiEmbedder = api)
        val result = healthy.retrieve("cache-retry", "reuse this query", knowledgeBaseIds = listOf(kb))
        assertTrue(result.hits.isNotEmpty())
        assertEquals(beforeQuery + 1, api.calls)
    }

    @Test
    fun apiEmbeddingCacheSurvivesGenerationPublishFailure() {
        val inner = JdbcSqlConnection()
        Migrations.apply(inner)
        val db = FailOnceGenerationConnection(inner)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val first = repo.importBytes(
            "publish-failure.txt",
            "text/plain",
            "cache before generation publication".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )
        assertEquals(ImportStage.FAILED, first.stage)
        val callsAfterCache = api.calls
        val resumed = repo.resumeImport(first.id, visionConfigured = false)
        assertEquals(ImportStage.READY, resumed.stage)
        assertEquals(callsAfterCache, api.calls)
        assertTrue(repo.search("publication", knowledgeBaseIds = listOf(kb)).isNotEmpty())
    }

    @Test
    fun cancelledApiEmbeddingAfterDispatchBecomesUnknownWithoutReplay() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CancelledCancellableEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                repo.importBytesCancellable(
                    "cancelled.txt",
                    "text/plain",
                    "cancel after dispatch".toByteArray(),
                    visionConfigured = false,
                    knowledgeBaseId = kb,
                    embeddingIsApi = true,
                    embeddingConsent = true,
                )
            }
        }
        assertEquals(1, api.calls)
        assertEquals(
            1,
            db.query(
                "SELECT COUNT(*) AS n FROM embedding_operations WHERE kb_id = ? AND state = 'UNKNOWN'",
                listOf(kb),
            ).single().long("n"),
        )
        val job = db.query("SELECT id, stage, error FROM import_jobs WHERE kb_id = ? ORDER BY updated_at DESC LIMIT 1", listOf(kb)).single()
        assertEquals(ImportStage.FAILED.name, job.string("stage"))
        assertTrue(job.string("error").contains("UNKNOWN_OUTCOME"))
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(job.string("id"), visionConfigured = false)
        }
        assertEquals(1, api.calls)
    }

    @Test
    fun ordinaryApiEmbeddingFailureAfterDispatchBecomesUnknownWithoutReplay() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = OrdinaryFailureEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)

        val failed = repo.importBytes(
            "ordinary-failure.txt",
            "text/plain",
            "failure after dispatch".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = true,
        )

        assertEquals(ImportStage.FAILED, failed.stage)
        assertTrue(failed.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, api.calls)
        assertEquals(
            1,
            db.query(
                "SELECT COUNT(*) AS n FROM embedding_operations WHERE kb_id = ? AND state = 'UNKNOWN'",
                listOf(kb),
            ).single().long("n"),
        )
        assertThrows(IllegalStateException::class.java) {
            repo.resumeImport(failed.id, visionConfigured = false)
        }
        assertEquals(1, api.calls)
    }

    @Test
    fun visionConsentDoesNotImplyApiEmbeddingConsent() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testApiBinding()
        val api = CountingEmbedder(binding.spaceId, binding.dimension)
        var visionCalls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            visionCalls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "description"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val awaiting = repo.importBytes(
            "visual.png",
            "image/png",
            png,
            visionConfigured = true,
            knowledgeBaseId = kb,
            visionConsent = true,
            embeddingIsApi = true,
            embeddingConsent = false,
        )

        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, awaiting.stage)
        assertEquals(1, visionCalls)
        assertEquals(0, api.calls)
        val ready = repo.grantEmbeddingConsent(awaiting.id, visionConfigured = true)
        assertEquals(ImportStage.READY, ready.stage, ready.error)
        assertEquals(1, visionCalls)
        assertTrue(api.calls > 0)
    }

    @Test
    fun epubWithTextIsSearchable() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val zip = zipBytesNamed(
            "mimetype" to "application/epub+zip",
            "OPS/ch1.xhtml" to "<html><body><p>EPUB mentions USearch JNI</p></body></html>",
        )
        val job = repo.importBytes("book.epub", "application/epub+zip", zip, false)
        assertEquals(ImportStage.READY, job.stage)
        assertTrue(repo.search("USearch").any { "JNI" in it.text })
    }

    @Test
    fun skillEPackageIsNotInstalled() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val skills = SkillRepository(db)
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(4)
        val zip = zipBytesNamed("native.so" to String(elf, Charsets.ISO_8859_1))
        val result = skills.importPackage(zip)
        assertFalse(result.accepted)
        assertTrue(skills.list().isEmpty())
    }

    @Test
    fun skillInstructionPackageCanBeListed() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val skills = SkillRepository(db)
        val zip = zipBytesNamed("SKILL.md" to "# Helper\nSearch only granted libraries.\n")
        val result = skills.importPackage(zip, enable = true)
        assertTrue(result.accepted)
        assertEquals(1, skills.list().size)
        assertTrue(skills.enabledInstructions().any { it.contains("Helper") })
    }

    @Test
    fun vectorPdfDoesNotBecomeReadyWithoutVision() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val pdf = runtime.mobileagent.knowledge.PdfParser.writeTextAndVectorPdf("vector label")
        val job = repo.importBytes("draw.pdf", "application/pdf", pdf, visionConfigured = false)
        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, job.stage)
        assertTrue(repo.search("vector").isEmpty())
    }

    @Test
    fun drawingOnlyPdfDoesNotCallVisionOrBecomeReady() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var calls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            calls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "desc"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision)
        val pdf = runtime.mobileagent.knowledge.PdfParser.writeDrawingOnlyPdf()
        val job = repo.importBytes("draw.pdf", "application/pdf", pdf, visionConfigured = true, visionConsent = true)
        assertEquals(ImportStage.FAILED, job.stage)
        assertEquals(0, calls)
        assertTrue(repo.search("Page").isEmpty())
    }

    @Test
    fun rawFlatePdfImageDoesNotReachVisionOrBecomeReady() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var calls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            calls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "desc"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision)
        val pdf = rawFlateImagePdf("raw image label")

        val job = repo.importBytes(
            "raw-image.pdf",
            "application/pdf",
            pdf,
            visionConfigured = true,
            visionConsent = true,
        )

        assertEquals(ImportStage.FAILED, job.stage)
        assertEquals(0, calls)
        assertTrue(repo.search("raw image label").isEmpty())
    }

    @Test
    fun docxExternalImageDoesNotBecomeReadyOrFetch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        var calls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            calls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "desc"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision)
        val zip = zipBytesNamed(
            "word/document.xml" to """<w:document><w:body><w:p><w:r><w:t>caption</w:t></w:r></w:p><w:p><w:r><w:drawing><a:blip r:link="rId9"/></w:drawing></w:r></w:p></w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="rId9" Type="http://example/image" Target="https://example.invalid/image.png" TargetMode="External"/></Relationships>""",
        )
        val job = repo.importBytes("note.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip, true, visionConsent = true)
        assertEquals(ImportStage.FAILED, job.stage)
        assertEquals(0, calls)
        assertTrue(repo.search("caption").isEmpty())
    }

    @Test
    fun visionTableMarkdownIsIndexedAndCached() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        var calls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            calls += 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess(
                    ocrText = "",
                    semanticDescription = "table",
                    tableMarkdown = "TABLE_ONLY_MARKER",
                    type = "table",
                ),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val first = repo.importBytes("grid.png", "image/png", png, visionConfigured = true, visionConsent = true)
        assertEquals(ImportStage.READY, first.stage)
        assertTrue(repo.search("TABLE_ONLY_MARKER").any { "TABLE_ONLY_MARKER" in it.text })
        val second = repo.grantVisionConsent(first.id)
        assertEquals(ImportStage.READY, second.stage)
        assertEquals(1, calls)
        assertTrue(repo.search("TABLE_ONLY_MARKER").any { "TABLE_ONLY_MARKER" in it.text })
    }

    @Test
    fun unknownVisionRetryRequiresAckAndCanSucceed() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        var calls = 0
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            calls += 1
            if (calls == 1) runtime.mobileagent.knowledge.VisionOutcome.UnknownOutcome
            else runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "recovered"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision)
        val job = repo.importBytes("scan.png", "image/png", png, visionConfigured = true, visionConsent = true)
        assertEquals(ImportStage.FAILED, job.stage)
        val again = repo.grantVisionConsent(job.id)
        assertEquals(ImportStage.FAILED, again.stage)
        assertEquals(1, calls)
        val retried = repo.retryUnknownVision(job.id, acknowledgeDuplicateCharge = true)
        assertEquals(ImportStage.READY, retried.stage)
        assertEquals(2, calls)
        assertTrue(repo.search("recovered").isNotEmpty())
    }

    @Test
    fun locatorRejectsForgedCitationAndReturnsAssetHash() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val pdf = runtime.mobileagent.knowledge.PdfParser.writePdfWithImageXObject("flowchart page")
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr-flow", "flowchart of torque"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionModelFingerprint = "vision-test")
        val job = repo.importBytes("flow.pdf", "application/pdf", pdf, visionConfigured = true, visionConsent = true)
        assertEquals(ImportStage.READY, job.stage)
        val hits = repo.search("flowchart")
        val bound = CitationMap.bind("run", hits)
        val locator = repo.locateCitation(bound.first())
        assertFalse(locator.removed)
        assertEquals(1, locator.page)
        assertTrue(locator.assetId != null)
        val asset = db.query("SELECT blob_hash FROM assets WHERE id = ?", listOf(locator.assetId)).single()
        assertEquals(asset.string("blob_hash"), locator.blobHash)
        val evidence = repo.evidenceBytes(bound.first())
        assertEquals("image/jpeg", evidence?.first)
        assertTrue(evidence?.second?.isNotEmpty() == true)
        val forged = bound.first().copy(chunkId = "no-such-chunk", documentVersionId = "forged", assetId = "forged")
        assertTrue(repo.locateCitation(forged).removed)
        val otherKnowledgeBase = repo.createKnowledgeBase("Other library")
        assertTrue(repo.locateCitation(bound.first().copy(knowledgeBaseId = otherKnowledgeBase)).removed)
        assertTrue(repo.locateCitation(bound.first().copy(knowledgeBaseId = "")).removed)
        assertTrue(repo.locateCitation(bound.first().copy(documentVersionId = "")).removed)
        assertNull(repo.evidenceBytes(bound.first().copy(knowledgeBaseId = otherKnowledgeBase)))
        repo.deleteKnowledgeBase(otherKnowledgeBase)
        assertTrue(repo.locateCitation(bound.first().copy(knowledgeBaseId = otherKnowledgeBase)).removed)
    }

    @Test
    fun twoPagePdfDoesNotSplitByCharacterCount() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val pdf = runtime.mobileagent.knowledge.PdfParser.writeTwoPageTextPdf("ALPHAPAGEUNIQUE", "BETAPAGEUNIQUE")
        val job = repo.importBytes("two.pdf", "application/pdf", pdf, false)
        assertEquals(ImportStage.READY, job.stage)
        val alphaPages = repo.search("ALPHAPAGEUNIQUE").filter { "ALPHAPAGEUNIQUE" in it.text }.map { it.page }.toSet()
        val betaPages = repo.search("BETAPAGEUNIQUE").filter { "BETAPAGEUNIQUE" in it.text }.map { it.page }.toSet()
        assertEquals(setOf(1), alphaPages)
        assertEquals(setOf(2), betaPages)
    }

    @Test
    fun skillGrantDoesNotSearchUndeclaredKnowledgeBase() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val kbA = "kb-a"
        val kbB = "kb-b"
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        repo.createKnowledgeBase("A", kbA)
        repo.createKnowledgeBase("B", kbB)
        repo.importBytes("a.txt", "text/plain", "alpha-only-marker".toByteArray(), false, knowledgeBaseId = kbA)
        repo.importBytes("b.txt", "text/plain", "beta-private-marker".toByteArray(), false, knowledgeBaseId = kbB)
        val skills = SkillRepository(db)
        val manifest = """
            {"schemaVersion":1,"id":"dev.example.kb","name":"KB","version":"1","license":"AGPL-3.0-only",
             "runtime":{"kind":"instruction"},
             "permissions":{"knowledge.search":{"knowledgeBaseIds":["kb-a"]}}}
        """.trimIndent()
        val zip = zipBytesNamed("mobile-skill.json" to manifest, "SKILL.md" to "# k\n")
        assertTrue(skills.importPackage(zip, enable = true).accepted)
        val grant = skills.effectiveGrant()
        val broker = runtime.mobileagent.skills.ToolBroker(
            grant.capabilities,
            runtime.mobileagent.skills.ToolContext(
                search = { query, ids, topK ->
                    repo.search(query, topK, ids).joinToString { it.text }
                },
                readDocument = { id, max -> repo.readDocumentText(id, max) },
                grantedKnowledgeBaseIds = grant.knowledgeBaseIds,
            ),
        )
        val result = broker.invoke(
            runtime.mobileagent.skills.ToolCall("s", "knowledge_search", """{"query":"marker","knowledgeBaseIds":["kb-b"]}"""),
        ) as runtime.mobileagent.skills.ToolResult.Value
        assertTrue("beta-private-marker" !in result.json)
        val reimport = skills.importPackage(zip, enable = true)
        assertTrue(reimport.accepted)
        assertEquals(1, skills.grantsFor(skills.list().single().installId).size)
        val bytes = skills.packageBytes(skills.list().single().packageHash)
        assertTrue(bytes != null && bytes.contentEquals(zip))
    }

    @Test
    fun inlineImagePdfDoesNotBecomeReadyWithoutVision() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val pdf = runtime.mobileagent.knowledge.PdfParser.writeTextAndInlineImagePdf("inline caption token")
        val job = repo.importBytes("inline.pdf", "application/pdf", pdf, visionConfigured = false)
        assertTrue(job.stage == ImportStage.WAITING_FOR_VISION_MODEL || job.stage == ImportStage.FAILED)
        assertFalse(ImportStateMachine.isCompleteSuccess(job))
        assertTrue(repo.search("inline caption token").isEmpty() || job.stage != ImportStage.READY)
    }

    @Test
    fun visionConsentDoesNotFollowSwitchedProvider() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        var binding = runtime.mobileagent.knowledge.VisionBinding("prov-a", "shared-model", "https://a.example.invalid/v1", 1)
        val calls = mutableMapOf<String, Int>()
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            val id = binding.providerId
            calls[id] = calls.getOrDefault(id, 0) + 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "desc-$id"),
            )
        }
        val first = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionBinding = { binding })
        val paused = first.importBytes("scan.png", "image/png", png, visionConfigured = true, visionConsent = true, pauseAt = ImportStage.COPYING)
        assertEquals(ImportStage.COPYING, paused.stage)
        binding = runtime.mobileagent.knowledge.VisionBinding("prov-b", "shared-model", "https://b.example.invalid/v1", 1)
        val resumed = first.resumeImport(paused.id, png, visionConfigured = true)
        assertEquals(ImportStage.AWAITING_UPLOAD_CONSENT, resumed.stage)
        assertEquals(0, calls.getOrDefault("prov-b", 0))
        assertFalse(ImportStateMachine.isCompleteSuccess(resumed))
    }

    @Test
    fun visionCacheDoesNotCrossProvidersWithSameModelId() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        var binding = runtime.mobileagent.knowledge.VisionBinding("prov-a", "shared-model", "https://a.example.invalid/v1", 1)
        val calls = mutableMapOf<String, Int>()
        val vision = runtime.mobileagent.knowledge.VisionBackend {
            val id = binding.providerId
            calls[id] = calls.getOrDefault(id, 0) + 1
            runtime.mobileagent.knowledge.VisionOutcome.Success(
                runtime.mobileagent.knowledge.VisionSuccess("ocr", "desc-$id"),
            )
        }
        val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = vision, visionBinding = { binding })
        repo.createKnowledgeBase("A", "kb-a")
        repo.createKnowledgeBase("B", "kb-b")
        val a = repo.importBytes("scan.png", "image/png", png, true, knowledgeBaseId = "kb-a", visionConsent = true)
        assertEquals(ImportStage.READY, a.stage)
        assertEquals(1, calls["prov-a"])
        binding = runtime.mobileagent.knowledge.VisionBinding("prov-b", "shared-model", "https://b.example.invalid/v1", 1)
        val b = repo.importBytes("scan.png", "image/png", png, true, knowledgeBaseId = "kb-b", visionConsent = true)
        assertEquals(ImportStage.READY, b.stage)
        assertEquals(1, calls["prov-b"])
    }

    @Test
    fun epubSameNameImagesStayOnTheirChapters() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val pngA = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "MARK-A".toByteArray()
        val pngB = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "MARK-B".toByteArray()
        val zip = zipNamedBytes(
            "mimetype" to "application/epub+zip".toByteArray(),
            "OPS/ch1/chapter.xhtml" to "<html><body><p>chapter-one-unique</p><img src=\"images/fig.png\"/></body></html>".toByteArray(),
            "OPS/ch2/chapter.xhtml" to "<html><body><p>chapter-two-unique</p><img src=\"images/fig.png\"/></body></html>".toByteArray(),
            "OPS/ch1/images/fig.png" to pngA,
            "OPS/ch2/images/fig.png" to pngB,
        )
        val parsed = runtime.mobileagent.knowledge.OfficeParser.parse("book.epub", zip)
        val page2 = parsed.assets.single { it.page == 2 && it.kind == "IMAGE" }
        assertTrue(String(page2.bytes, Charsets.ISO_8859_1).contains("MARK-B"))
    }

    @Test
    fun readDocumentHonorsKnowledgeBaseGrant() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        repo.createKnowledgeBase("A", "kb-a")
        repo.createKnowledgeBase("B", "kb-b")
        val a = repo.importBytes("a.txt", "text/plain", "private-A-marker".toByteArray(), false, knowledgeBaseId = "kb-a")
        val b = repo.importBytes("b.txt", "text/plain", "private-B-marker".toByteArray(), false, knowledgeBaseId = "kb-b")
        assertEquals(ImportStage.READY, a.stage)
        val grant = runtime.mobileagent.skills.PermissionGrant(
            grantId = "g",
            installId = "i",
            packageHash = "h",
            capabilities = setOf("knowledge.read"),
            knowledgeBaseIds = setOf("kb-a"),
        )
        val broker = runtime.mobileagent.skills.ToolBroker(
            emptySet(),
            runtime.mobileagent.skills.ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { id, max -> repo.readDocumentText(id, max, grant.knowledgeBaseIds) },
                grantedKnowledgeBaseIds = emptySet(),
                documentKnowledgeBaseId = { id -> repo.documentKnowledgeBaseId(id) },
            ),
            liveGrant = { grant },
        )
        val denied = broker.invoke(
            runtime.mobileagent.skills.ToolCall("r", "read_document", """{"documentId":"${b.documentId}"}"""),
        )
        assertTrue(denied is runtime.mobileagent.skills.ToolResult.Denied)
        assertTrue("private-B-marker" !in denied.toString())
        val allowed = broker.invoke(
            runtime.mobileagent.skills.ToolCall("r2", "read_document", """{"documentId":"${a.documentId}"}"""),
        ) as runtime.mobileagent.skills.ToolResult.Value
        assertTrue(allowed.json.contains("private-A-marker"))
    }

    private fun zipNamedBytes(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipBytesNamed(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun applyLegacyV3(db: JdbcSqlConnection) {
        listOf(
            "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS knowledge_bases (id TEXT PRIMARY KEY, name TEXT NOT NULL, active_generation_id TEXT, embedding_space_id TEXT, created_at TEXT NOT NULL, deleted_at TEXT)",
            "CREATE TABLE IF NOT EXISTS blobs (hash TEXT PRIMARY KEY, byte_length INTEGER NOT NULL, media_type TEXT NOT NULL, local_ref TEXT NOT NULL, ref_count INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, blob_hash TEXT NOT NULL, display_name TEXT NOT NULL, format TEXT NOT NULL, active_version_id TEXT, deleted_at TEXT, UNIQUE(kb_id, blob_hash), FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))",
            "CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, document_version_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, content_hash TEXT NOT NULL, UNIQUE(document_version_id, ordinal))",
            "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, content='chunks', content_rowid='rowid')",
            "CREATE TABLE IF NOT EXISTS embeddings (chunk_id TEXT NOT NULL, space_id TEXT NOT NULL, vector_blob BLOB NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY(chunk_id, space_id))",
            "CREATE TABLE IF NOT EXISTS import_jobs (id TEXT PRIMARY KEY, kb_id TEXT NOT NULL, document_id TEXT NOT NULL, display_name TEXT NOT NULL, stage TEXT NOT NULL, has_images INTEGER NOT NULL, error TEXT, updated_at TEXT NOT NULL)",
        ).forEach { db.execute(it) }
    }

    private fun zipSlip(): ByteArray = zipBytes("../evil.txt", "no")

    private fun validZip(name: String): ByteArray = zipBytes(name, "application/epub+zip")

    private fun zipBytes(name: String, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(payload.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun rawFlateImagePdf(label: String): ByteArray {
        val pdf = runtime.mobileagent.knowledge.PdfParser.writePdfWithImageXObject(label)
        val marker = "/Filter /DCTDecode /Length 4 >>\nstream\n"
        val markerStart = String(pdf, Charsets.ISO_8859_1).indexOf(marker)
        require(markerStart >= 0) { "Image object marker not found" }
        val oldPayloadStart = markerStart + marker.length
        val oldPayloadEnd = oldPayloadStart + 4
        val compressed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(byteArrayOf(0x7F, 0x20, 0x10)) }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(pdf, 0, markerStart)
            write("/Filter /FlateDecode /Length ${compressed.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            write(compressed)
            write(pdf, oldPayloadEnd, pdf.size - oldPayloadEnd)
        }.toByteArray()
    }

    private fun testApiBinding(
        endpoint: String = "https://api.example.test/v1/embeddings",
        modelId: String = "embedding-model",
        modelRevision: Int = 1,
    ): ApiEmbeddingBinding = ApiEmbeddingBinding(
        providerId = "provider-test",
        endpoint = endpoint,
        providerRevision = 1,
        modelId = modelId,
        modelRevision = modelRevision,
        dimension = 8,
        dataScope = "document text; retrieval purpose",
    )
}

    private class ThrowingEmbedder : runtime.mobileagent.knowledge.TextEmbedder {
    override val spaceId: String = "local-hash-v1-d32"
    override val dimension: Int = 32
    override fun embed(text: String): FloatArray = error("embed failed")
}

private class GenerationSwitchingConnection(
    private val inner: SqlConnection,
    private val original: String,
) : SqlConnection {
    var activeReads: Int = 0

    override fun execute(sql: String, args: List<Any?>) = inner.execute(sql, args)

    override fun query(sql: String, args: List<Any?>): List<SqlRow> {
        if (sql.contains("SELECT active_generation_id FROM knowledge_bases")) {
            activeReads += 1
            if (activeReads > 1) {
                return listOf(SqlRow(mapOf("active_generation_id" to "gen-other")))
            }
        }
        return inner.query(sql, args)
    }

    override fun <T> transaction(block: () -> T): T = inner.transaction(block)
}

private class CountingEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        return FloatArray(dimension) { index -> (text.hashCode() + index).toFloat() }
    }
}

private class EmptyVectorIndex(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.VectorIndexPort {
    override fun add(id: String, vector: FloatArray) = Unit

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> = emptyList()
}

private class ThrowingVectorIndex(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.VectorIndexPort {
    override fun add(id: String, vector: FloatArray) = Unit

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> =
        error("injected local ANN failure")
}

private class FailOnceGenerationConnection(
    private val inner: SqlConnection,
) : SqlConnection {
    private var failNextGenerationUpdate = true

    override fun execute(sql: String, args: List<Any?>) {
        if (failNextGenerationUpdate && sql.contains("UPDATE index_generations SET state")) {
            failNextGenerationUpdate = false
            error("injected generation publication failure")
        }
        inner.execute(sql, args)
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> = inner.query(sql, args)

    override fun <T> transaction(block: () -> T): T = inner.transaction(block)
}

private class CancelledCancellableEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.TextEmbedder,
    runtime.mobileagent.knowledge.CancellableBatchTextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray = error("sync API embedding is not allowed in this fixture")

    override suspend fun embedBatchCancellable(texts: List<String>): List<FloatArray> {
        calls += 1
        throw CancellationException("cancelled after dispatch")
    }
}

private class OrdinaryFailureEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        throw IllegalStateException("injected provider failure after dispatch")
    }
}

private class UnknownEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        throw EmbeddingUnknownOutcomeException()
    }
}

private class FailOnceUnknownEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.TextEmbedder {
    var calls: Int = 0
    var failNext = true

    override fun embed(text: String): FloatArray {
        calls += 1
        if (failNext) {
            failNext = false
            throw EmbeddingUnknownOutcomeException()
        }
        return FloatArray(dimension) { index -> (text.hashCode() + index).toFloat() }
    }
}

private class InterruptedOnceEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : runtime.mobileagent.knowledge.TextEmbedder {
    var calls: Int = 0
    var interruptNext: Boolean = true

    override fun embed(text: String): FloatArray {
        calls += 1
        if (interruptNext) {
            interruptNext = false
            throw InterruptedException("transport interrupted")
        }
        return FloatArray(dimension) { index -> (text.hashCode() + index).toFloat() }
    }
}
