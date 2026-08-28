// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink

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
}
