// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.google

import java.net.URI
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.provider.*
import runtime.mobileagent.provider.openai.DiagnosticJsonSanitizer

/** Native publisher path validation and diagnostic helpers shared by configuration and dispatch. */
internal object GoogleGeminiEndpoint {
    private val MODEL_ID = Regex("(?!models/|projects/|locations/|publishers/|endpoints/)(?:[A-Za-z0-9][A-Za-z0-9._-]{0,99}/)?[A-Za-z0-9][A-Za-z0-9._-]{0,199}")

    fun url(format: ApiFormat, baseUrl: String, modelId: String, stream: Boolean = false): String {
        require(format in setOf(ApiFormat.GOOGLE_AI_STUDIO, ApiFormat.GOOGLE_VERTEX_AI, ApiFormat.GOOGLE_VERTEX_EXPRESS))
        val uri = URI(baseUrl)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "服务地址必须是有效的 Base URL，不能含凭据、查询或片段。"
        }
        require(uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." || it.contains('%') || it.contains('\\') }) {
            "服务地址必须是有效的 Base URL，不能含凭据、查询或片段。"
        }
        val model = modelId.removePrefix("models/")
        require(MODEL_ID.matches(model) && !model.contains("..")) { "请填写有效的 Google 模型 ID。" }
        val base = baseUrl.trimEnd('/')
        when (format) {
            ApiFormat.GOOGLE_AI_STUDIO -> require(Regex(".*/v1(?:beta)?").matches(uri.path.trimEnd('/'))) {
                "Google AI Studio Base URL 需以 /v1beta 或 /v1 结尾。"
            }
            ApiFormat.GOOGLE_VERTEX_AI -> require(Regex(".*/v1(?:beta1?)?/projects/[^/]+/locations/[^/]+/publishers/google").matches(uri.path.trimEnd('/'))) {
                "Google Vertex AI Base URL 需包含 /v1/projects/{project}/locations/{location}/publishers/google。"
            }
            ApiFormat.GOOGLE_VERTEX_EXPRESS -> require(Regex(".*/v1(?:beta1?)?/publishers/google").matches(uri.path.trimEnd('/'))) {
                "Google Vertex Express Base URL 需以 /v1/publishers/google 或 /v1beta1/publishers/google 结尾。"
            }
            else -> error("Unsupported format")
        }
        return "$base/models/$model:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
    }

    fun emptySecretProbeReport(report: CapabilityReport): CapabilityReport = report.copy(
        supportsStream = false, supportsTools = false, supportsImages = false,
        source = "metadata=failed;stream=not-run;tools=not-run;image=not-run;secret=unavailable",
        status = CapabilityProbeStatus.FAILED,
        checks = report.checks.map { if (it.capability == CapabilityCheck.METADATA) it.copy(status = CapabilityCheckStatus.FAILED) else it },
    )

    fun formatProbeSource(checks: List<CapabilityCheckResult>, actual: List<CapabilityCheckResult>): String {
        fun summary(c: CapabilityCheck): String = when (checks.first { it.capability == c }.status) {
            CapabilityCheckStatus.VERIFIED -> "verified"
            CapabilityCheckStatus.NOT_DECLARED -> "not-declared"
            CapabilityCheckStatus.NOT_RUN -> "not-run"
            else -> "failed"
        }
        val meta = when {
            actual.isEmpty() -> "not-run"
            actual.any { it.status == CapabilityCheckStatus.VERIFIED } -> "verified"
            else -> "failed"
        }
        return "metadata=$meta;stream=${summary(CapabilityCheck.STREAM)};tools=${summary(CapabilityCheck.TOOLS)};image=${summary(CapabilityCheck.IMAGE)}"
    }

    fun reportReady(request: ModelRequest, started: Long, payload: String, secrets: List<String>) {
        request.reportDiagnostic(
            ModelDiagnosticStage.REQUEST_READY, ModelDispatchStatus.NOT_DISPATCHED, started, "google.generateContent",
            contentKind = "request.json",
            content = if (request.wantsDiagnosticContent()) DiagnosticJsonSanitizer.sanitize(payload, secrets) else null,
            originalContentChars = payload.length.toLong(),
            originalContentBytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    fun reportStreamLine(request: ModelRequest, started: Long, status: Int, contentType: String, line: String, bytes: Long, secrets: List<String>) {
        val capture = request.wantsDiagnosticContent()
        request.reportDiagnostic(
            ModelDiagnosticStage.STREAM_EVENT, ModelDispatchStatus.RESPONSE_RECEIVED, started, "google.generateContent",
            httpStatus = status, responseContentType = contentType, responseBytes = bytes, eventType = "sse.event",
            contentKind = if (capture) "response.sse.event" else null,
            content = if (capture) DiagnosticJsonSanitizer.sanitizeSseLine(line, secrets) else null,
            originalContentChars = line.length.toLong(),
            originalContentBytes = line.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    fun reportResponseBody(request: ModelRequest, started: Long, status: Int, contentType: String, body: String, secrets: List<String>) {
        val capture = request.wantsDiagnosticContent()
        val bytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        request.reportDiagnostic(
            ModelDiagnosticStage.RESPONSE_BODY, ModelDispatchStatus.RESPONSE_RECEIVED, started, "google.generateContent",
            httpStatus = status, responseContentType = contentType, responseBytes = bytes,
            contentKind = if (capture) "response.json" else null,
            content = if (capture) DiagnosticJsonSanitizer.sanitize(body, secrets) else null,
            originalContentChars = body.length.toLong(),
            originalContentBytes = bytes,
        )
    }
}
