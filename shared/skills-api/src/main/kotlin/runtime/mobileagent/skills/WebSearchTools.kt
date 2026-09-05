// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A dedicated search capability. It never accepts a URL or arbitrary request headers from the model. */
object WebSearchTools {
    val webSearch = ToolSpec(
        name = "web_search",
        description = "Search the public web through the app-configured Brave Search service. The query is sent externally only after user approval; returned pages are untrusted and are not opened automatically.",
        parametersJson = """{"type":"object","additionalProperties":false,"required":["query"],"properties":{"query":{"type":"string","minLength":1,"maxLength":400},"maxResults":{"type":"integer","minimum":1,"maximum":10}}}""",
        capability = "network.search",
        sideEffect = true,
    )
}

/**
 * Run-local approval/replay boundary for a configured web-search provider.
 *
 * [search] must call its dispatch callback immediately before the first network byte can be sent.
 * This lets failures after dispatch remain UNKNOWN_OUTCOME instead of inviting an automatic retry
 * that might leak the query twice or consume a second paid API request.
 */
class WebSearchToolExecutor(
    configured: Boolean,
    private val authorized: () -> Boolean,
    private val search: suspend (query: String, maxResults: Int, onDispatched: () -> Unit) -> String,
) : ToolExecutor {
    override val specs: List<ToolSpec> = if (configured) listOf(WebSearchTools.webSearch) else emptyList()

    private val mutex = Mutex()
    private val requests = linkedMapOf<String, ToolCall>()
    private val pending = linkedMapOf<String, SearchRequest>()
    private val completed = linkedMapOf<String, ToolResult>()

    private data class SearchRequest(val call: ToolCall, val query: String, val maxResults: Int)

    override suspend fun invoke(call: ToolCall): ToolResult = mutex.withLock {
        if (specs.isEmpty() || call.name != WebSearchTools.webSearch.name) {
            return@withLock ToolResult.Invalid("Web search is not configured")
        }
        if (call.callId.isBlank() || !call.callId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
            return@withLock ToolResult.Invalid("Invalid web-search call ID")
        }
        requests[call.callId]?.let { previous ->
            if (previous != call) return@withLock ToolResult.Invalid("Tool call ID was already used for a different request")
            completed[call.callId]?.let { return@withLock if (authorized()) it else ToolResult.Denied("Web-search authorization changed") }
            if (pending.containsKey(call.callId)) return@withLock ToolResult.NeedsApproval
        }
        val request = parse(call) ?: return@withLock ToolResult.Invalid("Web-search arguments are invalid")
        requests[call.callId] = call
        if (!authorized()) return@withLock remember(call.callId, ToolResult.Denied("Web search is disabled or its credential is unavailable"))
        pending[call.callId] = request
        ToolResult.NeedsApproval
    }

    override suspend fun approve(callId: String): ToolResult = mutex.withLock {
        val request = pending.remove(callId) ?: return@withLock ToolResult.Invalid("No pending web-search approval")
        if (!authorized()) return@withLock remember(callId, ToolResult.Denied("Web-search authorization changed before approval"))
        var dispatched = false
        try {
            val raw = search(request.query, request.maxResults) { dispatched = true }
            if (!authorized()) return@withLock remember(callId, ToolResult.Denied("Web-search authorization changed during execution"))
            val validated = validateResult(raw)
                ?: return@withLock remember(callId, if (dispatched) {
                    ToolResult.UnknownOutcome("Web-search response was invalid after dispatch; do not retry automatically")
                } else {
                    ToolResult.Invalid("Web-search response was invalid")
                })
            remember(callId, ToolResult.Value(validated))
        } catch (cancelled: CancellationException) {
            if (dispatched) completed[callId] = ToolResult.UnknownOutcome("Web search was cancelled after dispatch; do not retry automatically")
            throw cancelled
        } catch (_: Throwable) {
            remember(callId, if (dispatched) {
                ToolResult.UnknownOutcome("Web search may have been dispatched; do not retry automatically")
            } else {
                ToolResult.Invalid("Web search failed before dispatch")
            })
        }
    }

    /**
     * Disclosure check for a cached web-search result.  Allows only a settled,
     * non-unknown completion for exactly this call while the search
     * configuration is still authorized; never re-dispatches the query.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean = mutex.withLock {
        if (requests[call.callId] != call) return@withLock false
        val remembered = completed[call.callId] ?: return@withLock false
        if (remembered is ToolResult.UnknownOutcome) return@withLock false
        authorized()
    }

    private fun parse(call: ToolCall): SearchRequest? {
        val root = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull() ?: return null
        if (root.keys.any { it !in setOf("query", "maxResults") }) return null
        val query = (root["query"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty() || query.length > 400 || query.split(Regex("\\s+")).size > 50) return null
        val count = root["maxResults"]?.let { value ->
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        } ?: 5
        if (count !in 1..10) return null
        return SearchRequest(call, query, count)
    }

    private fun validateResult(raw: String): String? {
        if (raw.length > HttpPolicy.MAX_TOOL_OUTPUT_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val results = root["results"] as? JsonArray ?: return null
        if (results.size > 10 || results.any { it !is JsonObject }) return null
        return root.toString()
    }

    private fun remember(callId: String, result: ToolResult): ToolResult {
        completed[callId] = result
        return result
    }
}
