// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
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
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Actual source import and model work, unlike the synthetic generation-member scale fixture. */
@RunWith(AndroidJUnit4::class)
class KnowledgeCapacityDeviceTest {
    @Test(timeout = 1_800_000) fun boundedTextBatchUsesRealModelAndReportsObservedPeakMemory() {
        val count = InstrumentationRegistry.getArguments().getString("capacityFiles")?.toInt() ?: 300
        require(count in setOf(300, 500))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "capacity-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val peakPss = java.util.concurrent.atomic.AtomicInteger()
        val peakNative = java.util.concurrent.atomic.AtomicLong()
        val peakJava = java.util.concurrent.atomic.AtomicLong()
        val stop = java.util.concurrent.CountDownLatch(1)
        val samplingFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val sampler = Thread {
          try {
            while (stop.count > 0) {
                peakPss.accumulateAndGet(Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss, ::maxOf)
                peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                val runtime = Runtime.getRuntime()
                peakJava.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                if (stop.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            }
          } catch (failure: Throwable) { samplingFailure.set(failure) }
        }.apply { isDaemon = true }
        try {
            OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
                AndroidContextSqlite(context, "capacity.db").use { db ->
                    Migrations.apply(db)
                    val repo = KnowledgeRepository(db, CasBlobSink(File(context.filesDir, "cas")), embedder,
                        vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(context.cacheDir, "ann"))
                    try {
                        val kb = repo.createKnowledgeBase("Synthetic text capacity")
                        val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "Real local model capacity")
                        val start = SystemClock.elapsedRealtime()
                        sampler.start()
                        repeat(count) { index ->
                            val text = "Warehouse record $index: package identifier CAPACITY$index. 商品采用可回收包装，退款需要保留购买凭证。"
                            val job = repo.importBytes("sample-$index.txt", "text/plain", text.toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
                            repo.bindJobToBatch(batch, job, "sample-$index.txt")
                        }
                        val stagingMs = SystemClock.elapsedRealtime() - start
                        val processing = SystemClock.elapsedRealtime()
                        repo.processBatch(batch, false)
                        val processMs = SystemClock.elapsedRealtime() - processing
                        assertEquals(count, repo.batchProgress(batch).published)
                        assertEquals(ImportBatchState.COMPLETED, repo.findBatch(batch)?.state)
                        listOf(0, count / 2, count - 1).forEach { index ->
                            val result = repo.retrieve("capacity-$index", "CAPACITY$index", 8, listOf(kb))
                            assertTrue(result.hits.any { it.text.contains("CAPACITY$index.") })
                            result.citations.forEach { assertFalse(repo.locateCitation(it).removed) }
                        }
                        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        val metric = "{\"files\":$count,\"dimension\":${embedder.dimension},\"stagingMs\":$stagingMs,\"processingMs\":$processMs," +
                            "\"sampledPeakPssKb\":${peakPss.get()},\"sampledPeakNativeBytes\":${peakNative.get()},\"sampledPeakJavaBytes\":${peakJava.get()},\"finalDiskBytes\":$bytes}"
                        File(base.filesDir, "knowledge-capacity-$count.json").writeText(metric)
                    } finally {
                        stop.countDown()
                        var interrupted = Thread.interrupted()
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                        try {
                            while (sampler.isAlive && System.nanoTime() < deadline) {
                                try { sampler.join(100) } catch (_: InterruptedException) { interrupted = true }
                            }
                        } finally {
                            try { repo.closeVectorIndexes() }
                            finally { if (interrupted) Thread.currentThread().interrupt() }
                        }
                        assertFalse("Memory sampler did not exit", sampler.isAlive)
                        samplingFailure.get()?.let { throw AssertionError("Memory sampler failed", it) }
                    }
                }
            }
        } finally { stop.countDown(); root.deleteRecursively() }
    }
}
