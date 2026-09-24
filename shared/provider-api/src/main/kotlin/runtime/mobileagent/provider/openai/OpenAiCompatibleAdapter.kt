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
import runtime.mobileagent.domain.probeOutputTokenLimit
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.LengthStopKind
import runtime.mobileagent.domain.classifyLengthStop
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
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.InputBudgetEstimate
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelDiagnosticStage
import runtime.mobileagent.provider.ModelDispatchStatus
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.ParameterMerger
import runtime.mobileagent.provider.ProbeConsent
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderConnectionResult
import runtime.mobileagent.provider.ProviderHttpResponseException
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.RequestInputBudget
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.provider.reportDiagnostic
import runtime.mobileagent.provider.wantsDiagnosticContent

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
            // Probes never spend the user's full output budget on a two-word answer.
            // A probe has its own task-local cap: never read the numeric column that
            // the selected mode declares ignored (AUTO stores 0 there).
            val probeOutputTokens = probeOutputTokenLimit(configured.outputLimitMode, configured.outputLimit, CONNECTION_PROBE_MAX_OUTPUT_TOKENS)
            val request = ModelRequest(
                modelId = configured.modelId,
                messages = listOf(ChatMessage(role = "user", text = "Reply with ok.")),
                stream = false,
                parameters = probeParameterLayers(modelParameters, probeOutputTokens),
                operationId = operationId,
                outputTokenLimit = probeOutputTokens,
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
            val metadata = probeMetadata(profile.modelId, resolved)
            // A provider which does not implement the optional per-model
            // metadata route must not prevent the independent chat feature
            // probes. Authentication, transport and malformed responses do
            // stop here so we do not spend additional requests after a hard
            // failure.
            if (!metadata.verified && metadata.status != CapabilityCheckStatus.UNSUPPORTED) {
                return profileReport.copy(
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
            }

            val stream = probeDeclaredFeature(profile, resolved, ProbeFeature.STREAM)
            val tools = probeDeclaredFeature(profile, resolved, ProbeFeature.TOOLS)
            val image = probeDeclaredFeature(profile, resolved, ProbeFeature.IMAGE)
            val featureResults = listOf(stream, tools, image)
            val featureStatus = capabilityProbeStatus(featureResults)
            val overallStatus = if (metadata.status == CapabilityCheckStatus.UNSUPPORTED &&
                featureStatus == CapabilityProbeStatus.SUCCEEDED
            ) {
                // The declared chat features were verified, but the optional
                // metadata route remains unavailable.
                CapabilityProbeStatus.PARTIAL
            } else {
                featureStatus
            }
            profileReport.copy(
                supportsStream = stream.supported,
                supportsTools = tools.supported,
                supportsImages = image.supported,
                source = "metadata=${metadata.summary};stream=${stream.summary};tools=${tools.summary};image=${image.summary}",
                status = overallStatus,
                // A chat capability probe is potentially billable even when
                // the provider later rejects or truncates it.
                charged = metadata.charged || featureResults.any { it.charged },
                operationId = operationId,
                checks = listOf(
                    CapabilityCheckResult(CapabilityCheck.METADATA, metadata.status, metadata.httpStatus),
                    CapabilityCheckResult(CapabilityCheck.STREAM, stream.status, stream.httpStatus),
                    CapabilityCheckResult(CapabilityCheck.TOOLS, tools.status, tools.httpStatus),
                    CapabilityCheckResult(CapabilityCheck.IMAGE, image.status, image.httpStatus),
                ),
            )
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
        val started = System.nanoTime()
        var dispatchStatus = ModelDispatchStatus.NOT_DISPATCHED
        var httpStatus: Int? = null
        var responseContentType: String? = null
        request.reportDiagnostic(ModelDiagnosticStage.REQUEST_VALIDATION, dispatchStatus, started, "chat.completions")
        if (secret.isEmpty()) {
            emit(ModelEvent.Failed(ErrorCode.SECRET_UNAVAILABLE.name))
            request.reportDiagnostic(
                ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions",
                errorCode = ErrorCode.SECRET_UNAVAILABLE.name,
            )
            return@flow
        }

        val token = secret.concatToString()
        val streamState = StreamOutputState()
        try {
            val payload = buildPayload(request, includeImageBytes = true)
            val payloadText = payload.toString()
            val resolved = resolveHeaders(token, request.headers)
            val redactionSecrets = listOf(token) + resolved.secrets
            val streamRedactor = StreamingSecretRedactor(redactionSecrets)
            request.reportDiagnostic(
                ModelDiagnosticStage.REQUEST_READY, dispatchStatus, started, "chat.completions",
                contentKind = "request.json",
                content = if (request.wantsDiagnosticContent()) DiagnosticJsonSanitizer.sanitize(payloadText, redactionSecrets) else null,
                originalContentChars = payloadText.length.toLong(),
                originalContentBytes = payloadText.toByteArray(Charsets.UTF_8).size.toLong(),
            )
            val dispatchAllowed = try {
                request.beforeDispatch()
            } catch (error: Exception) {
                emitTerminalFailure(streamState, ErrorCode.INVALID_CONFIG.name)
                request.reportDiagnostic(
                    ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions",
                    errorCode = ErrorCode.INVALID_CONFIG.name, exception = error,
                )
                return@flow
            }
            if (!dispatchAllowed) {
                emitTerminalFailure(streamState, REQUEST_CANCELLED)
                request.reportDiagnostic(
                    ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions",
                    errorCode = REQUEST_CANCELLED,
                )
                return@flow
            }
            dispatchStatus = ModelDispatchStatus.DISPATCHED
            request.reportDiagnostic(ModelDiagnosticStage.REQUEST_DISPATCH, dispatchStatus, started, "chat.completions")
            http.preparePost(url(baseUrl, "/chat/completions")) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "text/event-stream, application/json")
                    resolved.values.forEach { (name, value) -> append(name, value) }
                }
                setBody(payload.toString())
            }.execute { response ->
                val status = response.status.value
                httpStatus = status
                dispatchStatus = ModelDispatchStatus.RESPONSE_RECEIVED
                responseContentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                request.reportDiagnostic(
                    ModelDiagnosticStage.RESPONSE_HEADERS, dispatchStatus, started, "chat.completions",
                    httpStatus = status, responseContentType = responseContentType,
                )
                if (status >= 400) {
                    // Read the error body so an explicit input-window rejection
                    // is not reported as a generic rejection.  The body itself
                    // is never surfaced verbatim.
                    val raw = runCatching { readBounded(response.bodyAsChannel()) }.getOrDefault("")
                    val usage = reportedUsage(raw)
                    if (usage != null) {
                        streamState.lastUsage = usage
                        emit(usage)
                    }
                    val error = when (status) {
                        401 -> ErrorCode.PROVIDER_UNAUTHORIZED.name
                        429 -> ErrorCode.RATE_LIMITED.name
                        else -> InputOverflowSignal.failureCode(
                        raw,
                        // Canonical code only: consumers must not have to parse a
                        // decorated string, and the HTTP status already travels in
                        // the diagnostic metadata.
                        if (status >= 500) ErrorCode.UNKNOWN_OUTCOME.name else ProviderConnectionErrorCode.PROVIDER_REJECTED.name,
                        )
                    }
                    emitTerminalFailure(
                        streamState,
                        error,
                    )
                    request.reportDiagnostic(ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions", status, error, usage = usage)
                    return@execute
                }
                val responseType = responseContentType.orEmpty()
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
                        val captureLine = request.wantsDiagnosticContent()
                        request.reportDiagnostic(
                            ModelDiagnosticStage.STREAM_EVENT, dispatchStatus, started, "chat.completions",
                            httpStatus = status, responseContentType = responseType,
                            responseBytes = receivedBytes, eventType = "sse.event",
                            contentKind = if (captureLine) "response.sse.event" else null,
                            content = if (captureLine) DiagnosticJsonSanitizer.sanitizeSseLine(line, redactionSecrets) else null,
                            originalContentChars = line.length.toLong(),
                            originalContentBytes = line.toByteArray(Charsets.UTF_8).size.toLong(),
                        )
                        val parsedEvents = OpenAiSse.eventsFromLine(
                            line,
                            toolBuf,
                            redactionSecrets,
                            indexToId,
                        )
                        OpenAiSse.finishReasonFromLine(line)?.let { streamState.finishReason = it }
                        for (event in parsedEvents) {
                            val usage = event as? ModelEvent.Usage
                            request.reportDiagnostic(
                                ModelDiagnosticStage.STREAM_EVENT, dispatchStatus, started, "chat.completions",
                                httpStatus = status, usage = usage, responseContentType = responseType,
                                responseBytes = receivedBytes, eventType = event::class.simpleName,
                            )
                            val terminal = emitRedacted(event, streamRedactor, redactionSecrets, streamState)
                            streamState.diagnosticEvents.forEach { safeEvent ->
                                request.reportDiagnostic(
                                    ModelDiagnosticStage.STREAM_EVENT, dispatchStatus, started, "chat.completions",
                                    httpStatus = status, responseContentType = responseType,
                                    responseBytes = receivedBytes, eventType = safeEvent::class.simpleName,
                                    contentKind = if (request.wantsDiagnosticContent()) "response.sse.model_event" else null,
                                    content = if (request.wantsDiagnosticContent()) {
                                        DiagnosticJsonSanitizer.sanitizeModelEvent(safeEvent, redactionSecrets)
                                    } else null,
                                )
                            }
                            streamState.diagnosticEvents.clear()
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
                        emitTerminalFailure(
                            streamState,
                            streamState.deferredFailure ?: INVALID_RESPONSE_MESSAGE,
                        )
                    }
                    request.reportDiagnostic(
                        ModelDiagnosticStage.RESPONSE_BODY, dispatchStatus, started, "chat.completions",
                        httpStatus = status, errorCode = streamState.terminalError,
                        finishReason = streamState.finishReason, usage = streamState.lastUsage,
                        responseContentType = responseType, responseBytes = receivedBytes,
                    )
                } else {
                    val raw = readBounded(response.bodyAsChannel())
                    emitJsonResponse(raw, redactionSecrets, streamState)
                    request.reportDiagnostic(
                        ModelDiagnosticStage.RESPONSE_BODY, dispatchStatus, started, "chat.completions",
                        httpStatus = status, errorCode = streamState.terminalError,
                        finishReason = streamState.finishReason, usage = streamState.lastUsage,
                        responseContentType = responseType, responseBytes = raw.toByteArray(Charsets.UTF_8).size.toLong(),
                        contentKind = "response.json",
                        content = if (request.wantsDiagnosticContent()) DiagnosticJsonSanitizer.sanitize(raw, redactionSecrets) else null,
                        originalContentChars = raw.length.toLong(),
                        originalContentBytes = raw.toByteArray(Charsets.UTF_8).size.toLong(),
                    )
                }
                request.reportDiagnostic(
                    ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions",
                    httpStatus = status, errorCode = streamState.terminalError,
                    finishReason = streamState.finishReason, usage = streamState.lastUsage,
                    responseContentType = responseType,
                )
            }
        } catch (e: AppException) {
            emitTerminalFailure(streamState, e.error.code.name)
            request.reportDiagnostic(ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions", httpStatus, e.error.code.name, e, responseContentType = responseContentType)
        } catch (_: SecretUnavailableException) {
            emitTerminalFailure(streamState, ErrorCode.SECRET_UNAVAILABLE.name)
            request.reportDiagnostic(ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions", httpStatus, ErrorCode.SECRET_UNAVAILABLE.name, responseContentType = responseContentType)
        } catch (_: InvalidHeaderException) {
            emitTerminalFailure(streamState, ErrorCode.INVALID_CONFIG.name)
            request.reportDiagnostic(ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions", httpStatus, ErrorCode.INVALID_CONFIG.name, responseContentType = responseContentType)
        } catch (error: ProviderHttpResponseException) {
            httpStatus = error.httpStatus
            dispatchStatus = ModelDispatchStatus.RESPONSE_RECEIVED
            val code = when (error.httpStatus) {
                401, 403 -> ErrorCode.PROVIDER_UNAUTHORIZED.name
                429 -> ErrorCode.RATE_LIMITED.name
                // The HTTP-engine interceptor path and the ordinary response
                // path must obey one recovery policy: a 5xx is an unknown
                // outcome (possibly dispatched, never auto-retried), not a
                // decided rejection.
                in 500..599 -> ErrorCode.UNKNOWN_OUTCOME.name
                else -> ProviderConnectionErrorCode.PROVIDER_REJECTED.name
            }
            emitTerminalFailure(streamState, code)
            request.reportDiagnostic(ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions", httpStatus, code, error)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (error: Exception) {
            if (dispatchStatus != ModelDispatchStatus.NOT_DISPATCHED) dispatchStatus = ModelDispatchStatus.UNKNOWN_AFTER_DISPATCH
            emitTerminalFailure(streamState, ErrorCode.UNKNOWN_OUTCOME.name)
            request.reportDiagnostic(ModelDiagnosticStage.TERMINAL, dispatchStatus, started, "chat.completions", httpStatus, ErrorCode.UNKNOWN_OUTCOME.name, error, responseContentType = responseContentType)
        } finally {
            token.toCharArray().fill('\u0000')
        }
    }

    /** Emit text only after cross-delta credential redaction is safe. */
    private suspend fun FlowCollector<ModelEvent>.emitRedacted(
        event: ModelEvent,
        redactor: StreamingSecretRedactor,
        secrets: List<String>,
        state: StreamOutputState,
    ): ModelEvent? {
        return when (event) {
            is ModelEvent.TextDelta -> {
                val safe = redactor.accept(event.text, StreamingSecretRedactor.Channel.TEXT)
                if (safe.isNotEmpty()) {
                    state.hasVisibleOutput = true
                    val safeEvent = ModelEvent.TextDelta(safe)
                    emit(safeEvent)
                    state.diagnosticEvents += safeEvent
                }
                null
            }
            is ModelEvent.RefusalDelta -> {
                val safe = redactor.accept(event.text, StreamingSecretRedactor.Channel.REFUSAL)
                if (safe.isNotEmpty()) {
                    state.hasVisibleOutput = true
                    val safeEvent = ModelEvent.RefusalDelta(safe)
                    emit(safeEvent)
                    state.diagnosticEvents += safeEvent
                }
                null
            }
            is ModelEvent.ReasoningDelta -> {
                val safe = redactor.accept(event.text, StreamingSecretRedactor.Channel.REASONING)
                if (safe.isNotEmpty()) {
                    state.hasReasoningOutput = true
                    val safeEvent = ModelEvent.ReasoningDelta(safe)
                    emit(safeEvent)
                    state.diagnosticEvents += safeEvent
                }
                null
            }
            is ModelEvent.ToolCallDelta -> when (
                val decision = decideToolCallDelta(event.callId, event.name, event.argumentsJson, secrets)
            ) {
                is ToolCallDecision.Terminal -> {
                    // A withheld credential keeps the conservative unknown outcome; a
                    // locally unusable argument payload is a decided invalid response.
                    redactor.discard()
                    emitTerminalFailure(state, decision.code)
                    ModelEvent.Failed(decision.code)
                }
                is ToolCallDecision.Forward -> {
                    val safeEvent =
                        if (decision.argumentsJson == event.argumentsJson) event
                        else event.copy(argumentsJson = decision.argumentsJson)
                    state.hasVisibleOutput = true
                    emit(safeEvent)
                    state.diagnosticEvents += safeEvent
                    null
                }
            }
            is ModelEvent.Failed -> {
                redactor.discard()
                val message = SecretRedactor.redact(event.sanitizedMessage, secrets)
                if (message == ErrorCode.OUTPUT_TRUNCATED.name) {
                    // Providers sometimes send finish_reason=length before a
                    // separate usage-only frame. Keep reading until DONE/EOF so
                    // the final cumulative usage is retained, then emit the
                    // truncation terminal event exactly once.  The label is
                    // refined below from what actually came back.
                    state.deferredFailure = message
                    null
                } else {
                    emitTerminalFailure(state, message)
                    ModelEvent.Failed(message)
                }
            }
            ModelEvent.Completed -> if (state.finishReason == "length") {
                // Classify a length stop *before* flushing the redaction buffer:
                // the buffered tail may hold reasoning text, and presenting it as
                // the answer would both mislabel the failure and leak hidden
                // reasoning into the transcript.
                redactor.discard()
                val failure = lengthFailureCode(state)
                emitTerminalFailure(state, failure)
                ModelEvent.Failed(failure)
            } else {
                // Every channel flushes its own withheld suffix as its own event
                // type: a reasoning prefix can never surface as the answer, and
                // no channel loses characters because another channel advanced.
                redactor.finish().forEach { (channel, safeTail) ->
                    val safeEvent = when (channel) {
                        StreamingSecretRedactor.Channel.REASONING -> ModelEvent.ReasoningDelta(safeTail)
                        StreamingSecretRedactor.Channel.REFUSAL -> ModelEvent.RefusalDelta(safeTail)
                        else -> ModelEvent.TextDelta(safeTail)
                    }
                    if (safeEvent is ModelEvent.ReasoningDelta) state.hasReasoningOutput = true
                    else state.hasVisibleOutput = true
                    emit(safeEvent)
                    state.diagnosticEvents += safeEvent
                }
                if (state.deferredFailure != null) {
                    val failure = state.deferredFailure!!
                    emitTerminalFailure(state, failure)
                    ModelEvent.Failed(failure)
                } else if (!state.hasVisibleOutput) {
                    val failure = reasoningOnlyTerminal(state)
                    emitTerminalFailure(state, failure)
                    ModelEvent.Failed(failure)
                } else {
                    emitUsage(state)
                    state.terminal = true
                    emit(ModelEvent.Completed)
                    ModelEvent.Completed
                }
            }
            is ModelEvent.Usage -> {
                state.latestUsage = event
                state.lastUsage = event
                null
            }
            else -> {
                emit(event)
                null
            }
        }
    }

    private suspend fun FlowCollector<ModelEvent>.emitUsage(state: StreamOutputState) {
        state.latestUsage?.let {
            emit(it)
            state.latestUsage = null
        }
    }

    private suspend fun FlowCollector<ModelEvent>.emitTerminalFailure(
        state: StreamOutputState,
        message: String,
    ) {
        if (state.terminal) return
        state.terminal = true
        state.terminalError = message
        emitUsage(state)
        emit(ModelEvent.Failed(message))
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

    /**
     * Chat Completions never encodes [ChatMessage.providerContinuationItems]
     * (see the private `encodeMessage`), so that provider-private channel must
     * not be reserved. The remaining request shape uses the shared
     * conservative estimate and stays labelled as such.
     */
    override fun estimateInput(request: ModelRequest): InputBudgetEstimate =
        RequestInputBudget.estimate(request, includeProviderContinuation = false)

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
        // The resolved decision wins: keep exactly one output alias in the payload
        // instead of letting a model-level alias collide with an agent-level one.
        val chosenField = request.outputTokenField
        if (chosenField != null) {
            val effectiveBudget = budget
                ?: throw invalidConfig("outputTokenField requires an output budget", request.operationId)
            if (effectiveBudget <= 0) throw invalidConfig("outputTokenLimit must be positive", request.operationId)
            if (chosenField !in listOf("max_tokens", "max_completion_tokens")) {
                throw invalidConfig("Unsupported output token field", request.operationId)
            }
            val normalized = linkedMapOf<String, JsonElement>()
            normalized.putAll(merged)
            normalized.remove("max_tokens")
            normalized.remove("max_completion_tokens")
            normalized[chosenField] = JsonPrimitive(effectiveBudget)
            return JsonObject(normalized)
        }
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

    /**
     * Metadata is useful when a provider implements it, but it is not a
     * prerequisite for the independent chat feature checks.  Some OpenAI
     * compatible hosts document only `GET /models` and return 404/405 for
     * `GET /models/{id}` even when the collection lists the exact id. Retry
     * that unsupported per-id route through `/models` and require an exact
     * id match. Authentication failures and mismatched 200 bodies must not
     * fall through to the list.
     */
    private suspend fun probeMetadata(
        modelId: String,
        headers: ResolvedHeaders,
    ): MetadataProbeResult {
        val encodedModelId = URLEncoder.encode(modelId, Charsets.UTF_8.name()).replace("+", "%20")
        val direct = probeMetadataPath(
            path = "/models/$encodedModelId",
            expectedModelId = modelId,
            headers = headers,
            collection = false,
        )
        return if (
            direct.status == CapabilityCheckStatus.UNSUPPORTED &&
            (direct.httpStatus == 404 || direct.httpStatus == 405)
        ) {
            probeMetadataPath(
                path = "/models",
                expectedModelId = modelId,
                headers = headers,
                collection = true,
            )
        } else {
            direct
        }
    }

    private suspend fun probeMetadataPath(
        path: String,
        expectedModelId: String,
        headers: ResolvedHeaders,
        collection: Boolean,
    ): MetadataProbeResult = try {
        http.prepareGet(url(baseUrl, path)) {
            headers { headers.values.forEach { (name, value) -> append(name, value) } }
        }.execute { response ->
            val status = response.status.value
            if (status in 200..299) {
                val raw = readBounded(response.bodyAsChannel())
                val matches = if (collection) {
                    metadataListMatches(raw, expectedModelId)
                } else {
                    metadataMatches(raw, expectedModelId)
                }
                MetadataProbeResult(
                    summary = if (matches) "verified" else "invalid-response",
                    verified = matches,
                    status = if (matches) CapabilityCheckStatus.VERIFIED else CapabilityCheckStatus.FAILED,
                    httpStatus = status.takeUnless { matches },
                )
            } else {
                val checkStatus = when {
                    status == 404 || status == 405 -> CapabilityCheckStatus.UNSUPPORTED
                    status in 500..599 -> CapabilityCheckStatus.UNKNOWN
                    else -> CapabilityCheckStatus.FAILED
                }
                MetadataProbeResult(
                    summary = "http-$status",
                    verified = false,
                    httpStatus = status,
                    status = checkStatus,
                )
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        MetadataProbeResult(
            summary = "unknown-outcome",
            verified = false,
            status = CapabilityCheckStatus.UNKNOWN,
        )
    }

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
        // One token is enough to prove that an endpoint accepted the request,
        // but it is not enough for a complete no-op function call. Keep the
        // capability probe useful while retaining a fixed, small spend cap and
        // never exceeding the configured model output budget.
            val probeOutputTokens = probeOutputTokenLimit(profile.outputLimitMode, profile.outputLimit, CONNECTION_PROBE_MAX_OUTPUT_TOKENS)
        val modelParameters = runCatching {
            Json.parseToJsonElement(profile.parametersJson).jsonObject
        }.getOrElse {
            return FeatureProbeResult(
                summary = "invalid-model-parameters",
                supported = false,
                charged = false,
                status = CapabilityCheckStatus.FAILED,
            )
        }
        // The probe must exercise the same payload the run will send: the profile's
        // validated model parameters (for example DeepSeek thinking mode), the same
        // image encoding, and never a hard-coded image_url shape.
        val probeRequest = ModelRequest(
            modelId = profile.modelId,
            messages = listOf(
                ChatMessage(
                    role = "user",
                    text = when (feature) {
                        ProbeFeature.IMAGE -> "Describe the image in one word."
                        ProbeFeature.TOOLS ->
                            "Call the $PROBE_TOOL_NAME function exactly once. Do not answer in text."
                        ProbeFeature.STREAM -> "Reply with ok."
                    },
                    images = if (feature == ProbeFeature.IMAGE) {
                        listOf(InlineImage(mediaType = "image/png", base64 = PROBE_PNG))
                    } else {
                        emptyList()
                    },
                ),
            ),
            tools = if (feature == ProbeFeature.TOOLS) {
                listOf(
                    mapOf(
                        "name" to PROBE_TOOL_NAME,
                        "description" to "Call this no-op probe exactly once.",
                        "parameters" to "{\"type\":\"object\",\"properties\":{}}",
                    ),
                )
            } else {
                emptyList()
            },
            stream = feature == ProbeFeature.STREAM,
            parameters = probeParameterLayers(modelParameters, probeOutputTokens),
            operationId = "capability-probe-${feature.name.lowercase()}",
            outputTokenLimit = probeOutputTokens,
        )
        // Defensive: a profile whose stored parameters are rejected by the shared merger
        // must surface as an explicit probe classification, not abort every feature probe.
        val payload = runCatching { buildPayload(probeRequest, includeImageBytes = true) }.getOrElse {
            return FeatureProbeResult(
                summary = "invalid-model-parameters",
                supported = false,
                charged = false,
                status = CapabilityCheckStatus.FAILED,
            )
        }
        val firstBody = if (feature == ProbeFeature.TOOLS) {
            JsonObject(payload + ("tool_choice" to forcedProbeToolChoice()))
        } else {
            payload
        }
        val first = executeFeatureProbe(headers, feature, firstBody)
        val firstStatus = first.httpStatus
        val shapeRejection = feature == ProbeFeature.TOOLS && firstStatus != null &&
            featureHttpStatus(firstStatus) == CapabilityCheckStatus.UNSUPPORTED
        if (!shapeRejection) return first
        // Several OpenAI-compatible hosts reject a forced tool_choice for a model that
        // still supports tool calling (DeepSeek thinking mode answers HTTP 400
        // "Thinking mode does not support this tool_choice"). Retry once without the
        // forced choice so that "probe shape incompatible" is not reported as
        // "the model cannot call tools".
        val retry = executeFeatureProbe(headers, feature, payload)
        return when {
            retry.supported -> retry.copy(summary = "verified-without-forced-tool-choice", charged = true)
            retry.status == CapabilityCheckStatus.UNKNOWN -> retry.copy(summary = "inconclusive", charged = true)
            else -> retry.copy(charged = true)
        }
    }

    private fun forcedProbeToolChoice(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("function", buildJsonObject { put("name", JsonPrimitive(PROBE_TOOL_NAME)) })
    }

    /**
     * Probes keep a fixed, small output cap, but a profile whose validated default is a legal
     * `max_tokens` (for example 1024 against a 4096 request budget) must not be reported as
     * invalid configuration merely because the probe spends less. The single output-limit field
     * the profile already uses is clamped to the probe cap (a cheaper configured default such as
     * 32 is kept), so the field name a provider requires is preserved and every other parameter
     * is carried through unchanged. A profile that sets both fields, or a non-positive /
     * non-numeric value, stays untouched and is rejected by the shared merger exactly as a normal
     * request would be.
     */
    /**
     * Probe parameters never carry the business output aliases: a legitimate large
     * cap must not be judged as an invalid probe configuration, and the probe
     * supplies its own task-local cap through [ModelRequest.outputTokenLimit].
     */
    private fun probeParameterLayers(modelParameters: JsonObject, probeOutputCap: Int): ParameterLayers {
        val hasMaxTokens = modelParameters.containsKey("max_tokens")
        val hasMaxCompletionTokens = modelParameters.containsKey("max_completion_tokens")
        if (hasMaxTokens && hasMaxCompletionTokens) return ParameterLayers(modelParameters = modelParameters)
        val field = when {
            hasMaxCompletionTokens -> "max_completion_tokens"
            hasMaxTokens -> "max_tokens"
            else -> return ParameterLayers(modelParameters = modelParameters)
        }
        val configured = (modelParameters[field] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.content
            ?.toLongOrNull()
            ?: return ParameterLayers(modelParameters = modelParameters)
        if (configured <= 0L) return ParameterLayers(modelParameters = modelParameters)
        val adjusted = LinkedHashMap(modelParameters)
        adjusted[field] = JsonPrimitive(minOf(configured, probeOutputCap.toLong()))
        return ParameterLayers(modelParameters = adjusted)
    }

    private suspend fun executeFeatureProbe(
        headers: ResolvedHeaders,
        feature: ProbeFeature,
        body: JsonObject,
    ): FeatureProbeResult {
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
                    // A 200 response that accepted the tools payload but did
                    // not emit the forced no-op call is inconclusive: the
                    // probe budget is tiny and live tool calling can still
                    // succeed. Do not report that as a failed protocol check.
                    if (feature == ProbeFeature.TOOLS && !supported) {
                        FeatureProbeResult(
                            summary = "inconclusive",
                            supported = false,
                            charged = true,
                            status = CapabilityCheckStatus.UNKNOWN,
                        )
                    } else {
                        FeatureProbeResult(
                            summary = if (supported) "verified" else "invalid-response",
                            supported = supported,
                            charged = true,
                            status = if (supported) CapabilityCheckStatus.VERIFIED else CapabilityCheckStatus.FAILED,
                        )
                    }
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

    private fun metadataListMatches(raw: String, expectedModelId: String): Boolean {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return false
        val models = runCatching {
            when {
                root is JsonObject -> root["data"]?.jsonArray
                root is kotlinx.serialization.json.JsonArray -> root
                else -> null
            }
        }.getOrNull() ?: return false
        return models.any { element ->
            runCatching {
                element.jsonObject["id"]?.jsonPrimitive?.contentOrNull == expectedModelId
            }.getOrDefault(false)
        }
    }

    private fun parseChatProbe(raw: String, requireToolCall: Boolean): Boolean {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
        // Only a non-null error object fails the probe; "error":null is JsonNull,
        // not a missing key, and appears in legitimate success payloads.
        if (root["error"] is JsonObject) return false
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
                    is ModelEvent.RefusalDelta,
                    is ModelEvent.ToolCallDelta,
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
                    is ModelEvent.RefusalDelta,
                    is ModelEvent.ToolCallDelta,
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

    private class StreamOutputState {
        var latestUsage: ModelEvent.Usage? = null
        var lastUsage: ModelEvent.Usage? = null
        var hasVisibleOutput: Boolean = false
        var hasReasoningOutput: Boolean = false
        var deferredFailure: String? = null
        var terminalError: String? = null
        var finishReason: String? = null
        val diagnosticEvents = mutableListOf<ModelEvent>()
        var terminal: Boolean = false
    }

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
        state: StreamOutputState,
    ) {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: run {
                emitTerminalFailure(state, INVALID_RESPONSE_MESSAGE)
                return
            }
        root["usage"]?.let { usageElement ->
            runCatching { usageElement.jsonObject }.getOrNull()?.let { usage ->
                state.latestUsage = OpenAiSse.usageFromJson(usage)
                state.lastUsage = state.latestUsage
            }
        }
        (root["error"] as? JsonObject)?.get("message")?.let { errorMessage ->
            val message = runCatching { errorMessage.jsonPrimitive.contentOrNull }.getOrNull()
                ?: ErrorCode.UNKNOWN_OUTCOME.name
            emitTerminalFailure(state, SecretRedactor.redact(message, redactionSecrets))
            return
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: run {
                emitTerminalFailure(state, INVALID_RESPONSE_MESSAGE)
                return
            }
        val message = choice["message"]?.jsonObject
        messageReasoningText(message)?.let {
            state.hasReasoningOutput = true
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
        val safeToolEvents = mutableListOf<ModelEvent.ToolCallDelta>()
        for (event in toolEvents) {
            when (
                val decision = decideToolCallDelta(
                    event.callId,
                    event.name,
                    event.argumentsJson,
                    redactionSecrets,
                )
            ) {
                // A credential-bearing call is withheld (unknown outcome by
                // design); unusable arguments were never dispatched at all.
                is ToolCallDecision.Terminal -> {
                    emitTerminalFailure(state, decision.code)
                    return
                }
                is ToolCallDecision.Forward -> safeToolEvents +=
                    ModelEvent.ToolCallDelta(event.callId, event.name, decision.argumentsJson)
            }
        }
        val refusal = message?.get("refusal")?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }
        refusal?.takeIf { it.isNotBlank() }?.let {
            val safe = SecretRedactor.redact(it, redactionSecrets)
            if (safe.isNotEmpty()) {
                state.hasVisibleOutput = true
                emit(ModelEvent.RefusalDelta(safe))
            }
        }
        messageContentText(message).forEach { text ->
            val safe = SecretRedactor.redact(text, redactionSecrets)
            if (safe.isNotEmpty()) {
                state.hasVisibleOutput = true
                emit(ModelEvent.TextDelta(safe))
            }
        }
        val finishReason = choice["finish_reason"]?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }
        state.finishReason = finishReason
        if (finishReason == "length") {
            emitTerminalFailure(state, lengthFailureCode(state))
            return
        }
        safeToolEvents.forEach {
            state.hasVisibleOutput = true
            emit(it)
        }
        if (!state.hasVisibleOutput) {
            emitTerminalFailure(state, reasoningOnlyTerminal(state))
            return
        }
        emitUsage(state)
        state.terminal = true
        emit(ModelEvent.Completed)
    }

    /**
     * Classify a provider `finish_reason=length` from what the stream actually
     * produced.  The old adapter collapsed every truncation into
     * `CONTEXT_OVERFLOW`, which told a user whose *input* fit comfortably that
     * their document was too large.
     *
     * - visible text/refusal -> OUTPUT_TRUNCATED;
     * - no visible output but reasoning was actually reported -> the hidden
     *   budget consumed the allowance (REASONING_EXHAUSTED);
     * - no visible output and no reasoning report -> the response is empty and
     *   its budget is unknown, which is not evidence of hidden reasoning.
     */
    private fun lengthFailureCode(state: StreamOutputState): String =
        // One shared rule for every protocol so a truncated page cannot be
        // reported differently depending on which adapter saw it.
        when (classifyLengthStop(
            visibleAnswer = state.hasVisibleOutput,
            reasoningTokens = state.latestUsage?.reasoningTokens,
            outputTokens = state.latestUsage?.outputTokens,
        )) {
            LengthStopKind.OUTPUT_TRUNCATED -> ErrorCode.OUTPUT_TRUNCATED.name
            LengthStopKind.REASONING_EXHAUSTED -> ErrorCode.REASONING_EXHAUSTED.name
            LengthStopKind.EMPTY_RESPONSE -> INVALID_RESPONSE_MESSAGE
        }

    /**
     * A normal-stop terminal with no visible output but observed reasoning is a
     * reasoning-only answer (REASONING_ONLY), not an unrecognizable response and
     * not necessarily an exhausted budget — the provider may simply have placed
     * the whole reply in the reasoning channel.  Only output that never produced
     * reasoning keeps INVALID_RESPONSE.
     */
    private fun reasoningOnlyTerminal(state: StreamOutputState): String =
        if (state.hasReasoningOutput || (state.latestUsage?.reasoningTokens ?: 0) > 0) {
            ErrorCode.REASONING_ONLY.name
        } else {
            INVALID_RESPONSE_MESSAGE
        }

    private fun messageContentText(message: JsonObject?): List<String> {
        val content = message?.get("content") ?: return emptyList()
        val primitive = content as? JsonPrimitive
        if (primitive != null) {
            return listOfNotNull(primitive.contentOrNull?.takeIf { it.isNotBlank() })
        }
        return runCatching {
            content.jsonArray.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
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
        private const val REQUEST_CANCELLED = "REQUEST_CANCELLED"
        private const val INVALID_RESPONSE_MESSAGE = "INVALID_RESPONSE"
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

/**
 * Decision for one tool-call delta about to be handed to the caller.
 *
 * The two ways a call can be refused are deliberately different failures.  A
 * credential found in the call (in its id/name, in the raw argument text, or
 * inside the parsed argument values) keeps the conservative unknown outcome:
 * the provider may already have seen the secret.  Arguments that are not usable
 * JSON — or that are not a JSON object at all — are a local, decided failure:
 * that call was never dispatched anywhere, so calling it an unknown outcome
 * would wrongly force a retry-confirmation dialog onto the user.
 */
internal sealed interface ToolCallDecision {
    /** Safe to dispatch; [argumentsJson] is the exact text to hand over. */
    data class Forward(val argumentsJson: String) : ToolCallDecision

    /** Never dispatched; [code] is the terminal error code to report. */
    data class Terminal(val code: String) : ToolCallDecision
}

internal fun decideToolCallDelta(
    callId: String,
    name: String,
    argumentsJson: String,
    secrets: List<String>,
): ToolCallDecision {
    // A repair is used only to make an otherwise-unusable string parseable; it
    // never completes missing structure, so a truncated payload stays a failure.
    val parsed = ToolArguments.parse(argumentsJson)
    if (containsCredentialText(callId, secrets) ||
        containsCredentialText(name, secrets) ||
        containsCredentialText(argumentsJson, secrets) ||
        (parsed != null && containsCredentialJson(parsed.json, secrets))
    ) {
        return ToolCallDecision.Terminal(ErrorCode.UNKNOWN_OUTCOME.name)
    }
    // A blank call id cannot be paired with a fed-back tool result; keep it a
    // decided invalid response.  Other unusable arguments are still forwarded:
    // the runtime rejects them before dispatch and feeds a bounded INVALID
    // result back so the model can resend corrected arguments.
    if (callId.isBlank()) {
        return ToolCallDecision.Terminal(ProviderConnectionErrorCode.INVALID_RESPONSE.name)
    }
    return ToolCallDecision.Forward(parsed?.text ?: argumentsJson)
}
