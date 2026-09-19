// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.VectorIndexCache
import runtime.mobileagent.knowledge.VectorIndexSnapshotException
import runtime.mobileagent.knowledge.VectorIndexSnapshotIdentity
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndex
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/**
 * Index reliability at 1k/10k/50k chunks on real USearch JNI, plus the
 * production KnowledgeRepository snapshot path (same db, same cacheDir,
 * restart / lost file / corrupt file).
 *
 * Performance numbers are only RECORDED, never asserted: this test asserts the
 * invariants that must hold at every scale (exact counts, deterministic
 * results, snapshot round-trip identity, fail-closed corruption, SQLite as the
 * only truth).  Machine readable metrics go to logcat (tag [TAG], prefix
 * "INDEX_SCALE_METRIC ") and to <filesDir>/index-scale-metrics.jsonl.
 *
 * Every number must come from a real owned emulator run; this test does not
 * claim any threshold and is not a release gate by itself.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeIndexScaleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dimension: Int
        get() = InstrumentationRegistry.getArguments().getString("indexScaleDimension")?.toIntOrNull() ?: 384

    private val topK = 8
    private val queryCount = 24

    @Test(timeout = 180_000)
    fun scale1k() = runScale(1_000)

    @Test(timeout = 420_000)
    fun scale10k() = runScale(10_000)

    // Includes three full constructions (initial, rebuild, cache miss).
    // Unoptimized Debug JNI on an emulator can exceed 20 minutes at 50k;
    // timings are recorded rather than treated as a performance threshold.
    @Test(timeout = 3_600_000)
    fun scale50k() = runScale(50_000)

    /**
     * Build -> query -> save -> load -> rebuild at [count] chunks, with the
     * snapshot round-trip required to reproduce the built index' exact top-k.
     */
    private fun runScale(count: Int) {
        val dim = dimension
        val space = "index-scale-d$dim"
        val dir = File(context.cacheDir, "index-scale-$count-${UUID.randomUUID()}").apply { mkdirs() }
        val snapshot = File(dir, "scale-$count.idx")
        val queries = (0 until queryCount).map { vectorFor("query-$count-$it", dim) }
        val heapBefore = usedHeapBytes()
        val nativeBefore = nativeHeapBytes()

        val built = UsearchVectorIndex(space, dim, count)
        var expectedTop: List<List<String>> = emptyList()
        var saveMs = 0L
        var loadMs = 0L
        var rebuildMs = 0L
        var queryP50 = 0L
        var queryP95 = 0L
        var snapshotBytes = 0L
        var identity: VectorIndexSnapshotIdentity? = null
        var javaAfterBuild = 0L
        var nativeAfterBuild = 0L
        val memoryAfterBuild = Debug.MemoryInfo()
        try {
            assertThrows(IllegalArgumentException::class.java) { built.add("rejected-nan", FloatArray(dim) { Float.NaN }) }
            val buildMs = elapsed {
                for (index in 0 until count) built.add(idFor(index), vectorFor(idFor(index), dim))
            }
            Log.i(TAG, "PHASE chunks=$count buildMs=$buildMs vectors=${built.vectorCount}")
            assertEquals(count, built.vectorCount)
            assertThrows(IllegalArgumentException::class.java) { built.search(FloatArray(dim) { Float.NaN }, topK) }
            expectedTop = queries.map { query -> built.search(query, topK).map { it.first } }
            // Repeated query on one handle must be identical (deterministic).
            assertEquals(expectedTop.first(), built.search(queries.first(), topK).map { it.first })
            val queryTimings = queries.map { query -> elapsed { built.search(query, topK) } }.sorted()
            queryP50 = queryTimings[queryTimings.size / 2]
            queryP95 = queryTimings[(queryTimings.size * 95) / 100]
            javaAfterBuild = usedHeapBytes()
            nativeAfterBuild = nativeHeapBytes()
            Debug.getMemoryInfo(memoryAfterBuild)
            saveMs = elapsed { identity = built.saveSnapshot(snapshot) }
            Log.i(TAG, "PHASE chunks=$count saveMs=$saveMs")
            snapshotBytes = snapshot.length()
            assertTrue("snapshot must be written", snapshot.isFile && snapshotBytes > 0L)

            // Release the first handle before restoring a second one: at 50k a
            // live handle holds a Kotlin vector copy plus a native graph, so
            // keeping both would roughly double peak memory for no benefit.
            built.close()

            // Load into a fresh handle: same vectors, same graph, same top-k.
            val loaded = UsearchVectorIndex(space, dim, count)
            try {
                loadMs = elapsed { loaded.loadSnapshot(snapshot, requireNotNull(identity)) }
                Log.i(TAG, "PHASE chunks=$count loadMs=$loadMs")
                assertEquals(count, loaded.vectorCount)
                queries.forEachIndexed { index, query ->
                    assertEquals(expectedTop[index], loaded.search(query, topK).map { it.first })
                }
            } finally {
                loaded.close()
            }

            // Rebuild from the same derived truth and require the same results.
            val rebuilt = UsearchVectorIndex(space, dim, count)
            try {
                rebuildMs = elapsed {
                    for (index in 0 until count) rebuilt.add(idFor(index), vectorFor(idFor(index), dim))
                }
                Log.i(TAG, "PHASE chunks=$count rebuildMs=$rebuildMs")
                assertEquals(count, rebuilt.vectorCount)
                queries.forEachIndexed { index, query ->
                    assertEquals(expectedTop[index], rebuilt.search(query, topK).map { it.first })
                }
            } finally {
                rebuilt.close()
            }

            val corrupt = File(dir, "corrupt-$count.idx")
            snapshot.copyTo(corrupt, overwrite = true)
            RandomAccessFile(corrupt, "rw").use { it.setLength((it.length() * 4L) / 10L) }
            val victim = UsearchVectorIndex(space, dim, count)
            try {
                assertThrows(VectorIndexSnapshotException::class.java) {
                    victim.loadSnapshot(corrupt, requireNotNull(identity))
                }
                // Fail-closed: the rejected handle must not be searchable.
                assertEquals(0, victim.vectorCount)
                assertThrows(IllegalStateException::class.java) { victim.search(queries.first(), topK) }
            } finally {
                victim.close()
            }
            val missing = UsearchVectorIndex(space, dim, count)
            try {
                assertThrows(VectorIndexSnapshotException::class.java) {
                    missing.loadSnapshot(File(dir, "absent-$count.idx"), requireNotNull(identity))
                }
                assertEquals(0, missing.vectorCount)
            } finally {
                missing.close()
            }

            val cache = measureSingleBuildCache(count, space, dim)
            record(
                "scale-$count",
                linkedMapOf(
                    "chunks" to count,
                    "dimension" to dim,
                    "buildIncludesSyntheticVectorGeneration" to true,
                    "liveIndexCountForMemorySample" to 1,
                    "cacheLeaseAcquisitions" to cache.second,
                    "cacheSubsequentReuses" to (cache.second - 1L),
                    "buildMs" to buildMs,
                    "saveMs" to saveMs,
                    "loadMs" to loadMs,
                    "rebuildMs" to rebuildMs,
                    "queryP50Ms" to queryP50,
                    "queryP95Ms" to queryP95,
                    "snapshotBytes" to snapshotBytes,
                    "javaHeapBuildDeltaBytes" to (javaAfterBuild - heapBefore),
                    "nativeHeapBuildDeltaBytes" to (nativeAfterBuild - nativeBefore),
                    // Absolute samples make GC-sensitive deltas interpretable.
                    // PSS is process-wide and is not an index-only peak.
                    "javaHeapAfterBuildBytes" to javaAfterBuild,
                    "nativeHeapAfterBuildBytes" to nativeAfterBuild,
                    "processPssAfterBuildKiB" to memoryAfterBuild.totalPss,
                    "nativePssAfterBuildKiB" to memoryAfterBuild.nativePss,
                    "snapshotHashVerified" to true,
                    "corruptRejected" to true,
                    "missingRejected" to true,
                    "cacheMembers" to count,
                    "cacheBuilds" to cache.first,
                    "cacheReuseHits" to cache.second,
                    "cacheIncompleteRejects" to cache.third,
                ),
            )
            Log.i(TAG, "scale=$count build_ms=$buildMs load_ms=$loadMs rebuild_ms=$rebuildMs " +
                "query_p50_ms=$queryP50 query_p95_ms=$queryP95 snapshot_bytes=$snapshotBytes")
        } finally {
            built.close()
            dir.deleteRecursively()
        }
    }

    /**
     * Duplicate-build count for one cache key: repeated getOrBuild must build
     * once and reuse afterwards, and the unified cache must report it.
     */
    private fun measureSingleBuildCache(count: Int, space: String, dim: Int): Triple<Long, Long, Long> {
        // The FULL requested member set: a 512-member cache probe would report
        // a 50k "cacheBuilds" metric that was never measured at 50k.
        val members = (0 until count).map { idFor(it) }.toSet()
        val cache = VectorIndexCache(null, maxEntries = 4)
        try {
            val key = VectorIndexCache.Key("kb-scale-$count", space, dim, "g1")
            repeat(3) {
                cache.getOrBuild(key, members) {
                    UsearchVectorIndex(space, dim, members.size).also { index ->
                        members.forEach { id -> index.add(id, vectorFor(id, dim)) }
                    }
                }.use { lease -> lease.index.search(vectorFor(idFor(0), dim), topK) }
            }
            val stats = cache.stats()
            assertEquals("one build per key", 1L, stats.builds)
            // Existing counter includes the first lease handed out after build.
            assertEquals("three acquired leases; only one construction", 3L, stats.reuseHits)
            assertEquals(0L, stats.incompleteRejects)
            return Triple(stats.builds, stats.reuseHits, stats.incompleteRejects)
        } finally {
            cache.close()
        }
    }

    /**
     * Production restart path: SQLite is the only truth, the snapshot is
     * disposable in cacheDir.  A restart with the same db + same directory must
     * LOAD the snapshot (not rebuild), while a lost or corrupt file must be
     * rejected and rebuilt with identical results.
     */
    @Test(timeout = 300_000)
    fun repositoryRestartLoadsSnapshotAndRecoversFromLossOrCorruption() {
        val dim = 64
        val embedder = HashingTextEmbedder(dimension = dim)
        val root = File(context.filesDir, "index-scale-repo-${UUID.randomUUID()}").apply { mkdirs() }
        val indexDir = File(root, "knowledge-index").apply { mkdirs() }
        val casDir = File(root, "cas")
        val dbName = "index-scale-${UUID.randomUUID()}.db"
        val connections = mutableListOf<AndroidContextSqlite>()
        fun openRepository(): KnowledgeRepository {
            connections.lastOrNull()?.close()
            val connection = AndroidContextSqlite(context, dbName)
            connections += connection
            Migrations.apply(connection)
            return KnowledgeRepository(
                connection,
                CasBlobSink(casDir),
                embedder,
                vectorIndexFactory = UsearchVectorIndexFactory(),
                vectorIndexDirectory = indexDir,
            )
        }
        fun snapshots() = indexDir.listFiles()?.filter { it.name.endsWith(".idx") }.orEmpty()

        val query = "index scale reliability"
        try {
            val repository = openRepository()
            val kb = repository.createKnowledgeBase("index scale restart")
            val text = buildString {
                repeat(60) { line ->
                    append("Line ").append(line)
                        .append(" carries index scale reliability token and other filler business text.\n")
                }
            }
            val imported = repository.importBytes("scale.txt", "text/plain", text.toByteArray(Charsets.UTF_8), false, kb)
            assertEquals("import must be READY: ${imported.error}", runtime.mobileagent.knowledge.ImportStage.READY, imported.stage)
            val first = repository.retrieve("scale-run-1", query, topK, listOf(kb))
            assertTrue("the fixture must retrieve through the vector path", first.hits.isNotEmpty())
            val expectedChunks = first.hits.map { it.chunkId }
            assertEquals("snapshot must be persisted", 1, snapshots().size)
            repository.closeVectorIndexes()

            // 1) restart with the same db + same cacheDir: load, do not rebuild.
            val reopened = openRepository()
            val second = reopened.retrieve("scale-run-2", query, topK, listOf(kb))
            assertEquals("a restart must return the same evidence", expectedChunks, second.hits.map { it.chunkId })
            val loadedStats = reopened.vectorIndexSnapshotStats()
            assertEquals("the snapshot must be loaded, not rebuilt", 0L, loadedStats.rebuilds)
            assertTrue("the snapshot must be loaded", loadedStats.loads >= 1L)
            reopened.closeVectorIndexes()
            record(
                "repository-restart",
                linkedMapOf(
                    "chunks" to expectedChunks.size,
                    "repoLoads" to loadedStats.loads,
                    "repoRebuilds" to loadedStats.rebuilds,
                    "repoRejectedSnapshots" to loadedStats.rejectedSnapshots,
                    "sameEvidence" to true,
                ),
            )

            // 2) corrupt snapshot: rejected, rebuilt from SQLite, same evidence.
            assertTrue("a snapshot must exist before corruption", snapshots().isNotEmpty())
            snapshots().forEach { file -> RandomAccessFile(file, "rw").use { it.setLength(it.length() / 2) } }
            val afterCorruption = openRepository()
            val third = afterCorruption.retrieve("scale-run-3", query, topK, listOf(kb))
            assertEquals(expectedChunks, third.hits.map { it.chunkId })
            val corruptStats = afterCorruption.vectorIndexSnapshotStats()
            assertTrue("a corrupt snapshot must be rejected", corruptStats.rejectedSnapshots >= 1L)
            assertTrue("a corrupt snapshot must be rebuilt", corruptStats.rebuilds >= 1L)
            afterCorruption.closeVectorIndexes()

            // 3) lost snapshot: rebuilt from SQLite truth, same evidence.
            snapshots().forEach { it.delete() }
            val afterLoss = openRepository()
            val fourth = afterLoss.retrieve("scale-run-4", query, topK, listOf(kb))
            assertEquals(expectedChunks, fourth.hits.map { it.chunkId })
            assertTrue("a lost snapshot must be rebuilt", afterLoss.vectorIndexSnapshotStats().rebuilds >= 1L)
            val rebuiltSnapshot = snapshots()
            assertTrue("a rebuild must repersist a usable snapshot", rebuiltSnapshot.size == 1)
            afterLoss.closeVectorIndexes()

            // 4) the repersisted snapshot loads again on the next restart.
            val reloaded = openRepository()
            val fifth = reloaded.retrieve("scale-run-5", query, topK, listOf(kb))
            assertEquals(expectedChunks, fifth.hits.map { it.chunkId })
            assertTrue("the repersisted snapshot must load", reloaded.vectorIndexSnapshotStats().loads >= 1L)
            reloaded.closeVectorIndexes()
        } finally {
            connections.forEach { it.close() }
            root.deleteRecursively()
        }
    }

    private fun idFor(index: Int) = "chunk-%08d".format(index)

    /** Deterministic, normalized, self-authored vector; never user data. */
    private fun vectorFor(id: String, dim: Int): FloatArray {
        val random = Random(id.hashCode().toLong() * 31L + dim)
        val vector = FloatArray(dim) { random.nextFloat() * 2f - 1f }
        var norm = 0.0
        for (value in vector) norm += value.toDouble() * value.toDouble()
        val scale = sqrt(norm).toFloat().coerceAtLeast(1e-6f)
        for (index in vector.indices) vector[index] /= scale
        return vector
    }

    private inline fun elapsed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()

    private val metricLock = Any()

    private fun record(scenario: String, fields: Map<String, Any>) {
        val json = buildString {
            append("{\"scenario\":\"").append(scenario).append('"')
            fields.forEach { (key, value) -> append(",\"").append(key).append("\":").append(value) }
            append('}')
        }
        Log.i(TAG, "$METRIC_PREFIX$json")
        runCatching {
            synchronized(metricLock) {
                File(context.filesDir, METRICS_FILE).appendText(json + "\n")
            }
        }
    }

    private companion object {
        const val TAG = "IndexScaleMetric"
        const val METRIC_PREFIX = "INDEX_SCALE_METRIC "
        const val METRICS_FILE = "index-scale-metrics.jsonl"
    }
}
