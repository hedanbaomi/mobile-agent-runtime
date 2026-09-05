// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.InputModality
import runtime.mobileagent.domain.ModelFeature
import runtime.mobileagent.domain.ModelOperation
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.isChatEndpoint
import runtime.mobileagent.domain.withEndpoint
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckResult
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.CapabilityProbeStatus
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.InlineImage
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
 * Native OpenAI Responses adapter. It intentionally owns a separate request
 * builder and SSE parser: Responses `input`/output items and event names are
 * not Chat Completions `messages`/choice deltas.
 */
class OpenAiResponsesAdapter(
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
            supportsImages = "image" in profile.capabilities || profile.role == ModelRole.VISION,
            source = "profile-only;format=OPENAI_RESPONSES",
            probedAt = Utc.nowIso(),
            charged = false,
            status = CapabilityProbeStatus.PROFILE_ONLY,
            checks = profileChecks(profile),
        )

    override suspend fun testConnection(
        profile: ModelProfile,
        secret: CharArray,
        operationId: String,
    ): ProviderConnectionResult {
        if (secret.isEmpty()) {
            return ProviderConnectionResult.Failure(ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE, retryable = false)
        }
        val configured = profile.withEndpoint()
        if (!configured.isChatEndpoint() || configured.modelId.isBlank()) {
            return ProviderConnectionResult.Failure(ProviderConnectionErrorCode.CONFIG_INVALID, retryable = false)
        }
        val started = System.nanoTime()
        val token = secret.concatToString()
        return try {
            val modelParameters = runCatching { Json.parseToJsonElement(configured.parametersJson).jsonObject }
                .getOrElse { throw InvalidConnectionConfigException() }
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
            withTimeout(CONNECTION_TIMEOUT_MS) {
                http.preparePost(url(baseUrl, "/responses")) {
                    contentType(ContentType.Application.Json)
                    headers {
                        append(HttpHeaders.Accept, "text/event-stream, application/json")
                        resolved.values.forEach { (name, value) -> append(name, value) }
                    }
                    setBody(payload.toString())
                }.execute { response ->
                    val raw = readBounded(response.bodyAsChannel())
                    val status = response.status.value
                    if (status !in 200..299) {
                        connectionFailureForHttp(status, raw)
                    } else {
                        val events = parseResponseBody(raw, response.headers[HttpHeaders.ContentType].orEmpty(), resolved.secrets + token)
                        // A well-formed completed refusal proves the endpoint,
                        // auth, and protocol round-trip; it is not a malformed
                        // response.
                        if (events.any { it is ModelEvent.Completed } && events.any {
                                it is ModelEvent.TextDelta || it is ModelEvent.ToolCallDelta || it is ModelEvent.RefusalDelta
                            }) {
                            ProviderConnectionResult.Success(elapsedMillis(started), charged = true)
                        } else {
                            ProviderConnectionResult.Failure(
                                code = ProviderConnectionErrorCode.INVALID_RESPONSE,
                                retryable = false,
                                charged = true,
                            )
                        }
                    }
                }
            }
        } catch (_: InvalidConnectionConfigException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.CONFIG_INVALID, retryable = false)
        } catch (_: SecretUnavailableException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE, retryable = false)
        } catch (_: InvalidHeaderException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.CONFIG_INVALID, retryable = false)
        } catch (error: AppException) {
            ProviderConnectionResult.Failure(
                code = if (error.error.code == ErrorCode.SECRET_UNAVAILABLE) ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE
                else ProviderConnectionErrorCode.CONFIG_INVALID,
                retryable = false,
            )
        } catch (_: TimeoutCancellationException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.TIMEOUT, retryable = true, charged = true)
        } catch (_: SSLException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.TLS_FAILURE, retryable = false)
        } catch (_: UnknownHostException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.NETWORK_UNREACHABLE, retryable = true)
        } catch (_: ConnectException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.NETWORK_UNREACHABLE, retryable = true)
        } catch (_: IOException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.NETWORK_UNREACHABLE, retryable = true)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.UNKNOWN, retryable = false, charged = true)
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    override suspend fun probe(
        profile: ModelProfile,
        secret: CharArray,
        consent: ProbeConsent,
        operationId: String,
    ): CapabilityReport {
        val profileReport = probe(profile)
        if (consent != ProbeConsent.GRANTED) {
            return profileReport.copy(
                source = "profile-only;consent-required;format=OPENAI_RESPONSES",
                operationId = operationId,
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
                    if (check.capability == CapabilityCheck.METADATA) check.copy(status = CapabilityCheckStatus.FAILED) else check
                },
            )
        }
        val token = secret.concatToString()
        return try {
            val resolved = resolveHeaders(token, emptyMap())
            val metadata = probeRequest(
                profile,
                resolved,
                token,
                ModelRequest(
                    modelId = profile.modelId,
                    messages = listOf(ChatMessage("user", "Reply with ok.")),
                    stream = false,
                    operationId = operationId,
                    outputTokenLimit = minOf(profile.outputLimit.coerceAtLeast(1), CONNECTION_PROBE_MAX_OUTPUT_TOKENS),
                ),
                require = ProbeRequirement.TEXT,
            )
            if (!metadata.supported) {
                return profileReport.copy(
                    supportsStream = false,
                    supportsTools = false,
                    supportsImages = false,
                    source = "metadata=${metadata.summary};stream=not-run;tools=not-run;image=not-run",
                    status = CapabilityProbeStatus.FAILED,
                    charged = metadata.charged,
                    operationId = operationId,
                    checks = listOf(
                        CapabilityCheckResult(CapabilityCheck.METADATA, metadata.status, metadata.httpStatus),
                        CapabilityCheckResult(CapabilityCheck.STREAM, CapabilityCheckStatus.NOT_RUN),
                        CapabilityCheckResult(CapabilityCheck.TOOLS, CapabilityCheckStatus.NOT_RUN),
                        CapabilityCheckResult(CapabilityCheck.IMAGE, CapabilityCheckStatus.NOT_RUN),
                    ),
                )
            }
            val stream = probeFeature(profile, resolved, token, ProbeFeature.STREAM, operationId)
            val tools = probeFeature(profile, resolved, token, ProbeFeature.TOOLS, operationId)
            val image = probeFeature(profile, resolved, token, ProbeFeature.IMAGE, operationId)
            val features = listOf(stream, tools, image)
            profileReport.copy(
                supportsStream = stream.supported,
                supportsTools = tools.supported,
                supportsImages = image.supported,
                source = "metadata=${metadata.summary};stream=${stream.summary};tools=${tools.summary};image=${image.summary}",
                status = capabilityProbeStatus(features),
                charged = metadata.charged || features.any { it.charged },
                operationId = operationId,
                checks = listOf(
                    CapabilityCheckResult(CapabilityCheck.METADATA, metadata.status, metadata.httpStatus),
                    CapabilityCheckResult(CapabilityCheck.STREAM, stream.status, stream.httpStatus),
                    CapabilityCheckResult(CapabilityCheck.TOOLS, tools.status, tools.httpStatus),
                    CapabilityCheckResult(CapabilityCheck.IMAGE, image.status, image.httpStatus),
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecretUnavailableException) {
            failedProbeReport(profileReport, operationId, "secret-unavailable")
        } catch (_: InvalidHeaderException) {
            failedProbeReport(profileReport, operationId, "invalid-config")
        } catch (_: TimeoutCancellationException) {
            failedProbeReport(profileReport, operationId, "timeout")
        } catch (_: SSLException) {
            failedProbeReport(profileReport, operationId, "tls-failure")
        } catch (_: UnknownHostException) {
            failedProbeReport(profileReport, operationId, "network-unreachable")
        } catch (_: ConnectException) {
            failedProbeReport(profileReport, operationId, "network-unreachable")
        } catch (_: IOException) {
            failedProbeReport(profileReport, operationId, "network-unreachable")
        } catch (_: Exception) {
            failedProbeReport(profileReport, operationId, "unknown-outcome")
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    override fun previewRequest(request: ModelRequest): String =
        // Provider-private continuation items are transport-only: the preview
        // a user inspects must never contain encrypted provider payloads.
        SecretRedactor.redact(
            buildPayload(
                request.copy(messages = request.messages.map { it.copy(providerContinuationItems = emptyList()) }),
                includeImageBytes = false,
            ).toString(),
        )

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
            val redactor = StreamingSecretRedactor(redactionSecrets)
            http.preparePost(url(baseUrl, "/responses")) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "text/event-stream, application/json")
                    resolved.values.forEach { (name, value) -> append(name, value) }
                }
                setBody(payload.toString())
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) {
                    val raw = readBounded(response.bodyAsChannel())
                    emit(ModelEvent.Failed(httpFailureMessage(status, raw)))
                    return@execute
                }
                val responseType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                if (responseType.contains("text/event-stream")) {
                    val channel = response.bodyAsChannel()
                    val state = OpenAiResponsesSse.State()
                    var terminal = false
                    var bytes = 0L
                    while (!channel.isClosedForRead && !terminal) {
                        val line = channel.readUTF8Line(MAX_LINE_BYTES) ?: break
                        bytes += line.toByteArray(Charsets.UTF_8).size + 1L
                        require(bytes <= MAX_RESPONSE_BYTES) { "Provider response exceeds limit" }
                        OpenAiResponsesSse.eventsFromLine(line, state, redactionSecrets).forEach { event ->
                            val safeTerminal = emitSafe(event, redactor, redactionSecrets)
                            if (safeTerminal) terminal = true
                        }
                    }
                    if (!terminal) {
                        redactor.discard()
                        emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
                    }
                } else {
                    parseResponseBody(readBounded(response.bodyAsChannel()), responseType, redactionSecrets).forEach { emit(it) }
                }
            }
        } catch (e: AppException) {
            emit(ModelEvent.Failed(e.error.code.name))
        } catch (_: SecretUnavailableException) {
            emit(ModelEvent.Failed(ErrorCode.SECRET_UNAVAILABLE.name))
        } catch (_: InvalidHeaderException) {
            emit(ModelEvent.Failed(ErrorCode.INVALID_CONFIG.name))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: TimeoutCancellationException) {
            emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        } catch (_: SSLException) {
            emit(ModelEvent.Failed(ErrorCode.NETWORK_UNAVAILABLE.name))
        } catch (_: UnknownHostException) {
            emit(ModelEvent.Failed(ErrorCode.NETWORK_UNAVAILABLE.name))
        } catch (_: ConnectException) {
            emit(ModelEvent.Failed(ErrorCode.NETWORK_UNAVAILABLE.name))
        } catch (_: IOException) {
            emit(ModelEvent.Failed(ErrorCode.NETWORK_UNAVAILABLE.name))
        } catch (_: Exception) {
            emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    /** Embeddings remain the shared OpenAI-compatible `/embeddings` surface. */
    override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch =
        OpenAiCompatibleAdapter(http, apiRoot(baseUrl), headerSecretResolver, defaultHeaders).embed(request, secret)

    private suspend fun FlowCollector<ModelEvent>.emitSafe(
        event: ModelEvent,
        redactor: StreamingSecretRedactor,
        secrets: List<String>,
    ): Boolean = when (event) {
        is ModelEvent.TextDelta -> {
            val safe = redactor.accept(event.text)
            if (safe.isNotEmpty()) emit(ModelEvent.TextDelta(safe))
            false
        }
        is ModelEvent.ReasoningDelta -> {
            val safe = redactor.accept(event.text)
            if (safe.isNotEmpty()) emit(ModelEvent.ReasoningDelta(safe))
            false
        }
        is ModelEvent.RefusalDelta -> {
            val safe = redactor.accept(event.text)
            if (safe.isNotEmpty()) emit(ModelEvent.RefusalDelta(safe))
            false
        }
        // Provider-private continuation bypasses redaction buffering
        // untouched: it is opaque transport for the owning adapter, and the
        // runtime — not the UI — consumes it on the next round.
        is ModelEvent.ProviderContinuation -> {
            emit(event)
            false
        }
        is ModelEvent.ToolCallDelta -> {
            val parsed = runCatching { Json.parseToJsonElement(event.argumentsJson).jsonObject }.getOrNull()
            if (parsed == null || credentialText(event.callId, secrets) ||
                credentialText(event.name, secrets) || credentialText(event.argumentsJson, secrets) ||
                credentialJson(parsed, secrets)
            ) {
                redactor.discard()
                emit(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
                true
            } else {
                emit(event)
                false
            }
        }
        is ModelEvent.Failed -> {
            redactor.discard()
            emit(ModelEvent.Failed(SecretRedactor.redact(event.sanitizedMessage, secrets)))
            true
        }
        ModelEvent.Completed -> {
            val safeTail = redactor.finish()
            if (safeTail.isNotEmpty()) emit(ModelEvent.TextDelta(safeTail))
            emit(ModelEvent.Completed)
            true
        }
        else -> {
            emit(event)
            false
        }
    }

    private fun buildPayload(
        request: ModelRequest,
        includeImageBytes: Boolean,
        forcedToolName: String? = null,
    ): JsonObject {
        val instructions = request.messages.asSequence()
            .filter { it.role.equals("system", true) || it.role.equals("developer", true) }
            .map { it.text }
            .filter { it.isNotBlank() }
            .toList()
        val runtimeFields = linkedMapOf<String, JsonElement>(
            "model" to JsonPrimitive(request.modelId),
            "input" to encodeInput(request.messages, includeImageBytes),
            "stream" to JsonPrimitive(request.stream),
        )
        if (instructions.isNotEmpty()) runtimeFields["instructions"] = JsonPrimitive(instructions.joinToString("\n\n"))
        if (request.tools.isNotEmpty()) {
            runtimeFields["tools"] = buildJsonArray {
                request.tools.forEach { spec ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("name", JsonPrimitive(spec["name"].orEmpty()))
                        put("description", JsonPrimitive(spec["description"].orEmpty()))
                        put("parameters", runCatching { Json.parseToJsonElement(spec["parameters"] ?: "{}") }
                            .getOrElse { throw InvalidHeaderException("tool parameters are not valid JSON") })
                        spec["strict"]?.let { raw ->
                            raw.toBooleanStrictOrNull()?.let { put("strict", JsonPrimitive(it)) }
                        }
                    })
                }
            }
        }
        if (forcedToolName != null) {
            require(request.tools.any { it["name"] == forcedToolName }) {
                "Forced tool must be present in the request tool definitions"
            }
            runtimeFields["tool_choice"] = buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(forcedToolName))
            }
        }
        val merged = ParameterMerger.merge(
            layers = request.parameters,
            legacyExtras = request.extra,
            runtimeFields = runtimeFields,
            operationId = request.operationId,
        )
        val fields = linkedMapOf<String, JsonElement>()
        fields.putAll(merged)
        // Stateless orchestration stays local by default: the provider must
        // not retain the conversation unless the user explicitly opts in with
        // a boolean `store:true` advanced parameter.  A non-boolean store is a
        // configuration error, never a silent default.
        val effectiveStore = when (val configured = fields["store"]) {
            null -> false
            is JsonPrimitive -> configured.booleanOrNull
                ?: throw invalidConfig("store must be a boolean", request.operationId)
            else -> throw invalidConfig("store must be a boolean", request.operationId)
        }
        fields["store"] = JsonPrimitive(effectiveStore)
        // A stateless run replays provider-private reasoning items itself, so
        // the provider must return them unless the caller already set an
        // explicit include list.
        if (!effectiveStore && fields["include"] == null) {
            fields["include"] = buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) }
        }
        val legacyMaxTokens = fields.remove("max_tokens")
        val legacyMaxCompletionTokens = fields.remove("max_completion_tokens")
        val legacyMax = legacyMaxTokens ?: legacyMaxCompletionTokens
        if ((legacyMaxTokens != null && legacyMaxCompletionTokens != null) ||
            (legacyMax != null && fields["max_output_tokens"] != null)
        ) {
            throw invalidConfig("legacy output token fields cannot be combined with max_output_tokens", request.operationId)
        }
        if (legacyMax != null) fields["max_output_tokens"] = legacyMax
        val budget = request.outputTokenLimit
        if (budget != null && budget <= 0) throw invalidConfig("outputTokenLimit must be positive", request.operationId)
        val maxOutput = fields["max_output_tokens"]
        if (maxOutput != null) {
            val primitive = maxOutput as? JsonPrimitive
            val value = primitive?.takeIf { !it.isString }?.content?.toLongOrNull()
                ?: throw invalidConfig("max_output_tokens must be a positive integer", request.operationId)
            if (value <= 0 || value > Int.MAX_VALUE || (budget != null && value > budget)) {
                throw invalidConfig("max_output_tokens exceeds outputTokenLimit", request.operationId)
            }
        } else if (budget != null) {
            fields["max_output_tokens"] = JsonPrimitive(budget)
        }
        return JsonObject(fields)
    }

    private fun encodeInput(messages: List<ChatMessage>, includeImageBytes: Boolean) = buildJsonArray {
        messages.forEach { message ->
            val role = message.role.lowercase()
            if (role == "system" || role == "developer") return@forEach
            if (role == "tool" && message.toolCallId != null) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(message.toolCallId!!))
                    put("output", JsonPrimitive(message.text))
                })
                return@forEach
            }
            if (message.toolCalls.isNotEmpty()) {
                // Provider-private continuation items replay first, in provider
                // output order, so a reasoning model can continue statelessly.
                // They are transport-only and never enter previews or history.
                message.providerContinuationItems.forEach { continuation ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("reasoning"))
                        continuation.itemId?.let { put("id", JsonPrimitive(it)) }
                        put("encrypted_content", JsonPrimitive(continuation.encryptedContent))
                    })
                }
                if (message.text.isNotBlank()) add(messageContent("assistant", message.text, emptyList(), includeImageBytes))
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function_call"))
                        put("call_id", JsonPrimitive(call.id))
                        put("name", JsonPrimitive(call.name))
                        put("arguments", JsonPrimitive(call.argumentsJson))
                    })
                }
            } else {
                add(messageContent(if (role == "assistant") "assistant" else "user", message.text, message.images, includeImageBytes))
            }
        }
    }

    private fun messageContent(role: String, text: String, images: List<InlineImage>, includeImageBytes: Boolean) = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", buildJsonArray {
            if (text.isNotEmpty()) add(buildJsonObject {
                // Responses input message content uses input_text for both
                // user and historical assistant messages. output_text is an
                // output item type and is not valid as input content.
                put("type", JsonPrimitive("input_text"))
                put("text", JsonPrimitive(text))
            })
            images.forEach { image ->
                val url = if (includeImageBytes) "data:${image.mediaType};base64,${image.base64}"
                else "<redacted-image:${image.assetId ?: "inline"}:${image.mediaType}:${image.base64.length} bytes>"
                add(buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    put("image_url", JsonPrimitive(url))
                })
            }
            if (text.isEmpty() && images.isEmpty()) add(JsonPrimitive(""))
        })
    }

    private fun parseResponseBody(raw: String, responseType: String, secrets: List<String>): List<ModelEvent> {
        if (responseType.contains("text/event-stream")) {
            val state = OpenAiResponsesSse.State()
            return raw.lineSequence().flatMap { OpenAiResponsesSse.eventsFromLine(it, state, secrets).asSequence() }.toList()
        }
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        // Only a non-null error *object* is a failure. A legitimate success
        // response may carry an explicit "error":null member (JsonNull, not a
        // missing key); treating any present key as failure misclassifies
        // completed responses as UNKNOWN_OUTCOME.
        (root["error"] as? JsonObject)?.let { error ->
            val message = runCatching { error["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: ErrorCode.UNKNOWN_OUTCOME.name
            return listOf(ModelEvent.Failed(SecretRedactor.redact(message, secrets)))
        }
        val events = mutableListOf<ModelEvent>()
        val emittedContinuations = mutableSetOf<String>()
        root["output"]?.let { output ->
            runCatching { output.jsonArray }.getOrNull()?.forEach { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> item["content"]?.let { content ->
                        runCatching { content.jsonArray }.getOrNull()?.forEach { part ->
                            val partObject = runCatching { part.jsonObject }.getOrNull() ?: return@forEach
                            when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
                                "output_text" -> partObject["text"]?.jsonPrimitive?.contentOrNull?.let {
                                    events += ModelEvent.TextDelta(SecretRedactor.redact(it, secrets))
                                }
                                // A refusal is readable assistant output, not a
                                // transport failure and not reasoning.
                                "refusal" -> (
                                    partObject["refusal"] ?: partObject["text"]
                                    )?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                    events += ModelEvent.RefusalDelta(SecretRedactor.redact(it, secrets))
                                }
                            }
                        }
                    }
                    "reasoning" -> events += OpenAiResponsesSse.captureContinuation(item, emittedContinuations)
                    "function_call" -> {
                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val args = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val parsed = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull()
                        if (callId.isBlank() || name.isBlank() || parsed == null ||
                            credentialText(callId, secrets) || credentialText(name, secrets) ||
                            credentialText(args, secrets) || credentialJson(parsed, secrets)
                        ) {
                            return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
                        }
                        events += ModelEvent.ToolCallDelta(callId, name, args)
                    }
                }
            }
        }
        if (events.none { it is ModelEvent.TextDelta }) {
            root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                events += ModelEvent.TextDelta(SecretRedactor.redact(it, secrets))
            }
        }
        root["usage"]?.let { usage ->
            runCatching { usage.jsonObject }.getOrNull()?.let {
                events += ModelEvent.Usage(
                    it["input_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    it["output_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                )
            }
        }
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status == null || status == "completed") events += ModelEvent.Completed
        else if (status == "failed" || status == "incomplete") events += ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name)
        else return listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name))
        return events
    }

    private suspend fun probeRequest(
        profile: ModelProfile,
        headers: ResolvedHeaders,
        token: String,
        request: ModelRequest,
        require: ProbeRequirement,
    ): FeatureProbeResult = try {
        withTimeout(CONNECTION_TIMEOUT_MS) {
            http.preparePost(url(baseUrl, "/responses")) {
                contentType(ContentType.Application.Json)
                headers { headers.values.forEach { (name, value) -> append(name, value) } }
                setBody(
                    buildPayload(
                        request,
                        includeImageBytes = true,
                        forcedToolName = PROBE_TOOL_NAME.takeIf { require == ProbeRequirement.TOOL },
                    ).toString(),
                )
            }.execute { response ->
                val status = response.status.value
                val raw = readBounded(response.bodyAsChannel())
                if (status !in 200..299) {
                    FeatureProbeResult(
                        summary = probeHttpSummary(status, raw),
                        supported = false,
                        charged = true,
                        httpStatus = status,
                        status = featureHttpStatus(status),
                    )
                } else {
                    val responseType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                    if (require == ProbeRequirement.STREAM && !responseType.contains("text/event-stream")) {
                        return@execute FeatureProbeResult(
                            summary = "wrong-content-type",
                            supported = false,
                            charged = true,
                            status = CapabilityCheckStatus.FAILED,
                        )
                    }
                    val events = parseResponseBody(raw, responseType, listOf(token) + headers.secrets)
                    val hasTerminal = events.any { it is ModelEvent.Completed }
                    val hasText = events.any { it is ModelEvent.TextDelta }
                    val hasTool = events.any { it is ModelEvent.ToolCallDelta }
                    val hasRefusal = events.any { it is ModelEvent.RefusalDelta }
                    val supported = hasTerminal && when (require) {
                        ProbeRequirement.TEXT -> hasText
                        ProbeRequirement.STREAM -> hasText
                        ProbeRequirement.TOOL -> hasTool
                        ProbeRequirement.IMAGE -> hasText
                    }
                    FeatureProbeResult(
                        // A refusal proves the provider answered, but it does
                        // not verify the probed text/tool capability.  Keep it
                        // distinct from a malformed response.
                        summary = when {
                            supported -> "verified"
                            hasRefusal -> "refusal"
                            else -> "invalid-response"
                        },
                        supported = supported,
                        charged = true,
                        status = if (supported) CapabilityCheckStatus.VERIFIED else CapabilityCheckStatus.FAILED,
                    )
                }
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: TimeoutCancellationException) {
        FeatureProbeResult("timeout", false, true, status = CapabilityCheckStatus.UNKNOWN)
    } catch (_: SSLException) {
        FeatureProbeResult("tls-failure", false, true, status = CapabilityCheckStatus.UNKNOWN)
    } catch (_: UnknownHostException) {
        FeatureProbeResult("network-unreachable", false, true, status = CapabilityCheckStatus.UNKNOWN)
    } catch (_: ConnectException) {
        FeatureProbeResult("network-unreachable", false, true, status = CapabilityCheckStatus.UNKNOWN)
    } catch (_: IOException) {
        FeatureProbeResult("network-unreachable", false, true, status = CapabilityCheckStatus.UNKNOWN)
    } catch (_: Exception) {
        FeatureProbeResult("unknown-outcome", false, true, status = CapabilityCheckStatus.UNKNOWN)
    }

    private suspend fun probeFeature(
        profile: ModelProfile,
        headers: ResolvedHeaders,
        token: String,
        feature: ProbeFeature,
        operationId: String,
    ): FeatureProbeResult {
        val configured = profile.withEndpoint()
        val chat = configured.isChatEndpoint()
        val declared = when (feature) {
            ProbeFeature.STREAM -> ModelFeature.STREAMING in configured.endpoint.features || "stream" in configured.capabilities
            ProbeFeature.TOOLS -> ModelFeature.TOOL_CALLING in configured.endpoint.features || "tools" in configured.capabilities
            ProbeFeature.IMAGE -> InputModality.IMAGE in configured.endpoint.inputModalities || "image" in configured.capabilities || configured.role == ModelRole.VISION
        }
        if (!declared || !chat) return FeatureProbeResult("not-declared", false, false, status = CapabilityCheckStatus.NOT_DECLARED)
        val request = ModelRequest(
            modelId = profile.modelId,
            messages = listOf(
                ChatMessage(
                    "user",
                    if (feature == ProbeFeature.IMAGE) "Describe the image in one word." else "Reply with ok.",
                    images = if (feature == ProbeFeature.IMAGE) listOf(InlineImage("image/png", PROBE_PNG)) else emptyList(),
                ),
            ),
            tools = if (feature == ProbeFeature.TOOLS) listOf(
                mapOf(
                    "name" to PROBE_TOOL_NAME,
                    "description" to "Call this no-op probe exactly once.",
                    "parameters" to "{\"type\":\"object\",\"properties\":{}}",
                ),
            ) else emptyList(),
            stream = feature == ProbeFeature.STREAM,
            operationId = operationId,
            // The forced tool probe still gets a small bounded budget: enough
            // for a short reasoning trace plus one no-op call on reasoning
            // models, but far below any real profile output limit.
            outputTokenLimit = minOf(
                profile.outputLimit.coerceAtLeast(1),
                if (feature == ProbeFeature.TOOLS) CAPABILITY_PROBE_MAX_OUTPUT_TOKENS else CONNECTION_PROBE_MAX_OUTPUT_TOKENS,
            ),
        )
        return probeRequest(profile, headers, token, request, when (feature) {
            ProbeFeature.STREAM -> ProbeRequirement.STREAM
            ProbeFeature.TOOLS -> ProbeRequirement.TOOL
            ProbeFeature.IMAGE -> ProbeRequirement.IMAGE
        })
    }

    private fun profileChecks(profile: ModelProfile): List<CapabilityCheckResult> {
        val configured = profile.withEndpoint()
        val chat = configured.isChatEndpoint()
        fun declared(feature: ProbeFeature): Boolean = when (feature) {
            ProbeFeature.STREAM -> ModelFeature.STREAMING in configured.endpoint.features || "stream" in configured.capabilities
            ProbeFeature.TOOLS -> ModelFeature.TOOL_CALLING in configured.endpoint.features || "tools" in configured.capabilities
            ProbeFeature.IMAGE -> InputModality.IMAGE in configured.endpoint.inputModalities || "image" in configured.capabilities || configured.role == ModelRole.VISION
        }
        fun status(feature: ProbeFeature) = if (chat && declared(feature)) CapabilityCheckStatus.NOT_RUN else CapabilityCheckStatus.NOT_DECLARED
        return listOf(
            CapabilityCheckResult(CapabilityCheck.METADATA, CapabilityCheckStatus.NOT_RUN),
            CapabilityCheckResult(CapabilityCheck.STREAM, status(ProbeFeature.STREAM)),
            CapabilityCheckResult(CapabilityCheck.TOOLS, status(ProbeFeature.TOOLS)),
            CapabilityCheckResult(CapabilityCheck.IMAGE, status(ProbeFeature.IMAGE)),
        )
    }

    private fun failedProbeReport(
        profileReport: CapabilityReport,
        operationId: String,
        reason: String,
    ): CapabilityReport = profileReport.copy(
        supportsStream = false,
        supportsTools = false,
        supportsImages = false,
        source = "metadata=$reason;stream=not-run;tools=not-run;image=not-run",
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

    private fun capabilityProbeStatus(results: List<FeatureProbeResult>): CapabilityProbeStatus = when {
        results.all { it.status == CapabilityCheckStatus.VERIFIED || it.status == CapabilityCheckStatus.NOT_DECLARED } -> CapabilityProbeStatus.SUCCEEDED
        results.any { it.status == CapabilityCheckStatus.UNSUPPORTED } -> CapabilityProbeStatus.PARTIAL
        results.any { it.status == CapabilityCheckStatus.VERIFIED || it.status == CapabilityCheckStatus.NOT_DECLARED } -> CapabilityProbeStatus.PARTIAL
        else -> CapabilityProbeStatus.FAILED
    }

    private fun featureHttpStatus(status: Int) = when {
        status in 400..499 && status !in setOf(401, 403, 408, 429) -> CapabilityCheckStatus.UNSUPPORTED
        status in 500..599 -> CapabilityCheckStatus.UNKNOWN
        else -> CapabilityCheckStatus.FAILED
    }

    private fun probeHttpSummary(status: Int, raw: String): String = when {
        status == 401 || status == 403 -> "auth-failed"
        status == 404 && bodyMentionsModel(raw) -> "model-not-found"
        status == 404 -> "endpoint-unsupported"
        status == 408 -> "timeout"
        status == 429 -> "rate-limited"
        status in 400..499 && bodyMentionsFeature(raw) -> "feature-unsupported"
        status in 400..499 -> "provider-rejected"
        status in 500..599 -> "provider-unavailable"
        else -> "http-$status"
    }

    private fun connectionFailureForHttp(status: Int, raw: String): ProviderConnectionResult.Failure = when (status) {
        401, 403 -> ProviderConnectionResult.Failure(ProviderConnectionErrorCode.AUTH_FAILED, status, retryable = false, charged = true)
        404 -> if (bodyMentionsModel(raw)) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.MODEL_NOT_FOUND, status, retryable = false, charged = true)
        } else {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED, status, retryable = false, charged = true)
        }
        408 -> ProviderConnectionResult.Failure(ProviderConnectionErrorCode.TIMEOUT, status, retryable = true, charged = true)
        429 -> ProviderConnectionResult.Failure(ProviderConnectionErrorCode.RATE_LIMITED, status, retryable = true, charged = true)
        in 400..499 -> ProviderConnectionResult.Failure(
            if (bodyMentionsFeature(raw)) ProviderConnectionErrorCode.FEATURE_UNSUPPORTED else ProviderConnectionErrorCode.PROVIDER_REJECTED,
            status,
            retryable = false,
            charged = true,
        )
        in 500..599 -> ProviderConnectionResult.Failure(ProviderConnectionErrorCode.PROVIDER_REJECTED, status, retryable = true, charged = true)
        else -> ProviderConnectionResult.Failure(ProviderConnectionErrorCode.UNKNOWN, status, retryable = false, charged = true)
    }

    private fun httpFailureMessage(status: Int, raw: String): String = when (status) {
        401, 403 -> ErrorCode.PROVIDER_UNAUTHORIZED.name
        404 -> if (bodyMentionsModel(raw)) ProviderConnectionErrorCode.MODEL_NOT_FOUND.name else ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED.name
        408 -> ProviderConnectionErrorCode.TIMEOUT.name
        429 -> ErrorCode.RATE_LIMITED.name
        in 400..499 -> if (bodyMentionsFeature(raw)) ProviderConnectionErrorCode.FEATURE_UNSUPPORTED.name else ProviderConnectionErrorCode.PROVIDER_REJECTED.name
        in 500..599 -> ProviderConnectionErrorCode.PROVIDER_REJECTED.name
        else -> ErrorCode.UNKNOWN_OUTCOME.name
    }

    private fun bodyMentionsModel(raw: String): Boolean {
        val lower = raw.lowercase()
        return lower.contains("model_not_found") || lower.contains("model not found") || lower.contains("unknown model")
    }

    private fun bodyMentionsFeature(raw: String): Boolean {
        val lower = raw.lowercase()
        return lower.contains("unsupported") || lower.contains("not support") || lower.contains("stream") || lower.contains("tool")
    }

    private suspend fun readBounded(channel: ByteReadChannel): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            require(output.size() + count <= MAX_RESPONSE_BYTES) { "Provider response exceeds limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private suspend fun resolveHeaders(token: String, requestHeaders: Map<String, RequestHeaderValue>): ResolvedHeaders {
        val merged = linkedMapOf<String, RequestHeaderValue>()
        defaultHeaders.forEach { (name, value) -> merged[name] = value }
        requestHeaders.forEach { (name, value) ->
            merged.keys.firstOrNull { it.equals(name, true) }?.let(merged::remove)
            merged[name] = value
        }
        val values = linkedMapOf("Authorization" to "Bearer $token")
        val secrets = mutableListOf<String>()
        val host = URI(baseUrl).host?.lowercase()?.trim('.') ?: throw InvalidHeaderException("Provider URL has no host")
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
                    val resolved = chars.concatToString()
                    chars.fill('\u0000')
                    if (resolved.isEmpty()) throw SecretUnavailableException()
                    validateHeaderValue(resolved)
                    values[name] = resolved
                    secrets += resolved
                }
            }
        }
        return ResolvedHeaders(values, secrets)
    }

    private fun validateHeaderName(name: String) {
        if (name.isBlank() || name.any { it == '\r' || it == '\n' }) throw InvalidHeaderException("Header name is invalid")
        if (name.lowercase() in FORBIDDEN_HEADERS || name.equals("authorization", true) || name.equals("api-key", true)) {
            throw InvalidHeaderException("Header $name is reserved")
        }
    }

    private fun validateHeaderValue(value: String) {
        if (value.any { it == '\r' || it == '\n' }) throw InvalidHeaderException("Header value is invalid")
    }

    private fun invalidConfig(message: String, operationId: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "provider-api",
        operationId = operationId,
    ).asException()

    private fun credentialText(value: String, secrets: List<String>): Boolean =
        SecretRedactor.redact(value, secrets) != value

    private fun credentialJson(value: JsonElement, secrets: List<String>): Boolean = when (value) {
        is JsonPrimitive -> credentialText(value.content, secrets)
        is JsonObject -> value.values.any { credentialJson(it, secrets) }
        is kotlinx.serialization.json.JsonArray -> value.any { credentialJson(it, secrets) }
        else -> false
    }

    private fun elapsedMillis(started: Long): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private data class ResolvedHeaders(val values: Map<String, String>, val secrets: List<String>)
    private data class FeatureProbeResult(
        val summary: String,
        val supported: Boolean,
        val charged: Boolean,
        val httpStatus: Int? = null,
        val status: CapabilityCheckStatus,
    )
    private enum class ProbeFeature { STREAM, TOOLS, IMAGE }
    private enum class ProbeRequirement { TEXT, STREAM, TOOL, IMAGE }
    private class SecretUnavailableException : RuntimeException()
    private class InvalidHeaderException(message: String) : RuntimeException(message)
    private class InvalidConnectionConfigException : RuntimeException()

    companion object {
        private const val PROBE_TOOL_NAME = "mar_probe_noop"
        /**
         * Probe output budgets.  Probes only need a two-word answer or one
         * forced no-op call, so they never spend the profile's full output
         * budget (which users configure at 10k+).  The tool probe keeps a
         * slightly larger bound so a reasoning model can still emit its
         * required trace plus the forced call; both stay far below real
         * limits.  A probe that fails on budget is reported as-is and is
         * never retried with a larger paid request.
         */
        const val CONNECTION_PROBE_MAX_OUTPUT_TOKENS = 64
        const val CAPABILITY_PROBE_MAX_OUTPUT_TOKENS = 128
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val MAX_RESPONSE_BYTES = 8_388_608L
        private const val MAX_LINE_BYTES = 1_048_576
        private const val PROBE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        private val FORBIDDEN_HEADERS = setOf(
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "proxy-authorization",
            "proxy-authenticate", "te", "trailer", "content-type", "accept",
        )

        fun url(base: String, path: String): String {
            val normalized = base.trimEnd('/')
            val suffix = if (path.startsWith('/')) path else "/$path"
            return if (suffix.equals("/responses", ignoreCase = true) && normalized.endsWith("/responses", ignoreCase = true)) {
                normalized
            } else {
                normalized + suffix
            }
        }

        private fun apiRoot(base: String): String = base.trimEnd('/').let { normalized ->
            if (normalized.endsWith("/responses", ignoreCase = true)) {
                normalized.dropLast("/responses".length)
            } else {
                normalized
            }
        }
    }
}
