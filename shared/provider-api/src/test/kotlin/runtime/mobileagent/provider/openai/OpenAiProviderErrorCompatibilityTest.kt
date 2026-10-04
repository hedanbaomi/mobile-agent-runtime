// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderConnectionResult

class OpenAiProviderErrorCompatibilityTest {
    private val formats = listOf("chat", "responses")
    private val profile = ModelProfile(
        id = "error-envelope", providerId = "provider", modelId = "demo", role = ModelRole.CHAT,
        capabilities = emptySet(), contextLimit = 4096, outputLimit = 64, revision = 1,
    )

    private suspend fun verify(
        status: Int, body: String, expected: ProviderConnectionErrorCode, retryable: Boolean = false,
    ) {
        formats.forEach { format ->
            HttpClient(MockEngine { respond(body, HttpStatusCode.fromValue(status)) }).use { client ->
                val adapter = if (format == "responses") OpenAiResponsesAdapter(client, "https://example.invalid/v1")
                    else OpenAiCompatibleAdapter(client, "https://example.invalid/v1")
                val failure = adapter.testConnection(profile, "fixture-secret".toCharArray())
                    as ProviderConnectionResult.Failure
                assertEquals(expected, failure.code, "$format: $body")
                assertEquals(status, failure.httpStatus)
                assertEquals(retryable, failure.retryable)
                assertTrue(failure.charged)
                assertFalse(failure.toString().contains("fixture-secret"))
                assertFalse(failure.toString().contains("unsupported type object"))
            }
        }
    }

    @Test
    fun nullableAndAbsentCodesDoNotHideMissingModel() = runBlocking {
        listOf("", "\"code\":null,", "\"code\":\"\",", "\"code\":\"  \",", "\"code\":42,", "\"code\":{},").forEach { code ->
            verify(404, """{"error":{$code"type":"invalid_request_error","param":"model","message":"Model not found"}}""",
                ProviderConnectionErrorCode.MODEL_NOT_FOUND)
        }
    }

    @Test
    fun malformedParametersWithOnlyTypeDoNotDisableFeatures() = runBlocking {
        listOf("stop", "stream", "tools", "response_format").forEach { param ->
            listOf("", "\"code\":null,").forEach { code ->
                verify(400, """{"error":{$code"type":"invalid_request_error","param":"$param","message":"Invalid $param value: unsupported type object"}}""",
                    ProviderConnectionErrorCode.PROVIDER_REJECTED)
            }
        }
    }

    @Test
    fun explicitCodesWinOverMisleadingMessages() = runBlocking {
        for (status in listOf(400, 404)) {
            for (code in listOf("model_not_found", "unsupported_model")) {
                verify(status, """{"error":{"code":"$code","message":"Request rejected"}}""", ProviderConnectionErrorCode.MODEL_NOT_FOUND)
            }
            verify(status, """{"error":{"code":"route_not_found","message":"Model not found; unsupported tools"}}""",
                if (status == 404) ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED else ProviderConnectionErrorCode.PROVIDER_REJECTED)
            verify(status, """{"error":{"code":"unsupported_feature","type":"invalid_request_error","param":"tools","message":"Request rejected"}}""",
                ProviderConnectionErrorCode.FEATURE_UNSUPPORTED)
        }
    }

    @Test
    fun messageFallbackRequiresErrorEvidence() = runBlocking {
        verify(404, """{"error":{"message":"Unknown endpoint"}}""", ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
        verify(404, """{"model":"model_not_found","error":{"message":"Unknown endpoint"}}""", ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
        verify(404, """{"message":"Model not found"}""", ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
        verify(404, kotlinx.serialization.json.JsonPrimitive("Model not found").toString(), ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
        verify(404, "model_not_found", ProviderConnectionErrorCode.MODEL_NOT_FOUND)
        verify(400, """{"error":{"message":"stream is not supported"}}""", ProviderConnectionErrorCode.FEATURE_UNSUPPORTED)
        verify(400, """{"error":{"message":"Invalid tool value: unsupported type object"}}""", ProviderConnectionErrorCode.PROVIDER_REJECTED)
        verify(400, """{"error":{"message":"stream value must be boolean"}}""", ProviderConnectionErrorCode.PROVIDER_REJECTED)
        verify(400, "unsupported tools", ProviderConnectionErrorCode.FEATURE_UNSUPPORTED)
    }

    @Test
    fun opaque404IsAnEndpointFailureAndNamedModelEvidenceIsRecognized() = runBlocking {
        listOf("", "rejected", """{"error":{"message":42}}""").forEach { body ->
            verify(404, body, ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
        }
        verify(404, """{"error":"Model not found"}""", ProviderConnectionErrorCode.MODEL_NOT_FOUND)
        listOf("Model 'x' not found", "No such model", "model x is not available", "The model x does not exist").forEach { message ->
            val encoded = kotlinx.serialization.json.JsonPrimitive(message)
            verify(404, """{"error":{"type":"invalid_request_error","param":"model","message":$encoded}}""",
                ProviderConnectionErrorCode.MODEL_NOT_FOUND)
        }
        listOf("list", "catalog", "endpoint", "route", "path").forEach { resource ->
            verify(404, """{"error":{"message":"model $resource not found"}}""", ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
        }
        verify(404, """{"error":{"param":"model","message":"Model 'list' not found"}}""", ProviderConnectionErrorCode.MODEL_NOT_FOUND)
        verify(404, """{"error":{"param":"model","message":"Unknown endpoint"}}""", ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED)
    }

    @Test
    fun statusPolicyStillOverridesTheEnvelope() = runBlocking {
        val body = """{"error":{"code":"model_not_found","message":"fixture-secret"}}"""
        verify(401, body, ProviderConnectionErrorCode.AUTH_FAILED)
        verify(403, body, ProviderConnectionErrorCode.AUTH_FAILED)
        verify(408, body, ProviderConnectionErrorCode.TIMEOUT, retryable = true)
        verify(429, body, ProviderConnectionErrorCode.RATE_LIMITED, retryable = true)
        verify(503, body, ProviderConnectionErrorCode.PROVIDER_REJECTED, retryable = true)
    }

    @Test
    fun responsesRuntimeUsesTheSameSanitizedClassification() = runBlocking {
        listOf(
            404 to """{"error":{"code":null,"type":"invalid_request_error","param":"model","message":"Model not found"}}""" to ProviderConnectionErrorCode.MODEL_NOT_FOUND,
            400 to """{"error":{"type":"invalid_request_error","param":"stop","message":"Invalid stop value: unsupported type object"}}""" to ProviderConnectionErrorCode.PROVIDER_REJECTED,
            400 to """{"error":{"code":"unsupported_model","message":"fixture-secret"}}""" to ProviderConnectionErrorCode.MODEL_NOT_FOUND,
        ).forEach { (response, expected) ->
            HttpClient(MockEngine { respond(response.second, HttpStatusCode.fromValue(response.first)) }).use { client ->
                val events = OpenAiResponsesAdapter(client, "https://example.invalid/v1").stream(
                    ModelRequest("demo", listOf(ChatMessage("user", "hi")), stream = false),
                    "fixture-secret".toCharArray(),
                ).toList()
                assertEquals(listOf(ModelEvent.Failed(expected.name)), events)
            }
        }
    }
}
