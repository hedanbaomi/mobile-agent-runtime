// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.InputModality
import runtime.mobileagent.domain.ModelFeature
import runtime.mobileagent.domain.ModelOperation
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.withEndpoint
import runtime.mobileagent.domain.isChatEndpoint
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.CapabilityProbeStatus
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckResult
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterMerger
import runtime.mobileagent.provider.ProbeConsent
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderConnectionResult
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.SecretRedactor

/**
 * OpenAI-compatible adapter with an intentionally small, explicit wire surface.
 *
 * The old `(HttpClient, String)` constructor and ModelAdapter methods remain
 * source-compatible. User parameters are merged into the actual request body;
 * runtime fields are inserted last and cannot be replaced by custom JSON.
 */
class OpenAiCompatibleAdapter(
    private val http: HttpClient,
    private val baseUrl: String,
    private val headerSecretResolver: HeaderSecretResolver? = null,
    private val defaultHeaders: Map<String, RequestHeaderValue> = emptyMap(),
) : ModelAdapter {

    override suspend fun probe(profile: ModelProfile): CapabilityReport =
        CapabilityReport(
            modelId = profile.modelId,
            supportsStream = "stream" in profile.capabilities,
            supportsTools = "tools" in profile.capabilities,
            supportsImages = "image" in profile.capabilities,
            source = "profile-only",
            probedAt = Utc.nowIso(),
            charged = false,
            status = CapabilityProbeStatus.PROFILE_ONLY,
            checks = profileChecks(profile),
        )

    /**
     * Run one minimal non-streaming chat request through the same payload,
     * header and endpoint path used by normal model traffic.  This deliberately
     * does not call the capability sub-probes: a provider may accept chat while
     * rejecting metadata, streaming, tools, or images.
     */
    override suspend fun testConnection(
        profile: ModelProfile,
        secret: CharArray,
        operationId: String,
    ): ProviderConnectionResult {
        if (secret.isEmpty()) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE,
                retryable = false,
            )
        }
        val configured = profile.withEndpoint()
        if (!configured.isChatEndpoint() || configured.modelId.isBlank()) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.CONFIG_INVALID,
                retryable = false,
            )
        }

        val started = System.nanoTime()
        val token = secret.concatToString()
        try {
            val modelParameters = runCatching {
                Json.parseToJsonElement(configured.parametersJson).jsonObject
            }.getOrElse { throw InvalidConnectionConfigException() }
            val request = ModelRequest(
                modelId = configured.modelId,
                messages = listOf(ChatMessage(role = "user", text = "Reply with ok.")),
                stream = false,
                parameters = runtime.mobileagent.provider.ParameterLayers(modelParameters = modelParameters),
                operationId = operationId,
                // Probes never spend the user's full output budget on a two-word answer.
                outputTokenLimit = minOf(configured.outputLimit.coerceAtLeast(1), CONNECTION_PROBE_MAX_OUTPUT_TOKENS),
            )
            val payload = buildPayload(request, includeImageBytes = true)
            val resolved = resolveHeaders(token, emptyMap())
            return withTimeout(CONNECTION_TIMEOUT_MS) {
                http.preparePost(url(baseUrl, "/chat/completions")) {
                    contentType(ContentType.Application.Json)
                    headers {
                        append(HttpHeaders.Accept, "text/event-stream, application/json")
                        resolved.values.forEach { (name, value) -> append(name, value) }
                    }
                    setBody(payload.toString())
                }.execute { response ->
                    val status = response.status.value
                    if (status !in 200..299) {
                        return@execute connectionFailureForHttp(status)
                    }
                    val raw = readBounded(response.bodyAsChannel())
                    val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                    val valid = if (contentType.contains("text/event-stream")) {
                        parseConnectionSse(raw)
                    } else {
                        parseChatProbe(raw, requireToolCall = false)
                    }
                    if (valid) {
                        ProviderConnectionResult.Success(
                            latencyMs = elapsedMillis(started),
                            charged = true,
                        )
                    } else {
                        ProviderConnectionResult.Failure(
                            code = ProviderConnectionErrorCode.INVALID_RESPONSE,
                            retryable = false,
                            charged = true,
                        )
                    }
                }
            }
        } catch (_: InvalidConnectionConfigException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.CONFIG_INVALID,
                retryable = false,
            )
        } catch (_: SecretUnavailableException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE,
                retryable = false,
            )
        } catch (_: InvalidHeaderException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.CONFIG_INVALID,
                retryable = false,
            )
        } catch (error: AppException) {
            return ProviderConnectionResult.Failure(
                code = if (error.error.code == ErrorCode.SECRET_UNAVAILABLE) {
                    ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE
                } else {
                    ProviderConnectionErrorCode.CONFIG_INVALID
                },
                retryable = false,
            )
        } catch (_: TimeoutCancellationException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.TIMEOUT,
                retryable = true,
                charged = true,
            )
        } catch (error: SocketTimeoutException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.TIMEOUT,
                retryable = true,
                charged = true,
            )
        } catch (_: SSLException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.TLS_FAILURE,
                retryable = false,
                charged = false,
            )
        } catch (_: UnknownHostException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.NETWORK_UNREACHABLE,
                retryable = true,
                charged = false,
            )
        } catch (_: ConnectException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.NETWORK_UNREACHABLE,
                retryable = true,
                charged = false,
            )
        } catch (_: IOException) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.NETWORK_UNREACHABLE,
                retryable = true,
                charged = false,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.UNKNOWN,
                retryable = false,
                charged = true,
            )
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    /**
     * A live probe is deliberately a separate opt-in operation. Metadata and
     * each declared chat capability are checked independently. A successful
     * metadata response is not evidence that the model can stream, call tools,
     * or consume images; those claims require a minimally valid response for
     * the corresponding request.
     */
    override suspend fun probe(
        profile: ModelProfile,
        secret: CharArray,
        consent: ProbeConsent,
        operationId: String,
    ): CapabilityReport {
        val profileReport = probe(profile)
        if (consent != ProbeConsent.GRANTED) {
            return profileReport.copy(
                source = "profile-only;consent-required;metadata=not-run;stream=not-run;tools=not-run;image=not-run",
                operationId = operationId,
                checks = profileChecks(profile),
            )
        }
        if (secret.isEmpty()) {
            return profileReport.copy(
                supportsStream = false,
                supportsTools = false,
                supportsImages = false,
                source = "metadata=not-run;stream=not-run;tools=not-run;image=not-run;secret=unavailable",
                status = CapabilityProbeStatus.FAILED,
                operationId = operationId,
                checks = profileChecks(profile).map { check ->
                    if (check.capability == CapabilityCheck.METADATA) {
                        check.copy(status = CapabilityCheckStatus.FAILED)
                    } else {
                        check
                    }
                },
            )
        }
        val token = secret.concatToString()
        return try {
            val resolved = resolveHeaders(token, emptyMap())
            val modelPath = "/models/${URLEncoder.encode(profile.modelId, Charsets.UTF_8.name()).replace("+", "%20")}"
            http.prepareGet(url(baseUrl, modelPath)) {
                headers {
                    resolved.values.forEach { (name, value) -> append(name, value) }
                }
            }.execute { response ->
                val status = response.status.value
                val metadata = if (status in 200..299) {
                    val body = readBounded(response.bodyAsChannel())
                    if (metadataMatches(body, profile.modelId)) {
                        MetadataProbeResult(
                            summary = "verified",
                            verified = true,
                            status = CapabilityCheckStatus.VERIFIED,
                        )
                    } else {
                        MetadataProbeResult(
                            summary = "invalid-response",
                            verified = false,
                            status = CapabilityCheckStatus.FAILED,
                        )
                    }
                } else {
                    MetadataProbeResult(
                        summary = "http-$status",
                        verified = false,
                        httpStatus = status,
                        status = CapabilityCheckStatus.FAILED,
                    )
                }
                if (!metadata.verified) {
                    profileReport.copy(
                        supportsStream = false,
                        supportsTools = false,
                        supportsImages = false,
                        source = "metadata=${metadata.summary};stream=not-run;tools=not-run;image=not-run",
                        status = CapabilityProbeStatus.FAILED,
                        charged = metadata.charged,
                        operationId = operationId,
                        checks = listOf(
                            CapabilityCheckResult(
                                capability = CapabilityCheck.METADATA,
                                status = metadata.status,
                                httpStatus = metadata.httpStatus,
                            ),
                            CapabilityCheckResult(CapabilityCheck.STREAM, CapabilityCheckStatus.NOT_RUN),
                            CapabilityCheckResult(CapabilityCheck.TOOLS, CapabilityCheckStatus.NOT_RUN),
                            CapabilityCheckResult(CapabilityCheck.IMAGE, CapabilityCheckStatus.NOT_RUN),
                        ),
                    )
                } else {
                    val stream = probeDeclaredFeature(profile, resolved, ProbeFeature.STREAM)
                    val tools = probeDeclaredFeature(profile, resolved, ProbeFeature.TOOLS)
                    val image = probeDeclaredFeature(profile, resolved, ProbeFeature.IMAGE)
                    val featureResults = listOf(stream, tools, image)
                    profileReport.copy(
                        supportsStream = stream.supported,
                        supportsTools = tools.supported,
                        supportsImages = image.supported,
                        source = "metadata=${metadata.summary};stream=${stream.summary};tools=${tools.summary};image=${image.summary}",
                        status = capabilityProbeStatus(featureResults),
                        // A chat capability probe is potentially billable even
                        // when the provider later rejects or truncates it.
                        charged = metadata.charged || featureResults.any { it.charged },
                        operationId = operationId,
                        checks = listOf(
                            CapabilityCheckResult(CapabilityCheck.METADATA, metadata.status, metadata.httpStatus),
                            CapabilityCheckResult(CapabilityCheck.STREAM, stream.status, stream.httpStatus),
                            CapabilityCheckResult(CapabilityCheck.TOOLS, tools.status, tools.httpStatus),
                            CapabilityCheckResult(CapabilityCheck.IMAGE, image.status, image.httpStatus),
                        ),
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            profileReport.copy(
                supportsStream = false,
                supportsTools = false,
                supportsImages = false,
                source = "metadata=unknown-outcome;stream=not-run;tools=not-run;image=not-run",
                status = CapabilityProbeStatus.FAILED,
                charged = false,
                operationId = operationId,
                checks = listOf(
                    CapabilityCheckResult(CapabilityCheck.METADATA, CapabilityCheckStatus.UNKNOWN),
                    CapabilityCheckResult(CapabilityCheck.STREAM, CapabilityCheckStatus.NOT_RUN),
                    CapabilityCheckResult(CapabilityCheck.TOOLS, CapabilityCheckStatus.NOT_RUN),
                    CapabilityCheckResult(CapabilityCheck.IMAGE, CapabilityCheckStatus.NOT_RUN),
                ),
            )
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
        if (secret.isEmpty()) {
            emit(ModelEvent.Failed(ErrorCode.SECRET_UNAVAILABLE.name))
            return@flow
        }

        val token = secret.concatToString()
        try {
            val payload = buildPayload(request, includeImageBytes = true)
            val resolved = resolveHeaders(token, request.headers)
            val redactionSecrets = listOf(token) + resolved.secrets
            val streamRedactor = StreamingSecretRedactor(redactionSecrets)
            http.preparePost(url(baseUrl, "/chat/completions")) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "text/event-stream, application/json")
                    resolved.values.forEach { (name, value) -> append(name, value) }
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
                    emit(ModelEvent.Failed(if (status >= 500) "UNKNOWN_OUTCOME: Provider HTTP $status" else "Provider HTTP $status"))
                    return@execute
                }

                val responseType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                if (responseType.contains("text/event-stream")) {
                    val channel = response.bodyAsChannel()
                    val toolBuf = linkedMapOf<String, Pair<String, StringBuilder>>()
                    val indexToId = mutableMapOf<Int, String>()
                    var sawCompleted = false
                    var sawFailed = false
                    var receivedBytes = 0L
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line(1_048_576) ?: break
                        receivedBytes += line.toByteArray(Charsets.UTF_8).size + 1L
                        require(receivedBytes <= 8_388_608L) { "Provider response exceeds limit" }
                        val parsedEvents = OpenAiSse.eventsFromLine(
                            line,
                            toolBuf,
                            redactionSecrets,
                            indexToId,
                        )
                        for (event in parsedEvents) {
                            val terminal = emitRedacted(event, streamRedactor, redactionSecrets)
                            when (terminal) {
                                ModelEvent.Completed -> sawCompleted = true
                                is ModelEvent.Failed -> sawFailed = true
                                null -> Unit
                                else -> Unit
                            }
                            if (terminal != null) break
                        }
                        if (sawCompleted || sawFailed) break
                    }
                    if (!sawFailed && !sawCompleted) {
                        // EOF is not a successful completion.  In particular, do not flush a
                        // suffix which could still become a credential on a later delta.
                        streamRedactor.discard()
                        emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
                    }
                } else {
                    emitJsonResponse(readBounded(response.bodyAsChannel()), redactionSecrets)
                }
            }
        } catch (e: AppException) {
            emit(ModelEvent.Failed(e.error.code.name))
        } catch (_: SecretUnavailableException) {
            emit(ModelEvent.Failed(ErrorCode.SECRET_UNAVAILABLE.name))
        } catch (_: InvalidHeaderException) {
            emit(ModelEvent.Failed(ErrorCode.INVALID_CONFIG.name))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    /** Emit text only after cross-delta credential redaction is safe. */
    private suspend fun FlowCollector<ModelEvent>.emitRedacted(
        event: ModelEvent,
        redactor: StreamingSecretRedactor,
        secrets: List<String>,
    ): ModelEvent? {
        return when (event) {
            is ModelEvent.TextDelta -> {
                val safe = redactor.accept(event.text)
                if (safe.isNotEmpty()) emit(ModelEvent.TextDelta(safe))
                null
            }
            is ModelEvent.ReasoningDelta -> {
                val safe = redactor.accept(event.text)
                if (safe.isNotEmpty()) emit(ModelEvent.ReasoningDelta(safe))
                null
            }
            is ModelEvent.ToolCallDelta -> {
                val parsed = runCatching { Json.parseToJsonElement(event.argumentsJson) }.getOrNull()
                val hasCredential = containsCredentialText(event.callId, secrets) ||
                    containsCredentialText(event.name, secrets) ||
                    containsCredentialText(event.argumentsJson, secrets) ||
                    (parsed != null && containsCredentialJson(parsed, secrets))
                if (hasCredential || parsed == null || parsed !is JsonObject) {
                    redactor.discard()
                    val failure = ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name)
                    emit(failure)
                    failure
                } else {
                    emit(event)
                    null
                }
            }
            is ModelEvent.Failed -> {
                redactor.discard()
                val failure = ModelEvent.Failed(SecretRedactor.redact(event.sanitizedMessage, secrets))
                emit(failure)
                failure
            }
            ModelEvent.Completed -> {
                val safeTail = redactor.finish()
                if (safeTail.isNotEmpty()) emit(ModelEvent.TextDelta(safeTail))
                emit(ModelEvent.Completed)
                ModelEvent.Completed
            }
            else -> {
                emit(event)
                null
            }
        }
    }

    private suspend fun readBounded(channel: ByteReadChannel): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            require(output.size() + count <= 8_388_608) { "Provider response exceeds limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch {
        validateEmbeddingRequest(request)
        if (secret.isEmpty()) throw embeddingFailure(ErrorCode.SECRET_UNAVAILABLE, "Embedding secret unavailable")

        val token = secret.concatToString()
        try {
            val payload = buildEmbeddingPayload(request)
            val resolved = resolveHeaders(token, emptyMap())
            return http.preparePost(url(baseUrl, "/embeddings")) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    resolved.values.forEach { (name, value) -> append(name, value) }
                }
                setBody(payload)
            }.execute { response ->
                val status = response.status.value
                when {
                    status == 401 -> throw embeddingFailure(
                        ErrorCode.PROVIDER_UNAUTHORIZED,
                        "Embedding provider unauthorized",
                    )
                    status == 429 -> throw embeddingFailure(
                        ErrorCode.RATE_LIMITED,
                        "Embedding provider rate limited",
                    )
                    status !in 200..299 -> throw embeddingFailure(
                        ErrorCode.UNKNOWN_OUTCOME,
                        "Embedding provider HTTP $status",
                    )
                }
                parseEmbeddingResponse(readBounded(response.bodyAsChannel()), request.inputs.size)
            }
        } catch (e: AppException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: SecretUnavailableException) {
            throw embeddingFailure(ErrorCode.SECRET_UNAVAILABLE, "Embedding secret unavailable")
        } catch (_: InvalidHeaderException) {
            throw embeddingFailure(ErrorCode.INVALID_CONFIG, "Embedding request headers are invalid")
        } catch (_: Exception) {
            // A response may have been accepted by the provider before a local
            // transport or schema failure.  Never turn that boundary into a
            // successful or automatically retryable result.
            throw embeddingFailure(ErrorCode.UNKNOWN_OUTCOME, "Embedding outcome is unknown")
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    /**
     * Pure, body-only inspector preview. It calls the exact same builder used
     * for the wire request, replacing image bytes with metadata placeholders.
     * Authorization and custom secret values are never part of this body.
     */
    override fun previewRequest(request: ModelRequest): String =
        SecretRedactor.redact(buildPayload(request, includeImageBytes = false).toString())

    private fun buildPayload(request: ModelRequest, includeImageBytes: Boolean): JsonObject {
        val runtimeFields = linkedMapOf<String, JsonElement>(
            "model" to JsonPrimitive(request.modelId),
            "messages" to buildJsonArray {
                request.messages.forEach { add(encodeMessage(it, includeImageBytes)) }
            },
            "stream" to JsonPrimitive(request.stream),
        )
        if (request.tools.isNotEmpty()) {
            runtimeFields["tools"] = buildJsonArray {
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
                                        }.getOrElse {
                                            throw InvalidHeaderException("tool parameters are not valid JSON")
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
        val merged = ParameterMerger.merge(
            layers = request.parameters,
            legacyExtras = request.extra,
            runtimeFields = runtimeFields,
            operationId = request.operationId,
        )
        return applyOutputTokenLimit(merged, request)
    }

    private fun applyOutputTokenLimit(merged: JsonObject, request: ModelRequest): JsonObject {
        val budget = request.outputTokenLimit
        if (budget != null && budget <= 0) {
            throw invalidConfig("outputTokenLimit must be positive", request.operationId)
        }

        fun readPositiveInteger(name: String): Long? {
            val element = merged[name] ?: return null
            val primitive = element as? JsonPrimitive
            if (primitive == null || primitive.isString) {
                throw invalidConfig("$name must be a positive integer", request.operationId)
            }
            val value = primitive.content.toLongOrNull()
            if (value == null || value <= 0L || value > Int.MAX_VALUE.toLong()) {
                throw invalidConfig("$name must be a positive integer", request.operationId)
            }
            if (budget != null && value > budget.toLong()) {
                throw invalidConfig("$name exceeds outputTokenLimit", request.operationId)
            }
            return value
        }

        val maxTokens = readPositiveInteger("max_tokens")
        val maxCompletionTokens = readPositiveInteger("max_completion_tokens")
        if (maxTokens != null && maxCompletionTokens != null) {
            throw invalidConfig("max_tokens and max_completion_tokens cannot both be set", request.operationId)
        }
        if (budget != null && maxTokens == null && maxCompletionTokens == null) {
            val fields = linkedMapOf<String, JsonElement>()
            fields.putAll(merged)
            fields["max_tokens"] = JsonPrimitive(budget)
            return JsonObject(fields)
        }
        return merged
    }

    private fun validateEmbeddingRequest(request: EmbeddingRequest) {
        if (request.modelId.isBlank() || request.modelId.length > MAX_EMBEDDING_MODEL_CHARS) {
            throw invalidConfig("Embedding model id is invalid", "embedding")
        }
        if (request.inputs.isEmpty() || request.inputs.size > MAX_EMBEDDING_INPUTS) {
            throw invalidConfig("Embedding input batch is invalid", "embedding")
        }
        var rawBytes = request.modelId.toByteArray(Charsets.UTF_8).size.toLong()
        request.inputs.forEach { input ->
            if (input.length > MAX_EMBEDDING_INPUT_CHARS) {
                throw invalidConfig("Embedding input is too large", "embedding")
            }
            rawBytes += input.toByteArray(Charsets.UTF_8).size.toLong()
        }
        if (rawBytes > MAX_EMBEDDING_INPUT_BYTES) {
            throw invalidConfig("Embedding request is too large", "embedding")
        }
    }

    private fun buildEmbeddingPayload(request: EmbeddingRequest): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("input", buildJsonArray {
                request.inputs.forEach { add(JsonPrimitive(it)) }
            })
        }.toString()
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_EMBEDDING_REQUEST_BYTES) {
            throw invalidConfig("Embedding request is too large", "embedding")
        }
        return payload
    }

    private fun parseEmbeddingResponse(raw: String, expectedCount: Int): EmbeddingBatch {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalStateException("Embedding response is not an object") }
        val data = root["data"]?.let { element ->
            runCatching { element.jsonArray }.getOrNull()
        } ?: throw IllegalStateException("Embedding response data is missing")
        if (data.size != expectedCount || data.isEmpty() || data.size > MAX_EMBEDDING_INPUTS) {
            throw IllegalStateException("Embedding response count is invalid")
        }

        val vectors = arrayOfNulls<FloatArray>(expectedCount)
        var dimension = -1
        data.forEach { element ->
            val item = element.jsonObject
            val index = item["index"]?.let { indexElement ->
                val primitive = indexElement as? JsonPrimitive
                if (primitive == null || primitive.isString) null else primitive.content.toIntOrNull()
            } ?: throw IllegalStateException("Embedding response index is invalid")
            if (index !in 0 until expectedCount || vectors[index] != null) {
                throw IllegalStateException("Embedding response indexes are invalid")
            }
            val embedding = item["embedding"]?.let { embeddingElement ->
                runCatching { embeddingElement.jsonArray }.getOrNull()
            } ?: throw IllegalStateException("Embedding vector is missing")
            if (embedding.isEmpty() || embedding.size > MAX_EMBEDDING_DIMENSION) {
                throw IllegalStateException("Embedding dimension is invalid")
            }
            if (dimension < 0) dimension = embedding.size
            if (embedding.size != dimension) {
                throw IllegalStateException("Embedding dimensions do not match")
            }
            val vector = FloatArray(embedding.size)
            embedding.forEachIndexed { vectorIndex, component ->
                val primitive = component as? JsonPrimitive
                if (primitive == null || primitive.isString) {
                    throw IllegalStateException("Embedding component is not numeric")
                }
                val value = primitive.content.toDoubleOrNull()
                    ?: throw IllegalStateException("Embedding component is not numeric")
                if (!value.isFinite()) throw IllegalStateException("Embedding component is not finite")
                val asFloat = value.toFloat()
                if (!asFloat.isFinite()) throw IllegalStateException("Embedding component is not finite")
                vector[vectorIndex] = asFloat
            }
            vectors[index] = vector
        }
        if (vectors.any { it == null } || dimension <= 0) {
            throw IllegalStateException("Embedding response indexes are incomplete")
        }
        return EmbeddingBatch(vectors.map { it!! }, dimension)
    }

    private fun invalidConfig(message: String, operationId: String): AppException =
        AppError(
            code = ErrorCode.INVALID_CONFIG,
            userMessage = message,
            retryClass = RetryClass.USER_ACTION,
            stage = "provider-api",
            operationId = operationId,
        ).asException()

    private fun embeddingFailure(code: ErrorCode, message: String): AppException =
        AppError(
            code = code,
            userMessage = message,
            retryClass = if (code == ErrorCode.UNKNOWN_OUTCOME) RetryClass.NEVER else RetryClass.USER_ACTION,
            stage = "embedding",
            operationId = "embedding",
        ).asException()

    private suspend fun probeDeclaredFeature(
        profile: ModelProfile,
        headers: ResolvedHeaders,
        feature: ProbeFeature,
    ): FeatureProbeResult {
        val endpoint = profile.withEndpoint().endpoint
        val chat = ModelOperation.CHAT in endpoint.operations || profile.role.name == "CHAT" || profile.role.name == "VISION"
        val declared = when (feature) {
            ProbeFeature.STREAM -> ModelFeature.STREAMING in endpoint.features || "stream" in profile.capabilities
            ProbeFeature.TOOLS -> ModelFeature.TOOL_CALLING in endpoint.features || "tools" in profile.capabilities
            ProbeFeature.IMAGE -> InputModality.IMAGE in endpoint.inputModalities || "image" in profile.capabilities || profile.role.name == "VISION"
        }
        if (!declared || !chat) {
            return FeatureProbeResult(
                summary = "not-declared",
                supported = false,
                charged = false,
                status = CapabilityCheckStatus.NOT_DECLARED,
            )
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(profile.modelId))
            put("max_tokens", JsonPrimitive(1))
            put("stream", JsonPrimitive(feature == ProbeFeature.STREAM))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            if (feature == ProbeFeature.IMAGE) {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", JsonPrimitive("text"))
                                            put("text", JsonPrimitive("Describe the image in one word."))
                                        })
                                        add(buildJsonObject {
                                            put("type", JsonPrimitive("image_url"))
                                            put("image_url", buildJsonObject {
                                                put("url", JsonPrimitive("data:image/png;base64,$PROBE_PNG"))
                                            })
                                        })
                                    },
                                )
                            } else {
                                put("content", JsonPrimitive("Reply with ok."))
                            }
                        },
                    )
                },
            )
            if (feature == ProbeFeature.TOOLS) {
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", JsonPrimitive(PROBE_TOOL_NAME))
                                        put("description", JsonPrimitive("Call this no-op probe exactly once."))
                                        put(
                                            "parameters",
                                            buildJsonObject {
                                                put("type", JsonPrimitive("object"))
                                                put("properties", buildJsonObject { })
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "tool_choice",
                    buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject { put("name", JsonPrimitive(PROBE_TOOL_NAME)) })
                    },
                )
            }
        }
        return try {
            http.preparePost(url(baseUrl, "/chat/completions")) {
                contentType(ContentType.Application.Json)
                headers {
                    headers.values.forEach { (name, value) -> append(name, value) }
                }
                setBody(body.toString())
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) {
                    return@execute FeatureProbeResult(
                        summary = "http-$status",
                        supported = false,
                        charged = true,
                        httpStatus = status,
                        status = featureHttpStatus(status),
                    )
                }
                if (feature == ProbeFeature.STREAM) {
                    val responseType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                    if (!responseType.contains("text/event-stream")) {
                        return@execute FeatureProbeResult(
                            summary = "wrong-content-type",
                            supported = false,
                            charged = true,
                            status = CapabilityCheckStatus.FAILED,
                        )
                    }
                    val supported = parseStreamProbe(response.bodyAsChannel())
                    FeatureProbeResult(
                        summary = if (supported) "verified" else "invalid-response",
                        supported = supported,
                        charged = true,
                        status = if (supported) CapabilityCheckStatus.VERIFIED else CapabilityCheckStatus.FAILED,
                    )
                } else {
                    val supported = parseChatProbe(
                        readBounded(response.bodyAsChannel()),
                        requireToolCall = feature == ProbeFeature.TOOLS,
                    )
                    FeatureProbeResult(
                        summary = if (supported) "verified" else "invalid-response",
                        supported = supported,
                        charged = true,
                        status = if (supported) CapabilityCheckStatus.VERIFIED else CapabilityCheckStatus.FAILED,
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The request may have reached the provider before transport
            // failure. Preserve UNKNOWN_OUTCOME/charged semantics instead of
            // presenting this as a free, retry-safe failure.
            FeatureProbeResult(
                summary = "unknown-outcome",
                supported = false,
                charged = true,
                status = CapabilityCheckStatus.UNKNOWN,
            )
        }
    }

    private fun featureHttpStatus(status: Int): CapabilityCheckStatus = when {
        status in 400..499 && status !in setOf(401, 403, 408, 429) -> CapabilityCheckStatus.UNSUPPORTED
        status in 500..599 -> CapabilityCheckStatus.UNKNOWN
        else -> CapabilityCheckStatus.FAILED
    }

    private fun profileChecks(profile: ModelProfile): List<CapabilityCheckResult> {
        val configured = profile.withEndpoint()
        val chat = configured.isChatEndpoint()
        fun declared(feature: ProbeFeature): Boolean = when (feature) {
            ProbeFeature.STREAM -> ModelFeature.STREAMING in configured.endpoint.features || "stream" in configured.capabilities
            ProbeFeature.TOOLS -> ModelFeature.TOOL_CALLING in configured.endpoint.features || "tools" in configured.capabilities
            ProbeFeature.IMAGE -> InputModality.IMAGE in configured.endpoint.inputModalities ||
                "image" in configured.capabilities || configured.role == ModelRole.VISION
        }
        fun status(feature: ProbeFeature): CapabilityCheckStatus =
            if (chat && declared(feature)) CapabilityCheckStatus.NOT_RUN else CapabilityCheckStatus.NOT_DECLARED
        return listOf(
            CapabilityCheckResult(CapabilityCheck.METADATA, CapabilityCheckStatus.NOT_RUN),
            CapabilityCheckResult(CapabilityCheck.STREAM, status(ProbeFeature.STREAM)),
            CapabilityCheckResult(CapabilityCheck.TOOLS, status(ProbeFeature.TOOLS)),
            CapabilityCheckResult(CapabilityCheck.IMAGE, status(ProbeFeature.IMAGE)),
        )
    }

    private fun capabilityProbeStatus(results: List<FeatureProbeResult>): CapabilityProbeStatus = when {
        results.all { it.status == CapabilityCheckStatus.VERIFIED || it.status == CapabilityCheckStatus.NOT_DECLARED } ->
            CapabilityProbeStatus.SUCCEEDED
        results.any { it.status == CapabilityCheckStatus.UNSUPPORTED } -> CapabilityProbeStatus.PARTIAL
        results.any { it.status == CapabilityCheckStatus.VERIFIED || it.status == CapabilityCheckStatus.NOT_DECLARED } ->
            CapabilityProbeStatus.PARTIAL
        else -> CapabilityProbeStatus.FAILED
    }

    private fun metadataMatches(raw: String, expectedModelId: String): Boolean {
        val id = runCatching {
            Json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return false
        return id == expectedModelId
    }

    private fun parseChatProbe(raw: String, requireToolCall: Boolean): Boolean {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
        if (root["error"] != null) return false
        val message = runCatching {
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        }.getOrNull() ?: return false
        if (requireToolCall) {
            return runCatching {
                message["tool_calls"]?.jsonArray?.any { element ->
                    val call = element.jsonObject
                    val function = call["function"]?.jsonObject ?: return@any false
                    val name = function["name"]?.jsonPrimitive?.contentOrNull
                    val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull
                    name == PROBE_TOOL_NAME && arguments != null &&
                        runCatching { Json.parseToJsonElement(arguments).jsonObject }.isSuccess
                } == true
            }.getOrDefault(false)
        }
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull?.isNotBlank() == true
            else -> runCatching {
                content?.jsonArray?.any { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                } == true
            }.getOrDefault(false)
        }
    }

    private suspend fun parseStreamProbe(channel: ByteReadChannel): Boolean {
        val toolBuf = linkedMapOf<String, Pair<String, StringBuilder>>()
        var sawPayload = false
        var sawCompleted = false
        var sawFailed = false
        var receivedBytes = 0L
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line(1_048_576) ?: break
            receivedBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            require(receivedBytes <= MAX_PROBE_RESPONSE_BYTES) { "Provider probe response exceeds limit" }
            OpenAiSse.eventsFromLine(line, toolBuf).forEach { event ->
                when (event) {
                    is ModelEvent.TextDelta,
                    is ModelEvent.ReasoningDelta,
                    is ModelEvent.ToolCallDelta,
                    is ModelEvent.Usage,
                    -> sawPayload = true
                    ModelEvent.Completed -> sawCompleted = true
                    is ModelEvent.Failed -> sawFailed = true
                    else -> Unit
                }
            }
            if (sawCompleted || sawFailed) break
        }
        return sawPayload && sawCompleted && !sawFailed
    }

    private fun parseConnectionSse(raw: String): Boolean {
        val toolBuf = linkedMapOf<String, Pair<String, StringBuilder>>()
        var sawPayload = false
        var sawCompleted = false
        var sawFailed = false
        raw.lineSequence().forEach { line ->
            OpenAiSse.eventsFromLine(line, toolBuf).forEach { event ->
                when (event) {
                    is ModelEvent.TextDelta,
                    is ModelEvent.ReasoningDelta,
                    is ModelEvent.ToolCallDelta,
                    is ModelEvent.Usage,
                    -> sawPayload = true
                    ModelEvent.Completed -> sawCompleted = true
                    is ModelEvent.Failed -> sawFailed = true
                    else -> Unit
                }
            }
        }
        return sawPayload && sawCompleted && !sawFailed
    }

    private fun connectionFailureForHttp(status: Int): ProviderConnectionResult.Failure = when (status) {
        401, 403 -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.AUTH_FAILED,
            httpStatus = status,
            retryable = false,
            charged = true,
        )
        404 -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.MODEL_NOT_FOUND,
            httpStatus = status,
            retryable = false,
            charged = true,
        )
        408 -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.TIMEOUT,
            httpStatus = status,
            retryable = true,
            charged = true,
        )
        429 -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.RATE_LIMITED,
            httpStatus = status,
            retryable = true,
            charged = true,
        )
        in 400..499 -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.PROVIDER_REJECTED,
            httpStatus = status,
            retryable = false,
            charged = true,
        )
        in 500..599 -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.PROVIDER_REJECTED,
            httpStatus = status,
            retryable = true,
            charged = true,
        )
        else -> ProviderConnectionResult.Failure(
            code = ProviderConnectionErrorCode.UNKNOWN,
            httpStatus = status,
            retryable = false,
            charged = true,
        )
    }

    private enum class ProbeFeature { STREAM, TOOLS, IMAGE }

    private data class MetadataProbeResult(
        val summary: String,
        val verified: Boolean,
        val charged: Boolean = false,
        val httpStatus: Int? = null,
        val status: CapabilityCheckStatus = CapabilityCheckStatus.FAILED,
    )

    private data class FeatureProbeResult(
        val summary: String,
        val supported: Boolean,
        val charged: Boolean,
        val httpStatus: Int? = null,
        val status: CapabilityCheckStatus = CapabilityCheckStatus.FAILED,
    )

    private fun elapsedMillis(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private suspend fun resolveHeaders(
        token: String,
        requestHeaders: Map<String, RequestHeaderValue>,
    ): ResolvedHeaders {
        val merged = linkedMapOf<String, RequestHeaderValue>()
        defaultHeaders.forEach { (name, value) -> merged[name] = value }
        requestHeaders.forEach { (name, value) ->
            val previous = merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (previous != null) merged.remove(previous)
            merged[name] = value
        }
        val values = linkedMapOf<String, String>("Authorization" to "Bearer $token")
        val secrets = mutableListOf<String>()
        val host = URI(baseUrl).host?.lowercase()?.trim('.')
            ?: throw InvalidHeaderException("Provider URL has no host")
        merged.forEach { (name, value) ->
            validateHeaderName(name)
            when (value) {
                is RequestHeaderValue.Plain -> {
                    validateHeaderValue(value.value)
                    values[name] = value.value
                }
                is RequestHeaderValue.SecretRef -> {
                    if (value.ref.isBlank()) throw SecretUnavailableException()
                    val resolver = headerSecretResolver ?: throw SecretUnavailableException()
                    val chars = resolver.resolve(host, value.ref)
                    val text = chars.concatToString()
                    chars.fill('\u0000')
                    if (text.isEmpty()) throw SecretUnavailableException()
                    validateHeaderValue(text)
                    values[name] = text
                    secrets += text
                }
            }
        }
        return ResolvedHeaders(values, secrets)
    }

    private fun validateHeaderName(name: String) {
        if (name.isBlank() || name.any { it == '\r' || it == '\n' }) throw InvalidHeaderException("Header name is invalid")
        val lower = name.lowercase()
        if (lower in FORBIDDEN_HEADERS || lower == "authorization" || lower == "api_key" || lower == "api-key") {
            throw InvalidHeaderException("Header $name is reserved")
        }
    }

    private fun validateHeaderValue(value: String) {
        if (value.any { it == '\r' || it == '\n' }) throw InvalidHeaderException("Header value is invalid")
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelEvent>.emitJsonResponse(
        raw: String,
        redactionSecrets: List<String>,
    ) {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: run {
                emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
                return
            }
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let { message ->
            emit(ModelEvent.Failed(SecretRedactor.redact(message, redactionSecrets)))
            return
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: run {
                emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
                return
            }
        val message = choice["message"]?.jsonObject
        messageReasoningText(message)?.let {
            emit(ModelEvent.ReasoningDelta(SecretRedactor.redact(it, redactionSecrets)))
        }
        val toolEvents = mutableListOf<ModelEvent.ToolCallDelta>()
        message?.get("tool_calls")?.jsonArray?.forEach { element ->
            val call = element.jsonObject
            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val function = call["function"]?.jsonObject
            toolEvents +=
                ModelEvent.ToolCallDelta(
                    id,
                    function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
        }
        if (toolEvents.any { event ->
                val parsed = runCatching { Json.parseToJsonElement(event.argumentsJson) }.getOrNull()
                val objectArgs = parsed as? JsonObject
                objectArgs == null ||
                    containsCredentialText(event.callId, redactionSecrets) ||
                    containsCredentialText(event.name, redactionSecrets) ||
                    containsCredentialText(event.argumentsJson, redactionSecrets) ||
                    containsCredentialJson(objectArgs, redactionSecrets)
            }) {
            emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
            return
        }
        message?.get("content")?.jsonPrimitive?.contentOrNull?.let {
            emit(ModelEvent.TextDelta(SecretRedactor.redact(it, redactionSecrets)))
        }
        toolEvents.forEach { emit(it) }
        root["usage"]?.jsonObject?.let { usage ->
            emit(
                ModelEvent.Usage(
                    usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                ),
            )
        }
        emit(ModelEvent.Completed)
    }

    /** Prefer an explicitly supplied reasoning field; never derive one. */
    private fun messageReasoningText(message: JsonObject?): String? =
        listOf("reasoning_content", "reasoning")
            .firstNotNullOfOrNull { key ->
                (message?.get(key) as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }

    private data class ResolvedHeaders(
        val values: Map<String, String>,
        val secrets: List<String>,
    ) {
        override fun toString(): String = "ResolvedHeaders(values=${values.keys}, secrets=<redacted>)"
    }

    private class SecretUnavailableException : RuntimeException()
    private class InvalidHeaderException(message: String) : RuntimeException(message)
    private class InvalidConnectionConfigException : RuntimeException()

    companion object {
        private const val PROBE_TOOL_NAME = "mar_probe_noop"
        /**
         * Probe output budget.  Feature probes already use `max_tokens: 1`;
         * the connection probe is clamped to the same small bound so a
         * 10k+ profile output limit never becomes a probe spend.
         */
        const val CONNECTION_PROBE_MAX_OUTPUT_TOKENS = 64
        private const val MAX_PROBE_RESPONSE_BYTES = 1_048_576L
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val MAX_EMBEDDING_MODEL_CHARS = 256
        private const val MAX_EMBEDDING_INPUTS = 2_048
        private const val MAX_EMBEDDING_INPUT_CHARS = 16_384
        private const val MAX_EMBEDDING_INPUT_BYTES = 512 * 1024
        private const val MAX_EMBEDDING_REQUEST_BYTES = 1_048_576
        private const val MAX_EMBEDDING_DIMENSION = 16_384
        private const val PROBE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        private val FORBIDDEN_HEADERS = setOf(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "upgrade",
            "proxy-authorization",
            "proxy-authenticate",
            "te",
            "trailer",
            "content-type",
            "accept",
        )

        fun url(base: String, path: String): String = base.trimEnd('/') + path

        internal fun encodeMessage(msg: ChatMessage): JsonObject = encodeMessage(msg, includeImageBytes = true)

        private fun encodeMessage(msg: ChatMessage, includeImageBytes: Boolean): JsonObject = buildJsonObject {
            put("role", JsonPrimitive(msg.role))
            msg.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
            if (msg.toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        msg.toolCalls.forEach { call -> add(encodeToolCall(call)) }
                    },
                )
                put("content", if (msg.text.isNotEmpty()) JsonPrimitive(msg.text) else JsonNull)
            } else if (msg.images.isNotEmpty()) {
                put(
                    "content",
                    buildJsonArray {
                        if (msg.text.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(msg.text))
                            })
                        }
                        msg.images.forEach { image ->
                            val imageUrl = if (includeImageBytes) {
                                "data:${image.mediaType};base64,${image.base64}"
                            } else {
                                "<redacted-image:${image.assetId ?: "inline"}:${image.mediaType}:${image.base64.length} bytes>"
                            }
                            add(buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put("image_url", buildJsonObject { put("url", JsonPrimitive(imageUrl)) })
                            })
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
