// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.knowledge.VisionDiagnosticMetadata
import runtime.mobileagent.knowledge.VisionDiagnosticPhase
import runtime.mobileagent.knowledge.VisionInput
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.PdfPageRasterizer
import runtime.mobileagent.knowledge.PdfUnitRasterizer
import runtime.mobileagent.knowledge.ProcessingUnit
import runtime.mobileagent.knowledge.RenderedPdfPage
import runtime.mobileagent.knowledge.UnitRenderLimits
import runtime.mobileagent.knowledge.MemoryBlobSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCompatibleVisionTest {
    @Test fun httpErrorKeepsReportedUsageOnBothProtocols() {
        for (format in ApiFormat.entries) {
            val pair = target("error-usage", "vision")
            val selected = pair.first.copy(apiFormat = format) to pair.second
            val response = """{"error":{"message":"rejected"},"usage":{"input_tokens":8,"output_tokens":5,"output_tokens_details":{"reasoning_tokens":3}}}"""
            val result = backend(listOf(selected), MockEngine {
                respond(response, HttpStatusCode.BadRequest, jsonHeaders())
            }).process(input(selected)) as VisionOutcome.Failed
            assertEquals(8, result.metadata.inputTokens)
            assertEquals(5, result.metadata.outputTokens)
            assertEquals(3, result.metadata.reasoningTokens)
        }
    }

    @Test
    fun successCarriesNullableUsageFactsAndReasoningSubset() {
        val selected = target("usage", "vision")
        val prefix = successBody().substringBefore(",\"usage\"")
        listOf(
            "}" to Triple<Int?, Int?, Int?>(null, null, null),
            ",\"usage\":{\"prompt_tokens\":9}}" to Triple(9, null, null),
            ",\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"completion_tokens_details\":{\"reasoning_tokens\":2}}}" to Triple(9, 3, 2),
        ).forEach { (suffix, expected) ->
            val result = backend(listOf(selected), MockEngine {
                respond(prefix + suffix, HttpStatusCode.OK, jsonHeaders())
            }).process(input(selected)) as VisionOutcome.Success
            assertEquals(expected.first, result.metadata.inputTokens)
            assertEquals(expected.second, result.metadata.outputTokens)
            assertEquals(expected.third, result.metadata.reasoningTokens)
        }
    }

    @Test
    fun truncatedResultRetainsProviderUsageIncludingReasoning() {
        val selected = target("spent", "vision")
        val body = """{"choices":[{"message":{"content":""},"finish_reason":"length"}],"usage":{"prompt_tokens":12,"completion_tokens":7,"completion_tokens_details":{"reasoning_tokens":7}}}"""
        val result = backend(listOf(selected), MockEngine {
            respond(body, HttpStatusCode.OK, jsonHeaders())
        }).process(input(selected)) as VisionOutcome.Failed
        assertEquals("REASONING_EXHAUSTED", result.metadata.errorCode)
        assertEquals(12, result.metadata.inputTokens)
        assertEquals(7, result.metadata.outputTokens)
        assertEquals(7, result.metadata.reasoningTokens)
    }

    @Test
    fun resolvesExactNondefaultFingerprintAndReturnsSuccessWithMetadata() {
        var body = ""
        var url = ""
        val secretRefs = mutableListOf<String>()
        val diagnostics = mutableListOf<VisionDiagnosticMetadata>()
        val backend = backend(
            targets = listOf(target("default", "model-default"), target("selected", "model-selected")),
            engine = MockEngine { request ->
                body = (request.body as io.ktor.http.content.TextContent).text
                url = request.url.toString()
                respond(successBody(), HttpStatusCode.OK, jsonHeaders())
            },
            secret = { ref -> secretRefs += ref; "key".toCharArray() },
        )

        val outcome = backend.process(input(target("selected", "model-selected"), diagnostics = diagnostics::add))

        assertTrue(outcome is VisionOutcome.Success)
        assertTrue(body.contains("\"model\":\"model-selected\""))
        assertTrue(body.contains("\"temperature\":0.35"))
        assertFalse(body.contains("\"temperature\":0.1"))
        assertFalse(body.contains("model-default"))
        assertEquals("https://selected.example/v1/chat/completions", url)
        assertEquals(listOf("secret-selected"), secretRefs)
        assertTrue(diagnostics.any { it.phase == VisionDiagnosticPhase.DISPATCH && it.dispatched })
        assertTrue(diagnostics.any { it.responseReceived && it.httpStatus == 200 })
        assertEquals("VISION_RESULT_PARSE", diagnostics.last().stage)
        assertEquals(9, diagnostics.last().inputTokens)
        assertEquals(3, diagnostics.last().outputTokens)
    }

    @Test
    fun changedOrAmbiguousIdentityFailsBeforeCredentialOrNetwork() {
        var secretCalls = 0
        val selected = target("selected", "same-wire-model")
        val changed = selected.copy(second = selected.second.copy(parametersJson = "{\"temperature\":1}"))
        val changedBackend = backend(
            targets = listOf(changed),
            engine = MockEngine { error("must not dispatch") },
            secret = { secretCalls++; "key".toCharArray() },
        )
        val changedOutcome = changedBackend.process(input(selected))
        assertEquals("VISION_DESTINATION_CHANGED", (changedOutcome as VisionOutcome.Failed).message)
        assertFalse(changedOutcome.metadata.dispatched)

        val duplicateBackend = backend(
            targets = listOf(selected, selected),
            engine = MockEngine { error("must not dispatch") },
            secret = { secretCalls++; "key".toCharArray() },
        )
        val duplicateOutcome = duplicateBackend.process(input(selected))
        assertEquals("VISION_DESTINATION_AMBIGUOUS", (duplicateOutcome as VisionOutcome.Failed).message)
        assertEquals(0, secretCalls)
    }

    @Test
    fun credentialFailureAndHttpFailuresAreKnownAndTyped() {
        val selected = target("selected", "vision")
        val credentialDiagnostics = mutableListOf<VisionDiagnosticMetadata>()
        val credentialOutcome = backend(
            targets = listOf(selected),
            engine = MockEngine { error("must not dispatch") },
            secret = { CharArray(0) },
        ).process(input(selected, diagnostics = credentialDiagnostics::add))
        assertEquals("SECRET_UNAVAILABLE", (credentialOutcome as VisionOutcome.Failed).message)
        assertFalse(credentialOutcome.metadata.dispatched)
        assertEquals("CREDENTIAL_RESOLUTION", credentialOutcome.metadata.stage)

        listOf(HttpStatusCode.TooManyRequests to "RATE_LIMITED", HttpStatusCode.BadRequest to "PROVIDER_REJECTED").forEach { (status, code) ->
            val outcome = backend(
                targets = listOf(selected),
                engine = MockEngine { respond("{}", status, jsonHeaders()) },
            ).process(input(selected))
            assertEquals(code, (outcome as VisionOutcome.Failed).message)
            assertEquals(status.value, outcome.metadata.httpStatus)
            assertTrue(outcome.metadata.responseReceived)
            assertEquals("RESPONSE_HEADERS", outcome.metadata.stage)
        }
    }

    @Test
    fun malformedAndTruncatedCompleteResponsesAreKnownInvalidFailures() {
        val selected = target("selected", "vision")
        val malformed = backend(
            listOf(selected),
            MockEngine { respond("{bad", HttpStatusCode.OK, jsonHeaders()) },
        ).process(input(selected))
        assertEquals("INVALID_RESPONSE", (malformed as VisionOutcome.Failed).message)
        assertTrue(malformed.metadata.responseReceived)
        assertEquals("RESPONSE_BODY", malformed.metadata.stage)

        val truncated = backend(
            listOf(selected),
            MockEngine {
                respond(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ).process(input(selected))
        assertEquals("INVALID_RESPONSE", (truncated as VisionOutcome.Failed).message)
        assertTrue(truncated.metadata.responseReceived)
        assertEquals("RESPONSE_BODY", truncated.metadata.stage)
    }

    @Test
    fun transportCutIsDetailedUnknownAndContentCaptureIsLazyAndSanitized() {
        val selected = target("selected", "vision")
        val cut = backend(
            listOf(selected),
            MockEngine { throw IOException("network cut details") },
        ).process(input(selected))
        assertTrue(cut is VisionOutcome.Unknown)
        val unknown = cut as VisionOutcome.Unknown
        assertTrue(unknown.metadata.dispatched)
        assertFalse(unknown.metadata.responseReceived)
        assertEquals(IOException::class.java.name, unknown.metadata.exceptionType)

        val diagnostics = mutableListOf<VisionDiagnosticMetadata>()
        val captured = backend(
            listOf(selected),
            MockEngine { respond(successBody(), HttpStatusCode.OK, jsonHeaders()) },
            secret = { "do-not-log".toCharArray() },
        ).process(input(selected, diagnostics = diagnostics::add, capture = true))
        assertTrue(captured is VisionOutcome.Success)
        assertTrue(diagnostics.any { it.contentKind == "request.json" && it.content?.contains("data:image/png;base64") == true })
        assertTrue(diagnostics.any { it.contentKind == "response.json" && it.content?.contains("semanticDescription") == true })
        assertTrue(diagnostics.filter { it.content != null }.none { it.content!!.contains("do-not-log") })
        assertTrue(diagnostics.filter { it.content != null }.all { it.contentBytes != null && it.contentChars != null })
    }

    @Test
    fun forwardsFinalDispatchGateAndRechecksExactTargetWithoutHttpCall() {
        val selected = target("selected", "vision")
        var httpCalls = 0
        var gateCalls = 0
        val backend = backend(
            listOf(selected),
            MockEngine { httpCalls++; error("must not dispatch") },
        )

        val outcome = backend.process(
            input(selected).copy(beforeDispatch = { gateCalls++; false }),
        )

        assertEquals("REQUEST_CANCELLED", (outcome as VisionOutcome.Failed).message)
        assertFalse(outcome.metadata.dispatched)
        assertEquals(1, gateCalls)
        assertEquals(0, httpCalls)
    }

    @Test
    fun pauseAfterCredentialResolutionStillBlocksFinalDispatch() {
        val selected = target("selected", "vision")
        val credentialResolved = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val allowDispatch = AtomicBoolean(true)
        val diagnostics = mutableListOf<VisionDiagnosticMetadata>()
        var httpCalls = 0
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = backend(
                listOf(selected),
                MockEngine { httpCalls++; error("must not dispatch") },
                secret = {
                    credentialResolved.countDown()
                    assertTrue(releasePreparation.await(5, TimeUnit.SECONDS))
                    "key".toCharArray()
                },
            )
            val future = executor.submit<VisionOutcome> {
                backend.process(
                    input(selected, diagnostics = diagnostics::add).copy(beforeDispatch = allowDispatch::get),
                )
            }
            assertTrue(credentialResolved.await(5, TimeUnit.SECONDS))
            allowDispatch.set(false)
            releasePreparation.countDown()

            val outcome = future.get(5, TimeUnit.SECONDS)
            assertEquals("REQUEST_CANCELLED", (outcome as VisionOutcome.Failed).message)
            assertFalse(outcome.metadata.dispatched)
            assertEquals(0, httpCalls)
            assertTrue(diagnostics.none { it.dispatched })
        } finally {
            releasePreparation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun repositoryGateRunsBeforeFinalTargetRecheckWhenTargetChangesDuringPreparation() {
        val selected = target("selected", "vision")
        var targets = listOf(selected)
        var gateCalls = 0
        var httpCalls = 0
        val backend = OpenAiCompatibleVision(
            HttpClient(MockEngine { httpCalls++; error("must not dispatch") }),
            { targets },
            {
                targets = listOf(selected.copy(second = selected.second.copy(parametersJson = "{\"temperature\":1}")))
                "key".toCharArray()
            },
        )

        val outcome = backend.process(input(selected).copy(beforeDispatch = { gateCalls++; false }))

        assertEquals("REQUEST_CANCELLED", (outcome as VisionOutcome.Failed).message)
        assertFalse(outcome.metadata.dispatched)
        assertEquals(1, gateCalls)
        assertEquals(0, httpCalls)
    }

    private fun backend(
        targets: List<Pair<ProviderProfile, ModelProfile>>,
        engine: MockEngine,
        secret: suspend (String) -> CharArray = { "key".toCharArray() },
    ) = OpenAiCompatibleVision(HttpClient(engine), { targets }, secret)

    private fun target(id: String, modelId: String): Pair<ProviderProfile, ModelProfile> {
        val provider = ProviderProfile(
            id = "provider-$id",
            name = id,
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://$id.example/v1",
            nonSecretHeaders = mapOf("X-Client" to "test"),
            secretRef = "secret-$id",
            revision = 2,
        )
        val model = ModelProfile(
            id = "profile-$id",
            providerId = provider.id,
            role = ModelRole.CHAT,
            modelId = modelId,
            capabilities = setOf("image"),
            contextLimit = 4096,
            outputLimit = 512,
            revision = 3,
            parametersJson = if (id == "selected") "{\"temperature\":0.35}" else "{\"temperature\":0.1}",
        )
        return provider to model
    }

    private fun input(
        target: Pair<ProviderProfile, ModelProfile>,
        diagnostics: (VisionDiagnosticMetadata) -> Unit = {},
        capture: Boolean = false,
    ) = VisionInput(
        assetHash = "asset-hash",
        contextHash = "context-hash",
        modelFingerprint = visionProfileBinding(target.first, target.second).fingerprint,
        bytes = byteArrayOf(1, 2, 3),
        mediaType = "image/png",
        surroundingText = "context",
        page = 1,
        section = "s",
        requestId = "request-1",
        attempt = 2,
        diagnostics = diagnostics,
        captureDiagnosticContent = capture,
    )

    /**
     * AUTO (follow the provider) must not put any output cap on the Vision
     * request; MANUAL keeps sending exactly the configured number.
     */
    @Test
    fun automaticOutputLimitSendsNoCapOnTheVisionRequest() {
        var body = ""
        val auto = target("auto", "model-auto")
        val autoTarget = auto.first to auto.second.copy(outputLimit = 0, outputLimitMode = OutputLimitMode.AUTO)
        val backend = backend(
            targets = listOf(autoTarget),
            engine = MockEngine { request ->
                body = (request.body as io.ktor.http.content.TextContent).text
                respond(successBody(), HttpStatusCode.OK, jsonHeaders())
            },
        )

        val outcome = backend.process(input(autoTarget))

        assertTrue(outcome is VisionOutcome.Success, outcome.toString())
        assertFalse(body.contains("\"max_tokens\""), body)
        assertFalse(body.contains("\"max_completion_tokens\""), body)
        assertFalse(body.contains("\"max_output_tokens\""), body)
    }

    @Test
    fun manualOutputLimitIsSentOnTheVisionRequest() {
        var body = ""
        val manual = target("manual", "model-manual")
        val manualTarget = manual.first to manual.second.copy(outputLimit = 8192, outputLimitMode = OutputLimitMode.MANUAL)
        val backend = backend(
            targets = listOf(manualTarget),
            engine = MockEngine { request ->
                body = (request.body as io.ktor.http.content.TextContent).text
                respond(successBody(), HttpStatusCode.OK, jsonHeaders())
            },
        )

        val outcome = backend.process(input(manualTarget))

        assertTrue(outcome is VisionOutcome.Success, outcome.toString())
        assertTrue(body.contains("\"max_tokens\":8192"), body)
    }

    @Test
    fun modeChangeInvalidatesTheFrozenFingerprintWithoutDispatch() {
        var httpCalls = 0
        val manual = target("fp", "model-fp")
        val autoTarget = manual.first to manual.second.copy(outputLimit = 0, outputLimitMode = OutputLimitMode.AUTO)
        val backend = backend(
            targets = listOf(autoTarget),
            engine = MockEngine { httpCalls++; error("must not dispatch") },
        )
        val outcome = backend.process(input(manual))
        assertTrue(outcome is VisionOutcome.Failed, outcome.toString())
        assertEquals(0, httpCalls)
    }


    /**
     * A MANUAL profile plus a lower native advanced cap must not produce two
     * conflicting aliases: the user wrote one legal override and it wins.
     */
    @Test
    fun nativeAdvancedCapReplacesTheProfileCapOnTheVisionRequest() {
        var body = ""
        val manual = target("adv", "model-adv")
        val manualTarget = manual.first to manual.second.copy(
            outputLimit = 8192,
            outputLimitMode = OutputLimitMode.MANUAL,
            parametersJson = "{\"max_completion_tokens\":4096}",
        )
        val backend = backend(
            targets = listOf(manualTarget),
            engine = MockEngine { request ->
                body = (request.body as io.ktor.http.content.TextContent).text
                respond(successBody(), HttpStatusCode.OK, jsonHeaders())
            },
        )

        val outcome = backend.process(input(manualTarget))

        assertTrue(outcome is VisionOutcome.Success, outcome.toString())
        assertTrue(body.contains("\"max_completion_tokens\":4096"), body)
        assertFalse(body.contains("\"max_tokens\":8192"), body)
    }

    private fun successBody() =
        """{"choices":[{"message":{"content":"{\"ocrText\":\"ocr\",\"semanticDescription\":\"description\",\"tableMarkdown\":\"\",\"type\":\"image\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun f3_endToEndDensePageWirePayloadsAreBoundedAndReconstructOriginalDocument() {
        val originalText = buildString {
            for (i in 1..2000) {
                append("Section $i: This is detailed domain content paragraph $i with analysis and observations.\n")
            }
        }
        assertTrue(originalText.length > 50_000)

        val interceptedContexts = mutableListOf<String>()
        var httpRequestsCount = 0

        val selected = target("dense-e2e", "model-dense-e2e")
        val targetFingerprint = visionProfileBinding(selected.first, selected.second).fingerprint

        val mockEngine = MockEngine { request ->
            httpRequestsCount++
            val body = (request.body as io.ktor.http.content.TextContent).text
            val root = Json.parseToJsonElement(body).jsonObject
            val messages = root["messages"]!!.jsonArray
            val firstMessage = messages[0].jsonObject
            val content = firstMessage["content"]!!.jsonArray
            val textPart = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.jsonObject["text"]!!.jsonPrimitive.content
            val extractedContext = textPart.substringAfter("<context>").substringBefore("</context>")
            interceptedContexts += extractedContext

            respond(
                """{"choices":[{"message":{"content":"{\"ocrText\":\"ocr\",\"semanticDescription\":\"description\",\"tableMarkdown\":\"\",\"type\":\"image\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }

        val visionBackend = backend(listOf(selected), mockEngine)

        val rasterizer = object : PdfPageRasterizer, PdfUnitRasterizer {
            override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> =
                pages.map { RenderedPdfPage(it, byteArrayOf(1), "image/png", 100, 100) }
            override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage =
                RenderedPdfPage(unit.page, byteArrayOf(1, 2, 3), "image/png", 100, 100)
        }

        JdbcConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val repo = KnowledgeRepository(
                db = db,
                blobs = blobs,
                pdfRasterizer = rasterizer,
                vision = visionBackend,
                visionModelFingerprint = targetFingerprint,
                visionBinding = { visionProfileBinding(selected.first, selected.second) },
            )

            val kb = repo.ensureDefaultBase()
            val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "f3-e2e-batch")
            val pdfBytes = complexPagePdf(originalText)
            val job = repo.importBytes("dense.pdf", "application/pdf", pdfBytes, false, kb, pauseAt = ImportStage.COPYING)
            repo.bindJobToBatch(batch, job, "dense.pdf")
            repo.authorizeBatchVision(batch, targetFingerprint)

            // First run
            repo.processBatch(batch, true)

            // Assertions on intercepted wire payloads:
            // 1. Multiple units planned for the dense page
            assertTrue(interceptedContexts.size >= 2, "Expected multiple units, got: ${interceptedContexts.size}")

            // 2. Each wire payload's <context> is strictly bounded (<= 8,500 characters)
            assertTrue(
                interceptedContexts.all { it.length <= 8_500 },
                "Wire <context> text must be bounded to <= 8,500 chars, got: ${interceptedContexts.map { it.length }}"
            )

            // 3. Complete text coverage: concatenation of all intercepted contexts equals original text exactly
            val reconstructed = interceptedContexts.joinToString("")
            assertEquals(originalText.trim(), reconstructed, "Wire context slices must reconstruct original text exactly")

            // 4. Recovery/re-run sends 0 additional HTTP requests for already processed units
            val requestsAfterFirstRun = httpRequestsCount
            repo.processBatch(batch, true)
            assertEquals(requestsAfterFirstRun, httpRequestsCount, "Re-run must send 0 additional HTTP requests")
        }
    }

    @Test
    fun f3_endToEndOrdinaryNativeAndScannedPageWireVerification() {
        val selected = target("ord-e2e", "model-ord-e2e")
        val targetFingerprint = visionProfileBinding(selected.first, selected.second).fingerprint
        var httpRequestsCount = 0
        val interceptedContexts = mutableListOf<String>()

        val mockEngine = MockEngine { request ->
            httpRequestsCount++
            val body = (request.body as io.ktor.http.content.TextContent).text
            val root = Json.parseToJsonElement(body).jsonObject
            val messages = root["messages"]!!.jsonArray
            val firstMessage = messages[0].jsonObject
            val content = firstMessage["content"]!!.jsonArray
            val textPart = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.jsonObject["text"]!!.jsonPrimitive.content
            val extractedContext = textPart.substringAfter("<context>").substringBefore("</context>")
            interceptedContexts += extractedContext

            respond(
                """{"choices":[{"message":{"content":"{\"ocrText\":\"scanned content\",\"semanticDescription\":\"scanned page\",\"tableMarkdown\":\"\",\"type\":\"image\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }

        val visionBackend = backend(listOf(selected), mockEngine)
        val rasterizer = object : PdfPageRasterizer, PdfUnitRasterizer {
            override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> =
                pages.map { RenderedPdfPage(it, byteArrayOf(1), "image/png", 100, 100) }
            override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage =
                RenderedPdfPage(unit.page, byteArrayOf(1, 2, 3), "image/png", 100, 100)
        }

        JdbcConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val repo = KnowledgeRepository(
                db = db,
                blobs = blobs,
                pdfRasterizer = rasterizer,
                vision = visionBackend,
                visionModelFingerprint = targetFingerprint,
                visionBinding = { visionProfileBinding(selected.first, selected.second) },
            )

            // 1. Ordinary native page (text only, needsVision = false)
            val kb = repo.ensureDefaultBase()
            val nativeBatch = repo.beginBatch(kb, ImportBatchKind.FILES, "native-batch")
            val nativePdf = runtime.mobileagent.knowledge.PdfParser.writeSimpleTextPdf("Pure native text document without visual elements.")
            val nativeJob = repo.importBytes("native.pdf", "application/pdf", nativePdf, false, kb, pauseAt = ImportStage.COPYING)
            repo.bindJobToBatch(nativeBatch, nativeJob, "native.pdf")
            repo.authorizeBatchVision(nativeBatch, targetFingerprint)
            repo.processBatch(nativeBatch, true)

            // Pure native page must NOT dispatch any HTTP requests to vision provider!
            assertEquals(0, httpRequestsCount, "Native page without visual elements must produce 0 vision requests")
            assertEquals(1, repo.batchPipelineProgress(nativeBatch).published)

            // 2. Scanned page (image only, needsVision = true)
            val scannedBatch = repo.beginBatch(kb, ImportBatchKind.FILES, "scanned-batch")
            val scannedPdf = runtime.mobileagent.knowledge.PdfParser.writePdfWithImageXObject("")
            val scannedJob = repo.importBytes("scan.pdf", "application/pdf", scannedPdf, false, kb, pauseAt = ImportStage.COPYING)
            repo.bindJobToBatch(scannedBatch, scannedJob, "scan.pdf")
            repo.authorizeBatchVision(scannedBatch, targetFingerprint)
            repo.processBatch(scannedBatch, true)

            // Scanned page dispatches exactly 1 bounded wire request
            assertEquals(1, httpRequestsCount, "Scanned page must dispatch exactly 1 vision request")
            assertEquals(1, interceptedContexts.size)
            assertTrue(interceptedContexts[0].isEmpty(), "Scanned page wire context should have empty surrounding text")
            assertEquals(1, repo.batchPipelineProgress(scannedBatch).published)

            // Recovery/re-run sends 0 additional HTTP requests
            repo.processBatch(scannedBatch, true)
            assertEquals(1, httpRequestsCount, "Re-run of scanned page must send 0 additional HTTP requests")
        }
    }

    @Test
    fun f3_endToEndMixedPageWireVerification() {
        val selected = target("mixed-e2e", "model-mixed-e2e")
        val targetFingerprint = visionProfileBinding(selected.first, selected.second).fingerprint
        var httpRequestsCount = 0
        val interceptedContexts = mutableListOf<String>()

        val mockEngine = MockEngine { request ->
            httpRequestsCount++
            val body = (request.body as io.ktor.http.content.TextContent).text
            val root = Json.parseToJsonElement(body).jsonObject
            val messages = root["messages"]!!.jsonArray
            val firstMessage = messages[0].jsonObject
            val content = firstMessage["content"]!!.jsonArray
            val textPart = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.jsonObject["text"]!!.jsonPrimitive.content
            val extractedContext = textPart.substringAfter("<context>").substringBefore("</context>")
            interceptedContexts += extractedContext

            respond(
                """{"choices":[{"message":{"content":"{\"ocrText\":\"mixed content\",\"semanticDescription\":\"chart with caption\",\"tableMarkdown\":\"\",\"type\":\"image\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":15,"completion_tokens":8}}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }

        val visionBackend = backend(listOf(selected), mockEngine)
        val rasterizer = object : PdfPageRasterizer, PdfUnitRasterizer {
            override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> =
                pages.map { RenderedPdfPage(it, byteArrayOf(1), "image/png", 100, 100) }
            override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage =
                RenderedPdfPage(unit.page, byteArrayOf(1, 2, 3), "image/png", 100, 100)
        }

        JdbcConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val repo = KnowledgeRepository(
                db = db,
                blobs = blobs,
                pdfRasterizer = rasterizer,
                vision = visionBackend,
                visionModelFingerprint = targetFingerprint,
                visionBinding = { visionProfileBinding(selected.first, selected.second) },
            )

            val kb = repo.ensureDefaultBase()
            val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "mixed-batch")
            val mixedPdf = runtime.mobileagent.knowledge.PdfParser.writeTextAndInlineImagePdf("Figure 1: Quarterly revenue trends.")
            val job = repo.importBytes("mixed.pdf", "application/pdf", mixedPdf, false, kb, pauseAt = ImportStage.COPYING)
            repo.bindJobToBatch(batch, job, "mixed.pdf")
            repo.authorizeBatchVision(batch, targetFingerprint)
            repo.processBatch(batch, true)

            // Mixed page dispatches 1 HTTP request with native caption in <context>
            assertEquals(1, httpRequestsCount, "Mixed page must dispatch exactly 1 vision request")
            assertEquals(1, interceptedContexts.size)
            assertTrue(interceptedContexts[0].contains("Figure 1: Quarterly revenue trends."), "Wire context must contain native caption")
            assertEquals(1, repo.batchPipelineProgress(batch).published)

            // Recovery/re-run sends 0 additional HTTP requests
            repo.processBatch(batch, true)
            assertEquals(1, httpRequestsCount, "Re-run of mixed page must send 0 additional HTTP requests")
        }
    }

    private fun complexPagePdf(text: String): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val content = "BT /F1 12 Tf 10 10 Td ($escaped) Tj ET\n" + "0 0 10 10 re f\n".repeat(12)
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n" +
            "2 0 obj << /Type /Pages /Count 1 /Kids [3 0 R] >> endobj\n" +
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >> endobj\n" +
            "4 0 obj << /Length ${content.toByteArray().size} >>\nstream\n${content}endstream\nendobj\n" +
            "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF").toByteArray()
    }

    private class JdbcConnection : SqlConnection, AutoCloseable {
        private val connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        }
        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.execute()
            }
        }
        override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(SqlRow((1..result.metaData.columnCount).associate {
                        result.metaData.getColumnLabel(it) to result.getObject(it)
                    }))
                }
            }
        }
        override fun <T> transaction(block: () -> T): T {
            if (!connection.autoCommit) return block()
            val prev = connection.autoCommit
            connection.autoCommit = false
            return try {
                val result = block()
                connection.commit()
                result
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = prev
            }
        }
        override fun close() = connection.close()
    }
}
