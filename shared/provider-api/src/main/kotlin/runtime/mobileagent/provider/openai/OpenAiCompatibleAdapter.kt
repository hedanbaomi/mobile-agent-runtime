// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.ChatMessage
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
        if (secret.isEmpty()) {
            emit(ModelEvent.Failed("SECRET_UNAVAILABLE"))
            return@flow
        }
        val payload = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("stream", JsonPrimitive(true))
            put(
                "messages",
                buildJsonArray {
                    request.messages.forEach { msg ->
                        add(encodeMessage(msg))
                    }
                },
            )
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { spec ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function"))
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", JsonPrimitive(spec["name"].orEmpty()))
                                            put("description", JsonPrimitive(spec["description"].orEmpty()))
                                            put(
                                                "parameters",
                                                runCatching {
                                                    Json.parseToJsonElement(spec["parameters"] ?: "{}")
                                                }.getOrDefault(Json.parseToJsonElement("{}")),
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
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
                val status = response.status.value
                if (status == 401) {
                    emit(ModelEvent.Failed(ErrorCode.PROVIDER_UNAUTHORIZED.name))
                    return@execute
                }
                if (status == 429) {
                    emit(ModelEvent.Failed(ErrorCode.RATE_LIMITED.name))
                    return@execute
                }
                if (status >= 400) {
                    emit(
                        ModelEvent.Failed(
                            SecretRedactor.redact("Provider HTTP $status", listOf(token)),
                        ),
                    )
                    return@execute
                }
                val channel = response.bodyAsChannel()
                val toolBuf = linkedMapOf<String, Pair<String, StringBuilder>>()
                val indexToId = mutableMapOf<Int, String>()
                var sawCompleted = false
                var sawFailed = false
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    OpenAiSse.eventsFromLine(line, toolBuf, listOf(token), indexToId).forEach { event ->
                        val outgoing = when (event) {
                            is ModelEvent.Failed ->
                                ModelEvent.Failed(SecretRedactor.redact(event.sanitizedMessage, listOf(token)))
                            else -> event
                        }
                        when (outgoing) {
                            is ModelEvent.Completed -> sawCompleted = true
                            is ModelEvent.Failed -> sawFailed = true
                            else -> Unit
                        }
                        emit(outgoing)
                    }
                    if (sawCompleted || sawFailed) break
                }
                if (sawFailed || sawCompleted) {
                    return@execute
                }
                emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ModelEvent.Failed(SecretRedactor.redact(e.message ?: "network", listOf(token))))
        }
    }

    override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch {
        throw UnsupportedOperationException("Use the embedding port, not the chat adapter")
    }

    companion object {
        fun url(base: String, path: String): String = base.trimEnd('/') + path

        internal fun encodeMessage(msg: ChatMessage) = buildJsonObject {
            put("role", JsonPrimitive(msg.role))
            msg.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
            if (msg.toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        msg.toolCalls.forEach { call ->
                            add(encodeToolCall(call))
                        }
                    },
                )
                if (msg.text.isNotEmpty()) {
                    put("content", JsonPrimitive(msg.text))
                } else {
                    put("content", JsonNull)
                }
            } else if (msg.images.isNotEmpty()) {
                put(
                    "content",
                    buildJsonArray {
                        if (msg.text.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(msg.text))
                                },
                            )
                        }
                        msg.images.forEach { image ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    put(
                                        "image_url",
                                        buildJsonObject {
                                            put("url", JsonPrimitive("data:${image.mediaType};base64,${image.base64}"))
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            } else {
                put("content", JsonPrimitive(msg.text))
            }
        }

        private fun encodeToolCall(call: AssistantToolCall) = buildJsonObject {
            put("id", JsonPrimitive(call.id))
            put("type", JsonPrimitive("function"))
            put(
                "function",
                buildJsonObject {
                    put("name", JsonPrimitive(call.name))
                    put("arguments", JsonPrimitive(call.argumentsJson))
                },
            )
        }
    }
}
