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

class OpenAiCompatibleVisionTest {
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

    private fun successBody() =
        """{"choices":[{"message":{"content":"{\"ocrText\":\"ocr\",\"semanticDescription\":\"description\",\"tableMarkdown\":\"\",\"type\":\"image\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
}
