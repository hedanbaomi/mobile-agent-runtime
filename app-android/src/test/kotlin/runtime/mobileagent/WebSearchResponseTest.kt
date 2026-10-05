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
