// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class DocumentPipelineReviewRegressionTest {
    @Test fun selectingNewTargetDoesNotInheritSuccessAndKeepsPaidResult() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("INSERT INTO knowledge_bases(id,name,created_at) VALUES('kb','review','now')")
            db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format) VALUES('doc','kb','source','review.pdf','PDF')")
            db.execute("INSERT INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,updated_at,batch_id,vision_binding_json) VALUES('j','kb','doc','review.pdf','READY',1,'now','b','A')")
            val unit = DocumentUnitPlanner().plan("source", listOf(PlanningPage(1, "", true))).single()
            val store = DocumentPipelineStore(db)
            store.materialize("j", "source", unit.plannerVersion, listOf(unit))
            store.saveResult("j", unit, "A", "cache-A", "asset-A", VisionSuccess("ocr", "description"))
            store.published("j", PIPELINE_CHUNK_VERSION)
            store.selectTarget("j", "B")
            assertEquals("PLANNED", db.query("SELECT state FROM pipeline_units WHERE job_id='j'").single().string("state"))
            assertEquals(1, store.progress("b").pending)
            assertEquals(0, store.progress("b").succeeded)
            assertEquals(0, store.progress("b").published)
            assertNotNull(store.result("j", unit.unitId, "A"))
            assertTrue(db.query("SELECT * FROM pipeline_attempts").isEmpty(), "Preview must not create attempts")
            db.execute("UPDATE import_jobs SET vision_binding_json='B' WHERE id='j'")
            store.selectTarget("j", "A")
            assertEquals(1, store.progress("b").succeeded)
            assertEquals(0, store.progress("b").published, "Switching back still needs local publication")
            assertTrue(db.query("SELECT * FROM pipeline_attempts").isEmpty())
        }
    }

    @Test fun aSuccessfulAttemptWithoutCurrentResultCannotMarkTargetSucceeded() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val store = DocumentPipelineStore(db)
            val unit = DocumentUnitPlanner().plan("source", listOf(PlanningPage(1, "", true))).single()
            store.materialize("j", "source", unit.plannerVersion, listOf(unit))
            store.prepare("j", unit, "b", "B", "cache-B", "request-B")
            store.settle("request-B", PipelineAttemptState.SUCCEEDED, VisionDiagnosticMetadata(inputTokens = 3, outputTokens = 2))
            store.selectTarget("j", "B")
            assertEquals("PLANNED", db.query("SELECT state FROM pipeline_units").single().string("state"))
            assertEquals("SUCCEEDED", db.query("SELECT state FROM pipeline_attempts").single().string("state"))
        }
    }

    @Test fun targetSelectionDoesNotClearUnacknowledgedUnknown() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val store = DocumentPipelineStore(db)
            val unit = DocumentUnitPlanner().plan("source", listOf(PlanningPage(1, "", true))).single()
            store.materialize("j", "source", unit.plannerVersion, listOf(unit))
            store.prepare("j", unit, "b", "A", "cache-A", "request-A")
            store.settle("request-A", PipelineAttemptState.UNKNOWN_OUTCOME, VisionDiagnosticMetadata(dispatched = true))
            store.selectTarget("j", "B")
            assertEquals("UNKNOWN_OUTCOME", db.query("SELECT state FROM pipeline_units").single().string("state"))
            assertThrows(IllegalStateException::class.java) { store.prepare("j", unit, "b", "B", "cache-B", "request-B") }
            assertEquals(1, db.query("SELECT * FROM pipeline_attempts").size)
            assertTrue(db.query("SELECT * FROM pipeline_retry_permits").isEmpty())
        }
    }

    @Test fun blankRequestSlicesAndLocalRebuildNeverDuplicateTheWholePage() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val contexts = mutableListOf<String>()
            val renderer = renderer()
            val backend = VisionBackend { input ->
                assertTrue(input.beforeDispatch())
                contexts += input.surroundingText
                VisionOutcome.Success(VisionSuccess("ocr", "description"), VisionDiagnosticMetadata(dispatched = true, inputTokens = 5, outputTokens = 2))
            }
            fun repository(chunk: String = PIPELINE_CHUNK_VERSION) = KnowledgeRepository(
                db, blobs, vision = backend, pdfRasterizer = renderer, visionModelFingerprint = "target", chunkVersion = chunk,
            )
            val repo = repository()
            val source = "A" + " ".repeat(30_000) + "B"
            val (batch, _) = stage(repo, complexPdf(source))
            repo.processBatch(batch, true)
            assertTrue(contexts.size > 1)
            assertEquals(source, contexts.joinToString(""))
            assertTrue(contexts.all { it.length <= DocumentUnitPlanner.MAX_REQUEST_TEXT_CHARS })
            fun contextChunks() = db.query("SELECT c.text FROM chunks c JOIN documents d ON d.active_version_id=c.document_version_id WHERE c.source_span LIKE '%part:context%' ORDER BY c.text").map { it.string("text") }
            assertEquals(listOf("A", "B"), contextChunks())
            val count = contexts.size
            assertEquals(1, repository("review-rebuild-v2").rebuildBatchLocalChunks(batch))
            assertEquals(count, contexts.size)
            assertEquals(listOf("A", "B"), contextChunks())
        }
    }

    @Test fun textOverCapacityProducesFailedJobWithZeroRenderingOrProviderAttempts() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            var renders = 0
            var requests = 0
            val repo = KnowledgeRepository(db, blobs, vision = VisionBackend {
                requests++
                error("Over-capacity page must not reach provider")
            }, pdfRasterizer = renderer { renders++ }, visionModelFingerprint = "target")
            val (batch, job) = stage(repo, complexPdf("x".repeat(600_000)))
            repo.processBatch(batch, true)
            val saved = db.query("SELECT stage,error FROM import_jobs WHERE id=?", listOf(job)).single()
            assertEquals("FAILED", saved.string("stage"))
            assertTrue(saved.string("error").contains("PIPELINE_TEXT_LIMIT_EXCEEDED"))
            assertEquals(0, renders)
            assertEquals(0, requests)
            assertTrue(db.query("SELECT * FROM pipeline_attempts").isEmpty())
            assertTrue(db.query("SELECT * FROM vision_attempts").isEmpty())
            assertEquals(0, repo.batchPipelineProgress(batch).published)
        }
    }

    private fun stage(repo: KnowledgeRepository, bytes: ByteArray): Pair<String, String> {
        val kb = repo.ensureDefaultBase()
        val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "review-synthetic")
        val job = repo.importBytes("review.pdf", "application/pdf", bytes, false, kb, pauseAt = ImportStage.COPYING)
        repo.bindJobToBatch(batch, job, "review.pdf")
        repo.authorizeBatchVision(batch, "target")
        return batch to job.id
    }

    private fun renderer(onRender: () -> Unit = {}) = object : PdfPageRasterizer, PdfUnitRasterizer {
        override fun render(pdfBytes: ByteArray, pages: List<Int>) = pages.map {
            onRender()
            RenderedPdfPage(it, byteArrayOf(1, 2, 3), "image/png", 100, 100)
        }
        override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage {
            onRender()
            return RenderedPdfPage(unit.page, byteArrayOf(1, 2, 3), "image/png", 100, 100)
        }
    }

    private fun complexPdf(text: String): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val content = "BT /F1 12 Tf 10 10 Td ($escaped) Tj ET\n" + "0 0 10 10 re f\n".repeat(12)
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n" +
            "2 0 obj << /Type /Pages /Count 1 /Kids [3 0 R] >> endobj\n" +
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >> endobj\n" +
            "4 0 obj << /Length ${content.toByteArray().size} >>\nstream\n${content}endstream\nendobj\n" +
            "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF").toByteArray()
    }
}
