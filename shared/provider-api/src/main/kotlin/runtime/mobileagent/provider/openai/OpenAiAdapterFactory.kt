// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.RequestHeaderValue

/**
 * Single production selection seam for the two supported OpenAI wire
 * protocols. Keeping this in provider-api prevents Android call sites from
 * accidentally aliasing Responses to `/chat/completions`.
 */
object OpenAiAdapterFactory {
    fun requestEndpoint(format: ApiFormat, baseUrl: String): String = when (format) {
        ApiFormat.OPENAI_COMPATIBLE -> OpenAiCompatibleAdapter.url(baseUrl, "/chat/completions")
        ApiFormat.OPENAI_RESPONSES -> OpenAiResponsesAdapter.url(baseUrl, "/responses")
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
    }
}
