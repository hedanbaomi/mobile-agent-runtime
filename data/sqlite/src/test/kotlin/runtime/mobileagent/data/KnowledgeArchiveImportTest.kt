// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
