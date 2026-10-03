// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class KnowledgeRepositoryEpubCompletenessTest {
    @Test fun blockedVisualsNeverPublishReadyOrSendVisionInEitherImportPath(): Unit = runBlocking {
        for (async in listOf(false, true)) {
            for (visual in listOf("<img src='https://example.invalid/a.png'/>", "<svg><rect/></svg>", "<object data='missing.svg'/>")) {
                JdbcSqlConnection().use { db ->
                    Migrations.apply(db)
                    var calls = 0
                    val binding = ApiEmbeddingBinding("fixture", "https://example.invalid/v1/embeddings", 1, "fixture", 1, 8, "retrieval")
                    val api = object : TextEmbedder {
                        override val spaceId = binding.spaceId
                        override val dimension = 8
                        override fun embed(text: String) = FloatArray(8) { if (it == 0) 1f else 0f }
                    }
                    val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api, vision = VisionBackend {
                        calls++; VisionOutcome.Success(VisionSuccess("ocr", "fixture"))
                    }, visionModelFingerprint = "fixture-vision")
                    val kb = if (async) repo.createApiKnowledgeBase("fixture", binding) else repo.ensureDefaultBase()
                    val bytes = epub(visual)
                    val job = if (async) repo.importBytesCancellable("book.epub", "application/epub+zip", bytes,
                        false, knowledgeBaseId = kb, embeddingIsApi = true, embeddingConsent = true) else repo.importBytes("book.epub", "application/epub+zip", bytes,
                        false, knowledgeBaseId = kb)
                    assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, job.stage)
                    assertTrue(repo.retrieve("waiting", "epub fixture", knowledgeBaseIds = listOf(kb)).hits.isEmpty())
                    val failed = repo.resumeImport(job.id, visionConfigured = true)
                    assertEquals(ImportStage.AWAITING_UPLOAD_CONSENT, failed.stage)
                    assertEquals(ImportStage.FAILED, repo.grantVisionConsent(job.id).stage)
                    assertEquals(0, calls)
                }
            }
        }
    }

    @Test fun explicitTextOnlyAcceptanceRetainsGapStatusAndOldFingerprintIsNotReused() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink())
            val kb = repo.ensureDefaultBase()
            val bytes = epub("<svg><rect/></svg>")
            val waiting = repo.importBytes("book.epub", "application/epub+zip", bytes, false, knowledgeBaseId = kb)
            assertEquals(ImportStage.READY_WITH_VISUAL_GAPS, repo.acceptTextOnlyVisualGaps(waiting.id).stage)
            assertTrue(repo.retrieve("gapped", "epub fixture", knowledgeBaseIds = listOf(kb)).hits.isNotEmpty())
            // Simulate a persisted v3 full-READY document that predated visual detection.
            db.execute("UPDATE document_versions SET parser_fingerprint='epub-xml-v3',status='READY'")
            val reimport = repo.importBytes("book.epub", "application/epub+zip", bytes, false, knowledgeBaseId = kb)
            assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, reimport.stage)
            assertTrue(reimport.hasImages)
        }
    }

    private fun epub(visual: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, text) in listOf("mimetype" to "application/epub+zip", "OPS/ch.xhtml" to
                "<html><body><p>epub fixture searchable text</p>$visual</body></html>")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()
}
