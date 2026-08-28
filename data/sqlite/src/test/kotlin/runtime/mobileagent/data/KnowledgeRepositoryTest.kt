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
import runtime.mobileagent.knowledge.CitationMap
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import java.io.ByteArrayOutputStream
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
        assertEquals(ImportStage.READY, ready.stage)
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
        val forged = bound.first().copy(chunkId = "no-such-chunk", documentVersionId = "forged", assetId = "forged")
        assertTrue(repo.locateCitation(forged).removed)
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
