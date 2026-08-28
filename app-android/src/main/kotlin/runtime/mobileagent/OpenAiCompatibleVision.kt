// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionInput
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess
import runtime.mobileagent.security.AndroidSecretStore
import java.util.Base64

class OpenAiCompatibleVision(
    private val http: HttpClient,
    private val profiles: ProfileRepository,
    private val secrets: AndroidSecretStore,
) : VisionBackend {
    override fun process(input: VisionInput): VisionOutcome {
        val binding = profiles.visionBinding()
            ?: return VisionOutcome.Failed("Vision model is configured in profile but no backend is bound")
        val (provider, model) = binding
        return runBlocking {
            val secret = secrets.resolveForHost(provider.secretRef)
            try {
                call(provider.baseUrl, model.modelId, String(secret), input)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                VisionOutcome.UnknownOutcome
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    private suspend fun call(baseUrl: String, modelId: String, token: String, input: VisionInput): VisionOutcome {
        val b64 = Base64.getEncoder().encodeToString(input.bytes)
        val body = JSONObject()
            .put("model", modelId)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", "Return JSON with keys ocrText, semanticDescription, tableMarkdown, type. Surrounding text: ${input.surroundingText}"),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", "data:${input.mediaType};base64,$b64")),
                                ),
                        ),
                ),
            )
            .toString()
        val response = http.post(baseUrl.trimEnd('/') + "/chat/completions") {
            contentType(ContentType.Application.Json)
            headers { append("Authorization", "Bearer $token") }
            setBody(body)
        }
        val status = response.status.value
        if (status == 401) return VisionOutcome.Failed("PROVIDER_UNAUTHORIZED")
        if (status >= 400) return VisionOutcome.UnknownOutcome
        val text = response.bodyAsText()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return VisionOutcome.UnknownOutcome
        if (obj.has("error")) return VisionOutcome.UnknownOutcome
        val content = obj.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: return VisionOutcome.UnknownOutcome
        return VisionOutcome.Success(parseResult(content))
    }

    private fun parseResult(content: String): VisionSuccess {
        val jsonish = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { JSONObject(jsonish) }.getOrNull()
        return VisionSuccess(
            ocrText = obj?.optString("ocrText").orEmpty(),
            semanticDescription = obj?.optString("semanticDescription")?.ifBlank { jsonish } ?: jsonish,
            tableMarkdown = obj?.optString("tableMarkdown").orEmpty(),
            type = obj?.optString("type")?.ifBlank { "image" } ?: "image",
        )
    }
}
