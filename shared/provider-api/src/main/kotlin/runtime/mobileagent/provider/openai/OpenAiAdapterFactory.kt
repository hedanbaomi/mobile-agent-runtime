// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.isGoogleNative
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.google.GoogleGeminiAdapter

/**
 * Shared production selection seam. The historical name is retained for callers;
 * each format uses its native protocol, including Google generateContent.
 */
object OpenAiAdapterFactory {
    fun requestEndpoint(format: ApiFormat, baseUrl: String, modelId: String = "", stream: Boolean = true): String = when (format) {
        ApiFormat.OPENAI_COMPATIBLE -> OpenAiCompatibleAdapter.url(baseUrl, "/chat/completions")
        ApiFormat.OPENAI_RESPONSES -> OpenAiResponsesAdapter.url(baseUrl, "/responses")
        ApiFormat.GOOGLE_AI_STUDIO, ApiFormat.GOOGLE_VERTEX_AI, ApiFormat.GOOGLE_VERTEX_EXPRESS ->
            GoogleGeminiAdapter.requestEndpoint(format, baseUrl, modelId, stream)
    }

    fun validateDraftSave(
        format: ApiFormat,
        baseUrl: String,
        saveModel: Boolean,
        draftRole: ModelRole,
        effectiveModelId: String,
        existingModels: List<ModelProfile> = emptyList(),
        editingModelId: String? = null,
    ) {
        if (!format.isGoogleNative()) return
        val retained = existingModels.filter { it.id != editingModelId }
        val roles = (if (saveModel) listOf(draftRole) else emptyList()) + retained.map { it.role }
        require(roles.all { it == ModelRole.CHAT || it == ModelRole.VISION }) { "Google 原生格式当前支持聊天与视觉模型。" }
        require(!baseUrl.contains("PROJECT_ID")) { "请将 Base URL 中的 PROJECT_ID 替换为 Google Cloud 项目 ID。" }
        val modelIds = (if (saveModel) listOf(effectiveModelId) else listOf("gemini-probe")) + retained.map { it.modelId }
        modelIds.forEach { id -> requestEndpoint(format, baseUrl, id) }
    }

    fun create(
        format: ApiFormat,
        http: HttpClient,
        baseUrl: String,
        headerSecretResolver: HeaderSecretResolver? = null,
        defaultHeaders: Map<String, RequestHeaderValue> = emptyMap(),
    ): ModelAdapter = when (format) {
        ApiFormat.OPENAI_COMPATIBLE -> OpenAiCompatibleAdapter(http, baseUrl, headerSecretResolver, defaultHeaders)
        ApiFormat.OPENAI_RESPONSES -> OpenAiResponsesAdapter(http, baseUrl, headerSecretResolver, defaultHeaders)
        ApiFormat.GOOGLE_AI_STUDIO, ApiFormat.GOOGLE_VERTEX_AI, ApiFormat.GOOGLE_VERTEX_EXPRESS ->
            GoogleGeminiAdapter(http, baseUrl, format, headerSecretResolver, defaultHeaders)
    }
}
