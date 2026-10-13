// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class OfficeParserRecoveryTest {
    @Test fun legacyOfficeDerivativesRequireExplicitReimportWithoutRewritingHistory() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>Recovery evidence</w:t></w:r></w:p></w:document>".toByteArray())
                zip.closeEntry()
            } }.toByteArray()
            val job = repo.importBytes("recovery.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes, false)
            assertEquals(ImportStage.READY, job.stage, job.error)
            val oldVersion = db.query("SELECT active_version_id FROM documents WHERE id=?", listOf(job.documentId)).single().string("active_version_id")
            db.execute("UPDATE document_versions SET parser_fingerprint='docx-xml-v2' WHERE id=?", listOf(oldVersion))
            val result = repo.retrieve("synthetic", "Recovery", knowledgeBaseIds = listOf(job.knowledgeBaseId))
            assertTrue(result.hits.isEmpty())
            assertTrue(result.warnings.any { it.contains("PARSER_REPARSE_REQUIRED") })
            assertThrows(IllegalStateException::class.java) { repo.rebuildIndex(job.knowledgeBaseId) }
            assertThrows(IllegalStateException::class.java) { repo.readDocumentText(job.documentId, 1000) }
            assertTrue(repo.readDocumentRange(job.documentId, 1000, expectedVersion = oldVersion).text.contains("Recovery evidence"))
            val repaired = repo.importBytes("recovery.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes, false)
            assertEquals(ImportStage.READY, repaired.stage, repaired.error)
            assertEquals(job.documentId, repaired.documentId)
            assertTrue(repo.readDocumentText(job.documentId, 1000).contains("Recovery evidence"))
            assertTrue(repo.retrieve("after", "Recovery", knowledgeBaseIds = listOf(job.knowledgeBaseId)).hits.isNotEmpty())
            assertEquals("docx-xml-v2", db.query("SELECT parser_fingerprint FROM document_versions WHERE id=?", listOf(oldVersion)).single().string("parser_fingerprint"))
        }
    }
}
