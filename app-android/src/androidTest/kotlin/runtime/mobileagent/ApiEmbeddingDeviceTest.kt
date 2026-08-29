// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.ApiQueryUnknownOutcomeException
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.VectorIndexFactory
import runtime.mobileagent.knowledge.VectorIndexPort
import runtime.mobileagent.security.AndroidSecretStore
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android integration with a loopback-only synthetic provider; no paid provider or user DB. */
@RunWith(AndroidJUnit4::class)
class ApiEmbeddingDeviceTest {
    @Test(timeout = 30_000)
    fun consentGatesHttpAndSecretsThenUsesTheExactBinding() = withFixture(ReplyMode.VALID) { fixture ->
        val waiting = fixture.importWithoutConsent()
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT, waiting.stage)
        assertEquals(ImportStage.AWAITING_EMBEDDING_CONSENT,
            fixture.knowledge.resumeImport(waiting.id, visionConfigured = false).stage)
        assertEquals("Unapproved text must not open HTTP", 0, fixture.server.connections.get())
        assertEquals("Unapproved text must not resolve the dummy credential", 0, fixture.db.secretReads.get())
        fixture.assertNotPublished()
        val ready = fixture.knowledge.grantEmbeddingConsent(waiting.id)
        assertEquals(ImportStage.READY, ready.stage)
        fixture.assertSingleAuthorizedRequest()
        assertEquals(1, fixture.db.secretReads.get())
        fixture.assertReadyGeneration()
        // READY publishes SQLite vectors; this real retrieval is what constructs the native index.
        val retrieved = fixture.knowledge.retrieve("api-fixture-query", FIXTURE_QUERY, 8, listOf(fixture.kbId))
        assertEquals(ready.documentId, retrieved.hits.single().documentId)
        assertEquals(fixture.kbId, retrieved.hits.single().knowledgeBaseId)
        assertEquals(ready.documentId, retrieved.citations.single().documentId)
        assertEquals("api-fixture-query", retrieved.citations.single().runId)
        assertEquals(2, fixture.server.requests.size)
        assertEquals(2, fixture.server.connections.get())
        fixture.assertAuthorizedRequest(fixture.server.requests.last(), FIXTURE_QUERY)
        assertEquals(2, fixture.db.secretReads.get())
        assertTrue("API vectors must reach the real USearch JNI factory", fixture.nativeCreates.get() > 0)
    }

    @Test(timeout = 30_000)
    fun providerRevisionInvalidatesPendingAndCapturedBindingsBeforeSecretRead() = withFixture(ReplyMode.VALID) { fixture ->
        val waiting = fixture.importWithoutConsent()
        val captured = requireNotNull(fixture.registry.resolve(fixture.binding.spaceId))
        fixture.profiles.updateProvider(fixture.provider.copy(revision = fixture.provider.revision + 1))
        assertNull(fixture.registry.resolve(fixture.binding.spaceId))
        assertThrows(IllegalStateException::class.java) { captured.embed("synthetic stale binding input") }
        assertThrows(IllegalStateException::class.java) { fixture.knowledge.grantEmbeddingConsent(waiting.id) }
        assertEquals(0, fixture.db.secretReads.get())
        assertEquals(0, fixture.server.connections.get())
        fixture.assertNotPublished()
        val updated = fixture.registry.binding(fixture.model.id, DIMENSION, fixture.kbId)
        assertTrue(updated.spaceId != fixture.binding.spaceId)
        assertNotNull(fixture.registry.resolve(updated.spaceId))
        assertEquals("Resolving an adapter is not credential access", 0, fixture.db.secretReads.get())
    }

    @Test(timeout = 30_000)
    fun wrongProviderDimensionCannotPublishReadyVectors() = withFixture(ReplyMode.WRONG_DIMENSION) { fixture ->
        val waiting = fixture.importWithoutConsent()
        val failed = fixture.knowledge.grantEmbeddingConsent(waiting.id)
        assertEquals(ImportStage.FAILED, failed.stage)
        assertTrue("A billed invalid response remains uncertain", failed.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        fixture.assertSingleAuthorizedRequest()
        fixture.assertNotPublished()
        assertEquals("Invalid dimensions cannot reach a native index", 0, fixture.nativeCreates.get())
    }

    @Test(timeout = 30_000)
    fun droppedResponseStaysUnknownUntilExplicitDuplicateChargeRetry() = withFixture(ReplyMode.DROP_FIRST) { fixture ->
        val waiting = fixture.importWithoutConsent()
        val unknown = fixture.knowledge.grantEmbeddingConsent(waiting.id)
        assertEquals(ImportStage.FAILED, unknown.stage)
        assertTrue(unknown.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        fixture.assertSingleAuthorizedRequest()
        fixture.assertNotPublished()
        // Reconstruct the repository: the gate must survive the loss of its in-memory object.
        val reopened = fixture.newKnowledge()
        assertThrows(IllegalStateException::class.java) { reopened.resumeImport(waiting.id, visionConfigured = false) }
        assertThrows(IllegalStateException::class.java) { reopened.grantEmbeddingConsent(waiting.id) }
        assertThrows(IllegalStateException::class.java) { reopened.retryUnknownEmbedding(waiting.id, false) }
        val repeated = reopened.importBytes("fixture.txt", "text/plain", FIXTURE_TEXT.toByteArray(), false,
            fixture.kbId, embeddingIsApi = true, embeddingConsent = true)
        assertEquals(ImportStage.FAILED, repeated.stage)
        assertTrue(repeated.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals("Ordinary resume/grant/reimport must not replay POST", 1, fixture.server.requests.size)
        assertEquals(1, fixture.server.connections.get())
        assertEquals(1, fixture.db.secretReads.get())
        val ready = reopened.retryUnknownEmbedding(waiting.id, acknowledgeDuplicateCharge = true)
        assertEquals(ImportStage.READY, ready.stage)
        assertEquals("Only acknowledged retry may send a second POST", 2, fixture.server.requests.size)
        assertEquals(2, fixture.server.connections.get())
        assertEquals(2, fixture.db.secretReads.get())
        fixture.server.requests.forEach { fixture.assertAuthorizedRequest(it) }
        fixture.assertReadyGeneration()
    }

    @Test(timeout = 30_000)
    fun queryUnknownSurvivesRepositoryRecreationAndConsumesOneRetryGrant() = withFixture(ReplyMode.DROP_SECOND) { fixture ->
        val waiting = fixture.importWithoutConsent()
        val ready = fixture.knowledge.grantEmbeddingConsent(waiting.id)
        assertEquals(ImportStage.READY, ready.stage)
        fixture.assertSingleAuthorizedRequest()
        fixture.assertReadyGeneration()

        // POST 1 imported successfully. POST 2 is a complete query request whose response is dropped.
        val unknown = assertThrows(ApiQueryUnknownOutcomeException::class.java) {
            fixture.knowledge.retrieve("api-query-unknown", FIXTURE_QUERY, 8, listOf(fixture.kbId))
        }
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(FIXTURE_QUERY.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(fixture.kbId, unknown.knowledgeBaseId)
        assertEquals(fixture.binding.spaceId, unknown.spaceId)
        assertEquals(expectedHash, unknown.queryHash)
        assertNull("Provider payload exceptions must not escape through the query gate", unknown.cause)
        assertEquals(2, fixture.server.requests.size)
        fixture.assertAuthorizedRequest(fixture.server.requests.last(), FIXTURE_QUERY)
        assertEquals(2, fixture.db.secretReads.get())

        val reopened = fixture.newKnowledge()
        val pending = reopened.pendingApiQueries(fixture.kbId).single()
        assertEquals(fixture.kbId, pending.knowledgeBaseId)
        assertEquals(fixture.binding.spaceId, pending.spaceId)
        assertEquals(expectedHash, pending.queryHash)
        assertTrue(!pending.retryAuthorized && pending.error.contains("UNKNOWN_OUTCOME"))
        assertThrows(ApiQueryUnknownOutcomeException::class.java) {
            reopened.retrieve("api-query-new-run", FIXTURE_QUERY, 8, listOf(fixture.kbId))
        }
        assertThrows(IllegalStateException::class.java) {
            reopened.authorizeApiQueryRetry(fixture.kbId, pending.spaceId, pending.queryHash, false)
        }
        assertEquals("A new repository/run cannot replay an unresolved query", 2, fixture.server.requests.size)
        assertEquals(2, fixture.server.connections.get())
        assertEquals("The persisted gate must reject before credential resolution", 2, fixture.db.secretReads.get())

        val authorized = reopened.authorizeApiQueryRetry(fixture.kbId, pending.spaceId, pending.queryHash, true)
        assertTrue(authorized.retryAuthorized)
        assertThrows(IllegalStateException::class.java) {
            reopened.authorizeApiQueryRetry(fixture.kbId, pending.spaceId, pending.queryHash, true)
        }
        val retryRepository = fixture.newKnowledge()
        assertTrue(retryRepository.pendingApiQueries(fixture.kbId).single().retryAuthorized)
        val result = retryRepository.retrieve("api-query-explicit-retry", FIXTURE_QUERY, 8, listOf(fixture.kbId))
        assertEquals(ready.documentId, result.hits.single().documentId)
        assertEquals(ready.documentId, result.citations.single().documentId)
        assertEquals("api-query-explicit-retry", result.citations.single().runId)
        assertTrue("The completed retry clears its pending key", retryRepository.pendingApiQueries(fixture.kbId).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            retryRepository.authorizeApiQueryRetry(fixture.kbId, pending.spaceId, pending.queryHash, true)
        }
        assertEquals("Exactly one acknowledged query retry may send POST 3", 3, fixture.server.requests.size)
        assertEquals(3, fixture.server.connections.get())
        assertEquals(3, fixture.db.secretReads.get())
        fixture.assertAuthorizedRequest(fixture.server.requests.first())
        fixture.server.requests.drop(1).forEach { fixture.assertAuthorizedRequest(it, FIXTURE_QUERY) }
        assertTrue("Successful query retry must use USearch JNI", fixture.nativeCreates.get() > 0)
        fixture.assertReadyGeneration()
    }

    private class Fixture(
        val db: ObservedSql,
        val profiles: ProfileRepository,
        val registry: ApiEmbeddingRegistry,
        val server: LoopbackProvider,
        val provider: ProviderProfile,
        val model: ModelProfile,
        val kbId: String,
        val binding: ApiEmbeddingBinding,
        private val blobs: CasBlobSink,
    ) {
        val nativeCreates = AtomicInteger()
        private val nativeFactory = object : VectorIndexFactory {
            override fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndexPort {
                val index = UsearchVectorIndexFactory().create(spaceId, dimension, capacity)
                nativeCreates.incrementAndGet()
                return index
            }
        }
        val knowledge = newKnowledge().also { it.createApiKnowledgeBase("Synthetic API fixture", binding, kbId) }

        fun newKnowledge() = KnowledgeRepository(db, blobs,
            vectorIndexFactory = nativeFactory, apiEmbedderResolver = registry::resolve)

        fun importWithoutConsent(): ImportJob = knowledge.importBytes(
            "fixture.txt", "text/plain", FIXTURE_TEXT.toByteArray(Charsets.UTF_8), false, kbId,
            embeddingIsApi = true, embeddingConsent = false,
        )

        fun assertAuthorizedRequest(request: RecordedRequest, expectedText: String = FIXTURE_TEXT) {
            assertEquals("POST", request.method)
            assertEquals("/selected/v1/embeddings", request.path)
            assertEquals(model.modelId, request.model)
            assertTrue("Only the dummy credential may be attached", request.dummyAuthorizationMatched)
            assertEquals(listOf(expectedText), request.texts)
        }

        fun assertSingleAuthorizedRequest() {
            server.assertHealthy()
            assertEquals(1, server.requests.size)
            assertEquals(1, server.connections.get())
            assertAuthorizedRequest(server.requests.single())
        }

        fun assertNotPublished() {
            val kb = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id=?", listOf(kbId)).single()
            assertTrue(kb.string("active_generation_id").isBlank())
            assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM embeddings").single().long("n"))
            assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM index_generations WHERE state='READY'").single().long("n"))
        }

        fun assertReadyGeneration() {
            val kb = db.query("SELECT active_generation_id,embedding_space_id FROM knowledge_bases WHERE id=?", listOf(kbId)).single()
            assertEquals(binding.spaceId, kb.string("embedding_space_id"))
            val generation = kb.string("active_generation_id")
            assertTrue(generation.isNotBlank())
            val row = db.query("SELECT state,space_id,vector_count FROM index_generations WHERE id=?", listOf(generation)).single()
            assertEquals("READY", row.string("state"))
            assertEquals(binding.spaceId, row.string("space_id"))
            assertEquals(1L, row.long("vector_count"))
            assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM embeddings WHERE space_id=?", listOf(binding.spaceId)).single().long("n"))
        }
    }

    private fun withFixture(mode: ReplyMode, body: (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationContext.javaClass == Application::class.java) {
            "Use PythonRuntimeDeviceTestRunner's plain Application, never user App initialization"
        }
        check(BuildConfig.DEBUG) { "Loopback cleartext fixtures are debug-only" }
        val id = UUID.randomUUID().toString()
        val cache = target.cacheDir.canonicalFile
        val root = File(cache, "api-embedding-device-" + id).canonicalFile
        check(root.parentFile == cache && root.mkdir())
        val context = FixtureContext(target, root)
        val ref = "api-embedding-fixture-" + id
        LoopbackProvider(mode).use { server ->
            val client = HttpClient(OkHttp) {
                followRedirects = false
                engine { config {
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    // Check before DNS/connect, even if a regression selects another provider.
                    addInterceptor { chain ->
                        val url = chain.request().url
                        check(url.scheme == "http" && url.host == "127.0.0.1" && url.port == server.port) {
                            "API fixture refused a non-loopback destination"
                        }
                        chain.proceed(chain.request())
                    }
                } }
                install(HttpTimeout) { requestTimeoutMillis = 3_000; connectTimeoutMillis = 2_000; socketTimeoutMillis = 3_000 }
            }
            var db: ObservedSql? = null
            try {
                val connection = ObservedSql(AndroidContextSqlite(context, DB_NAME), ref).also { db = it }
                Migrations.apply(connection)
                val profiles = ProfileRepository(connection)
                val secrets = AndroidSecretStore(context, connection)
                secrets.put(ref, DUMMY_SECRET.toCharArray())
                val provider = ProviderProfile("provider-" + id, "Synthetic loopback provider", ApiFormat.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.port + "/selected/v1", secretRef = ref, revision = 1)
                val model = ModelProfile("model-" + id, provider.id, ModelRole.EMBEDDING, "synthetic-embedding-model",
                    emptySet(), contextLimit = 1024, outputLimit = 16, revision = 1)
                profiles.createProvider(provider)
                profiles.createModel(model)
                // A competing configured model must never be chosen implicitly.
                profiles.createModel(model.copy(id = "decoy-" + id, modelId = "unselected-decoy-model"))
                val registry = ApiEmbeddingRegistry(profiles, secrets, client)
                val kbId = "kb-" + id
                val binding = registry.binding(model.id, DIMENSION, kbId)
                body(Fixture(connection, profiles, registry, server, provider, model, kbId, binding,
                    CasBlobSink(File(context.filesDir, "cas"))))
                server.assertHealthy()
            } finally {
                // Only this UUID's dummy row is removed. Never enumerate/delete the shared Keystore alias.
                try { db?.execute("DELETE FROM secrets WHERE ref = ?", listOf(ref)) } finally { client.close() }
                // SqlConnection has no public close: retain isolated DB/CAS, never unlink an open DB.
            }
        }
    }

    /** Observes the real driver; never substitutes rows, transactions, ciphertext or errors. */
    private class ObservedSql(private val delegate: SqlConnection, private val ownSecretRef: String) : SqlConnection {
        val secretReads = AtomicInteger()
        override fun execute(sql: String, args: List<Any?>) = delegate.execute(sql, args)
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            if (sql.trimStart().startsWith("SELECT", ignoreCase = true) && Regex("(?i)\\bsecrets\\b").containsMatchIn(sql)) {
                check(args.size == 1 && args.single() == ownSecretRef) { "Fixture may query only its own dummy credential row" }
                secretReads.incrementAndGet()
            }
            return delegate.query(sql, args)
        }
        override fun <T> transaction(block: () -> T): T = delegate.transaction(block)
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir(): File = File(root, "cache").apply { check(mkdirs() || isDirectory) }
        override fun getDatabasePath(name: String): File {
            check(name == DB_NAME)
            return File(File(root, "databases").apply { check(mkdirs() || isDirectory) }, name)
        }
    }

    private enum class ReplyMode { VALID, WRONG_DIMENSION, DROP_FIRST, DROP_SECOND }
    private data class RecordedRequest(val method: String, val path: String, val model: String,
                                       val texts: List<String>, val dummyAuthorizationMatched: Boolean)

    /** Real HTTP socket; records only fixture text/model and a dummy-credential match boolean. */
    private class LoopbackProvider(private val mode: ReplyMode) : AutoCloseable {
        private val listener = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).apply { soTimeout = 200 }
        val port: Int = listener.localPort
        val connections = AtomicInteger()
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        private val closed = AtomicBoolean()
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread({ serve() }, "api-embedding-loopback-fixture").apply { isDaemon = true; start() }

        private fun serve() {
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                catch (error: Throwable) { if (!closed.get()) failure.compareAndSet(null, error); return }
                active.set(socket)
                try {
                    socket.use {
                        check(connections.incrementAndGet() <= 8) { "Unexpected fixture connection count" }
                        it.soTimeout = 1_500
                        val input = it.getInputStream()
                        val headers = readHeaders(input)
                        val requestLine = headers.first().split(' ')
                        check(requestLine.size == 3)
                        val fields = headers.drop(1).associate { line ->
                            val separator = line.indexOf(':')
                            check(separator > 0)
                            line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
                        }
                        check("transfer-encoding" !in fields) { "Fixture requires bounded Content-Length" }
                        val size = requireNotNull(fields["content-length"]?.toIntOrNull())
                        check(size in 1..16_384)
                        val body = ByteArray(size)
                        var offset = 0
                        while (offset < size) {
                            val count = input.read(body, offset, size - offset)
                            check(count > 0) { "Incomplete fixture HTTP body" }
                            offset += count
                        }
                        val value = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                        val texts = (value.getValue("input") as JsonArray).map { item -> item.jsonPrimitive.content }
                        check(texts.size in 1..8)
                        requests.add(RecordedRequest(requestLine[0], requestLine[1],
                            value.getValue("model").jsonPrimitive.content, texts,
                            fields["authorization"] == "Bearer " + DUMMY_SECRET))
                        // Complete POST then close without a response: the provider may have charged.
                        if ((mode == ReplyMode.DROP_FIRST && requests.size == 1) ||
                            (mode == ReplyMode.DROP_SECOND && requests.size == 2)) return@use
                        val dimension = if (mode == ReplyMode.WRONG_DIMENSION) DIMENSION - 1 else DIMENSION
                        val response = buildJsonObject {
                            put("data", buildJsonArray {
                                texts.indices.forEach { index ->
                                    add(buildJsonObject {
                                        put("index", index)
                                        put("embedding", buildJsonArray {
                                            repeat(dimension) { coordinate -> add(JsonPrimitive(if (coordinate == 0) 1.0 else 0.0)) }
                                        })
                                    })
                                }
                            })
                        }.toString().toByteArray(Charsets.UTF_8)
                        val output = it.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                            response.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(response)
                        output.flush()
                    }
                } catch (error: Throwable) {
                    if (!closed.get()) failure.compareAndSet(null, error)
                } finally { active.set(null) }
            }
        }

        fun assertHealthy() {
            assertNull("Loopback fixture failed; values intentionally omitted", failure.get()?.javaClass?.simpleName)
        }

        override fun close() {
            closed.set(true)
            listener.close()
            active.getAndSet(null)?.let { runCatching { it.close() } }
            worker.join(2_000)
            check(!worker.isAlive) { "Loopback fixture did not stop within its deadline" }
        }

        private fun readHeaders(input: InputStream): List<String> {
            val bytes = ByteArrayOutputStream()
            var lastFour = 0
            while (bytes.size() < 16_384) {
                val next = input.read()
                check(next >= 0) { "Missing fixture HTTP headers" }
                bytes.write(next)
                lastFour = (lastFour shl 8) or next
                if (lastFour == 0x0d0a0d0a) return bytes.toString("US-ASCII").removeSuffix("\r\n\r\n").split("\r\n")
            }
            error("Fixture HTTP headers exceed limit")
        }
    }

    private companion object {
        const val DB_NAME = "api-embedding-fixture.db"
        const val DIMENSION = 3
        const val FIXTURE_TEXT = "Synthetic quasar telescope embedding fixture."
        const val FIXTURE_QUERY = "quasar telescope"
        const val DUMMY_SECRET = "LOCAL-QA-FIXTURE-NOT-A-REAL-KEY"
    }
}
