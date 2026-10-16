// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentSearchPermission
import runtime.mobileagent.domain.WebSearchProvider
import runtime.mobileagent.skills.HttpPolicy

class WebSearchResponseTest {
    @Test fun deepSeekSearchPrefersSummaryAndNeverReturnsUnexpectedFullContent() {
        val raw = """{"results":[{"title":"A","url":"https://example.com/a","summary":"Long synthetic-token summary","snippet":"Short","content":"do-not-return-body"},{"title":"B","url":"https://example.com/b","summary":"","snippet":"Fallback"}]}"""
        val output = parseWebSearchResponse(WebSearchProvider.DEEPSEEK, raw, 5, listOf("synthetic-token"))
        val results = Json.parseToJsonElement(output).jsonObject["results"]!!.jsonArray
        assertTrue(results[0].jsonObject["snippet"]!!.jsonPrimitive.content.startsWith("Long "))
        assertEquals("Fallback", results[1].jsonObject["snippet"]!!.jsonPrimitive.content)
        assertFalse(output.contains("synthetic-token"))
        assertFalse(output.contains("do-not-return-body"))
        assertTrue(Json.parseToJsonElement(parseWebSearchResponse(WebSearchProvider.DEEPSEEK, """{"results":[]}""", 5)).jsonObject["results"]!!.jsonArray.isEmpty())
    }

    @Test fun fetchPreservesMarkdownAndUrlAndRedactsBeforeClipping() {
        val url = "https://example.com/a%2Fb?sig=abc%2Fdef%3D"
        val raw = buildJsonObject { put("title", "Title synthetic-token"); put("content", "# Heading\n\n[Link](https://example.com/)\nsynthetic-token\n") }.toString()
        val output = parseWebFetchResponse(url, raw, listOf("synthetic-token"))
        val root = Json.parseToJsonElement(output).jsonObject
        assertEquals("deepseek", root["provider"]!!.jsonPrimitive.content)
        assertEquals(url, root["url"]!!.jsonPrimitive.content)
        assertTrue(root["content"]!!.jsonPrimitive.content.startsWith("# Heading\n\n[Link]"))
        assertTrue(root["untrusted"]!!.jsonPrimitive.boolean)
        assertFalse(root["truncated"]!!.jsonPrimitive.boolean)
        assertFalse(output.contains("synthetic-token"))
        val noTitle = Json.parseToJsonElement(parseWebFetchResponse(url, """{"content":"body"}""")).jsonObject
        assertEquals("", noTitle["title"]!!.jsonPrimitive.content)
    }

    @Test fun fetchBoundsEscapedJsonAndMarksTruncationWithoutSplittingUnicode() {
        val url = "https://example.com/" + "x".repeat(8172)
        for ((content, truncated) in listOf("\u0001".repeat(20_000) to true, ("x".repeat(16_383) + "😀end") to true, "synthetic-token".repeat(2000) to false)) {
            val raw = buildJsonObject { put("title", "\\".repeat(1000)); put("content", content) }.toString()
            val output = parseWebFetchResponse(url, raw, listOf("synthetic-token"))
            val root = Json.parseToJsonElement(output).jsonObject
            assertTrue(output.length <= HttpPolicy.MAX_TOOL_OUTPUT_CHARS)
            assertEquals(url, root["url"]!!.jsonPrimitive.content)
            val body = root["content"]!!.jsonPrimitive.content
            assertTrue(body.length <= HttpPolicy.MAX_READ_DOCUMENT_CHARS)
            assertFalse(body.last().isHighSurrogate())
            assertEquals(truncated, root["truncated"]!!.jsonPrimitive.boolean)
            assertFalse(output.contains("synthetic-token"))
        }
    }

    @Test fun fetchRejectsMissingBodyUnsafeOrSecretBearingUrlsAndAdvertisesItsChoice() {
        for (raw in listOf("{}", """{"content":null}""", """{"content":3}""", """{"content":" "}""", """{"error":{"message":"bad"}}""")) {
            assertThrows(IllegalStateException::class.java) { parseWebFetchResponse("https://example.com/", raw) }
        }
        for (url in listOf("https://localhost/", "https://example.com/?token=synthetic-token")) {
            assertThrows(IllegalArgumentException::class.java) { parseWebFetchResponse(url, """{"content":"body"}""", listOf("synthetic-token")) }
        }
        assertTrue(runtimeCapabilitySummary(listOf("web_search", "web_fetch")).contains("web_fetch=available through DeepSeek"))
        assertTrue(runtimeCapabilitySummary(listOf("web_search")).contains("web_fetch=unavailable"))
    }

    @Test fun allProtocolsNormalizeRedactAndRejectPrivateOrCredentialedLinks() {
        for (provider in WebSearchProvider.entries) {
            val results = buildJsonArray {
                add(buildJsonObject {
                    put("title", "Visible synthetic-token"); put("url", "https://example.com/public")
                    put("description", "Brave"); put("content", "Tavily")
                    put("highlights", buildJsonArray { add("Exa synthetic-token") })
                    put("raw_content", "must-not-be-returned")
                })
                listOf("http://example.com", "https://localhost/", "https://10.0.0.1/", "https://u:p@example.com/", "https://example.com:8443/", "https://example.com/#secret").forEach {
                    add(buildJsonObject { put("title", "Unsafe"); put("url", it) })
                }
            }
            val raw = buildJsonObject {
                if (provider == WebSearchProvider.BRAVE) put("web", buildJsonObject { put("results", results) })
                else put("results", results)
            }
            val normalized = parseWebSearchResponse(provider, raw.toString(), 10, listOf("synthetic-token"))
            val root = Json.parseToJsonElement(normalized).jsonObject
            assertEquals(provider.id, root["provider"]!!.jsonPrimitive.content)
            assertTrue(root["untrusted"]!!.jsonPrimitive.boolean)
            assertEquals(1, root["results"]!!.jsonArray.size)
            assertFalse(normalized.contains("synthetic-token"))
            assertFalse(normalized.contains("must-not-be-returned"))
        }
    }

    @Test fun escapedLongResultsStayWithinToolBudgetAndInvalidResponsesAreRejected() {
        val results = buildJsonArray { repeat(10) {
            add(buildJsonObject {
                put("title", "\\".repeat(512)); put("url", "https://example.com/"+"x".repeat(1500))
                put("content", "\u0001".repeat(2048))
            })
        } }
        val output = parseWebSearchResponse(WebSearchProvider.TAVILY, buildJsonObject { put("results", results) }.toString(), 10)
        assertTrue(output.length <= HttpPolicy.MAX_TOOL_OUTPUT_CHARS)
        assertTrue(Json.parseToJsonElement(output).jsonObject["results"]!!.jsonArray.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { parseWebSearchResponse(WebSearchProvider.TAVILY, "{\"error\":\"bad\"}", 5) }
    }

    @Test fun resultUrlBoundariesPreserveExactIdentityAcrossProviders() {
        val base = "https://example.com/search?q="
        val atLimit = base + "x".repeat(2048 - base.length)
        val signed = "https://example.com/a%2Fb?sig=abc%2Fdef%3D&x=1"
        val unicode = "https://example.com/文😀?q=中文"
        val accepted = listOf(atLimit, signed, unicode, "https://example.com/a/../b?q=%25", "https://example.com/?q=%E4%B8%AD")
        val rejected = listOf(
            atLimit + "x", atLimit + "&sig=tail", "https://example.com/" + "x".repeat(2049),
            atLimit.dropLast(1) + "%2", atLimit + "#fragment", "https://user@example.com/",
            "https://localhost/", "https://192.168.1.1/", "https://example.com/#fragment",
            " " + atLimit, "https://example.com/?q=" + "😀".repeat(1100),
        )
        for (provider in WebSearchProvider.entries) {
            for (url in accepted + rejected) {
                val results = buildJsonArray { add(buildJsonObject { put("title", "title"); put("url", url) }) }
                val raw = buildJsonObject {
                    if (provider == WebSearchProvider.BRAVE) put("web", buildJsonObject { put("results", results) })
                    else put("results", results)
                }
                val output = Json.parseToJsonElement(parseWebSearchResponse(provider, raw.toString(), 10)).jsonObject["results"]!!.jsonArray
                if (url in accepted) assertEquals(url, output.single().jsonObject["url"]!!.jsonPrimitive.content)
                else assertTrue(output.isEmpty(), provider.name + ": " + url.take(80))
            }
        }
    }

    @Test fun sensitiveUrlIsDiscardedInsteadOfRewrittenByRedaction() {
        val raw = """{"results":[{"title":"synthetic-private-token","url":"https://example.com/?token=synthetic-private-token"}]}"""
        val output = parseWebSearchResponse(WebSearchProvider.TAVILY, raw, 5, listOf("synthetic-private-token"))
        assertTrue(Json.parseToJsonElement(output).jsonObject["results"]!!.jsonArray.isEmpty())
        assertFalse(output.contains("synthetic-private-token"))
    }

    @Test fun searchPermissionDefaultsOffAndNeedsBothFrozenAndLiveOptIn() {
        val on = AgentSearchPermission.update("{\"other\":7}", true)
        val off = AgentSearchPermission.update(on, false)
        assertTrue(on.contains("\"other\":7"))
        for (invalid in listOf(null, "{}", "broken", "{\"webSearchEnabled\":\"true\"}", "{\"webSearchEnabled\":1}")) {
            assertFalse(AgentSearchPermission.enabled(invalid))
            assertFalse(AgentSearchPermission.allows(invalid, on))
        }
        assertTrue(AgentSearchPermission.allows(on, on))
        assertFalse(AgentSearchPermission.allows(on, off))
        assertFalse(AgentSearchPermission.allows(off, on))
    }
}
