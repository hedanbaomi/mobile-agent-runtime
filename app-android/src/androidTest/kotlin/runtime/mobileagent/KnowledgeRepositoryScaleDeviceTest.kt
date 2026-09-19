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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.UUID
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.VectorIndexFactory
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndex

/**
 * Real KnowledgeRepository + bundled SQLite + USearch JNI + snapshot directory at
 * 1k/10k/50k members.  Arg `repositoryScaleCount` (default 1000, cap 50000),
 * dimension 384, local HashingTextEmbedder only: no apiEmbedder/vision/network.
 *
 * FIXTURE SEMANTICS: one genuinely imported self-authored text gives a legal KB /
 * document_version / space / READY generation / chunk / embedding / member start.
 * The rest are added INSIDE ONE TEST-DB TRANSACTION by copying that shape:
 * synthetic `chunks` (ordinals past the imported ones), `embeddings` with
 * deterministic normalized blobs whose content_hash equals the chunk's,
 * `generation_members` on the same space/version, and an updated
 * `index_generations.vector_count`.  SYNTHETIC index-truth fixture for
 * scale/restart/corruption only: it claims nothing about real import, chunking or
 * embedding performance, and it mutates a READY generation in place, which
 * production never does.  `chunks_fts` is not extended, so the probe query is
 * answered by the vector channel alone.  Metrics are recorded, never asserted;
 * this is not a release gate.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeRepositoryScaleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dimension = 384
    private val topK = 8

    @Test(timeout = 3_600_000)
    fun repositoryScaleBuildReloadAndSnapshotRecovery() {
        val requested = InstrumentationRegistry.getArguments().getString("repositoryScaleCount")?.toIntOrNull() ?: 1_000
        require(requested in setOf(1_000, 10_000, 50_000)) { "repositoryScaleCount must be 1000, 10000 or 50000" }
        val embedder = HashingTextEmbedder(dimension = dimension)
        val chunkChars = 1_800
        val padding = "证据段落 with unicode context. ".repeat(100)
        val root = File(context.filesDir, "repository-scale-${UUID.randomUUID()}").apply { mkdirs() }
        val snapshotDir = File(root, "knowledge-index").apply { mkdirs() }
        val casDir = File(root, "cas")
        val dbName = "repository-scale-${UUID.randomUUID()}.db"
        val sessions = mutableListOf<Pair<KnowledgeRepository, AndroidContextSqlite>>()
        val nativeIndexes = mutableListOf<UsearchVectorIndex>()
        val phasePss = mutableListOf<Int>()
        fun captureIndex(phase: String) {
            assertEquals("$phase must cover every member", requested, nativeIndexes.last().vectorCount)
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            phasePss += memory.totalPss
            Log.i(TAG, "PHASE members=$requested phase=$phase pssKiB=${memory.totalPss}")
        }

        fun openSession(): Pair<KnowledgeRepository, AndroidContextSqlite> {
            val connection = AndroidContextSqlite(context, dbName)
            Migrations.apply(connection)
            val repository = KnowledgeRepository(
                connection,
                CasBlobSink(casDir),
                embedder,
                vectorIndexFactory = VectorIndexFactory { spaceId, dim, capacity ->
                    UsearchVectorIndex(spaceId, dim, capacity).also { nativeIndexes += it }
                },
                vectorIndexDirectory = snapshotDir,
            )
            return (repository to connection).also { sessions += it }
        }
        fun snapshots() = snapshotDir.listFiles()?.filter { it.name.endsWith(".idx") }.orEmpty()

        try {
            val (repository, connection) = openSession()
            val kb = repository.createKnowledgeBase("repository scale fixture")
            val seed = buildString {
                append("Repository scale seed document about warehouses, invoices and delivery routes.\n")
                repeat(12) { append("Seed paragraph ").append(it).append(" carries ordinary filler prose for chunking.\n") }
            }
            val imported = repository.importBytes("seed.txt", "text/plain", seed.toByteArray(Charsets.UTF_8), false, kb)
            assertEquals("seed import must be READY: ${imported.error}", ImportStage.READY, imported.stage)

            val generation = connection.query(
                "SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kb),
            ).single().string("active_generation_id")
            val space = connection.query(
                "SELECT embedding_space_id FROM knowledge_bases WHERE id = ?", listOf(kb),
            ).single().string("embedding_space_id")
            assertEquals("the fixture must use the local hashing space", embedder.spaceId, space)
            // pinnedReadyGeneration requires state READY and generation space == KB space.
            val generationRow = connection.query(
                "SELECT state, space_id FROM index_generations WHERE id = ?", listOf(generation),
            ).single()
            assertEquals("READY", generationRow.string("state"))
            assertEquals(space, generationRow.string("space_id"))
            val versionId = connection.query(
                "SELECT active_version_id FROM documents WHERE kb_id = ?", listOf(kb),
            ).single().string("active_version_id")
            assertTrue("the seed document must have an active version", versionId.isNotBlank())
            val seededMembers = connection.query(
                "SELECT COUNT(*) AS n FROM generation_members WHERE generation_id = ?", listOf(generation),
            ).single().long("n").toInt()
            val need = requested - seededMembers
            require(need >= 0) { "the seed already has $seededMembers members, above requested $requested" }

            val fixtureStart = SystemClock.elapsedRealtime()
            connection.transaction {
                if (need > 0) {
                    connection.execute(
                        "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ?) " +
                            "INSERT INTO chunks (id, document_version_id, ordinal, text, content_hash, source_span, asset_ids, page) " +
                            "SELECT 'syn-' || printf('%08d', n), ?, 100000 + n, " +
                            "'synthetic filler chunk ' || n || substr(?, 1, ? - length('synthetic filler chunk ' || n)), " +
                            "printf('%064d', n), '', '', NULL FROM seq",
                        listOf(need, versionId, padding, chunkChars),
                    )
                    connection.execute(
                        "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ?) " +
                            "INSERT INTO generation_members (generation_id, chunk_id, space_id, document_version_id) " +
                            "SELECT ?, 'syn-' || printf('%08d', n), ?, ? FROM seq",
                        listOf(need, generation, space, versionId),
                    )
                    val vector = FloatArray(dimension)
                    val blob = ByteArray(dimension * 4)
                    val floats = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for (n in 1..need) {
                        val random = Random(n.toLong() * 2_654_435_761L)
                        var norm = 0.0
                        for (i in 0 until dimension) {
                            val value = random.nextFloat() * 2f - 1f
                            vector[i] = value
                            norm += value.toDouble() * value.toDouble()
                        }
                        val scale = sqrt(norm).toFloat().coerceAtLeast(1e-6f)
                        for (i in 0 until dimension) vector[i] /= scale
                        floats.clear()
                        floats.put(vector)
                        val id = "syn-%08d".format(n)
                        val hash = sha256Hex(("synthetic filler chunk $n" + padding).take(chunkChars).toByteArray(Charsets.UTF_8))
                        connection.execute("UPDATE chunks SET content_hash = ? WHERE id = ?", listOf(hash, id))
                        connection.execute(
                            "INSERT INTO embeddings (chunk_id, space_id, vector_blob, content_hash) VALUES (?, ?, ?, ?)",
                            listOf(id, space, blob.copyOf(), hash),
                        )
                    }
                }
                connection.execute(
                    "UPDATE index_generations SET vector_count = ? WHERE id = ?",
                    listOf(requested.toLong(), generation),
                )
            }
            val fixtureMs = SystemClock.elapsedRealtime() - fixtureStart

            // Precondition before any cache build: SQLite is exactly and consistently N members.
            assertEquals(requested.toLong(), connection.query(
                "SELECT COUNT(*) AS n FROM generation_members WHERE generation_id = ?", listOf(generation),
            ).single().long("n"))
            assertEquals("members must be unique", requested.toLong(), connection.query(
                "SELECT COUNT(DISTINCT chunk_id) AS n FROM generation_members WHERE generation_id = ?", listOf(generation),
            ).single().long("n"))
            assertEquals("every member needs a usable embedding row", requested.toLong(), connection.query(
                "SELECT COUNT(*) AS n FROM generation_members g " +
                    "JOIN embeddings e ON e.chunk_id = g.chunk_id AND e.space_id = g.space_id " +
                    "JOIN chunks c ON c.id = g.chunk_id JOIN documents d ON d.active_version_id = c.document_version_id " +
                    "WHERE g.generation_id = ? AND d.kb_id = ? AND d.deleted_at IS NULL",
                listOf(generation, kb),
            ).single().long("n"))
            assertEquals("content_hash and blob length must match", 0L, connection.query(
                "SELECT COUNT(*) AS n FROM generation_members g " +
                    "JOIN embeddings e ON e.chunk_id = g.chunk_id AND e.space_id = g.space_id " +
                    "JOIN chunks c ON c.id = g.chunk_id " +
                    "WHERE g.generation_id = ? AND (e.content_hash <> c.content_hash OR length(e.vector_blob) <> ?)",
                listOf(generation, dimension * 4),
            ).single().long("n"))
            assertEquals(requested.toLong(), connection.query(
                "SELECT vector_count FROM index_generations WHERE id = ?", listOf(generation),
            ).single().long("vector_count"))

            // A single token that appears in no chunk text (and is not a word any
            // tokenizer can split into one that does), so FTS matches nothing and
            // the probe can only be answered by the vector channel.
            val query = "zzqvectoronlyprobe"
            val (first, buildMs) = timed { repository.retrieve("repo-scale-1", query, topK, listOf(kb)) }
            captureIndex("build")
            assertTrue("the vector channel must serve the synthetic generation", first.hits.isNotEmpty())
            val expectedIds = first.hits.map { it.chunkId }
            val expectedTexts = first.hits.map { it.text }
            val expectedCitationChunks = first.citations.map { it.chunkId }
            val expectedCitationVersions = first.citations.map { it.documentVersionId }
            assertTrue("synthetic members must be searchable", expectedIds.any { it.startsWith("syn-") })
            assertTrue(first.citations.all { !repository.locateCitation(it).removed })
            val (warm, warmQueryMs) = timed { repository.retrieve("repo-scale-warm", query, topK, listOf(kb)) }
            assertEquals(expectedIds, warm.hits.map { it.chunkId })
            assertEquals("warm retrieval must not build again", 1L, repository.vectorIndexStats().builds)
            assertEquals("no member may be dropped as incomplete", 0L, repository.vectorIndexStats().incompleteRejects)
            assertTrue("a snapshot must be persisted", snapshots().isNotEmpty())
            val snapshotBytes = snapshots().sumOf { it.length() }
            assertTrue(
                "the snapshot must carry all $requested members, was $snapshotBytes bytes",
                snapshotBytes >= requested.toLong() * dimension * 4L,
            )

            // 1) Close native indexes AND the SQLite handle, then reopen the same db/cacheDir.
            repository.closeVectorIndexes()
            connection.close()
            val (reloaded, reloadedConnection) = openSession()
            val (second, loadMs) = timed { reloaded.retrieve("repo-scale-2", query, topK, listOf(kb)) }
            captureIndex("reload")
            assertEquals("hit ids must be stable across restart", expectedIds, second.hits.map { it.chunkId })
            assertEquals("source text must be stable across restart", expectedTexts, second.hits.map { it.text })
            assertEquals(expectedCitationChunks, second.citations.map { it.chunkId })
            assertEquals(expectedCitationVersions, second.citations.map { it.documentVersionId })
            val loadStats = reloaded.vectorIndexSnapshotStats()
            assertEquals("a restart must load the snapshot, not rebuild it", 0L, loadStats.rebuilds)
            assertTrue("a restart must load the snapshot", loadStats.loads >= 1L)
            reloaded.closeVectorIndexes()
            reloadedConnection.close()

            // 2) Corrupt snapshot: rejected, rebuilt from SQLite truth, same evidence.
            snapshots().forEach { file -> RandomAccessFile(file, "rw").use { it.setLength(it.length() / 2) } }
            val (corrupted, corruptedConnection) = openSession()
            val (third, corruptMs) = timed { corrupted.retrieve("repo-scale-3", query, topK, listOf(kb)) }
            captureIndex("corrupt-rebuild")
            assertEquals(expectedIds, third.hits.map { it.chunkId })
            assertEquals(expectedCitationChunks, third.citations.map { it.chunkId })
            val corruptStats = corrupted.vectorIndexSnapshotStats()
            assertTrue("a corrupt snapshot must be rejected", corruptStats.rejectedSnapshots >= 1L)
            assertTrue("a corrupt snapshot must be rebuilt", corruptStats.rebuilds >= 1L)
            corrupted.closeVectorIndexes()
            corruptedConnection.close()

            // 3) Lost snapshot: rebuilt from SQLite truth, same evidence.
            snapshots().forEach { it.delete() }
            val (lost, lostConnection) = openSession()
            val (fourth, lostMs) = timed { lost.retrieve("repo-scale-4", query, topK, listOf(kb)) }
            captureIndex("lost-rebuild")
            assertEquals(expectedIds, fourth.hits.map { it.chunkId })
            assertEquals(expectedTexts, fourth.hits.map { it.text })
            val lostStats = lost.vectorIndexSnapshotStats()
            assertTrue("a lost snapshot must be rebuilt", lostStats.rebuilds >= 1L)
            lost.closeVectorIndexes()
            lostConnection.close()

            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            record(
                linkedMapOf(
                    "requestedMembers" to requested, "actualMembers" to requested, "dimension" to dimension,
                    "syntheticChunkChars" to chunkChars,
                    "hitCount" to expectedIds.size, "fixtureInsertMs" to fixtureMs, "buildMs" to buildMs,
                    "warmQueryMs" to warmQueryMs, "maxLivePhasePssKiB" to phasePss.maxOrNull()!!,
                    "reloadLoadMs" to loadMs, "corruptRebuildMs" to corruptMs, "lostRebuildMs" to lostMs,
                    "snapshotBytes" to snapshotBytes, "repoLoads" to loadStats.loads,
                    "repoRebuilds" to (corruptStats.rebuilds + lostStats.rebuilds),
                    "repoRejectedSnapshots" to corruptStats.rejectedSnapshots,
                    "vectorBuilds" to repository.vectorIndexStats().builds,
                    "vectorReuseHits" to repository.vectorIndexStats().reuseHits,
                    "vectorIncompleteRejects" to repository.vectorIndexStats().incompleteRejects,
                    "javaHeapBytes" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
                    "nativeHeapBytes" to Debug.getNativeHeapAllocatedSize(), "pssKb" to memory.totalPss,
                    "sameIdsAcrossRestart" to true, "sameTextAcrossRestart" to true,
                ),
            )
            Log.i(TAG, "members=$requested fixture_ms=$fixtureMs build_ms=$buildMs reload_ms=$loadMs " +
                "corrupt_ms=$corruptMs lost_ms=$lostMs snapshot_bytes=$snapshotBytes hits=${expectedIds.size} " +
                "pss_kb=${memory.totalPss}")
        } finally {
            sessions.forEach { (repo, db) -> runCatching { repo.closeVectorIndexes() }; runCatching { db.close() } }
            nativeIndexes.forEach { runCatching { it.close() } }
            root.deleteRecursively()
            context.deleteDatabase(dbName)
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = SystemClock.elapsedRealtime()
        val value = block()
        return value to (SystemClock.elapsedRealtime() - start)
    }

    private fun record(fields: Map<String, Any>) {
        val json = buildString {
            append("{\"scenario\":\"repository-scale\"")
            fields.forEach { (key, value) -> append(",\"").append(key).append("\":").append(value) }
            append('}')
        }
        Log.i(TAG, "$METRIC_PREFIX$json")
        runCatching {
            synchronized(this) { File(context.filesDir, METRICS_FILE).appendText(json + "\n") }
        }
    }

    private companion object {
        const val TAG = "RepoScaleMetric"
        const val METRIC_PREFIX = "REPOSITORY_SCALE_METRIC "
        const val METRICS_FILE = "repository-scale-metrics.jsonl"
    }
}
