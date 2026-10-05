// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import java.net.URLEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import runtime.mobileagent.domain.WebSearchProvider

/** Closed service protocols: neither the model nor a saved credential can choose an endpoint. */
internal fun webSearchRequest(provider: WebSearchProvider, query: String, count: Int, key: String): Request {
    require(query.isNotBlank() && query.length <= 400 && query.none(Char::isISOControl))
    require(count in 1..10)
    require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 })
    val builder = Request.Builder()
    when (provider) {
        WebSearchProvider.BRAVE -> {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
            builder.url("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$count&safesearch=strict")
                .get().header("X-Subscription-Token", key)
        }
        WebSearchProvider.TAVILY -> {
            val body = buildJsonObject {
                put("query", query); put("max_results", count); put("search_depth", "basic")
                put("topic", "general"); put("auto_parameters", false)
                put("include_answer", false); put("include_raw_content", false); put("include_images", false)
            }
            builder.url("https://api.tavily.com/search").header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        WebSearchProvider.EXA -> {
            val body = buildJsonObject {
                put("query", query); put("numResults", count); put("type", "auto")
                put("contents", buildJsonObject { put("highlights", true) })
            }
            builder.url("https://api.exa.ai/search").header("x-api-key", key)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
    }
    return builder.header("Accept", "application/json").build()
}
