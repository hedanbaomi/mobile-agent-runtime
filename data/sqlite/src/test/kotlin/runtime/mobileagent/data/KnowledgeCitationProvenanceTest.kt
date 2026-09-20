// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.MemoryBlobSink

class KnowledgeCitationProvenanceTest {
    @Test fun officeParagraphOrdinalsAreNeverPresentedAsPhysicalPages() {
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
                zip.write("<w:document><w:p><w:r><w:t>first paragraph</w:t></w:r></w:p><w:p><w:r><w:t>Cobalt evidence</w:t></w:r></w:p></w:document>".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink())
            val job = repo.importBytes("paragraphs.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes, false)
            assertEquals(runtime.mobileagent.knowledge.ImportStage.READY, job.stage)
            val citation = repo.retrieve("office", "Cobalt").citations.first()
            assertNull(citation.page)
            assertTrue(citation.sourceSpan.orEmpty().startsWith("section-ordinal:2|"))
            assertFalse(repo.locateCitation(citation).removed)
            assertTrue(repo.evidenceBytes(citation)!!.second.contentEquals(bytes))
        }
    }

    @Test fun documentRangeReachesTheTailWithoutSplittingUnicodeOrCrossingAuthorization() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes("long.txt", "text/plain", ("abcd😀漢".repeat(4000)+"TAIL_COMPLETE").toByteArray(), false)
        var offset = 0
        var version: String? = null
        val combined = StringBuilder()
        do {
            val range = repo.readDocumentRange(job.documentId, 127, offset, setOf(job.knowledgeBaseId), version)
            assertEquals(range.text, String(range.text.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            combined.append(range.text)
            version = range.documentVersionId
            val next = range.nextOffset ?: break
            assertTrue(next > offset)
            offset = next
        } while (true)
        val truth = db.query("SELECT c.text FROM chunks c JOIN documents d ON d.active_version_id=c.document_version_id WHERE d.id=? ORDER BY c.ordinal", listOf(job.documentId))
            .joinToString("\n") { it.string("text") }
        assertEquals(truth, combined.toString())
        assertTrue(combined.endsWith("TAIL_COMPLETE"))
        assertEquals("", repo.readDocumentRange(job.documentId, 100, 0, emptySet()).text)
        assertThrows(IllegalArgumentException::class.java) { repo.readDocumentRange(job.documentId, 100, -1) }
    }

    @Test fun malformedSqliteVectorCannotBecomeAnApparentlyCompleteIndex() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val repo = KnowledgeRepository(db, blobs)
        val job = repo.importBytes("source.txt", "text/plain", "Unbroken cobalt source.".toByteArray(), false)
        db.execute("UPDATE embeddings SET vector_blob=?", listOf(byteArrayOf(0)))
        val restarted = KnowledgeRepository(db, blobs)
        assertThrows(IllegalStateException::class.java) { restarted.search("cobalt") }
        assertEquals(0, restarted.vectorIndexStats().builds)
        assertTrue(restarted.readDocumentText(job.documentId, 200).contains("Unbroken cobalt"))
    }

    @Test fun callerCannotInventPageOrSourceSpanForAValidChunk() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        repo.importBytes("source.txt", "text/plain", "Verified cobalt reference.".toByteArray(), false)
        val citation = repo.retrieve("test-run", "cobalt").citations.first()
        assertFalse(repo.locateCitation(citation).removed)
        val fakePage = citation.copy(page = 98765)
        val fakeSpan = citation.copy(sourceSpan = "page:98765|region:invented")
        assertTrue(repo.locateCitation(fakePage).removed)
        assertTrue(repo.locateCitation(fakeSpan).removed)
        assertNull(repo.evidenceBytes(fakePage))
        assertNull(repo.evidenceBytes(fakeSpan))
    }
}
