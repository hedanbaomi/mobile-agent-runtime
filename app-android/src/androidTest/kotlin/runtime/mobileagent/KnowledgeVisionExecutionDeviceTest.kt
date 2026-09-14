// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.PdfParser
import runtime.mobileagent.storage.AndroidContextSqlite

/**
 * Device integration for the real KnowledgeRepository -> Vision transport -> bundled SQLite path.
 * All HTTP is handled by Ktor MockEngine; no provider or user database is touched.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeVisionExecutionDeviceTest {
    @Test(timeout = 60_000)
    fun selectedNonDefaultChatImageTargetPublishesCachesAndRetrieves() = withFixture { db ->
        val default = target("default", "default-model", 0.1)
        val selected = target("selected", "selected-model", 0.35)
        val selectedBinding = visionProfileBinding(selected.first, selected.second)
        val secretRefs = mutableListOf<String>()
        var requestUrl = ""
        var authorization = ""
        var requestBody = ""
        val http = HttpClient(MockEngine { request ->
            requestUrl = request.url.toString()
            authorization = request.headers[HttpHeaders.Authorization].orEmpty()
            requestBody = (request.body as TextContent).text
            respond(successBody(), HttpStatusCode.OK, jsonHeaders())
        })
        try {
            val vision = OpenAiCompatibleVision(
                http,
                { listOf(default, selected) },
                { ref -> secretRefs += ref; "key-$ref".toCharArray() },
            )
            val events = mutableListOf<runtime.mobileagent.knowledge.ImportBatchEvent>()
            val repository = repository(db, vision, default, listOf(default, selected), events)
            val (batchId, knowledgeBaseId) = stagedImageBatch(repository, "selected-success")

            repository.authorizeBatchVision(batchId, selectedBinding.fingerprint)
            assertEquals(selectedBinding.fingerprint, repository.batchVisionAuthorization(batchId))
            repository.processBatch(batchId, visionConfigured = false)

            assertEquals("https://selected.example/v1/chat/completions", requestUrl)
            assertEquals("Bearer key-secret-selected", authorization)
            assertEquals(listOf("secret-selected"), secretRefs)
            assertTrue(requestBody.contains("\"model\":\"selected-model\""))
            assertTrue(requestBody.contains("\"temperature\":0.35"))
            assertFalse(requestBody.contains("default-model"))
            assertFalse(requestBody.contains("\"temperature\":0.1"))
            val captured = events.mapNotNull { it.diagnostic }.filter { it.content != null }
            assertTrue(captured.any { it.contentKind == "source.metadata.json" && it.content!!.contains("selected-success.pdf") })
            assertTrue(captured.any { it.contentKind == "target.configuration.json" && it.content!!.contains("selected-model") })
            assertTrue(captured.any { it.contentKind == "request.json" && it.content!!.contains("data:image/") })
            assertTrue(captured.any { it.contentKind == "response.json" })
            assertTrue(captured.none { it.content!!.contains("key-secret-selected") })
            assertEquals(1, events.mapNotNull { it.requestRef }.toSet().size)

            val result = db.query(
                "SELECT status, model_fingerprint, ocr_text, description FROM vision_results",
            ).single()
            assertEquals("SUCCESS", result.string("status"))
            assertEquals(selectedBinding.fingerprint, result.string("model_fingerprint"))
            assertEquals("selected device ocr", result.string("ocr_text"))
            assertEquals("selected-device-marker", result.string("description"))
            assertEquals(ImportBatchState.COMPLETED, repository.findBatch(batchId)!!.state)
            assertEquals(1, repository.batchProgress(batchId).published)

            val retrieval = repository.retrieve(
                runId = "selected-vision-device-run",
                query = "selected device marker",
                topK = 8,
                knowledgeBaseIds = listOf(knowledgeBaseId),
            )
            assertTrue(retrieval.hits.any { "selected-device-marker" in it.text })
            assertTrue(retrieval.citations.isNotEmpty())
        } finally {
            http.close()
        }
    }

    @Test(timeout = 60_000)
    fun received429PersistsFailedResultAndResponseReceivedAttempt() = withFixture { db ->
        val default = target("default", "default-model", 0.1)
        val selected = target("selected", "selected-model", 0.35)
        val selectedBinding = visionProfileBinding(selected.first, selected.second)
        var requests = 0
        val http = HttpClient(MockEngine {
            requests += 1
            respond("{\"error\":{\"message\":\"limited\"}}", HttpStatusCode.TooManyRequests, jsonHeaders())
        })
        try {
            val vision = OpenAiCompatibleVision(http, { listOf(default, selected) }) { "key".toCharArray() }
            val repository = repository(db, vision, default, listOf(default, selected))
            val (batchId, _) = stagedImageBatch(repository, "selected-429")

            repository.authorizeBatchVision(batchId, selectedBinding.fingerprint)
            repository.processBatch(batchId, visionConfigured = false)

            assertEquals(1, requests)
            val result = db.query("SELECT status, description FROM vision_results").single()
            assertEquals("FAILED", result.string("status"))
            assertTrue(result.string("description").contains("RATE_LIMITED"))
            assertTrue(result.string("description").contains("stage=RESPONSE_HEADERS"))
            assertTrue(result.string("description").contains("HTTP=429"))
            assertFalse(result.string("description").contains("UNKNOWN_OUTCOME"))

            val attempt = db.query(
                "SELECT status, stage, dispatch_status, error_code, http_status FROM vision_attempts",
            ).single()
            assertEquals("FAILED", attempt.string("status"))
            assertEquals("RESPONSE_HEADERS", attempt.string("stage"))
            assertEquals("RESPONSE_RECEIVED", attempt.string("dispatch_status"))
            assertEquals("RATE_LIMITED", attempt.string("error_code"))
            assertEquals(429L, attempt.long("http_status"))
            assertFalse(db.query("SELECT status FROM vision_results WHERE status='UNKNOWN_OUTCOME'").isNotEmpty())
            assertEquals(1, repository.batchProgress(batchId).failed)
            assertEquals(ImportBatchState.FAILED, repository.findBatch(batchId)!!.state)
        } finally {
            http.close()
        }
    }

    private fun repository(
        db: AndroidContextSqlite,
        vision: OpenAiCompatibleVision,
        default: Pair<ProviderProfile, ModelProfile>,
        targets: List<Pair<ProviderProfile, ModelProfile>>,
        events: MutableList<runtime.mobileagent.knowledge.ImportBatchEvent> = mutableListOf(),
    ): KnowledgeRepository {
        val bindings = targets.associate { target ->
            val binding = visionProfileBinding(target.first, target.second)
            binding.fingerprint to binding
        }
        return KnowledgeRepository(
            db = db,
            blobs = MemoryBlobSink(),
            vision = vision,
            visionBinding = { visionProfileBinding(default.first, default.second) },
            visionTargetResolver = bindings::get,
            captureVisionContent = { true },
            importEvents = { events += it },
        )
    }

    private fun stagedImageBatch(repository: KnowledgeRepository, label: String): Pair<String, String> {
        val knowledgeBaseId = repository.createKnowledgeBase("vision-$label")
        val batchId = repository.beginBatch(knowledgeBaseId, ImportBatchKind.FILES, label)
        val job = repository.importBytes(
            displayName = "$label.pdf",
            mediaType = "application/pdf",
            bytes = PdfParser.writePdfWithImageXObject(label),
            visionConfigured = false,
            knowledgeBaseId = knowledgeBaseId,
            pauseAt = ImportStage.COPYING,
        )
        repository.bindJobToBatch(batchId, job, "$label.pdf")
        return batchId to knowledgeBaseId
    }

    private fun target(id: String, modelId: String, temperature: Double): Pair<ProviderProfile, ModelProfile> {
        val provider = ProviderProfile(
            id = "provider-$id",
            name = id,
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://$id.example/v1",
            nonSecretHeaders = mapOf("X-Device-Fixture" to id),
            secretRef = "secret-$id",
            revision = 2,
        )
        return provider to ModelProfile(
            id = "profile-$id",
            providerId = provider.id,
            role = ModelRole.CHAT,
            modelId = modelId,
            capabilities = setOf("image"),
            contextLimit = 4096,
            outputLimit = 512,
            revision = 3,
            parametersJson = "{\"temperature\":$temperature}",
        )
    }

    private fun successBody() =
        """{"choices":[{"message":{"content":"{\"ocrText\":\"selected device ocr\",\"semanticDescription\":\"selected-device-marker\",\"tableMarkdown\":\"\",\"type\":\"image\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private inline fun withFixture(block: (AndroidContextSqlite) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "knowledge-vision-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            val db = AndroidContextSqlite(context, databaseName)
            Migrations.apply(db)
            block(db)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
