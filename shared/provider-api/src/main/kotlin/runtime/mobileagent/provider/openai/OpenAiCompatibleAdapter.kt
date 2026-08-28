// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.SecretRedactor

class OpenAiCompatibleAdapter(
    private val http: HttpClient,
    private val baseUrl: String,
) : ModelAdapter {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(profile: ModelProfile): CapabilityReport {
        return CapabilityReport(
            modelId = profile.modelId,
            supportsStream = "stream" in profile.capabilities,
            supportsTools = "tools" in profile.capabilities,
            supportsImages = "image" in profile.capabilities,
            source = "manual-plus-profile",
            probedAt = Utc.nowIso(),
        )
    }

    override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("stream", JsonPrimitive(true))
            put(
                "messages",
                buildJsonArray {
                    request.messages.forEach { msg ->
                        add(
                            buildJsonObject {
                                msg.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                            },
                        )
                    }
                },
            )
        }
        val token = String(secret)
        try {
            http.preparePost(url(baseUrl, "/chat/completions")) {
                contentType(ContentType.Application.Json)
                headers {
                    append("Authorization", "Bearer $token")
                }
                setBody(payload.toString())
            }.execute { response ->
                if (response.status.value == 401) {
                    emit(ModelEvent.Failed("Provider rejected credentials"))
                    return@execute
                }
                if (response.status.value >= 500) {
                    emit(ModelEvent.Failed(SecretRedactor.redact(response.status.toString(), listOf(token))))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                val toolBuf = linkedMapOf<String, Pair<String, StringBuilder>>()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        emit(ModelEvent.Completed)
                        break
                    }
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    val delta = choice["delta"]?.jsonObject
                    delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { emit(ModelEvent.TextDelta(it)) }
                    val toolCalls = delta?.get("tool_calls")?.jsonArray
                    toolCalls?.forEach { call ->
                        val c = call.jsonObject
                        val id = c["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val name = c["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                        val args = c["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                        val acc = toolBuf.getOrPut(id) { name to StringBuilder() }
                        acc.second.append(args)
                        emit(ModelEvent.ToolCallDelta(id, acc.first.ifBlank { name }, acc.second.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            emit(ModelEvent.Failed(SecretRedactor.redact(e.message ?: "network", listOf(token))))
        }
    }

    override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch {
        throw UnsupportedOperationException("Use the embedding port, not the chat adapter")
    }

    companion object {
        fun url(base: String, path: String): String = base.trimEnd('/') + path
        fun client(): HttpClient = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
            }
        }
    }
}
