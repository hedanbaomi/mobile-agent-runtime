// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.google

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.probeOutputTokenLimit
import runtime.mobileagent.domain.stripOutputCapAliases
import runtime.mobileagent.domain.validateOutputCapLayers
import runtime.mobileagent.domain.advancedOutputLimitOverride
import runtime.mobileagent.provider.*
import runtime.mobileagent.provider.openai.SseEventDecoder
import runtime.mobileagent.provider.openai.SseLineReader
import runtime.mobileagent.provider.openai.receiveStreamedReplyInFull

/** Native generateContent transport for AI Studio and both Vertex authentication modes. */
class GoogleGeminiAdapter(
    http: HttpClient,
    private val baseUrl: String,
    private val apiFormat: ApiFormat,
    private val headerSecretResolver: HeaderSecretResolver? = null,
    private val defaultHeaders: Map<String, RequestHeaderValue> = emptyMap(),
) : ModelAdapter {
    // A caller-supplied client may otherwise follow a credential-bearing redirect.
    private val client = http.config { followRedirects = false; expectSuccess = false }

    override suspend fun probe(profile: ModelProfile): CapabilityReport = CapabilityReport(
        profile.modelId, "stream" in profile.capabilities, "tools" in profile.capabilities, "image" in profile.capabilities,
        "profile-only;metadata=not-run;stream=not-run;tools=not-run;image=not-run", java.time.Instant.now().toString(),
        checks = listOf(CapabilityCheck.METADATA, CapabilityCheck.STREAM, CapabilityCheck.TOOLS, CapabilityCheck.IMAGE)
            .map { CapabilityCheckResult(it, CapabilityCheckStatus.NOT_RUN) })

    override suspend fun probe(profile: ModelProfile, secret: CharArray, consent: ProbeConsent, operationId: String): CapabilityReport {
        val report = probe(profile).copy(operationId = operationId)
        if (consent != ProbeConsent.GRANTED) return report
        if (secret.isEmpty()) return GoogleGeminiEndpoint.emptySecretProbeReport(report)
        // Verify each declared feature separately; one successful chat never claims tools or images.
        var charged = false
        var observedSse = false
        val checks = mutableListOf(CapabilityCheckResult(CapabilityCheck.METADATA, CapabilityCheckStatus.NOT_RUN))
        for ((feature, capability) in listOf("stream" to CapabilityCheck.STREAM, "tools" to CapabilityCheck.TOOLS, "image" to CapabilityCheck.IMAGE)) {
            if (feature !in profile.capabilities) {
                checks += CapabilityCheckResult(capability, CapabilityCheckStatus.NOT_DECLARED)
                continue
            }
            val configured = runCatching { Json.parseToJsonElement(profile.parametersJson).jsonObject }.getOrNull()
            if (configured == null) { checks += CapabilityCheckResult(capability, CapabilityCheckStatus.FAILED); continue }
            val cap = probeOutputTokenLimit(profile.outputLimitMode, profile.outputLimit, if (feature == "image") 1024 else 128)
            val parameters = runCatching { probeParameters(configured, cap) }.getOrNull()
            if (parameters == null) { checks += CapabilityCheckResult(capability, CapabilityCheckStatus.FAILED); continue }
            val request = ModelRequest(profile.modelId, listOf(ChatMessage("user", when(feature) {
                "tools" -> "Call probe_ok with no arguments."
                "image" -> "Describe this image in one word."
                else -> "Reply with ok."
            }, images = if (feature == "image") listOf(InlineImage("image/png", PROBE_IMAGE)) else emptyList())),
                tools = if (feature == "tools") listOf(mapOf("name" to "probe_ok", "parameters" to "{\"type\":\"object\",\"properties\":{}}")) else emptyList(),
                stream = feature == "stream", outputTokenLimit = cap, operationId = operationId,
                parameters = parameters)
            val events = mutableListOf<ModelEvent>()
            observedSse = false
            val result = try { withTimeout(15_000) { execute(request, secret, events::add, observedSse = { observedSse = true }, dispatched = { charged = true }) } }
                catch (_: TimeoutCancellationException) { Outcome(ProviderConnectionErrorCode.TIMEOUT) }
            val verified = result.code == null && events.any { it is ModelEvent.Completed } &&
                (feature != "tools" || events.any { it is ModelEvent.ToolCallDelta }) && (feature != "stream" || observedSse)
            checks += CapabilityCheckResult(capability, if (verified) CapabilityCheckStatus.VERIFIED else CapabilityCheckStatus.FAILED, result.httpStatus)
        }
        fun verified(c: CapabilityCheck) = checks.any { it.capability == c && it.status == CapabilityCheckStatus.VERIFIED }
        val actual = checks.filter { it.capability != CapabilityCheck.METADATA && it.status != CapabilityCheckStatus.NOT_DECLARED }
        val metaStatus = when {
            actual.isEmpty() -> CapabilityCheckStatus.NOT_RUN
            actual.any { it.status == CapabilityCheckStatus.VERIFIED } -> CapabilityCheckStatus.VERIFIED
            else -> CapabilityCheckStatus.FAILED
        }
        checks[0] = CapabilityCheckResult(CapabilityCheck.METADATA, metaStatus)
        return report.copy(supportsStream = verified(CapabilityCheck.STREAM), supportsTools = verified(CapabilityCheck.TOOLS),
            supportsImages = verified(CapabilityCheck.IMAGE), charged = charged, checks = checks,
            source = GoogleGeminiEndpoint.formatProbeSource(checks, actual), status = when {
                actual.isEmpty() -> CapabilityProbeStatus.PROFILE_ONLY
                actual.all { it.status == CapabilityCheckStatus.VERIFIED } -> CapabilityProbeStatus.SUCCEEDED
                actual.any { it.status == CapabilityCheckStatus.VERIFIED } -> CapabilityProbeStatus.PARTIAL
                else -> CapabilityProbeStatus.FAILED
            })
    }

    override suspend fun testConnection(profile: ModelProfile, secret: CharArray, operationId: String): ProviderConnectionResult {
        var charged = false
        val start = System.nanoTime()
        return try {
            val configured = Json.parseToJsonElement(profile.parametersJson).jsonObject
            // The minimal test has a separate cap and does not inherit business output aliases.
            val cap = probeOutputTokenLimit(profile.outputLimitMode, profile.outputLimit, 128)
            val request = ModelRequest(profile.modelId, listOf(ChatMessage("user", "Reply with ok.")), stream = false,
                parameters = probeParameters(configured, cap), outputTokenLimit = cap, operationId = operationId)
            val outcome = withTimeout(15_000) { execute(request, secret, {}) { charged = true } }
            outcome.code?.let { ProviderConnectionResult.Failure(it, outcome.httpStatus, retryable(it), charged) }
                ?: ProviderConnectionResult.Success((System.nanoTime() - start) / 1_000_000)
        } catch (_: TimeoutCancellationException) {
            ProviderConnectionResult.Failure(ProviderConnectionErrorCode.TIMEOUT, retryable = true, charged = charged)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ProviderConnectionResult.Failure(ProviderConnectionErrorCode.CONFIG_INVALID, retryable = false, charged = charged) }
    }

    override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
        val started = System.nanoTime()
        var dispatch = ModelDispatchStatus.NOT_DISPATCHED
        request.reportDiagnostic(ModelDiagnosticStage.REQUEST_VALIDATION, dispatch, started, "google.generateContent")
        val result = execute(request, secret, { emit(it) }, started = started) {
            dispatch = ModelDispatchStatus.DISPATCHED
            request.reportDiagnostic(ModelDiagnosticStage.REQUEST_DISPATCH, dispatch, started, "google.generateContent")
        }
        if (dispatch != ModelDispatchStatus.NOT_DISPATCHED && result.httpStatus == null) {
            dispatch = ModelDispatchStatus.UNKNOWN_AFTER_DISPATCH
        }
        val failure = result.code?.let { result.eventCode ?: streamErrorCode(it) }
        failure?.let { emit(ModelEvent.Failed(if (it == "EMPTY_RESPONSE") "INVALID_RESPONSE" else it)) }
        request.reportDiagnostic(
            ModelDiagnosticStage.TERMINAL,
            if (result.httpStatus != null) ModelDispatchStatus.RESPONSE_RECEIVED else dispatch,
            started, "google.generateContent",
            httpStatus = result.httpStatus, errorCode = failure,
            finishReason = result.finishReason, usage = result.usage, responseContentType = result.contentType,
        )
    }

    override fun estimateInput(request: ModelRequest): InputBudgetEstimate = RequestInputBudget.estimate(request.copy(
        messages = request.messages.map { it.copy(providerContinuationItems = it.providerContinuationItems.filter { item -> item.protocol == GOOGLE_PROTOCOL }) }),
        includeProviderContinuation = true, includeChatReasoning = false)

    override fun previewRequest(request: ModelRequest): String = SecretRedactor.redact(GoogleGeminiPayload.build(request, preview = true).toString())

    override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch =
        throw UnsupportedOperationException("Google generateContent is a chat protocol")

    private data class Outcome(
        val code: ProviderConnectionErrorCode? = null,
        val httpStatus: Int? = null,
        val eventCode: String? = null,
        val finishReason: String? = null,
        val usage: ModelEvent.Usage? = null,
        val contentType: String? = null,
    )

    private fun probeParameters(configured: JsonObject, cap: Int): ParameterLayers {
        require(validateOutputCapLayers(configured.toString()) == null)
        val explicit = advancedOutputLimitOverride(configured.toString())?.second?.toInt()
        val clean = Json.parseToJsonElement(stripOutputCapAliases(configured.toString())).jsonObject.toMutableMap()
        clean["maxOutputTokens"] = JsonPrimitive(minOf(explicit ?: cap, cap))
        return ParameterLayers(modelParameters = clean)
    }

    private suspend fun execute(request: ModelRequest, secret: CharArray, emit: suspend (ModelEvent) -> Unit,
        observedSse: () -> Unit = {}, started: Long = System.nanoTime(), dispatched: () -> Unit): Outcome {
        if (secret.isEmpty()) return Outcome(ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE)
        var sent = false
        var parser: GoogleGeminiResponse? = null
        var usageSent = false
        var observedStatus: Int? = null
        var observedContentType: String? = null
        val outcome = try {
            val endpoint = requestEndpoint(apiFormat, baseUrl, request.modelId, request.stream)
            val payload = GoogleGeminiPayload.build(request).toString()
            val token = secret.concatToString()
            require(token.isNotBlank() && token.none { it == '\r' || it == '\n' })
            val resolved = resolveHeaders(request.headers)
            val secrets = listOf(token) + resolved.second
            val auth = if (apiFormat == ApiFormat.GOOGLE_VERTEX_AI) "Authorization" to "Bearer $token" else "x-goog-api-key" to token
            val responseParser = GoogleGeminiResponse(secrets)
            parser = responseParser
            GoogleGeminiEndpoint.reportReady(request, started, payload, secrets)
            val statement = client.preparePost(endpoint) {
                if (request.stream) receiveStreamedReplyInFull(client)
                contentType(ContentType.Application.Json)
                accept(if (request.stream) ContentType.Text.EventStream else ContentType.Application.Json)
                header(auth.first, auth.second)
                resolved.first.forEach { (name, value) -> header(name, value) }
                setBody(payload)
            }
            // Last gate after all endpoint, parameter, image and secret work, immediately before I/O.
            val allowed = try { request.beforeDispatch() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return Outcome(ProviderConnectionErrorCode.CONFIG_INVALID) }
            if (!allowed) return Outcome(ProviderConnectionErrorCode.CONFIG_INVALID, eventCode = "CANCELLED")
            currentCoroutineContext().ensureActive()
            dispatched()
            sent = true
            statement.execute { response ->
                val status = response.status.value
                val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                observedStatus = status
                observedContentType = contentType
                request.reportDiagnostic(
                    ModelDiagnosticStage.RESPONSE_HEADERS, ModelDispatchStatus.RESPONSE_RECEIVED, started,
                    "google.generateContent", httpStatus = status, responseContentType = contentType,
                )
                if (status !in 200..299) {
                    try { responseParser.readUsage(Json.parseToJsonElement(readBounded(response.bodyAsChannel()))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Provider errors remain typed even with an invalid error body. */ }
                    return@execute Outcome(httpCode(status), status)
                }
                if (contentType.contains("text/event-stream")) {
                    observedSse()
                    val decoder = SseEventDecoder()
                    SseLineReader().read(response.bodyAsChannel()) { line, bytes ->
                        GoogleGeminiEndpoint.reportStreamLine(request, started, status, contentType, line, bytes, secrets)
                        decoder.feed(line) { data ->
                            require(data != "[DONE]") { "Invalid Google stream" }
                            responseParser.accept(data).forEach { emit(it) }
                            false
                        }
                        false
                    }
                    require(!decoder.finish()) { "Incomplete event" }
                } else {
                    val body = readBounded(response.bodyAsChannel())
                    GoogleGeminiEndpoint.reportResponseBody(request, started, status, contentType, body, secrets)
                    responseParser.accept(body).forEach { emit(it) }
                }
                val terminal = responseParser.complete()
                responseParser.latestUsage?.let { emit(it); usageSent = true }
                terminal.filterNot { it is ModelEvent.Failed }.forEach { emit(it) }
                val failure = terminal.filterIsInstance<ModelEvent.Failed>().firstOrNull()
                if (failure == null) Outcome(httpStatus = status) else Outcome(ProviderConnectionErrorCode.INVALID_RESPONSE, status, failure.sanitizedMessage)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MissingSecret) { Outcome(ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE) }
        catch (error: ProviderHttpResponseException) { Outcome(httpCode(error.httpStatus), error.httpStatus) }
        catch (_: SSLException) { Outcome(ProviderConnectionErrorCode.TLS_FAILURE, eventCode = if (sent) "UNKNOWN_OUTCOME" else null) }
        catch (_: SocketTimeoutException) { Outcome(ProviderConnectionErrorCode.TIMEOUT, eventCode = if (sent) "UNKNOWN_OUTCOME" else null) }
        catch (_: java.io.IOException) { Outcome(ProviderConnectionErrorCode.NETWORK_UNREACHABLE, eventCode = if (sent) "UNKNOWN_OUTCOME" else null) }
        catch (_: IllegalArgumentException) { Outcome(if (sent) ProviderConnectionErrorCode.INVALID_RESPONSE else ProviderConnectionErrorCode.CONFIG_INVALID, observedStatus) }
        catch (_: Exception) { Outcome(if (sent) ProviderConnectionErrorCode.INVALID_RESPONSE else ProviderConnectionErrorCode.CONFIG_INVALID, observedStatus) }
        if (!usageSent) parser?.latestUsage?.let { emit(it) }
        return outcome.copy(finishReason = parser?.finishReason, usage = parser?.latestUsage, contentType = observedContentType)
    }

    private class MissingSecret : RuntimeException()
    private suspend fun resolveHeaders(requestHeaders: Map<String, RequestHeaderValue>): Pair<Map<String, String>, List<String>> {
        val merged = linkedMapOf<String, RequestHeaderValue>()
        for ((name, value) in defaultHeaders + requestHeaders) {
            merged.keys.firstOrNull { it.equals(name, true) }?.let(merged::remove)
            merged[name] = value
        }
        val result = linkedMapOf<String, String>()
        val secrets = mutableListOf<String>()
        for ((name, value) in merged) {
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && name.lowercase() !in RESERVED_HEADERS)
            val text = when (value) {
                is RequestHeaderValue.Plain -> value.value
                is RequestHeaderValue.SecretRef -> {
                    val chars = (headerSecretResolver ?: throw MissingSecret()).resolve(URI(baseUrl).host.lowercase().trim('.'), value.ref)
                    try { chars.concatToString().also { if (it.isEmpty()) throw MissingSecret(); secrets += it } }
                    finally { chars.fill('\u0000') }
                }
            }
            require(text.none { it == '\r' || it == '\n' })
            result[name] = text
        }
        return result to secrets
    }

    companion object {
        private val RESERVED_HEADERS = setOf("authorization", "x-goog-api-key", "api-key", "api_key", "host", "content-length",
            "transfer-encoding", "connection", "upgrade", "proxy-authorization", "proxy-authenticate", "te", "trailer", "content-type", "accept")

        fun requestEndpoint(format: ApiFormat, baseUrl: String, modelId: String, stream: Boolean = false): String =
            GoogleGeminiEndpoint.url(format, baseUrl, modelId, stream)

        private fun httpCode(status: Int): ProviderConnectionErrorCode = when (status) {
            401, 403 -> ProviderConnectionErrorCode.AUTH_FAILED
            404 -> ProviderConnectionErrorCode.MODEL_NOT_FOUND
            408 -> ProviderConnectionErrorCode.TIMEOUT
            429 -> ProviderConnectionErrorCode.RATE_LIMITED
            in 300..399, 405 -> ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED
            else -> ProviderConnectionErrorCode.PROVIDER_REJECTED
        }
        /** Connection checks retain typed transport codes; chat emits the runtime's durable vocabulary. */
        private fun streamErrorCode(code: ProviderConnectionErrorCode): String = when (code) {
            ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE -> "SECRET_UNAVAILABLE"
            ProviderConnectionErrorCode.AUTH_FAILED -> "PROVIDER_UNAUTHORIZED"
            ProviderConnectionErrorCode.NETWORK_UNREACHABLE, ProviderConnectionErrorCode.TLS_FAILURE -> "NETWORK_UNAVAILABLE"
            ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED, ProviderConnectionErrorCode.MODEL_NOT_FOUND -> "CONFIG_INVALID"
            ProviderConnectionErrorCode.FEATURE_UNSUPPORTED -> "PROVIDER_REJECTED"
            ProviderConnectionErrorCode.UNKNOWN -> "UNKNOWN_OUTCOME"
            else -> code.name
        }
        private fun retryable(code: ProviderConnectionErrorCode) = code in setOf(ProviderConnectionErrorCode.TIMEOUT,
            ProviderConnectionErrorCode.RATE_LIMITED, ProviderConnectionErrorCode.NETWORK_UNREACHABLE)
        private suspend fun readBounded(channel: ByteReadChannel): String {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                require(bytes.size().toLong() + count <= 8_388_608) { "Provider response exceeds limit" }
                bytes.write(buffer, 0, count)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
        private const val PROBE_IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6xS8AAAAASUVORK5CYII="
    }
}
