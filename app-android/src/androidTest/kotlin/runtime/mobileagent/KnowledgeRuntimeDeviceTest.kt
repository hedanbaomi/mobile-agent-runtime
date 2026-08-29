// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.agent.PromptTemplates
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.ModelPackManifest
import runtime.mobileagent.embedding.OnnxModelPack
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.VectorIndexFactory
import runtime.mobileagent.knowledge.VectorIndexPort
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndex
import runtime.mobileagent.vector.UsearchVectorIndexFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Real packaged ONNX, JNI and bundled SQLite; no API model, network or user data.
 * The configured runner substitutes Application, so this is NOT MobileAgentApp cold-start proof.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeRuntimeDeviceTest {
    @Test(timeout = 180_000)
    fun packagedModelPackLoadsVerifiedNormalizedStableEmbeddings() = measured("packagedModelPackLoadsVerifiedNormalizedStableEmbeddings") { context ->
        val assetRoot = AndroidModelPackLoader.DEFAULT_ASSET_ROOT
        val manifest = context.assets.open("$assetRoot/manifest.json").use {
            Json.decodeFromString<ModelPackManifest>(it.bufferedReader().readText())
        }
        assertEquals(AndroidModelPackLoader.DEFAULT_MODEL_ID, manifest.id)
        assertEquals(AndroidModelPackLoader.DEFAULT_REVISION, manifest.revision)
        assertEquals(AndroidModelPackLoader.DEFAULT_SPACE_ID, manifest.spaceId)
        assertEquals(384, manifest.dimension)
        assertEquals(AndroidModelPackLoader.DEFAULT_MODEL_SHA256, manifest.sha256)
        assertEquals(AndroidModelPackLoader.DEFAULT_TOKENIZER_SHA256, manifest.tokenizerSha256)
        assertEquals(manifest.sha256, context.assets.open("$assetRoot/${manifest.modelFile}").use(::sha256))
        assertEquals(manifest.tokenizerSha256, context.assets.open("$assetRoot/${manifest.tokenizerFile}").use(::sha256))
        val pack = loadPack(context)
        assertEquals(manifest, pack.manifest)
        assertTrue(pack.modelFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        OnnxTextEmbedder(pack).use { embedder ->
            assertEquals(manifest.spaceId, embedder.spaceId)
            assertEquals(384, embedder.dimension)
            val first = embedder.embed(ASTRONOMY)
            val repeated = embedder.embed(ASTRONOMY)
            val unrelated = embedder.embed(COOKING)
            listOf(first, repeated, unrelated).forEach(::assertNormalized)
            assertArrayEquals("Repeated inference must be stable", first, repeated, 0.00001f)
            val similarity = cosine(first, unrelated)
            assertTrue("Unrelated inputs must not collapse to the same vector: $similarity", similarity < 0.999)
            Log.i(TAG, "embedding unrelated_cosine=$similarity dimension=${first.size}")
        }
    }

    @Test(timeout = 180_000)
    fun usearchJniAddsSearchesAndRejectsUseAfterClose() = measured("usearchJniAddsSearchesAndRejectsUseAfterClose") { context ->
        OnnxTextEmbedder(loadPack(context)).use { embedder ->
            val astronomy = embedder.embed(ASTRONOMY)
            val cooking = embedder.embed(COOKING)
            val index = UsearchVectorIndex(embedder.spaceId, embedder.dimension, 2)
            try {
                index.add("astronomy", astronomy)
                index.add("cooking", cooking)
                val found = index.search(astronomy, 2)
                assertEquals(setOf("astronomy", "cooking"), found.map { it.first }.toSet())
                assertEquals("astronomy", found.first().first)
                assertTrue(found.first().second > 0.999f)
                assertTrue(found.all { it.second.isFinite() })
                assertEquals("cooking", index.search(cooking, 1).single().first)
                assertThrows(IllegalArgumentException::class.java) { index.add("astronomy", astronomy) }
            } finally {
                index.close()
            }
            index.close() // The public close contract is idempotent.
            assertThrows(IllegalStateException::class.java) { index.search(astronomy, 1) }
            assertThrows(IllegalStateException::class.java) { index.add("closed", astronomy) }
        }
    }

    @Test(timeout = 180_000)
    fun sqliteFtsImportCitationAndDeletionStayIsolatedAcrossGenerations() = measured("sqliteFtsImportCitationAndDeletionStayIsolatedAcrossGenerations") { context ->
        val db = AndroidContextSqlite(context, "knowledge-fixture.db")
        Migrations.apply(db)
        assertEquals(Migrations.VERSION.toLong(), db.query("SELECT MAX(version) AS version FROM schema_version").single().long("version"))
        Log.i(TAG, "bundled_sqlite_version=${db.query("SELECT sqlite_version() AS version").single().string("version")}")
        OnnxTextEmbedder(loadPack(context)).use { embedder ->
            val nativeCreates = AtomicInteger()
            val factory = object : VectorIndexFactory {
                override fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndexPort {
                    // Only count real native instances; no test index or search result is substituted.
                    val native = UsearchVectorIndexFactory().create(spaceId, dimension, capacity)
                    nativeCreates.incrementAndGet()
                    return native
                }
            }
            val visionCalls = AtomicInteger()
            val unexpectedVision = VisionBackend {
                visionCalls.incrementAndGet()
                error("Plain-text fixture must never invoke Vision; this is not an external Vision backend")
            }
            val blobs = CasBlobSink(File(context.filesDir, "cas"))
            fun repository(connection: SqlConnection) = KnowledgeRepository(
                connection, blobs, embedder, vision = unexpectedVision, vectorIndexFactory = factory,
            )
            val repository = repository(db)
            val kbA = repository.createKnowledgeBase("fixture A")
            val kbB = repository.createKnowledgeBase("fixture B")
            val shared = "quasar telescope galaxy. 张伟研究星系和望远镜。"
            val sharedBytes = shared.toByteArray(Charsets.UTF_8)
            val a = repository.importBytes("a.txt", "text/plain", sharedBytes, false, kbA)
            val b = repository.importBytes("b.txt", "text/plain", sharedBytes, false, kbB)
            val survivor = repository.importBytes("cooking.txt", "text/plain", COOKING.toByteArray(Charsets.UTF_8), false, kbA)
            listOf(a, b, survivor).forEach { assertEquals("Import failed: ${it.error}", ImportStage.READY, it.stage) }
            assertNotEquals(a.documentId, b.documentId)
            // Direct MATCH has no repository LIKE fallback, proving the native FTS5 path itself.
            assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM chunks_fts WHERE chunks_fts MATCH ?", listOf("quasar")).single().long("n"))
            val result = repository.retrieve("fixture-run", "quasar", 8, listOf(kbA))
            assertTrue(result.hits.any { it.documentId == a.documentId })
            assertTrue(result.hits.all { it.knowledgeBaseId == kbA && it.documentId != b.documentId })
            assertTrue(repository.search("张伟", 8, listOf(kbA)).any { it.documentId == a.documentId })
            val citation = result.citations.first { it.documentId == a.documentId }
            assertEquals("fixture-run", citation.runId)
            assertTrue(citation.documentVersionId.isNotBlank() && citation.chunkId.isNotBlank())
            assertFalse(repository.locateCitation(citation).removed)
            assertTrue(repository.locateCitation(citation.copy(knowledgeBaseId = kbB)).removed)
            assertArrayEquals(sharedBytes, requireNotNull(repository.evidenceBytes(citation)).second)
            val oldGeneration = generation(db, kbA, expectedCount = 2, embedder.spaceId)
            val blobHash = sha256(sharedBytes.inputStream())
            assertEquals(2L, repository.blobRefCount(blobHash))
            repository.deleteDocument(a.documentId)
            val newGeneration = generation(db, kbA, expectedCount = 1, embedder.spaceId)
            assertNotEquals(oldGeneration, newGeneration)
            assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(oldGeneration)).single().long("n"))
            assertTrue(repository.locateCitation(citation).removed)
            assertEquals(1L, repository.blobRefCount(blobHash))
            assertTrue(repository.search("quasar", 8, listOf(kbA)).none { it.documentId == a.documentId || it.documentId == b.documentId })
            // A new connection/repository reads the persisted READY generation, not prior objects.
            val reopenedDb = AndroidContextSqlite(context, "knowledge-fixture.db")
            Migrations.apply(reopenedDb)
            val reopened = repository(reopenedDb)
            assertEquals(newGeneration, generation(reopenedDb, kbA, expectedCount = 1, embedder.spaceId))
            assertTrue(reopened.search("quasar", 8, listOf(kbA)).none { it.documentId == a.documentId })
            val bResult = reopened.retrieve("fixture-run-b", "quasar", 8, listOf(kbB))
            assertEquals(b.documentId, bResult.hits.single().documentId)
            assertArrayEquals(sharedBytes, requireNotNull(reopened.evidenceBytes(bResult.citations.single())).second)
            reopened.deleteKnowledgeBase(kbA)
            assertTrue(reopened.search("sourdough", 8, listOf(kbA)).isEmpty())
            assertEquals(b.documentId, reopened.search("quasar", 8, listOf(kbB)).single().documentId)
            assertTrue("Repository must actually construct USearch JNI indexes", nativeCreates.get() >= 3)
            assertEquals("No Vision invocation is permitted for this fixture", 0, visionCalls.get())
            Log.i(TAG, "generation old=$oldGeneration new=$newGeneration native_index_instances=${nativeCreates.get()} vision_calls=0")
        }
    }

    @Test(timeout = 60_000)
    fun agentRepositoryAndPromptTemplatesInitializeOnAndroid() = measured("agentRepositoryAndPromptTemplatesInitializeOnAndroid") { context ->
        val db = AndroidContextSqlite(context, "agent-fixture.db")
        Migrations.apply(db)
        val agents = AgentRepository(db)
        assertTrue(agents.list().isEmpty())
        val profiles = ProfileRepository(db)
        profiles.upsertProvider(ProviderProfile("fixture-provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://fixture.invalid/v1", revision = 1))
        profiles.upsertModel(ModelProfile("fixture-model", "fixture-provider", ModelRole.CHAT, "fixture-model", emptySet(), contextLimit = 4096, outputLimit = 256, revision = 1))
        val template = "Hello {{agent_name}} / {{date}} / {{knowledge_bases}}"
        val agent = agents.saveWithPrompt(AgentProfile("fixture-agent", "Android ICU", "new-prompt", "fixture-model", revision = 1), template)
        val prompt = requireNotNull(agents.promptRevision(agent.promptRevisionId))
        assertEquals(template, prompt.template)
        assertEquals("Hello Android ICU / 2026-08-29 / 本地资料", PromptTemplates.render(prompt.template, mapOf(
            "agent_name" to agent.name, "date" to "2026-08-29", "knowledge_bases" to "本地资料",
        )))
        assertThrows(IllegalArgumentException::class.java) { PromptTemplates.render("{{lookup}}", emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { PromptTemplates.render("{{agent_name}", emptyMap()) }
        assertTrue(runCatching { agents.saveWithPrompt(agent, "{{unknown}}") }.isFailure)
        assertEquals(1, agents.listPromptRevisions(agent.id).size)
        assertEquals(agent.id, agents.get(agent.id)?.id)
        Log.i(TAG, "android_regex_initialization=passed production_application_startup=not_tested")
    }

    private fun generation(db: SqlConnection, kbId: String, expectedCount: Int, spaceId: String): String {
        val generation = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id=?", listOf(kbId)).single().string("active_generation_id")
        val row = db.query("SELECT state,space_id,vector_count,manifest_hash FROM index_generations WHERE id=?", listOf(generation)).single()
        assertEquals("READY", row.string("state"))
        assertEquals(spaceId, row.string("space_id"))
        assertEquals(expectedCount.toLong(), row.long("vector_count"))
        assertTrue(row.string("manifest_hash").matches(Regex("[0-9a-f]{64}")))
        assertEquals(expectedCount.toLong(), db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(generation)).single().long("n"))
        return generation
    }

    private fun loadPack(context: Context): OnnxModelPack {
        val start = SystemClock.elapsedRealtime()
        val pack = AndroidModelPackLoader(context).load()
        assertEquals(pack.manifest.sha256, pack.modelFile.inputStream().use(::sha256))
        assertEquals(pack.manifest.tokenizerSha256, pack.tokenizerFile.inputStream().use(::sha256))
        Log.i(TAG, "pack_load_ms=${SystemClock.elapsedRealtime() - start} model_sha256=${pack.manifest.sha256} tokenizer_sha256=${pack.manifest.tokenizerSha256}")
        return pack
    }

    private fun assertNormalized(vector: FloatArray) {
        assertEquals(384, vector.size)
        assertTrue(vector.all { it.isFinite() })
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        assertEquals(1.0, norm, 0.0001)
    }

    private fun cosine(first: FloatArray, second: FloatArray): Double = first.indices.sumOf { first[it].toDouble() * second[it].toDouble() }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun measured(name: String, body: (FixtureContext) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        var passed = false
        try {
            val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext, suiteRoot, name)
            Log.i(TAG, "test=$name fixture=${context.fixtureRoot} expected_model_sha256=${AndroidModelPackLoader.DEFAULT_MODEL_SHA256} expected_tokenizer_sha256=${AndroidModelPackLoader.DEFAULT_TOKENIZER_SHA256}")
            body(context)
            passed = true
        } finally {
            Log.i(TAG, "test=$name status=${if (passed) "PASS" else "FAIL"} elapsed_ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private class FixtureContext(base: Context, private val suite: File, name: String) : ContextWrapper(base) {
        val fixtureRoot = File(suite, name).apply { check(mkdirs() || isDirectory) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(suite, "model-cache").apply { check(mkdirs() || isDirectory) }
        override fun getFilesDir(): File = File(fixtureRoot, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { check(mkdirs() || isDirectory) }
        override fun getDatabasePath(name: String): File {
            require(name.isNotBlank() && File(name).name == name && '/' !in name && '\\' !in name)
            val databaseDir = File(fixtureRoot, "databases").apply { check(mkdirs() || isDirectory) }
            return File(databaseDir, name)
        }
    }

    private companion object {
        const val TAG = "KnowledgeRuntimeDevice"
        const val ASTRONOMY = "Astronomers observe distant galaxies and quasars through a telescope."
        const val COOKING = "A sourdough baker kneads flour and water, then bakes bread in an oven."
        // Kept for post-run inspection. SqlConnection has no public close method;
        // do not unlink open databases or reach into the driver via reflection.
        val suiteRoot: File by lazy {
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
            File(cache, "knowledge-device-test-${UUID.randomUUID()}").apply {
                check(canonicalPath.startsWith(cache.path + File.separator))
                check(mkdirs())
            }
        }
    }
}
