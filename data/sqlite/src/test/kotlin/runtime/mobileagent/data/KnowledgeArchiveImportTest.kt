// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportItemState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KnowledgeArchiveImportTest {
    @Test
    fun zipDatasetExpandsIntoBatchAndIndexesText() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val zip = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notes.txt"))
                zip.write("Alpha widget torque spec is 12Nm.".toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val job = repo.importBytes("library.zip", "application/zip", zip, visionConfigured = false)
        assertEquals(ImportStage.READY, job.stage)
        assertTrue(repo.listBatches(job.knowledgeBaseId).isNotEmpty())
        assertTrue(repo.search("widget").any { "12Nm" in it.text })
    }

    @Test
    fun coordinatorPublishesEveryItemAndSynchronizesItemStates() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "two files")
        val first = repo.importBytes("first.txt", "text/plain", "first-marker".toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
        val second = repo.importBytes("second.txt", "text/plain", "second-marker".toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
        repo.bindJobToBatch(batchId, first, "first.txt")
        repo.bindJobToBatch(batchId, second, "second.txt")

        assertEquals(ImportBatchState.COPYING, repo.listBatches(kb).single().state)
        assertTrue(repo.listBatches(kb).single().state != ImportBatchState.COMPLETED)
        repo.processBatch(batchId, visionConfigured = false)

        val batch = repo.listBatches(kb).single()
        assertEquals(ImportBatchState.COMPLETED, batch.state)
        assertEquals(2, batch.totalItems)
        assertEquals(2, batch.copied)
        assertEquals(0, batch.failed)
        assertEquals(2, db.query("SELECT COUNT(*) AS n FROM import_items WHERE batch_id = ? AND state = ?", listOf(batchId, ImportItemState.PUBLISHED.name)).single().long("n"))
        assertEquals(ImportStage.READY, repo.listJobs().first { it.first.id == first.id }.first.stage)
        assertEquals(ImportStage.READY, repo.listJobs().first { it.first.id == second.id }.first.stage)
        assertTrue(repo.search("first-marker", knowledgeBaseIds = listOf(kb)).isNotEmpty())
        assertTrue(repo.search("second-marker", knowledgeBaseIds = listOf(kb)).isNotEmpty())
    }

    @Test
    fun externalGenerationChangeFailsBatchBeforeProcessing() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "generation guard")
        val job = repo.importBytes("guard.txt", "text/plain", "guard-marker".toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
        repo.bindJobToBatch(batchId, job, "guard.txt")
        db.execute("UPDATE knowledge_bases SET active_generation_id = ? WHERE id = ?", listOf("external-generation", kb))

        assertTrue(!repo.generationStillCurrent(batchId))
        assertThrows(IllegalStateException::class.java) { repo.processBatch(batchId, visionConfigured = false) }
        assertEquals(ImportBatchState.FAILED, repo.listBatches(kb).single().state)
        assertEquals(ImportItemState.FAILED.name, db.query("SELECT state FROM import_items WHERE batch_id = ?", listOf(batchId)).single().string("state"))
        assertEquals(ImportStage.FAILED, repo.listJobs().first { it.first.id == job.id }.first.stage)
        assertTrue(repo.search("guard-marker", knowledgeBaseIds = listOf(kb)).isEmpty())
    }

    @Test
    fun waitingItemNeverCompletesBatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "waiting image")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val job = repo.importBytes("waiting.png", "image/png", png, false, kb, pauseAt = ImportStage.COPYING)
        repo.bindJobToBatch(batchId, job, "waiting.png")
        repo.processBatch(batchId, visionConfigured = false)

        assertEquals(ImportBatchState.WAITING, repo.listBatches(kb).single().state)
        assertEquals(ImportItemState.WAITING.name, db.query("SELECT state FROM import_items WHERE batch_id = ?", listOf(batchId)).single().string("state"))
        assertTrue(repo.listBatches(kb).single().state != ImportBatchState.COMPLETED)
    }
}
