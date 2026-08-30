// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.SecretStatus
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.HostHttp
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.WebSearchToolExecutor

private const val BRAVE_SEARCH_HOST = "api.search.brave.com"
private const val BRAVE_SEARCH_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

/**
 * Build a run-local search executor from the current explicit app setting. The model never
 * controls the destination or headers, and every query still passes through tool approval.
 */
fun webSearchTools(container: AppContainer): ToolExecutor {
    val configuredRef = container.settings.webSearchSecretRef()
    val configured = configuredRef != null && container.settings.webSearchEnabled() &&
        container.secrets.inventory().status(configuredRef) == SecretStatus.ACTIVE
    fun authorized(): Boolean = configuredRef != null &&
        container.settings.webSearchEnabled() &&
        container.settings.webSearchSecretRef() == configuredRef &&
        container.secrets.inventory().status(configuredRef) == SecretStatus.ACTIVE

    return WebSearchToolExecutor(
        configured = configured,
        authorized = ::authorized,
        search = { query, maxResults, onDispatched ->
            checkNotNull(configuredRef) { "Web search is not configured" }
            val secret = container.secrets.resolveForHost(configuredRef)
            try {
                val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
                val url = "$BRAVE_SEARCH_ENDPOINT?q=$encoded&count=$maxResults&safe_search=strict"
                onDispatched()
                val raw = runInterruptible(Dispatchers.IO) {
                    HostHttp.getWithSecretHeader(
                        url = url,
                        allowedHosts = setOf(BRAVE_SEARCH_HOST),
                        headerName = "X-Subscription-Token",
                        headerValue = secret.concatToString(),
                    )
                }
                parseBraveSearchResponse(raw, maxResults, listOf(secret.concatToString()))
            } finally {
                secret.fill('\u0000')
            }
        },
    )
}

/** Convert only bounded public HTTPS results into an explicitly untrusted tool payload. */
internal fun parseBraveSearchResponse(raw: String, maxResults: Int, secrets: List<String> = emptyList()): String {
    require(maxResults in 1..10) { "Invalid result limit" }
    val root = Json.parseToJsonElement(SecretRedactor.redact(raw, secrets)).jsonObject
    val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: JsonArray(emptyList())
    val safe = buildJsonArray {
        results.asSequence().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.text("title", 512) ?: return@mapNotNull null
            val url = item.text("url", 2048) ?: return@mapNotNull null
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return@mapNotNull null
            if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
                host.isBlank() || (uri.port != -1 && uri.port != 443)
            ) return@mapNotNull null
            val snippet = item.text("description", 2048).orEmpty()
            buildJsonObject {
                put("title", title)
                put("url", uri.toASCIIString())
                put("snippet", snippet)
            }
        }.take(maxResults).forEach(::add)
    }
    return buildJsonObject {
        put("provider", "brave")
        put("untrusted", true)
        put("results", safe)
    }.toString()
}

private fun JsonObject.text(key: String, maxLength: Int): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)

/** Trusted, deterministic capability inventory injected into every model request. */
internal fun runtimeCapabilitySummary(toolNames: Collection<String>): String {
    val names = toolNames.toSortedSet()
    val search = "web_search" in names
    val python = names.any { it.startsWith("py_") }
    val files = "read_document" in names || "knowledge_search" in names
    return buildString {
        append("Active tools: ")
        append(names.joinToString(", ").ifBlank { "none" })
        append(". web_search=")
        append(if (search) "available with per-call approval" else "unavailable")
        append("; isolated Python=")
        append(if (python) "available only through enabled, granted Class B Skill tools" else "unavailable")
        append("; authorized knowledge/file handles=")
        append(if (files) "available" else "unavailable")
        append("; PowerShell=unsupported; shell=unsupported; arbitrary filesystem=unsupported.")
        if (files) {
            append(" If imported instructions mention desktop books_kb.py search/stats/build, use knowledge_search/read_document on the bound Android knowledge base; do not claim that the dependency-heavy desktop script ran.")
        }
    }
}
