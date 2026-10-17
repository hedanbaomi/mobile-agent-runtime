// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Explicit synthetic, local-only workload; never sends a document to a provider. */
@RunWith(AndroidJUnit4::class)
class OnnxEmbeddingThroughputDeviceTest {
    @Test(timeout = 240_000)
    fun compareRepeatedAndDistinctSentenceWindowsWithRealModel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddingPerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i("EmbeddingThroughput", "target_window_cache=" +
            OnnxTextEmbedder::class.java.declaredFields.any { it.name == "windowCache" })
        val common = "This section describes general experimental procedures and record keeping. ".repeat(6)
        val repeated = List(8) { common + "The delivery record contains ${it + 10} items." }
        val distinct = listOf(
            "The customer can apply for a refund within seven days.",
            "A rose needs sunlight and water to grow in the garden.",
            "The railway station is located near the city center.",
            "A database transaction must preserve the original document.",
            "The laboratory measures the temperature of the water.",
            "This invoice contains the total cost of the order.",
            "A cache stores previous results to avoid repeated work.",
            "The application searches local knowledge using semantic vectors.",
        )
        OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
            embedder.embed("A local model is ready.")
            for ((name, texts) in listOf("repeated" to repeated, "distinct" to distinct)) {
                val digest = MessageDigest.getInstance("SHA-256")
                val started = SystemClock.elapsedRealtimeNanos()
                texts.forEach { text ->
                    val vector = embedder.embed(text)
                    check(vector.size == embedder.dimension && vector.all { it.isFinite() })
                    vector.forEach { value ->
                        val bits = value.toRawBits()
                        repeat(4) { offset -> digest.update((bits ushr (offset * 8)).toByte()) }
                    }
                }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                Log.i("EmbeddingThroughput", "corpus=$name documents=${texts.size} elapsed_ms=$elapsedMs vectors_sha256=$hash")
            }
        }
        val root = File(context.cacheDir, "embedding-throughput-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        AndroidContextSqlite(isolated, "throughput.db").use { db ->
            Migrations.apply(db)
            OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
                val repo = KnowledgeRepository(db, CasBlobSink(File(root, "cas")), embedder,
                    vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(root, "ann"))
                val kb = repo.createKnowledgeBase("synthetic throughput")
                embedder.embed("A local model is ready.")
                val started = SystemClock.elapsedRealtimeNanos()
                repeat(12) { index ->
                    val text = common + "The delivery record contains ${index + 100} items. 铁路运输退款记录。"
                    val job = repo.importBytes("record-$index.txt", "text/plain", text.toByteArray(), false, kb)
                    check(job.stage == ImportStage.READY) { "Synthetic import did not complete: ${job.stage}" }
                }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                val digest = MessageDigest.getInstance("SHA-256")
                val vectors = db.query("SELECT e.vector_blob FROM embeddings e JOIN chunks c ON c.id=e.chunk_id " +
                    "JOIN documents d ON d.active_version_id=c.document_version_id WHERE d.kb_id=? ORDER BY c.content_hash", listOf(kb))
                vectors.forEach { digest.update(it.columns.getValue("vector_blob") as ByteArray) }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val hits = db.query("SELECT COUNT(*) AS n FROM chunks_fts WHERE chunks_fts MATCH ?", listOf("\"铁路\""))
                    .single().long("n")
                check(hits >= 12) { "CJK FTS postings are incomplete" }
                val queryStarted = SystemClock.elapsedRealtimeNanos()
                val retrieval = repo.retrieve("throughput-query", "railway refund", 4, listOf(kb))
                check(retrieval.hits.isNotEmpty() && retrieval.citations.isNotEmpty())
                val queryMs = (SystemClock.elapsedRealtimeNanos() - queryStarted) / 1_000_000.0
                Log.i("EmbeddingThroughput", "corpus=import documents=12 elapsed_ms=$elapsedMs vectors_sha256=$hash")
                Log.i("EmbeddingThroughput", "import_query_ms=$queryMs cjk_matches=$hits fixture=${root.name}")
                repo.closeVectorIndexes()
            }
        }
    }
}
