// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import java.net.URI
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.RequestHeaderValue

internal data class ResolvedHeaders(val values: Map<String, String>, val secrets: List<String>) {
    override fun toString(): String = "ResolvedHeaders(values=${values.keys}, secrets=<redacted>)"
}

internal enum class ProbeFeature { STREAM, TOOLS, IMAGE }
internal class SecretUnavailableException : RuntimeException()
internal class InvalidHeaderException(message: String) : RuntimeException(message)

/** Both OpenAI transports share destination binding, merging and secret handling. */
internal suspend fun resolveOpenAiHeaders(
    baseUrl: String,
    token: String,
    defaults: Map<String, RequestHeaderValue>,
    requestHeaders: Map<String, RequestHeaderValue>,
    resolver: HeaderSecretResolver?,
): ResolvedHeaders {
    val merged = linkedMapOf<String, RequestHeaderValue>()
    for ((name, value) in defaults.entries + requestHeaders.entries) {
        merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(merged::remove)
        merged[name] = value
    }
    val host = URI(baseUrl).host?.lowercase()?.trim('.')
        ?: throw InvalidHeaderException("Provider URL has no host")
    val values = linkedMapOf("Authorization" to "Bearer $token")
    val secrets = mutableListOf<String>()
    for ((name, value) in merged) {
        if (name.isBlank() || name.any { it == '\r' || it == '\n' } || name.lowercase() in RESERVED_HEADERS) {
            throw InvalidHeaderException("Header name is invalid or reserved")
        }
        val text = when (value) {
            is RequestHeaderValue.Plain -> value.value
            is RequestHeaderValue.SecretRef -> {
                if (value.ref.isBlank()) throw SecretUnavailableException()
                val chars = (resolver ?: throw SecretUnavailableException()).resolve(host, value.ref)
                try {
                    chars.concatToString().also {
                        if (it.isEmpty()) throw SecretUnavailableException()
                        secrets += it
                    }
                } finally {
                    chars.fill('\u0000')
                }
            }
        }
        if (text.any { it == '\r' || it == '\n' }) throw InvalidHeaderException("Header value is invalid")
        values[name] = text
    }
    return ResolvedHeaders(values, secrets)
}

private val RESERVED_HEADERS = setOf(
    "authorization", "api_key", "api-key", "host", "content-length", "transfer-encoding",
    "connection", "upgrade", "proxy-authorization", "proxy-authenticate", "te", "trailer", "content-type", "accept",
)
