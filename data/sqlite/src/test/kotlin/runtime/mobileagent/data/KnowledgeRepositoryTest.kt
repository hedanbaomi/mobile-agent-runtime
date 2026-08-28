// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun epubArchiveFailsWithEvidenceAndIsNotParsed() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("book.epub", "application/epub+zip", validZip("mimetype"), false)
        assertEquals(ImportStage.FAILED, job.stage)
        assertTrue(job.error.orEmpty().contains("DOCX/EPUB"))
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
