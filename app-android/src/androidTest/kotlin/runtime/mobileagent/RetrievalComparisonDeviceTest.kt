// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Retrieval-only A/B: fixed document/version/chunk IDs and bytes, real local embeddings.
 * Import correctness is covered separately; SQL fixture truth is not import throughput. */
@RunWith(AndroidJUnit4::class)
class RetrievalComparisonDeviceTest {
    @Test(timeout = 480_000)
    fun fixedCorpusAcrossOneAndFourLibrariesProducesComparableMetrics() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "retrieval-ab-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val corpus = RetrievalQualityCorpus.documents
        val fingerprint = sha256Hex(corpus.joinToString("\u0000") { "${it.first}\u0001${it.second}" }.toByteArray())
        try {
            OnnxTextEmbedder(AndroidModelPackLoader(base).load()).use { embedder ->
                val metrics = JSONObject().put("schema", 2).put("fixture", "fixed-text-relevance-v1")
                    .put("corpusSha256", fingerprint).put("documents", corpus.size)
                    .put("spaceId", embedder.spaceId).put("dimension", embedder.dimension)
                    .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                    .put("scope", "retrieval-only; real ONNX; equal full corpus; no external provider")
                    .put("warmup", "Every corpus text and labelled query embedded once before each distribution")
                val distributions = JSONArray()
                for (libraryCount in listOf(1, 4)) {
                    val context = fixtureContext(base, File(root, "kb-$libraryCount"))
                    AndroidContextSqlite(context, "quality.db").use { db ->
                        Migrations.apply(db)
                        val blobs = CasBlobSink(File(context.filesDir, "cas"))
                        val repo = KnowledgeRepository(db, blobs, embedder,
                            vectorIndexFactory = UsearchVectorIndexFactory(),
                            vectorIndexDirectory = File(context.cacheDir, "ann"))
                        try {
                            val libraries = (0 until libraryCount).map { repo.createKnowledgeBase("Quality $it", "quality-kb-$it") }
                            seed(db, blobs, libraries)
                            val identity = sha256Hex(db.query(
                                "SELECT d.id AS doc,c.document_version_id AS version,c.id AS chunk FROM documents d " +
                                    "JOIN chunks c ON c.document_version_id=d.active_version_id ORDER BY d.id,c.ordinal"
                            ).joinToString("\n") { row ->
                                listOf(row.string("doc"), row.string("version"), row.string("chunk")).joinToString("|")
                            }.toByteArray())
                            if (metrics.has("identitySha256")) assertEquals(metrics.getString("identitySha256"), identity)
                            else metrics.put("identitySha256", identity)
                            corpus.forEach { pair -> embedder.embed(pair.first); embedder.embed(pair.second) }
                            val started = SystemClock.elapsedRealtime()
                            libraries.forEach { repo.rebuildIndex(it) }
                            val buildMs = SystemClock.elapsedRealtime() - started
                            assertEquals(corpus.size.toLong(), db.query("SELECT count(*) AS n FROM chunks").single().long("n"))
                            val result = measure(repo, libraries).put("libraries", libraryCount).put("buildMs", buildMs)
                            val first = result.getJSONArray("rankedChunkIds").toString()
                            repo.closeVectorIndexes()
                            val reopened = KnowledgeRepository(db, blobs, embedder,
                                vectorIndexFactory = UsearchVectorIndexFactory(),
                                vectorIndexDirectory = File(context.cacheDir, "ann"))
                            try {
                                assertEquals("Fixed corpus ranking changed after native snapshot reload",
                                    first, measure(reopened, libraries).getJSONArray("rankedChunkIds").toString())
                            } finally { reopened.closeVectorIndexes() }
                            distributions.put(result)
                        } finally { repo.closeVectorIndexes() }
                    }
                }
                metrics.put("distributions", distributions)
                File(base.filesDir, "retrieval-comparison.json").writeText(metrics.toString(2))
            }
        } finally { root.deleteRecursively() }
    }

    private fun seed(db: SqlConnection, blobs: CasBlobSink, libraries: List<String>) {
        db.transaction {
            RetrievalQualityCorpus.documents.forEachIndexed { index, pair ->
                val id = "quality-doc-%03d".format(java.util.Locale.ROOT, index)
                val version = "quality-version-%03d".format(java.util.Locale.ROOT, index)
                val chunk = "quality-chunk-%03d".format(java.util.Locale.ROOT, index)
                val stored = blobs.put(pair.first.toByteArray(), "text/plain")
                db.execute("INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,1)",
                    listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef))
                db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES(?,?,?,?,?,?,NULL)",
                    listOf(id, libraries[index % libraries.size], stored.sha256, "$id.txt", "TEXT", null))
                db.execute("INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES(?,?,?,?,?,?)",
                    listOf(version, id, "fixed-quality-text-v1", stored.sha256, "READY", "2026-11-10T00:00:00Z"))
                db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page,text_utf16_length) VALUES(?,?,0,?,?,?,'',NULL,?)",
                    listOf(chunk, version, pair.first, stored.sha256, "fixed-corpus:$index", pair.first.length))
                db.execute("UPDATE documents SET active_version_id=? WHERE id=?", listOf(version, id))
            }
        }
    }

    private fun measure(repo: KnowledgeRepository, libraries: List<String>): JSONObject {
        val times = mutableListOf<Long>()
        val warmTimes = mutableListOf<Long>()
        val ranks = JSONArray()
        val rankedIds = JSONArray()
        var recall = 0
        var reciprocal = 0.0
        RetrievalQualityCorpus.documents.forEachIndexed { index, pair ->
            val expected = "quality-doc-%03d".format(java.util.Locale.ROOT, index)
            val start = SystemClock.elapsedRealtime()
            val result = repo.retrieve("fixed-$index", pair.second, 8, libraries)
            times += SystemClock.elapsedRealtime() - start
            val rank = result.hits.indexOfFirst { it.documentId == expected }
            ranks.put(if (rank < 0) 0 else rank + 1)
            rankedIds.put(JSONArray(result.hits.map { it.chunkId }))

            if (rank >= 0) { recall++; reciprocal += 1.0 / (rank + 1) }
            assertFalse(requireNotNull(result.coverage).partial)
            assertTrue(result.hits.all { it.documentId.startsWith("quality-doc-") })
            result.citations.forEach { citation ->
                val sourceIndex = citation.documentId.removePrefix("quality-doc-").toInt()
                assertFalse(repo.locateCitation(citation).removed)
                assertArrayEquals(RetrievalQualityCorpus.documents[sourceIndex].first.toByteArray(),
                    requireNotNull(repo.evidenceBytes(citation)).second)
            }
            val warmStart = SystemClock.elapsedRealtime()
            val warm = repo.retrieve("warm-$index", pair.second, 8, libraries)
            warmTimes += SystemClock.elapsedRealtime() - warmStart
            assertEquals(result.hits.map { it.chunkId }, warm.hits.map { it.chunkId })
        }
        val negatives = JSONArray()
        for (query in listOf("The serial number of the spacecraft on Mars", "这份语料的作者护照号码是多少")) {
            val hits = repo.retrieve("no-answer", query, 8, libraries).hits
            negatives.put(JSONObject().put("query", query).put("relevantLabels", 0)
                .put("candidateCount", hits.size).put("answerEvaluated", false))
        }
        assertTrue("Fixed corpus Recall@8 below initial 12/16 floor", recall >= 12)
        return JSONObject().put("queries", RetrievalQualityCorpus.documents.size)
            .put("recallAt8", recall.toDouble() / RetrievalQualityCorpus.documents.size)
            .put("mrrAt8", reciprocal / RetrievalQualityCorpus.documents.size)
            .put("firstQueryPassP50Ms", percentile(times, 50)).put("firstQueryPassP95Ms", percentile(times, 95))
            .put("warmP50Ms", percentile(warmTimes, 50)).put("warmP95Ms", percentile(warmTimes, 95))
            .put("ranks", ranks).put("rankedChunkIds", rankedIds).put("negativeQueries", negatives)
    }

    private fun percentile(values: List<Long>, percent: Int): Long =
        values.sorted()[((values.size * percent + 99) / 100 - 1).coerceAtLeast(0)]

    private fun fixtureContext(base: Context, root: File): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name).also { it.parentFile!!.mkdirs() }
    }
}
