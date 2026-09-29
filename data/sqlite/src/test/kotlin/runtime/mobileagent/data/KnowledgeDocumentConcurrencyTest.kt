// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class KnowledgeDocumentConcurrencyTest {
    @Test
    fun visualUnitsAndLocalEmbeddingOverlapButPauseKeepsPublicationAtomic() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val entered = CountDownLatch(2)
            val releaseFirst = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            val embeddingStarted = CountDownLatch(1)
            val calls = AtomicInteger()
            val local = object : TextEmbedder {
                private val hashing = HashingTextEmbedder()
                override val spaceId = hashing.spaceId
                override val dimension = hashing.dimension
                override fun embed(text: String): FloatArray {
                    embeddingStarted.countDown()
                    return hashing.embed(text)
                }
            }
            val backend = VisionBackend { input ->
                assertTrue(input.beforeDispatch())
                calls.incrementAndGet()
                entered.countDown()
                val gate = if (input.page == 1) releaseFirst else releaseSecond
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                VisionOutcome.Success(VisionSuccess("OCR ${input.page}", "diagram ${input.page}"))
            }
            val renderer = PdfPageRasterizer { _, pages ->
                pages.map { RenderedPdfPage(it, byteArrayOf(it.toByte(), 42), "image/png", 200, 200) }
            }
            fun repository() = KnowledgeRepository(db, blobs, embedder = local, vision = backend,
                visionModelFingerprint = "target", pdfRasterizer = renderer)
            val pdf = pdf(2, nativeText = true)
            assertTrue(PdfParser.parse(pdf).pages.any { it.text.contains("Native evidence") })
            val repo = repository()
            val batch = stage(repo, pdf)
            repo.authorizeBatchVision(batch, "target")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val running = executor.submit<Boolean> { repo.processBatch(batch, true) }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "two units of one document must reach Vision together")
                assertTrue(embeddingStarted.await(5, TimeUnit.SECONDS), "local embedding must begin while Vision is blocked")
                assertEquals(2, db.query("SELECT state FROM pipeline_attempts WHERE state='DISPATCHED'").size)
                assertEquals(0, db.query("SELECT id FROM document_versions").size,
                    "native vectors and chunks remain private until all visual units finish")
                releaseFirst.countDown()
                awaitCondition { db.query("SELECT unit_id FROM pipeline_results").size == 1 }
                assertEquals(0, db.query("SELECT id FROM document_versions").size)
                repo.pauseBatch(batch)
                releaseSecond.countDown()
                running.get(15, TimeUnit.SECONDS)
                assertEquals(2, db.query("SELECT unit_id FROM pipeline_results").size)
                assertEquals(0, db.query("SELECT id FROM document_versions").size)
                assertEquals(0, repo.batchProgress(batch).published)
                assertNotNull(repo.resumeBatch(batch))
                val restarted = repository()
                restarted.processBatch(batch, true)
                assertEquals(2, calls.get(), "restart must consume both durable unit results")
                assertEquals(1, restarted.batchProgress(batch).published)
                val version = db.query("SELECT id,status FROM document_versions").single()
                assertEquals("READY", version.string("status"))
                val chunks = db.query("SELECT text FROM chunks WHERE document_version_id=?", listOf(version.string("id")))
                    .map { it.string("text") }
                assertTrue(chunks.any { it.contains("Native evidence") })
                assertTrue(chunks.any { it.contains("OCR 1") })
                assertTrue(chunks.any { it.contains("OCR 2") })
                assertEquals(1, db.query("SELECT id FROM document_versions WHERE status='READY'").size)
            } finally {
                releaseFirst.countDown()
                releaseSecond.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun identicalUnitsSingleFlightWhileDifferentImageRunsConcurrently() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val renderer = PdfPageRasterizer { _, pages ->
                pages.map { page -> RenderedPdfPage(page,
                    if (page == 1) byteArrayOf(1) else byteArrayOf(2), "image/png", 200, 200) }
            }
            val backend = VisionBackend { input ->
                assertTrue(input.beforeDispatch())
                calls += input.page!!
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                VisionOutcome.Success(VisionSuccess("same OCR", "same diagram"))
            }
            val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = backend,
                visionModelFingerprint = "target", pdfRasterizer = renderer)
            val batch = stage(repo, pdf(3, nativeText = false))
            repo.authorizeBatchVision(batch, "target")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val running = executor.submit<Boolean> { repo.processBatch(batch, true) }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "distinct images must overlap")
                assertEquals(setOf(1, 2), calls.toSet())
                release.countDown()
                running.get(15, TimeUnit.SECONDS)
                assertEquals(2, calls.size, "page 3 reuses the byte-identical page 2 response")
                assertEquals(3, db.query("SELECT unit_id FROM pipeline_results").size)
                assertEquals(1, repo.batchProgress(batch).published)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun stage(repo: KnowledgeRepository, pdf: ByteArray): String {
        val kb = repo.ensureDefaultBase()
        val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "same-document concurrency")
        val job = repo.importBytes("pages.pdf", "application/pdf", pdf, false, kb, pauseAt = ImportStage.COPYING)
        repo.bindJobToBatch(batch, job, "pages.pdf")
        return batch
    }

    private fun pdf(count: Int, nativeText: Boolean): ByteArray = buildString {
        append("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        append("2 0 obj << /Type /Pages /Count $count /Kids [")
        (1..count).forEach { append("${it + 2} 0 R ") }
        append("] >> endobj\n")
        for (page in 1..count) {
            val content = buildString {
                if (nativeText) append("BT /F1 12 Tf 10 10 Td (Native evidence $page) Tj ET\n")
                append("0 0 100 100 re f\n")
            }
            val resources = if (nativeText) "/Resources << /Font << /F1 ${count * 2 + 3} 0 R >> >>" else ""
            append("${page + 2} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] $resources /Contents ${page + count + 2} 0 R >> endobj\n")
            append("${page + count + 2} 0 obj << /Length ${content.toByteArray().size} >>\nstream\n${content}endstream\nendobj\n")
        }
        if (nativeText) append("${count * 2 + 3} 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n")
        append("trailer << /Root 1 0 R >>\n%%EOF")
    }.toByteArray()

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
