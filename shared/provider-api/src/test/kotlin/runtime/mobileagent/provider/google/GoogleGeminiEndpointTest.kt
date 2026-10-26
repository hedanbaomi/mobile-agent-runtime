// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.google

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory

class GoogleGeminiEndpointTest {
    private val endpoints = mapOf(
        ApiFormat.GOOGLE_AI_STUDIO to "https://native.example.invalid/v1beta",
        ApiFormat.GOOGLE_VERTEX_AI to "https://native.example.invalid/v1/projects/demo/locations/global/publishers/google",
        ApiFormat.GOOGLE_VERTEX_EXPRESS to "https://native.example.invalid/v1/publishers/google",
    )

    @Test fun `plain model IDs retain 200 character support with optional resource prefix`() {
        endpoints.forEach { (format, base) ->
            listOf(1, 100, 101, 200).forEach { length ->
                val model = "a".repeat(length)
                listOf(model, "models/$model").forEach { input ->
                    OpenAiAdapterFactory.validateDraftSave(format, base, true, ModelRole.CHAT, input)
                    assertEquals("$base/models/$model:generateContent",
                        GoogleGeminiAdapter.requestEndpoint(format, base, input))
                }
            }
        }
    }

    @Test fun `namespace and model segment bounds reject overlong and unsafe paths`() {
        val model = "m".repeat(200)
        endpoints.forEach { (format, base) ->
            listOf("g", "g".repeat(100)).forEach { namespace ->
                val id = "$namespace/$model"
                listOf(id, "models/$id").forEach { input ->
                    OpenAiAdapterFactory.validateDraftSave(format, base, true, ModelRole.CHAT, input)
                    assertEquals("$base/models/$id:streamGenerateContent?alt=sse",
                        GoogleGeminiAdapter.requestEndpoint(format, base, input, true))
                }
            }
            listOf("google/$model", "models/google/$model").forEach { input ->
                OpenAiAdapterFactory.validateDraftSave(format, base, true, ModelRole.CHAT, input)
                val expectedModel = if (format == ApiFormat.GOOGLE_AI_STUDIO) "google/$model" else model
                assertEquals("$base/models/$expectedModel:streamGenerateContent?alt=sse",
                    GoogleGeminiAdapter.requestEndpoint(format, base, input, true))
            }
            listOf("a".repeat(201), "g/${"a".repeat(201)}", "google/${"a".repeat(201)}", "${"g".repeat(101)}/m",
                "g/../m", "g/.", "g/m/extra", "google/google/m", "g/m?key=x", "g/m#x", "g/m%2fx",
                "g\\m", "projects/m", "publishers/m", "models/models/m").forEach { input ->
                assertThrows(IllegalArgumentException::class.java) {
                    GoogleGeminiAdapter.requestEndpoint(format, base, input)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    OpenAiAdapterFactory.validateDraftSave(format, base, true, ModelRole.CHAT, input)
                }
            }
        }
        val expressProjectBase = "https://native.example.invalid/v1beta1/projects/demo/locations/global/publishers/google"
        OpenAiAdapterFactory.validateDraftSave(ApiFormat.GOOGLE_VERTEX_EXPRESS, expressProjectBase, true, ModelRole.CHAT, "google/gemini-4-argon")
        assertEquals(
            "$expressProjectBase/models/gemini-4-argon:generateContent",
            GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_VERTEX_EXPRESS, expressProjectBase, "google/gemini-4-argon"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiAdapterFactory.validateDraftSave(
                ApiFormat.GOOGLE_VERTEX_EXPRESS,
                expressProjectBase.replace("demo", "PROJECT_ID"),
                true,
                ModelRole.CHAT,
                "gemini-4-argon",
            )
        }
    }
}
