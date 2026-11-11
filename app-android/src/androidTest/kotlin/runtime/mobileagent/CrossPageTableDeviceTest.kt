// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.AndroidPdfRendererAdapter
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Tests row/page retention after labelled local Vision responses, never remote OCR accuracy. */
@RunWith(AndroidJUnit4::class)
class CrossPageTableDeviceTest {
    private val rows = listOf(listOf("Harbor" to 2, "Mountain" to 9, "Garden" to 5),
        listOf("Island" to 7, "Forest" to 4, "Desert" to 6))

    @Test(timeout = 480_000)
    fun distinctTablePagesRetainAllLabelledCellsAndSourceCitations() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "cross-page-table-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val pages = ConcurrentHashMap.newKeySet<Int>()
        val imageBytes = ConcurrentHashMap<String, ByteArray>()
        val calls = AtomicInteger()
        val source = tablePdf("labelled")
        assertTrue(PdfParser.parse(source).needsVision)
        try {
            OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
                AndroidContextSqlite(context, "tables.db").use { db ->
                    Migrations.apply(db)
                    val vision = VisionBackend { input ->
                        check(input.beforeDispatch())
                        val page = requireNotNull(input.page)
                        pages += page
                        calls.incrementAndGet()
                        imageBytes[input.assetHash] = input.bytes.copyOf()
                        val table = "|Destination|Delivery days|\n|---|---|\n" +
                            rows[page - 1].joinToString("\n") { "|" + it.first + "|" + it.second + " days|" }
                        VisionOutcome.Success(VisionSuccess("", "Delivery timetable continuation page $page", tableMarkdown = table))
                    }
                    val repo = KnowledgeRepository(db, CasBlobSink(File(context.filesDir, "cas")), embedder,
                        vision = vision, visionModelFingerprint = "labelled-tables-v1",
                        pdfRasterizer = AndroidPdfRendererAdapter(context),
                        vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(context.cacheDir, "ann"))
                    try {
                        val kb = repo.createKnowledgeBase("Table page retention")
                        val job = repo.importBytes("table.pdf", "application/pdf", source, true, kb, visionConsent = true)
                        assertEquals("Import error: " + job.error, ImportStage.READY, job.stage)
                        assertEquals(setOf(1, 2), pages)
                        assertTrue("Distinct images must not deduplicate together", imageBytes.size >= 2)
                        var retained = 0
                        rows.forEachIndexed { pageIndex, pageRows ->
                            pageRows.forEach { (destination, days) ->
                                val result = repo.retrieve("table-$destination", "$destination delivery days", 8, listOf(kb))
                                assertTrue(result.hits.any { it.documentId == job.documentId && it.page == pageIndex + 1 &&
                                    it.text.contains(destination) && it.text.contains("$days days") })
                                retained++
                                assertTrue(result.citations.any { it.documentId == job.documentId && it.page == pageIndex + 1 })
                                result.citations.forEach { citation ->
                                    assertFalse(repo.locateCitation(citation).removed)
                                    val bytes = requireNotNull(repo.evidenceBytes(citation)).second
                                    assertTrue(bytes.contentEquals(source) || imageBytes.values.any { it.contentEquals(bytes) })
                                }
                            }
                        }
                        val before = calls.get()
                        val missing = repo.importBytes("missing-vision.pdf", "application/pdf", tablePdf("unconfigured"), false, kb)
                        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, missing.stage)
                        assertEquals(before, calls.get())
                        assertTrue(repo.retrieve("missing", "unconfigured", 8, listOf(kb)).hits.none { it.documentId == missing.documentId })
                        val metric = JSONObject().put("metric", "local-labelled-table-row-retention")
                            .put("expectedRows", 6).put("retainedRows", retained).put("sourcePages", 2)
                            .put("visionCalls", calls.get()).put("sourceSha256", sha256Hex(source))
                            .put("visionBackend", "deterministic labels; remote OCR not evaluated")
                        File(base.filesDir, "cross-page-table-retention.json").writeText(metric.toString(2))
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                            putString("tableMetric", metric.toString())
                        })
                    } finally { repo.closeVectorIndexes() }
                }
            }
        } finally { root.deleteRecursively() }
    }

    private fun tablePdf(marker: String): ByteArray {
        val pdf = PdfDocument()
        try {
        rows.forEachIndexed { pageIndex, pageRows ->
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f }
                canvas.drawText("Delivery table - page " + (pageIndex + 1), 20f, 40f, ink)
                canvas.drawText("Destination        Days", 20f, 90f, ink)
                pageRows.forEachIndexed { rowIndex, (destination, days) ->
                    val y = 145f + rowIndex * 65
                    canvas.drawText(destination, 25f, y, ink)
                    canvas.drawText(days.toString(), 285f, y, ink)
                    canvas.drawLine(20f, y + 15f, 375f, y + 15f, ink)
                }
                canvas.drawText(marker, 20f, 520f, ink)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, pageIndex + 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(page)
            } finally { bitmap.recycle() }
        }
        return ByteArrayOutputStream().use { output -> pdf.writeTo(output); output.toByteArray() }
        } finally { pdf.close() }
    }
}
