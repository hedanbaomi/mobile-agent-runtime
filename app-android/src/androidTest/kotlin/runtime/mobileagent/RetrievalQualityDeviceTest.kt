// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Self-authored labelled corpus; real bundled semantic model, SQLite and JNI, no provider requests. */
@RunWith(AndroidJUnit4::class)
class RetrievalQualityDeviceTest {
    @Test(timeout = 480_000) fun duplicateVisualPagesRetainCitationsAndMissingVisionStaysWaiting() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "table-quality-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        // Real image objects force the visual path; table rows are deterministic port labels, not OCR truth.
        val source = runtime.mobileagent.knowledge.PdfParser.writeTwoPagePdfWithImageXObject(jpeg)
        assertTrue(runtime.mobileagent.knowledge.PdfParser.parse(source).needsVision)
        val visualPages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val visualCalls = java.util.concurrent.atomic.AtomicInteger()
        val sentImages = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        try {
            OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
                AndroidContextSqlite(context, "tables.db").use { db ->
                    Migrations.apply(db)
                    // Labelled local Vision output tests omission/plumbing, not provider OCR accuracy.
                    val vision = runtime.mobileagent.knowledge.VisionBackend { input ->
                        check(input.beforeDispatch())
                        visualCalls.incrementAndGet()
                        sentImages[input.assetHash] = input.bytes.copyOf()
                        visualPages += requireNotNull(input.page)
                        val row = if (input.page == 1) "|Harbor|2 days|" else "|Mountain|9 days|"
                        runtime.mobileagent.knowledge.VisionOutcome.Success(runtime.mobileagent.knowledge.VisionSuccess(
                            "", "Delivery table continuation page ${input.page}", tableMarkdown = "|Destination|Delivery|\n|---|---|\n$row"))
                    }
                    val repo = KnowledgeRepository(db, CasBlobSink(File(context.filesDir, "cas")), embedder, vision = vision,
                        pdfRasterizer = runtime.mobileagent.storage.AndroidPdfRendererAdapter(context),
                        vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(context.cacheDir, "ann"))
                    try {
                        val kb = repo.createKnowledgeBase("Cross-page evidence")
                        val job = repo.importBytes("routes.pdf", "application/pdf", source, true, kb, visionConsent = true)
                        assertEquals(ImportStage.READY, job.stage)
                        // Identical image bytes are deduplicated; cached Vision output must still bind both pages.
                        assertEquals(setOf(1), visualPages)
                        listOf("First native page" to 1, "Second native page" to 2).forEach { (query, page) ->
                            val result = repo.retrieve("table-$page", query, 8, listOf(kb))
                            assertTrue(result.hits.any { it.documentId == job.documentId && it.page == page && it.text.contains(query) })
                            assertTrue(result.citations.isNotEmpty())
                            result.citations.forEach { citation ->
                                val evidence = requireNotNull(repo.evidenceBytes(citation)).second
                                if (citation.assetId == null) assertArrayEquals(source, evidence)
                                else assertTrue(sentImages.values.any { it.contentEquals(evidence) })
                            }
                        }
                        val calls = visualCalls.get()
                        val pending = repo.importBytes("unprocessed-chart.pdf", "application/pdf",
                            runtime.mobileagent.knowledge.PdfParser.writeTextAndVectorPdf("Chart requiring visual evidence"), false, kb)
                        assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, pending.stage)
                        assertEquals(calls, visualCalls.get())
                        assertTrue(repo.retrieve("missing-chart", "Chart requiring visual evidence", 8, listOf(kb)).hits.none { it.documentId == pending.documentId })
                        File(base.filesDir, "retrieval-table-quality.json").writeText(
                            "{\"labelledQueries\":2,\"pageRecallAt8\":1.0,\"sourcePages\":2,\"uniqueImageCalls\":${visualCalls.get()},\"visionBackend\":\"deterministic-local-labels\"}")
                    } finally { repo.closeVectorIndexes() }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test(timeout = 480_000) fun labelledMultilingualQueriesAcrossFourLibrariesRetainEvidenceAndMeasureRecall() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "quality-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val documents = RetrievalQualityCorpus.documents
        try {
            OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
                AndroidContextSqlite(context, "quality.db").use { db ->
                    Migrations.apply(db)
                    val repo = KnowledgeRepository(db, CasBlobSink(File(context.filesDir, "cas")), embedder,
                        vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(context.cacheDir, "ann"))
                    try {
                        val libraries = (0..3).map { repo.createKnowledgeBase("Quality $it") }
                        val importStart = SystemClock.elapsedRealtime()
                        val ids = documents.mapIndexed { index, pair ->
                            repo.importBytes("sample-$index.txt", "text/plain", pair.first.toByteArray(), false, libraries[index % 4]).also {
                                assertEquals(ImportStage.READY, it.stage)
                            }.documentId
                        }
                        val importMs = SystemClock.elapsedRealtime() - importStart
                        var recall = 0
                        var reciprocalRank = 0.0
                        val times = mutableListOf<Long>()
                        val warmTimes = mutableListOf<Long>()
                        val singleTimes = mutableListOf<Long>()
                        documents.forEachIndexed { index, pair ->
                            val started = SystemClock.elapsedRealtime()
                            val result = repo.retrieve("quality-$index", pair.second, 8, libraries)
                            times += SystemClock.elapsedRealtime() - started
                            val rank = result.hits.indexOfFirst { it.documentId == ids[index] }
                            if (rank >= 0) { recall++; reciprocalRank += 1.0 / (rank + 1) }
                            assertFalse(requireNotNull(result.coverage).partial)
                            result.citations.forEach { citation ->
                                assertFalse(repo.locateCitation(citation).removed)
                                assertTrue(result.hits.any { it.chunkId == citation.chunkId && it.documentId == citation.documentId })
                                val original = repo.evidenceBytes(citation)
                                assertNotNull(original)
                                val sourceIndex = ids.indexOf(citation.documentId)
                                assertTrue(sourceIndex >= 0)
                                assertArrayEquals(documents[sourceIndex].first.toByteArray(), requireNotNull(original).second)
                            }
                            val warmStart = SystemClock.elapsedRealtime()
                            val warm = repo.retrieve("warm-$index", pair.second, 8, libraries)
                            warmTimes += SystemClock.elapsedRealtime() - warmStart
                            assertEquals(result.hits.map { it.chunkId }, warm.hits.map { it.chunkId })
                            val singleStart = SystemClock.elapsedRealtime()
                            val single = repo.retrieve("single-$index", pair.second, 8, listOf(libraries[index % 4]))
                            singleTimes += SystemClock.elapsedRealtime() - singleStart
                            assertTrue(single.hits.any { it.documentId == ids[index] })
                        }
                        // A negative query deliberately has no relevant label. Candidates are not proof of an answer.
                        val negative = repo.retrieve("quality-negative", "The serial number of the spacecraft on Mars", 8, libraries)
                        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                        times.sort()
                        warmTimes.sort()
                        singleTimes.sort()
                        val metric = "{\"queries\":${documents.size},\"recallAt8\":${recall.toDouble()/documents.size}," +
                            "\"mrrAt8\":${reciprocalRank/documents.size},\"p50Ms\":${times[times.size/2]}," +
                            "\"p95Ms\":${times[(times.size*95/100).coerceAtMost(times.lastIndex)]}," +
                            "\"warmP50Ms\":${warmTimes[warmTimes.size/2]},\"singleLibraryP50Ms\":${singleTimes[singleTimes.size/2]}," +
                            "\"importMs\":$importMs,\"dimension\":${embedder.dimension},\"libraries\":4,\"pssKb\":${memory.totalPss}," +
                            "\"nativeBytes\":${Debug.getNativeHeapAllocatedSize()},\"negativeCandidates\":${negative.hits.size}}"
                        Log.i("RetrievalQuality", metric)
                        File(base.filesDir, "retrieval-quality.json").writeText(metric)
                        assertTrue("Recall@8 below reviewed initial floor: $metric", recall >= 12)
                    } finally { repo.closeVectorIndexes() }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
