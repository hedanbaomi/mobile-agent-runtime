// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.provider.ProviderConnectionResult

/**
 * Responses probes must use their own task-local budget on every entry point
 * and must not judge a legitimate business output cap as invalid.
 */
class ResponsesProbeBudgetTest {
    private fun profile(mode: OutputLimitMode, limit: Int, parametersJson: String = "{}") = ModelProfile(
        id = "model.rs", providerId = "provider.rs", role = ModelRole.CHAT, modelId = "rs-chat",
        capabilities = setOf("stream"), contextLimit = 32_768, outputLimit = limit, revision = 1,
        outputLimitMode = mode, parametersJson = parametersJson,
    )

    private val jsonProbeBody =
        "{\"status\":\"completed\",\"output_text\":\"ok\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}"

    /** A streamed probe must answer in the transport it asked for. */
    private val streamProbeBody =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"

    /** The forced no-op tool probe needs a real tool call in the response. */
    private val toolProbeBody =
        "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"mar_probe_noop\",\"arguments\":\"{}\"}]}"

    private fun engine(captured: MutableList<String>) = MockEngine { request ->
        val body = (request.body as io.ktor.http.content.TextContent).text
        captured += body
        val (payload, contentType) = when {
            body.contains("mar_probe_noop") -> toolProbeBody to "application/json"
            body.contains("\"input_image\"") -> jsonProbeBody to "application/json"
            body.contains("\"stream\":true") -> streamProbeBody to "text/event-stream"
            else -> jsonProbeBody to "application/json"
        }
        respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType))
    }

    private fun adapter(captured: MutableList<String>) =
        OpenAiResponsesAdapter(HttpClient(engine(captured)), "https://example.invalid/v1")

    @Test
    fun autoAndManualProbesUseTheirOwnTaskCap() = runBlocking {
        val autoCaptured = mutableListOf<String>()
        val auto = adapter(autoCaptured).testConnection(profile(OutputLimitMode.AUTO, 0), "token".toCharArray())
        assertTrue(auto is ProviderConnectionResult.Success, auto.toString())
        assertEquals(1, autoCaptured.size)
        assertTrue(autoCaptured.single().contains("\"max_output_tokens\":64"), autoCaptured.single())

        val manualCaptured = mutableListOf<String>()
        val manual = adapter(manualCaptured).testConnection(profile(OutputLimitMode.MANUAL, 32), "token".toCharArray())
        assertTrue(manual is ProviderConnectionResult.Success, manual.toString())
        assertTrue(manualCaptured.single().contains("\"max_output_tokens\":32"), manualCaptured.single())
    }

    /** A legitimate large business cap must not make the probe an invalid config. */
    @Test
    fun legitimateLargeBusinessCapDoesNotBreakTheProbe() = runBlocking {
        val captured = mutableListOf<String>()
        val result = adapter(captured).testConnection(
            profile(OutputLimitMode.MANUAL, 8000, "{\"max_output_tokens\":8192,\"temperature\":0.3}"),
            "token".toCharArray(),
        )
        assertTrue(result is ProviderConnectionResult.Success, result.toString())
        val body = captured.single()
        assertTrue(body.contains("\"max_output_tokens\":64"), body)
        assertTrue(body.contains("\"temperature\":0.3"), body)
        assertFalse(body.contains("\"max_output_tokens\":8192"), body)
    }

    /** The legacy alias form keeps the task-local cap too. */
    @Test
    fun legacyAliasConnectionProbeKeepsTheTaskCap() = runBlocking {
        val captured = mutableListOf<String>()
        val result = adapter(captured).testConnection(
            profile(OutputLimitMode.MANUAL, 8000, "{\"max_tokens\":8192}"),
            "token".toCharArray(),
        )
        assertTrue(result is ProviderConnectionResult.Success, result.toString())
        assertEquals(1, captured.size, result.toString())
        assertTrue(captured.single().contains("\"max_output_tokens\":64"), captured.single())
    }

    /** Public profile-only probe: it classifies without spending anything. */
    @Test
    fun publicProfileOnlyProbeDoesNotDispatch() = runBlocking {
        val captured = mutableListOf<String>()
        val report = adapter(captured).probe(profile(OutputLimitMode.AUTO, 0))
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.PROFILE_ONLY, report.status)
        assertTrue(captured.isEmpty(), "a profile-only probe must not spend")
        // The capability probe (TOOLS) is covered through the public probe entry below.
    }

    /**
     * The tested profile is a value object that no transport layer can rewrite:
     * the probe still must not be the reason a profile changes, so the test
     * drives the public entry and asserts the two observable halves - the
     * instance the caller owns is untouched, and the preserved business
     * parameter is on the wire while the business cap is not.
     */
    @Test
    fun probeDoesNotMutateThePersistedParameters() = runBlocking {
        val persisted = "{\"max_output_tokens\":8192,\"temperature\":0.3}"
        val tested = profile(OutputLimitMode.MANUAL, 8000, persisted)
        val captured = mutableListOf<String>()
        val result = adapter(captured).testConnection(tested, "token".toCharArray())
        assertTrue(result is ProviderConnectionResult.Success, result.toString())
        assertEquals(persisted, tested.parametersJson, "the tested instance must not be rewritten")
        val body = captured.single()
        assertTrue(body.contains("\"temperature\":0.3"), body)
        assertFalse(body.contains("\"max_output_tokens\":8192"), body)
    }

    /**
     * Public granted probe: every declared feature really dispatches, each
     * request carries its own task-local cap, and the reported capabilities come
     * from those responses.  `probeFeature` is private, so the only honest proof
     * is the public `probe(profile, secret, consent)` entry.
     */
    @Test
    fun grantedPublicProbeDispatchesEachFeatureAndClassifiesTheResponses() = runBlocking {
        val captured = mutableListOf<String>()
        val rich = profile(OutputLimitMode.AUTO, 0).copy(capabilities = setOf("stream", "tools", "image"))
        val report = adapter(captured).probe(rich, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "probe-public-test")

        // metadata, STREAM, TOOLS, IMAGE - one real request each.
        assertEquals(4, captured.size, "captured=$captured report=$report")
        val caps = captured.map { body ->
            Regex("\"max_output_tokens\":(\\d+)").find(body)?.groupValues?.get(1)?.toInt()
        }
        assertEquals(listOf(64, 64, 128, 64), caps, captured.toString())
        assertTrue(captured[1].contains("\"stream\":true"), captured[1])
        assertTrue(captured[2].contains("tool_choice"), captured[2])
        assertTrue(captured[3].contains("input_image"), captured[3])

        assertTrue(report.charged, report.toString())
        assertTrue(report.supportsStream, report.toString())
        assertTrue(report.supportsTools, report.toString())
        assertTrue(report.supportsImages, report.toString())
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.SUCCEEDED, report.status, report.toString())
    }

    /** Without consent the probe must classify from metadata only - no spend. */
    @Test
    fun publicProbeWithoutConsentDoesNotDispatch() = runBlocking {
        val captured = mutableListOf<String>()
        val rich = profile(OutputLimitMode.AUTO, 0).copy(capabilities = setOf("stream", "tools", "image"))
        val report = adapter(captured).probe(rich, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.NOT_GRANTED, "probe-no-consent")
        assertTrue(captured.isEmpty(), "consent is required before any request: $report")
    }
}
