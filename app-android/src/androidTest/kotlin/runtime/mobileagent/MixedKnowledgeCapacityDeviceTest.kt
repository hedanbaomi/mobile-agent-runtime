// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
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
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** 500 real files, 300-500 MB, real local embeddings; labelled Vision port is NOT OCR evaluation. */
@RunWith(AndroidJUnit4::class)
class MixedKnowledgeCapacityDeviceTest {
    @Test(timeout = 3_600_000)
    fun fiveHundredMixedSourcesPublishWithoutOmissionsAtBoundedConcurrency() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "mixed-capacity-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val seenImages = ConcurrentHashMap.newKeySet<String>()
        val vision = VisionBackend { input ->
            check(input.beforeDispatch())
            calls.incrementAndGet()
            seenImages += input.assetHash
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                // Bounded overlapping local work exercises actual lane accounting, without HTTP.
                Thread.sleep(150)
                VisionOutcome.Success(VisionSuccess("", "Synthetic shipping illustration; preserve source image evidence"))
            } finally { active.decrementAndGet() }
        }
        val observation = CapacityObservation(base, root)
        val manifest = JSONArray()
        try {
            OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
                AndroidContextSqlite(context, "mixed.db").use { db ->
                    Migrations.apply(db)
                    val repo = KnowledgeRepository(db, CasBlobSink(File(context.filesDir, "cas")), embedder,
                        vision = vision, visionModelFingerprint = "capacity-local-labelled-v1",
                        vectorIndexFactory = UsearchVectorIndexFactory(),
                        vectorIndexDirectory = File(context.cacheDir, "ann"))
                    try {
                        val kb = repo.createKnowledgeBase("Synthetic mixed capacity")
                        val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "500 files, local labelled image processing")
                        var sourceBytes = 0L
                        val stagingStart = SystemClock.elapsedRealtime()
                        repeat(500) { index ->
                            val image = index % 5 < 3
                            val bytes = if (image) noisePng(index) else
                                "Warehouse record $index: package identifier CAPACITY$index. 商品采用可回收包装，退款需要保留购买凭证。".toByteArray()
                            val name = "sample-$index." + if (image) "png" else "txt"
                            val type = if (image) "image/png" else "text/plain"
                            sourceBytes += bytes.size
                            manifest.put(JSONObject().put("name", name).put("bytes", bytes.size).put("sha256", sha256Hex(bytes)))
                            val job = repo.importBytes(name, type, bytes, true, kb,
                                pauseAt = ImportStage.COPYING, visionConsent = true)
                            repo.bindJobToBatch(batch, job, name)
                        }
                        assertTrue("Mixed fixture byte range: $sourceBytes", sourceBytes in 300_000_000L..500_000_000L)
                        val stagingMs = SystemClock.elapsedRealtime() - stagingStart
                        repo.authorizeBatchVision(batch, "capacity-local-labelled-v1")
                        val start = SystemClock.elapsedRealtime()
                        repo.processBatch(batch, true)
                        val processMs = SystemClock.elapsedRealtime() - start
                        assertEquals("All text and images must publish", 500, repo.batchProgress(batch).published)
                        assertEquals(ImportBatchState.COMPLETED, repo.findBatch(batch)?.state)
                        assertEquals(300, seenImages.size)
                        assertEquals(300, calls.get())
                        assertEquals(0, active.get())
                        assertTrue("Independent image work did not overlap", peak.get() >= 2)
                        assertTrue(peak.get() <= repo.batchPipelinePolicy(batch).maxConcurrency)
                        for (index in listOf(3, 253, 499)) {
                            val result = repo.retrieve("capacity-$index", "CAPACITY$index", 8, listOf(kb))
                            assertTrue(result.hits.any { it.text.contains("CAPACITY$index.") })
                            result.citations.forEach { assertFalse(repo.locateCitation(it).removed) }
                        }
                        val sourceRows = db.query("SELECT d.blob_hash,b.byte_length FROM documents d JOIN blobs b ON b.hash=d.blob_hash WHERE d.kb_id=?", listOf(kb))
                        assertEquals(500, sourceRows.size)
                        assertEquals(sourceBytes, sourceRows.sumOf { it.long("byte_length") })
                        observation.close()
                        val metric = observation.metric().put("files", 500).put("imageFiles", 300)
                            .put("textFiles", 200).put("sourceBytes", sourceBytes).put("dimension", embedder.dimension)
                            .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                            .put("modelSpace", embedder.spaceId).put("stagingAndFixtureGenerationMs", stagingMs)
                            .put("processingMs", processMs).put("visionCalls", calls.get())
                            .put("observedMaxConcurrentVisionCalls", peak.get())
                            .put("visionBackend", "deterministic-local-labels; no OCR or remote requests")
                            .put("fixtureManifest", manifest)
                        File(base.filesDir, "knowledge-capacity-mixed-500.json").writeText(metric.toString(2))
                    } finally { repo.closeVectorIndexes() }
                }
            }
        } finally {
            try { observation.close() } finally { root.deleteRecursively() }
        }
    }

    private fun noisePng(index: Int): ByteArray {
        val side = 640
        val random = Random(20261110L + index)
        val pixels = IntArray(side * side) { (random.nextInt() and 0x00ffffff) or (0xff shl 24) }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
